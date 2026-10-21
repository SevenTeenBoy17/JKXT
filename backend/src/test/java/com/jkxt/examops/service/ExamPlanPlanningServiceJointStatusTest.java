package com.jkxt.examops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobRequest;
import com.jkxt.common.jobs.AsyncJobLeaseService;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import com.jkxt.common.jobs.AsyncJobLeaseService.Kind;
import com.jkxt.examops.joint.DeterministicJointPlanner;
import com.jkxt.examops.joint.JointPlanningModel.DemandWindow;
import com.jkxt.examops.joint.JointPlanningModel.ExamDemand;
import com.jkxt.examops.joint.JointPlanningModel.Room;
import com.jkxt.examops.joint.JointPlanningModel.Seat;
import com.jkxt.examops.joint.JointPlanningModel.Snapshot;
import com.jkxt.examops.joint.JointPlanningModel.Teacher;
import com.jkxt.examops.joint.JointPlanningModel.TeacherStatus;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.LoadedSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.function.Supplier;

/** Real planner and orchestration, with only storage/transaction boundaries replaced; no database. */
class ExamPlanPlanningServiceJointStatusTest {

  @Test
  void persistsUnprovenSearchAsRetryableFailureNotInfeasibleWithoutPartialAssignments() throws Exception {
    List<Write> writes = execute(roomPlacementSnapshot(true));
    Write terminal = terminalWrite(writes);
    assertThat(terminal.args()[0]).isEqualTo("FAILED");
    assertThat(terminal.args()[1]).isEqualTo("UNRESOLVED");
    JsonNode stats = new ObjectMapper().readTree((String) terminal.args()[3]);
    assertThat(stats.path("solverStatus").asText()).isEqualTo("UNKNOWN");
    assertThat(stats.path("infeasibilityProven").asBoolean()).isFalse();
    assertThat(stats.path("partialAssignmentsExposed").asBoolean()).isFalse();
    assertThat(stats.path("dutyAssignments").asInt()).isZero();
    assertThat((String) terminal.args()[2]).contains("PLANNING_SEARCH_UNRESOLVED");
    assertThat((String) terminal.args()[4]).matches("[a-f0-9]{64}");
    assertThat(terminal.args()[5]).isEqualTo(1);
    assertThat(writes.stream().filter(write -> write.sql().contains("update exam_plan_revision")))
        .singleElement().satisfies(write -> assertThat(write.args()[0]).isEqualTo("FAILED"));
    assertThat(writes).noneSatisfy(write -> assertThat(write.sql()).contains("insert into"));
    assertThat(writes).noneSatisfy(write -> assertThat(write.sql()).contains("delete from"));
  }

  @Test
  void retainsInfeasibleOnlyWhenOriginalInputHasAnIndependentNecessaryConditionFailure() throws Exception {
    Write terminal = terminalWrite(execute(roomPlacementSnapshot(false)));
    assertThat(terminal.args()[0]).isEqualTo("INFEASIBLE");
    assertThat(terminal.args()[1]).isEqualTo("DIAGNOSTICS");
    JsonNode stats = new ObjectMapper().readTree((String) terminal.args()[3]);
    assertThat(stats.path("solverStatus").asText()).isEqualTo("INFEASIBLE");
    assertThat(stats.path("infeasibilityProven").asBoolean()).isTrue();
  }

  private List<Write> execute(Snapshot snapshot) {
    List<Write> writes = new ArrayList<>();
    JdbcTemplate jdbc = new JdbcTemplate() {
      @Override
      public int update(String sql, Object... args) {
        writes.add(new Write(sql, args.clone()));
        return 1;
      }

      @Override
      public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        assertThat(sql).contains("select cancel_requested");
        return requiredType.cast(false);
      }
    };
    JointPlanningSnapshotLoader loader = mock(JointPlanningSnapshotLoader.class);
    UUID org = new UUID(0, 100), plan = new UUID(0, 101), revision = new UUID(0, 102);
    when(loader.load(org, revision)).thenReturn(new LoadedSnapshot(snapshot, Map.of(), Map.of(),
        Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of()));
    AsyncJobLeaseService leases = mock(AsyncJobLeaseService.class);
    when(leases.fenced(any(), any())).thenAnswer(invocation -> invocation.getArgument(1, Supplier.class).get());
    doAnswer(invocation -> { invocation.getArgument(1, Runnable.class).run(); return null; })
        .when(leases).fencedRun(any(), any());
    doAnswer(invocation -> { invocation.getArgument(1, Runnable.class).run(); return null; })
        .when(leases).finish(any(), any());
    when(leases.valid(any())).thenReturn(true);
    ExamPlanPlanningService service = new ExamPlanPlanningService(jdbc, new ObjectMapper(),
        mock(DeterministicInvigilatorAllocator.class), mock(ExamOperationsHardConstraints.class),
        loader, Runnable::run, leases, mock(PublishedResourceBaseline.class));

    // Stable orchestration seam: use the production planner, not a mocked Result/status.
    ReflectionTestUtils.invokeMethod(service, "executeJoint", org, plan, revision,
        new UUID(0, 103), new UUID(0, 104), new PlanningJobRequest("JOINT", 0L, true, 120),
        new Lease(org, Kind.PLANNING, new UUID(0, 103), new UUID(0, 105), 1));

    Write running = writes.stream().filter(write -> write.sql().contains("input_hash=?"))
        .findFirst().orElseThrow();
    assertThat(running.args()[0]).isEqualTo(new DeterministicJointPlanner().canonicalInputHash(snapshot, 0));
    assertThat(running.args()[2]).isEqualTo(DeterministicJointPlanner.SOLVER_VERSION);
    return writes;
  }

  private Write terminalWrite(List<Write> writes) {
    return writes.stream().filter(write -> write.sql().contains("result_hash=?"))
        .findFirst().orElseThrow();
  }

  private Snapshot roomPlacementSnapshot(boolean hasLab) {
    Instant start = Instant.parse("2028-06-05T00:00:00Z");
    DemandWindow window = new DemandWindow(new UUID(0, 1), start, start.plusSeconds(3600), 0, false);
    ExamDemand generic = new ExamDemand(new UUID(0, 2), "A-GENERIC", "Generic", null,
        60, 1, 1, 1, false, Set.of(), Set.of(), List.of(window));
    ExamDemand laboratory = new ExamDemand(new UUID(0, 3), "Z-LAB", "Lab", null,
        60, 1, 1, 1, false, Set.of("LAB"), Set.of(), List.of(window));
    Room plain = room(4, 1, Set.of());
    Room lab = room(5, 2, hasLab ? Set.of("LAB") : Set.of());
    return new Snapshot("status-test", List.of(generic, laboratory), List.of(), List.of(), List.of(),
        List.of(plain, lab), List.of(teacher(6), teacher(7)), List.of(), List.of(), List.of(), List.of());
  }

  private Room room(long id, int capacity, Set<String> features) {
    List<Seat> seats = new ArrayList<>();
    for (int index = 0; index < capacity; index++) {
      seats.add(new Seat(new UUID(id, index), "S" + index, 1, index + 1, Set.of(), true));
    }
    return new Room(new UUID(0, id), "R" + id, "MAIN", capacity, features, List.of(), List.of(),
        seats, true, false);
  }

  private Teacher teacher(long id) {
    return new Teacher(new UUID(0, id), "T" + id, "Teacher", Set.of("CHIEF", "INVIGILATOR"), null,
        2, 0, "MAIN", TeacherStatus.INCLUDED, List.of(), List.of(), Set.of(), Set.of(),
        Set.of(), Set.of(), Set.of(), Set.of(), 35, false, 0);
  }

  private record Write(String sql, Object[] args) { }

}
