package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.notify.repo.NotificationRepository;
import com.jkxt.scheduling.repo.ScheduleAssignmentRepository;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.tasks.repo.ExamTaskRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleEditApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private ScheduleAssignmentRepository assignmentRepository;

  @Autowired
  private TeacherRepository teacherRepository;

  @Autowired
  private ExamTaskRepository taskRepository;

  @Autowired
  private NotificationRepository notificationRepository;

  @Autowired
  private AuditLogRepository auditLogRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void republishIsIdempotentDoesNotDuplicateNotificationOrChangePublishedAt() throws Exception {
    Cookie session = login();
    JsonNode done = startFeasibleRun(session);
    UUID taskId = UUID.fromString(done.get("taskId").asText());

    // First publish: writes exactly one notification and stamps published_at.
    JsonNode firstPublish = json(mockMvc.perform(post("/api/v1/tasks/{taskId}/publish", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("published"))
        .andExpect(jsonPath("$.notificationStatus").value("sent"))
        .andExpect(jsonPath("$.notificationCount").value(1))
        .andExpect(jsonPath("$.alreadyPublished").value(false))
        .andReturn());
    UUID firstNotificationId = UUID.fromString(firstPublish.get("notificationId").asText());
    int recipientCount = firstPublish.get("recipientCount").asInt();
    assertThat(recipientCount).isGreaterThan(0);

    long notificationRowsAfterFirst = countNotifications(taskId);
    Timestamp publishedAtAfterFirst = publishedAt(taskId);
    assertThat(notificationRowsAfterFirst).isEqualTo(1L);
    assertThat(publishedAtAfterFirst).isNotNull();

    // Second publish (double-click): idempotent no-op.
    JsonNode secondPublish = json(mockMvc.perform(post("/api/v1/tasks/{taskId}/publish", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("published"))
        .andExpect(jsonPath("$.notificationCount").value(0))
        .andExpect(jsonPath("$.alreadyPublished").value(true))
        .andReturn());

    // No second notification row inserted, and the existing notification is echoed back.
    assertThat(countNotifications(taskId)).isEqualTo(notificationRowsAfterFirst).isEqualTo(1L);
    assertThat(UUID.fromString(secondPublish.get("notificationId").asText())).isEqualTo(firstNotificationId);
    // recipientCount (distinct teacher count) is still reported on the no-op re-publish.
    assertThat(secondPublish.get("recipientCount").asInt()).isEqualTo(recipientCount);

    // published_at unchanged by the re-publish.
    assertThat(publishedAt(taskId)).isEqualTo(publishedAtAfterFirst);
  }

  private long countNotifications(UUID taskId) {
    Long count = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM notification WHERE exam_task_id = ? AND type = 'schedule_published'",
        Long.class,
        taskId);
    return count == null ? 0L : count;
  }

  private Timestamp publishedAt(UUID taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT published_at FROM exam_task WHERE id = ?",
        Timestamp.class,
        taskId);
  }

  @Test
  void dragReassignmentChecksConflictsSupportsUndoRedoAndPublishesNotification() throws Exception {
    Cookie session = login();
    JsonNode done = startFeasibleRun(session);
    UUID taskId = UUID.fromString(done.get("taskId").asText());

    JsonNode rows = assignments(taskId, session).get("rows");
    JsonNode base = rows.get(0);
    JsonNode sameSlotOther = sameSlotOther(rows, base);
    UUID assignmentId = UUID.fromString(base.get("assignmentId").asText());
    UUID originalTeacherId = UUID.fromString(base.get("teacherId").asText());
    UUID targetTeacherId = createTeacher(session);

    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/check-conflicts", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s"
                }
                """.formatted(assignmentId, targetTeacherId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.count").value(0));

    mockMvc.perform(patch("/api/v1/tasks/{taskId}/schedule", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s",
                  "expectedRevision": 0,
                  "reason": "integration-test-drag"
                }
                """.formatted(assignmentId, targetTeacherId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1))
        .andExpect(jsonPath("$.teacherId").value(targetTeacherId.toString()));

    assertThat(assignmentRepository.findById(assignmentId)).isPresent()
        .get()
        .extracting(assignment -> assignment.getTeacherId())
        .isEqualTo(targetTeacherId);

    UUID conflictingAssignmentId = UUID.fromString(sameSlotOther.get("assignmentId").asText());
    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/check-conflicts", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s"
                }
                """.formatted(conflictingAssignmentId, targetTeacherId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.conflicts[0].code").value("H1_TEACHER_TIME_OVERLAP"));

    mockMvc.perform(patch("/api/v1/tasks/{taskId}/schedule", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s",
                  "expectedRevision": 1
                }
                """.formatted(conflictingAssignmentId, targetTeacherId)))
        .andExpect(status().isConflict());

    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/undo", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("undo"))
        .andExpect(jsonPath("$.revisionCursor").value(0));
    assertThat(assignmentRepository.findById(assignmentId)).isPresent()
        .get()
        .extracting(assignment -> assignment.getTeacherId())
        .isEqualTo(originalTeacherId);

    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/redo", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("redo"))
        .andExpect(jsonPath("$.revisionCursor").value(1));
    assertThat(assignmentRepository.findById(assignmentId)).isPresent()
        .get()
        .extracting(assignment -> assignment.getTeacherId())
        .isEqualTo(targetTeacherId);

    JsonNode published = json(mockMvc.perform(post("/api/v1/tasks/{taskId}/publish", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("published"))
        .andExpect(jsonPath("$.notificationStatus").value("sent"))
        .andReturn());

    assertThat(taskRepository.findById(taskId)).isPresent()
        .get()
        .extracting(task -> task.getStatus())
        .isEqualTo("published");
    assertThat(notificationRepository.findByOrgIdAndExamTaskId(
        taskRepository.findById(taskId).orElseThrow().getOrgId(),
        taskId
    )).anySatisfy(notification -> {
      assertThat(notification.getId()).isEqualTo(UUID.fromString(published.get("notificationId").asText()));
      assertThat(notification.getStatus()).isEqualTo("sent");
    });
    assertThat(auditLogRepository.findByOrgId(taskRepository.findById(taskId).orElseThrow().getOrgId()))
        .anySatisfy(log -> assertThat(log.getAction()).isEqualTo("SCHEDULE_REASSIGN"))
        .anySatisfy(log -> assertThat(log.getAction()).isEqualTo("SCHEDULE_PUBLISH"));
  }

  @Test
  void manualConflictCheckAndPatchEnforceTeacherStatusLeaveDutyAndHomeroomEligibility() throws Exception {
    Cookie session = login();
    JsonNode done = startFeasibleRun(session);
    UUID taskId = UUID.fromString(done.get("taskId").asText());
    JsonNode base = assignments(taskId, session).get("rows").get(0);
    UUID assignmentId = UUID.fromString(base.get("assignmentId").asText());
    LocalDateTime examStart = examStart(base);
    LocalDateTime examEnd = examEnd(base);

    UUID disabled = createTeacher(session);
    configureTeacher(disabled, "disabled", List.of(), List.of());
    assertManualTeacherRejected(taskId, assignmentId, disabled, 0,
        "H2_TEACHER_UNAVAILABLE", session);

    UUID onLeave = createTeacher(session);
    configureTeacher(onLeave, "active",
        List.of(window(examStart.minusMinutes(15), examStart.plusMinutes(15))),
        List.of());
    assertManualTeacherRejected(taskId, assignmentId, onLeave, 0,
        "H2_TEACHER_LEAVE_WINDOW", session);

    UUID outsideDuty = createTeacher(session);
    configureTeacher(outsideDuty, "active", List.of(),
        List.of(window(examStart.minusMinutes(15), examEnd.minusMinutes(1))));
    assertManualTeacherRejected(taskId, assignmentId, outsideDuty, 0,
        "H2_TEACHER_DUTY_WINDOW", session);

    UUID homeroom = createTeacher(session);
    UUID examClassId = ensureExamClass(UUID.fromString(base.get("examId").asText()));
    Teacher homeroomTeacher = teacherRepository.findById(homeroom).orElseThrow();
    homeroomTeacher.setHomeroom(true);
    homeroomTeacher.setHomeroomClassId(examClassId);
    teacherRepository.saveAndFlush(homeroomTeacher);
    assertManualTeacherRejected(taskId, assignmentId, homeroom, 0,
        "H3_HOMEROOM_AVOID", session);

    // A leave window ending exactly when the exam starts does not overlap. An exact duty-window
    // match is valid because the complete exam interval is contained in one declared window.
    UUID boundaryEligible = createTeacher(session);
    configureTeacher(boundaryEligible, "active",
        List.of(window(examStart.minusHours(1), examStart)),
        List.of(window(examStart, examEnd)));

    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/check-conflicts", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s"
                }
                """.formatted(assignmentId, boundaryEligible)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(true))
        .andExpect(jsonPath("$.count").value(0));

    mockMvc.perform(patch("/api/v1/tasks/{taskId}/schedule", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s",
                  "expectedRevision": 0
                }
                """.formatted(assignmentId, boundaryEligible)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teacherId").value(boundaryEligible.toString()))
        .andExpect(jsonPath("$.revisionCursor").value(1));
  }

  @Test
  void undoAndRedoRevalidateAllCurrentHardEligibilityRulesBeforeApplyingStoredTeacher() throws Exception {
    Cookie session = login();
    JsonNode done = startFeasibleRun(session);
    UUID taskId = UUID.fromString(done.get("taskId").asText());
    UUID runId = UUID.fromString(done.get("runId").asText());
    JsonNode rows = assignments(taskId, session).get("rows");
    JsonNode base = rows.get(0);
    JsonNode sameSlotOther = sameSlotOther(rows, base);
    UUID assignmentId = UUID.fromString(base.get("assignmentId").asText());
    UUID sameSlotOtherId = UUID.fromString(sameSlotOther.get("assignmentId").asText());
    UUID sameSlotOtherTeacher = UUID.fromString(sameSlotOther.get("teacherId").asText());
    LocalDateTime examStart = examStart(base);
    LocalDateTime examEnd = examEnd(base);

    UUID firstManualTeacher = createTeacher(session);
    UUID secondManualTeacher = createTeacher(session);
    patchTeacher(taskId, assignmentId, firstManualTeacher, 0, session);
    patchTeacher(taskId, assignmentId, secondManualTeacher, 1, session);

    configureTeacher(firstManualTeacher, "disabled", List.of(), List.of());
    assertUndoRejected(taskId, assignmentId, secondManualTeacher, runId, 2, session);

    configureTeacher(firstManualTeacher, "active",
        List.of(window(examStart.minusMinutes(1), examStart.plusMinutes(1))),
        List.of());
    assertUndoRejected(taskId, assignmentId, secondManualTeacher, runId, 2, session);

    configureTeacher(firstManualTeacher, "active", List.of(),
        List.of(window(examStart, examEnd.minusMinutes(1))));
    assertUndoRejected(taskId, assignmentId, secondManualTeacher, runId, 2, session);

    // The same H1 overlap rule used by conflict-check/PATCH is re-evaluated during undo.
    var otherAssignment = assignmentRepository.findById(sameSlotOtherId).orElseThrow();
    otherAssignment.setTeacherId(firstManualTeacher);
    assignmentRepository.saveAndFlush(otherAssignment);
    assertUndoRejected(taskId, assignmentId, secondManualTeacher, runId, 2, session);
    var restoredOtherAssignment = assignmentRepository.findById(sameSlotOtherId).orElseThrow();
    restoredOtherAssignment.setTeacherId(sameSlotOtherTeacher);
    assignmentRepository.saveAndFlush(restoredOtherAssignment);

    configureTeacher(firstManualTeacher, "active",
        List.of(window(examStart.minusHours(1), examStart)),
        List.of(window(examStart, examEnd)));
    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/undo", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionCursor").value(1))
        .andExpect(jsonPath("$.assignment.teacherId").value(firstManualTeacher.toString()));

    configureTeacher(secondManualTeacher, "disabled", List.of(), List.of());
    assertRedoRejected(taskId, assignmentId, firstManualTeacher, runId, 1, session);

    configureTeacher(secondManualTeacher, "active", List.of(),
        List.of(window(examStart.plusMinutes(1), examEnd.plusMinutes(1))));
    assertRedoRejected(taskId, assignmentId, firstManualTeacher, runId, 1, session);

    configureTeacher(secondManualTeacher, "active",
        List.of(window(examStart, examEnd)),
        List.of(window(examStart, examEnd)));
    assertRedoRejected(taskId, assignmentId, firstManualTeacher, runId, 1, session);

    UUID examClassId = ensureExamClass(UUID.fromString(base.get("examId").asText()));
    Teacher target = teacherRepository.findById(secondManualTeacher).orElseThrow();
    target.setLeaveWindows(List.of());
    target.setDutyWindows(List.of(window(examStart, examEnd)));
    target.setHomeroom(true);
    target.setHomeroomClassId(examClassId);
    teacherRepository.saveAndFlush(target);
    assertRedoRejected(taskId, assignmentId, firstManualTeacher, runId, 1, session);

    Teacher restoredTarget = teacherRepository.findById(secondManualTeacher).orElseThrow();
    restoredTarget.setHomeroom(false);
    restoredTarget.setHomeroomClassId(null);
    restoredTarget.setLeaveWindows(List.of(window(examEnd, examEnd.plusHours(1))));
    teacherRepository.saveAndFlush(restoredTarget);

    // The same H1 overlap rule is also re-evaluated during redo.
    var redoOverlapAssignment = assignmentRepository.findById(sameSlotOtherId).orElseThrow();
    redoOverlapAssignment.setTeacherId(secondManualTeacher);
    assignmentRepository.saveAndFlush(redoOverlapAssignment);
    assertRedoRejected(taskId, assignmentId, firstManualTeacher, runId, 1, session);
    var redoRestoredAssignment = assignmentRepository.findById(sameSlotOtherId).orElseThrow();
    redoRestoredAssignment.setTeacherId(sameSlotOtherTeacher);
    assignmentRepository.saveAndFlush(redoRestoredAssignment);

    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/redo", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionCursor").value(2))
        .andExpect(jsonPath("$.assignment.teacherId").value(secondManualTeacher.toString()));
  }

  private void assertManualTeacherRejected(
      UUID taskId,
      UUID assignmentId,
      UUID teacherId,
      int expectedRevision,
      String expectedConflictCode,
      Cookie session
  ) throws Exception {
    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/check-conflicts", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s"
                }
                """.formatted(assignmentId, teacherId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed").value(false))
        .andExpect(jsonPath("$.conflicts[0].code").value(expectedConflictCode));

    mockMvc.perform(patch("/api/v1/tasks/{taskId}/schedule", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s",
                  "expectedRevision": %d
                }
                """.formatted(assignmentId, teacherId, expectedRevision)))
        .andExpect(status().isConflict());
  }

  private void patchTeacher(
      UUID taskId,
      UUID assignmentId,
      UUID teacherId,
      int expectedRevision,
      Cookie session
  ) throws Exception {
    mockMvc.perform(patch("/api/v1/tasks/{taskId}/schedule", taskId)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "assignmentId": "%s",
                  "teacherId": "%s",
                  "expectedRevision": %d
                }
                """.formatted(assignmentId, teacherId, expectedRevision)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teacherId").value(teacherId.toString()))
        .andExpect(jsonPath("$.revisionCursor").value(expectedRevision + 1));
  }

  private void assertUndoRejected(
      UUID taskId,
      UUID assignmentId,
      UUID currentTeacherId,
      UUID runId,
      int expectedCursor,
      Cookie session
  ) throws Exception {
    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/undo", taskId).cookie(session))
        .andExpect(status().isConflict());
    assertAssignmentTeacher(assignmentId, currentTeacherId);
    assertRevisionCursor(runId, expectedCursor, session);
  }

  private void assertRedoRejected(
      UUID taskId,
      UUID assignmentId,
      UUID currentTeacherId,
      UUID runId,
      int expectedCursor,
      Cookie session
  ) throws Exception {
    mockMvc.perform(post("/api/v1/tasks/{taskId}/schedule/redo", taskId).cookie(session))
        .andExpect(status().isConflict());
    assertAssignmentTeacher(assignmentId, currentTeacherId);
    assertRevisionCursor(runId, expectedCursor, session);
  }

  private void assertAssignmentTeacher(UUID assignmentId, UUID teacherId) {
    assertThat(assignmentRepository.findById(assignmentId)).isPresent()
        .get()
        .extracting(assignment -> assignment.getTeacherId())
        .isEqualTo(teacherId);
  }

  private void assertRevisionCursor(UUID runId, int expectedCursor, Cookie session) throws Exception {
    mockMvc.perform(get("/api/v1/scheduling/runs/{runId}", runId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.revisionCursor").value(expectedCursor));
  }

  private void configureTeacher(
      UUID teacherId,
      String status,
      List<Map<String, Object>> leaveWindows,
      List<Map<String, Object>> dutyWindows
  ) {
    Teacher teacher = teacherRepository.findById(teacherId).orElseThrow();
    teacher.setStatus(status);
    teacher.setLeaveWindows(leaveWindows);
    teacher.setDutyWindows(dutyWindows);
    teacherRepository.saveAndFlush(teacher);
  }

  private Map<String, Object> window(LocalDateTime start, LocalDateTime end) {
    return Map.of("start", start.toString(), "end", end.toString());
  }

  private UUID ensureExamClass(UUID examId) {
    UUID existing = jdbcTemplate.query(
        "select class_id from exam_class where exam_id = ? order by class_id limit 1",
        rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
        examId);
    if (existing != null) {
      return existing;
    }
    UUID orgId = jdbcTemplate.queryForObject(
        "select org_id from exam where id = ?", UUID.class, examId);
    UUID classId = jdbcTemplate.queryForObject(
        "select id from school_class where org_id = ? order by id limit 1", UUID.class, orgId);
    jdbcTemplate.update(
        "insert into exam_class (exam_id, class_id, org_id) values (?, ?, ?) on conflict do nothing",
        examId, classId, orgId);
    return classId;
  }

  private LocalDateTime examStart(JsonNode assignment) {
    return LocalDate.parse(assignment.get("examDate").asText())
        .atTime(LocalTime.parse(assignment.get("startTime").asText()));
  }

  private LocalDateTime examEnd(JsonNode assignment) {
    return LocalDate.parse(assignment.get("examDate").asText())
        .atTime(LocalTime.parse(assignment.get("endTime").asText()));
  }

  private JsonNode sameSlotOther(JsonNode rows, JsonNode base) {
    String date = base.get("examDate").asText();
    String start = base.get("startTime").asText();
    String end = base.get("endTime").asText();
    String assignmentId = base.get("assignmentId").asText();
    for (JsonNode row : rows) {
      if (!assignmentId.equals(row.get("assignmentId").asText())
          && date.equals(row.get("examDate").asText())
          && start.equals(row.get("startTime").asText())
          && end.equals(row.get("endTime").asText())) {
        return row;
      }
    }
    throw new AssertionError("Expected another assignment in the same time slot");
  }

  private UUID createTeacher(Cookie session) throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    JsonNode teacher = json(mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "T-P9-%s",
                  "name": "P9 Candidate %s",
                  "gender": "unknown",
                  "grade": "G10",
                  "status": "active",
                  "isHomeroom": false
                }
                """.formatted(suffix, suffix)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uuid").exists())
        .andReturn());
    return UUID.fromString(teacher.get("uuid").asText());
  }

  private JsonNode assignments(UUID taskId, Cookie session) throws Exception {
    return json(mockMvc.perform(get("/api/v1/exam-tasks/{taskId}/assignments", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].assignmentId").exists())
        .andReturn());
  }

  private JsonNode startFeasibleRun(Cookie session) throws Exception {
    JsonNode started = json(mockMvc.perform(post("/api/v1/scheduling/fixtures/feasible/runs").cookie(session))
        .andExpect(status().isOk())
        .andReturn());
    UUID runId = UUID.fromString(started.get("runId").asText());
    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    JsonNode latest = null;
    while (System.nanoTime() < deadlineNanos) {
      latest = json(mockMvc.perform(get("/api/v1/scheduling/runs/{runId}", runId).cookie(session))
          .andExpect(status().isOk())
          .andReturn());
      String status = latest.get("status").asText();
      if ("succeeded".equals(status) || "failed".equals(status) || "infeasible".equals(status)) {
        assertThat(status).isEqualTo("succeeded");
        return latest;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Schedule run did not finish in time. Last snapshot: " + latest);
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "jwc.demo",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    Cookie session = login.getResponse().getCookie("JKXT_SESSION");
    assertThat(session).isNotNull();
    return session;
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
