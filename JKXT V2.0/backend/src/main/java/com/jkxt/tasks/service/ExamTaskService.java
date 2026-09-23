package com.jkxt.tasks.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.importer.api.ImportDtos.Counts;
import com.jkxt.importer.api.ImportDtos.Overview;
import com.jkxt.importer.api.ImportDtos.ValidationItem;
import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.importer.domain.ImportBatch;
import com.jkxt.importer.domain.UploadedFile;
import com.jkxt.importer.repo.ImportBatchRepository;
import com.jkxt.importer.repo.UploadedFileRepository;
import com.jkxt.importer.service.ImportCommitService;
import com.jkxt.importer.service.ImportValidationService;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.scheduling.api.ScheduleDtos.RunSnapshot;
import com.jkxt.scheduling.api.ScheduleDtos.RunStartResponse;
import com.jkxt.scheduling.domain.ScheduleAssignment;
import com.jkxt.scheduling.domain.ScheduleRun;
import com.jkxt.scheduling.repo.ScheduleAssignmentRepository;
import com.jkxt.scheduling.repo.ScheduleRunRepository;
import com.jkxt.scheduling.service.ScheduleJobService;
import com.jkxt.tasks.api.ExamTaskDtos.AiAdvice;
import com.jkxt.tasks.api.ExamTaskDtos.AiTarget;
import com.jkxt.tasks.api.ExamTaskDtos.AssignmentRow;
import com.jkxt.tasks.api.ExamTaskDtos.AssignmentsResponse;
import com.jkxt.tasks.api.ExamTaskDtos.CreateExamTaskRequest;
import com.jkxt.tasks.api.ExamTaskDtos.ExamTaskResponse;
import com.jkxt.tasks.api.ExamTaskDtos.JobResponse;
import com.jkxt.tasks.api.ExamTaskDtos.ReviewedUpload;
import com.jkxt.tasks.api.ExamTaskDtos.UploadResponse;
import com.jkxt.tasks.api.ExamTaskDtos.ValidationResponse;
import com.jkxt.tasks.domain.Exam;
import com.jkxt.tasks.domain.ExamTask;
import com.jkxt.tasks.repo.ExamRepository;
import com.jkxt.tasks.repo.ExamTaskRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExamTaskService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExamTaskService.class);
  private static final List<String> REQUIRED_UPLOAD_KINDS = List.of("teacher", "classroom", "exam");
  private static final UUID SAMPLE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  /** 容量不足项最多列出的条数，避免大批量超容时刷屏（超出部分以总数提示）。 */
  private static final int MAX_CAPACITY_ITEMS = 5;

  private final ExamTaskRepository examTaskRepository;
  private final UploadedFileRepository uploadedFileRepository;
  private final ImportBatchRepository importBatchRepository;
  private final ScheduleRunRepository scheduleRunRepository;
  private final ScheduleAssignmentRepository assignmentRepository;
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  private final ExamRepository examRepository;
  private final TeacherRepository teacherRepository;
  private final ClassroomRepository classroomRepository;
  private final ImportValidationService validationService;
  private final ImportCommitService importCommitService;
  private final ScheduleJobService scheduleJobService;

  public ExamTaskService(
      ExamTaskRepository examTaskRepository,
      UploadedFileRepository uploadedFileRepository,
      ImportBatchRepository importBatchRepository,
      ScheduleRunRepository scheduleRunRepository,
      ScheduleAssignmentRepository assignmentRepository,
      ExamRepository examRepository,
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      ImportValidationService validationService,
      ImportCommitService importCommitService,
      ScheduleJobService scheduleJobService,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.examTaskRepository = examTaskRepository;
    this.uploadedFileRepository = uploadedFileRepository;
    this.importBatchRepository = importBatchRepository;
    this.scheduleRunRepository = scheduleRunRepository;
    this.assignmentRepository = assignmentRepository;
    this.examRepository = examRepository;
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.validationService = validationService;
    this.importCommitService = importCommitService;
    this.scheduleJobService = scheduleJobService;
  }

  @Transactional
  public ExamTaskResponse create(CreateExamTaskRequest request) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = new ExamTask();
    task.setOrgId(orgId);
    task.setTaskNo("QK-%s-%s".formatted(LocalDate.now().toString().replace("-", ""), shortId()));
    task.setName(nonBlank(request == null ? null : request.name(), "Quick one-click scheduling"));
    task.setSource(normalizeSource(request == null ? null : request.source()));
    task.setStatus("draft");
    task = examTaskRepository.saveAndFlush(task);
    return toTaskResponse(orgId, task);
  }

  @Transactional(readOnly = true)
  public List<ExamTaskResponse> list() {
    UUID orgId = TenantContext.getRequired();
    return examTaskRepository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
        .map(task -> toTaskResponse(orgId, task))
        .toList();
  }

  @Transactional(readOnly = true)
  public ExamTaskResponse detail(UUID taskId) {
    UUID orgId = TenantContext.getRequired();
    return toTaskResponse(orgId, getTask(orgId, taskId));
  }

  /**
   * 删除考试任务及其全部派生数据。exam/exam_room/exam_class/schedule_run/schedule_assignment/
   * schedule_revision/import_batch/uploaded_file/notification 等子表均配 ON DELETE CASCADE，
   * 删除任务即级联清理，无孤儿残留（此前缺删除端点，任务与历史数据只增不减）。404 当任务不存在。
   */
  @Transactional
  public void delete(UUID taskId) {
    UUID orgId = TenantContext.getRequired();
    examTaskRepository.delete(getTask(orgId, taskId));
  }

  @Transactional
  public UploadResponse upload(UUID taskId, String rawKind, MultipartFile file, boolean sample) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = examTaskRepository.findByOrgIdAndIdForUpdate(orgId, taskId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));
    ScheduleJobService.requireMutableTask(task);
    if (sample && !SAMPLE_ORG_ID.equals(orgId)) {
      throw new ApiException(HttpStatus.FORBIDDEN, "SAMPLE_UPLOAD_FORBIDDEN",
          "示例导入仅限专用演示机构；请上传本校真实文件");
    }
    if (!sample && (file == null || file.isEmpty())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "UPLOAD_FILE_REQUIRED", "请选择非空的上传文件");
    }
    String kind = normalizeKind(rawKind);
    String templateKind = templateKind(kind);

    try {
      byte[] bytes = sample
          ? validationService.template(templateKind)
          : file.getBytes();
      String originalName = sample
          ? validationService.templateFilename(templateKind).replace("template", "sample")
          : nonBlank(file.getOriginalFilename(), kind + ".xlsx");
      ValidationReport report = validationService.validate(templateKind, new BytesMultipartFile(originalName, bytes));

      UploadedFile uploaded = new UploadedFile();
      uploaded.setOrgId(orgId);
      uploaded.setExamTaskId(taskId);
      uploaded.setKind(kind);
      uploaded.setOriginalName(originalName);
      uploaded.setStorageKey("exam-tasks/%s/%s/%s".formatted(taskId, kind, originalName));
      uploaded.setSizeBytes(bytes.length);
      uploaded.setSha256(sha256(bytes));
      uploaded = uploadedFileRepository.saveAndFlush(uploaded);

      ImportBatch batch = new ImportBatch();
      batch.setOrgId(orgId);
      batch.setExamTaskId(taskId);
      batch.setUploadedFileId(uploaded.getId());
      batch.setKind(kind);
      batch.setStatus(report.counts().error() > 0 ? "failed" : "validated");
      int rowCount = rowCount(kind, report);
      batch.setRowCount(rowCount);
      batch.setValidCount(Math.max(0, rowCount - report.counts().error()));
      batch.setErrorCount(report.counts().error());
      importBatchRepository.save(batch);

      // Real data flow (Epic 1, R6): a clean upload is PERSISTED into the DB, not just validated.
      // Roster kinds (teacher/classroom) upsert the org roster; exam rows bind to this task so the
      // scheduler can read them via buildFixtureFromTask instead of solving a hardcoded fixture.
      if (report.counts().error() == 0) {
        UUID commitTaskId = "exam".equals(kind) ? taskId : null;
        importCommitService.commit(templateKind, commitTaskId, new BytesMultipartFile(originalName, bytes));
      }

      task.setStatus("importing");
      examTaskRepository.save(task);
      return new UploadResponse(uploaded.getId(), uploaded.getKind(), uploaded.getOriginalName(), uploaded.getSizeBytes(), uploaded.getSha256(), report);
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read upload file", exception);
    }
  }

  @Transactional
  public ValidationResponse validate(UUID taskId) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = examTaskRepository.findByOrgIdAndIdForUpdate(orgId, taskId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));
    ScheduleJobService.requireMutableTask(task);
    ValidationReport report = aggregateReport(orgId, taskId);
    task.setStatus(report.counts().error() == 0 ? "ready" : "importing");
    examTaskRepository.save(task);
    return new ValidationResponse(taskId, task.getStatus(), report, uploads(orgId, taskId));
  }

  public JobResponse startScheduleJob(UUID taskId) {
    return startScheduleJob(taskId, null);
  }

  public JobResponse startScheduleJob(UUID taskId, UUID policyId) {
    return startScheduleJob(taskId, policyId, null);
  }

  public JobResponse startScheduleJob(UUID taskId, UUID policyId, List<ReviewedUpload> reviewedUploads) {
    RunStartResponse started = scheduleJobService.startTaskRun(taskId, policyId, reviewedUploads);
    return job(started.runId(), started.eventsPath());
  }

  @Transactional(readOnly = true)
  public JobResponse job(UUID runId) {
    return job(runId, "/api/v1/schedule-jobs/%s/events".formatted(runId));
  }

  @Transactional(readOnly = true)
  public AssignmentsResponse assignments(UUID taskId) {
    return assignments(taskId, null);
  }

  @Transactional(readOnly = true)
  public AssignmentsResponse assignments(UUID taskId, UUID runId) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = getTask(orgId, taskId);
    Optional<ScheduleRun> selected = runId == null
        ? latestRun(orgId, task.getId())
        : scheduleRunRepository.findByOrgIdAndId(orgId, runId)
            .filter(run -> task.getId().equals(run.getExamTaskId()));
    if (runId != null && selected.isEmpty()) {
      // Return the same 404 for a missing run and a run owned by another task/tenant. Besides
      // keeping the contract simple, this avoids leaking the existence of another task's run.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule run not found for exam task");
    }
    if (selected.isEmpty()) {
      return new AssignmentsResponse(taskId, null, "pending", List.of(), List.of(), aiAdvice(List.of()), downloads(taskId), Map.of());
    }
    ScheduleRun run = selected.get();
    List<AssignmentRow> rows = assignmentRows(orgId, task.getId(), run.getId());
    RunSnapshot snapshot = scheduleJobService.snapshot(run.getId());
    Map<String, Object> stats = new LinkedHashMap<>(snapshot.stats());
    stats.put("revision", run.getRevision());
    stats.put("revisionCursor", run.getRevisionCursor());
    return new AssignmentsResponse(
        taskId,
        run.getId(),
        run.getStatus(),
        rows,
        snapshot.fairness(),
        aiAdvice(rows),
        downloads(taskId),
        stats
    );
  }

  @Transactional(readOnly = true)
  public byte[] export(UUID taskId, String format) {
    // 只接受白名单格式。此前任何非 'pdf' 值(含 csv/json/拼写错误)都被静默当作 xlsx 返回，调用方会误以为
    // 拿到了所请求的格式。未知格式直接 400，沿用统一错误信封。
    String fmt = (format == null || format.isBlank()) ? "xlsx" : format.trim();
    if (!"xlsx".equalsIgnoreCase(fmt) && !"pdf".equalsIgnoreCase(fmt)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_EXPORT_FORMAT: 仅支持 xlsx 或 pdf");
    }
    AssignmentsResponse response = assignments(taskId);
    return "pdf".equalsIgnoreCase(fmt) ? pdf(response.rows()) : xlsx(response.rows());
  }

  private JobResponse job(UUID runId, String eventsPath) {
    RunSnapshot snapshot = scheduleJobService.snapshot(runId);
    List<AssignmentRow> rows = assignmentRows(TenantContext.getRequired(), snapshot.taskId(), runId);
    return new JobResponse(
        runId,
        runId,
        snapshot.taskId(),
        snapshot.status(),
        snapshot.stage(),
        snapshot.progress(),
        snapshot.solver(),
        snapshot.seed(),
        snapshot.errorCode(),
        snapshot.errorMessage(),
        snapshot.stats(),
        snapshot.logs(),
        rows,
        snapshot.conflictSet(),
        snapshot.fairness(),
        aiAdvice(rows),
        downloads(snapshot.taskId()),
        eventsPath
    );
  }

  private ExamTaskResponse toTaskResponse(UUID orgId, ExamTask task) {
    List<UploadedFile> files = uploadedFileRepository.findByOrgIdAndExamTaskId(orgId, task.getId());
    Optional<ScheduleRun> latestRun = latestRun(orgId, task.getId());
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("uploads", files.size());
    stats.put("runs", scheduleRunRepository.findByOrgIdAndExamTaskId(orgId, task.getId()).size());
    stats.put("assignments", latestRun
        .map(run -> assignmentRepository.findByOrgIdAndScheduleRunId(orgId, run.getId()).size())
        .orElse(0));
    latestRun.ifPresent(run -> {
      stats.put("revision", run.getRevision());
      stats.put("revisionCursor", run.getRevisionCursor());
    });
    return new ExamTaskResponse(
        task.getId(),
        task.getTaskNo(),
        task.getName(),
        task.getStatus(),
        task.getStartsOn(),
        task.getEndsOn(),
        task.getCreatedAt(),
        task.getUpdatedAt(),
        task.getPublishedAt(),
        files.stream().map(file -> new UploadResponse(file.getId(), file.getKind(), file.getOriginalName(), file.getSizeBytes(), file.getSha256(), null)).toList(),
        latestRun.map(run -> job(run.getId())).orElse(null),
        stats
    );
  }

  private ValidationReport aggregateReport(UUID orgId, UUID taskId) {
    List<ImportBatch> batches = importBatchRepository.findByOrgIdAndExamTaskIdOrderByCreatedAtDesc(orgId, taskId);
    Map<String, ImportBatch> latestByKind = new LinkedHashMap<>();
    for (ImportBatch batch : batches) {
      latestByKind.putIfAbsent(batch.getKind(), batch);
    }

    // Readiness is judged against the data the scheduler will actually read, counting BOTH import
    // paths: the task-upload flow (ImportBatch rows linked to this task) AND the master-data commit
    // flow (/import/{kind}/commit writes teacher/classroom tables + task-scoped exams directly,
    // creating no task batch). A kind only counts as "missing" when neither source has any data —
    // otherwise validate would falsely report an empty/incomplete import right before a successful
    // schedule run (the master-data + exam tables are populated). (defect: validate-vs-commit blindness)
    int teachers = effectiveKindCount("teacher", latestByKind, orgId, taskId);
    int rooms = effectiveKindCount("classroom", latestByKind, orgId, taskId);
    int exams = effectiveKindCount("exam", latestByKind, orgId, taskId);
    Map<String, Integer> effectiveCounts = Map.of("teacher", teachers, "classroom", rooms, "exam", exams);

    List<ValidationItem> items = new ArrayList<>();
    for (String kind : REQUIRED_UPLOAD_KINDS) {
      if (effectiveCounts.getOrDefault(kind, 0) > 0) continue;
      items.add(new ValidationItem(
          "MISSING_" + kind.toUpperCase(),
          "error",
          "fields",
          kind,
          "file",
          null,
          kind,
          kind,
          "Need upload",
          "缺少" + kindLabel(kind) + "数据：请上传对应 Excel 或在主数据中维护后再排考",
          "Upload the " + kind + " Excel file or populate master data before scheduling",
          null,
          "QUICK_UPLOAD_MISSING"
      ));
    }

    // 跨表容量可行性：考试人数 > 该考场的考试容量时排考必然无解（CpSatScheduleSolver.precheck 的 H7
    // 会在建模前直接判 infeasible）。此前这里的 cap 分类恒为 0，用户看到 validate 全绿(completeness=100)
    // 后才在排考阶段吃到无解，且只被告知一条冲突。此处提前暴露：单文件 validate 做不到（考场容量在另一个
    // 文件里），但任务级 validate 时三表都已落库，可以跨表比对。
    List<ValidationItem> capItems = capacityFeasibilityItems(orgId, taskId);
    items.addAll(capItems);

    int capError = capItems.size();
    int error = items.size() + latestByKind.values().stream().mapToInt(ImportBatch::getErrorCount).sum();
    int pass = Math.max(0, teachers + rooms + exams - error);
    Counts counts = new Counts(pass, 0, 0, error);
    Counts fieldCounts = new Counts(Math.max(0, pass), 0, 0, Math.max(0, error - capError));
    Map<String, Counts> cats = new LinkedHashMap<>();
    cats.put("fields", fieldCounts);
    cats.put("avail", new Counts(0, 0, 0, 0));
    cats.put("cap", new Counts(0, 0, 0, capError));
    cats.put("time", new Counts(0, 0, 0, 0));
    cats.put("rules", new Counts(0, 0, 0, 0));
    cats.put("notes", new Counts(0, 0, 0, 0));
    Overview overview = new Overview(error == 0 ? 100 : Math.max(0, 100 - error * 8), teachers, rooms, exams, exams * 2);
    return new ValidationReport(error == 0 ? "pass" : "error", "fields", overview, counts, cats, items);
  }

  /**
   * 跨表容量可行性校验：任一「考试人数 &gt; 所绑考场的考试容量」都会让排考在 CP-SAT 建模前被
   * {@code CpSatScheduleSolver.precheck()} 的 H7 直接判 infeasible。
   *
   * <p>单文件 {@code /import/exams/validate} 做不到这件事（考场容量在另一个文件里），所以此前
   * validate 报 {@code completeness=100 / error=0} 的"全绿"，用户点排考才吃到无解，且冲突集只显示一条，
   * 极易误判为系统故障。任务级 validate 时三表都已落库，正是做交叉校验的地方。
   *
   * <p>同类问题按考场聚合，最多列出 {@value #MAX_CAPACITY_ITEMS} 条，避免 96 个考场全超容时刷屏。
   */
  private List<ValidationItem> capacityFeasibilityItems(UUID orgId, UUID taskId) {
    List<Map<String, Object>> rows;
    try {
      rows = jdbcTemplate.queryForList(
          "SELECT c.code AS room_code, c.exam_capacity, "
              + "       COALESCE((e.settings->>'seatDemand')::int, 1) AS seat_demand, "
              + "       e.subject_name, e.grade, COUNT(*) OVER () AS total "
              + "FROM exam e "
              + "JOIN exam_room er ON er.exam_id = e.id AND er.org_id = e.org_id "
              + "JOIN classroom c ON c.id = er.classroom_id AND c.org_id = e.org_id "
              + "WHERE e.org_id = ? AND e.exam_task_id = ? "
              + "  AND COALESCE((e.settings->>'seatDemand')::int, 1) > c.exam_capacity "
              + "ORDER BY c.code",
          orgId, taskId);
    } catch (RuntimeException ex) {
      // 校验属增强项：查询异常不得阻断既有 validate 流程（例如 settings 里存了非数字的脏值）。
      log.warn("capacity feasibility check skipped for task {}: {}", taskId, ex.toString());
      return List.of();
    }
    if (rows.isEmpty()) {
      return List.of();
    }
    int total = rows.size();
    List<ValidationItem> items = new ArrayList<>();
    for (Map<String, Object> r : rows.subList(0, Math.min(total, MAX_CAPACITY_ITEMS))) {
      String room = String.valueOf(r.get("room_code"));
      Object cap = r.get("exam_capacity");
      Object need = r.get("seat_demand");
      String subject = String.valueOf(r.get("subject_name"));
      String grade = String.valueOf(r.get("grade"));
      String tail = total > MAX_CAPACITY_ITEMS && items.size() == MAX_CAPACITY_ITEMS - 1
          ? "（共 %d 处考场容量不足，需一并处理）".formatted(total) : "";
      items.add(new ValidationItem(
          "CAPACITY_" + room,
          "error",
          "cap",
          "exam",
          room,
          null,
          "考试容量",
          "examCapacity",
          "Capacity insufficient",
          "考场 %s 容量 %s 人，但「%s·%s」需要 %s 人，排考会因容量不足而无解%s"
              .formatted(room, cap, grade, subject, need, tail),
          "请提高该考场的考试容量，或把该场考试改到容量足够的考场",
          String.valueOf(need),
          "EXAM_ROOM_CAPACITY_INSUFFICIENT"
      ));
    }
    return items;
  }

  /**
   * Effective row count for a required kind, preferring the task-linked import batch but falling
   * back to the real tables the scheduler reads — so both the upload flow and the master-data
   * commit flow are recognised as "data present".
   */
  private int effectiveKindCount(String kind, Map<String, ImportBatch> latestByKind, UUID orgId, UUID taskId) {
    ImportBatch batch = latestByKind.get(kind);
    if (batch != null && batch.getRowCount() > 0) {
      return batch.getRowCount();
    }
    return switch (kind) {
      case "teacher" -> (int) teacherRepository.countByOrgId(orgId);
      case "classroom" -> (int) classroomRepository.countByOrgId(orgId);
      case "exam" -> examRepository.findByOrgIdAndExamTaskId(orgId, taskId).size();
      default -> 0;
    };
  }

  private String kindLabel(String kind) {
    return switch (kind) {
      case "teacher" -> "教师";
      case "classroom" -> "考场";
      case "exam" -> "考试";
      default -> kind;
    };
  }

  private List<UploadResponse> uploads(UUID orgId, UUID taskId) {
    return uploadedFileRepository.findByOrgIdAndExamTaskId(orgId, taskId).stream()
        .map(file -> new UploadResponse(file.getId(), file.getKind(), file.getOriginalName(), file.getSizeBytes(), file.getSha256(), null))
        .toList();
  }

  private List<AssignmentRow> assignmentRows(UUID orgId, UUID taskId, UUID runId) {
    Map<UUID, Exam> exams = examRepository.findByOrgIdAndExamTaskId(orgId, taskId).stream()
        .collect(Collectors.toMap(Exam::getId, exam -> exam, (left, right) -> left));
    Map<UUID, Teacher> teachers = teacherRepository.findAllById(teacherIds(orgId, runId)).stream()
        .filter(teacher -> Objects.equals(teacher.getOrgId(), orgId))
        .collect(Collectors.toMap(Teacher::getId, teacher -> teacher, (left, right) -> left));
    Map<UUID, Classroom> rooms = classroomRepository.findAllById(roomIds(orgId, runId)).stream()
        .filter(room -> Objects.equals(room.getOrgId(), orgId))
        .collect(Collectors.toMap(Classroom::getId, room -> room, (left, right) -> left));
    return assignmentRepository.findByOrgIdAndScheduleRunId(orgId, runId).stream()
        .map(assignment -> toAssignmentRow(assignment, exams, teachers, rooms))
        .toList();
  }

  private AssignmentRow toAssignmentRow(
      ScheduleAssignment assignment,
      Map<UUID, Exam> exams,
      Map<UUID, Teacher> teachers,
      Map<UUID, Classroom> rooms
  ) {
    Exam exam = exams.get(assignment.getExamId());
    Teacher teacher = teachers.get(assignment.getTeacherId());
    Classroom room = rooms.get(assignment.getClassroomId());
    return new AssignmentRow(
        assignment.getId(),
        assignment.getExamId(),
        assignment.getClassroomId(),
        assignment.getTeacherId(),
        exam == null ? "Unknown subject" : exam.getSubjectName(),
        exam == null ? null : exam.getGrade(),
        assignment.getExamDate(),
        assignment.getStartTime(),
        assignment.getEndTime(),
        room == null ? "unknown" : room.getCode(),
        room == null ? "Unknown room" : room.getName(),
        teacher == null ? null : teacher.getTeacherNo(),
        teacher == null ? "Unknown teacher" : teacher.getName(),
        assignment.getRole(),
        assignment.getSource()
    );
  }

  private Set<UUID> teacherIds(UUID orgId, UUID runId) {
    return assignmentRepository.findByOrgIdAndScheduleRunId(orgId, runId).stream()
        .map(ScheduleAssignment::getTeacherId)
        .collect(Collectors.toSet());
  }

  private Set<UUID> roomIds(UUID orgId, UUID runId) {
    return assignmentRepository.findByOrgIdAndScheduleRunId(orgId, runId).stream()
        .map(ScheduleAssignment::getClassroomId)
        .collect(Collectors.toSet());
  }

  private AiAdvice aiAdvice(List<AssignmentRow> rows) {
    int teachers = (int) rows.stream().map(AssignmentRow::teacherId).distinct().count();
    int rooms = (int) rows.stream().map(AssignmentRow::roomId).distinct().count();
    int slots = (int) rows.stream()
        .map(row -> row.examDate() + "|" + row.startTime() + "|" + row.endTime())
        .distinct()
        .count();
    return new AiAdvice(
        List.of(
            new AiTarget("teacher", "教师负载复核", teachers, "复核监考工作量分布，关注异常偏高或偏低的教师。"),
            new AiTarget("room", "考场容量复核", rooms, "发布前复核考场使用情况与容量压力。"),
            new AiTarget("slot", "时段冲突复核", slots, "复核集中时段并进行后续人工微调。")
        ),
        List.of(
            "最终监考分配以求解器为准，本汇总仅用于锁定需复核的重点。",
            "发布前请核对教师负载、考场压力与时段集中度。",
            "可先将导出文件作为底稿，再到任务详情中继续精细调整。"
        )
    );
  }

  private Map<String, String> downloads(UUID taskId) {
    return Map.of(
        "xlsx", "/api/v1/exam-tasks/%s/export?format=xlsx".formatted(taskId),
        "pdf", "/api/v1/exam-tasks/%s/export?format=pdf".formatted(taskId)
    );
  }

  private byte[] xlsx(List<AssignmentRow> rows) {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("监考安排");
      Row header = sheet.createRow(0);
      List<String> columns = List.of("考试日期", "开始时间", "结束时间", "科目", "年级", "考场", "工号", "监考教师", "角色", "来源");
      for (int i = 0; i < columns.size(); i++) header.createCell(i).setCellValue(columns.get(i));
      for (int i = 0; i < rows.size(); i++) {
        AssignmentRow row = rows.get(i);
        Row sheetRow = sheet.createRow(i + 1);
        sheetRow.createCell(0).setCellValue(String.valueOf(row.examDate()));
        sheetRow.createCell(1).setCellValue(String.valueOf(row.startTime()));
        sheetRow.createCell(2).setCellValue(String.valueOf(row.endTime()));
        // 用户可控字段(姓名/科目/年级/考场/工号 来自导入)做公式注入防护；日期/时间/角色/来源系统生成，安全。
        sheetRow.createCell(3).setCellValue(csvSafe(row.subjectName()));
        sheetRow.createCell(4).setCellValue(csvSafe(row.grade() == null ? "" : row.grade()));
        sheetRow.createCell(5).setCellValue(csvSafe(row.roomCode()));
        sheetRow.createCell(6).setCellValue(csvSafe(row.teacherNo() == null ? "" : row.teacherNo()));
        sheetRow.createCell(7).setCellValue(csvSafe(row.teacherName()));
        sheetRow.createCell(8).setCellValue(roleLabel(row.role()));
        sheetRow.createCell(9).setCellValue(sourceLabel(row.source()));
      }
      for (int i = 0; i < columns.size(); i++) sheet.autoSizeColumn(i);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出 Excel 失败", exception);
    }
  }

  /**
   * CSV/公式注入防护(CWE-1236)：导入的文本若以 = + - @ 制表/回车 开头，导出 xlsx 后用户在 Excel/WPS 打开或
   * 另存 CSV 时会被当公式执行(如 =HYPERLINK/@cmd)。前置单引号使其强制为文本，消除可执行性。
   */
  private static String csvSafe(String value) {
    if (value == null || value.isEmpty()) {
      return value == null ? "" : value;
    }
    char first = value.charAt(0);
    if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
      return "'" + value;
    }
    return value;
  }

  /** 监考角色显示名（内部键 main/assistant 不变，仅导出/展示层中文化）。 */
  private static String roleLabel(String role) {
    if (role == null) return "";
    return switch (role) {
      case "main" -> "主监";
      case "assistant" -> "副监";
      default -> role;
    };
  }

  /** 分配来源显示名（solver=系统排考；manual=人工调整）。 */
  private static String sourceLabel(String source) {
    if (source == null) return "";
    return switch (source) {
      case "solver" -> "系统排考";
      case "manual" -> "人工调整";
      default -> source;
    };
  }

  // 用 OpenPDF 生成可打印的中文监考安排表：内嵌 CMap 的简体中文字体(STSong-Light/UniGB-UCS2-H，无需打包
  // TTF)、中文表头、主监/副监本地化、全部行(不再截断20行)、表格自动分页且跨页重复表头。
  // 旧实现用 Helvetica + ISO-8859-1 手搓单页 PDF：中文全变 ??、只画前20行却谎称 Rows:N、超页不分页。
  private byte[] pdf(List<AssignmentRow> rows) {
    com.lowagie.text.Document document =
        new com.lowagie.text.Document(com.lowagie.text.PageSize.A4.rotate(), 28, 28, 32, 28);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      com.lowagie.text.pdf.PdfWriter.getInstance(document, out);
      document.open();
      com.lowagie.text.pdf.BaseFont cjkBase;
      try {
        cjkBase = com.lowagie.text.pdf.BaseFont.createFont(
            "STSong-Light", "UniGB-UCS2-H", com.lowagie.text.pdf.BaseFont.NOT_EMBEDDED);
      } catch (Exception fontEx) {
        cjkBase = com.lowagie.text.pdf.BaseFont.createFont(
            com.lowagie.text.pdf.BaseFont.HELVETICA, com.lowagie.text.pdf.BaseFont.CP1252,
            com.lowagie.text.pdf.BaseFont.NOT_EMBEDDED);
      }
      com.lowagie.text.Font titleFont = new com.lowagie.text.Font(cjkBase, 14, com.lowagie.text.Font.BOLD);
      com.lowagie.text.Font headFont =
          new com.lowagie.text.Font(cjkBase, 9, com.lowagie.text.Font.BOLD, java.awt.Color.WHITE);
      com.lowagie.text.Font cellFont = new com.lowagie.text.Font(cjkBase, 9);

      com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph("监考安排表", titleFont);
      title.setSpacingAfter(4f);
      document.add(title);
      document.add(new com.lowagie.text.Paragraph("共 " + rows.size() + " 条监考分配",
          new com.lowagie.text.Font(cjkBase, 9, com.lowagie.text.Font.NORMAL, java.awt.Color.DARK_GRAY)));

      String[] cols = {"考试日期", "开始", "结束", "科目", "年级", "考场", "工号", "监考教师", "角色", "来源"};
      float[] widths = {2.2f, 1.2f, 1.2f, 2.0f, 1.1f, 1.6f, 1.8f, 2.0f, 1.1f, 1.6f};
      com.lowagie.text.pdf.PdfPTable table = new com.lowagie.text.pdf.PdfPTable(widths);
      table.setWidthPercentage(100);
      table.setSpacingBefore(8f);
      table.setHeaderRows(1); // 跨页自动重复表头
      java.awt.Color headBg = new java.awt.Color(37, 99, 145);
      for (String c : cols) {
        com.lowagie.text.pdf.PdfPCell cell =
            new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(c, headFont));
        cell.setBackgroundColor(headBg);
        cell.setPadding(4f);
        cell.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_CENTER);
        table.addCell(cell);
      }
      for (AssignmentRow row : rows) { // 全部行，PdfPTable 自动分页
        pdfCell(table, String.valueOf(row.examDate()), cellFont);
        pdfCell(table, String.valueOf(row.startTime()), cellFont);
        pdfCell(table, String.valueOf(row.endTime()), cellFont);
        pdfCell(table, row.subjectName(), cellFont);
        pdfCell(table, row.grade() == null ? "" : row.grade(), cellFont);
        pdfCell(table, row.roomCode(), cellFont);
        pdfCell(table, row.teacherNo() == null ? "" : row.teacherNo(), cellFont);
        pdfCell(table, row.teacherName(), cellFont);
        pdfCell(table, roleLabel(row.role()), cellFont);
        pdfCell(table, sourceLabel(row.source()), cellFont);
      }
      document.add(table);
      document.close();
      return out.toByteArray();
    } catch (Exception exception) {
      // 涵盖 DocumentException 与字体回退路径的 IOException（BaseFont.createFont 抛受检异常）。
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出 PDF 失败", exception);
    }
  }

  private static void pdfCell(com.lowagie.text.pdf.PdfPTable table, String text, com.lowagie.text.Font font) {
    com.lowagie.text.pdf.PdfPCell cell =
        new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(text == null ? "" : text, font));
    cell.setPadding(3f);
    table.addCell(cell);
  }

  private Optional<ScheduleRun> latestRun(UUID orgId, UUID taskId) {
    // Prefer the latest SUCCEEDED run as the task's active schedule: a later infeasible/failed re-run
    // (e.g. an over-capacity tweak) must NOT hide an already-generated good schedule. Only when no run
    // ever succeeded do we surface the latest (infeasible/failed) run so the user sees the real state.
    // 迭代10 P1: 抽到 ScheduleRunRepository.findActiveRun 作为唯一真源，编辑/发布路径共用，永不再漂移。
    return scheduleRunRepository.findActiveRun(orgId, taskId);
  }

  private ExamTask getTask(UUID orgId, UUID taskId) {
    return examTaskRepository.findByOrgIdAndId(orgId, taskId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));
  }

  private String normalizeKind(String rawKind) {
    String kind = rawKind == null ? "" : rawKind.trim().toLowerCase();
    return switch (kind) {
      case "teacher", "teachers", "t01" -> "teacher";
      case "classroom", "classrooms", "room", "rooms", "t02" -> "classroom";
      case "exam", "exams", "schedule", "schedules", "t03" -> "exam";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported upload kind: " + rawKind);
    };
  }

  private String templateKind(String kind) {
    return switch (kind) {
      case "teacher" -> "teachers";
      case "classroom" -> "classrooms";
      case "exam" -> "exams";
      default -> kind;
    };
  }

  private int rowCount(String kind, ValidationReport report) {
    return switch (kind) {
      case "teacher" -> report.overview().teachers();
      case "classroom" -> report.overview().rooms();
      case "exam" -> report.overview().exams();
      default -> 0;
    };
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String shortId() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  private String nonBlank(String value, String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  private String normalizeSource(String value) {
    if (value == null || value.isBlank()) {
      return "manual";
    }
    String source = value.trim().toLowerCase();
    return switch (source) {
      case "manual", "quick", "ai_policy", "import" -> source;
      default -> "manual";
    };
  }

  private record BytesMultipartFile(String originalFilename, byte[] bytes) implements MultipartFile {

    @Override
    public String getName() {
      return "file";
    }

    @Override
    public String getOriginalFilename() {
      return originalFilename;
    }

    @Override
    public String getContentType() {
      return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public boolean isEmpty() {
      return bytes == null || bytes.length == 0;
    }

    @Override
    public long getSize() {
      return bytes == null ? 0 : bytes.length;
    }

    @Override
    public byte[] getBytes() {
      return bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(getBytes());
    }

    @Override
    public void transferTo(java.io.File dest) {
      throw new UnsupportedOperationException("In-memory upload does not support transferTo");
    }
  }
}
