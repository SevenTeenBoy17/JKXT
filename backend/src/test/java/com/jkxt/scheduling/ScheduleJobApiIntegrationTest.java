package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleJobApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void fixtureRunMovesFromPendingToSucceededAndPersistsAssignments() throws Exception {
    Cookie session = login();
    JsonNode started = json(mockMvc.perform(post("/api/v1/scheduling/fixtures/feasible/runs").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").exists())
        .andExpect(jsonPath("$.eventsPath").exists())
        .andReturn());

    UUID runId = UUID.fromString(started.get("runId").asText());
    JsonNode done = waitForTerminal(runId, session);

    assertThat(done.get("status").asText()).isEqualTo("succeeded");
    assertThat(done.get("assignments")).hasSize(6);
    assertThat(done.get("fairness")).hasSize(5);
    assertThat(done.get("stats").get("solver").asText()).isEqualTo("ortools-cpsat");
  }

  @Test
  void infeasibleFixtureRunPersistsConflictSet() throws Exception {
    Cookie session = login();
    JsonNode started = json(mockMvc.perform(post("/api/v1/scheduling/fixtures/teacher-shortage/runs").cookie(session))
        .andExpect(status().isOk())
        .andReturn());

    UUID runId = UUID.fromString(started.get("runId").asText());
    JsonNode done = waitForTerminal(runId, session);

    assertThat(done.get("status").asText()).isEqualTo("infeasible");
    assertThat(done.get("errorCode").asText()).isEqualTo("SCHEDULE_INFEASIBLE");
    assertThat(done.get("assignments")).isEmpty();
    assertThat(done.get("conflictSet")).hasSize(1);
    assertThat(done.get("conflictSet").get(0).get("message").asText()).contains("可用教师不足");
  }

  @Test
  void sseProgressEndpointStartsAsyncStream() throws Exception {
    Cookie session = login();
    JsonNode started = json(mockMvc.perform(post("/api/v1/scheduling/fixtures/feasible/runs").cookie(session))
        .andExpect(status().isOk())
        .andReturn());
    UUID runId = UUID.fromString(started.get("runId").asText());

    mockMvc.perform(get("/api/v1/scheduling/runs/{runId}/events", runId)
            .cookie(session)
            .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted());
  }

  @Test
  void synchronousSolveEndpointReturnsCapacityConflict() throws Exception {
    Cookie session = login();

    mockMvc.perform(post("/api/v1/scheduling/fixtures/capacity-conflict/solve").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("infeasible"))
        .andExpect(jsonPath("$.errorCode").value("SCHEDULE_INFEASIBLE"))
        .andExpect(jsonPath("$.conflictSet[0].constraint").value("H7"));
  }

  private JsonNode waitForTerminal(UUID runId, Cookie session) throws Exception {
    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    JsonNode latest = null;
    while (System.nanoTime() < deadlineNanos) {
      latest = json(mockMvc.perform(get("/api/v1/scheduling/runs/{runId}", runId).cookie(session))
          .andExpect(status().isOk())
          .andReturn());
      String status = latest.get("status").asText();
      if ("succeeded".equals(status) || "failed".equals(status) || "infeasible".equals(status)) {
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
    Cookie session = login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
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
