package com.jkxt.scheduling.solver;

import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ScheduleFixtureFactory {

  private static final LocalDate DATE = LocalDate.of(2026, 6, 8);
  private static final UUID CLASS_G10_A = uuid("00000000-0000-0000-0000-000000100101");
  private static final UUID CLASS_G10_B = uuid("00000000-0000-0000-0000-000000100102");
  private static final UUID CLASS_G11_A = uuid("00000000-0000-0000-0000-000000100201");

  public ScheduleFixture fixture(String id) {
    return switch (normalize(id)) {
      case "teacher-shortage" -> teacherShortage();
      case "capacity-conflict" -> capacityConflict();
      case "benchmark-200" -> benchmark200();
      case "feasible", "ok", "small" -> feasible();
      default -> throw new IllegalArgumentException("Unknown scheduling fixture: " + id);
    };
  }

  public List<String> fixtureIds() {
    return List.of("feasible", "teacher-shortage", "capacity-conflict", "benchmark-200");
  }

  public ScheduleFixture feasible() {
    List<TeacherInput> teachers = List.of(
        teacher(1, "T-P6-001", "王雅宁", "G11", true, CLASS_G11_A),
        teacher(2, "T-P6-002", "李明远", "G12", true, CLASS_G10_B),
        teacher(3, "T-P6-003", "陈思雨", "G9", false),
        teacher(4, "T-P6-004", "赵启航", "G8", false),
        teacher(5, "T-P6-005", "周晓禾", "G12", false),
        teacher(6, "T-P6-006", "林远舟", "G9", false)
    );
    List<RoomInput> rooms = List.of(
        room(1, "P6-R101", "P6 明德楼 101", 48),
        room(2, "P6-R201", "P6 明德楼 201", 52)
    );
    List<ExamRoomInput> examRooms = List.of(
        examRoom(1, CLASS_G10_A, rooms.get(0).id(), "数学", "G10", DATE, "08:30", "10:00", 36, 2),
        examRoom(2, CLASS_G10_B, rooms.get(1).id(), "英语", "G10", DATE, "08:30", "10:00", 40, 2),
        examRoom(3, CLASS_G11_A, rooms.get(0).id(), "物理", "G11", DATE, "10:30", "12:00", 42, 2)
    );
    return new ScheduleFixture("feasible", "P6 feasible small sample", 2026060501L, teachers, rooms, examRooms);
  }

  public ScheduleFixture teacherShortage() {
    List<TeacherInput> teachers = List.of(
        teacher(21, "T-P6-021", "同年级教师", "G10", false),
        teacher(22, "T-P6-022", "本班班主任", "G9", true, CLASS_G10_A),
        teacher(23, "T-P6-023", "另一名同年级", "G10", false)
    );
    List<RoomInput> rooms = List.of(room(21, "R-SHORT", "教师不足考场", 48));
    List<ExamRoomInput> examRooms = List.of(
        examRoom(21, CLASS_G10_A, rooms.get(0).id(), "数学", "G10", DATE, "08:30", "10:00", 36, 2)
    );
    return new ScheduleFixture("teacher-shortage", "P6 teacher shortage infeasible sample", 2026060502L, teachers, rooms, examRooms);
  }

  public ScheduleFixture capacityConflict() {
    List<TeacherInput> teachers = List.of(
        teacher(31, "T-P6-031", "容量样本教师甲", "G11", false),
        teacher(32, "T-P6-032", "容量样本教师乙", "G12", false)
    );
    List<RoomInput> rooms = List.of(room(31, "R-CAP", "容量不足考场", 30));
    List<ExamRoomInput> examRooms = List.of(
        examRoom(31, CLASS_G10_A, rooms.get(0).id(), "语文", "G10", DATE, "14:00", "15:30", 45, 2)
    );
    return new ScheduleFixture("capacity-conflict", "P6 classroom capacity infeasible sample", 2026060503L, teachers, rooms, examRooms);
  }

  public ScheduleFixture benchmark200() {
    List<TeacherInput> teachers = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String grade = i % 2 == 0 ? "G11" : "G12";
      teachers.add(new TeacherInput(
          uuid("00000000-0000-0000-0000-00000020%04d".formatted(i + 1)),
          "T-BENCH-%03d".formatted(i + 1),
          "基准教师%03d".formatted(i + 1),
          grade,
          false,
          Set.of()
      ).withEligibility(Set.of("INVIGILATOR", "CHIEF"), null, 4, List.of()));
    }
    List<RoomInput> rooms = new ArrayList<>();
    for (int i = 0; i < 1; i++) {
      rooms.add(new RoomInput(
          uuid("00000000-0000-0000-0000-00000030%04d".formatted(i + 1)),
          "B%03d".formatted(i + 1),
          "基准考场%03d".formatted(i + 1),
          60
      ));
    }
    List<ExamRoomInput> examRooms = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      int dayOffset = i / 4;
      int slot = i % 4;
      LocalTime start = switch (slot) {
        case 0 -> LocalTime.of(8, 0);
        case 1 -> LocalTime.of(10, 0);
        case 2 -> LocalTime.of(14, 0);
        default -> LocalTime.of(16, 0);
      };
      UUID classId = i % 2 == 0 ? CLASS_G10_A : CLASS_G10_B;
      examRooms.add(new ExamRoomInput(
          uuid("00000000-0000-0000-0000-00000040%04d".formatted(i + 1)),
          Set.of(classId),
          rooms.get(i % rooms.size()).id(),
          "基准科目%02d".formatted((i % 6) + 1),
          "G10",
          DATE.plusDays(dayOffset),
          start,
          start.plusMinutes(90),
          42,
          2
      ));
    }
    return new ScheduleFixture("benchmark-200", "P6 200 exam benchmark sample", 2026060520L, teachers, rooms, examRooms);
  }

  private static TeacherInput teacher(int n, String no, String name, String grade, boolean homeroom, UUID... classes) {
    // Explicit synthetic fixture qualifications; real teachers are loaded from master data instead.
    return new TeacherInput(
        uuid("00000000-0000-0000-0000-00000010%04d".formatted(n)),
        no,
        name,
        grade,
        homeroom,
        Set.of(classes)
    ).withEligibility(Set.of("INVIGILATOR", "CHIEF"), null, 4, List.of());
  }

  private static RoomInput room(int n, String code, String name, int examCapacity) {
    return new RoomInput(uuid("00000000-0000-0000-0000-00000011%04d".formatted(n)), code, name, examCapacity);
  }

  private static ExamRoomInput examRoom(
      int n,
      UUID classId,
      UUID roomId,
      String subject,
      String grade,
      LocalDate date,
      String start,
      String end,
      int seatDemand,
      int supervisors
  ) {
    return new ExamRoomInput(
        uuid("00000000-0000-0000-0000-00000012%04d".formatted(n)),
        Set.of(classId),
        roomId,
        subject,
        grade,
        date,
        LocalTime.parse(start),
        LocalTime.parse(end),
        seatDemand,
        supervisors
    );
  }

  private static String normalize(String id) {
    return id == null ? "feasible" : id.trim().toLowerCase().replace('_', '-');
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
