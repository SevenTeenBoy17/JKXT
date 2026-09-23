package com.jkxt.examops.joint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.jkxt.examops.joint.JointPlanningModel.CampusTravel;
import com.jkxt.examops.joint.JointPlanningModel.Candidate;
import com.jkxt.examops.joint.JointPlanningModel.DemandWindow;
import com.jkxt.examops.joint.JointPlanningModel.Diagnostic;
import com.jkxt.examops.joint.JointPlanningModel.Enrollment;
import com.jkxt.examops.joint.JointPlanningModel.ExamDemand;
import com.jkxt.examops.joint.JointPlanningModel.PlanningControl;
import com.jkxt.examops.joint.JointPlanningModel.Result;
import com.jkxt.examops.joint.JointPlanningModel.ResultStatus;
import com.jkxt.examops.joint.JointPlanningModel.Room;
import com.jkxt.examops.joint.JointPlanningModel.Seat;
import com.jkxt.examops.joint.JointPlanningModel.Snapshot;
import com.jkxt.examops.joint.JointPlanningModel.SpecialRequirement;
import com.jkxt.examops.joint.JointPlanningModel.Teacher;
import com.jkxt.examops.joint.JointPlanningModel.TeacherStatus;
import com.jkxt.examops.joint.JointPlanningModel.TimeInterval;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DeterministicJointPlannerTest {

  private static final Instant BASE = Instant.parse("2026-06-15T00:00:00Z");
  private final DeterministicJointPlanner planner = new DeterministicJointPlanner();

  @Test
  void plansSmallFeasibleSnapshotWithCompleteCoverage() {
    ExamDemand math = demand("math", 0, 2, Set.of(), Set.of(id("class-a")));
    ExamDemand physics = demand("physics", 2, 2, Set.of(), Set.of(id("class-b")));
    List<Candidate> candidates = List.of(
        candidate("a-1", id("class-a")), candidate("a-2", id("class-a")),
        candidate("b-1", id("class-b")), candidate("b-2", id("class-b")));
    List<Enrollment> enrollments = List.of(
        enrollment(candidates.get(0), math), enrollment(candidates.get(1), math),
        enrollment(candidates.get(2), physics), enrollment(candidates.get(3), physics));
    Snapshot snapshot = snapshot(List.of(math, physics), candidates, enrollments, List.of(),
        List.of(room("r1", "A", 8, Set.of(), Set.of()), room("r2", "A", 8, Set.of(), Set.of())),
        List.of(teacher("t1"), teacher("t2"), teacher("t3"), teacher("t4")), List.of());

    Result result = planner.solve(snapshot, PlanningControl.unlimited(42));

    assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(result.sessions()).hasSize(2);
    assertThat(result.seats()).hasSize(4);
    assertThat(result.duties()).hasSize(4);
    assertThat(result.metrics()).containsEntry("hardConstraintViolations", 0);
    assertThat(result.diagnostics()).isEmpty();
  }

  @Test
  void feedsTeacherInfeasibilityBackIntoTimeChoice() {
    ExamDemand first = demand("first", List.of(window("first-w1", 0, 100)), 1, Set.of(), Set.of());
    ExamDemand movable = demand("movable", List.of(window("move-w1", 0, 100), window("move-w2", 2, 0)),
        1, Set.of(), Set.of());
    Snapshot snapshot = snapshot(List.of(first, movable), List.of(), List.of(), List.of(),
        List.of(room("r1", "A", 4, Set.of(), Set.of()), room("r2", "A", 4, Set.of(), Set.of())),
        List.of(teacher("only-chief")), List.of());

    Result result = planner.solve(snapshot, PlanningControl.unlimited(7));

    assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(result.iterationCount()).isGreaterThan(1);
    assertThat(result.sessions()).extracting(value -> value.start())
        .contains(BASE, BASE.plus(Duration.ofHours(2)));
  }

  @Test
  void diagnosesCandidateAndClassTimeConflictsWithoutPartialResult() {
    ExamDemand first = demand("first", 0, 1, Set.of(), Set.of(id("shared-class")));
    ExamDemand second = demand("second", 0, 1, Set.of(), Set.of(id("shared-class")));
    Candidate shared = candidate("shared", id("shared-class"));
    Snapshot snapshot = snapshot(List.of(first, second), List.of(shared),
        List.of(enrollment(shared, first), enrollment(shared, second)), List.of(),
        List.of(room("r1", "A", 4, Set.of(), Set.of()), room("r2", "A", 4, Set.of(), Set.of())),
        List.of(teacher("t1"), teacher("t2")), List.of());

    Result result = planner.solve(snapshot, PlanningControl.unlimited(1));

    assertInfeasible(result, "CANDIDATE_TIME_CONFLICT");
  }

  @Test
  void diagnosesRoomFeaturesDowntimeCapacityAndTopology() {
    ExamDemand featureDemand = demand("feature", 0, 1, Set.of("LAB"), Set.of());
    Result feature = planner.solve(snapshot(List.of(featureDemand), List.of(), List.of(), List.of(),
        List.of(room("plain", "A", 4, Set.of(), Set.of())), List.of(teacher("t")), List.of()),
        PlanningControl.unlimited(1));
    assertInfeasible(feature, "ROOM_FEATURE_UNAVAILABLE");

    ExamDemand downtimeDemand = demand("downtime", 0, 1, Set.of(), Set.of());
    Room down = room("down", "A", 4, Set.of(), Set.of(), List.of(),
        List.of(new TimeInterval(BASE.minusSeconds(1), BASE.plus(Duration.ofHours(2)))));
    Result downtime = planner.solve(snapshot(List.of(downtimeDemand), List.of(), List.of(), List.of(),
        List.of(down), List.of(teacher("t")), List.of()), PlanningControl.unlimited(1));
    assertInfeasible(downtime, "ROOM_UNAVAILABLE");

    ExamDemand topologyDemand = demand("topology", 0, 1, Set.of(), Set.of());
    Candidate candidate = candidate("candidate", null);
    Room noSeats = new Room(id("no-seats"), "NO-SEATS", "A", 4, Set.of(), List.of(), List.of(),
        List.of(), true, false);
    Result topology = planner.solve(snapshot(List.of(topologyDemand), List.of(candidate),
        List.of(enrollment(candidate, topologyDemand)), List.of(), List.of(noSeats),
        List.of(teacher("t")), List.of()), PlanningControl.unlimited(1));
    assertInfeasible(topology, "SEAT_TOPOLOGY_MISSING");

    ExamDemand capacityDemand = demand("capacity", 0, 1, Set.of(), Set.of());
    List<Candidate> many = IntStream.range(0, 5).mapToObj(i -> candidate("c-" + i, null)).toList();
    Result capacity = planner.solve(snapshot(List.of(capacityDemand), many,
        many.stream().map(value -> enrollment(value, capacityDemand)).toList(), List.of(),
        List.of(room("small", "A", 2, Set.of(), Set.of())), List.of(teacher("t")), List.of()),
        PlanningControl.unlimited(1));
    assertInfeasible(capacity, "ROOM_SPLIT_FORBIDDEN");
  }

  @Test
  void diagnosesUnsatisfiedSpecialSeatRequirement() {
    ExamDemand demand = demand("accessible", 0, 1, Set.of(), Set.of());
    Candidate candidate = candidate("needs-access", null);
    SpecialRequirement requirement = new SpecialRequirement(candidate.id(), demand.id(), "ACCESS",
        Set.of("WHEELCHAIR"), 0, false);
    Snapshot snapshot = snapshot(List.of(demand), List.of(candidate), List.of(enrollment(candidate, demand)),
        List.of(requirement), List.of(room("plain", "A", 4, Set.of(), Set.of())),
        List.of(teacher("t")), List.of());

    Result result = planner.solve(snapshot, PlanningControl.unlimited(1));

    assertInfeasible(result, "SPECIAL_REQUIREMENT_UNSATISFIED");
  }

  @Test
  void diagnosesTeacherQualificationExpiryAvailabilityAndAvoidance() {
    ExamDemand demand = demand("teacher-hard", 0, 1, Set.of(), Set.of(id("class")));
    Room room = room("r", "A", 4, Set.of(), Set.of());

    Teacher unqualified = teacher("unqualified", Set.of("INVIGILATOR"), null, 4, List.of(), Set.of());
    assertInfeasible(planner.solve(snapshot(List.of(demand), List.of(), List.of(), List.of(), List.of(room),
        List.of(unqualified), List.of()), PlanningControl.unlimited(1)), "DUTY_QUALIFICATION_SHORTAGE");

    Teacher expired = teacher("expired", Set.of("CHIEF"), BASE.minusSeconds(1), 4, List.of(), Set.of());
    assertInfeasible(planner.solve(snapshot(List.of(demand), List.of(), List.of(), List.of(), List.of(room),
        List.of(expired), List.of()), PlanningControl.unlimited(1)), "DUTY_QUALIFICATION_SHORTAGE");

    Teacher absent = teacher("absent", Set.of("CHIEF"), null, 4,
        List.of(new TimeInterval(BASE.minusSeconds(1), BASE.plus(Duration.ofHours(2)))), Set.of());
    assertInfeasible(planner.solve(snapshot(List.of(demand), List.of(), List.of(), List.of(), List.of(room),
        List.of(absent), List.of()), PlanningControl.unlimited(1)), "TEACHER_UNAVAILABLE");

    Teacher avoided = teacher("avoided", Set.of("CHIEF"), null, 4, List.of(), Set.of(demand.id()));
    assertInfeasible(planner.solve(snapshot(List.of(demand), List.of(), List.of(), List.of(), List.of(room),
        List.of(avoided), List.of()), PlanningControl.unlimited(1)), "TEACHER_AVOIDANCE_CONFLICT");
  }

  @Test
  void diagnosesDailyLimitAndCrossCampusTravel() {
    ExamDemand first = demand("first", 0, 1, Set.of(), Set.of());
    ExamDemand second = demand("second", 2, 1, Set.of(), Set.of());
    Teacher limited = teacher("limited", Set.of("CHIEF"), null, 1, List.of(), Set.of());
    Snapshot daily = snapshot(List.of(first, second), List.of(), List.of(), List.of(),
        List.of(room("r1", "A", 4, Set.of(), Set.of()), room("r2", "A", 4, Set.of(), Set.of())),
        List.of(limited), List.of());
    assertInfeasible(planner.solve(daily, PlanningControl.unlimited(1)), "TEACHER_DAILY_LIMIT_EXCEEDED");

    ExamDemand nearFirst = demand("near-first", List.of(windowMinutes("near-w1", 0)), 1,
        Set.of("CAMPUS_A_ONLY"), Set.of());
    ExamDemand nearSecond = demand("near-second", List.of(windowMinutes("near-w2", 75)), 1,
        Set.of("CAMPUS_B_ONLY"), Set.of());
    Teacher traveler = teacher("traveler");
    Snapshot travel = snapshot(List.of(nearFirst, nearSecond), List.of(), List.of(), List.of(),
        List.of(room("campus-a", "A", 4, Set.of("CAMPUS_A_ONLY"), Set.of()),
            room("campus-b", "B", 4, Set.of("CAMPUS_B_ONLY"), Set.of())),
        List.of(traveler), List.of(new CampusTravel("A", "B", 30, 0), new CampusTravel("B", "A", 30, 0)));
    assertInfeasible(planner.solve(travel, PlanningControl.unlimited(1)), "CAMPUS_TRAVEL_CONFLICT");
  }

  @Test
  void isDeterministicForFixedSeedAndInputRegardlessOfListOrder() {
    Snapshot first = generatedSnapshot(30, 300, 20, 40, 8);
    List<ExamDemand> reversedDemands = new ArrayList<>(first.demands());
    List<Candidate> reversedCandidates = new ArrayList<>(first.candidates());
    List<Room> reversedRooms = new ArrayList<>(first.rooms());
    List<Teacher> reversedTeachers = new ArrayList<>(first.teachers());
    Collections.reverse(reversedDemands);
    Collections.reverse(reversedCandidates);
    Collections.reverse(reversedRooms);
    Collections.reverse(reversedTeachers);
    Snapshot reversed = new Snapshot(first.revisionKey(), reversedDemands, reversedCandidates,
        first.enrollments(), first.specialRequirements(), reversedRooms, reversedTeachers,
        first.campusTravel(), first.lockedSessions(), first.lockedRooms(), first.lockedDuties());

    Result left = planner.solve(first, PlanningControl.unlimited(9981));
    Result right = planner.solve(reversed, PlanningControl.unlimited(9981));

    assertThat(left.status()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(right.status()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(right.inputHash()).isEqualTo(left.inputHash())
        .isEqualTo(planner.canonicalInputHash(first, 9981));
    assertThat(right.resultHash()).isEqualTo(left.resultHash());
    assertThat(right.sessions()).containsExactlyElementsOf(left.sessions());
    assertThat(right.duties()).containsExactlyElementsOf(left.duties());
  }

  @Test
  void supportsCancellationAndDeadlineWithoutPartialAssignments() {
    AtomicBoolean cancelled = new AtomicBoolean(true);
    Snapshot snapshot = generatedSnapshot(10, 100, 10, 20, 5);

    Result cancelledResult = planner.solve(snapshot,
        new PlanningControl(1, null, cancelled::get, 10));
    Result deadlineResult = planner.solve(snapshot,
        new PlanningControl(1, Instant.now().minusSeconds(1), () -> false, 10));
    AtomicInteger checkpoints = new AtomicInteger();
    Result midRunCancellation = planner.solve(generatedSnapshot(300, 3000, 60, 150, 10),
        new PlanningControl(1, null, () -> checkpoints.incrementAndGet() > 100, 64));

    assertThat(cancelledResult.status()).isEqualTo(ResultStatus.CANCELLED);
    assertThat(cancelledResult.sessions()).isEmpty();
    assertThat(deadlineResult.status()).isEqualTo(ResultStatus.DEADLINE_EXCEEDED);
    assertThat(deadlineResult.duties()).isEmpty();
    assertThat(midRunCancellation.status()).isEqualTo(ResultStatus.CANCELLED);
    assertThat(midRunCancellation.sessions()).isEmpty();
  }

  @Test
  void generatedPropertyCasesPreserveCoverageAndResourceExclusivity() {
    for (int seed = 0; seed < 20; seed++) {
      Result result = planner.solve(generatedSnapshot(20, 200, 12, 30, 5),
          PlanningControl.unlimited(seed));
      assertThat(result.status()).as("seed %s", seed).isEqualTo(ResultStatus.SUCCESS);
      assertThat(result.sessions()).hasSize(20);
      assertThat(result.seats()).hasSize(200);
      assertThat(result.duties()).hasSize(20);
      assertNoOverlappingTeacherDuties(result);
      assertNoDuplicateRoomOrSeatAtATime(result);
    }
  }

  @Test
  void plansSchoolScaleOfThreeHundredDemandsAndThreeThousandCandidatesUnderTwoMinutes() {
    assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
      Snapshot snapshot = generatedSnapshot(300, 3000, 60, 150, 10);
      Result result = planner.solve(snapshot,
          new PlanningControl(20260831L, Instant.now().plusSeconds(120), () -> false, 128));

      assertThat(result.status()).isEqualTo(ResultStatus.SUCCESS);
      assertThat(result.sessions()).hasSize(300);
      assertThat(result.seats()).hasSize(3000);
      assertThat(result.duties()).hasSize(300);
      assertThat(result.metrics()).containsEntry("hardConstraintViolations", 0);
      assertNoOverlappingTeacherDuties(result);
    });
  }

  private void assertInfeasible(Result result, String expectedCode) {
    assertThat(result.status()).isEqualTo(ResultStatus.INFEASIBLE);
    assertThat(result.sessions()).isEmpty();
    assertThat(result.rooms()).isEmpty();
    assertThat(result.seats()).isEmpty();
    assertThat(result.duties()).isEmpty();
    assertThat(result.diagnostics()).extracting(Diagnostic::code).contains(expectedCode);
  }

  private void assertNoOverlappingTeacherDuties(Result result) {
    result.duties().stream().collect(java.util.stream.Collectors.groupingBy(value -> value.teacherId()))
        .values().forEach(duties -> {
          List<JointPlanningModel.DutyAssignment> ordered = duties.stream()
              .sorted(java.util.Comparator.comparing(value -> value.start())).toList();
          for (int index = 1; index < ordered.size(); index++) {
            assertThat(ordered.get(index).start().isBefore(ordered.get(index - 1).end())).isFalse();
          }
        });
  }

  private void assertNoDuplicateRoomOrSeatAtATime(Result result) {
    Set<String> roomKeys = new HashSet<>();
    Map<UUID, JointPlanningModel.SessionAssignment> sessions = result.sessions().stream()
        .collect(java.util.stream.Collectors.toMap(value -> value.demandId(), value -> value));
    result.rooms().forEach(room -> {
      JointPlanningModel.SessionAssignment session = sessions.get(room.demandId());
      assertThat(roomKeys.add(room.roomId() + "@" + session.start())).isTrue();
    });
    Set<String> seatKeys = new HashSet<>();
    result.seats().forEach(seat -> assertThat(seatKeys.add(seat.seatId() + "@" + seat.start())).isTrue());
  }

  private Snapshot generatedSnapshot(
      int demandCount,
      int candidateCount,
      int roomCount,
      int teacherCount,
      int slotCount
  ) {
    List<ExamDemand> demands = new ArrayList<>();
    List<Candidate> candidates = new ArrayList<>();
    List<Enrollment> enrollments = new ArrayList<>();
    int candidatesPerDemand = candidateCount / demandCount;
    for (int demandIndex = 0; demandIndex < demandCount; demandIndex++) {
      UUID classId = id("scale-class-" + demandIndex);
      int slot = demandIndex % slotCount;
      ExamDemand demand = demand("scale-demand-" + demandIndex,
          List.of(window("scale-window-" + demandIndex, slot * 2, 0)), 1, Set.of(), Set.of(classId));
      demands.add(demand);
      for (int position = 0; position < candidatesPerDemand; position++) {
        Candidate candidate = candidate("scale-candidate-" + demandIndex + '-' + position, classId);
        candidates.add(candidate);
        enrollments.add(enrollment(candidate, demand));
      }
    }
    List<Room> rooms = IntStream.range(0, roomCount)
        .mapToObj(index -> room("scale-room-" + index, "MAIN", 60, Set.of(), Set.of())).toList();
    List<Teacher> teachers = IntStream.range(0, teacherCount)
        .mapToObj(index -> teacher("scale-teacher-" + index, Set.of("CHIEF", "INVIGILATOR"),
            null, 12, List.of(), Set.of())).toList();
    return snapshot(demands, candidates, enrollments, List.of(), rooms, teachers, List.of());
  }

  private Snapshot snapshot(
      List<ExamDemand> demands,
      List<Candidate> candidates,
      List<Enrollment> enrollments,
      List<SpecialRequirement> requirements,
      List<Room> rooms,
      List<Teacher> teachers,
      List<CampusTravel> travel
  ) {
    return new Snapshot("test-revision", demands, candidates, enrollments, requirements, rooms, teachers,
        travel, List.of(), List.of(), List.of());
  }

  private ExamDemand demand(
      String key,
      int slot,
      int invigilators,
      Set<String> roomFeatures,
      Set<UUID> classIds
  ) {
    return demand(key, List.of(window(key + "-window", slot, 0)), invigilators, roomFeatures, classIds);
  }

  private ExamDemand demand(
      String key,
      List<DemandWindow> windows,
      int invigilators,
      Set<String> roomFeatures,
      Set<UUID> classIds
  ) {
    return new ExamDemand(id(key), key, key, id("subject-" + key), 60, invigilators,
        1, 1, false, roomFeatures, classIds, windows);
  }

  private DemandWindow window(String key, int twoHourSlot, int preference) {
    Instant start = BASE.plus(Duration.ofHours(twoHourSlot));
    return new DemandWindow(id(key), start, start.plus(Duration.ofMinutes(60)), preference, false);
  }

  private DemandWindow windowMinutes(String key, int minuteOffset) {
    Instant start = BASE.plus(Duration.ofMinutes(minuteOffset));
    return new DemandWindow(id(key), start, start.plus(Duration.ofMinutes(60)), 0, false);
  }

  private Candidate candidate(String key, UUID classId) {
    return new Candidate(id(key), key, classId, List.of(), List.of());
  }

  private Enrollment enrollment(Candidate candidate, ExamDemand demand) {
    return new Enrollment(candidate.id(), demand.id());
  }

  private Room room(
      String key,
      String campus,
      int capacity,
      Set<String> roomFeatures,
      Set<String> seatFeatures
  ) {
    return room(key, campus, capacity, roomFeatures, seatFeatures, List.of(), List.of());
  }

  private Room room(
      String key,
      String campus,
      int capacity,
      Set<String> roomFeatures,
      Set<String> seatFeatures,
      List<TimeInterval> available,
      List<TimeInterval> unavailable
  ) {
    List<Seat> seats = IntStream.range(0, capacity)
        .mapToObj(index -> new Seat(id(key + "-seat-" + index), "S" + index,
            index / 10 + 1, index % 10 + 1, seatFeatures, true)).toList();
    return new Room(id(key), key.toUpperCase(), campus, capacity, roomFeatures, available, unavailable,
        seats, true, false);
  }

  private Teacher teacher(String key) {
    return teacher(key, Set.of("CHIEF", "INVIGILATOR"), null, 12, List.of(), Set.of());
  }

  private Teacher teacher(
      String key,
      Set<String> roles,
      Instant expiry,
      int maxDaily,
      List<TimeInterval> unavailable,
      Set<UUID> hardAvoidDemands
  ) {
    return new Teacher(id(key), key, key, roles, expiry, maxDaily, 0, null, TeacherStatus.INCLUDED,
        List.of(), unavailable, hardAvoidDemands, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
        35, false, 0);
  }

  private UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
