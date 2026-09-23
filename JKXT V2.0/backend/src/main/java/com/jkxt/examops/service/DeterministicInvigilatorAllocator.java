package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherWindow;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic, coverage-first allocator for the explicitly supported INVIGILATOR_ONLY mode.
 *
 * <p>The full joint time/room/seat problem remains outside this component. This allocator consumes
 * already locked examination/room slots, enforces teacher leave, duty windows and interval
 * exclusivity, and only reports success after every required post has an assignment.</p>
 */
@Component
public class DeterministicInvigilatorAllocator {

  private final ExamOperationsHardConstraints hardConstraints = new ExamOperationsHardConstraints();

  public ScheduleSolverResult allocate(ScheduleFixture fixture) {
    Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> legacyEligibility = new HashMap<>();
    fixture.teachers().forEach(teacher -> legacyEligibility.put(teacher.id(),
        new ExamOperationsHardConstraints.TeacherEligibility(
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
                .add("INVIGILATOR").add("CHIEF"), null, teacher.homeroom(), null, 4, null)));
    return allocate(fixture, legacyEligibility, Map.of(), Map.of(), Map.of());
  }

  public ScheduleSolverResult allocate(
      ScheduleFixture fixture,
      Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> eligibility
  ) {
    return allocate(fixture, eligibility, Map.of(), Map.of(), Map.of());
  }

  public ScheduleSolverResult allocate(
      ScheduleFixture fixture,
      Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> eligibility,
      Map<UUID, String> roomCampuses
  ) {
    return allocate(fixture, eligibility, roomCampuses, Map.of(), Map.of());
  }

  public ScheduleSolverResult allocate(
      ScheduleFixture fixture,
      Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> eligibility,
      Map<UUID, String> roomCampuses,
      Map<DutyKey, JsonNode> dutyQualifications,
      Map<UUID, List<ExistingDuty>> existingDuties
  ) {
    long started = System.nanoTime();
    List<ExamRoomInput> slots = fixture.examRooms().stream()
        .sorted(Comparator.comparing(ExamRoomInput::date)
            .thenComparing(ExamRoomInput::start)
            .thenComparing(ExamRoomInput::end)
            .thenComparing(slot -> slot.examId().toString())
            .thenComparing(slot -> slot.roomId().toString()))
        .toList();
    List<TeacherInput> teachers = fixture.teachers().stream()
        .sorted(Comparator.comparing(TeacherInput::teacherNo, Comparator.nullsLast(String::compareTo))
            .thenComparing(teacher -> teacher.id().toString()))
        .toList();
    Map<UUID, Integer> loads = new HashMap<>();
    Map<UUID, Map<LocalDate, Integer>> dailyLoads = baselineDailyLoads(existingDuties);
    Map<UUID, List<Interval>> assigned = baselineIntervals(existingDuties);
    List<ScheduleSolverResult.Assignment> assignments = new ArrayList<>();

    for (ExamRoomInput slot : slots) {
      LocalDateTime start = slot.date().atTime(slot.start());
      LocalDateTime end = slot.date().atTime(slot.end());
      if (!end.isAfter(start)) end = end.plusDays(1);
      for (int position = 0; position < slot.supervisorsPerRoom(); position++) {
        String dutyRole = position == 0 ? "CHIEF" : "INVIGILATOR";
        JsonNode persistedQualification = dutyQualifications == null ? null
            : dutyQualifications.get(new DutyKey(slot.examId(), slot.roomId(), dutyRole));
        JsonNode qualification = persistedQualification == null
            ? hardConstraints.defaultDutyQualification(dutyRole) : persistedQualification;
        LocalDateTime dutyStart = start;
        LocalDateTime dutyEnd = end;
        String roomCampus = roomCampuses == null ? null : roomCampuses.get(slot.roomId());
        List<TeacherInput> qualified = teachers.stream()
            .filter(teacher -> qualified(teacher, dutyRole, qualification, eligibility, dutyStart.toLocalDate()))
            .toList();
        List<TeacherInput> withinDailyLimit = qualified.stream()
            .filter(teacher -> dailyLoad(dailyLoads, teacher.id(), dutyStart.toLocalDate())
                < maxDailySessions(eligibility, teacher.id()))
            .toList();
        TeacherInput selected = withinDailyLimit.stream()
            .filter(teacher -> available(teacher, dutyStart, dutyEnd, assigned.getOrDefault(teacher.id(), List.of())))
            .min(Comparator.comparingInt((TeacherInput teacher) -> loads.getOrDefault(teacher.id(), 0))
                .thenComparingInt(teacher -> hardConstraints.campusPreferencePenalty(
                    eligibility == null ? null : eligibility.get(teacher.id()), roomCampus))
                .thenComparing(TeacherInput::teacherNo, Comparator.nullsLast(String::compareTo))
                .thenComparing(teacher -> teacher.id().toString()))
            .orElse(null);
        if (selected == null) {
          boolean intrinsicallyUnavailable = withinDailyLimit.stream()
              .noneMatch(teacher -> available(teacher, dutyStart, dutyEnd, List.of()));
          long elapsed = elapsedMillis(started);
          Map<String, Object> refs = new LinkedHashMap<>();
          refs.put("examId", slot.examId());
          refs.put("roomId", slot.roomId());
          refs.put("startsAt", dutyStart.toString());
          refs.put("endsAt", dutyEnd.toString());
          refs.put("requiredPosition", position + 1);
          String code = qualified.isEmpty() ? "DUTY_QUALIFICATION_SHORTAGE"
              : withinDailyLimit.isEmpty() ? "TEACHER_DAILY_LIMIT_EXCEEDED"
              : intrinsicallyUnavailable ? "INVIGILATOR_CAPACITY_SHORTAGE" : "DETERMINISTIC_ALLOCATION_EXHAUSTED";
          ScheduleSolverResult.ConflictItem conflict = new ScheduleSolverResult.ConflictItem(
              code,
              "teacher_availability_and_overlap",
              "hard",
              "exam-room",
              qualified.isEmpty()
                  ? "No active teacher satisfies the required " + dutyRole + " qualification"
                  : withinDailyLimit.isEmpty()
                      ? "Every qualified teacher has reached the configured daily session limit"
                  : "No leave-safe, duty-window-eligible and conflict-free teacher is available for every required post",
              Map.copyOf(refs));
          return new ScheduleSolverResult(
              qualified.isEmpty() || intrinsicallyUnavailable ? "infeasible" : "failed",
              qualified.isEmpty() || intrinsicallyUnavailable ? "DETERMINISTIC_INFEASIBLE" : "DETERMINISTIC_UNKNOWN",
              conflict.code(),
              conflict.message(), List.of(), List.of(conflict), List.of(),
              Map.of(
                  "solver", "deterministic-invigilator-interval",
                  "teachers", teachers.size(),
                  "rooms", slots.size(),
                  "dutiesRequired", slots.stream().mapToInt(ExamRoomInput::supervisorsPerRoom).sum(),
                  "dutiesAssigned", assignments.size()),
              elapsed, Instant.now());
        }
        loads.merge(selected.id(), 1, Integer::sum);
        dailyLoads.computeIfAbsent(selected.id(), ignored -> new HashMap<>())
            .merge(dutyStart.toLocalDate(), 1, Integer::sum);
        assigned.computeIfAbsent(selected.id(), ignored -> new ArrayList<>()).add(new Interval(dutyStart, dutyEnd));
        assignments.add(new ScheduleSolverResult.Assignment(
            slot.examId(), slot.roomId(), selected.id(), selected.name(), roomCode(fixture, slot.roomId()),
            "CHIEF".equals(dutyRole) ? "main" : "assist", slot.date(), slot.start(), slot.end()));
      }
    }

    long elapsed = elapsedMillis(started);
    int required = slots.stream().mapToInt(ExamRoomInput::supervisorsPerRoom).sum();
    if (assignments.size() != required) {
      throw new IllegalStateException("Deterministic allocator produced partial coverage");
    }
    int minLoad = loads.isEmpty() ? 0 : loads.values().stream().mapToInt(Integer::intValue).min().orElse(0);
    int maxLoad = loads.isEmpty() ? 0 : loads.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return new ScheduleSolverResult(
        "succeeded", "DETERMINISTIC_FEASIBLE", null, null, List.copyOf(assignments), List.of(), List.of(),
        Map.of(
            "solver", "deterministic-invigilator-interval",
            "hardCoverageComplete", true,
            "teachers", teachers.size(),
            "rooms", slots.size(),
            "dutiesRequired", required,
            "dutiesAssigned", assignments.size(),
            "minimumAssignedLoad", minLoad,
            "maximumAssignedLoad", maxLoad),
        elapsed, Instant.now());
  }

  private boolean qualified(
      TeacherInput teacher,
      String dutyRole,
      JsonNode qualification,
      Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> eligibility,
      LocalDate dutyDate
  ) {
    return hardConstraints.teacherEligible(dutyRole, qualification,
        eligibility == null ? null : eligibility.get(teacher.id()), dutyDate);
  }

  private Map<UUID, Map<LocalDate, Integer>> baselineDailyLoads(
      Map<UUID, List<ExistingDuty>> existingDuties
  ) {
    Map<UUID, Map<LocalDate, Integer>> result = new HashMap<>();
    if (existingDuties == null) return result;
    existingDuties.forEach((teacherId, duties) -> duties.forEach(duty -> result
        .computeIfAbsent(teacherId, ignored -> new HashMap<>())
        .merge(duty.start().toLocalDate(), 1, Integer::sum)));
    return result;
  }

  private Map<UUID, List<Interval>> baselineIntervals(Map<UUID, List<ExistingDuty>> existingDuties) {
    Map<UUID, List<Interval>> result = new HashMap<>();
    if (existingDuties == null) return result;
    existingDuties.forEach((teacherId, duties) -> result.put(teacherId, new ArrayList<>(duties.stream()
        .map(duty -> new Interval(duty.start(), duty.end())).toList())));
    return result;
  }

  private int maxDailySessions(
      Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> eligibility,
      UUID teacherId
  ) {
    ExamOperationsHardConstraints.TeacherEligibility profile = eligibility == null ? null : eligibility.get(teacherId);
    return profile == null ? 0 : profile.maxDailySessions();
  }

  private int dailyLoad(Map<UUID, Map<LocalDate, Integer>> dailyLoads, UUID teacherId, LocalDate date) {
    return dailyLoads.getOrDefault(teacherId, Map.of()).getOrDefault(date, 0);
  }

  private boolean available(
      TeacherInput teacher,
      LocalDateTime start,
      LocalDateTime end,
      List<Interval> existing
  ) {
    boolean insideDutyWindow = teacher.dutyWindows().isEmpty()
        || teacher.dutyWindows().stream().anyMatch(window -> contains(window, start, end));
    if (!insideDutyWindow) return false;
    if (teacher.leaveWindows().stream().anyMatch(window -> overlaps(window.start(), window.end(), start, end))) {
      return false;
    }
    return existing.stream().noneMatch(interval -> overlaps(interval.start(), interval.end(), start, end));
  }

  private boolean contains(TeacherWindow window, LocalDateTime start, LocalDateTime end) {
    return !window.start().isAfter(start) && !window.end().isBefore(end);
  }

  private boolean overlaps(LocalDateTime firstStart, LocalDateTime firstEnd,
      LocalDateTime secondStart, LocalDateTime secondEnd) {
    return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
  }

  private String roomCode(ScheduleFixture fixture, UUID roomId) {
    return fixture.rooms().stream().filter(room -> room.id().equals(roomId))
        .map(ScheduleFixture.RoomInput::code).findFirst().orElse(roomId.toString());
  }

  private long elapsedMillis(long started) {
    return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
  }

  private record Interval(LocalDateTime start, LocalDateTime end) {
  }

  public record DutyKey(UUID sessionId, UUID roomId, String role) {
  }

  public record ExistingDuty(LocalDateTime start, LocalDateTime end) {
  }
}
