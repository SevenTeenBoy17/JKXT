package com.jkxt.scheduling.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.jobs.AsyncJobLeaseService;
import com.jkxt.common.jobs.AsyncJobLeaseService.Kind;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import com.jkxt.common.jobs.AsyncJobLeaseService.LeaseLostException;
import com.jkxt.common.jobs.AsyncJobLeaseService.ParentBusyException;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.service.PublishedResourceBaseline;
import com.jkxt.ai.api.AiDtos.PolicyDsl;
import com.jkxt.ai.service.PolicyDslValidator;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.SchoolClass;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.SchoolClassRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.notify.service.NotificationService;
import com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent;
import com.jkxt.scheduling.api.ScheduleDtos.RunSnapshot;
import com.jkxt.scheduling.api.ScheduleDtos.RunStartResponse;
import com.jkxt.scheduling.domain.ScheduleAssignment;
import com.jkxt.scheduling.domain.ScheduleRun;
import com.jkxt.scheduling.repo.ScheduleAssignmentRepository;
import com.jkxt.scheduling.repo.ScheduleRunRepository;
import com.jkxt.scheduling.solver.CpSatScheduleSolver;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherWindow;
import com.jkxt.scheduling.solver.ScheduleFixtureFactory;
import com.jkxt.scheduling.solver.SchedulePolicy;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import com.jkxt.scheduling.solver.ScheduleSolverResult.Assignment;
import com.jkxt.scheduling.solver.ScheduleSolverResult.ConflictItem;
import com.jkxt.scheduling.solver.ScheduleSolverResult.FairnessItem;
import com.jkxt.tasks.domain.Exam;
import com.jkxt.tasks.api.ExamTaskDtos.ReviewedUpload;
import com.jkxt.tasks.domain.ExamTask;
import com.jkxt.tasks.repo.ExamRepository;
import com.jkxt.tasks.repo.ExamTaskRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ScheduleJobService {

  private static final Logger log = LoggerFactory.getLogger(ScheduleJobService.class);
  private static final TypeReference<List<ConflictItem>> CONFLICT_LIST = new TypeReference<>() {
  };
  private static final TypeReference<List<FairnessItem>> FAIRNESS_LIST = new TypeReference<>() {
  };

  private final ScheduleFixtureFactory fixtureFactory;
  private final CpSatScheduleSolver solver;
  private final ScheduleProgressSink progressSink;
  private final ScheduleRunRepository runRepository;
  private final ScheduleAssignmentRepository assignmentRepository;
  private final ExamTaskRepository examTaskRepository;
  private final ExamRepository examRepository;
  private final TeacherRepository teacherRepository;
  private final ClassroomRepository classroomRepository;
  private final SchoolClassRepository schoolClassRepository;
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate tx;
  private final Executor executor;
  private final ObjectMapper objectMapper;
  private final NotificationService notificationService;
  private final PublishedResourceBaseline publishedBaseline;
  private final AsyncJobLeaseService leases;

  public ScheduleJobService(
      ScheduleFixtureFactory fixtureFactory,
      CpSatScheduleSolver solver,
      ScheduleProgressSink progressSink,
      ScheduleRunRepository runRepository,
      ScheduleAssignmentRepository assignmentRepository,
      ExamTaskRepository examTaskRepository,
      ExamRepository examRepository,
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      SchoolClassRepository schoolClassRepository,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      @Qualifier("scheduleTaskExecutor") Executor executor,
      ObjectMapper objectMapper,
      NotificationService notificationService,
      PublishedResourceBaseline publishedBaseline,
      AsyncJobLeaseService leases
  ) {
    this.fixtureFactory = fixtureFactory;
    this.solver = solver;
    this.progressSink = progressSink;
    this.runRepository = runRepository;
    this.assignmentRepository = assignmentRepository;
    this.examTaskRepository = examTaskRepository;
    this.examRepository = examRepository;
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.schoolClassRepository = schoolClassRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.tx = new TransactionTemplate(transactionManager);
    this.executor = executor;
    this.objectMapper = objectMapper;
    this.notificationService = notificationService;
    this.publishedBaseline = publishedBaseline;
    this.leases = leases;
  }

  public List<String> fixtureIds() {
    return fixtureFactory.fixtureIds();
  }

  public ScheduleSolverResult solveFixture(String fixtureId) {
    return solver.solve(fixtureFactory.fixture(fixtureId));
  }

  public RunStartResponse startFixtureRun(String fixtureId) {
    UUID orgId = TenantContext.getRequired();
    PreparedRun prepared = tx.execute(status -> prepareRun(orgId, fixtureFactory.fixture(fixtureId)));
    if (prepared == null) throw new IllegalStateException("Failed to prepare schedule run");
    boolean queued = enqueue(orgId, prepared);
    return new RunStartResponse(
        prepared.runId(),
        prepared.taskId(),
        queued ? "pending" : "failed",
        queued ? "queued" : "QUEUE_FULL",
        queued ? 0 : 100,
        "/api/v1/scheduling/runs/%s/events".formatted(prepared.runId())
    );
  }

  public RunStartResponse startTaskRun(UUID taskId) {
    return startTaskRun(taskId, null);
  }

  public RunStartResponse startTaskRun(UUID taskId, UUID policyId) {
    return startTaskRun(taskId, policyId, null);
  }

  public RunStartResponse startTaskRun(UUID taskId, UUID policyId, List<ReviewedUpload> reviewedUploads) {
    UUID orgId = TenantContext.getRequired();
    PreparedRun prepared = tx.execute(status -> {
      ExamTask task = examTaskRepository.findByOrgIdAndIdForUpdate(orgId, taskId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));
      requireMutableTask(task);
      // upload() takes the same row lock: no upload can slip between this review check and snapshot creation.
      if (policyId != null || reviewedUploads != null) assertReviewedUploads(orgId, taskId, reviewedUploads);
      SchedulePolicy policy = policyId == null ? resolvePolicy(orgId, task.getId()) : resolvePolicy(orgId, task.getId(), policyId);
      // Killer-bug fix (Epic 1): solve the task's REAL imported data instead of a hardcoded fixture.
      // buildFixtureFromTask validates the inputs and throws 400 if exams/rooms/teachers are missing.
      ScheduleFixture fixture = buildFixtureFromTask(orgId, task);
      return prepareRealRun(orgId, task, fixture, policy, policyId);
    });
    if (prepared == null) throw new IllegalStateException("Failed to prepare schedule run");
    boolean queued = enqueue(orgId, prepared);
    return new RunStartResponse(
        prepared.runId(),
        prepared.taskId(),
        queued ? "pending" : "failed",
        queued ? "queued" : "QUEUE_FULL",
        queued ? 0 : 100,
        "/api/v1/schedule-jobs/%s/events".formatted(prepared.runId())
    );
  }

  public RunSnapshot snapshot(UUID runId) {
    UUID orgId = TenantContext.getRequired();
    ScheduleRun run = runRepository.findByOrgIdAndId(orgId, runId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule run not found"));
    List<Assignment> assignments = assignmentRepository.findByOrgIdAndScheduleRunId(orgId, runId).stream()
        .map(this::toAssignment)
        .toList();
    return toSnapshot(run, assignments);
  }

  public SseEmitter stream(UUID runId) {
    snapshot(runId);
    return progressSink.stream(runId);
  }

  private PreparedRun prepareRun(UUID orgId, ScheduleFixture fixture) {
    PersistedFixtureData persisted = upsertFixtureMasterData(orgId, fixture);
    ExamTask task = new ExamTask();
    task.setOrgId(orgId);
    task.setTaskNo("P6-%s-%s".formatted(fixture.id().toUpperCase(), UUID.randomUUID().toString().substring(0, 8)));
    task.setName("P6 " + fixture.name());
    task.setStatus("scheduling");
    task.setStartsOn(fixture.examRooms().stream().map(ExamRoomInput::date).min(LocalDate::compareTo).orElse(LocalDate.now()));
    task.setEndsOn(fixture.examRooms().stream().map(ExamRoomInput::date).max(LocalDate::compareTo).orElse(LocalDate.now()));
    task = examTaskRepository.saveAndFlush(task);

    List<ExamRoomInput> remapped = new ArrayList<>();
    for (ExamRoomInput input : fixture.examRooms()) {
      Exam exam = new Exam();
      exam.setOrgId(orgId);
      exam.setExamTaskId(task.getId());
      exam.setSubjectName(input.subjectName());
      exam.setExamDate(input.date());
      exam.setStartTime(input.start());
      exam.setEndTime(input.end());
      exam.setGrade(input.grade());
      exam = examRepository.saveAndFlush(exam);
      remapped.add(new ExamRoomInput(
          exam.getId(),
          input.classIds(),
          persisted.roomIds().getOrDefault(input.roomId(), input.roomId()),
          input.subjectName(),
          input.grade(),
          input.date(),
          input.start(),
          input.end(),
          input.seatDemand(),
          input.supervisorsPerRoom(),
          input.examSubjectIds()
      ));
    }

    ScheduleRun run = new ScheduleRun();
    run.setOrgId(orgId);
    run.setExamTaskId(task.getId());
    run.setStatus("pending");
    run.setStage("queued");
    run.setProgress(0);
    run.setSeed(fixture.seed());
    run.setStats(new LinkedHashMap<>(Map.of(
        "fixture", fixture.id(),
        "solver", "ortools-cpsat",
        "teachers", fixture.teachers().size(),
        "rooms", fixture.rooms().size(),
        "exams", fixture.examRooms().size()
    )));
    run = runRepository.saveAndFlush(run);

    ScheduleFixture runFixture = new ScheduleFixture(
        fixture.id(),
        fixture.name(),
        fixture.seed(),
        remapTeachers(fixture, persisted.teacherIds()),
        remapRooms(fixture, persisted.roomIds()),
        remapped
    );
    return new PreparedRun(run.getId(), task.getId(), runFixture, SchedulePolicy.allOn(),
        leases.register(Kind.SCHEDULE, orgId, run.getId()));
  }

  /**
   * Builds the CP-SAT fixture from the task's REAL imported data (Epic 1 killer-bug fix):
   * the exams bound to this task, their {@code exam_room → classroom} mapping, and the org's
   * active teacher roster. Replaces the hardcoded {@code fixtureFactory.feasible()} that every
   * quick run used to solve, so the schedule now reflects what the user actually imported.
   *
   * <p>Decision R2: on incomplete input we surface a 400 and let the user fix the data — we
   * never silently relax constraints or fabricate rows.
   */
  private ScheduleFixture buildFixtureFromTask(UUID orgId, ExamTask task) {
    List<Exam> exams = examRepository.findByOrgIdAndExamTaskId(orgId, task.getId());
    if (exams.isEmpty()) {
      throw incompleteInput("该任务尚未导入任何考试安排，请先导入考试安排后再排考。");
    }

    // exam_id -> classroom_id(s) from the keyless exam_room join table (org-scoped).
    // B3: 半连接下推到 SQL，只取本任务这批 exam 的关联行。此前按 org_id 拉全表——exam_room 随
    // 「任务数 × 每任务考试数 × 每场考场数」只增不减，大机构排一次考要把全部历史任务的关联行读进堆内存，
    // 而下面只按本任务的 exam.getId() 取值，其余行进了 map 却永不被读。子查询谓词与上面
    // findByOrgIdAndExamTaskId 同源(org + task)，故被读到的键值对与收窄前逐条相等。
    // ORDER BY ctid 是行为保持的关键而非装饰：收窄前无 ORDER BY，但 org 过滤下的每种可选计划
    // (seq scan / idx_exam_room_org 索引扫 / 位图扫)都按堆物理序返回；收窄后 PG 可能改走
    // PK(exam_id, classroom_id) 的嵌套循环索引扫，同一 exam 内会变成按 classroom_id 排序。同一 exam 的
    // 多行仅 roomId 不同，其顺序决定 examRooms 的槽位顺序 → 决定 CP-SAT 建模顺序与 precheck 首条冲突，
    // 故须显式钉回与收窄前一致的堆物理序。
    Map<UUID, List<UUID>> roomsByExam = new LinkedHashMap<>();
    for (Map<String, Object> joinRow : jdbcTemplate.queryForList(
        "SELECT exam_id, classroom_id FROM exam_room WHERE org_id = ? "
            + "AND exam_id IN (SELECT id FROM exam WHERE org_id = ? AND exam_task_id = ?) "
            + "ORDER BY ctid",
        orgId, orgId, task.getId())) {
      UUID examId = (UUID) joinRow.get("exam_id");
      UUID roomId = (UUID) joinRow.get("classroom_id");
      roomsByExam.computeIfAbsent(examId, key -> new ArrayList<>()).add(roomId);
    }

    // exam_id -> class_id(s) from exam_class (which classes sit this exam). This is what powers the
    // "班主任/任课老师不监本班" rule: an exam-room carrying these classes excludes their homeroom
    // teachers. Populated by commitExams from the 班级 column; absent → no class binding (safe).
    // B3: 与 exam_room 同理收窄到本任务这批 exam(exam_class 同样随 任务×考试×班级 只增不减)；下面同样只按
    // 本任务的 exam.getId() 取值，故被读到的键值对与收窄前逐条相等。这里无须 ORDER BY：值是 Set，且
    // 全链路只经 isEmpty()/Collections.disjoint() 消费(CpSatScheduleSolver.eligible 的班主任回避)，
    // 元素顺序到不了模型，不像 exam_room 的 roomId 会决定槽位顺序。
    Map<UUID, Set<UUID>> classesByExam = new LinkedHashMap<>();
    for (Map<String, Object> joinRow : jdbcTemplate.queryForList(
        "SELECT exam_id, class_id FROM exam_class WHERE org_id = ? "
            + "AND exam_id IN (SELECT id FROM exam WHERE org_id = ? AND exam_task_id = ?)",
        orgId, orgId, task.getId())) {
      classesByExam.computeIfAbsent((UUID) joinRow.get("exam_id"), key -> new LinkedHashSet<>())
          .add((UUID) joinRow.get("class_id"));
    }
    // class_id -> homeroom teacher, derived from class master data (SchoolClass.homeroom_teacher_id).
    // The teacher's own homeroom set is unioned from BOTH the teacher import column (teacher.homeroom_class_id)
    // AND this master-data derivation, so the avoidance works whichever way the school maintains the link.
    Map<UUID, Set<UUID>> homeroomClassesByTeacher = new LinkedHashMap<>();
    for (SchoolClass klass : schoolClassRepository.findByOrgIdOrderByGradeAscCodeAsc(orgId)) {
      if (klass.getHomeroomTeacherId() != null) {
        homeroomClassesByTeacher.computeIfAbsent(klass.getHomeroomTeacherId(), key -> new LinkedHashSet<>())
            .add(klass.getId());
      }
    }

    // 任课映射，用于"任课老师不监本班"软偏好(subject_avoid)：teacher→任教学科(subject_id 集)。
    Map<UUID, Set<UUID>> subjectsByTeacher = new LinkedHashMap<>();
    for (Map<String, Object> joinRow : jdbcTemplate.queryForList(
        "SELECT teacher_id, subject_id FROM teacher_subject WHERE org_id = ?", orgId)) {
      subjectsByTeacher.computeIfAbsent((UUID) joinRow.get("teacher_id"), key -> new LinkedHashSet<>())
          .add((UUID) joinRow.get("subject_id"));
    }
    // 学科名→id：用本场考试的科目(而非所坐班级开设的全部科目)做 subject_avoid 的区分信号。
    Map<String, UUID> subjectIdByName = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "SELECT id, name FROM subject WHERE org_id = ?", orgId)) {
      subjectIdByName.put((String) row.get("name"), (UUID) row.get("id"));
    }

    Map<UUID, Classroom> roomById = new LinkedHashMap<>();
    List<ExamRoomInput> examRooms = new ArrayList<>();
    for (Exam exam : exams) {
      List<UUID> roomIds = roomsByExam.getOrDefault(exam.getId(), List.of());
      if (roomIds.isEmpty()) {
        throw incompleteInput("考试『%s』未绑定考场，请为其指定考场后再排考。".formatted(exam.getSubjectName()));
      }
      int seatDemand = intSetting(exam, "seatDemand", 1);
      int supervisors = Math.max(1, intSetting(exam, "supervisorsPerRoom", 2));
      for (UUID roomId : roomIds) {
        Classroom room = roomById.computeIfAbsent(roomId,
            id -> classroomRepository.findByOrgIdAndId(orgId, id).orElse(null));
        if (room == null) {
          throw incompleteInput(
              "考试『%s』绑定的考场不存在或已删除，请重新维护考场后再排考。".formatted(exam.getSubjectName()));
        }
        Set<UUID> roomClassIds = classesByExam.getOrDefault(exam.getId(), Set.of());
        // subject_avoid 的区分信号 = 本场考试科目(scope=sameSubject)。若取所坐班级开设的全部科目，
        // 多科班级会让"任教任一开设科目"对所有教师恒成立→惩罚成常数、无区分度(迭代6 Bug2)。
        Set<UUID> examSubjectIds = new LinkedHashSet<>();
        UUID examSubjectId = subjectIdByName.get(exam.getSubjectName());
        if (examSubjectId != null) {
          examSubjectIds.add(examSubjectId);
        }
        examRooms.add(new ExamRoomInput(
            exam.getId(),
            roomClassIds,
            room.getId(),
            exam.getSubjectName(),
            exam.getGrade(),
            exam.getExamDate(),
            exam.getStartTime(),
            exam.getEndTime(),
            seatDemand,
            supervisors,
            examSubjectIds
        ));
      }
    }

    List<RoomInput> rooms = roomById.values().stream()
        .map(room -> new RoomInput(room.getId(), room.getCode(), room.getName(), Math.max(1, room.getExamCapacity())))
        .toList();

    ZoneId schoolZone = ZoneId.of("Asia/Shanghai");
    Map<UUID, List<TeacherWindow>> approvedLeave = new LinkedHashMap<>();
    jdbcTemplate.query("select teacher_id, starts_at, ends_at from exam_plan_leave_request where org_id=? and status='APPROVED'",
        (org.springframework.jdbc.core.RowCallbackHandler) rs -> approvedLeave
            .computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>())
            .add(new TeacherWindow(rs.getTimestamp(2).toInstant().atZone(schoolZone).toLocalDateTime(),
                rs.getTimestamp(3).toInstant().atZone(schoolZone).toLocalDateTime())), orgId);
    Map<UUID, List<TeacherWindow>> publishedDuties = new LinkedHashMap<>();
    PublishedResourceBaseline.Snapshot baseline = publishedBaseline.load(orgId, null, task.getId());
    for (var duty : baseline.teacherDuties()) {
      publishedDuties.computeIfAbsent(duty.teacherId(), ignored -> new ArrayList<>()).add(new TeacherWindow(
          duty.startsAt().atZone(schoolZone).toLocalDateTime(), duty.endsAt().atZone(schoolZone).toLocalDateTime()));
    }
    for (ExamRoomInput slot : examRooms) {
      Instant start = slot.date().atTime(slot.start()).atZone(schoolZone).toInstant();
      Instant end = slot.date().atTime(slot.end()).atZone(schoolZone).toInstant();
      if (baseline.rooms().stream().anyMatch(busy -> busy.classroomId().equals(slot.roomId())
          && busy.startsAt().isBefore(end) && busy.endsAt().isAfter(start))) {
        throw new ApiException(HttpStatus.CONFLICT, "SCHEDULE_ROOM_PUBLISHED_CONFLICT",
            "本任务考场时段已被其他正式发布计划占用，请调整考试时间或考场后重新排班");
      }
    }
    List<TeacherInput> teachers = teacherRepository.findByOrgIdOrderByTeacherNoAsc(orgId).stream()
        .filter(teacher -> "active".equalsIgnoreCase(
            teacher.getStatus() == null ? "" : teacher.getStatus().trim()))
        .map(teacher -> {
          // Union the homeroom-class binding from BOTH sources (import column + class master data),
          // so "班主任不监本班" fires whichever way the school maintained the link.
          Set<UUID> homeroomClassIds = new LinkedHashSet<>(
              homeroomClassesByTeacher.getOrDefault(teacher.getId(), Set.of()));
          if (teacher.getHomeroomClassId() != null) {
            homeroomClassIds.add(teacher.getHomeroomClassId());
          }
          boolean isHomeroom = teacher.isHomeroom() || !homeroomClassIds.isEmpty();
          List<TeacherWindow> unavailable = new ArrayList<>(solverWindows(teacher, teacher.getLeaveWindows(), "leaveWindows"));
          unavailable.addAll(approvedLeave.getOrDefault(teacher.getId(), List.of()));
          return new TeacherInput(
              teacher.getId(),
              teacher.getTeacherNo(),
              teacher.getName(),
              teacher.getGrade(),
              isHomeroom,
              homeroomClassIds,
              subjectsByTeacher.getOrDefault(teacher.getId(), Set.of()),
              unavailable,
              solverWindows(teacher, teacher.getDutyWindows(), "dutyWindows"),
              Set.copyOf(teacher.getQualifications()), teacher.getQualificationExpiry(), teacher.getMaxDailySessions(),
              publishedDuties.getOrDefault(teacher.getId(), List.of()));
        })
        .toList();
    if (teachers.isEmpty()) {
      throw incompleteInput("教师名册为空，请先导入教师名册后再排考。");
    }

    return new ScheduleFixture(
        "task-" + task.getId(),
        task.getName() == null ? "排考任务" : task.getName(),
        task.getId().getMostSignificantBits() & Long.MAX_VALUE,
        teachers,
        rooms,
        examRooms
    );
  }

  /**
   * Prepares a run over real task data. Unlike {@link #prepareRun}, it does NOT upsert master
   * data or create new exam rows — the fixture already carries the persisted exam/room/teacher
   * IDs, so the solver's assignments reference real rows directly.
   */
  private PreparedRun prepareRealRun(UUID orgId, ExamTask task, ScheduleFixture fixture, SchedulePolicy policy, UUID policyId) {
    task.setStatus("scheduling");
    task.setStartsOn(fixture.examRooms().stream().map(ExamRoomInput::date).min(LocalDate::compareTo).orElse(LocalDate.now()));
    task.setEndsOn(fixture.examRooms().stream().map(ExamRoomInput::date).max(LocalDate::compareTo).orElse(LocalDate.now()));
    examTaskRepository.saveAndFlush(task);

    ScheduleRun run = new ScheduleRun();
    run.setOrgId(orgId);
    run.setExamTaskId(task.getId());
    run.setStatus("pending");
    run.setStage("queued");
    run.setProgress(0);
    run.setSeed(fixture.seed());
    run.setStats(new LinkedHashMap<>(Map.of(
        "inputSource", "task-import",
        "solver", "ortools-cpsat",
        "teachers", fixture.teachers().size(),
        "rooms", fixture.rooms().size(),
        "exams", fixture.examRooms().size()
    )));
    run.getStats().put("policySource", policyId == null ? "legacy-defaults" : "user-confirmed-ai-policy");
    if (policyId != null) run.getStats().put("policyId", policyId.toString());
    run.getStats().put("policySnapshot", objectMapper.convertValue(policy, Map.class));
    run = runRepository.saveAndFlush(run);
    return new PreparedRun(run.getId(), task.getId(), fixture, policy,
        leases.register(Kind.SCHEDULE, orgId, run.getId()));
  }

  /** No explicit confirmation means no AI influence, regardless of newer parse audit records. */
  private SchedulePolicy resolvePolicy(UUID orgId, UUID taskId) {
    return SchedulePolicy.defaults();
  }

  private void assertReviewedUploads(UUID orgId, UUID taskId, List<ReviewedUpload> reviewedUploads) {
    if (reviewedUploads == null) {
      throw new ApiException(HttpStatus.CONFLICT, "AI_REVIEW_REQUIRED", "请先校验并确认当前任务的数据，再提交 AI 排班。");
    }
    Set<UUID> seen = new LinkedHashSet<>();
    if (reviewedUploads.size() > 1000 || reviewedUploads.stream().anyMatch(upload ->
        upload == null || upload.id() == null || upload.kind() == null || upload.kind().isBlank() || !seen.add(upload.id()))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "AI_REVIEW_INVALID", "确认数据的文件标识无效，请重新校验。");
    }
    List<ReviewedUpload> current = jdbcTemplate.queryForList(
        "SELECT id, kind, sha256 FROM uploaded_file WHERE org_id = ? AND exam_task_id = ?", orgId, taskId).stream()
        .map(row -> new ReviewedUpload(UUID.fromString(row.get("id").toString()), (String) row.get("kind"), (String) row.get("sha256")))
        .toList();
    if (current.size() != reviewedUploads.size() || !new LinkedHashSet<>(current).equals(new LinkedHashSet<>(reviewedUploads))) {
      throw new ApiException(HttpStatus.CONFLICT, "AI_REVIEW_STALE", "上传数据已发生变化，请重新校验并确认后再排班。");
    }
  }

  private SchedulePolicy resolvePolicy(UUID orgId, UUID taskId, UUID policyId) {
    String json = jdbcTemplate.query(
        "SELECT jsonb_build_object('output', masked_output, 'input', masked_input)::text FROM ai_call_log "
            + "WHERE org_id = ? AND exam_task_id = ? AND id = ? AND purpose = 'nl2dsl' "
            + "AND status = 'succeeded' AND request_meta->>'fallback' = 'false'",
        ps -> {
          ps.setObject(1, orgId);
          ps.setObject(2, taskId);
          ps.setObject(3, policyId);
        },
        rs -> rs.next() ? rs.getString(1) : null);
    if (json == null || json.isBlank()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "AI_POLICY_NOT_FOUND", "已确认策略不存在或不属于此任务");
    }
    try {
      JsonNode saved = objectMapper.readTree(json);
      JsonNode dsl = saved.path("output").path("dsl");
      if (!dsl.isObject() || !dsl.path("hard").isArray() || !dsl.path("soft").isArray()) {
        throw new IllegalArgumentException("Confirmed policy is not a valid DSL");
      }
      JsonNode selection = saved.path("input").path("policyIds");
      if (!selection.isArray() || java.util.stream.StreamSupport.stream(selection.spliterator(), false).anyMatch(id -> !id.isTextual())) {
        throw new IllegalArgumentException("Confirmed policy has no valid reviewed selection");
      }
      PolicyDsl typed = objectMapper.treeToValue(dsl, PolicyDsl.class);
      PolicyDslValidator validator = new PolicyDslValidator();
      List<String> selectedIds = new ArrayList<>();
      selection.forEach(id -> selectedIds.add(id.asText()));
      if (!validator.validate(typed).isEmpty() || !validator.validateSelection(typed, selectedIds).isEmpty()) {
        throw new IllegalArgumentException("Confirmed policy contradicts its reviewed selection");
      }
      return policyFromDsl(dsl);
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.CONFLICT, "AI_POLICY_INVALID", "已确认策略无效，请重新解析并确认");
    }
  }

  /** Must run under the task row lock before validation, import mutation or new scheduling work. */
  public static void requireMutableTask(ExamTask task) {
    if (task.getPublishedAt() != null || Set.of("published", "scheduling", "running", "executing", "archived", "closed")
        .contains(task.getStatus())) {
      throw new ApiException(HttpStatus.CONFLICT, "EXAM_TASK_NOT_EDITABLE", "已发布或正在排班的任务不能重新校验或排班");
    }
  }

  /** Resolve a parsed policy DSL node into solver flags. Package-private for focused unit testing. */
  static SchedulePolicy policyFromDsl(JsonNode dsl) {
    return new SchedulePolicy(
        rulePresent(dsl, "teacher_avoid_class"),
        sameGradeFromDsl(dsl),
        rulePresent(dsl, "subject_avoid"));
  }

  /** True only for an explicitly enabled optional rule; absent rules never inherit legacy defaults. */
  private static boolean rulePresent(JsonNode dsl, String type) {
    for (String group : new String[] { "hard", "soft" }) {
      JsonNode rules = dsl.path(group);
      if (rules.isArray()) {
        for (JsonNode rule : rules) {
          if (type.equals(rule.path("type").asText()) && rule.path("enabled").asBoolean(false)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** In confirmed AI DSLs absent/disabled means NONE; both soft and optimize mean SOFT. */
  private static SchedulePolicy.SameGrade sameGradeFromDsl(JsonNode dsl) {
    for (String group : new String[] { "hard", "soft" }) {
      JsonNode rules = dsl.path(group);
      if (rules.isArray()) {
        for (JsonNode rule : rules) {
          if ("same_grade_avoid".equals(rule.path("type").asText())) {
            if (!rule.path("enabled").asBoolean(false)) {
              return SchedulePolicy.SameGrade.NONE;
            }
            return "hard".equalsIgnoreCase(rule.path("severity").asText())
                ? SchedulePolicy.SameGrade.HARD
                : SchedulePolicy.SameGrade.SOFT;
          }
        }
      }
    }
    return SchedulePolicy.SameGrade.NONE;
  }

  private static ApiException incompleteInput(String message) {
    // Stable machine code (decision R2) so the frontend can route the user back to data import.
    return new ApiException(HttpStatus.BAD_REQUEST, "SCHEDULE_INPUT_INCOMPLETE", message);
  }

  private static int intSetting(Exam exam, String key, int fallback) {
    Object value = exam.getSettings() == null ? null : exam.getSettings().get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static List<TeacherWindow> solverWindows(
      Teacher teacher,
      List<Map<String, Object>> values,
      String field
  ) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    List<TeacherWindow> windows = new ArrayList<>(values.size());
    for (int index = 0; index < values.size(); index++) {
      Map<String, Object> value = values.get(index);
      try {
        LocalDateTime start = LocalDateTime.parse(String.valueOf(value.get("start")));
        LocalDateTime end = LocalDateTime.parse(String.valueOf(value.get("end")));
        windows.add(new TeacherWindow(start, end));
      } catch (RuntimeException exception) {
        throw incompleteInput("教师『%s』的 %s[%d] 时间窗口格式无效，请修正后再排考。"
            .formatted(teacher.getName(), field, index));
      }
    }
    return List.copyOf(windows);
  }

  private PersistedFixtureData upsertFixtureMasterData(UUID orgId, ScheduleFixture fixture) {
    Map<UUID, UUID> teacherIds = new LinkedHashMap<>();
    Map<UUID, UUID> roomIds = new LinkedHashMap<>();
    for (TeacherInput input : fixture.teachers()) {
      Teacher teacher = teacherRepository.findByOrgIdAndTeacherNo(orgId, input.teacherNo()).orElseGet(Teacher::new);
      teacher.setOrgId(orgId);
      teacher.setTeacherNo(input.teacherNo());
      teacher.setName(input.name());
      teacher.setGender("unknown");
      teacher.setGrade(input.grade());
      teacher.setStatus("active");
      teacher.setHomeroom(input.homeroom());
      // This endpoint creates explicitly synthetic fixture master data, never the real task roster.
      teacher.setQualifications(List.copyOf(input.qualifications()));
      teacher.setQualificationExpiry(input.qualificationExpiry());
      teacher.setMaxDailySessions(input.maxDailySessions());
      teacher = teacherRepository.saveAndFlush(teacher);
      teacherIds.put(input.id(), teacher.getId());
    }
    for (RoomInput input : fixture.rooms()) {
      Classroom classroom = classroomRepository.findByOrgIdAndCode(orgId, input.code()).orElseGet(Classroom::new);
      classroom.setOrgId(orgId);
      classroom.setCode(input.code());
      classroom.setName(input.name());
      classroom.setBuilding("P6 Fixture");
      classroom.setFloor("1");
      classroom.setCapacity(Math.max(input.examCapacity(), 1));
      classroom.setExamCapacity(input.examCapacity());
      classroom.setRoomType("standard");
      classroom.setFeatures(List.of("solver-fixture"));
      classroom.setStatus("active");
      classroom = classroomRepository.saveAndFlush(classroom);
      roomIds.put(input.id(), classroom.getId());
    }
    return new PersistedFixtureData(teacherIds, roomIds);
  }

  private List<TeacherInput> remapTeachers(ScheduleFixture fixture, Map<UUID, UUID> teacherIds) {
    return fixture.teachers().stream()
        .map(input -> new TeacherInput(
            teacherIds.getOrDefault(input.id(), input.id()),
            input.teacherNo(),
            input.name(),
            input.grade(),
            input.homeroom(),
            input.homeroomClassIds(),
            input.subjectIds(),
            input.leaveWindows(),
            input.dutyWindows(), input.qualifications(), input.qualificationExpiry(), input.maxDailySessions(),
            input.publishedDuties()
        ))
        .toList();
  }

  private List<RoomInput> remapRooms(ScheduleFixture fixture, Map<UUID, UUID> roomIds) {
    return fixture.rooms().stream()
        .map(input -> new RoomInput(
            roomIds.getOrDefault(input.id(), input.id()),
            input.code(),
            input.name(),
            input.examCapacity()
        ))
        .toList();
  }

  private boolean enqueue(UUID orgId, PreparedRun prepared) {
    try {
      publish(prepared.lease(), "pending", "queued", 0, "排考任务已创建，等待求解器执行。");
    } catch (ParentBusyException busy) {
      // The committed queued job still exists. A contended optional progress write must not
      // silently prevent its single executor submission; the callback will revalidate its fence.
      log.info("Initial schedule progress deferred by parent contention for {}", prepared.runId());
    }
    try {
      executor.execute(() -> executePreparedRun(orgId, prepared.runId(), prepared.fixture(), prepared.policy(), prepared.lease()));
      return true;
    } catch (RejectedExecutionException rejected) {
      failOwnedRun(prepared.lease(), "SCHEDULE_QUEUE_FULL", "QUEUE_FULL", "排班队列已满，本次未执行。请稍后重新发起排班。");
      return false;
    }
  }

  private void executePreparedRun(UUID orgId, UUID runId, ScheduleFixture fixture, SchedulePolicy policy, Lease lease) {
    try {
      // Native CP-SAT is bounded at 60 seconds. Allow a separate bounded margin for model/output work.
      leases.begin(lease, 120_000L);
      leases.fencedRun(lease, () -> markRunning(orgId, runId));
      publish(lease, "running", "读取排班数据", 15, "已读取本次教师、考场与考试数据。");
      publish(lease, "running", "构建约束模型", 35, "正在构建 CP-SAT 决策变量与硬约束。");
      ScheduleSolverResult result = solver.solve(fixture, policy);
      publish(lease, "running", "求解完成，写入结果", 80, "CP-SAT 返回 %s。".formatted(result.solverStatus()));
      // Assignments, aggregate, terminal status, notification and token invalidation are one transaction.
      // No late callback can change a recovered run or expose a partial successful result.
      leases.finish(lease, () -> {
        persistResult(orgId, runId, result);
        appendProgress(lease, result.status(), finalStage(result), 100, finalMessage(result));
        if ("infeasible".equals(result.status())) {
          runRepository.findByOrgIdAndId(orgId, runId).ifPresent(run ->
            examTaskRepository.findByOrgIdAndId(orgId, run.getExamTaskId()).ifPresent(task ->
                notificationService.publishSystemNotice(orgId, task.getId(), "schedule_infeasible",
                    (task.getName() == null ? "排监考任务" : task.getName()) + " · 排考无可行解",
                    finalMessage(result),
                    "/tasks/" + task.getId(),
                    Map.of("runId", runId.toString(), "solverStatus", result.solverStatus()))));
        }
      });
    } catch (ParentBusyException busy) {
      // Bounded contention is not proof of lost ownership. No second failure write/retry here:
      // retain the durable lease so deletion rollback remains recoverable by the watchdog.
      log.info("Stopped schedule worker after bounded parent contention for {}", runId);
    } catch (LeaseLostException expired) {
      log.info("Discarded schedule worker after lease loss for {}", runId);
    } catch (Exception ex) {
      log.error("Schedule run {} failed during solve", runId, ex);
      failOwnedRun(lease, "SCHEDULE_JOB_FAILED", "failed", truncateError(describeError(ex)));
    } finally {
      leases.stopTracking(lease);
    }
  }

  private void failOwnedRun(Lease lease, String errorCode, String stage, String message) {
    try {
      leases.finish(lease, () -> runRepository.findByOrgIdAndId(lease.orgId(), lease.jobId()).ifPresent(run -> {
        run.setStatus("failed");
        run.setStage(stage);
        run.setProgress(100);
        run.setErrorCode(errorCode);
        run.setErrorMessage(message);
        run.setFinishedAt(Instant.now());
        runRepository.save(run);
        examTaskRepository.findByOrgIdAndId(lease.orgId(), run.getExamTaskId()).ifPresent(task -> {
          task.setStatus("failed");
          examTaskRepository.save(task);
          notificationService.publishSystemNotice(lease.orgId(), task.getId(), "schedule_failed",
              (task.getName() == null ? "排监考任务" : task.getName()) + " · 排考执行失败",
              message,
              "/tasks/" + task.getId(),
              Map.of("runId", lease.jobId().toString(), "errorCode", errorCode));
        });
        appendProgress(lease, "failed", stage, 100, message);
      }));
    } catch (RuntimeException unavailable) {
      // No unfenced fallback writes. A lost/expired/database-unavailable job is left for recovery.
      log.warn("Unable to record schedule failure under an owned lease for {}", lease.jobId());
    }
  }

  @Scheduled(scheduler = "jobLeaseScheduler", fixedDelayString = "${jkxt.jobs.watchdog-interval-ms:10000}")
  public void recoverExpiredScheduleRuns() {
    leases.expireIfReady(Kind.SCHEDULE, lease -> {
      List<UUID> activeTasks = jdbcTemplate.queryForList("""
          select exam_task_id from schedule_run where org_id=? and id=? and status in ('pending','running')
          """, UUID.class, lease.orgId(), lease.jobId());
      if (activeTasks.isEmpty()) return true;
      // Public task deletion locks task -> run -> cascading lease. We already hold the lease,
      // so never wait for either parent lock: defer this row intact and recover unrelated rows.
      if (jdbcTemplate.queryForList("select id from exam_task where org_id=? and id=? for update skip locked",
          UUID.class, lease.orgId(), activeTasks.getFirst()).isEmpty()) return false;
      if (jdbcTemplate.queryForList("select id from schedule_run where org_id=? and id=? for update skip locked",
          UUID.class, lease.orgId(), lease.jobId()).isEmpty()) return false;
      List<UUID> taskIds = jdbcTemplate.queryForList("""
          update schedule_run set status='failed',stage='WORKER_LOST',progress=100,
            error_code='SCHEDULE_WORKER_LOST',error_message='执行租约失效，本次结果未提交；请重新发起排班。',
            finished_at=clock_timestamp(),updated_at=clock_timestamp()
          where org_id=? and id=? and status in ('pending','running') returning exam_task_id
          """, UUID.class, lease.orgId(), lease.jobId());
      if (taskIds.size() == 1) {
        jdbcTemplate.update("""
            update exam_task set status='failed',updated_at=clock_timestamp()
            where org_id=? and id=? and status='scheduling' and published_at is null
              and not exists (select 1 from schedule_run where org_id=? and exam_task_id=?
                and id<>? and status in ('pending','running'))
            """, lease.orgId(), taskIds.getFirst(), lease.orgId(), taskIds.getFirst(), lease.jobId());
        appendProgress(lease, "failed", "WORKER_LOST", 100, "执行租约失效，本次结果未提交；请重新发起排班。");
      }
      return true;
    });
  }

  private void markRunning(UUID orgId, UUID runId) {
    // 迭代12 P2: run 可能在求解期间被删(考试任务删除级联)——ifPresent no-op 而非 orElseThrow,避免异步崩溃。
    tx.executeWithoutResult(status -> runRepository.findByOrgIdAndId(orgId, runId).ifPresent(run -> {
      run.setStatus("running");
      run.setStage("CP-SAT 初始化");
      run.setProgress(10);
      run.setStartedAt(Instant.now());
      runRepository.save(run);
    }));
  }

  private void persistResult(UUID orgId, UUID runId, ScheduleSolverResult result) {
    // 迭代12 P2: run 可能在求解期间被删——整体 ifPresent no-op,run 已删则不落库(良性),不再 orElseThrow 崩溃。
    tx.executeWithoutResult(status -> runRepository.findByOrgIdAndId(orgId, runId).ifPresent(run -> {
      run.setStatus(result.status());
      run.setStage(result.status());
      run.setProgress(100);
      run.setFinishedAt(Instant.now());
      run.setErrorCode(result.errorCode());
      run.setErrorMessage(truncateError(result.errorMessage()));
      Map<String, Object> stats = new LinkedHashMap<>(run.getStats());
      stats.putAll(result.stats());
      stats.put("conflictSet", objectMapper.convertValue(result.conflictSet(), List.class));
      stats.put("fairness", objectMapper.convertValue(result.fairness(), List.class));
      run.setStats(stats);
      runRepository.save(run);
      examTaskRepository.findByOrgIdAndId(orgId, run.getExamTaskId()).ifPresent(task -> {
        task.setStatus(result.feasible() ? "scheduled" : "failed");
        examTaskRepository.save(task);
      });

      if (result.feasible()) {
        for (Assignment assignment : result.assignments()) {
          ScheduleAssignment entity = new ScheduleAssignment();
          entity.setOrgId(orgId);
          entity.setScheduleRunId(runId);
          entity.setExamId(assignment.examId());
          entity.setClassroomId(assignment.roomId());
          entity.setTeacherId(assignment.teacherId());
          entity.setRole(assignment.role());
          entity.setExamDate(assignment.date());
          entity.setStartTime(assignment.start());
          entity.setEndTime(assignment.end());
          entity.setSource("solver");
          assignmentRepository.save(entity);
        }
      }
    }));
  }

  private void publish(Lease lease, String status, String stage, int progress, String message) {
    leases.fencedRun(lease, () -> appendProgress(lease, status, stage, progress, message));
  }

  /** Caller owns the lease row (normal fence or expiry recovery); only committed progress is broadcast. */
  private void appendProgress(Lease lease, String status, String stage, int progress, String message) {
    ProgressEvent event = new ProgressEvent(lease.jobId(), status, stage, progress, message, Instant.now());
    runRepository.findByOrgIdAndId(lease.orgId(), lease.jobId()).ifPresent(run -> {
      run.setStatus(status);
      run.setStage(stage);
      run.setProgress(progress);
      List<Map<String, Object>> logs = new ArrayList<>(run.getLogs());
      logs.add(objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {
      }));
      run.setLogs(logs);
      runRepository.save(run);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { progressSink.publish(event); }
      });
    });
  }

  private String finalStage(ScheduleSolverResult result) {
    if (result.feasible()) return "生成结果预览";
    // PRECHECK = 基础数据预检未通过、求解器未运行；沿用"输出无解冲突集"会让用户以为算法已证明无解。
    if ("PRECHECK".equals(result.solverStatus())) return "基础数据预检未通过";
    if ("infeasible".equals(result.status())) return "输出无解冲突集";
    return "solver failed";
  }

  private String finalMessage(ScheduleSolverResult result) {
    if (result.feasible()) return "已写入排考 assignments。";
    if ("infeasible".equals(result.status())) return "已写入 conflictSet，等待用户调整基础数据或约束。";
    return truncateError(result.errorMessage());
  }

  private String truncateError(String message) {
    if (message == null) return null;
    return message.length() <= 480 ? message : message.substring(0, 480);
  }

  private static String describeError(Throwable ex) {
    String raw = ex.getMessage();
    String type = ex.getClass().getSimpleName();
    return raw == null || raw.isBlank() ? type : type + ": " + raw;
  }

  private RunSnapshot toSnapshot(ScheduleRun run, List<Assignment> assignments) {
    List<ConflictItem> conflictSet = objectMapper.convertValue(
        run.getStats().getOrDefault("conflictSet", List.of()),
        CONFLICT_LIST
    );
    List<FairnessItem> fairness = objectMapper.convertValue(
        run.getStats().getOrDefault("fairness", List.of()),
        FAIRNESS_LIST
    );
    return new RunSnapshot(
        run.getId(),
        run.getExamTaskId(),
        run.getStatus(),
        run.getStage(),
        run.getProgress(),
        run.getSolver(),
        run.getSeed(),
        run.getErrorCode(),
        run.getErrorMessage(),
        run.getStats(),
        run.getLogs(),
        assignments,
        conflictSet,
        fairness
    );
  }

  private Assignment toAssignment(ScheduleAssignment assignment) {
    return new Assignment(
        assignment.getExamId(),
        assignment.getClassroomId(),
        assignment.getTeacherId(),
        null,
        null,
        assignment.getRole(),
        assignment.getExamDate(),
        assignment.getStartTime(),
        assignment.getEndTime()
    );
  }

  private record PreparedRun(UUID runId, UUID taskId, ScheduleFixture fixture, SchedulePolicy policy, Lease lease) {
  }

  private record PersistedFixtureData(Map<UUID, UUID> teacherIds, Map<UUID, UUID> roomIds) {
  }
}
