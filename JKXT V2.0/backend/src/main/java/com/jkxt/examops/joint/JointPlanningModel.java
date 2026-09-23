package com.jkxt.examops.joint;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Immutable input and output contracts for the in-memory joint examination planner.
 *
 * <p>The records deliberately contain no persistence types. A database adapter can build a
 * {@link Snapshot}, invoke the planner, validate the returned result, and only then persist the
 * complete version graph in one transaction.</p>
 */
public final class JointPlanningModel {

  private JointPlanningModel() {
  }

  public enum ResultStatus {
    SUCCESS,
    INFEASIBLE,
    CANCELLED,
    DEADLINE_EXCEEDED
  }

  public enum TeacherStatus {
    INCLUDED,
    RESERVE,
    EXCLUDED
  }

  public enum DiagnosticStage {
    INPUT,
    TIME,
    ROOM,
    SEAT,
    DUTY,
    CONTROL
  }

  public record TimeInterval(Instant start, Instant end) {
    public TimeInterval {
      if (start == null || end == null || !end.isAfter(start)) {
        throw new IllegalArgumentException("time interval end must be after start");
      }
    }

    public boolean contains(Instant candidateStart, Instant candidateEnd) {
      return !start.isAfter(candidateStart) && !end.isBefore(candidateEnd);
    }

    public boolean overlaps(Instant candidateStart, Instant candidateEnd) {
      return start.isBefore(candidateEnd) && end.isAfter(candidateStart);
    }
  }

  public record DemandWindow(
      UUID id,
      Instant start,
      Instant end,
      int preferenceWeight,
      boolean locked
  ) {
    public DemandWindow {
      requireId(id, "window id");
      if (start == null || end == null || !end.isAfter(start)) {
        throw new IllegalArgumentException("demand window end must be after start");
      }
    }
  }

  public record ExamDemand(
      UUID id,
      String code,
      String name,
      UUID subjectId,
      int durationMinutes,
      int invigilatorsPerRoom,
      int minRooms,
      int maxRooms,
      boolean splitAllowed,
      Set<String> requiredRoomFeatures,
      Set<UUID> classIds,
      List<DemandWindow> windows
  ) {
    public ExamDemand {
      requireId(id, "demand id");
      code = requireText(code, "demand code");
      name = requireText(name, "demand name");
      if (durationMinutes <= 0 || invigilatorsPerRoom <= 0 || minRooms <= 0
          || maxRooms < minRooms) {
        throw new IllegalArgumentException("invalid demand duration, duty count, or room bounds");
      }
      requiredRoomFeatures = immutableSet(requiredRoomFeatures);
      classIds = immutableSet(classIds);
      windows = immutableList(windows);
    }
  }

  public record Candidate(
      UUID id,
      String code,
      UUID classId,
      List<TimeInterval> availableWindows,
      List<TimeInterval> unavailableWindows
  ) {
    public Candidate {
      requireId(id, "candidate id");
      code = requireText(code, "candidate code");
      availableWindows = immutableList(availableWindows);
      unavailableWindows = immutableList(unavailableWindows);
    }
  }

  public record Enrollment(UUID candidateId, UUID demandId) {
    public Enrollment {
      requireId(candidateId, "enrollment candidate id");
      requireId(demandId, "enrollment demand id");
    }
  }

  public record SpecialRequirement(
      UUID candidateId,
      UUID demandId,
      String requirementCode,
      Set<String> requiredFeatures,
      int extraMinutes,
      boolean separateRoom
  ) {
    public SpecialRequirement {
      requireId(candidateId, "special-requirement candidate id");
      requirementCode = requireText(requirementCode, "special-requirement code");
      requiredFeatures = immutableSet(requiredFeatures);
      if (extraMinutes < 0) {
        throw new IllegalArgumentException("extra minutes cannot be negative");
      }
    }
  }

  public record Seat(
      UUID id,
      String code,
      Integer row,
      Integer column,
      Set<String> features,
      boolean active
  ) {
    public Seat {
      requireId(id, "seat id");
      code = requireText(code, "seat code");
      if ((row != null && row <= 0) || (column != null && column <= 0)) {
        throw new IllegalArgumentException("seat row and column must be positive when present");
      }
      features = immutableSet(features);
    }
  }

  public record Room(
      UUID id,
      String code,
      String campus,
      int examCapacity,
      Set<String> features,
      List<TimeInterval> availableWindows,
      List<TimeInterval> unavailableWindows,
      List<Seat> seats,
      boolean included,
      boolean locked
  ) {
    public Room {
      requireId(id, "room id");
      code = requireText(code, "room code");
      campus = requireText(campus, "room campus");
      if (examCapacity <= 0) {
        throw new IllegalArgumentException("room exam capacity must be positive");
      }
      features = immutableSet(features);
      availableWindows = immutableList(availableWindows);
      unavailableWindows = immutableList(unavailableWindows);
      seats = immutableList(seats);
    }
  }

  public record Teacher(
      UUID id,
      String code,
      String name,
      Set<String> eligibleRoles,
      Instant qualificationExpiry,
      int maxDailySessions,
      int minRestMinutes,
      String preferredCampus,
      TeacherStatus status,
      List<TimeInterval> availableWindows,
      List<TimeInterval> unavailableWindows,
      Set<UUID> hardAvoidDemandIds,
      Set<UUID> hardAvoidClassIds,
      Set<UUID> hardAvoidCandidateIds,
      Set<UUID> softAvoidDemandIds,
      Set<UUID> homeroomClassIds,
      Set<UUID> subjectIds,
      int age,
      boolean healthRestricted,
      int positionWeight
  ) {
    public Teacher {
      requireId(id, "teacher id");
      code = requireText(code, "teacher code");
      name = requireText(name, "teacher name");
      eligibleRoles = immutableSet(eligibleRoles);
      if (maxDailySessions <= 0 || minRestMinutes < 0 || age < 0 || positionWeight < 0) {
        throw new IllegalArgumentException("invalid teacher limits or care attributes");
      }
      status = status == null ? TeacherStatus.EXCLUDED : status;
      availableWindows = immutableList(availableWindows);
      unavailableWindows = immutableList(unavailableWindows);
      hardAvoidDemandIds = immutableSet(hardAvoidDemandIds);
      hardAvoidClassIds = immutableSet(hardAvoidClassIds);
      hardAvoidCandidateIds = immutableSet(hardAvoidCandidateIds);
      softAvoidDemandIds = immutableSet(softAvoidDemandIds);
      homeroomClassIds = immutableSet(homeroomClassIds);
      subjectIds = immutableSet(subjectIds);
    }
  }

  public record CampusTravel(
      String fromCampus,
      String toCampus,
      int travelMinutes,
      int bufferMinutes
  ) {
    public CampusTravel {
      fromCampus = requireText(fromCampus, "from campus");
      toCampus = requireText(toCampus, "to campus");
      if (fromCampus.equals(toCampus) || travelMinutes <= 0 || bufferMinutes < 0) {
        throw new IllegalArgumentException("invalid cross-campus travel rule");
      }
    }
  }

  public record LockedSession(UUID demandId, UUID windowId) {
    public LockedSession {
      requireId(demandId, "locked-session demand id");
      requireId(windowId, "locked-session window id");
    }
  }

  public record LockedRoom(UUID demandId, UUID roomId) {
    public LockedRoom {
      requireId(demandId, "locked-room demand id");
      requireId(roomId, "locked-room room id");
    }
  }

  public record LockedDuty(
      UUID demandId,
      UUID roomId,
      String role,
      int position,
      UUID teacherId
  ) {
    public LockedDuty {
      requireId(demandId, "locked-duty demand id");
      requireId(roomId, "locked-duty room id");
      role = requireText(role, "locked-duty role").toUpperCase();
      requireId(teacherId, "locked-duty teacher id");
      if (position < 0) {
        throw new IllegalArgumentException("locked-duty position cannot be negative");
      }
    }
  }

  public record Snapshot(
      String revisionKey,
      List<ExamDemand> demands,
      List<Candidate> candidates,
      List<Enrollment> enrollments,
      List<SpecialRequirement> specialRequirements,
      List<Room> rooms,
      List<Teacher> teachers,
      List<CampusTravel> campusTravel,
      List<LockedSession> lockedSessions,
      List<LockedRoom> lockedRooms,
      List<LockedDuty> lockedDuties
  ) {
    public Snapshot {
      revisionKey = requireText(revisionKey, "revision key");
      demands = immutableList(demands);
      candidates = immutableList(candidates);
      enrollments = immutableList(enrollments);
      specialRequirements = immutableList(specialRequirements);
      rooms = immutableList(rooms);
      teachers = immutableList(teachers);
      campusTravel = immutableList(campusTravel);
      lockedSessions = immutableList(lockedSessions);
      lockedRooms = immutableList(lockedRooms);
      lockedDuties = immutableList(lockedDuties);
    }
  }

  public record PlanningControl(
      long seed,
      Instant deadline,
      BooleanSupplier cancellationRequested,
      int maxFeedbackIterations
  ) {
    public PlanningControl {
      cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
      if (maxFeedbackIterations <= 0) {
        throw new IllegalArgumentException("max feedback iterations must be positive");
      }
    }

    public static PlanningControl unlimited(long seed) {
      return new PlanningControl(seed, null, () -> false, 64);
    }
  }

  public record SessionAssignment(
      UUID demandId,
      UUID windowId,
      Instant start,
      Instant end
  ) {
  }

  public record RoomAssignment(
      UUID demandId,
      UUID roomId,
      int splitIndex,
      int allocatedCandidates
  ) {
  }

  public record SeatAssignment(
      UUID demandId,
      UUID candidateId,
      UUID roomId,
      UUID seatId,
      Instant start,
      Instant end
  ) {
  }

  public record DutyAssignment(
      UUID demandId,
      UUID roomId,
      String role,
      int position,
      UUID teacherId,
      Instant start,
      Instant end
  ) {
  }

  public record Diagnostic(
      String code,
      DiagnosticStage stage,
      String message,
      List<String> affectedIds,
      List<String> suggestions,
      Map<String, Object> details
  ) {
    public Diagnostic {
      code = requireText(code, "diagnostic code");
      stage = stage == null ? DiagnosticStage.INPUT : stage;
      message = requireText(message, "diagnostic message");
      affectedIds = immutableList(affectedIds);
      suggestions = immutableList(suggestions);
      details = details == null ? Map.of() : Map.copyOf(details);
    }
  }

  public record Objective(
      int lockedChanges,
      int roomSplits,
      long workloadVariance,
      int consecutivePenalty,
      int carePenalty,
      int avoidancePenalty,
      int campusPenalty
  ) {
  }

  public record Result(
      ResultStatus status,
      List<SessionAssignment> sessions,
      List<RoomAssignment> rooms,
      List<SeatAssignment> seats,
      List<DutyAssignment> duties,
      List<Diagnostic> diagnostics,
      Objective objective,
      Map<String, Object> metrics,
      String solverVersion,
      long seed,
      int iterationCount,
      String inputHash,
      String resultHash
  ) {
    public Result {
      if (status == null) {
        throw new IllegalArgumentException("result status is required");
      }
      sessions = immutableList(sessions);
      rooms = immutableList(rooms);
      seats = immutableList(seats);
      duties = immutableList(duties);
      diagnostics = immutableList(diagnostics);
      metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
      solverVersion = requireText(solverVersion, "solver version");
      inputHash = requireText(inputHash, "input hash");
      resultHash = requireText(resultHash, "result hash");
      if (status != ResultStatus.SUCCESS
          && (!sessions.isEmpty() || !rooms.isEmpty() || !seats.isEmpty() || !duties.isEmpty())) {
        throw new IllegalArgumentException("non-success result must not expose partial assignments");
      }
    }
  }

  private static UUID requireId(UUID value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + " is required");
    }
    return value;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return value.trim();
  }

  private static <T> List<T> immutableList(List<T> value) {
    return value == null ? List.of() : List.copyOf(value);
  }

  private static <T> Set<T> immutableSet(Set<T> value) {
    return value == null ? Set.of() : Set.copyOf(value);
  }
}
