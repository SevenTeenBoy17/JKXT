package com.jkxt.scheduling.solver;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
      Set<UUID> homeroomClassIds
  ) {
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
      UUID classId,
      UUID roomId,
      String subjectName,
      String grade,
      LocalDate date,
      LocalTime start,
      LocalTime end,
      int seatDemand,
      int supervisorsPerRoom
  ) {
  }
}
