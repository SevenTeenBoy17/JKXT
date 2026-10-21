package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/** Additive UI context through actual authentication, controllers, services and isolated PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DutyWorkflowProjectionIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant START = Instant.parse("2043-07-01T00:00:00Z");
  private static final Instant END = START.plusSeconds(7200);
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void selfDutyReturnsRealContextWithoutInventingOptionalRoomOrSubject(boolean withRoom) throws Exception {
    Account teacher = teacher("original");
    Duty duty = duty(teacher, withRoom, "ACTIVE");
    var before = snapshot(duty);
    JsonNode row = find(self(login(teacher)), duty.assignment());
    assertThat(row.path("plan_name").asText()).isEqualTo("界面验收考试计划");
    assertThat(row.path("teacher_name").asText()).isEqualTo(teacher.name());
    assertThat(row.path("session_external_code").asText()).isEqualTo(duty.sessionCode());
    assertThat(row.path("engagementNo").asLong(-1)).isZero();
    assertThat(row.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
    if (withRoom) {
      assertThat(row.path("room_name").asText()).isEqualTo("明德楼 201");
      assertThat(row.path("room_code").asText()).isEqualTo(duty.roomCode());
      assertThat(row.path("subject_name").asText()).isEqualTo("数学");
    } else {
      for (String key : List.of("room_name", "room_code", "subject_name")) {
        assertThat(row.has(key)).as("optional field is explicit, not omitted: %s", key).isTrue();
        assertThat(row.get(key).isNull()).as("no fabricated context: %s", key).isTrue();
      }
    }
    assertThat(snapshot(duty)).as("GET projection creates no business side effects").isEqualTo(before);
  }

  @Test
  void managerSeesHumanContextAndResolutionDoesNotMeanTeacherConfirmation() throws Exception {
    Account original = teacher("original"), replacement = teacher("replacement");
    Duty duty = duty(original, true, "ACTIVE");
    UUID caseId = reject(login(original), duty);
    Cookie manager = manager();
    var before = snapshot(duty);
    JsonNode open = find(cases(manager), caseId);
    assertThat(open.path("planId").asText()).isEqualTo(duty.plan().toString());
    assertThat(open.path("planName").asText()).isEqualTo("界面验收考试计划");
    assertThat(open.path("teacherName").asText()).isEqualTo(original.name());
    assertThat(open.has("replacementTeacherName")).isTrue();
    assertThat(open.get("replacementTeacherName").isNull()).isTrue();
    assertThat(open.path("roomName").asText()).isEqualTo("明德楼 201");
    assertThat(open.path("roomCode").asText()).isEqualTo(duty.roomCode());
    assertThat(open.path("sessionCode").asText()).isEqualTo(duty.sessionCode());
    assertThat(open.path("actionable").asBoolean(false)).isTrue();
    assertThat(snapshot(duty)).isEqualTo(before);

    JsonNode resolved = json(mvc.perform(post("/api/v1/substitution-cases/{id}/resolve", caseId)
        .cookie(manager).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("replacementTeacherId", replacement.teacher(), "reason", "界面验收真实替补"))))
        .andExpect(status().isOk()).andReturn());
    assertThat(resolved.path("status").asText()).isEqualTo("RESOLVED");
    assertThat(resolved.path("engagementNo").asLong(-1)).isZero();
    assertThat(resolved.path("replacementTeacherName").asText()).isEqualTo(replacement.name());
    assertThat(resolved.path("actionable").asBoolean(true)).isFalse();
    JsonNode current = find(self(login(replacement)), duty.assignment());
    assertThat(current.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(current.path("engagementNo").asLong(-1)).isEqualTo(1);
    assertThat(current.path("teacher_name").asText()).isEqualTo(replacement.name());
    assertThat(current.path("room_name").asText()).isEqualTo("明德楼 201");
    assertThat(jdbc.queryForObject("select teacher_id from exam_plan_duty_assignment where id=?", UUID.class, duty.assignment()))
        .as("published original is not rewritten to provide UI context").isEqualTo(original.teacher());
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUPERSEDED", "CLOSED", "CANCELLED_ASSIGNMENT", "CANCELLED_SESSION", "CANCELLED_ROOM", "CANCELLED_CASE"})
  void historicalOrCancelledCaseIsReadableButNotActionable(String condition) throws Exception {
    Account original = teacher("history");
    Duty duty = duty(original, true, condition);
    UUID caseId = directCase(duty, original, condition.equals("CANCELLED_CASE") ? "CANCELLED" : "OPEN");
    var before = snapshot(duty);
    JsonNode row = find(cases(manager()), caseId);
    assertThat(row.has("actionable")).isTrue();
    assertThat(row.path("actionable").asBoolean(true)).isFalse();
    assertThat(row.path("planName").asText()).isEqualTo("界面验收考试计划");
    assertThat(snapshot(duty)).isEqualTo(before);
  }

  @Test
  void roomlessCaseRemainsActionableAndRoomContextStaysNull() throws Exception {
    Account original = teacher("roomless");
    Duty duty = duty(original, false, "ACTIVE");
    UUID caseId = reject(login(original), duty);
    JsonNode row = find(cases(manager()), caseId);
    assertThat(row.path("actionable").asBoolean(false)).isTrue();
    for (String key : List.of("roomName", "roomCode")) {
      assertThat(row.has(key)).isTrue();
      assertThat(row.get(key).isNull()).isTrue();
    }
  }

  @Test
  void permissionsAndOwnershipRemainEnforcedForReadOnlyContext() throws Exception {
    Account owner = teacher("owner"), other = teacher("other");
    Duty duty = duty(owner, true, "ACTIVE");
    Cookie ownerCookie = login(owner), otherCookie = login(other);
    UUID caseId = reject(ownerCookie, duty);
    var before = snapshot(duty);
    mvc.perform(get("/api/v1/auth/me/duty-assignments")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/substitution-cases")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/substitution-cases").cookie(ownerCookie)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(manager())).andExpect(status().isForbidden());
    assertThat(self(otherCookie).findValuesAsText("id")).doesNotContain(duty.assignment().toString());
    mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(ownerCookie)
        .header("X-Tenant-Id", "00000000-0000-0000-0000-000000010002"))
        .andExpect(status().isForbidden());
    assertThat(find(cases(manager()), caseId).path("teacherName").asText()).isEqualTo(owner.name());
    assertThat(snapshot(duty)).isEqualTo(before);
  }

  private Duty duty(Account owner, boolean withRoom, String condition) {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID();
    UUID requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    String sessionCode = "UI-" + session.toString().substring(0, 12);
    String roomCode = withRoom ? "UI-" + UUID.randomUUID().toString().substring(0, 12) : null;
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name,status) values (?,?,?,'界面验收考试计划','DRAFT')", plan, ORG, "UI-" + plan);
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type) values (?,?,?,1,'DRAFT','API')", revision, ORG, plan);
    jdbc.update("insert into exam_plan_session(id,org_id,revision_id,session_code,name,subject_name,starts_at,ends_at,candidate_count,status) values (?,?,?,?,'上午场',?,?,?,0,?)",
        session, ORG, revision, sessionCode, withRoom ? "数学" : null, Timestamp.from(START), Timestamp.from(END), condition.equals("CANCELLED_SESSION") ? "CANCELLED" : "PLANNED");
    UUID room = null;
    if (withRoom) {
      UUID classroom = UUID.randomUUID(); room = UUID.randomUUID();
      jdbc.update("insert into classroom(id,org_id,code,name,capacity,exam_capacity) values (?,?,?,'明德楼 201',30,30)", classroom, ORG, roomCode);
      jdbc.update("insert into exam_plan_room_assignment(id,org_id,revision_id,session_id,classroom_id,starts_at,ends_at,planned_capacity,allocated_candidates,status) values (?,?,?,?,?,?,?,30,0,?)",
          room, ORG, revision, session, classroom, Timestamp.from(START), Timestamp.from(END), condition.equals("CANCELLED_ROOM") ? "CANCELLED" : "PLANNED");
    }
    jdbc.update("insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,room_assignment_id,duty_role,required_count,qualification) values (?,?,?,?,?,'INVIGILATOR',1,'{\"requiredQualifications\":[\"INVIGILATOR\"]}'::jsonb)", requirement, ORG, revision, session, room);
    jdbc.update("insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status) values (?,?,?,?,?,?,?,'INVIGILATOR',?)",
        assignment, ORG, revision, requirement, owner.teacher(), Timestamp.from(START), Timestamp.from(END), condition.equals("CANCELLED_ASSIGNMENT") ? "CANCELLED" : "PENDING_CONFIRMATION");
    // Synthetic fixture construction, not evidence that the real approval/publication workflow ran.
    jdbc.update("update exam_plan_revision set status=?,published_at=now() where id=?", condition.equals("CLOSED") ? "CLOSED" : "PUBLISHED", revision);
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where id=?", revision, plan);
    if (condition.equals("SUPERSEDED")) {
      UUID successor = UUID.randomUUID();
      jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type) values (?,?,?,2,'PUBLISHED','API')", successor, ORG, plan);
      jdbc.update("update exam_plan set active_revision_id=? where id=?", successor, plan);
    }
    return new Duty(plan, revision, assignment, sessionCode, roomCode);
  }

  private Account teacher(String label) {
    UUID user = UUID.randomUUID(), teacher = UUID.randomUUID();
    String username = "ui." + label + "." + user.toString().substring(0, 8), name = "测试教师-" + label;
    assertThat(jdbc.update("insert into app_user(id,org_id,username,display_name,email,password_hash,role,status,must_change_password,onboarded) select ?,org_id,?,?,?,password_hash,'TEACHER_QUERY','active',false,true from app_user where org_id=? and username='jwc.demo'",
        user, username, name, username + "@example.test", ORG)).isOne();
    assertThat(jdbc.update("insert into user_role(user_id,role_id,org_id) select ?,id,org_id from role where org_id=? and code='TEACHER_QUERY'", user, ORG)).isOne();
    jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender,email,status,qualifications,max_daily_sessions,app_user_id) values (?,?,?,?,'unknown',?,'active','[\"INVIGILATOR\"]'::jsonb,4,?)",
        teacher, ORG, "UI-" + teacher.toString().substring(0, 12), name, username + "@example.test", user);
    return new Account(user, teacher, username, name);
  }

  private UUID directCase(Duty duty, Account teacher, String state) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into exam_plan_substitution_case(id,org_id,revision_id,duty_assignment_id,teacher_id,engagement_no,status,reason) values (?,?,?,?,?,0,?,'界面历史记录夹具')",
        id, ORG, duty.revision(), duty.assignment(), teacher.teacher(), state);
    return id;
  }

  private UUID reject(Cookie cookie, Duty duty) throws Exception {
    JsonNode result = json(mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", duty.assignment())
        .cookie(cookie).header("X-Duty-Engagement", 0).contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"界面验收申请替补\"}")).andExpect(status().isOk()).andReturn());
    return UUID.fromString(result.path("substitutionCaseId").asText());
  }

  private Cookie manager() throws Exception { return login("tenant.test", "Tenant001!!!"); }
  private Cookie login(Account account) throws Exception { return login(account.username(), "Demo@2025"); }
  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = mapper.readValue(mvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
    Cookie cookie = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", password, "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn().getResponse().getCookie("JKXT_SCHOOL_SESSION");
    assertThat(cookie).as("fresh isolated test authentication, never browser cookies").isNotNull();
    return cookie;
  }
  private JsonNode self(Cookie cookie) throws Exception { return json(mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(cookie)).andExpect(status().isOk()).andReturn()); }
  private JsonNode cases(Cookie cookie) throws Exception { return json(mvc.perform(get("/api/v1/substitution-cases").cookie(cookie)).andExpect(status().isOk()).andReturn()); }
  private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
  private JsonNode find(JsonNode rows, UUID id) {
    for (JsonNode row : rows) if (row.path("id").asText().equals(id.toString())) return row;
    throw new AssertionError("Expected actual API row " + id);
  }
  private List<List<String>> snapshot(Duty duty) {
    return List.of(
        jdbc.queryForList("select to_jsonb(a)::text from exam_plan_duty_assignment a where org_id=? and id=?", String.class, ORG, duty.assignment()),
        jdbc.queryForList("select to_jsonb(r)::text from exam_plan_revision r where org_id=? and id=?", String.class, ORG, duty.revision()),
        jdbc.queryForList("select to_jsonb(x)::text from exam_plan_duty_engagement x where org_id=? and assignment_id=? order by engagement_no", String.class, ORG, duty.assignment()),
        jdbc.queryForList("select to_jsonb(x)::text from exam_plan_duty_response x where org_id=? and assignment_id=? order by engagement_no", String.class, ORG, duty.assignment()),
        jdbc.queryForList("select to_jsonb(x)::text from exam_plan_substitution_case x where org_id=? and duty_assignment_id=? order by id", String.class, ORG, duty.assignment()),
        jdbc.queryForList("select to_jsonb(x)::text from exam_plan_audit_event x where org_id=? and revision_id=? order by id", String.class, ORG, duty.revision()),
        jdbc.queryForList("select to_jsonb(x)::text from exam_plan_outbox x where org_id=? and revision_id=? order by id", String.class, ORG, duty.revision()));
  }
  private record Account(UUID user, UUID teacher, String username, String name) {}
  private record Duty(UUID plan, UUID revision, UUID assignment, String sessionCode, String roomCode) {}
}
