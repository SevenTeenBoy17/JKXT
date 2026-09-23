package com.jkxt.scheduling.solver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ScheduleFixture(
    String id,
    String name,
    long seed,
    List<TeacherInput> teachers,
    List<RoomInput> rooms,
    List<ExamRoomInput> examRooms
) {

  public record TeacherInput(
      UUID id,
      String teacherNo,
      String name,
      String grade,
      boolean homeroom,
      Set<UUID> homeroomClassIds,
      Set<UUID> subjectIds,
      List<TeacherWindow> leaveWindows,
      List<TeacherWindow> dutyWindows
  ) {
    public TeacherInput {
      homeroomClassIds = homeroomClassIds == null ? Set.of() : Set.copyOf(homeroomClassIds);
      subjectIds = subjectIds == null ? Set.of() : Set.copyOf(subjectIds);
      leaveWindows = leaveWindows == null ? List.of() : List.copyOf(leaveWindows);
      dutyWindows = dutyWindows == null ? List.of() : List.copyOf(dutyWindows);
    }

    /** Backward-compatible constructor for fixtures/tests that don't carry 任教学科. */
    public TeacherInput(UUID id, String teacherNo, String name, String grade, boolean homeroom, Set<UUID> homeroomClassIds) {
      this(id, teacherNo, name, grade, homeroom, homeroomClassIds, Set.of(), List.of(), List.of());
    }

    /** Backward-compatible constructor for fixtures/tests that carry subjects but no availability. */
    public TeacherInput(UUID id, String teacherNo, String name, String grade, boolean homeroom,
        Set<UUID> homeroomClassIds, Set<UUID> subjectIds) {
      this(id, teacherNo, name, grade, homeroom, homeroomClassIds, subjectIds, List.of(), List.of());
    }
  }

  public record TeacherWindow(LocalDateTime start, LocalDateTime end) {
    public TeacherWindow {
      Objects.requireNonNull(start, "start");
      Objects.requireNonNull(end, "end");
      if (!start.isBefore(end)) {
        throw new IllegalArgumentException("Teacher window start must be before end");
      }
    }
  }

  public record RoomInput(
      UUID id,
      String code,
      String name,
      int examCapacity
  ) {
  }

  public record ExamRoomInput(
      UUID examId,
      Set<UUID> classIds,
      UUID roomId,
      String subjectName,
      String grade,
      LocalDate date,
      LocalTime start,
      LocalTime end,
      int seatDemand,
      int supervisorsPerRoom,
      /** 本场考试的科目 id(用于 subject_avoid：任教该科的教师被软回避)；通常 1 个，未知则空。 */
      Set<UUID> examSubjectIds
  ) {
    /** Backward-compatible constructor for fixtures/tests that don't carry the exam's 科目. */
    public ExamRoomInput(UUID examId, Set<UUID> classIds, UUID roomId, String subjectName, String grade,
        LocalDate date, LocalTime start, LocalTime end, int seatDemand, int supervisorsPerRoom) {
      this(examId, classIds, roomId, subjectName, grade, date, start, end, seatDemand, supervisorsPerRoom, Set.of());
    }
  }
}
