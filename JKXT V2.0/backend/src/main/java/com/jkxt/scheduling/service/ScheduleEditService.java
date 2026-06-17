package com.jkxt.scheduling.service;

import com.jkxt.audit.domain.AuditLog;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.notify.domain.Notification;
import com.jkxt.notify.repo.NotificationRepository;
import com.jkxt.scheduling.api.ScheduleEditDtos.ConflictCheckRequest;
import com.jkxt.scheduling.api.ScheduleEditDtos.ConflictCheckResponse;
import com.jkxt.scheduling.api.ScheduleEditDtos.PublishResponse;
import com.jkxt.scheduling.api.ScheduleEditDtos.RevisionResponse;
import com.jkxt.scheduling.api.ScheduleEditDtos.ScheduleConflict;
import com.jkxt.scheduling.api.ScheduleEditDtos.SchedulePatchRequest;
import com.jkxt.scheduling.api.ScheduleEditDtos.SchedulePatchResponse;
import com.jkxt.scheduling.domain.ScheduleAssignment;
import com.jkxt.scheduling.domain.ScheduleRevision;
import com.jkxt.scheduling.domain.ScheduleRun;
import com.jkxt.scheduling.repo.ScheduleAssignmentRepository;
import com.jkxt.scheduling.repo.ScheduleRevisionRepository;
import com.jkxt.scheduling.repo.ScheduleRunRepository;
import com.jkxt.security.auth.SessionPrincipal;
import com.jkxt.tasks.api.ExamTaskDtos.AssignmentRow;
import com.jkxt.tasks.domain.Exam;
import com.jkxt.tasks.domain.ExamTask;
import com.jkxt.tasks.repo.ExamRepository;
import com.jkxt.tasks.repo.ExamTaskRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScheduleEditService {

  private final ExamTaskRepository taskRepository;
  private final ExamRepository examRepository;
  private final TeacherRepository teacherRepository;
  private final ClassroomRepository classroomRepository;
  private final ScheduleRunRepository runRepository;
  private final ScheduleAssignmentRepository assignmentRepository;
  private final ScheduleRevisionRepository revisionRepository;
  private final NotificationRepository notificationRepository;
  private final AuditLogRepository auditLogRepository;

  public ScheduleEditService(
      ExamTaskRepository taskRepository,
      ExamRepository examRepository,
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      ScheduleRunRepository runRepository,
      ScheduleAssignmentRepository assignmentRepository,
      ScheduleRevisionRepository revisionRepository,
      NotificationRepository notificationRepository,
      AuditLogRepository auditLogRepository
  ) {
    this.taskRepository = taskRepository;
    this.examRepository = examRepository;
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.runRepository = runRepository;
    this.assignmentRepository = assignmentRepository;
    this.revisionRepository = revisionRepository;
    this.notificationRepository = notificationRepository;
    this.auditLogRepository = auditLogRepository;
  }

  @Transactional(readOnly = true)
  public ConflictCheckResponse checkConflicts(UUID taskId, ConflictCheckRequest request) {
    Context context = context(taskId);
    ScheduleAssignment assignment = assignment(context, request.assignmentId());
    Teacher target = teacher(context.orgId(), request.teacherId());
    List<ScheduleConflict> conflicts = conflicts(context, assignment, target);
    return new ConflictCheckResponse(
        context.task().getId(),
        context.run().getId(),
        assignment.getId(),
        target.getId(),
        conflicts.isEmpty(),
        conflicts.size(),
        conflicts
    );
  }

  @Transactional
  public SchedulePatchResponse patch(UUID taskId, SchedulePatchRequest request) {
    Context context = context(taskId);
    ScheduleRun run = context.run();
    if (request.expectedRevision() != null && request.expectedRevision() != run.getRevisionCursor()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SCHEDULE_REVISION_STALE");
    }

    ScheduleAssignment assignment = assignment(context, request.assignmentId());
    Teacher target = teacher(context.orgId(), request.teacherId());
    List<ScheduleConflict> conflicts = conflicts(context, assignment, target);
    if (!conflicts.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT");
    }

    if (run.getRevisionCursor() < run.getRevision()) {
      revisionRepository.deleteByOrgIdAndScheduleRunIdAndRevisionNoGreaterThan(
          context.orgId(),
          run.getId(),
          run.getRevisionCursor()
      );
      run.setRevision(run.getRevisionCursor());
    }

    Map<String, Object> before = state(assignment, teacherName(context.orgId(), assignment.getTeacherId()));
    assignment.setTeacherId(target.getId());
    assignment.setSource("manual");
    assignment.setConflict(new LinkedHashMap<>());
    assignment = assignmentRepository.saveAndFlush(assignment);

    int nextRevision = run.getRevisionCursor() + 1;
    ScheduleRevision revision = new ScheduleRevision();
    revision.setOrgId(context.orgId());
    revision.setExamTaskId(context.task().getId());
    revision.setScheduleRunId(run.getId());
    revision.setRevisionNo(nextRevision);
    revision.setAction("reassign");
    revision.setAssignmentId(assignment.getId());
    revision.setBeforeState(before);
    revision.setAfterState(state(assignment, target.getName()));
    revision.setCreatedBy(actorId());
    revisionRepository.save(revision);

    run.setRevision(nextRevision);
    run.setRevisionCursor(nextRevision);
    updateRevisionStats(run);
    runRepository.save(run);
    audit(context.orgId(), "SCHEDULE_REASSIGN", "schedule_assignment", assignment.getId(), "info", Map.of(
        "taskId", context.task().getId().toString(),
        "runId", run.getId().toString(),
        "revision", nextRevision,
        "reason", request.reason() == null ? "manual-drag" : request.reason()
    ));

    return new SchedulePatchResponse(
        context.task().getId(),
        run.getId(),
        assignment.getId(),
        target.getId(),
        target.getName(),
        run.getRevision(),
        run.getRevisionCursor(),
        row(context.orgId(), context.task().getId(), assignment),
        List.of()
    );
  }

  @Transactional
  public RevisionResponse undo(UUID taskId) {
    Context context = context(taskId);
    ScheduleRun run = context.run();
    if (run.getRevisionCursor() <= 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SCHEDULE_REVISION_NOTHING_TO_UNDO");
    }
    ScheduleRevision revision = revision(context.orgId(), run, run.getRevisionCursor());
    ScheduleAssignment assignment = assignment(context, revision.getAssignmentId());
    applyState(context.orgId(), assignment, revision.getBeforeState());
    revision.setStatus("undone");
    revisionRepository.save(revision);
    run.setRevisionCursor(run.getRevisionCursor() - 1);
    updateRevisionStats(run);
    runRepository.save(run);
    audit(context.orgId(), "SCHEDULE_UNDO", "schedule_assignment", assignment.getId(), "info", Map.of(
        "taskId", context.task().getId().toString(),
        "runId", run.getId().toString(),
        "revision", revision.getRevisionNo()
    ));
    return new RevisionResponse(
        context.task().getId(),
        run.getId(),
        run.getRevision(),
        run.getRevisionCursor(),
        "undo",
        row(context.orgId(), context.task().getId(), assignment)
    );
  }

  @Transactional
  public RevisionResponse redo(UUID taskId) {
    Context context = context(taskId);
    ScheduleRun run = context.run();
    if (run.getRevisionCursor() >= run.getRevision()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SCHEDULE_REVISION_NOTHING_TO_REDO");
    }
    ScheduleRevision revision = revision(context.orgId(), run, run.getRevisionCursor() + 1);
    ScheduleAssignment assignment = assignment(context, revision.getAssignmentId());
    applyState(context.orgId(), assignment, revision.getAfterState());
    revision.setStatus("applied");
    revisionRepository.save(revision);
    run.setRevisionCursor(run.getRevisionCursor() + 1);
    updateRevisionStats(run);
    runRepository.save(run);
    audit(context.orgId(), "SCHEDULE_REDO", "schedule_assignment", assignment.getId(), "info", Map.of(
        "taskId", context.task().getId().toString(),
        "runId", run.getId().toString(),
        "revision", revision.getRevisionNo()
    ));
    return new RevisionResponse(
        context.task().getId(),
        run.getId(),
        run.getRevision(),
        run.getRevisionCursor(),
        "redo",
        row(context.orgId(), context.task().getId(), assignment)
    );
  }

  @Transactional
  public PublishResponse publish(UUID taskId) {
    Context context = context(taskId);
    List<ScheduleAssignment> assignments = assignmentRepository.findByOrgIdAndScheduleRunId(context.orgId(), context.run().getId());
    if (assignments.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SCHEDULE_PUBLISH_REQUIRES_ASSIGNMENTS");
    }

    Instant now = Instant.now();
    ExamTask task = context.task();
    task.setStatus("published");
    task.setPublishedAt(now);
    taskRepository.save(task);

    int teacherCount = (int) assignments.stream().map(ScheduleAssignment::getTeacherId).distinct().count();
    Notification notification = new Notification();
    notification.setOrgId(context.orgId());
    notification.setExamTaskId(task.getId());
    notification.setType("schedule_published");
    notification.setTitle("Schedule published");
    notification.setBody("Exam proctor schedule has been published.");
    notification.setStatus("sent");
    notification.setTargetUrl("/tasks/" + task.getId());
    notification.setPublishedAt(now);
    notification.setPayload(Map.of(
        "runId", context.run().getId().toString(),
        "assignmentCount", assignments.size(),
        "teacherCount", teacherCount
    ));
    notification = notificationRepository.save(notification);

    audit(context.orgId(), "SCHEDULE_PUBLISH", "exam_task", task.getId(), "info", Map.of(
        "runId", context.run().getId().toString(),
        "notificationId", notification.getId().toString(),
        "teacherCount", teacherCount
    ));

    return new PublishResponse(
        task.getId(),
        task.getStatus(),
        now,
        notification.getId(),
        notification.getStatus(),
        teacherCount
    );
  }

  private Context context(UUID taskId) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = taskRepository.findByOrgIdAndId(orgId, taskId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));
    ScheduleRun run = runRepository.findByOrgIdAndExamTaskIdOrderByCreatedAtDesc(orgId, taskId).stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule run not found"));
    return new Context(orgId, task, run);
  }

  private ScheduleAssignment assignment(Context context, UUID assignmentId) {
    ScheduleAssignment assignment = assignmentRepository.findByOrgIdAndId(context.orgId(), assignmentId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule assignment not found"));
    if (!assignment.getScheduleRunId().equals(context.run().getId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule assignment does not belong to latest run");
    }
    return assignment;
  }

  private Teacher teacher(UUID orgId, UUID teacherId) {
    return teacherRepository.findByOrgIdAndId(orgId, teacherId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Teacher not found"));
  }

  private ScheduleRevision revision(UUID orgId, ScheduleRun run, int revisionNo) {
    return revisionRepository.findByOrgIdAndScheduleRunIdAndRevisionNo(orgId, run.getId(), revisionNo)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule revision not found"));
  }

  private List<ScheduleConflict> conflicts(Context context, ScheduleAssignment assignment, Teacher target) {
    List<ScheduleConflict> conflicts = assignmentRepository
        .findByOrgIdAndTeacherIdAndExamDate(context.orgId(), target.getId(), assignment.getExamDate())
        .stream()
        .filter(other -> !Objects.equals(other.getId(), assignment.getId()))
        .filter(other -> Objects.equals(other.getScheduleRunId(), context.run().getId()))
        .filter(other -> overlaps(assignment.getStartTime(), assignment.getEndTime(), other.getStartTime(), other.getEndTime()))
        .map(other -> new ScheduleConflict(
            "H1_TEACHER_TIME_OVERLAP",
            "hard",
            "%s already has an assignment in this time slot.".formatted(target.getName()),
            assignment.getId(),
            target.getId(),
            other.getId(),
            assignment.getExamDate(),
            assignment.getStartTime(),
            assignment.getEndTime()
        ))
        .collect(Collectors.toList());

    if (!"active".equalsIgnoreCase(target.getStatus())) {
      conflicts.add(new ScheduleConflict(
          "H2_TEACHER_UNAVAILABLE",
          "hard",
          "%s is not active and cannot be assigned.".formatted(target.getName()),
          assignment.getId(),
          target.getId(),
          null,
          assignment.getExamDate(),
          assignment.getStartTime(),
          assignment.getEndTime()
      ));
    }
    return conflicts;
  }

  private boolean overlaps(LocalTime leftStart, LocalTime leftEnd, LocalTime rightStart, LocalTime rightEnd) {
    return leftStart.isBefore(rightEnd) && leftEnd.isAfter(rightStart);
  }

  private Map<String, Object> state(ScheduleAssignment assignment, String teacherName) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("teacherId", assignment.getTeacherId().toString());
    state.put("teacherName", teacherName);
    state.put("source", assignment.getSource());
    return state;
  }

  private void applyState(UUID orgId, ScheduleAssignment assignment, Map<String, Object> state) {
    UUID teacherId = UUID.fromString(String.valueOf(state.get("teacherId")));
    teacher(orgId, teacherId);
    assignment.setTeacherId(teacherId);
    assignment.setSource(String.valueOf(state.getOrDefault("source", "manual")));
    assignment.setConflict(new LinkedHashMap<>());
    assignmentRepository.saveAndFlush(assignment);
  }

  private AssignmentRow row(UUID orgId, UUID taskId, ScheduleAssignment assignment) {
    Exam exam = examRepository.findByOrgIdAndId(orgId, assignment.getExamId()).orElse(null);
    Teacher teacher = teacherRepository.findByOrgIdAndId(orgId, assignment.getTeacherId()).orElse(null);
    Classroom room = classroomRepository.findByOrgIdAndId(orgId, assignment.getClassroomId()).orElse(null);
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

  private String teacherName(UUID orgId, UUID teacherId) {
    return teacherRepository.findByOrgIdAndId(orgId, teacherId)
        .map(Teacher::getName)
        .orElse("Unknown teacher");
  }

  private void updateRevisionStats(ScheduleRun run) {
    Map<String, Object> stats = new LinkedHashMap<>(run.getStats());
    stats.put("revision", run.getRevision());
    stats.put("revisionCursor", run.getRevisionCursor());
    run.setStats(stats);
  }

  private UUID actorId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
      return principal.userId();
    }
    return null;
  }

  private void audit(UUID orgId, String action, String targetType, UUID targetId, String severity, Map<String, Object> payload) {
    AuditLog log = new AuditLog();
    log.setOrgId(orgId);
    log.setActorId(actorId());
    log.setAction(action);
    log.setTargetType(targetType);
    log.setTargetId(targetId);
    log.setSeverity(severity);
    log.setPayload(payload);
    auditLogRepository.save(log);
  }

  private record Context(UUID orgId, ExamTask task, ScheduleRun run) {
  }
}
