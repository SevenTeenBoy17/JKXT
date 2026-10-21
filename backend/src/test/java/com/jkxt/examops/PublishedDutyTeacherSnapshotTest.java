package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * PB-10 (round 9, DATA-03): renaming a teacher must not rewrite a published duty roster. V36 captures
 * the teacher as published; a draft revision still follows the live master data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublishedDutyTeacherSnapshotTest {

  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void renamingATeacherDoesNotRewriteThePublishedDutyRoster() throws Exception {
    UUID teacher = jdbcTemplate.queryForObject(
        "select id from teacher where org_id=? and status='active' order by teacher_no limit 1", UUID.class, ORG);
    String original = jdbcTemplate.queryForObject("select name from teacher where id=?", String.class, teacher);
    String teacherNo = jdbcTemplate.queryForObject("select teacher_no from teacher where id=?", String.class, teacher);
    Instant base = Instant.parse("2057-05-10T08:00:00Z");
    Fixture published = fixture(teacher, base);
    Fixture draft = fixture(teacher, base.plusSeconds(10_800));
    jdbcTemplate.update("update exam_plan_revision set status='PUBLISHED', published_at=now() where id=?", published.revision());
    jdbcTemplate.update("update exam_plan set status='PUBLISHED', active_revision_id=? where id=?",
        published.revision(), published.plan());

    jdbcTemplate.update("update teacher set name=?, teacher_no=? where id=?", original + " 改名", teacherNo + "-R", teacher);
    Cookie admin = login();

    JsonNode duty = duty(admin, published);
    assertThat(duty.get("teacher_name").asText()).isEqualTo(original);
    assertThat(duty.get("teacher_no").asText()).isEqualTo(teacherNo);
    assertThat(duty.get("current_teacher_name").asText()).isEqualTo(original);
    assertThat(duty.get("current_teacher_no").asText()).isEqualTo(teacherNo);

    JsonNode draftDuty = duty(admin, draft);
    assertThat(draftDuty.get("teacher_name").asText()).isEqualTo(original + " 改名");

    assertThatThrownBy(() -> jdbcTemplate.update(
        "update exam_plan_teacher_snapshot set teacher_name='x' where revision_id=?", published.revision()))
        .hasMessageContaining("immutable");
  }

  private JsonNode duty(Cookie admin, Fixture fixture) throws Exception {
    JsonNode rows = objectMapper.readTree(mockMvc.perform(
            get("/api/v1/plan-revisions/{id}/duties", fixture.revision()).cookie(admin))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    for (JsonNode row : rows) {
      if (fixture.assignment().toString().equals(row.get("id").asText())) return row;
    }
    throw new AssertionError("duty not listed: " + rows);
  }

  private Fixture fixture(UUID teacherId, Instant start) {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID();
    UUID requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan (id, org_id, plan_no, name, status)
        values (?, ?, ?, 'Snapshot fixture', 'DRAFT')
        """, plan, ORG, "SNAP-" + plan.toString().substring(0, 10));
    jdbcTemplate.update("""
        insert into exam_plan_revision (id, org_id, plan_id, revision_no, status, source_type)
        values (?, ?, ?, 1, 'DRAFT', 'API')
        """, revision, ORG, plan);
    jdbcTemplate.update("""
        insert into exam_plan_session (id, org_id, revision_id, session_code, name,
          starts_at, ends_at, candidate_count)
        values (?, ?, ?, ?, 'Snapshot session', ?, ?, 0)
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
    jdbcTemplate.update("update exam_plan set active_revision_id=? where id=?", revision, plan);
    return new Fixture(plan, revision, assignment);
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), CaptchaResponse.class);
    return mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"jwc.demo","password":"Demo@2025","captchaId":"%s","captcha":"%s"}
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk()).andReturn().getResponse().getCookie("JKXT_SCHOOL_SESSION");
  }

  private record Fixture(UUID plan, UUID revision, UUID assignment) {
  }
}
