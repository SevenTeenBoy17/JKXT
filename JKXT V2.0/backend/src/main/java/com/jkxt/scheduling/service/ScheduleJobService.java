package com.jkxt.scheduling.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
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
import com.jkxt.scheduling.solver.ScheduleFixtureFactory;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import com.jkxt.scheduling.solver.ScheduleSolverResult.Assignment;
import com.jkxt.scheduling.solver.ScheduleSolverResult.ConflictItem;
import com.jkxt.scheduling.solver.ScheduleSolverResult.FairnessItem;
import com.jkxt.tasks.domain.Exam;
import com.jkxt.tasks.domain.ExamTask;
import com.jkxt.tasks.repo.ExamRepository;
import com.jkxt.tasks.repo.ExamTaskRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ScheduleJobService {

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
  private final TransactionTemplate tx;
  private final Executor executor;
  private final ObjectMapper objectMapper;

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
      PlatformTransactionManager transactionManager,
      @Qualifier("scheduleTaskExecutor") Executor executor,
      ObjectMapper objectMapper
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
    this.tx = new TransactionTemplate(transactionManager);
    this.executor = executor;
    this.objectMapper = objectMapper;
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
    publish(prepared.runId(), "pending", "queued", 0, "排考任务已创建，等待求解器执行。");
    CompletableFuture.runAsync(() -> executePreparedRun(orgId, prepared.runId(), prepared.fixture()), executor);
    return new RunStartResponse(
        prepared.runId(),
        prepared.taskId(),
        "pending",
        "queued",
        0,
        "/api/v1/scheduling/runs/%s/events".formatted(prepared.runId())
    );
  }

  public RunStartResponse startTaskRun(UUID taskId) {
    UUID orgId = TenantContext.getRequired();
    PreparedRun prepared = tx.execute(status -> {
      ExamTask task = examTaskRepository.findByOrgIdAndId(orgId, taskId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));
      task.setStatus("scheduling");
      examTaskRepository.save(task);
      return prepareRunForTask(orgId, task, fixtureFactory.feasible());
    });
    if (prepared == null) throw new IllegalStateException("Failed to prepare schedule run");
    publish(prepared.runId(), "pending", "queued", 0, "Quick schedule job queued.");
    CompletableFuture.runAsync(() -> executePreparedRun(orgId, prepared.runId(), prepared.fixture()), executor);
    return new RunStartResponse(
        prepared.runId(),
        prepared.taskId(),
        "pending",
        "queued",
        0,
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
          input.classId(),
          persisted.roomIds().getOrDefault(input.roomId(), input.roomId()),
          input.subjectName(),
          input.grade(),
          input.date(),
          input.start(),
          input.end(),
          input.seatDemand(),
          input.supervisorsPerRoom()
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
    return new PreparedRun(run.getId(), task.getId(), runFixture);
  }

  private PreparedRun prepareRunForTask(UUID orgId, ExamTask task, ScheduleFixture fixture) {
    PersistedFixtureData persisted = upsertFixtureMasterData(orgId, fixture);
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
          input.classId(),
          persisted.roomIds().getOrDefault(input.roomId(), input.roomId()),
          input.subjectName(),
          input.grade(),
          input.date(),
          input.start(),
          input.end(),
          input.seatDemand(),
          input.supervisorsPerRoom()
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
        "inputSource", "quick-task",
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
    return new PreparedRun(run.getId(), task.getId(), runFixture);
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
            input.homeroomClassIds()
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

  private void executePreparedRun(UUID orgId, UUID runId, ScheduleFixture fixture) {
    try {
      markRunning(orgId, runId);
      publish(runId, "running", "读取小样本 fixture", 15, "已读取教师、考场、考试小样本。");
      publish(runId, "running", "构建 H1-H7 约束模型", 35, "正在构建 CP-SAT 决策变量与硬约束。");
      ScheduleSolverResult result = solver.solve(fixture);
      publish(runId, "running", "求解完成，写入结果", 80, "CP-SAT 返回 %s。".formatted(result.solverStatus()));
      persistResult(orgId, runId, result);
      publish(
          runId,
          result.status(),
          finalStage(result),
          100,
          finalMessage(result)
      );
    } catch (Exception ex) {
      String message = truncateError(ex.getMessage());
      tx.executeWithoutResult(status -> {
        ScheduleRun run = runRepository.findByOrgIdAndId(orgId, runId).orElseThrow();
        run.setStatus("failed");
        run.setStage("failed");
        run.setProgress(100);
        run.setErrorCode("SCHEDULE_JOB_FAILED");
        run.setErrorMessage(message);
        run.setFinishedAt(Instant.now());
        runRepository.save(run);
        examTaskRepository.findByOrgIdAndId(orgId, run.getExamTaskId()).ifPresent(task -> {
          task.setStatus("failed");
          examTaskRepository.save(task);
        });
      });
      publish(runId, "failed", "执行失败", 100, message);
    }
  }

  private void markRunning(UUID orgId, UUID runId) {
    tx.executeWithoutResult(status -> {
      ScheduleRun run = runRepository.findByOrgIdAndId(orgId, runId).orElseThrow();
      run.setStatus("running");
      run.setStage("CP-SAT 初始化");
      run.setProgress(10);
      run.setStartedAt(Instant.now());
      runRepository.save(run);
    });
  }

  private void persistResult(UUID orgId, UUID runId, ScheduleSolverResult result) {
    tx.executeWithoutResult(status -> {
      ScheduleRun run = runRepository.findByOrgIdAndId(orgId, runId).orElseThrow();
      run.setStatus(result.status());
      run.setStage(result.status());
      run.setProgress(100);
      run.setFinishedAt(Instant.now());
      run.setErrorCode(result.errorCode());
      run.setErrorMessage(truncateError(result.errorMessage()));
      Map<String, Object> stats = new LinkedHashMap<>(result.stats());
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
    });
  }

  private void publish(UUID runId, String status, String stage, int progress, String message) {
    ProgressEvent event = new ProgressEvent(runId, status, stage, progress, message, Instant.now());
    progressSink.publish(event);
    tx.executeWithoutResult(txStatus -> runRepository.findById(runId).ifPresent(run -> {
      run.setStatus(status);
      run.setStage(stage);
      run.setProgress(progress);
      List<Map<String, Object>> logs = new ArrayList<>(run.getLogs());
      logs.add(objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {
      }));
      run.setLogs(logs);
      runRepository.save(run);
    }));
  }

  private String finalStage(ScheduleSolverResult result) {
    if (result.feasible()) return "生成结果预览";
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

  private record PreparedRun(UUID runId, UUID taskId, ScheduleFixture fixture) {
  }

  private record PersistedFixtureData(Map<UUID, UUID> teacherIds, Map<UUID, UUID> roomIds) {
  }
}
