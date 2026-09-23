package com.jkxt.examops.joint;

import static com.jkxt.examops.joint.JointPlanningModel.DiagnosticStage.CONTROL;
import static com.jkxt.examops.joint.JointPlanningModel.DiagnosticStage.DUTY;
import static com.jkxt.examops.joint.JointPlanningModel.DiagnosticStage.INPUT;
import static com.jkxt.examops.joint.JointPlanningModel.DiagnosticStage.ROOM;
import static com.jkxt.examops.joint.JointPlanningModel.DiagnosticStage.SEAT;
import static com.jkxt.examops.joint.JointPlanningModel.DiagnosticStage.TIME;

import com.jkxt.examops.joint.JointPlanningModel.CampusTravel;
import com.jkxt.examops.joint.JointPlanningModel.Candidate;
import com.jkxt.examops.joint.JointPlanningModel.DemandWindow;
import com.jkxt.examops.joint.JointPlanningModel.Diagnostic;
import com.jkxt.examops.joint.JointPlanningModel.DiagnosticStage;
import com.jkxt.examops.joint.JointPlanningModel.DutyAssignment;
import com.jkxt.examops.joint.JointPlanningModel.Enrollment;
import com.jkxt.examops.joint.JointPlanningModel.ExamDemand;
import com.jkxt.examops.joint.JointPlanningModel.LockedDuty;
import com.jkxt.examops.joint.JointPlanningModel.LockedRoom;
import com.jkxt.examops.joint.JointPlanningModel.LockedSession;
import com.jkxt.examops.joint.JointPlanningModel.Objective;
import com.jkxt.examops.joint.JointPlanningModel.PlanningControl;
import com.jkxt.examops.joint.JointPlanningModel.Result;
import com.jkxt.examops.joint.JointPlanningModel.ResultStatus;
import com.jkxt.examops.joint.JointPlanningModel.Room;
import com.jkxt.examops.joint.JointPlanningModel.RoomAssignment;
import com.jkxt.examops.joint.JointPlanningModel.Seat;
import com.jkxt.examops.joint.JointPlanningModel.SeatAssignment;
import com.jkxt.examops.joint.JointPlanningModel.SessionAssignment;
import com.jkxt.examops.joint.JointPlanningModel.Snapshot;
import com.jkxt.examops.joint.JointPlanningModel.SpecialRequirement;
import com.jkxt.examops.joint.JointPlanningModel.Teacher;
import com.jkxt.examops.joint.JointPlanningModel.TeacherStatus;
import com.jkxt.examops.joint.JointPlanningModel.TimeInterval;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministic staged joint planner for examination time, room, seat, and duty assignments.
 *
 * <p>This implementation intentionally does not write to a database. It builds a complete result
 * in memory and exposes assignments only when every hard constraint has passed. When teacher
 * assignment is infeasible, the failed demand/window pair becomes a no-good and the full plan is
 * rebuilt, allowing an alternative time window to release staff capacity.</p>
 */
public final class DeterministicJointPlanner {

  public static final String SOLVER_VERSION = "joint-greedy-feedback/1.0";

  public Result solve(Snapshot snapshot, PlanningControl control) {
    Objects.requireNonNull(snapshot, "snapshot");
    control = control == null ? PlanningControl.unlimited(0L) : control;
    long started = System.nanoTime();
    String inputHash = canonicalInputHash(snapshot, control.seed());
    try {
      checkpoint(control);
      List<Diagnostic> invalid = validate(snapshot, control);
      if (!invalid.isEmpty()) {
        return terminal(ResultStatus.INFEASIBLE, invalid, control.seed(), 0, inputHash, started);
      }

      Set<WindowChoice> noGoods = new LinkedHashSet<>();
      Attempt last = null;
      for (int iteration = 1; iteration <= control.maxFeedbackIterations(); iteration++) {
        checkpoint(control);
        Attempt attempt = attempt(snapshot, control, noGoods);
        last = attempt;
        if (attempt.success()) {
          Objective objective = objective(snapshot, attempt);
          Map<String, Object> metrics = new LinkedHashMap<>();
          metrics.put("hardConstraintViolations", 0);
          metrics.put("demandCount", snapshot.demands().size());
          metrics.put("candidateCount", snapshot.candidates().size());
          metrics.put("sessionCount", attempt.sessions().size());
          metrics.put("roomAssignmentCount", attempt.rooms().size());
          metrics.put("seatAssignmentCount", attempt.seats().size());
          metrics.put("dutyAssignmentCount", attempt.duties().size());
          metrics.put("feedbackNoGoods", noGoods.size());
          metrics.put("elapsedMillis", elapsedMillis(started));
          String resultHash = resultHash(attempt, objective, control.seed());
          return new Result(ResultStatus.SUCCESS, attempt.sessions(), attempt.rooms(), attempt.seats(),
              attempt.duties(), List.of(), objective, metrics, SOLVER_VERSION, control.seed(),
              iteration, inputHash, resultHash);
        }

        WindowChoice feedback = attempt.feedbackChoice();
        if (attempt.failureStage() != DUTY || feedback == null || !hasAlternative(snapshot, feedback, noGoods)) {
          return terminal(ResultStatus.INFEASIBLE, attempt.diagnostics(), control.seed(), iteration,
              inputHash, started);
        }
        noGoods.add(feedback);
      }

      List<Diagnostic> diagnostics = new ArrayList<>(last == null ? List.of() : last.diagnostics());
      diagnostics.add(diagnostic("FEEDBACK_ITERATION_LIMIT", DUTY,
          "Teacher infeasibility feedback exhausted the configured retry budget",
          List.of(), List.of("Increase the retry budget", "Add staff capacity or additional time windows"),
          Map.of("maxFeedbackIterations", control.maxFeedbackIterations())));
      return terminal(ResultStatus.INFEASIBLE, diagnostics, control.seed(),
          control.maxFeedbackIterations(), inputHash, started);
    } catch (PlanningAbort abort) {
      ResultStatus status = abort.deadline ? ResultStatus.DEADLINE_EXCEEDED : ResultStatus.CANCELLED;
      String code = abort.deadline ? "PLANNING_DEADLINE_EXCEEDED" : "PLANNING_CANCELLED";
      return terminal(status, List.of(diagnostic(code, CONTROL, abort.getMessage(), List.of(),
          List.of(abort.deadline ? "Retry with a longer deadline" : "Start a new planning job when ready"),
          Map.of())), control.seed(), 0, inputHash, started);
    }
  }

  /**
   * Returns the stable SHA-256 provenance hash used by {@link Result#inputHash()}.
   * Orchestration may persist this value while a planning job is still queued, before the V28
   * provenance trigger makes the started job immutable.
   */
  public String canonicalInputHash(Snapshot snapshot, long seed) {
    Objects.requireNonNull(snapshot, "snapshot");
    return inputHash(snapshot, seed);
  }

  private Attempt attempt(Snapshot snapshot, PlanningControl control, Set<WindowChoice> noGoods) {
    PlannerContext context = new PlannerContext(snapshot, control.seed());
    List<ExamDemand> orderedDemands = snapshot.demands().stream()
        .sorted(Comparator.comparingInt((ExamDemand demand) -> demand.windows().size())
            .thenComparing(Comparator.comparingInt(
                (ExamDemand demand) -> context.candidatesByDemand.getOrDefault(demand.id(), List.of()).size())
                .reversed())
            .thenComparing(ExamDemand::code)
            .thenComparing(demand -> demand.id().toString()))
        .toList();

    for (ExamDemand demand : orderedDemands) {
      checkpoint(control);
      DemandPlacement placement = placeDemand(demand, context, control, noGoods);
      if (!placement.success()) {
        return Attempt.failure(placement.failureStage(), placement.diagnostics(), null);
      }
      context.commit(placement);
    }

    TeacherPlan teacherPlan = assignTeachers(context, control);
    if (!teacherPlan.success()) {
      return Attempt.failure(DUTY, teacherPlan.diagnostics(), teacherPlan.feedbackChoice());
    }
    return Attempt.success(List.copyOf(context.sessions), List.copyOf(context.roomAssignments),
        List.copyOf(context.seatAssignments), teacherPlan.assignments());
  }

  private DemandPlacement placeDemand(
      ExamDemand demand,
      PlannerContext context,
      PlanningControl control,
      Set<WindowChoice> noGoods
  ) {
    List<Candidate> candidates = context.candidatesByDemand.getOrDefault(demand.id(), List.of());
    List<SpecialRequirement> requirements = context.requirementsByDemand.getOrDefault(demand.id(), List.of());
    int extraMinutes = requirements.stream().mapToInt(SpecialRequirement::extraMinutes).max().orElse(0);
    UUID lockedWindow = context.lockedWindowByDemand.get(demand.id());
    List<DemandWindow> windows = demand.windows().stream()
        .filter(window -> !noGoods.contains(new WindowChoice(demand.id(), window.id())))
        .sorted(Comparator
            .comparingInt((DemandWindow window) -> Objects.equals(window.id(), lockedWindow) ? 0 : 1)
            .thenComparingInt(window -> window.locked() ? 0 : 1)
            .thenComparing(Comparator.comparingInt(DemandWindow::preferenceWeight).reversed())
            .thenComparingLong(window -> seededTie(context.seed, window.id(), demand.id()))
            .thenComparing(window -> window.id().toString()))
        .toList();
    if (windows.isEmpty()) {
      return DemandPlacement.failure(TIME, List.of(diagnostic("NO_ALTERNATE_TIME_WINDOW", TIME,
          "Every allowed time window for the demand has been ruled out by feedback",
          ids(demand.id()), List.of("Add a time window", "Increase teacher or room capacity"),
          Map.of("demandCode", demand.code()))));
    }

    List<Diagnostic> rejected = new ArrayList<>();
    for (DemandWindow window : windows) {
      checkpoint(control);
      Instant start = window.start();
      Instant end = start.plus(Duration.ofMinutes((long) demand.durationMinutes() + extraMinutes));
      if (end.isAfter(window.end())) {
        rejected.add(diagnostic("DEMAND_WINDOW_TOO_SHORT", TIME,
            "The time window cannot contain the examination and approved extra time",
            ids(demand.id(), window.id()), List.of("Extend the window", "Move the examination"),
            Map.of("requiredMinutes", demand.durationMinutes() + extraMinutes)));
        continue;
      }
      Optional<Diagnostic> availabilityFailure = candidateAvailabilityFailure(candidates, start, end, demand);
      if (availabilityFailure.isPresent()) {
        rejected.add(availabilityFailure.get());
        continue;
      }
      Optional<Diagnostic> collision = participantCollision(candidates, demand, start, end, context);
      if (collision.isPresent()) {
        rejected.add(collision.get());
        continue;
      }

      RoomPlan roomPlan = allocateRooms(demand, candidates, requirements, start, end, context, control);
      if (!roomPlan.success()) {
        rejected.addAll(roomPlan.diagnostics());
        continue;
      }
      SessionAssignment session = new SessionAssignment(demand.id(), window.id(), start, end);
      return DemandPlacement.success(session, roomPlan.rooms(), roomPlan.seats(), candidates,
          effectiveClassIds(demand, candidates));
    }
    return DemandPlacement.failure(primaryStage(rejected), compactDiagnostics(demand, rejected));
  }

  private Optional<Diagnostic> candidateAvailabilityFailure(
      List<Candidate> candidates,
      Instant start,
      Instant end,
      ExamDemand demand
  ) {
    for (Candidate candidate : candidates) {
      boolean insideAvailable = candidate.availableWindows().isEmpty()
          || candidate.availableWindows().stream().anyMatch(window -> window.contains(start, end));
      boolean unavailable = candidate.unavailableWindows().stream().anyMatch(window -> window.overlaps(start, end));
      if (!insideAvailable || unavailable) {
        return Optional.of(diagnostic("CANDIDATE_UNAVAILABLE", TIME,
            "An enrolled candidate is unavailable for the complete examination window",
            ids(demand.id(), candidate.id()), List.of("Choose another demand window", "Review candidate availability"),
            Map.of("candidateCode", candidate.code())));
      }
    }
    return Optional.empty();
  }

  private Optional<Diagnostic> participantCollision(
      List<Candidate> candidates,
      ExamDemand demand,
      Instant start,
      Instant end,
      PlannerContext context
  ) {
    for (Candidate candidate : candidates) {
      if (overlaps(context.candidateOccupancy.get(candidate.id()), start, end)) {
        return Optional.of(diagnostic("CANDIDATE_TIME_CONFLICT", TIME,
            "An enrolled candidate would sit overlapping examinations",
            ids(demand.id(), candidate.id()), List.of("Choose another demand window"), Map.of()));
      }
    }
    for (UUID classId : effectiveClassIds(demand, candidates)) {
      if (overlaps(context.classOccupancy.get(classId), start, end)) {
        return Optional.of(diagnostic("CLASS_TIME_CONFLICT", TIME,
            "A class would be assigned overlapping examinations",
            ids(demand.id(), classId), List.of("Choose another demand window"), Map.of()));
      }
    }
    return Optional.empty();
  }

  private RoomPlan allocateRooms(
      ExamDemand demand,
      List<Candidate> candidates,
      List<SpecialRequirement> requirements,
      Instant start,
      Instant end,
      PlannerContext context,
      PlanningControl control
  ) {
    Map<UUID, CandidateNeed> needs = candidateNeeds(candidates, requirements);
    List<Room> included = context.snapshot.rooms().stream().filter(Room::included).toList();
    if (included.isEmpty()) {
      return RoomPlan.failure(diagnostic("NO_INCLUDED_ROOMS", ROOM,
          "The plan has no included examination rooms", ids(demand.id()),
          List.of("Include at least one room in the revision room pool"), Map.of()));
    }
    List<Room> featureCompatible = included.stream()
        .filter(room -> room.features().containsAll(demand.requiredRoomFeatures()))
        .toList();
    if (featureCompatible.isEmpty()) {
      return RoomPlan.failure(diagnostic("ROOM_FEATURE_UNAVAILABLE", ROOM,
          "No room provides all demand-level required features", ids(demand.id()),
          List.of("Add a compatible room", "Correct the demand room-feature requirements"),
          Map.of("requiredFeatures", sorted(demand.requiredRoomFeatures()))));
    }
    List<Room> available = featureCompatible.stream()
        .filter(room -> resourceAvailable(room.availableWindows(), room.unavailableWindows(), start, end))
        .filter(room -> !overlaps(context.roomOccupancy.get(room.id()), start, end))
        .toList();
    if (available.isEmpty()) {
      return RoomPlan.failure(diagnostic("ROOM_UNAVAILABLE", ROOM,
          "Every compatible room is unavailable or already occupied", ids(demand.id()),
          List.of("Choose another time window", "Open an additional room"), Map.of()));
    }

    Set<UUID> lockedRoomIds = context.lockedRoomsByDemand.getOrDefault(demand.id(), Set.of());
    List<Room> orderedRooms = available.stream()
        .sorted(Comparator.comparingInt((Room room) -> lockedRoomIds.contains(room.id()) ? 0 : 1)
            .thenComparingInt(room -> room.locked() ? 0 : 1)
            .thenComparing(Comparator.comparingInt(this::usableCapacity).reversed())
            .thenComparingLong(room -> seededTie(context.seed, room.id(), demand.id()))
            .thenComparing(Room::code)
            .thenComparing(room -> room.id().toString()))
        .toList();

    if (!candidates.isEmpty() && orderedRooms.stream().noneMatch(room -> usableCapacity(room) > 0)) {
      return RoomPlan.failure(diagnostic("SEAT_TOPOLOGY_MISSING", SEAT,
          "Compatible rooms do not contain active versioned seat topology", ids(demand.id()),
          List.of("Import active room seats", "Correct the room topology"), Map.of()));
    }
    List<CandidateNeed> orderedCandidates = needs.values().stream()
        .sorted(Comparator.comparingInt((CandidateNeed need) -> need.separateRoom() ? 0 : 1)
            .thenComparing(Comparator.comparingInt((CandidateNeed need) -> need.features().size()).reversed())
            .thenComparing(need -> need.candidate().code())
            .thenComparing(need -> need.candidate().id().toString()))
        .toList();
    List<MutableRoom> opened = new ArrayList<>();
    Set<UUID> openedIds = new HashSet<>();

    for (CandidateNeed need : orderedCandidates) {
      checkpoint(control);
      SeatChoice choice = null;
      if (!need.separateRoom()) {
        choice = opened.stream().filter(room -> !room.dedicated)
            .map(room -> room.findSeat(need)).filter(Objects::nonNull).findFirst().orElse(null);
      }
      if (choice == null) {
        if (!opened.isEmpty() && !demand.splitAllowed()) {
          return RoomPlan.failure(diagnostic("ROOM_SPLIT_FORBIDDEN", ROOM,
              "The demand needs more than one room but splitting is disabled",
              ids(demand.id(), need.candidate().id()), List.of("Enable splitting", "Use a larger compatible room"),
              Map.of("maxRooms", demand.maxRooms())));
        }
        if (opened.size() >= demand.maxRooms()) {
          return RoomPlan.failure(roomOrSeatShortage(demand, need, orderedRooms, opened));
        }
        for (Room room : orderedRooms) {
          if (openedIds.contains(room.id())) {
            continue;
          }
          MutableRoom candidateRoom = new MutableRoom(room);
          choice = candidateRoom.findSeat(need);
          if (choice != null) {
            opened.add(candidateRoom);
            openedIds.add(room.id());
            break;
          }
        }
      }
      if (choice == null) {
        return RoomPlan.failure(roomOrSeatShortage(demand, need, orderedRooms, opened));
      }
      choice.room().assign(choice.seat(), need.candidate().id(), need.separateRoom());
    }

    while (opened.size() < demand.minRooms()) {
      checkpoint(control);
      if (!opened.isEmpty() && !demand.splitAllowed()) {
        return RoomPlan.failure(diagnostic("ROOM_SPLIT_FORBIDDEN", ROOM,
            "The configured minimum room count requires splitting but splitting is disabled",
            ids(demand.id()), List.of("Set minRooms to one", "Enable splitting"), Map.of()));
      }
      Room next = orderedRooms.stream().filter(room -> !openedIds.contains(room.id()))
          .filter(room -> candidates.isEmpty() || usableCapacity(room) > 0).findFirst().orElse(null);
      if (next == null || opened.size() >= demand.maxRooms()) {
        return RoomPlan.failure(diagnostic("MINIMUM_ROOM_COUNT_UNSATISFIED", ROOM,
            "The minimum room count cannot be met with available rooms", ids(demand.id()),
            List.of("Open additional rooms", "Lower the minimum room count"),
            Map.of("minRooms", demand.minRooms())));
      }
      opened.add(new MutableRoom(next));
      openedIds.add(next.id());
    }
    if (opened.isEmpty()) {
      return RoomPlan.failure(diagnostic("ROOM_CAPACITY_SHORTAGE", ROOM,
          "No room could be opened for the demand", ids(demand.id()),
          List.of("Add room capacity"), Map.of()));
    }

    opened.sort(Comparator.comparing((MutableRoom room) -> room.room.code())
        .thenComparing(room -> room.room.id().toString()));
    List<RoomAssignment> roomAssignments = new ArrayList<>();
    List<SeatAssignment> seatAssignments = new ArrayList<>();
    for (int index = 0; index < opened.size(); index++) {
      MutableRoom room = opened.get(index);
      roomAssignments.add(new RoomAssignment(demand.id(), room.room.id(), index + 1,
          room.candidateBySeat.size()));
      room.candidateBySeat.entrySet().stream()
          .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
          .forEach(entry -> seatAssignments.add(new SeatAssignment(demand.id(), entry.getValue(),
              room.room.id(), entry.getKey(), start, end)));
    }
    return RoomPlan.success(roomAssignments, seatAssignments);
  }

  private Diagnostic roomOrSeatShortage(
      ExamDemand demand,
      CandidateNeed need,
      List<Room> rooms,
      List<MutableRoom> opened
  ) {
    boolean anyFeatureMatch = rooms.stream().anyMatch(room -> room.seats().stream().filter(Seat::active)
        .anyMatch(seat -> unionContains(room.features(), seat.features(), need.features())));
    String code = !need.features().isEmpty() && !anyFeatureMatch
        ? "SPECIAL_REQUIREMENT_UNSATISFIED" : "ROOM_CAPACITY_SHORTAGE";
    DiagnosticStage stage = code.startsWith("SPECIAL") ? SEAT : ROOM;
    return diagnostic(code, stage,
        code.startsWith("SPECIAL")
            ? "No active seat and room feature combination satisfies a candidate special requirement"
            : "Available compatible room and seat capacity is insufficient",
        ids(demand.id(), need.candidate().id()),
        code.startsWith("SPECIAL")
            ? List.of("Add an accessible room or seat", "Review the candidate requirement")
            : List.of("Add room capacity", "Enable or increase room splitting"),
        Map.of("requiredFeatures", sorted(need.features()), "openedRooms", opened.size()));
  }

  private TeacherPlan assignTeachers(PlannerContext context, PlanningControl control) {
    List<DutyNeed> duties = new ArrayList<>();
    Map<UUID, SessionAssignment> sessionByDemand = context.sessions.stream()
        .collect(Collectors.toMap(SessionAssignment::demandId, Function.identity()));
    for (RoomAssignment room : context.roomAssignments) {
      ExamDemand demand = context.demandById.get(room.demandId());
      SessionAssignment session = sessionByDemand.get(room.demandId());
      for (int position = 0; position < demand.invigilatorsPerRoom(); position++) {
        duties.add(new DutyNeed(demand, room.roomId(), position == 0 ? "CHIEF" : "INVIGILATOR",
            position, session.start(), session.end(), context.roomById.get(room.roomId()).campus(),
            context.candidatesByDemand.getOrDefault(demand.id(), List.of())));
      }
    }
    duties.sort(Comparator.comparing(DutyNeed::start).thenComparing(DutyNeed::end)
        .thenComparing(duty -> duty.demand().code()).thenComparing(duty -> duty.roomId().toString())
        .thenComparingInt(DutyNeed::position));

    List<Teacher> teachers = context.snapshot.teachers().stream()
        .filter(teacher -> teacher.status() != TeacherStatus.EXCLUDED)
        .sorted(Comparator.comparing(Teacher::code).thenComparing(teacher -> teacher.id().toString()))
        .toList();
    Map<UUID, List<AssignedDuty>> assigned = new HashMap<>();
    Map<UUID, Integer> loads = new HashMap<>();
    Map<UUID, Map<LocalDate, Integer>> dailyLoads = new HashMap<>();
    List<DutyAssignment> result = new ArrayList<>();

    for (DutyNeed duty : duties) {
      checkpoint(control);
      Map<String, Integer> rejections = new LinkedHashMap<>();
      List<Teacher> eligible = teachers.stream()
          .filter(teacher -> teacherEligible(teacher, duty, assigned, dailyLoads, context, rejections))
          .sorted(teacherComparator(duty, assigned, loads, context))
          .toList();
      if (eligible.isEmpty()) {
        String code = dominantTeacherFailure(rejections);
        Diagnostic failure = diagnostic(code, DUTY, teacherFailureMessage(code),
            ids(duty.demand().id(), duty.roomId()), teacherSuggestions(code),
            Map.of("role", duty.role(), "position", duty.position(), "rejections", Map.copyOf(rejections)));
        SessionAssignment session = sessionByDemand.get(duty.demand().id());
        return TeacherPlan.failure(List.of(failure),
            new WindowChoice(duty.demand().id(), session.windowId()));
      }
      Teacher selected = eligible.getFirst();
      AssignedDuty assignment = new AssignedDuty(duty.start(), duty.end(), duty.campus(), duty.demand().id(),
          duty.roomId(), duty.role(), duty.position());
      assigned.computeIfAbsent(selected.id(), ignored -> new ArrayList<>()).add(assignment);
      loads.merge(selected.id(), 1, Integer::sum);
      LocalDate date = duty.start().atZone(ZoneOffset.UTC).toLocalDate();
      dailyLoads.computeIfAbsent(selected.id(), ignored -> new HashMap<>()).merge(date, 1, Integer::sum);
      result.add(new DutyAssignment(duty.demand().id(), duty.roomId(), duty.role(), duty.position(),
          selected.id(), duty.start(), duty.end()));
    }
    return TeacherPlan.success(List.copyOf(result));
  }

  private boolean teacherEligible(
      Teacher teacher,
      DutyNeed duty,
      Map<UUID, List<AssignedDuty>> assigned,
      Map<UUID, Map<LocalDate, Integer>> dailyLoads,
      PlannerContext context,
      Map<String, Integer> rejections
  ) {
    if (!teacher.eligibleRoles().contains(duty.role())
        || (teacher.qualificationExpiry() != null && teacher.qualificationExpiry().isBefore(duty.end()))) {
      reject(rejections, "DUTY_QUALIFICATION_SHORTAGE");
      return false;
    }
    if (!resourceAvailable(teacher.availableWindows(), teacher.unavailableWindows(), duty.start(), duty.end())) {
      reject(rejections, "TEACHER_UNAVAILABLE");
      return false;
    }
    Set<UUID> candidateIds = duty.candidates().stream().map(Candidate::id).collect(Collectors.toSet());
    Set<UUID> classIds = effectiveClassIds(duty.demand(), duty.candidates());
    if (teacher.hardAvoidDemandIds().contains(duty.demand().id())
        || !disjoint(teacher.hardAvoidClassIds(), classIds)
        || !disjoint(teacher.hardAvoidCandidateIds(), candidateIds)) {
      reject(rejections, "TEACHER_AVOIDANCE_CONFLICT");
      return false;
    }
    LocalDate day = duty.start().atZone(ZoneOffset.UTC).toLocalDate();
    if (dailyLoads.getOrDefault(teacher.id(), Map.of()).getOrDefault(day, 0) >= teacher.maxDailySessions()) {
      reject(rejections, "TEACHER_DAILY_LIMIT_EXCEEDED");
      return false;
    }
    for (AssignedDuty existing : assigned.getOrDefault(teacher.id(), List.of())) {
      if (existing.start().isBefore(duty.end()) && existing.end().isAfter(duty.start())) {
        reject(rejections, "TEACHER_TIME_CONFLICT");
        return false;
      }
      long requiredGap = teacher.minRestMinutes();
      String fromCampus;
      String toCampus;
      long actualGap;
      if (!existing.end().isAfter(duty.start())) {
        fromCampus = existing.campus();
        toCampus = duty.campus();
        actualGap = Duration.between(existing.end(), duty.start()).toMinutes();
      } else {
        fromCampus = duty.campus();
        toCampus = existing.campus();
        actualGap = Duration.between(duty.end(), existing.start()).toMinutes();
      }
      if (!fromCampus.equals(toCampus)) {
        Integer travel = context.travelMinutes.get(new CampusPair(fromCampus, toCampus));
        if (travel == null) {
          reject(rejections, "CAMPUS_TRAVEL_RULE_MISSING");
          return false;
        }
        requiredGap += travel;
      }
      if (actualGap < requiredGap) {
        reject(rejections, fromCampus.equals(toCampus)
            ? "TEACHER_REST_CONFLICT" : "CAMPUS_TRAVEL_CONFLICT");
        return false;
      }
    }
    return true;
  }

  private Comparator<Teacher> teacherComparator(
      DutyNeed duty,
      Map<UUID, List<AssignedDuty>> assigned,
      Map<UUID, Integer> loads,
      PlannerContext context
  ) {
    LockedDutyKey key = new LockedDutyKey(duty.demand().id(), duty.roomId(), duty.role(), duty.position());
    UUID lockedTeacher = context.lockedTeacherByDuty.get(key);
    return Comparator.comparingInt((Teacher teacher) -> Objects.equals(teacher.id(), lockedTeacher) ? 0 : 1)
        .thenComparingInt(teacher -> teacher.status() == TeacherStatus.INCLUDED ? 0 : 1)
        .thenComparingInt(teacher -> loads.getOrDefault(teacher.id(), 0))
        .thenComparingInt(teacher -> consecutivePenalty(teacher, duty, assigned))
        .thenComparingInt(teacher -> softAvoidancePenalty(teacher, duty))
        .thenComparingInt(teacher -> carePenalty(teacher))
        .thenComparingInt(teacher -> campusPenalty(teacher, duty.campus()))
        .thenComparingLong(teacher -> seededTie(context.seed, teacher.id(), duty.demand().id(), duty.roomId()))
        .thenComparing(Teacher::code)
        .thenComparing(teacher -> teacher.id().toString());
  }

  private int consecutivePenalty(
      Teacher teacher,
      DutyNeed duty,
      Map<UUID, List<AssignedDuty>> assigned
  ) {
    return (int) assigned.getOrDefault(teacher.id(), List.of()).stream()
        .filter(existing -> existing.start().atZone(ZoneOffset.UTC).toLocalDate()
            .equals(duty.start().atZone(ZoneOffset.UTC).toLocalDate()))
        .filter(existing -> {
          long before = Math.abs(Duration.between(existing.end(), duty.start()).toMinutes());
          long after = Math.abs(Duration.between(duty.end(), existing.start()).toMinutes());
          return Math.min(before, after) <= 30;
        }).count();
  }

  private int softAvoidancePenalty(Teacher teacher, DutyNeed duty) {
    int penalty = teacher.softAvoidDemandIds().contains(duty.demand().id()) ? 20 : 0;
    Set<UUID> classes = effectiveClassIds(duty.demand(), duty.candidates());
    if (!disjoint(teacher.homeroomClassIds(), classes)) {
      penalty += 12;
    }
    if (duty.demand().subjectId() != null && teacher.subjectIds().contains(duty.demand().subjectId())) {
      penalty += 8;
    }
    return penalty;
  }

  private int carePenalty(Teacher teacher) {
    return (teacher.age() >= 55 ? teacher.age() - 50 : 0)
        + (teacher.healthRestricted() ? 30 : 0) + teacher.positionWeight();
  }

  private int campusPenalty(Teacher teacher, String campus) {
    return teacher.preferredCampus() == null || teacher.preferredCampus().isBlank()
        || teacher.preferredCampus().equals(campus) ? 0 : 1;
  }

  private Objective objective(Snapshot snapshot, Attempt attempt) {
    Map<UUID, UUID> sessionWindows = attempt.sessions().stream()
        .collect(Collectors.toMap(SessionAssignment::demandId, SessionAssignment::windowId));
    Set<LockedRoomKey> actualRooms = attempt.rooms().stream()
        .map(room -> new LockedRoomKey(room.demandId(), room.roomId())).collect(Collectors.toSet());
    Map<LockedDutyKey, UUID> actualDuties = attempt.duties().stream().collect(Collectors.toMap(
        duty -> new LockedDutyKey(duty.demandId(), duty.roomId(), duty.role(), duty.position()),
        DutyAssignment::teacherId));
    int lockedChanges = 0;
    for (LockedSession locked : snapshot.lockedSessions()) {
      if (!Objects.equals(sessionWindows.get(locked.demandId()), locked.windowId())) {
        lockedChanges++;
      }
    }
    for (LockedRoom locked : snapshot.lockedRooms()) {
      if (!actualRooms.contains(new LockedRoomKey(locked.demandId(), locked.roomId()))) {
        lockedChanges++;
      }
    }
    for (LockedDuty locked : snapshot.lockedDuties()) {
      if (!Objects.equals(actualDuties.get(new LockedDutyKey(locked.demandId(), locked.roomId(),
          locked.role(), locked.position())), locked.teacherId())) {
        lockedChanges++;
      }
    }
    int splits = attempt.rooms().stream().collect(Collectors.groupingBy(RoomAssignment::demandId,
        Collectors.counting())).values().stream().mapToInt(count -> Math.max(0, count.intValue() - 1)).sum();
    Map<UUID, Long> loads = attempt.duties().stream().collect(Collectors.groupingBy(
        DutyAssignment::teacherId, Collectors.counting()));
    long sum = loads.values().stream().mapToLong(Long::longValue).sum();
    long squares = loads.values().stream().mapToLong(load -> load * load).sum();
    long variance = loads.isEmpty() ? 0 : loads.size() * squares - sum * sum;
    Map<UUID, Teacher> teachers = snapshot.teachers().stream()
        .collect(Collectors.toMap(Teacher::id, Function.identity()));
    Map<UUID, ExamDemand> demands = snapshot.demands().stream()
        .collect(Collectors.toMap(ExamDemand::id, Function.identity()));
    Map<UUID, Room> rooms = snapshot.rooms().stream().collect(Collectors.toMap(Room::id, Function.identity()));
    Map<UUID, List<Candidate>> candidates = candidatesByDemand(snapshot);
    int care = 0;
    int avoidance = 0;
    int campus = 0;
    Map<UUID, List<DutyAssignment>> byTeacher = attempt.duties().stream()
        .collect(Collectors.groupingBy(DutyAssignment::teacherId));
    int consecutive = 0;
    for (DutyAssignment duty : attempt.duties()) {
      Teacher teacher = teachers.get(duty.teacherId());
      ExamDemand demand = demands.get(duty.demandId());
      DutyNeed need = new DutyNeed(demand, duty.roomId(), duty.role(), duty.position(), duty.start(), duty.end(),
          rooms.get(duty.roomId()).campus(), candidates.getOrDefault(duty.demandId(), List.of()));
      care += carePenalty(teacher);
      avoidance += softAvoidancePenalty(teacher, need);
      campus += campusPenalty(teacher, need.campus());
    }
    for (List<DutyAssignment> teacherDuties : byTeacher.values()) {
      teacherDuties.sort(Comparator.comparing(DutyAssignment::start));
      for (int index = 1; index < teacherDuties.size(); index++) {
        DutyAssignment previous = teacherDuties.get(index - 1);
        DutyAssignment current = teacherDuties.get(index);
        if (previous.start().atZone(ZoneOffset.UTC).toLocalDate()
            .equals(current.start().atZone(ZoneOffset.UTC).toLocalDate())
            && Duration.between(previous.end(), current.start()).toMinutes() <= 30) {
          consecutive++;
        }
      }
    }
    return new Objective(lockedChanges, splits, variance, consecutive, care, avoidance, campus);
  }

  private List<Diagnostic> validate(Snapshot snapshot, PlanningControl control) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    if (snapshot.demands().isEmpty()) {
      diagnostics.add(diagnostic("NO_EXAM_DEMANDS", INPUT, "The revision contains no exam demands",
          List.of(), List.of("Import or create at least one exam demand"), Map.of()));
      return diagnostics;
    }
    checkUnique(snapshot.demands(), ExamDemand::id, "DUPLICATE_DEMAND_ID", diagnostics);
    checkUnique(snapshot.candidates(), Candidate::id, "DUPLICATE_CANDIDATE_ID", diagnostics);
    checkUnique(snapshot.rooms(), Room::id, "DUPLICATE_ROOM_ID", diagnostics);
    checkUnique(snapshot.teachers(), Teacher::id, "DUPLICATE_TEACHER_ID", diagnostics);
    Map<UUID, ExamDemand> demands = uniqueMap(snapshot.demands(), ExamDemand::id);
    Map<UUID, Candidate> candidates = uniqueMap(snapshot.candidates(), Candidate::id);
    for (ExamDemand demand : snapshot.demands()) {
      checkpoint(control);
      if (demand.windows().isEmpty()) {
        diagnostics.add(diagnostic("DEMAND_WITHOUT_WINDOW", INPUT,
            "An exam demand has no allowed time window", ids(demand.id()),
            List.of("Add at least one demand window"), Map.of("demandCode", demand.code())));
      }
      Set<UUID> windowIds = new HashSet<>();
      for (DemandWindow window : demand.windows()) {
        if (!windowIds.add(window.id())) {
          diagnostics.add(diagnostic("DUPLICATE_WINDOW_ID", INPUT,
              "A demand contains a duplicate window id", ids(demand.id(), window.id()),
              List.of("De-duplicate demand windows"), Map.of()));
        }
      }
      if (!demand.splitAllowed() && demand.minRooms() > 1) {
        diagnostics.add(diagnostic("INVALID_ROOM_SPLIT_POLICY", INPUT,
            "A non-splittable demand cannot require multiple rooms", ids(demand.id()),
            List.of("Enable splitting", "Set minRooms to one"), Map.of()));
      }
    }
    Set<Enrollment> enrollmentKeys = new HashSet<>();
    for (Enrollment enrollment : snapshot.enrollments()) {
      if (!candidates.containsKey(enrollment.candidateId()) || !demands.containsKey(enrollment.demandId())) {
        diagnostics.add(diagnostic("ORPHAN_ENROLLMENT", INPUT,
            "An enrollment references a candidate or demand outside the snapshot",
            ids(enrollment.candidateId(), enrollment.demandId()),
            List.of("Repair the import references"), Map.of()));
      } else if (!enrollmentKeys.add(enrollment)) {
        diagnostics.add(diagnostic("DUPLICATE_ENROLLMENT", INPUT,
            "The same candidate is enrolled twice in a demand",
            ids(enrollment.candidateId(), enrollment.demandId()),
            List.of("De-duplicate enrollments"), Map.of()));
      }
    }
    for (SpecialRequirement requirement : snapshot.specialRequirements()) {
      UUID demandId = requirement.demandId();
      if (!candidates.containsKey(requirement.candidateId())
          || (demandId != null && !enrollmentKeys.contains(new Enrollment(requirement.candidateId(), demandId)))) {
        diagnostics.add(diagnostic("ORPHAN_SPECIAL_REQUIREMENT", INPUT,
            "A special requirement is not attached to a valid enrolled candidate",
            ids(requirement.candidateId(), demandId), List.of("Repair the special-needs import"), Map.of()));
      }
    }
    for (LockedSession locked : snapshot.lockedSessions()) {
      ExamDemand demand = demands.get(locked.demandId());
      if (demand == null || demand.windows().stream().noneMatch(window -> window.id().equals(locked.windowId()))) {
        diagnostics.add(diagnostic("ORPHAN_LOCKED_SESSION", INPUT,
            "A locked session references an unknown demand window",
            ids(locked.demandId(), locked.windowId()), List.of("Remove or repair the stale lock"), Map.of()));
      }
    }
    return diagnostics;
  }

  private boolean hasAlternative(Snapshot snapshot, WindowChoice feedback, Set<WindowChoice> noGoods) {
    return snapshot.demands().stream().filter(demand -> demand.id().equals(feedback.demandId())).findFirst()
        .map(demand -> demand.windows().stream().anyMatch(window -> !window.id().equals(feedback.windowId())
            && !noGoods.contains(new WindowChoice(demand.id(), window.id()))))
        .orElse(false);
  }

  private Result terminal(
      ResultStatus status,
      List<Diagnostic> diagnostics,
      long seed,
      int iterations,
      String inputHash,
      long started
  ) {
    Map<String, Object> metrics = Map.of(
        "hardConstraintViolations", diagnostics.size(),
        "elapsedMillis", elapsedMillis(started),
        "partialAssignmentsExposed", false);
    String resultHash = sha256(status.name() + '|' + inputHash + '|'
        + diagnostics.stream().map(Diagnostic::code).sorted().collect(Collectors.joining(",")));
    return new Result(status, List.of(), List.of(), List.of(), List.of(), diagnostics, null, metrics,
        SOLVER_VERSION, seed, iterations, inputHash, resultHash);
  }

  private List<Diagnostic> compactDiagnostics(ExamDemand demand, List<Diagnostic> rejected) {
    if (rejected.isEmpty()) {
      return List.of(diagnostic("NO_FEASIBLE_TIME_WINDOW", TIME,
          "No feasible time window was found", ids(demand.id()),
          List.of("Add time, room, or staff capacity"), Map.of()));
    }
    Set<String> codes = rejected.stream().map(Diagnostic::code).collect(Collectors.toCollection(LinkedHashSet::new));
    if (codes.size() == 1) {
      return List.of(rejected.getFirst());
    }
    return List.of(diagnostic("NO_FEASIBLE_TIME_WINDOW", primaryStage(rejected),
        "Every demand window violates at least one hard constraint", ids(demand.id()),
        List.of("Review the reported causes", "Add an alternative time window"),
        Map.of("causes", List.copyOf(codes))));
  }

  private DiagnosticStage primaryStage(List<Diagnostic> diagnostics) {
    if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.stage() == SEAT)) {
      return SEAT;
    }
    if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.stage() == ROOM)) {
      return ROOM;
    }
    return TIME;
  }

  private Map<UUID, CandidateNeed> candidateNeeds(
      List<Candidate> candidates,
      List<SpecialRequirement> requirements
  ) {
    Map<UUID, Set<String>> features = new HashMap<>();
    Map<UUID, Boolean> separate = new HashMap<>();
    for (SpecialRequirement requirement : requirements) {
      features.computeIfAbsent(requirement.candidateId(), ignored -> new HashSet<>())
          .addAll(requirement.requiredFeatures());
      separate.merge(requirement.candidateId(), requirement.separateRoom(), Boolean::logicalOr);
    }
    Map<UUID, CandidateNeed> result = new LinkedHashMap<>();
    candidates.forEach(candidate -> result.put(candidate.id(), new CandidateNeed(candidate,
        Set.copyOf(features.getOrDefault(candidate.id(), Set.of())),
        separate.getOrDefault(candidate.id(), false))));
    return result;
  }

  private static Map<UUID, List<Candidate>> candidatesByDemand(Snapshot snapshot) {
    Map<UUID, Candidate> candidates = snapshot.candidates().stream()
        .collect(Collectors.toMap(Candidate::id, Function.identity()));
    Map<UUID, List<Candidate>> result = new HashMap<>();
    snapshot.enrollments().forEach(enrollment -> {
      Candidate candidate = candidates.get(enrollment.candidateId());
      if (candidate != null) {
        result.computeIfAbsent(enrollment.demandId(), ignored -> new ArrayList<>()).add(candidate);
      }
    });
    result.values().forEach(list -> list.sort(Comparator.comparing(Candidate::code)
        .thenComparing(candidate -> candidate.id().toString())));
    return result;
  }

  private Set<UUID> effectiveClassIds(ExamDemand demand, List<Candidate> candidates) {
    Set<UUID> result = new HashSet<>(demand.classIds());
    candidates.stream().map(Candidate::classId).filter(Objects::nonNull).forEach(result::add);
    return Set.copyOf(result);
  }

  private int usableCapacity(Room room) {
    long active = room.seats().stream().filter(Seat::active).count();
    return (int) Math.min(room.examCapacity(), active);
  }

  private boolean resourceAvailable(
      List<TimeInterval> available,
      List<TimeInterval> unavailable,
      Instant start,
      Instant end
  ) {
    boolean inside = available.isEmpty() || available.stream().anyMatch(window -> window.contains(start, end));
    return inside && unavailable.stream().noneMatch(window -> window.overlaps(start, end));
  }

  private boolean overlaps(List<TimeInterval> intervals, Instant start, Instant end) {
    return intervals != null && intervals.stream().anyMatch(interval -> interval.overlaps(start, end));
  }

  private boolean unionContains(Set<String> left, Set<String> right, Set<String> required) {
    if (required.isEmpty()) {
      return true;
    }
    Set<String> union = new HashSet<>(left);
    union.addAll(right);
    return union.containsAll(required);
  }

  private boolean disjoint(Set<UUID> left, Set<UUID> right) {
    return left.stream().noneMatch(right::contains);
  }

  private String dominantTeacherFailure(Map<String, Integer> rejections) {
    if (rejections.size() == 1) {
      return rejections.keySet().iterator().next();
    }
    return "DUTY_STAFF_SHORTAGE";
  }

  private String teacherFailureMessage(String code) {
    return switch (code) {
      case "DUTY_QUALIFICATION_SHORTAGE" -> "No teacher has a valid qualification for the duty role";
      case "TEACHER_AVOIDANCE_CONFLICT" -> "Every otherwise eligible teacher has a hard avoidance relationship";
      case "TEACHER_DAILY_LIMIT_EXCEEDED" -> "Every eligible teacher has reached the daily duty limit";
      case "TEACHER_UNAVAILABLE" -> "Every eligible teacher is unavailable or on leave";
      case "TEACHER_TIME_CONFLICT" -> "Every eligible teacher already has an overlapping duty";
      case "CAMPUS_TRAVEL_RULE_MISSING" -> "A required cross-campus transition has no travel-time rule";
      case "CAMPUS_TRAVEL_CONFLICT" -> "Cross-campus travel and buffer time cannot be satisfied";
      case "TEACHER_REST_CONFLICT" -> "The configured minimum rest time cannot be satisfied";
      default -> "No eligible teacher can cover the required duty";
    };
  }

  private List<String> teacherSuggestions(String code) {
    return switch (code) {
      case "DUTY_QUALIFICATION_SHORTAGE" -> List.of("Add a qualified chief/invigilator", "Renew expired qualifications");
      case "TEACHER_AVOIDANCE_CONFLICT" -> List.of("Add staff without the avoidance relationship", "Review the avoidance rule");
      case "TEACHER_DAILY_LIMIT_EXCEEDED" -> List.of("Increase staff capacity", "Move a demand to another day");
      case "CAMPUS_TRAVEL_RULE_MISSING" -> List.of("Configure campus travel time", "Keep duties on one campus");
      case "CAMPUS_TRAVEL_CONFLICT" -> List.of("Increase the transition gap", "Assign another teacher");
      default -> List.of("Move the demand to another window", "Add eligible teacher capacity");
    };
  }

  private void reject(Map<String, Integer> rejections, String code) {
    rejections.merge(code, 1, Integer::sum);
  }

  private void checkpoint(PlanningControl control) {
    if (control.cancellationRequested().getAsBoolean()) {
      throw new PlanningAbort(false, "Planning was cancelled before a partial result was exposed");
    }
    if (control.deadline() != null && !Instant.now().isBefore(control.deadline())) {
      throw new PlanningAbort(true, "Planning exceeded its deadline before a complete result was available");
    }
  }

  private long seededTie(long seed, UUID... ids) {
    long value = seed ^ 0x9E3779B97F4A7C15L;
    for (UUID id : ids) {
      value ^= id.getMostSignificantBits();
      value = Long.rotateLeft(value * 0xBF58476D1CE4E5B9L, 27);
      value ^= id.getLeastSignificantBits();
      value = Long.rotateLeft(value * 0x94D049BB133111EBL, 31);
    }
    return value ^ (value >>> 29);
  }

  private String inputHash(Snapshot snapshot, long seed) {
    StringBuilder canonical = new StringBuilder(snapshot.revisionKey()).append('|').append(seed);
    snapshot.demands().stream().sorted(Comparator.comparing(demand -> demand.id().toString()))
        .forEach(demand -> {
          canonical.append("|D:").append(demand.id()).append(':').append(demand.code())
              .append(':').append(demand.name()).append(':').append(demand.subjectId())
              .append(':').append(demand.durationMinutes())
              .append(':').append(demand.invigilatorsPerRoom()).append(':').append(demand.minRooms())
              .append(':').append(demand.maxRooms()).append(':').append(demand.splitAllowed())
              .append(':').append(sorted(demand.requiredRoomFeatures()))
              .append(':').append(sortedUuids(demand.classIds()));
          demand.windows().stream().sorted(Comparator.comparing(window -> window.id().toString()))
              .forEach(window -> canonical.append("@W:").append(window.id()).append(':')
                  .append(window.start()).append(':').append(window.end()).append(':')
                  .append(window.preferenceWeight()).append(':').append(window.locked()));
        });
    snapshot.candidates().stream().sorted(Comparator.comparing(candidate -> candidate.id().toString()))
        .forEach(candidate -> canonical.append("|C:").append(candidate.id()).append(':')
            .append(candidate.code()).append(':').append(candidate.classId())
            .append(':').append(intervals(candidate.availableWindows()))
            .append(':').append(intervals(candidate.unavailableWindows())));
    snapshot.enrollments().stream().sorted(Comparator.comparing((Enrollment value) -> value.demandId().toString())
        .thenComparing(value -> value.candidateId().toString()))
        .forEach(value -> canonical.append("|E:").append(value.demandId()).append(':').append(value.candidateId()));
    snapshot.specialRequirements().stream()
        .sorted(Comparator.comparing((SpecialRequirement value) -> value.candidateId().toString())
            .thenComparing(value -> value.demandId() == null ? "" : value.demandId().toString())
            .thenComparing(SpecialRequirement::requirementCode))
        .forEach(value -> canonical.append("|N:").append(value.candidateId()).append(':')
            .append(value.demandId()).append(':').append(value.requirementCode()).append(':')
            .append(sorted(value.requiredFeatures())).append(':').append(value.extraMinutes())
            .append(':').append(value.separateRoom()));
    snapshot.rooms().stream().sorted(Comparator.comparing(room -> room.id().toString()))
        .forEach(room -> canonical.append("|R:").append(room.id()).append(':').append(room.code())
            .append(':').append(room.campus())
            .append(':').append(room.examCapacity()).append(':').append(sorted(room.features()))
            .append(':').append(room.included()).append(':').append(room.locked())
            .append(':').append(intervals(room.availableWindows()))
            .append(':').append(intervals(room.unavailableWindows()))
            .append(':').append(room.seats().stream()
                .sorted(Comparator.comparing(seat -> seat.id().toString()))
                .map(seat -> seat.id() + ":" + seat.code() + ":" + seat.row() + ":"
                    + seat.column() + ":" + sorted(seat.features()) + ":" + seat.active())
                .toList()));
    snapshot.teachers().stream().sorted(Comparator.comparing(teacher -> teacher.id().toString()))
        .forEach(teacher -> canonical.append("|T:").append(teacher.id()).append(':')
            .append(teacher.code()).append(':').append(teacher.name()).append(':')
            .append(sorted(teacher.eligibleRoles())).append(':').append(teacher.qualificationExpiry())
            .append(':').append(teacher.maxDailySessions()).append(':').append(teacher.minRestMinutes())
            .append(':').append(teacher.preferredCampus()).append(':').append(teacher.status())
            .append(':').append(intervals(teacher.availableWindows()))
            .append(':').append(intervals(teacher.unavailableWindows()))
            .append(':').append(sortedUuids(teacher.hardAvoidDemandIds()))
            .append(':').append(sortedUuids(teacher.hardAvoidClassIds()))
            .append(':').append(sortedUuids(teacher.hardAvoidCandidateIds()))
            .append(':').append(sortedUuids(teacher.softAvoidDemandIds()))
            .append(':').append(sortedUuids(teacher.homeroomClassIds()))
            .append(':').append(sortedUuids(teacher.subjectIds()))
            .append(':').append(teacher.age()).append(':').append(teacher.healthRestricted())
            .append(':').append(teacher.positionWeight()));
    snapshot.campusTravel().stream()
        .sorted(Comparator.comparing(CampusTravel::fromCampus).thenComparing(CampusTravel::toCampus))
        .forEach(value -> canonical.append("|X:").append(value.fromCampus()).append(':')
            .append(value.toCampus()).append(':').append(value.travelMinutes()).append(':')
            .append(value.bufferMinutes()));
    snapshot.lockedSessions().stream()
        .sorted(Comparator.comparing(value -> value.demandId().toString()))
        .forEach(value -> canonical.append("|LS:").append(value.demandId()).append(':').append(value.windowId()));
    snapshot.lockedRooms().stream()
        .sorted(Comparator.comparing((LockedRoom value) -> value.demandId().toString())
            .thenComparing(value -> value.roomId().toString()))
        .forEach(value -> canonical.append("|LR:").append(value.demandId()).append(':').append(value.roomId()));
    snapshot.lockedDuties().stream()
        .sorted(Comparator.comparing((LockedDuty value) -> value.demandId().toString())
            .thenComparing(value -> value.roomId().toString()).thenComparing(LockedDuty::role)
            .thenComparingInt(LockedDuty::position))
        .forEach(value -> canonical.append("|LD:").append(value.demandId()).append(':')
            .append(value.roomId()).append(':').append(value.role()).append(':')
            .append(value.position()).append(':').append(value.teacherId()));
    return sha256(canonical.toString());
  }

  private String resultHash(Attempt attempt, Objective objective, long seed) {
    StringBuilder canonical = new StringBuilder(Long.toString(seed));
    attempt.sessions().stream().sorted(Comparator.comparing(value -> value.demandId().toString()))
        .forEach(value -> canonical.append("|S:").append(value));
    attempt.rooms().stream().sorted(Comparator.comparing((RoomAssignment value) -> value.demandId().toString())
        .thenComparing(value -> value.roomId().toString()))
        .forEach(value -> canonical.append("|R:").append(value));
    attempt.seats().stream().sorted(Comparator.comparing((SeatAssignment value) -> value.demandId().toString())
        .thenComparing(value -> value.candidateId().toString()))
        .forEach(value -> canonical.append("|A:").append(value));
    attempt.duties().stream().sorted(Comparator.comparing((DutyAssignment value) -> value.demandId().toString())
        .thenComparing(value -> value.roomId().toString()).thenComparingInt(DutyAssignment::position))
        .forEach(value -> canonical.append("|T:").append(value));
    return sha256(canonical.append("|O:").append(objective).toString());
  }

  private String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private long elapsedMillis(long started) {
    return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
  }

  private static Diagnostic diagnostic(
      String code,
      DiagnosticStage stage,
      String message,
      List<String> affected,
      List<String> suggestions,
      Map<String, Object> details
  ) {
    return new Diagnostic(code, stage, message, affected, suggestions, details);
  }

  private static List<String> ids(UUID... values) {
    List<String> result = new ArrayList<>();
    for (UUID value : values) {
      if (value != null) {
        result.add(value.toString());
      }
    }
    return List.copyOf(result);
  }

  private static List<String> sorted(Collection<String> values) {
    return values.stream().sorted().toList();
  }

  private static List<String> sortedUuids(Collection<UUID> values) {
    return values.stream().map(UUID::toString).sorted().toList();
  }

  private static List<String> intervals(Collection<TimeInterval> values) {
    return values.stream().map(value -> value.start() + "/" + value.end()).sorted().toList();
  }

  private <T> void checkUnique(
      List<T> values,
      Function<T, UUID> id,
      String code,
      List<Diagnostic> diagnostics
  ) {
    Set<UUID> seen = new HashSet<>();
    values.forEach(value -> {
      UUID key = id.apply(value);
      if (!seen.add(key)) {
        diagnostics.add(diagnostic(code, INPUT, "The snapshot contains a duplicate resource id",
            ids(key), List.of("De-duplicate the version input"), Map.of()));
      }
    });
  }

  private <T> Map<UUID, T> uniqueMap(List<T> values, Function<T, UUID> id) {
    Map<UUID, T> result = new HashMap<>();
    values.forEach(value -> result.putIfAbsent(id.apply(value), value));
    return result;
  }

  private static final class PlannerContext {
    private final Snapshot snapshot;
    private final long seed;
    private final Map<UUID, ExamDemand> demandById;
    private final Map<UUID, Room> roomById;
    private final Map<UUID, List<Candidate>> candidatesByDemand;
    private final Map<UUID, List<SpecialRequirement>> requirementsByDemand = new HashMap<>();
    private final Map<UUID, UUID> lockedWindowByDemand;
    private final Map<UUID, Set<UUID>> lockedRoomsByDemand = new HashMap<>();
    private final Map<LockedDutyKey, UUID> lockedTeacherByDuty;
    private final Map<CampusPair, Integer> travelMinutes = new HashMap<>();
    private final Map<UUID, List<TimeInterval>> candidateOccupancy = new HashMap<>();
    private final Map<UUID, List<TimeInterval>> classOccupancy = new HashMap<>();
    private final Map<UUID, List<TimeInterval>> roomOccupancy = new HashMap<>();
    private final List<SessionAssignment> sessions = new ArrayList<>();
    private final List<RoomAssignment> roomAssignments = new ArrayList<>();
    private final List<SeatAssignment> seatAssignments = new ArrayList<>();

    private PlannerContext(Snapshot snapshot, long seed) {
      this.snapshot = snapshot;
      this.seed = seed;
      demandById = snapshot.demands().stream().collect(Collectors.toMap(ExamDemand::id, Function.identity()));
      roomById = snapshot.rooms().stream().collect(Collectors.toMap(Room::id, Function.identity()));
      candidatesByDemand = DeterministicJointPlanner.candidatesByDemand(snapshot);
      Map<UUID, Set<UUID>> enrolledDemands = new HashMap<>();
      snapshot.enrollments().forEach(enrollment -> enrolledDemands
          .computeIfAbsent(enrollment.candidateId(), ignored -> new HashSet<>()).add(enrollment.demandId()));
      snapshot.specialRequirements().forEach(requirement -> {
        Collection<UUID> demandIds = requirement.demandId() == null
            ? enrolledDemands.getOrDefault(requirement.candidateId(), Set.of())
            : List.of(requirement.demandId());
        demandIds.forEach(demandId -> requirementsByDemand
            .computeIfAbsent(demandId, ignored -> new ArrayList<>()).add(requirement));
      });
      lockedWindowByDemand = snapshot.lockedSessions().stream().collect(Collectors.toMap(
          LockedSession::demandId, LockedSession::windowId, (first, ignored) -> first));
      snapshot.lockedRooms().forEach(lock -> lockedRoomsByDemand
          .computeIfAbsent(lock.demandId(), ignored -> new HashSet<>()).add(lock.roomId()));
      lockedTeacherByDuty = snapshot.lockedDuties().stream().collect(Collectors.toMap(
          lock -> new LockedDutyKey(lock.demandId(), lock.roomId(), lock.role(), lock.position()),
          LockedDuty::teacherId, (first, ignored) -> first));
      snapshot.campusTravel().forEach(rule -> travelMinutes.put(
          new CampusPair(rule.fromCampus(), rule.toCampus()), rule.travelMinutes() + rule.bufferMinutes()));
    }

    private void commit(DemandPlacement placement) {
      sessions.add(placement.session());
      roomAssignments.addAll(placement.rooms());
      seatAssignments.addAll(placement.seats());
      TimeInterval interval = new TimeInterval(placement.session().start(), placement.session().end());
      placement.candidates().forEach(candidate -> candidateOccupancy
          .computeIfAbsent(candidate.id(), ignored -> new ArrayList<>()).add(interval));
      placement.classIds().forEach(classId -> classOccupancy
          .computeIfAbsent(classId, ignored -> new ArrayList<>()).add(interval));
      placement.rooms().forEach(room -> roomOccupancy
          .computeIfAbsent(room.roomId(), ignored -> new ArrayList<>()).add(interval));
    }
  }

  private static final class MutableRoom {
    private final Room room;
    private final List<Seat> activeSeats;
    private final Map<UUID, UUID> candidateBySeat = new LinkedHashMap<>();
    private boolean dedicated;

    private MutableRoom(Room room) {
      this.room = room;
      this.activeSeats = room.seats().stream().filter(Seat::active)
          .sorted(Comparator.comparing((Seat seat) -> seat.row() == null ? Integer.MAX_VALUE : seat.row())
              .thenComparing(seat -> seat.column() == null ? Integer.MAX_VALUE : seat.column())
              .thenComparing(Seat::code).thenComparing(seat -> seat.id().toString()))
          .toList();
    }

    private SeatChoice findSeat(CandidateNeed need) {
      if (candidateBySeat.size() >= room.examCapacity() || dedicated) {
        return null;
      }
      return activeSeats.stream().filter(seat -> !candidateBySeat.containsKey(seat.id()))
          .filter(seat -> {
            Set<String> union = new HashSet<>(room.features());
            union.addAll(seat.features());
            return union.containsAll(need.features());
          }).findFirst().map(seat -> new SeatChoice(this, seat)).orElse(null);
    }

    private void assign(Seat seat, UUID candidateId, boolean separate) {
      candidateBySeat.put(seat.id(), candidateId);
      dedicated = separate;
    }
  }

  private record CandidateNeed(Candidate candidate, Set<String> features, boolean separateRoom) {
  }

  private record SeatChoice(MutableRoom room, Seat seat) {
  }

  private record DutyNeed(
      ExamDemand demand,
      UUID roomId,
      String role,
      int position,
      Instant start,
      Instant end,
      String campus,
      List<Candidate> candidates
  ) {
  }

  private record AssignedDuty(
      Instant start,
      Instant end,
      String campus,
      UUID demandId,
      UUID roomId,
      String role,
      int position
  ) {
  }

  private record WindowChoice(UUID demandId, UUID windowId) {
  }

  private record CampusPair(String from, String to) {
  }

  private record LockedRoomKey(UUID demandId, UUID roomId) {
  }

  private record LockedDutyKey(UUID demandId, UUID roomId, String role, int position) {
  }

  private record DemandPlacement(
      boolean success,
      SessionAssignment session,
      List<RoomAssignment> rooms,
      List<SeatAssignment> seats,
      List<Candidate> candidates,
      Set<UUID> classIds,
      DiagnosticStage failureStage,
      List<Diagnostic> diagnostics
  ) {
    private static DemandPlacement success(
        SessionAssignment session,
        List<RoomAssignment> rooms,
        List<SeatAssignment> seats,
        List<Candidate> candidates,
        Set<UUID> classIds
    ) {
      return new DemandPlacement(true, session, rooms, seats, candidates, classIds, null, List.of());
    }

    private static DemandPlacement failure(DiagnosticStage stage, List<Diagnostic> diagnostics) {
      return new DemandPlacement(false, null, List.of(), List.of(), List.of(), Set.of(), stage, diagnostics);
    }
  }

  private record RoomPlan(boolean success, List<RoomAssignment> rooms, List<SeatAssignment> seats,
                          List<Diagnostic> diagnostics) {
    private static RoomPlan success(List<RoomAssignment> rooms, List<SeatAssignment> seats) {
      return new RoomPlan(true, List.copyOf(rooms), List.copyOf(seats), List.of());
    }

    private static RoomPlan failure(Diagnostic diagnostic) {
      return new RoomPlan(false, List.of(), List.of(), List.of(diagnostic));
    }
  }

  private record TeacherPlan(
      boolean success,
      List<DutyAssignment> assignments,
      List<Diagnostic> diagnostics,
      WindowChoice feedbackChoice
  ) {
    private static TeacherPlan success(List<DutyAssignment> assignments) {
      return new TeacherPlan(true, assignments, List.of(), null);
    }

    private static TeacherPlan failure(List<Diagnostic> diagnostics, WindowChoice feedbackChoice) {
      return new TeacherPlan(false, List.of(), diagnostics, feedbackChoice);
    }
  }

  private record Attempt(
      boolean success,
      List<SessionAssignment> sessions,
      List<RoomAssignment> rooms,
      List<SeatAssignment> seats,
      List<DutyAssignment> duties,
      DiagnosticStage failureStage,
      List<Diagnostic> diagnostics,
      WindowChoice feedbackChoice
  ) {
    private static Attempt success(
        List<SessionAssignment> sessions,
        List<RoomAssignment> rooms,
        List<SeatAssignment> seats,
        List<DutyAssignment> duties
    ) {
      return new Attempt(true, sessions, rooms, seats, duties, null, List.of(), null);
    }

    private static Attempt failure(
        DiagnosticStage stage,
        List<Diagnostic> diagnostics,
        WindowChoice feedbackChoice
    ) {
      return new Attempt(false, List.of(), List.of(), List.of(), List.of(), stage, diagnostics, feedbackChoice);
    }
  }

  private static final class PlanningAbort extends RuntimeException {
    private final boolean deadline;

    private PlanningAbort(boolean deadline, String message) {
      super(message);
      this.deadline = deadline;
    }
  }
}
