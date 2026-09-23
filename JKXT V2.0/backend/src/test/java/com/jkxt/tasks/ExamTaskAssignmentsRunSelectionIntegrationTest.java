package com.jkxt.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExamTaskAssignmentsRunSelectionIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void selectsLatestSuccessfulCompletionAndSupportsExactRunWithoutCrossTaskLeakage() throws Exception {
    Cookie session = login();
    UUID taskId = createTask(session, "clock-regression-" + UUID.randomUUID());
    UUID otherTaskId = createTask(session, "run-isolation-" + UUID.randomUUID());
    UUID orgId = jdbcTemplate.queryForObject(
        "select org_id from app_user where username = 'jwc.demo' order by created_at limit 1",
        UUID.class);
    assertThat(orgId).isNotNull();

    Instant base = Instant.parse("2026-08-30T17:49:30Z");
    UUID staleByCreationClock = UUID.randomUUID();
    UUID latestCompleted = UUID.randomUUID();
    UUID otherTaskRun = UUID.randomUUID();

    // Reproduce the production failure: the database/JVM clock is corrected backwards between
    // rapid runs. The older result therefore has a later created_at, while the newer result has
    // the later (and authoritative) successful completion time.
    insertSucceededRun(staleByCreationClock, orgId, taskId, base.plusSeconds(70), base.plusSeconds(5));
    insertSucceededRun(latestCompleted, orgId, taskId, base.plusSeconds(10), base.plusSeconds(15));
    insertSucceededRun(otherTaskRun, orgId, otherTaskId, base.plusSeconds(20), base.plusSeconds(25));

    mockMvc.perform(get("/api/v1/exam-tasks/{taskId}/assignments", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value(latestCompleted.toString()))
        .andExpect(jsonPath("$.status").value("succeeded"));

    // A client polling the runId returned by POST can request that exact immutable result instead
    // of depending on any wall-clock ordering heuristic.
    mockMvc.perform(get("/api/v1/exam-tasks/{taskId}/assignments", taskId)
            .queryParam("runId", staleByCreationClock.toString())
            .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value(staleByCreationClock.toString()));

    // The exact-run selector must still bind the run to both the current tenant and task.
    mockMvc.perform(get("/api/v1/exam-tasks/{taskId}/assignments", taskId)
            .queryParam("runId", otherTaskRun.toString())
            .cookie(session))
        .andExpect(status().isNotFound());
  }

  private UUID createTask(Cookie session, String name) throws Exception {
    JsonNode response = json(mockMvc.perform(post("/api/v1/exam-tasks")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "%s",
                  "source": "quick"
                }
                """.formatted(name)))
        .andExpect(status().isOk())
        .andReturn());
    return UUID.fromString(response.path("id").asText());
  }

  private void insertSucceededRun(
      UUID runId,
      UUID orgId,
      UUID taskId,
      Instant createdAt,
      Instant finishedAt
  ) {
    jdbcTemplate.update("""
        insert into schedule_run (
          id, org_id, exam_task_id, status, stage, progress, solver,
          stats, logs, started_at, finished_at, created_at, updated_at
        ) values (?, ?, ?, 'succeeded', 'succeeded', 100, 'ortools-cpsat',
                  '{}'::jsonb, '[]'::jsonb, ?, ?, ?, ?)
        """,
        runId,
        orgId,
        taskId,
        Timestamp.from(createdAt),
        Timestamp.from(finishedAt),
        Timestamp.from(createdAt),
        Timestamp.from(finishedAt));
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
