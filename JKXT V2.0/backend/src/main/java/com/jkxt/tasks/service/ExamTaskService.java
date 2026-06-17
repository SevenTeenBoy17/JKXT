package com.jkxt.tasks.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.importer.api.ImportDtos.Counts;
import com.jkxt.importer.api.ImportDtos.Overview;
import com.jkxt.importer.api.ImportDtos.ValidationItem;
import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.importer.domain.ImportBatch;
import com.jkxt.importer.domain.UploadedFile;
import com.jkxt.importer.repo.ImportBatchRepository;
import com.jkxt.importer.repo.UploadedFileRepository;
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
import java.nio.charset.StandardCharsets;
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

  private static final List<String> REQUIRED_UPLOAD_KINDS = List.of("teacher", "classroom", "exam");

  private final ExamTaskRepository examTaskRepository;
  private final UploadedFileRepository uploadedFileRepository;
  private final ImportBatchRepository importBatchRepository;
  private final ScheduleRunRepository scheduleRunRepository;
  private final ScheduleAssignmentRepository assignmentRepository;
  private final ExamRepository examRepository;
  private final TeacherRepository teacherRepository;
  private final ClassroomRepository classroomRepository;
  private final ImportValidationService validationService;
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
      ScheduleJobService scheduleJobService
  ) {
    this.examTaskRepository = examTaskRepository;
    this.uploadedFileRepository = uploadedFileRepository;
    this.importBatchRepository = importBatchRepository;
    this.scheduleRunRepository = scheduleRunRepository;
    this.assignmentRepository = assignmentRepository;
    this.examRepository = examRepository;
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.validationService = validationService;
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

  @Transactional
  public UploadResponse upload(UUID taskId, String rawKind, MultipartFile file, boolean sample) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = getTask(orgId, taskId);
    String kind = normalizeKind(rawKind);
    String templateKind = templateKind(kind);

    try {
      byte[] bytes = sample || file == null || file.isEmpty()
          ? validationService.template(templateKind)
          : file.getBytes();
      String originalName = sample || file == null || file.isEmpty()
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
    ExamTask task = getTask(orgId, taskId);
    ValidationReport report = aggregateReport(orgId, taskId);
    task.setStatus(report.counts().error() == 0 ? "ready" : "importing");
    examTaskRepository.save(task);
    return new ValidationResponse(taskId, task.getStatus(), report, uploads(orgId, taskId));
  }

  public JobResponse startScheduleJob(UUID taskId) {
    RunStartResponse started = scheduleJobService.startTaskRun(taskId);
    return job(started.runId(), started.eventsPath());
  }

  @Transactional(readOnly = true)
  public JobResponse job(UUID runId) {
    return job(runId, "/api/v1/schedule-jobs/%s/events".formatted(runId));
  }

  @Transactional(readOnly = true)
  public AssignmentsResponse assignments(UUID taskId) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = getTask(orgId, taskId);
    Optional<ScheduleRun> latest = latestRun(orgId, task.getId());
    if (latest.isEmpty()) {
      return new AssignmentsResponse(taskId, null, "pending", List.of(), List.of(), aiAdvice(List.of()), downloads(taskId), Map.of());
    }
    ScheduleRun run = latest.get();
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
    AssignmentsResponse response = assignments(taskId);
    if ("pdf".equalsIgnoreCase(format)) {
      return pdf(response.rows());
    }
    return xlsx(response.rows());
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

    List<ValidationItem> items = new ArrayList<>();
    for (String kind : REQUIRED_UPLOAD_KINDS) {
      if (latestByKind.containsKey(kind)) continue;
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
          "Missing " + kind + " workbook",
          "Upload the " + kind + " Excel file before scheduling",
          null,
          "QUICK_UPLOAD_MISSING"
      ));
    }

    int teachers = latestByKind.getOrDefault("teacher", emptyBatch()).getRowCount();
    int rooms = latestByKind.getOrDefault("classroom", emptyBatch()).getRowCount();
    int exams = latestByKind.getOrDefault("exam", emptyBatch()).getRowCount();
    int error = items.size() + latestByKind.values().stream().mapToInt(ImportBatch::getErrorCount).sum();
    int pass = Math.max(0, teachers + rooms + exams - error);
    Counts counts = new Counts(pass, 0, 0, error);
    Counts fieldCounts = new Counts(pass, 0, 0, error);
    Map<String, Counts> cats = new LinkedHashMap<>();
    cats.put("fields", fieldCounts);
    cats.put("avail", new Counts(0, 0, 0, 0));
    cats.put("cap", new Counts(0, 0, 0, 0));
    cats.put("time", new Counts(0, 0, 0, 0));
    cats.put("rules", new Counts(0, 0, 0, 0));
    cats.put("notes", new Counts(0, 0, 0, 0));
    Overview overview = new Overview(error == 0 ? 100 : Math.max(0, 100 - error * 8), teachers, rooms, exams, exams * 2);
    return new ValidationReport(error == 0 ? "pass" : "error", "fields", overview, counts, cats, items);
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
            new AiTarget("teacher", "Teacher load review", teachers, "Review workload spread and unusually low or high assignments."),
            new AiTarget("room", "Room capacity review", rooms, "Review room usage and capacity pressure before publishing."),
            new AiTarget("slot", "Time-slot conflict review", slots, "Review concentrated slots and follow-up manual adjustments.")
        ),
        List.of(
            "Solver is the authority for final assignments; AI only summarizes review targets.",
            "Check teacher load, room pressure, and slot concentration before publishing.",
            "Use exports as a base version, then continue detailed edits in task detail."
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
      Sheet sheet = workbook.createSheet("assignments");
      Row header = sheet.createRow(0);
      List<String> columns = List.of("Date", "Start", "End", "Subject", "Grade", "Room", "Teacher", "Role", "Source");
      for (int i = 0; i < columns.size(); i++) header.createCell(i).setCellValue(columns.get(i));
      for (int i = 0; i < rows.size(); i++) {
        AssignmentRow row = rows.get(i);
        Row sheetRow = sheet.createRow(i + 1);
        sheetRow.createCell(0).setCellValue(String.valueOf(row.examDate()));
        sheetRow.createCell(1).setCellValue(String.valueOf(row.startTime()));
        sheetRow.createCell(2).setCellValue(String.valueOf(row.endTime()));
        sheetRow.createCell(3).setCellValue(row.subjectName());
        sheetRow.createCell(4).setCellValue(row.grade() == null ? "" : row.grade());
        sheetRow.createCell(5).setCellValue(row.roomCode());
        sheetRow.createCell(6).setCellValue(row.teacherName());
        sheetRow.createCell(7).setCellValue(row.role());
        sheetRow.createCell(8).setCellValue(row.source());
      }
      for (int i = 0; i < columns.size(); i++) sheet.autoSizeColumn(i);
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to export xlsx", exception);
    }
  }

  private byte[] pdf(List<AssignmentRow> rows) {
    List<String> lines = new ArrayList<>();
    lines.add("JKXT Exam Assignments");
    lines.add("Rows: " + rows.size());
    rows.stream().limit(20).forEach(row -> lines.add("%s %s %s %s %s %s".formatted(
        row.examDate(),
        row.startTime(),
        row.subjectName(),
        row.roomCode(),
        row.teacherName(),
        row.role()
    )));
    StringBuilder content = new StringBuilder("BT /F1 12 Tf 50 780 Td ");
    for (String line : lines) {
      content.append("(").append(pdfEscape(line)).append(") Tj 0 -18 Td ");
    }
    content.append("ET");
    byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
    String[] objects = new String[] {
        "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n",
        "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n",
        "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n",
        "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n",
        "5 0 obj << /Length " + stream.length + " >> stream\n" + new String(stream, StandardCharsets.ISO_8859_1) + "\nendstream endobj\n"
    };
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      out.write("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
      List<Integer> offsets = new ArrayList<>();
      for (String object : objects) {
        offsets.add(out.size());
        out.write(object.getBytes(StandardCharsets.ISO_8859_1));
      }
      int xrefOffset = out.size();
      out.write(("xref\n0 " + (objects.length + 1) + "\n").getBytes(StandardCharsets.ISO_8859_1));
      out.write("0000000000 65535 f \n".getBytes(StandardCharsets.ISO_8859_1));
      for (Integer offset : offsets) {
        out.write(("%010d 00000 n \n".formatted(offset)).getBytes(StandardCharsets.ISO_8859_1));
      }
      out.write(("trailer << /Root 1 0 R /Size " + (objects.length + 1) + " >>\nstartxref\n" + xrefOffset + "\n%%EOF\n")
          .getBytes(StandardCharsets.ISO_8859_1));
      return out.toByteArray();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to export pdf", exception);
    }
  }

  private String pdfEscape(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
  }

  private Optional<ScheduleRun> latestRun(UUID orgId, UUID taskId) {
    return scheduleRunRepository.findByOrgIdAndExamTaskIdOrderByCreatedAtDesc(orgId, taskId).stream().findFirst();
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

  private ImportBatch emptyBatch() {
    ImportBatch batch = new ImportBatch();
    batch.setRowCount(0);
    return batch;
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
