package com.jkxt.scheduling.solver;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearExpr;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleSolverResult.Assignment;
import com.jkxt.scheduling.solver.ScheduleSolverResult.ConflictItem;
import com.jkxt.scheduling.solver.ScheduleSolverResult.FairnessItem;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CpSatScheduleSolver {

  private static final String INFEASIBLE_CODE = "SCHEDULE_INFEASIBLE";

  public ScheduleSolverResult solve(ScheduleFixture fixture) {
    return solve(fixture, SchedulePolicy.allOn());
  }

  public ScheduleSolverResult solve(ScheduleFixture fixture, SchedulePolicy policy) {
    Instant started = Instant.now();
    long startedNanos = System.nanoTime();
    Map<UUID, RoomInput> rooms = indexRooms(fixture.rooms());

    List<ConflictItem> precheck = precheck(fixture, rooms, policy);
    if (!precheck.isEmpty()) {
      return infeasible("PRECHECK", precheck, startedNanos, fixture);
    }

    Loader.loadNativeLibraries();

    CpModel model = new CpModel();
    Map<Key, BoolVar> assign = new HashMap<>();
    Map<Key, BoolVar> main = new HashMap<>();
    Map<UUID, TeacherInput> teachers = indexTeachers(fixture.teachers());

    for (int slot = 0; slot < fixture.examRooms().size(); slot++) {
      ExamRoomInput examRoom = fixture.examRooms().get(slot);
      List<BoolVar> assignVars = new ArrayList<>();
      List<BoolVar> mainVars = new ArrayList<>();
      for (TeacherInput teacher : fixture.teachers()) {
        if (!eligible(teacher, examRoom, policy)) continue;
        Key key = new Key(slot, teacher.id());
        BoolVar assignVar = model.newBoolVar("assign_s%d_t%s".formatted(slot, shortId(teacher.id())));
        BoolVar mainVar = model.newBoolVar("main_s%d_t%s".formatted(slot, shortId(teacher.id())));
        assign.put(key, assignVar);
        main.put(key, mainVar);
        assignVars.add(assignVar);
        mainVars.add(mainVar);
        model.addLessOrEqual(mainVar, assignVar);
      }
      model.addEquality(LinearExpr.sum(assignVars.toArray(BoolVar[]::new)), examRoom.supervisorsPerRoom());
      model.addEquality(LinearExpr.sum(mainVars.toArray(BoolVar[]::new)), 1);
    }

    for (TeacherInput teacher : fixture.teachers()) {
      List<BoolVar> teacherAssignments = new ArrayList<>();
      Map<String, List<BoolVar>> byExactSlot = new HashMap<>();
      Map<LocalDate, List<Integer>> slotsByDate = new HashMap<>();
      for (int left = 0; left < fixture.examRooms().size(); left++) {
        BoolVar leftVar = assign.get(new Key(left, teacher.id()));
        if (leftVar == null) continue;
        teacherAssignments.add(leftVar);
        ExamRoomInput leftExamRoom = fixture.examRooms().get(left);
        byExactSlot.computeIfAbsent(slotKey(leftExamRoom), ignored -> new ArrayList<>()).add(leftVar);
        slotsByDate.computeIfAbsent(leftExamRoom.date(), ignored -> new ArrayList<>()).add(left);
      }
      for (List<Integer> sameDateSlots : slotsByDate.values()) {
        for (int i = 0; i < sameDateSlots.size(); i++) {
          int left = sameDateSlots.get(i);
          BoolVar leftVar = assign.get(new Key(left, teacher.id()));
          if (leftVar == null) continue;
          for (int j = i + 1; j < sameDateSlots.size(); j++) {
            int right = sameDateSlots.get(j);
            BoolVar rightVar = assign.get(new Key(right, teacher.id()));
            if (rightVar == null) continue;
            ExamRoomInput leftExamRoom = fixture.examRooms().get(left);
            ExamRoomInput rightExamRoom = fixture.examRooms().get(right);
            if (!slotKey(leftExamRoom).equals(slotKey(rightExamRoom)) && overlaps(leftExamRoom, rightExamRoom)) {
              model.addLessOrEqual(LinearExpr.sum(new BoolVar[] { leftVar, rightVar }), 1);
            }
          }
        }
      }
      for (List<BoolVar> sameSlot : byExactSlot.values()) {
        if (sameSlot.size() > 1) {
          model.addLessOrEqual(LinearExpr.sum(sameSlot.toArray(BoolVar[]::new)), 1);
        }
      }
      if (teacher.homeroom() && !teacherAssignments.isEmpty()) {
        model.addLessOrEqual(LinearExpr.sum(teacherAssignments.toArray(BoolVar[]::new)), 2);
      }
    }

    addDeterministicHint(model, fixture, assign, main, policy);

    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(60.0);
    solver.getParameters().setNumSearchWorkers(1);
    solver.getParameters().setRandomSeed((int) Math.floorMod(fixture.seed(), Integer.MAX_VALUE));
    solver.getParameters().setStopAfterFirstSolution(true);

    CpSolverStatus status = solver.solve(model);
    if (status == CpSolverStatus.INFEASIBLE || status == CpSolverStatus.MODEL_INVALID) {
      return infeasible(status.name(), fallbackConflicts(fixture), startedNanos, fixture);
    }
    if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
      return failed(status.name(), startedNanos, fixture);
    }

    List<Assignment> assignments = new ArrayList<>();
    for (int slot = 0; slot < fixture.examRooms().size(); slot++) {
      ExamRoomInput examRoom = fixture.examRooms().get(slot);
      RoomInput room = rooms.get(examRoom.roomId());
      for (TeacherInput teacher : fixture.teachers()) {
        Key key = new Key(slot, teacher.id());
        BoolVar assignVar = assign.get(key);
        if (assignVar == null || solver.value(assignVar) != 1L) continue;
        BoolVar mainVar = main.get(key);
        String role = mainVar != null && solver.value(mainVar) == 1L ? "main" : "assistant";
        assignments.add(new Assignment(
            examRoom.examId(),
            examRoom.roomId(),
            teacher.id(),
            teachers.get(teacher.id()).name(),
            room == null ? "unknown" : room.code(),
            role,
            examRoom.date(),
            examRoom.start(),
            examRoom.end()
        ));
      }
    }
    assignments.sort(Comparator
        .comparing(Assignment::date)
        .thenComparing(Assignment::start)
        .thenComparing(Assignment::roomCode)
        .thenComparing(Assignment::role));

    long solveMillis = elapsedMillis(startedNanos);
    Map<String, Object> stats = stats(fixture, assignments, status.name(), solveMillis);
    return new ScheduleSolverResult(
        "succeeded",
        status.name(),
        null,
        null,
        assignments,
        List.of(),
        fairness(fixture, assignments),
        stats,
        solveMillis,
        Instant.now()
    );
  }

  private List<ConflictItem> precheck(ScheduleFixture fixture, Map<UUID, RoomInput> rooms, SchedulePolicy policy) {
    List<ConflictItem> conflicts = new ArrayList<>();
    for (ExamRoomInput examRoom : fixture.examRooms()) {
      RoomInput room = rooms.get(examRoom.roomId());
      if (room == null) {
        conflicts.add(conflict(
            "ROOM_MISSING",
            "H7",
            "examRoom:" + examRoom.examId(),
            "考场不存在，无法执行容量可行性检查。",
            Map.of("examId", examRoom.examId(), "roomId", examRoom.roomId())
        ));
        continue;
      }
      if (examRoom.seatDemand() > room.examCapacity()) {
        conflicts.add(conflict(
            INFEASIBLE_CODE,
            "H7",
            "examRoom:" + examRoom.examId(),
            "考场容量不足: 需求 %d 人，%s 容量 %d 人。".formatted(examRoom.seatDemand(), room.code(), room.examCapacity()),
            Map.of("examId", examRoom.examId(), "roomId", room.id(), "seatDemand", examRoom.seatDemand(), "examCapacity", room.examCapacity())
        ));
      }
      List<TeacherInput> eligible = fixture.teachers().stream()
          .filter(teacher -> eligible(teacher, examRoom, policy))
          .toList();
      if (eligible.size() < examRoom.supervisorsPerRoom()) {
        conflicts.add(conflict(
            INFEASIBLE_CODE,
            "H2/H3/H4/H5",
            "examRoom:" + examRoom.examId(),
            "可用教师不足: 需要 %d 名监考，过滤班主任回避和同年级回避后仅剩 %d 名。".formatted(examRoom.supervisorsPerRoom(), eligible.size()),
            Map.of(
                "examId", examRoom.examId(),
                "roomId", examRoom.roomId(),
                "grade", examRoom.grade(),
                "required", examRoom.supervisorsPerRoom(),
                "eligible", eligible.size()
            )
        ));
      }
    }
    return minimal(conflicts);
  }

  private void addDeterministicHint(
      CpModel model,
      ScheduleFixture fixture,
      Map<Key, BoolVar> assign,
      Map<Key, BoolVar> main,
      SchedulePolicy policy
  ) {
    Map<Key, Boolean> assignHints = new HashMap<>();
    Map<Key, Boolean> mainHints = new HashMap<>();
    for (Key key : assign.keySet()) {
      assignHints.put(key, false);
      mainHints.put(key, false);
    }

    Map<UUID, List<ExamRoomInput>> teacherSchedule = new HashMap<>();
    Map<UUID, Integer> homeroomLoads = new HashMap<>();

    for (int slot = 0; slot < fixture.examRooms().size(); slot++) {
      ExamRoomInput examRoom = fixture.examRooms().get(slot);
      List<TeacherInput> selected = new ArrayList<>();
      for (TeacherInput teacher : fixture.teachers()) {
        if (selected.size() >= examRoom.supervisorsPerRoom()) break;
        if (!eligible(teacher, examRoom, policy)) continue;
        if (teacher.homeroom() && homeroomLoads.getOrDefault(teacher.id(), 0) >= 2) continue;
        List<ExamRoomInput> existing = teacherSchedule.getOrDefault(teacher.id(), List.of());
        if (existing.stream().anyMatch(previous -> overlaps(previous, examRoom))) continue;
        selected.add(teacher);
      }
      if (selected.size() < examRoom.supervisorsPerRoom()) {
        return;
      }
      for (int i = 0; i < selected.size(); i++) {
        TeacherInput teacher = selected.get(i);
        Key key = new Key(slot, teacher.id());
        assignHints.put(key, true);
        mainHints.put(key, i == 0);
        teacherSchedule.computeIfAbsent(teacher.id(), ignored -> new ArrayList<>()).add(examRoom);
        if (teacher.homeroom()) {
          homeroomLoads.merge(teacher.id(), 1, Integer::sum);
        }
      }
    }

    assignHints.forEach((key, value) -> model.addHint(assign.get(key), value ? 1L : 0L));
    mainHints.forEach((key, value) -> model.addHint(main.get(key), value ? 1L : 0L));
  }

  private List<ConflictItem> fallbackConflicts(ScheduleFixture fixture) {
    List<ConflictItem> conflicts = new ArrayList<>();
    for (ExamRoomInput examRoom : fixture.examRooms()) {
      conflicts.add(conflict(
          INFEASIBLE_CODE,
          "H1/H2/H3/H6",
          "examRoom:" + examRoom.examId(),
          "CP-SAT 判定无可行解，请放宽同一时段教师冲突、监考人数或班主任场次约束。",
          Map.of("examId", examRoom.examId(), "roomId", examRoom.roomId())
      ));
      break;
    }
    return conflicts;
  }

  private ScheduleSolverResult infeasible(
      String solverStatus,
      List<ConflictItem> conflictSet,
      long startedNanos,
      ScheduleFixture fixture
  ) {
    long solveMillis = elapsedMillis(startedNanos);
    return new ScheduleSolverResult(
        "infeasible",
        solverStatus,
        INFEASIBLE_CODE,
        "排考模型无可行解，请根据 conflictSet 调整教师、考场容量或硬约束。",
        List.of(),
        conflictSet,
        fairness(fixture, List.of()),
        stats(fixture, List.of(), solverStatus, solveMillis),
        solveMillis,
        Instant.now()
    );
  }

  private ScheduleSolverResult failed(String solverStatus, long startedNanos, ScheduleFixture fixture) {
    long solveMillis = elapsedMillis(startedNanos);
    return new ScheduleSolverResult(
        "failed",
        solverStatus,
        "SCHEDULE_SOLVER_FAILED",
        "CP-SAT did not produce a feasible or proven infeasible result within the configured budget.",
        List.of(),
        List.of(),
        fairness(fixture, List.of()),
        stats(fixture, List.of(), solverStatus, solveMillis),
        solveMillis,
        Instant.now()
    );
  }

  private List<ConflictItem> minimal(List<ConflictItem> conflicts) {
    if (conflicts.isEmpty()) return conflicts;
    return List.of(conflicts.get(0));
  }

  private long elapsedMillis(long startedNanos) {
    return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
  }

  private ConflictItem conflict(String code, String constraint, String scope, String message, Map<String, Object> refs) {
    return new ConflictItem(code, constraint, "error", scope, message, refs);
  }

  private boolean eligible(TeacherInput teacher, ExamRoomInput examRoom, SchedulePolicy policy) {
    // Homeroom-class avoidance (teacher_avoid_class) — only when the exam-room is class-bound and the
    // policy keeps the rule on. A null classId means "no class binding"; the null guard must also
    // come first because Set.of(...).contains(null) throws.
    UUID classId = examRoom.classId();
    if (policy.avoidHomeroomClass() && classId != null && teacher.homeroomClassIds().contains(classId)) {
      return false;
    }
    // Same-grade avoidance (same_grade_avoid) — only when the policy keeps the rule on.
    if (policy.avoidSameGrade() && Objects.equals(normalize(teacher.grade()), normalize(examRoom.grade()))) {
      return false;
    }
    return true;
  }

  private boolean overlaps(ExamRoomInput left, ExamRoomInput right) {
    return left.date().equals(right.date())
        && left.start().isBefore(right.end())
        && right.start().isBefore(left.end());
  }

  private String slotKey(ExamRoomInput input) {
    return input.date() + "|" + input.start() + "|" + input.end();
  }

  private List<FairnessItem> fairness(ScheduleFixture fixture, List<Assignment> assignments) {
    Map<UUID, Integer> loads = new HashMap<>();
    for (TeacherInput teacher : fixture.teachers()) {
      loads.put(teacher.id(), 0);
    }
    for (Assignment assignment : assignments) {
      loads.merge(assignment.teacherId(), 1, Integer::sum);
    }
    int max = loads.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    int min = loads.values().stream().mapToInt(Integer::intValue).min().orElse(0);
    double avg = loads.values().stream().mapToInt(Integer::intValue).average().orElse(0);
    long homeroomAssignments = assignments.stream()
        .filter(a -> fixture.teachers().stream().anyMatch(t -> t.id().equals(a.teacherId()) && t.homeroom()))
        .count();
    int corridor = 0;
    int notes = 0;
    return List.of(
        new FairnessItem("avgLoad", "平均监考", "%.1f".formatted(avg), max - min <= 2 ? "good" : "warn", "教师平均监考人次"),
        new FairnessItem("maxDiff", "最大差值", String.valueOf(max - min), max - min <= 2 ? "good" : "warn", "最高与最低监考次数差"),
        new FairnessItem("homeroomShare", "班主任占比", percent(homeroomAssignments, assignments.size()), homeroomAssignments <= Math.max(2, assignments.size() / 3) ? "good" : "warn", "班主任参与监考比例"),
        new FairnessItem("corridorShare", "楼道分配", percent(corridor, Math.max(1, assignments.size())), "good", "P6 fixture 暂未启用楼道监考"),
        new FairnessItem("notesApplied", "特殊备注", String.valueOf(notes), "good", "P6 fixture 暂无特殊备注")
    );
  }

  private Map<String, Object> stats(ScheduleFixture fixture, List<Assignment> assignments, String solverStatus, long solveMillis) {
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("fixture", fixture.id());
    stats.put("solver", "ortools-cpsat");
    stats.put("solverStatus", solverStatus);
    stats.put("teachers", fixture.teachers().size());
    stats.put("rooms", fixture.rooms().size());
    stats.put("exams", fixture.examRooms().size());
    stats.put("assignments", assignments.size());
    stats.put("solveMillis", solveMillis);
    stats.put("seed", fixture.seed());
    return stats;
  }

  private Map<UUID, TeacherInput> indexTeachers(List<TeacherInput> teachers) {
    Map<UUID, TeacherInput> result = new HashMap<>();
    for (TeacherInput teacher : teachers) result.put(teacher.id(), teacher);
    return result;
  }

  private Map<UUID, RoomInput> indexRooms(List<RoomInput> rooms) {
    Map<UUID, RoomInput> result = new HashMap<>();
    for (RoomInput room : rooms) result.put(room.id(), room);
    return result;
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private String percent(long value, long total) {
    if (total <= 0) return "0%";
    return Math.round(value * 100.0 / total) + "%";
  }

  private String shortId(UUID id) {
    return id.toString().substring(24);
  }

  private record Key(int slot, UUID teacherId) {
  }
}
