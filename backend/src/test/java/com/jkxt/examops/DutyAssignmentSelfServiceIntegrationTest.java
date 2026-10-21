package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.examops.service.DutyAssignmentSelfService;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DutyAssignmentSelfServiceIntegrationTest {

  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired DutyAssignmentSelfService selfService;

  @Test
  void selfServiceIsOwnedPublishedStatefulAuditedAndIdempotent() throws Exception {
    List<UUID> teachers = jdbcTemplate.query("""
        select id from teacher where org_id=? and status='active' order by teacher_no limit 2
        """, (rs, row) -> rs.getObject(1, UUID.class), ORG);
    assertThat(teachers).hasSize(2);
    User first = user("duty.first.", teachers.get(0));
    User second = user("duty.second.", teachers.get(1));
    Cookie firstCookie = login(first.username());

    Instant base = Instant.parse("2055-01-10T08:00:00Z");
    UUID accepted = assignment(teachers.get(0), "PUBLISHED", base);
    UUID rejected = assignment(teachers.get(0), "EXECUTING", base.plusSeconds(10_800));
    UUID pending = assignment(teachers.get(0), "PUBLISHED", base.plusSeconds(21_600));
    UUID hiddenDraft = assignment(teachers.get(0), "DRAFT", base.plusSeconds(32_400));
    UUID otherTeacher = assignment(teachers.get(1), "PUBLISHED", base.plusSeconds(43_200));

    JsonNode visible = objectMapper.readTree(mockMvc.perform(
            get("/api/v1/auth/me/duty-assignments").cookie(firstCookie))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(visible.findValuesAsText("id"))
        .contains(accepted.toString(), rejected.toString(), pending.toString())
        .doesNotContain(hiddenDraft.toString(), otherTeacher.toString());
    assertThat(visible.findValuesAsText("teacher_id")).containsOnly(teachers.get(0).toString());
    assertThat(visible.findValuesAsText("status")).containsOnly("PENDING_CONFIRMATION");

    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/accept", otherTeacher)
            .cookie(firstCookie))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DUTY_ASSIGNMENT_NOT_FOUND"));
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/accept", hiddenDraft)
            .cookie(firstCookie))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", rejected)
            .cookie(firstCookie).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/accept", accepted).cookie(firstCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.replayed").value(false));
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/accept", accepted).cookie(firstCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", accepted)
            .cookie(firstCookie).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed mind\"}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUTY_STATE_INVALID"));
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/check-in", accepted).cookie(firstCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN"));
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/check-in", accepted).cookie(firstCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", rejected)
            .cookie(firstCookie).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"medical leave\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.substitutionCaseId").isNotEmpty());
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", rejected)
            .cookie(firstCookie).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"medical leave\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
    mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/check-in", pending).cookie(firstCookie))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUTY_STATE_INVALID"));

    assertThat(count("exam_plan_audit_event", accepted, "DUTY_ACCEPTED")).isEqualTo(1);
    assertThat(count("exam_plan_audit_event", accepted, "DUTY_CHECKED_IN")).isEqualTo(1);
    assertThat(count("exam_plan_audit_event", rejected, "DUTY_REJECTED")).isEqualTo(1);
    assertThat(count("exam_plan_outbox", accepted, "DUTY_ACCEPTED")).isEqualTo(1);
    assertThat(count("exam_plan_outbox", accepted, "DUTY_CHECKED_IN")).isEqualTo(1);
    assertThat(count("exam_plan_outbox", rejected, "DUTY_REJECTED")).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_substitution_case where org_id=? and duty_assignment_id=?
        """, Integer.class, ORG, rejected)).isEqualTo(1);
  }

  /**
   * PB-17 (round 9): the backend decides the check-in window. With the production defaults (opens 60
   * minutes before start, closes at the end) a duty far in the future or already over is refused with
   * 409 and nothing is written; a duty starting in 30 minutes can be checked in.
   */
  @Test
  void checkInIsRefusedOutsideTheBackendWindowWithProductionDefaults() throws Exception {
    Object target = AopTestUtils.getTargetObject(selfService);
    Object opens = ReflectionTestUtils.getField(target, "checkInOpensBefore");
    Object closes = ReflectionTestUtils.getField(target, "checkInClosesAfterEnd");
    ReflectionTestUtils.setField(target, "checkInOpensBefore", Duration.ofMinutes(60));
    ReflectionTestUtils.setField(target, "checkInClosesAfterEnd", Duration.ZERO);
    try {
      UUID teacher = jdbcTemplate.queryForObject("""
          select id from teacher where org_id=? and status='active' order by teacher_no limit 1
          """, UUID.class, ORG);
      Cookie cookie = login(user("duty.window.", teacher).username());
      Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      UUID future = assignment(teacher, "PUBLISHED", Instant.parse("2056-03-01T08:00:00Z"));
      UUID past = assignment(teacher, "PUBLISHED", now.minusSeconds(4 * 3_600));
      UUID soon = assignment(teacher, "PUBLISHED", now.plusSeconds(30 * 60));
      for (UUID id : List.of(future, past, soon)) {
        mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/accept", id).cookie(cookie))
            .andExpect(status().isOk());
      }
      for (UUID id : List.of(future, past)) {
        mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/check-in", id).cookie(cookie))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUTY_CHECK_IN_WINDOW_CLOSED"));
        assertThat(count("exam_plan_audit_event", id, "DUTY_CHECKED_IN")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            select status from exam_plan_duty_response where org_id=? and assignment_id=?
            """, String.class, ORG, id)).isEqualTo("ACCEPTED");
      }
      mockMvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/check-in", soon).cookie(cookie))
          .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN"));
    } finally {
      ReflectionTestUtils.setField(target, "checkInOpensBefore", opens);
      ReflectionTestUtils.setField(target, "checkInClosesAfterEnd", closes);
    }
  }

  private int count(String table, UUID assignment, String event) {
    if (table.equals("exam_plan_outbox")) {
      return jdbcTemplate.queryForObject("""
          select count(*) from exam_plan_outbox
          where org_id=? and aggregate_id=? and event_type=?
          """, Integer.class, ORG, assignment, event);
    }
    return jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_audit_event
        where org_id=? and after_state->>'assignmentId'=? and action=?
        """, Integer.class, ORG, assignment.toString(), event);
  }

  private User user(String prefix, UUID teacherId) {
    UUID id = UUID.randomUUID();
    String username = prefix + id.toString().substring(0, 8);
    jdbcTemplate.update("""
        insert into app_user (id, org_id, username, display_name, email, password_hash, role,
          status, must_change_password, onboarded)
        select ?, org_id, ?, 'Duty teacher', ?, password_hash, 'TEACHER_QUERY',
          'active', false, true from app_user where org_id=? and username='jwc.demo'
        """, id, username, username + "@example.test", ORG);
    jdbcTemplate.update("""
        insert into user_role (user_id, role_id, org_id)
        select ?, id, org_id from role where org_id=? and code='TEACHER_QUERY'
        """, id, ORG);
    jdbcTemplate.update("update teacher set app_user_id=? where org_id=? and id=?", id, ORG, teacherId);
    return new User(id, username);
  }

  private UUID assignment(UUID teacherId, String revisionStatus, Instant start) {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID();
    UUID requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan (id, org_id, plan_no, name, status)
        values (?, ?, ?, 'Duty self service fixture', 'DRAFT')
        """, plan, ORG, "DUTY-" + plan.toString().substring(0, 10));
    jdbcTemplate.update("""
        insert into exam_plan_revision (id, org_id, plan_id, revision_no, status, source_type)
        values (?, ?, ?, 1, 'DRAFT', 'API')
        """, revision, ORG, plan);
    jdbcTemplate.update("""
        insert into exam_plan_session (id, org_id, revision_id, session_code, name,
          starts_at, ends_at, candidate_count)
        values (?, ?, ?, ?, 'Duty session', ?, ?, 0)
        """, session, ORG, revision, "S-" + session.toString().substring(0, 8),
        Timestamp.from(start), Timestamp.from(start.plusSeconds(7_200)));
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement
          (id, org_id, revision_id, session_id, duty_role, required_count)
        values (?, ?, ?, ?, 'INVIGILATOR', 1)
        """, requirement, ORG, revision, session);
    jdbcTemplate.update("""
        insert into exam_plan_duty_assignment
          (id, org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at, duty_role)
        values (?, ?, ?, ?, ?, ?, ?, 'INVIGILATOR')
        """, assignment, ORG, revision, requirement, teacherId,
        Timestamp.from(start), Timestamp.from(start.plusSeconds(7_200)));
    jdbcTemplate.update("update exam_plan_revision set status=?, published_at=case when ?='PUBLISHED' then now() end where id=?",
        revisionStatus, revisionStatus, revision);
    jdbcTemplate.update("update exam_plan set status=?, active_revision_id=? where id=?",
        revisionStatus, revision, plan);
    return assignment;
  }

  private Cookie login(String username) throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), CaptchaResponse.class);
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","password":"Demo@2025","captchaId":"%s","captcha":"%s"}
                """.formatted(username, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk()).andReturn();
    return result.getResponse().getCookie("JKXT_SCHOOL_SESSION");
  }

  private record User(UUID id, String username) {
  }
}
