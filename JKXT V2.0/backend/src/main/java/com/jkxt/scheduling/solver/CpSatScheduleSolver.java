package com.jkxt.scheduling.solver;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleSolverResult.Assignment;
import com.jkxt.scheduling.solver.ScheduleSolverResult.ConflictItem;
import com.jkxt.scheduling.solver.ScheduleSolverResult.FairnessItem;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
  /** 冲突集最多保留的**类别**数（每类一条代表样本 + 同类计数），防止异常数据产生超长冲突集。 */
  private static final int MAX_CONFLICT_KINDS = 5;
  // Lexicographic objective weights. Total-load fairness dominates; the soft 同年级回避 penalty comes
  // next; 主监(main)-role balance is a pure tie-breaker. The gaps (1e6 ≫ 1e3 ≫ 1) are wide enough that
  // for our scale (≤ a few hundred assignments) a higher tier is never traded away for a lower one:
  // a 1-unit change in maxLoad (1e6) outweighs the entire same-grade sum (≤ #assignments × 1e3), and
  // one same-grade assignment (1e3) outweighs the entire main-load range (≤ #examRooms × 1).
  private static final long FAIRNESS_WEIGHT = 1_000_000L;
  private static final long SAME_GRADE_WEIGHT = 1_000L;
  private static final long MAIN_FAIRNESS_WEIGHT = 1L;
  // 迭代10 P1: 班主任场次软上限。此前是硬约束 sum(班主任场次) ≤ 2，班主任占比高的真实花名册(K-12 里
  // 多数教师是班主任)会因"总供给被 2×班主任数 卡死"而把本可行的排班误判 INFEASIBLE。改为软惩罚：只惩罚
  // 超过 2 场的部分，与同年级/任课回避同档(1e3)，仍远低于公平权(1e6)故绝不会为它牺牲负载均衡，也绝不致无解。
  private static final int HOMEROOM_SOFT_CAP = 2;
  private static final long HOMEROOM_OVERLOAD_WEIGHT = 1_000L;

  public ScheduleSolverResult solve(ScheduleFixture fixture) {
    return solve(fixture, SchedulePolicy.allOn());
  }

  public ScheduleSolverResult solve(ScheduleFixture fixture, SchedulePolicy policy) {
    return solve(fixture, policy, null);
  }

  public ScheduleSolverResult solve(ScheduleFixture fixture, SchedulePolicy policy, SolveControl control) {
    checkpoint(control);
    Instant started = Instant.now();
    long startedNanos = System.nanoTime();
    Map<UUID, RoomInput> rooms = indexRooms(fixture.rooms());

    List<ConflictItem> precheck = precheck(fixture, rooms, policy);
    if (!precheck.isEmpty()) {
      return infeasible("PRECHECK", precheck, startedNanos, fixture);
    }

    Loader.loadNativeLibraries();
    checkpoint(control);

    CpModel model = new CpModel();
    Map<Key, BoolVar> assign = new HashMap<>();
    Map<Key, BoolVar> main = new HashMap<>();
    Map<UUID, TeacherInput> teachers = indexTeachers(fixture.teachers());

    for (int slot = 0; slot < fixture.examRooms().size(); slot++) {
      checkpoint(control);
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
        if (!teacher.qualified("CHIEF", examRoom.date())) model.addEquality(mainVar, 0);
      }
      model.addEquality(LinearExpr.sum(assignVars.toArray(BoolVar[]::new)), examRoom.supervisorsPerRoom());
      model.addEquality(LinearExpr.sum(mainVars.toArray(BoolVar[]::new)), 1);
    }

    List<IntVar> homeroomOverloadVars = new ArrayList<>();
    for (TeacherInput teacher : fixture.teachers()) {
      checkpoint(control);
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
        // 软上限：homeroomOverload = max(0, 场次 − 2)。下界 0(变量下界) + (场次 − CAP)(此约束)，配合目标里
        // 最小化其惩罚，最优时取 max(0, 场次−CAP)。班主任≤2场时惩罚为 0；供给紧张时允许超出而非拒绝出解。
        IntVar homeroomOverload =
            model.newIntVar(0, teacherAssignments.size(), "homeroomOverload_t" + shortId(teacher.id()));
        LinearExprBuilder overloadLowerBound = LinearExpr.newBuilder();
        overloadLowerBound.add(LinearExpr.sum(teacherAssignments.toArray(BoolVar[]::new)));
        overloadLowerBound.add(-HOMEROOM_SOFT_CAP);
        model.addGreaterOrEqual(homeroomOverload, overloadLowerBound);
        homeroomOverloadVars.add(homeroomOverload);
      }
    }

    // ── Fairness objective: spread invigilation duty across the roster ───────────────
    // Without an objective this model is pure feasibility, so CP-SAT piles all duty onto a
    // handful of eligible teachers and leaves the majority idle (measured on real data: only
    // 21–44% of teachers used, load-Gini 0.56–0.80 — a few do 4–5 invigilations while most do
    // zero). That is feasible but not what a school expects. Minimise the busiest teacher's
    // load (minimax): the optimum forces a near-even spread (each teacher ≈ ⌈assignments /
    // teachers⌉). It is cheap because the LP lower bound is tight (pigeonhole), and any result
    // we accept (OPTIMAL or best-FEASIBLE within budget) is a strictly fairer valid schedule.
    Map<UUID, List<BoolVar>> loadByTeacher = new HashMap<>();
    for (Map.Entry<Key, BoolVar> entry : assign.entrySet()) {
      loadByTeacher.computeIfAbsent(entry.getKey().teacherId(), ignored -> new ArrayList<>()).add(entry.getValue());
    }
    if (!loadByTeacher.isEmpty()) {
      IntVar maxLoad = model.newIntVar(0, fixture.examRooms().size(), "maxLoad");
      for (List<BoolVar> vars : loadByTeacher.values()) {
        model.addLessOrEqual(LinearExpr.sum(vars.toArray(BoolVar[]::new)), maxLoad);
      }
      // Weighted objective: fairness (minimax load) stays dominant; 同年级回避 in SOFT mode adds a
      // per-assignment penalty so the solver尽量 hands same-grade rooms to cross-grade teachers when it
      // can, WITHOUT ever risking infeasibility (unlike the HARD filter). The fairness weight (1e6) ≫
      // the largest possible same-grade contribution, so load balance is never traded away for it.
      LinearExprBuilder objective = LinearExpr.newBuilder();
      objective.addTerm(maxLoad, FAIRNESS_WEIGHT);
      if (policy.sameGrade() == SchedulePolicy.SameGrade.SOFT) {
        for (Map.Entry<Key, BoolVar> entry : assign.entrySet()) {
          ExamRoomInput examRoom = fixture.examRooms().get(entry.getKey().slot());
          TeacherInput teacher = teachers.get(entry.getKey().teacherId());
          if (teacher != null && Objects.equals(normalize(teacher.grade()), normalize(examRoom.grade()))) {
            objective.addTerm(entry.getValue(), SAME_GRADE_WEIGHT);
          }
        }
      }
      // 任课老师不监本班(subject_avoid) 软偏好：教师任教的学科 ∋ 本场考试科目 → 加惩罚(scope=sameSubject)，
      // 尽量让任教该科的教师不监考该科考试；作为软项绝不致无解，权重与同年级同档。
      // (取本场考试科目而非所坐班级开设的全部科目，否则多科班级会让惩罚成常数、失去区分度 — 迭代6 Bug2)
      if (policy.avoidSubjectClass()) {
        for (Map.Entry<Key, BoolVar> entry : assign.entrySet()) {
          ExamRoomInput examRoom = fixture.examRooms().get(entry.getKey().slot());
          TeacherInput teacher = teachers.get(entry.getKey().teacherId());
          if (teacher != null && teacher.subjectIds() != null && !teacher.subjectIds().isEmpty()
              && examRoom.examSubjectIds() != null
              && !java.util.Collections.disjoint(teacher.subjectIds(), examRoom.examSubjectIds())) {
            objective.addTerm(entry.getValue(), SAME_GRADE_WEIGHT);
          }
        }
      }
      // 迭代10 P1: 班主任场次软上限惩罚 —— 每超过 2 场的部分按 HOMEROOM_OVERLOAD_WEIGHT 计罚，鼓励
      // 但不强制班主任 ≤2 场；与同年级/任课回避同档，仍被公平权(1e6)压制，不会为它牺牲负载均衡。
      for (IntVar homeroomOverload : homeroomOverloadVars) {
        objective.addTerm(homeroomOverload, HOMEROOM_OVERLOAD_WEIGHT);
      }
      // Secondary fairness — also spread the higher-responsibility 主监 (main) role. Total-load minimax
      // alone can leave some teachers never serving as main while a few always do (observed: 16 of the
      // on-duty teachers never主监). A low-weight minimax on each teacher's main count evens it out as a
      // strict tie-breaker, below both total-load fairness and the same-grade penalty so neither is
      // traded away. (defect: main-role load imbalance)
      Map<UUID, List<BoolVar>> mainLoadByTeacher = new HashMap<>();
      for (Map.Entry<Key, BoolVar> entry : main.entrySet()) {
        mainLoadByTeacher.computeIfAbsent(entry.getKey().teacherId(), ignored -> new ArrayList<>()).add(entry.getValue());
      }
      if (!mainLoadByTeacher.isEmpty()) {
        IntVar maxMainLoad = model.newIntVar(0, fixture.examRooms().size(), "maxMainLoad");
        for (List<BoolVar> vars : mainLoadByTeacher.values()) {
          model.addLessOrEqual(LinearExpr.sum(vars.toArray(BoolVar[]::new)), maxMainLoad);
        }
        objective.addTerm(maxMainLoad, MAIN_FAIRNESS_WEIGHT);
      }
      model.minimize(objective);
    }

    addDeterministicHint(model, fixture, assign, main, policy);

    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(60.0);
    // Robust feasibility search. This model has no objective (pure feasibility), so CP-SAT
    // returns OPTIMAL the instant any worker finds a feasible assignment. Two settings are
    // load-bearing for reliability — both learned the hard way:
    //
    //  1. A PORTFOLIO of workers, not a single strategy. With num_workers=1 the solver ran one
    //     deterministic search that, for ~40% of random seeds, GAVE UP EARLY and returned
    //     UNKNOWN (in <2s, nowhere near the 60s budget) on a model that is provably feasible —
    //     a different seed solved the identical model in <2s. A multi-worker portfolio runs
    //     diverse strategies (feasibility-jump, LNS, LP, core, ...); at least one reliably
    //     finds the solution, so the spurious UNKNOWN disappears.
    //  2. NEVER setStopAfterFirstSolution(true): it put CP-SAT in a feasibility-only mode that
    //     amplified the early give-up above, and bought nothing (no objective to optimise).
    //
    // A defensive retry on UNKNOWN (varying only the seed) makes a residual spurious miss
    // astronomically unlikely. INFEASIBLE/OPTIMAL/FEASIBLE/MODEL_INVALID are all final and
    // break the loop immediately, so a genuinely infeasible model is never masked or retried.
    solver.getParameters().setNumSearchWorkers(8);
    long baseSeed = Math.floorMod(fixture.seed(), Integer.MAX_VALUE);

    CpSolverStatus status = CpSolverStatus.UNKNOWN;
    for (int attempt = 0; attempt < 3; attempt++) {
      checkpoint(control);
      if (control != null) solver.getParameters().setMaxTimeInSeconds(control.remainingNanos() / 1_000_000_000.0);
      solver.getParameters().setRandomSeed((int) Math.floorMod(baseSeed + attempt * 7919L, Integer.MAX_VALUE));
      try (var registration = control == null ? null : control.register(solver::stopSearch)) {
        checkpoint(control);
        if(control!=null)control.nativeCalls.incrementAndGet();
        try {status = runNative(solver,model);}
        finally {if(control!=null)control.nativeReturns.incrementAndGet();}
      }
      checkpoint(control);
      if (status != CpSolverStatus.UNKNOWN) {
        break;
      }
    }
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
            "可用教师不足: 需要 %d 名监考，过滤请假/值班窗口、班主任回避和同年级回避后仅剩 %d 名。"
                .formatted(examRoom.supervisorsPerRoom(), eligible.size()),
            Map.of(
                "examId", examRoom.examId(),
                "roomId", examRoom.roomId(),
                "grade", examRoom.grade(),
                "required", examRoom.supervisorsPerRoom(),
                "eligible", eligible.size()
            )
        ));
      }
      else if (eligible.stream().noneMatch(t -> t.qualified("CHIEF", examRoom.date()))) {
        conflicts.add(conflict(INFEASIBLE_CODE, "H2", "examRoom:" + examRoom.examId(),
            "可用主监考不足：需要同时具备有效 INVIGILATOR 和 CHIEF 资格的教师。",
            Map.of("examId", examRoom.examId(), "roomId", examRoom.roomId())));
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
        mainHints.put(key, i == 0 && teacher.qualified("CHIEF", examRoom.date()));
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
    // PRECHECK 与 CP-SAT 判定的 INFEASIBLE 必须分开措辞：前者是**基础数据预检未通过、求解器根本没运行**
    // （solveMillis=0），却沿用"排考模型无可行解"会让人以为算法已穷尽搜索并证明无解，从而怀疑约束设置，
    // 而真正该做的只是修数据（如考场容量小于考试人数）。
    String message = "PRECHECK".equals(solverStatus)
        ? "基础数据预检未通过，未进入求解：请先按 conflictSet 修正考场容量或监考人力等输入数据。"
        : "排考模型无可行解，请根据 conflictSet 调整教师、考场容量或硬约束。";
    return new ScheduleSolverResult(
        "infeasible",
        solverStatus,
        INFEASIBLE_CODE,
        message,
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

  /**
   * 冲突集精简：此前无条件只保留第 1 条，导致**批量同类问题被掩盖**——例如 96 个考场全部容量不足时，
   * 用户只看到「A101 容量不足」，按提示改好 A101 再排仍然无解，会误判成"修了没用/系统有 bug"。
   *
   * <p>现按 (constraint, code) 归类：每类保留首条代表样本，并在 message 后追加"另有 N 处同类问题"，
   * 同时把该类总数写进 refs.sameKindCount 供前端渲染。类别数上限 {@value #MAX_CONFLICT_KINDS}，
   * 避免异常数据产生超长冲突集；仍保持"精简"语义，不是把 96 条原样抛给用户。
   */
  private List<ConflictItem> minimal(List<ConflictItem> conflicts) {
    if (conflicts.size() <= 1) {
      return conflicts;
    }
    Map<String, List<ConflictItem>> byKind = new LinkedHashMap<>();
    for (ConflictItem c : conflicts) {
      byKind.computeIfAbsent(c.constraint() + "|" + c.code(), k -> new ArrayList<>()).add(c);
    }
    List<ConflictItem> out = new ArrayList<>();
    for (List<ConflictItem> group : byKind.values()) {
      if (out.size() >= MAX_CONFLICT_KINDS) {
        break;
      }
      ConflictItem head = group.get(0);
      if (group.size() == 1) {
        out.add(head);
        continue;
      }
      Map<String, Object> refs = new LinkedHashMap<>(head.refs() == null ? Map.of() : head.refs());
      refs.put("sameKindCount", group.size());
      out.add(new ConflictItem(head.code(), head.constraint(), head.severity(), head.scope(),
          head.message() + "（另有 %d 处同类问题，需一并处理）".formatted(group.size() - 1), refs));
    }
    return List.copyOf(out);
  }

  private long elapsedMillis(long startedNanos) {
    return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
  }

  private ConflictItem conflict(String code, String constraint, String scope, String message, Map<String, Object> refs) {
    return new ConflictItem(code, constraint, "error", scope, message, refs);
  }

  public boolean eligible(TeacherInput teacher, ExamRoomInput examRoom, SchedulePolicy policy) {
    if (!teacher.qualified("INVIGILATOR", examRoom.date())) return false;
    LocalDateTime examStart = examRoom.date().atTime(examRoom.start());
    LocalDateTime examEnd = examRoom.date().atTime(examRoom.end());
    // Leave windows are open-interval overlap checks: merely touching at an endpoint is allowed.
    boolean onLeave = teacher.leaveWindows().stream()
        .anyMatch(window -> window.start().isBefore(examEnd) && window.end().isAfter(examStart));
    if (onLeave) {
      return false;
    }
    // A non-empty duty-window list is an allow-list. The whole exam interval must fit inside one
    // declared window; an empty list means unrestricted availability.
    if (!teacher.dutyWindows().isEmpty()) {
      boolean fullyContained = teacher.dutyWindows().stream()
          .anyMatch(window -> !window.start().isAfter(examStart) && !window.end().isBefore(examEnd));
      if (!fullyContained) {
        return false;
      }
    }
    // Homeroom-class avoidance (teacher_avoid_class) — a 班主任/任课老师 must not invigilate a room
    // that seats one of their own classes. An exam-room can seat several classes (混编考场), so we
    // reject the teacher if their homeroom set intersects the room's class set. Empty/null sets mean
    // "no binding" and never exclude anyone.
    if (policy.avoidHomeroomClass()
        && examRoom.classIds() != null && !examRoom.classIds().isEmpty()
        && !teacher.homeroomClassIds().isEmpty()
        && !java.util.Collections.disjoint(teacher.homeroomClassIds(), examRoom.classIds())) {
      return false;
    }
    // Same-grade avoidance — only the HARD mode excludes the teacher outright. SOFT mode does not
    // filter here; it is handled as a penalty in the objective so it never causes infeasibility.
    if (policy.sameGrade() == SchedulePolicy.SameGrade.HARD
        && Objects.equals(normalize(teacher.grade()), normalize(examRoom.grade()))) {
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
    return List.of(
        new FairnessItem("avgLoad", "平均监考", "%.1f".formatted(avg), max - min <= 2 ? "good" : "warn", "教师平均监考人次"),
        new FairnessItem("maxDiff", "最大差值", String.valueOf(max - min), max - min <= 2 ? "good" : "warn", "最高与最低监考次数差"),
        new FairnessItem("homeroomShare", "班主任占比", percent(homeroomAssignments, assignments.size()), homeroomAssignments <= Math.max(2, assignments.size() / 3) ? "good" : "warn", "班主任参与监考比例"),
        new FairnessItem("corridorShare", "楼道分配", "—", "unavailable", "当前未采集楼道岗位数据，不计入公平性评估"),
        new FairnessItem("notesApplied", "特殊备注", "—", "unavailable", "当前未统计特殊备注执行情况，不计入评估")
    );
  }

  private Map<String, Object> stats(ScheduleFixture fixture, List<Assignment> assignments, String solverStatus, long solveMillis) {
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("fixture", fixture.id());
    stats.put("solver", "ortools-cpsat");
    stats.put("solverStatus", solverStatus);
    // "teachers" = teachers actually assigned (distinct), NOT the candidate pool size — the Quick
    // result panel renders this as "涉及教师" and the pool would overstate it (P2-3).
    stats.put("teachers", (int) assignments.stream().map(Assignment::teacherId).distinct().count());
    stats.put("teacherPool", fixture.teachers().size());
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

  /** Kept separate so native lifecycle tests can observe the real OR-Tools call without replacing it. */
  protected CpSolverStatus runNative(CpSolver solver,CpModel model) { return solver.solve(model); }
  private static void checkpoint(SolveControl control) { if (control != null) control.check(); }

  public static final class SolveStopped extends RuntimeException {
    private final String code;
    public SolveStopped(String code) { super(code); this.code=code; }
    public String code() { return code; }
  }

  /** One admission-relative budget; stop delivery never waits for any database lock. */
  public static final class SolveControl implements AutoCloseable {
    private final long admittedTick, budgetNanos;
    private final java.util.concurrent.ScheduledExecutorService scheduler;
    private final java.util.concurrent.atomic.AtomicReference<String> reason=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicLong deliveries=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger nativeCalls=new java.util.concurrent.atomic.AtomicInteger(),nativeReturns=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ScheduledFuture<?> deadline;
    private Registration active;
    private boolean closed;
    public SolveControl(long admittedTick,long budgetNanos,java.util.concurrent.ScheduledExecutorService scheduler) {
      this.admittedTick=admittedTick;this.budgetNanos=budgetNanos;this.scheduler=scheduler;
      deadline=scheduler.schedule(()->requestStop("DEMO_SOLVE_TIMEOUT"),remainingNanos(),java.util.concurrent.TimeUnit.NANOSECONDS);
    }
    public long remainingNanos() { return Math.max(0,budgetNanos-Math.max(0,System.nanoTime()-admittedTick)); }
    public String stopReason() {
      if(remainingNanos()==0) requestStop("DEMO_SOLVE_TIMEOUT");
      return reason.get();
    }
    public void check() { String code=stopReason();if(code!=null)throw new SolveStopped(code); }
    public synchronized void requestStop(String code) {
      reason.compareAndSet(null,code);
      if(active!=null)active.startPump();
    }
    public long stopDeliveries() { return deliveries.get(); }
    public boolean nativeReturned() { return nativeReturns.get()>0 && nativeReturns.get()==nativeCalls.get(); }
    public synchronized Registration register(Runnable stop) {
      if(closed || active!=null)throw new IllegalStateException("Native registration lifecycle violation");
      var registration=new Registration(stop);active=registration;
      if(stopReason()!=null)registration.startPump();
      return registration;
    }
    public final class Registration implements AutoCloseable {
      private final Runnable stop;private java.util.concurrent.ScheduledFuture<?> pump;private boolean ended;
      private Registration(Runnable stop){this.stop=stop;}
      private void deliver() {
        synchronized(SolveControl.this) {
          if(ended || active!=this)return;
          deliveries.incrementAndGet();stop.run();
        }
      }
      private void startPump() {
        if(pump!=null || ended)return;
        // The first delivery may precede CpSolver's private wrapper creation. Repeat until return.
        deliver();pump=scheduler.scheduleWithFixedDelay(this::deliver,10,10,java.util.concurrent.TimeUnit.MILLISECONDS);
      }
      @Override public void close() {
        synchronized(SolveControl.this) {
          ended=true;if(pump!=null)pump.cancel(false);if(active==this)active=null;
        }
      }
    }
    @Override public synchronized void close() {
      closed=true;deadline.cancel(false);
      if(active!=null)throw new IllegalStateException("Cannot close control before native return");
    }
  }
}
