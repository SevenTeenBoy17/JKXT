package com.jkxt.examops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherWindow;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicInvigilatorAllocatorTest {

  private final DeterministicInvigilatorAllocator allocator = new DeterministicInvigilatorAllocator();

  @Test
  void dailyCapGreedyDeadEndMustNotClaimAnExistingMorningBEveningASolutionIsInfeasible() {
    LocalDate day = LocalDate.of(2028, 6, 5);
    TeacherInput a = new TeacherInput(id("daily-counterexample-a"), "A", "A", null, false,
        Set.of(), Set.of(), List.of(), List.of());
    TeacherInput b = new TeacherInput(id("daily-counterexample-b"), "B", "B", null, false,
        Set.of(), Set.of(), List.of(),
        List.of(new TeacherWindow(day.atTime(8, 0), day.atTime(10, 0))));
    RoomInput room = new RoomInput(id("daily-counterexample-room"), "R", "Room", 30);
    ExamRoomInput morning = new ExamRoomInput(id("daily-counterexample-morning"), Set.of(),
        room.id(), "Morning", "G1", day, LocalTime.of(8, 0), LocalTime.of(10, 0), 10, 1);
    ExamRoomInput evening = new ExamRoomInput(id("daily-counterexample-evening"), Set.of(),
        room.id(), "Evening", "G1", day, LocalTime.of(16, 0), LocalTime.of(18, 0), 10, 1);
    var roles = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add("INVIGILATOR").add("CHIEF");
    var profiles = Map.of(
        a.id(), new ExamOperationsHardConstraints.TeacherEligibility(roles, null, false, null, 1, null),
        b.id(), new ExamOperationsHardConstraints.TeacherEligibility(roles, null, false, null, 1, null));
    ScheduleFixture fixture = new ScheduleFixture("daily-cap-counterexample", "Synthetic A/B witness", 42L,
        List.of(a, b), List.of(room), List.of(morning, evening));

    // Independent witness, not a repetition of allocator selection: B covers morning, A evening.
    assertThat(b.dutyWindows()).containsExactly(new TeacherWindow(day.atTime(8, 0), day.atTime(10, 0)));
    assertThat(a.dutyWindows()).isEmpty();
    assertThat(b.id()).isNotEqualTo(a.id());
    assertThat(profiles.values()).allSatisfy(profile -> {
      assertThat(profile.maxDailySessions()).isEqualTo(1);
      assertThat(profile.qualifications().toString()).contains("INVIGILATOR", "CHIEF");
      var constraints = new ExamOperationsHardConstraints();
      assertThat(constraints.teacherEligible("CHIEF", constraints.defaultDutyQualification("CHIEF"), profile, day)).isTrue();
    });

    ScheduleSolverResult result = allocator.allocate(fixture, profiles);
    assertThat(result.status()).as("A greedy daily-cap dead end is not a proof of infeasibility")
        .isIn("succeeded", "failed");
    if (result.feasible()) {
      assertThat(result.assignments()).hasSize(2);
      assertThat(result.assignments().stream().filter(x -> x.examId().equals(morning.examId())))
          .singleElement().satisfies(x -> assertThat(x.teacherId()).isEqualTo(b.id()));
      assertThat(result.assignments().stream().filter(x -> x.examId().equals(evening.examId())))
          .singleElement().satisfies(x -> assertThat(x.teacherId()).isEqualTo(a.id()));
      assertThat(result.assignments()).allSatisfy(x -> assertThat(x.role()).isEqualTo("main"));
    } else {
      assertThat(result.solverStatus()).isEqualTo("DETERMINISTIC_UNKNOWN");
      assertThat(result.errorCode()).isEqualTo("DETERMINISTIC_ALLOCATION_EXHAUSTED");
      assertThat(result.assignments()).isEmpty();
    }
  }

  @Test
  void assignsSixHundredPostsDeterministicallyWithoutOverlapOrLeaveViolation() {
    LocalDate firstDay = LocalDate.of(2028, 6, 1);
    List<TeacherInput> teachers = new ArrayList<>();
    for (int index = 0; index < 150; index++) {
      UUID id = id("teacher-" + index);
      List<TeacherWindow> leave = index < 10
          ? List.of(new TeacherWindow(firstDay.atTime(8, 0), firstDay.atTime(10, 0)))
          : List.of();
      teachers.add(new TeacherInput(id, "T%03d".formatted(index), "Teacher " + index,
          null, false, Set.of(), Set.of(), leave, List.of()));
    }
    List<RoomInput> rooms = new ArrayList<>();
    for (int index = 0; index < 60; index++) {
      rooms.add(new RoomInput(id("room-" + index), "R%03d".formatted(index), "Room " + index, 60));
    }
    List<ExamRoomInput> slots = new ArrayList<>();
    for (int day = 0; day < 10; day++) {
      for (int room = 0; room < 30; room++) {
        slots.add(new ExamRoomInput(id("exam-" + day + "-" + room), Set.of(), rooms.get(room).id(),
            "Subject", "G1", firstDay.plusDays(day), LocalTime.of(8, 0), LocalTime.of(10, 0),
            30, 2, Set.of()));
      }
    }
    ScheduleFixture fixture = new ScheduleFixture(
        "large-invigilator-only", "Large deterministic fixture", 42L,
        List.copyOf(teachers), List.copyOf(rooms), List.copyOf(slots));

    ScheduleSolverResult first = allocator.allocate(fixture);
    ScheduleSolverResult replay = allocator.allocate(fixture);

    assertThat(first.feasible()).isTrue();
    assertThat(first.assignments()).hasSize(600).containsExactlyElementsOf(replay.assignments());
    assertThat(first.solverStatus()).isEqualTo("DETERMINISTIC_FEASIBLE");
    assertThat(first.solveMillis()).isLessThan(5_000L);
    assertThat(first.assignments().stream()
        .filter(assignment -> assignment.date().equals(firstDay))
        .map(ScheduleSolverResult.Assignment::teacherId))
        .doesNotContainAnyElementsOf(teachers.subList(0, 10).stream().map(TeacherInput::id).toList());

    Map<UUID, List<ScheduleSolverResult.Assignment>> byTeacher = new HashMap<>();
    first.assignments().forEach(assignment -> byTeacher
        .computeIfAbsent(assignment.teacherId(), ignored -> new ArrayList<>()).add(assignment));
    byTeacher.values().forEach(assignments -> {
      for (int left = 0; left < assignments.size(); left++) {
        for (int right = left + 1; right < assignments.size(); right++) {
          ScheduleSolverResult.Assignment a = assignments.get(left);
          ScheduleSolverResult.Assignment b = assignments.get(right);
          if (!a.date().equals(b.date())) continue;
          assertThat(a.start().isBefore(b.end()) && a.end().isAfter(b.start())).isFalse();
        }
      }
    });
  }

  @Test
  void reportsGreedyExhaustionAsUnknownRatherThanClaimingGlobalInfeasibility() {
    LocalDate day = LocalDate.of(2028, 6, 1);
    TeacherInput flexible = new TeacherInput(id("a-flexible"), "A", "Flexible", null, false,
        Set.of(), Set.of(), List.of(), List.of());
    TeacherInput earlyOnly = new TeacherInput(id("b-early"), "B", "Early only", null, false,
        Set.of(), Set.of(), List.of(),
        List.of(new TeacherWindow(day.atTime(8, 0), day.atTime(10, 0))));
    RoomInput room = new RoomInput(id("room-unknown"), "R-U", "Unknown room", 30);
    ScheduleFixture fixture = new ScheduleFixture("greedy-unknown", "Must not overclaim", 42L,
        List.of(flexible, earlyOnly), List.of(room), List.of(
            new ExamRoomInput(id("exam-early"), Set.of(), room.id(), "S1", "G1", day,
                LocalTime.of(8, 0), LocalTime.of(10, 0), 10, 1, Set.of()),
            new ExamRoomInput(id("exam-overlap"), Set.of(), room.id(), "S2", "G1", day,
                LocalTime.of(9, 0), LocalTime.of(11, 0), 10, 1, Set.of())));

    ScheduleSolverResult result = allocator.allocate(fixture);

    assertThat(result.feasible()).isFalse();
    assertThat(result.solverStatus()).isEqualTo("DETERMINISTIC_UNKNOWN");
    assertThat(result.conflictSet()).extracting(ScheduleSolverResult.ConflictItem::code)
        .containsExactly("DETERMINISTIC_ALLOCATION_EXHAUSTED");
  }

  @Test
  void requiresExplicitChiefEligibilityAndAcceptsQualifiedChief() throws Exception {
    LocalDate day = LocalDate.of(2028, 6, 2);
    TeacherInput teacher = new TeacherInput(id("qualification-teacher"), "T-Q", "Teacher Q", null,
        false, Set.of(), Set.of(), List.of(), List.of());
    RoomInput room = new RoomInput(id("qualification-room"), "R-Q", "Qualification room", 30);
    ScheduleFixture fixture = new ScheduleFixture("qualification", "Qualification", 42L,
        List.of(teacher), List.of(room), List.of(
            new ExamRoomInput(id("qualification-exam"), Set.of(), room.id(), "S", "G1", day,
                LocalTime.of(8, 0), LocalTime.of(10, 0), 10, 1, Set.of())));

    var ordinary = Map.of(teacher.id(), new ExamOperationsHardConstraints.TeacherEligibility(
        new com.fasterxml.jackson.databind.ObjectMapper().readTree("[\"INVIGILATOR\"]"), null, false));
    ScheduleSolverResult rejected = allocator.allocate(fixture, ordinary);
    assertThat(rejected.feasible()).isFalse();
    assertThat(rejected.conflictSet()).extracting(ScheduleSolverResult.ConflictItem::code)
        .containsExactly("DUTY_QUALIFICATION_SHORTAGE");

    var chief = Map.of(teacher.id(), new ExamOperationsHardConstraints.TeacherEligibility(
        new com.fasterxml.jackson.databind.ObjectMapper().readTree("[\"INVIGILATOR\",\"CHIEF\"]"), null, false));
    assertThat(allocator.allocate(fixture, chief).feasible()).isTrue();
  }

  @Test
  void enforcesQualificationExpiryAndDailySessionLimit() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    LocalDate day = LocalDate.of(2028, 6, 3);
    TeacherInput teacher = new TeacherInput(id("limit-teacher"), "T-L", "Teacher L", null,
        false, Set.of(), Set.of(), List.of(), List.of());
    RoomInput room = new RoomInput(id("limit-room"), "R-L", "Limit room", 30);
    ScheduleFixture oneSlot = new ScheduleFixture("expiry", "Expiry", 42L,
        List.of(teacher), List.of(room), List.of(
            new ExamRoomInput(id("expiry-exam"), Set.of(), room.id(), "S", "G1", day,
                LocalTime.of(8, 0), LocalTime.of(10, 0), 10, 1, Set.of())));
    var expired = Map.of(teacher.id(), new ExamOperationsHardConstraints.TeacherEligibility(
        mapper.readTree("[\"INVIGILATOR\",\"CHIEF\"]"), null, false,
        day.minusDays(1), 4, null));
    assertThat(allocator.allocate(oneSlot, expired).conflictSet())
        .extracting(ScheduleSolverResult.ConflictItem::code)
        .containsExactly("DUTY_QUALIFICATION_SHORTAGE");

    ScheduleFixture twoSlots = new ScheduleFixture("daily-limit", "Daily limit", 42L,
        List.of(teacher), List.of(room), List.of(
            new ExamRoomInput(id("limit-exam-1"), Set.of(), room.id(), "S1", "G1", day,
                LocalTime.of(8, 0), LocalTime.of(10, 0), 10, 1, Set.of()),
            new ExamRoomInput(id("limit-exam-2"), Set.of(), room.id(), "S2", "G1", day,
                LocalTime.of(10, 30), LocalTime.of(12, 30), 10, 1, Set.of())));
    var capped = Map.of(teacher.id(), new ExamOperationsHardConstraints.TeacherEligibility(
        mapper.readTree("[\"INVIGILATOR\",\"CHIEF\"]"), null, false, null, 1, null));
    assertThat(allocator.allocate(twoSlots, capped).conflictSet())
        .extracting(ScheduleSolverResult.ConflictItem::code)
        .containsExactly("TEACHER_DAILY_LIMIT_EXCEEDED");

    ScheduleSolverResult crossPlanLimit = allocator.allocate(
        oneSlot, capped, Map.of(), Map.of(), Map.of(
            teacher.id(), List.of(new DeterministicInvigilatorAllocator.ExistingDuty(
                day.atTime(11, 0), day.atTime(12, 0)))));
    assertThat(crossPlanLimit.feasible()).isFalse();
    assertThat(crossPlanLimit.conflictSet())
        .extracting(ScheduleSolverResult.ConflictItem::code)
        .containsExactly("TEACHER_DAILY_LIMIT_EXCEEDED");
  }

  @Test
  void usesPreferredCampusOnlyAsATieBreakPreference() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    LocalDate day = LocalDate.of(2028, 6, 4);
    TeacherInput firstByNumber = new TeacherInput(id("campus-a"), "A", "Teacher A", null,
        false, Set.of(), Set.of(), List.of(), List.of());
    TeacherInput campusPreferred = new TeacherInput(id("campus-z"), "Z", "Teacher Z", null,
        false, Set.of(), Set.of(), List.of(), List.of());
    RoomInput room = new RoomInput(id("campus-room"), "R-C", "Campus room", 30);
    ScheduleFixture fixture = new ScheduleFixture("campus", "Campus preference", 42L,
        List.of(firstByNumber, campusPreferred), List.of(room), List.of(
            new ExamRoomInput(id("campus-exam"), Set.of(), room.id(), "S", "G1", day,
                LocalTime.of(8, 0), LocalTime.of(10, 0), 10, 1, Set.of())));
    var profiles = Map.of(
        firstByNumber.id(), new ExamOperationsHardConstraints.TeacherEligibility(
            mapper.readTree("[\"INVIGILATOR\",\"CHIEF\"]"), null, false, null, 4, "SOUTH"),
        campusPreferred.id(), new ExamOperationsHardConstraints.TeacherEligibility(
            mapper.readTree("[\"INVIGILATOR\",\"CHIEF\"]"), null, false, null, 4, "NORTH"));

    ScheduleSolverResult result = allocator.allocate(
        fixture, profiles, Map.of(room.id(), "CAMPUS_NORTH"));

    assertThat(result.feasible()).isTrue();
    assertThat(result.assignments()).extracting(ScheduleSolverResult.Assignment::teacherId)
        .containsExactly(campusPreferred.id());
  }

  private UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
