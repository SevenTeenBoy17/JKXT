package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.scheduling.solver.CpSatScheduleSolver;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixtureFactory;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CpSatScheduleSolverTest {

  private final ScheduleFixtureFactory fixtureFactory = new ScheduleFixtureFactory();
  private final CpSatScheduleSolver solver = new CpSatScheduleSolver();

  @Test
  void feasibleFixtureGeneratesAssignmentsAndFiveFairnessItems() {
    ScheduleFixture fixture = fixtureFactory.feasible();
    ScheduleSolverResult result = solver.solve(fixture);

    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.assignments()).hasSize(
        fixture.examRooms().stream().mapToInt(ScheduleFixture.ExamRoomInput::supervisorsPerRoom).sum()
    );
    assertThat(result.conflictSet()).isEmpty();
    assertThat(result.fairness()).hasSize(5);

    Set<String> teacherTime = new HashSet<>();
    result.assignments().forEach(a ->
        assertThat(teacherTime.add(a.teacherId() + "|" + a.date() + "|" + a.start() + "|" + a.end()))
            .as("H1 same teacher cannot be assigned twice in the same slot")
            .isTrue()
    );
    fixture.examRooms().forEach(room -> {
      long main = result.assignments().stream()
          .filter(a -> a.examId().equals(room.examId()) && a.roomId().equals(room.roomId()) && "main".equals(a.role()))
          .count();
      long total = result.assignments().stream()
          .filter(a -> a.examId().equals(room.examId()) && a.roomId().equals(room.roomId()))
          .count();
      assertThat(main).as("H2 one main proctor").isEqualTo(1);
      assertThat(total).as("H3 required proctor count").isEqualTo(room.supervisorsPerRoom());
    });
  }

  @Test
  void teacherShortageFixtureReturnsReadableConflictSet() {
    ScheduleSolverResult result = solver.solve(fixtureFactory.teacherShortage());

    assertThat(result.status()).isEqualTo("infeasible");
    assertThat(result.errorCode()).isEqualTo("SCHEDULE_INFEASIBLE");
    assertThat(result.assignments()).isEmpty();
    assertThat(result.conflictSet()).hasSize(1);
    assertThat(result.conflictSet().get(0).constraint()).contains("H2");
    assertThat(result.conflictSet().get(0).message()).contains("可用教师不足");
  }

  @Test
  void capacityConflictFixtureReturnsH7ConflictSet() {
    ScheduleSolverResult result = solver.solve(fixtureFactory.capacityConflict());

    assertThat(result.status()).isEqualTo("infeasible");
    assertThat(result.errorCode()).isEqualTo("SCHEDULE_INFEASIBLE");
    assertThat(result.conflictSet()).hasSize(1);
    assertThat(result.conflictSet().get(0).constraint()).isEqualTo("H7");
    assertThat(result.conflictSet().get(0).message()).contains("容量不足");
  }

  @Test
  void benchmark200ExamsCompletesWithinPerformanceBudget() {
    ScheduleSolverResult result = solver.solve(fixtureFactory.benchmark200());

    System.out.printf(
        "P6 benchmark-200 status=%s solverStatus=%s solveMillis=%d assignments=%d%n",
        result.status(),
        result.solverStatus(),
        result.solveMillis(),
        result.assignments().size()
    );
    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.assignments()).hasSize(400);
    assertThat(result.solveMillis()).isLessThan(45_000);
    assertThat(result.stats()).containsEntry("exams", 200);
  }
}
