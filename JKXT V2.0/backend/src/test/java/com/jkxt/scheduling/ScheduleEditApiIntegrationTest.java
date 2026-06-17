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
import com.jkxt.notify.repo.NotificationRepository;
import com.jkxt.scheduling.repo.ScheduleAssignmentRepository;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.tasks.repo.ExamTaskRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
  private ExamTaskRepository taskRepository;

  @Autowired
  private NotificationRepository notificationRepository;

  @Autowired
  private AuditLogRepository auditLogRepository;

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
