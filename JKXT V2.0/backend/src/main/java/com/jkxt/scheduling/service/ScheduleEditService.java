package com.jkxt.scheduling.service;

import com.jkxt.audit.domain.AuditLog;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.notify.channel.NotificationRecipient;
import com.jkxt.notify.domain.Notification;
import com.jkxt.notify.repo.NotificationRepository;
import com.jkxt.notify.service.NotificationDispatcher;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  private final NotificationDispatcher notificationDispatcher;
  private final AuditLogRepository auditLogRepository;
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  public ScheduleEditService(
      ExamTaskRepository taskRepository,
      ExamRepository examRepository,
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      ScheduleRunRepository runRepository,
      ScheduleAssignmentRepository assignmentRepository,
      ScheduleRevisionRepository revisionRepository,
      NotificationRepository notificationRepository,
      NotificationDispatcher notificationDispatcher,
      AuditLogRepository auditLogRepository,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.notificationDispatcher = notificationDispatcher;
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
    reopenIfPublished(context.task());

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
    applyState(context, assignment, revision.getBeforeState());
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
    reopenIfPublished(context.task());
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
    applyState(context, assignment, revision.getAfterState());
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
    reopenIfPublished(context.task());
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

    int teacherCount = (int) assignments.stream().map(ScheduleAssignment::getTeacherId).distinct().count();
    // 迭代12 P2: 在发布事务内对任务行取悲观写锁,串行化并发发布——否则两个并发发布各读到"未发布"、双双通过
    // 下方幂等判断,给每位监考重复下发通知。加锁后第二个发布阻塞至第一个提交,再读到 published 走幂等分支。
    ExamTask task = taskRepository.findByOrgIdAndIdForUpdate(context.orgId(), taskId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));

    // P2-7 + 迭代12 P2: publish is idempotent AND serialized. A re-publish (sequential or concurrent) of an
    // already-published task must NOT overwrite publishedAt and must NOT insert another notification.
    if ("published".equals(task.getStatus()) && task.getPublishedAt() != null) {
      Notification existing = latestPublishNotification(context.orgId(), task.getId());
      return new PublishResponse(
          task.getId(),
          task.getStatus(),
          task.getPublishedAt(),
          existing == null ? null : existing.getId(),
          existing == null ? "sent" : existing.getStatus(),
          teacherCount,
          0,
          true
      );
    }

    Instant now = Instant.now();
    task.setStatus("published");
    task.setPublishedAt(now);
    taskRepository.save(task);

    // R3: fan the publish out across channels (in-app + email + SMS) to every assigned invigilator.
    // In-app is the notification row below; email/SMS go through the dispatcher (logging-only senders
    // unless a real provider bean is wired). The per-channel result is recorded on the payload.
    List<UUID> teacherIds = assignments.stream()
        .map(ScheduleAssignment::getTeacherId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    List<NotificationRecipient> recipients = (teacherIds.isEmpty()
        ? List.<Teacher>of()
        : teacherRepository.findByOrgIdAndIdIn(context.orgId(), teacherIds)).stream()
        .map(t -> new NotificationRecipient(t.getId(), t.getName(), t.getEmail(), t.getPhone()))
        .toList();
    String taskName = task.getName() == null || task.getName().isBlank() ? "监考排班" : task.getName();
    String subject = taskName + " · 监考排班已发布";
    String body = "监考排班已发布，共 " + teacherCount + " 位监考教师。请在系统中查看明细，并导出监考安排表张贴/下发给监考教师。";
    Map<String, Object> channels = notificationDispatcher.dispatch(recipients, subject, body);

    Notification notification = new Notification();
    notification.setOrgId(context.orgId());
    notification.setExamTaskId(task.getId());
    notification.setType("schedule_published");
    notification.setTitle(subject);
    notification.setBody(body);
    notification.setStatus("sent");
    notification.setTargetUrl("/tasks/" + task.getId());
    notification.setPublishedAt(now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runId", context.run().getId().toString());
    payload.put("assignmentCount", assignments.size());
    payload.put("teacherCount", teacherCount);
    payload.put("channels", channels);
    notification.setPayload(payload);
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
        teacherCount,
        1,
        false
    );
  }

  // Editing a published schedule (PATCH / undo / redo) re-opens the task: the published artifact must
  // not silently diverge from what teachers were notified, so any post-publish mutation resets the
  // status to 'scheduled' and clears publishedAt — forcing an explicit re-publish to re-notify and
  // keeping the lifecycle invariant (published_at != null IFF status='published'). (P1-3/P2-1/P2-2)
  private void reopenIfPublished(ExamTask task) {
    if ("published".equals(task.getStatus())) {
      task.setStatus("scheduled");
      task.setPublishedAt(null);
      taskRepository.save(task);
      audit(task.getOrgId(), "SCHEDULE_REOPENED", "exam_task", task.getId(), "info", Map.of(
          "reason", "edited-after-publish"
      ));
    }
  }

  private Notification latestPublishNotification(UUID orgId, UUID taskId) {
    return notificationRepository.findByOrgIdAndExamTaskId(orgId, taskId).stream()
        .filter(n -> "schedule_published".equals(n.getType()))
        .max(Comparator.comparing(
            Notification::getPublishedAt,
            Comparator.nullsFirst(Comparator.naturalOrder())))
        .orElse(null);
  }

  private Context context(UUID taskId) {
    UUID orgId = TenantContext.getRequired();
    ExamTask task = taskRepository.findByOrgIdAndId(orgId, taskId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found"));
    // 迭代10 P1: 与读取路径(ExamTaskService.latestRun)共用同一"当前生效 run"口径(优先最新 succeeded)，
    // 否则一次失败/无解重排会把已成功的排班永久锁死为不可编辑/不可发布。
    ScheduleRun run = runRepository.findActiveRun(orgId, taskId)
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

    LocalDateTime examStart = assignment.getExamDate().atTime(assignment.getStartTime());
    LocalDateTime examEnd = assignment.getExamDate().atTime(assignment.getEndTime());
    boolean overlapsLeave = teacherWindows(target.getLeaveWindows()).stream()
        .anyMatch(window -> window.start().isBefore(examEnd) && window.end().isAfter(examStart));
    if (overlapsLeave) {
      conflicts.add(new ScheduleConflict(
          "H2_TEACHER_LEAVE_WINDOW",
          "hard",
          "%s has a leave window overlapping this exam.".formatted(target.getName()),
          assignment.getId(),
          target.getId(),
          null,
          assignment.getExamDate(),
          assignment.getStartTime(),
          assignment.getEndTime()
      ));
    }

    List<TeacherTimeWindow> dutyWindows = teacherWindows(target.getDutyWindows());
    boolean outsideDutyWindow = !dutyWindows.isEmpty() && dutyWindows.stream()
        .noneMatch(window -> !window.start().isAfter(examStart) && !window.end().isBefore(examEnd));
    if (outsideDutyWindow) {
      conflicts.add(new ScheduleConflict(
          "H2_TEACHER_DUTY_WINDOW",
          "hard",
          "%s does not have one duty window containing the complete exam.".formatted(target.getName()),
          assignment.getId(),
          target.getId(),
          null,
          assignment.getExamDate(),
          assignment.getStartTime(),
          assignment.getEndTime()
      ));
    }

    // H3 班主任不监本班 — 与求解器 CpSatScheduleSolver.eligible() 同口径，杜绝"一键流强制、人工改派却能绕过"
    // 的路径漂移。目标教师的"所带班级"集合 = 教师表 homeroom_class_id ∪ 班级表 homeroom_teacher_id(两条导入
    // 路径)，与该考试所坐班级(exam_class) 求交，非空即冲突。此前 conflicts() 只查 H1时间重叠+H2不可用，
    // 漏掉班主任维度，导致前端拖拽可把班主任排进其本班所在考场且 allowed=true。
    List<UUID> examClassIds = jdbcTemplate.queryForList(
        "select class_id from exam_class where org_id = ? and exam_id = ?",
        UUID.class, context.orgId(), assignment.getExamId());
    if (!examClassIds.isEmpty()) {
      Set<UUID> homeroomClassIds = new HashSet<>(jdbcTemplate.queryForList(
          "select id from school_class where org_id = ? and homeroom_teacher_id = ?",
          UUID.class, context.orgId(), target.getId()));
      if (target.getHomeroomClassId() != null) {
        homeroomClassIds.add(target.getHomeroomClassId());
      }
      if (!Collections.disjoint(homeroomClassIds, examClassIds)) {
        conflicts.add(new ScheduleConflict(
            "H3_HOMEROOM_AVOID",
            "hard",
            "%s 是该考场所坐班级的班主任，不能监考本班。".formatted(target.getName()),
            assignment.getId(),
            target.getId(),
            null,
            assignment.getExamDate(),
            assignment.getStartTime(),
            assignment.getEndTime()
        ));
      }
    }
    return conflicts;
  }

  private boolean overlaps(LocalTime leftStart, LocalTime leftEnd, LocalTime rightStart, LocalTime rightEnd) {
    return leftStart.isBefore(rightEnd) && leftEnd.isAfter(rightStart);
  }

  private List<TeacherTimeWindow> teacherWindows(List<Map<String, Object>> persistedWindows) {
    if (persistedWindows == null || persistedWindows.isEmpty()) {
      return List.of();
    }
    return persistedWindows.stream()
        .map(window -> {
          LocalDateTime start = LocalDateTime.parse(String.valueOf(window.get("start")));
          LocalDateTime end = LocalDateTime.parse(String.valueOf(window.get("end")));
          if (!start.isBefore(end)) {
            throw new IllegalStateException("Persisted teacher window start must be before end");
          }
          return new TeacherTimeWindow(start, end);
        })
        .toList();
  }

  private Map<String, Object> state(ScheduleAssignment assignment, String teacherName) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("teacherId", assignment.getTeacherId().toString());
    state.put("teacherName", teacherName);
    state.put("source", assignment.getSource());
    return state;
  }

  private void applyState(Context context, ScheduleAssignment assignment, Map<String, Object> state) {
    UUID teacherId = UUID.fromString(String.valueOf(state.get("teacherId")));
    Teacher target = teacher(context.orgId(), teacherId);
    List<ScheduleConflict> conflicts = conflicts(context, assignment, target);
    if (!conflicts.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SCHEDULE_CONFLICT");
    }
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

  private record TeacherTimeWindow(LocalDateTime start, LocalDateTime end) {
  }
}
