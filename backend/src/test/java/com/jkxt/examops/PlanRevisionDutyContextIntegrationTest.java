package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.scheduling.api.LegacyPublicationDtos.SubmitPublicationRequest;
import com.jkxt.scheduling.service.LegacyPublicationService;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Real legacy projection and authenticated read API on guard-enforced isolated PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlanRevisionDutyContextIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired LegacyPublicationService legacy;

  @AfterEach
  void clearTenant() { TenantContext.clear(); }

  @ParameterizedTest
  @ValueSource(strings = {"duties", "assignments"})
  void preexistingLegacyProjectionReturnsExactSessionAndRoomContextWithoutWrites(String resource) throws Exception {
    Cookie manager = login("jwc.demo", "Demo@2025");
    Projection projection = legacyProjection();
    Map<String, String> before = businessState();
    JsonNode rows = read(manager, projection.revision(), resource);
    assertThat(rows.size()).as("two source rooms must not become a Cartesian product").isEqualTo(2);
    for (JsonNode row : rows) {
      assertThat(row.path("session_external_code").asText()).isEqualTo(projection.exam().toString());
      assertThat(row.path("session_name").asText()).isEqualTo("投影数学");
      assertThat(row.path("subject_name").asText()).isEqualTo("投影数学");
      String teacher = row.path("teacher_name").asText();
      String suffix = teacher.equals("投影教师甲") ? "A" : "B";
      assertThat(teacher).isIn("投影教师甲", "投影教师乙");
      assertThat(row.path("room_code").asText()).isEqualTo(projection.roomCodePrefix() + suffix);
      assertThat(row.path("room_name").asText()).isEqualTo("投影考场" + suffix);
      assertThat(row.path("classroom_id").asText()).isEqualTo(projection.classrooms().get(suffix).toString());
      UUID allocation = jdbc.queryForObject(
          "select id from exam_plan_room_assignment where org_id=? and revision_id=? and classroom_id=?",
          UUID.class, ORG, projection.revision(), projection.classrooms().get(suffix));
      assertThat(row.path("room_assignment_id").asText()).isEqualTo(allocation.toString());
      assertThat(row.path("current_teacher_id").asText()).isEqualTo(row.path("teacher_id").asText());
      assertThat(row.path("duty_role").asText()).isEqualTo("CHIEF");
    }
    assertThat(businessState()).as("GET must not repair, reproject, approve or publish stored data").isEqualTo(before);
  }

  @Test
  void contextUsesStoredSessionButCurrentClassroomMetadataWithoutPretendingToBeARoomSnapshot() throws Exception {
    Cookie manager = login("jwc.demo", "Demo@2025");
    Projection projection = legacyProjection();
    jdbc.update("update exam set subject_name='后来修改的源科目' where id=?", projection.exam());
    jdbc.update("update classroom set name='当前基础资料名称' where id=?", projection.classrooms().get("A"));
    Map<String, String> before = businessState();
    JsonNode rows = read(manager, projection.revision(), "duties");
    assertThat(rows.size()).isEqualTo(2);
    for (JsonNode row : rows) {
      assertThat(row.path("session_name").asText()).isEqualTo("投影数学");
      assertThat(row.path("subject_name").asText()).isEqualTo("投影数学");
      if (row.path("classroom_id").asText().equals(projection.classrooms().get("A").toString())) {
        assertThat(row.path("room_name").asText()).isEqualTo("当前基础资料名称");
      }
    }
    assertThat(rows.findValuesAsText("room_name")).contains("当前基础资料名称", "投影考场B");
    assertThat(businessState()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void roomlessAndCancelledVersionDutiesRemainReadableWithExplicitNullRoomContext(boolean cancelled) throws Exception {
    Cookie manager = login("jwc.demo", "Demo@2025");
    UUID revision = roomlessRevision(cancelled);
    Map<String, String> before = businessState();
    JsonNode rows = read(manager, revision, "duties");
    assertThat(rows.size()).isOne();
    JsonNode row = rows.get(0);
    assertThat(row.path("session_name").asText()).isEqualTo("场次级巡考");
    assertThat(row.path("duty_role").asText()).isEqualTo("ROAMING");
    assertThat(row.path("status").asText()).isEqualTo(cancelled ? "CANCELLED" : "DRAFT");
    for (String key : List.of("subject_name", "room_assignment_id", "classroom_id", "room_name", "room_code")) {
      assertThat(row.has(key)).as("optional context remains explicit: %s", key).isTrue();
      assertThat(row.get(key).isNull()).as("no invented room or subject: %s", key).isTrue();
    }
    assertThat(businessState()).isEqualTo(before);
  }

  @Test
  void cancelledLinkedRoomDoesNotRemoveAnArrangementFromVersionHistory() throws Exception {
    Cookie manager = login("jwc.demo", "Demo@2025");
    Projection projection = legacyProjection();
    jdbc.update("update exam_plan_room_assignment set status='CANCELLED' where revision_id=? and classroom_id=?",
        projection.revision(), projection.classrooms().get("A"));
    Map<String, String> before = businessState();
    JsonNode rows = read(manager, projection.revision(), "duties");
    assertThat(rows.size()).isEqualTo(2);
    assertThat(rows.findValuesAsText("room_name")).containsExactlyInAnyOrder("投影考场A", "投影考场B");
    assertThat(businessState()).isEqualTo(before);
  }

  @Test
  void additionalReadContextDoesNotBypassAuthenticationRoleTenantOrRevisionBoundaries() throws Exception {
    Cookie manager = login("jwc.demo", "Demo@2025");
    Projection projection = legacyProjection();
    String teacherUsername = createReader();
    Cookie reader = login(teacherUsername, "Demo@2025");
    UUID foreignOrg = UUID.randomUUID(), foreignPlan = UUID.randomUUID(), foreignRevision = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Foreign context',?)", foreignOrg, "ctx-" + foreignOrg);
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Foreign plan')", foreignPlan, foreignOrg, "ctx-" + foreignPlan);
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no) values (?,?,?,1)", foreignRevision, foreignOrg, foreignPlan);
    Map<String, String> before = businessState();
    for (String resource : List.of("duties", "assignments")) {
      mvc.perform(get("/api/v1/plan-revisions/{id}/{resource}", projection.revision(), resource))
          .andExpect(status().isUnauthorized());
      mvc.perform(get("/api/v1/plan-revisions/{id}/{resource}", projection.revision(), resource).cookie(reader))
          .andExpect(status().isForbidden());
      mvc.perform(get("/api/v1/plan-revisions/{id}/{resource}", foreignRevision, resource).cookie(manager))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/v1/plan-revisions/{id}/{resource}", UUID.randomUUID(), resource).cookie(manager))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/v1/plan-revisions/{id}/{resource}", projection.revision(), resource).cookie(manager)
              .header("X-Tenant-Id", foreignOrg.toString())).andExpect(status().isForbidden());
    }
    assertThat(businessState()).isEqualTo(before);
  }

  private Projection legacyProjection() {
    UUID task = UUID.randomUUID(), run = UUID.randomUUID(), exam = UUID.randomUUID();
    String prefix = "CTX-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    jdbc.update("insert into exam_task(id,org_id,task_no,name,status) values (?,?,?,'Context projection','scheduled')", task, ORG, "ctx-" + task);
    jdbc.update("""
        insert into exam(id,org_id,exam_task_id,subject_name,exam_date,start_time,end_time,settings)
        values (?,?,?,'投影数学',date '2048-04-04',time '08:00',time '09:00',
          '{"seatDemand":20,"supervisorsPerRoom":1}'::jsonb)
        """, exam, ORG, task);
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status) values (?,?,?,'succeeded')", run, ORG, task);
    Map<String, UUID> classrooms = new LinkedHashMap<>();
    for (String suffix : List.of("A", "B")) {
      UUID classroom = UUID.randomUUID(), teacher = teacher(suffix.equals("A") ? "投影教师甲" : "投影教师乙");
      classrooms.put(suffix, classroom);
      jdbc.update("insert into classroom(id,org_id,code,name,capacity,exam_capacity) values (?,?,?,?,30,30)", classroom, ORG, prefix + suffix, "投影考场" + suffix);
      jdbc.update("insert into exam_room(org_id,exam_id,classroom_id) values (?,?,?)", ORG, exam, classroom);
      jdbc.update("""
          insert into schedule_assignment(org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time)
          values (?,?,?,?,?,'main',date '2048-04-04',time '08:00',time '09:00')
          """, ORG, run, exam, classroom, teacher);
    }
    UUID actor = jdbc.queryForObject("select id from app_user where org_id=? and username='jwc.demo'", UUID.class, ORG);
    TenantContext.set(ORG);
    try {
      var submitted = legacy.submit(task, new SubmitPublicationRequest(run, 0L), actor);
      assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
      return new Projection(submitted.revisionId(), exam, prefix, classrooms);
    } finally { TenantContext.clear(); }
  }

  private UUID roomlessRevision(boolean cancelled) {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID(), requirement = UUID.randomUUID();
    UUID teacher = teacher("场次巡考教师");
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Roomless context')", plan, ORG, "ctx-" + plan);
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no) values (?,?,?,1)", revision, ORG, plan);
    jdbc.update("""
        insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,status)
        values (?,?,?,'ROOMLESS','场次级巡考',timestamptz '2048-04-05 00:00:00+00',timestamptz '2048-04-05 01:00:00+00',?)
        """, session, ORG, revision, cancelled ? "CANCELLED" : "PLANNED");
    jdbc.update("insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count) values (?,?,?,?,'ROAMING',1)", requirement, ORG, revision, session);
    jdbc.update("""
        insert into exam_plan_duty_assignment(org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status)
        values (?,?,?,?,timestamptz '2048-04-05 00:00:00+00',timestamptz '2048-04-05 01:00:00+00','ROAMING',?)
        """, ORG, revision, requirement, teacher, cancelled ? "CANCELLED" : "DRAFT");
    return revision;
  }

  private UUID teacher(String name) {
    UUID teacher = UUID.randomUUID();
    jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,qualifications,max_daily_sessions)
        values (?,?,?,?,'unknown','["INVIGILATOR","CHIEF"]'::jsonb,4)
        """, teacher, ORG, "CTX-" + teacher.toString().substring(0, 12), name);
    return teacher;
  }

  private String createReader() {
    UUID user = UUID.randomUUID();
    String username = "ctx.reader." + user.toString().substring(0, 12);
    assertThat(jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password,onboarded)
        select ?,org_id,?,'Context reader',password_hash,'TEACHER_QUERY','active',false,true
        from app_user where org_id=? and username='jwc.demo'
        """, user, username, ORG)).isOne();
    jdbc.update("insert into user_role(user_id,role_id,org_id) select ?,id,org_id from role where org_id=? and code='TEACHER_QUERY'", user, ORG);
    return username;
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = mapper.readValue(mvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
    Cookie cookie = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("username", username, "password", password,
                "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn().getResponse().getCookie("JKXT_SCHOOL_SESSION");
    assertThat(cookie).as("fresh isolated test login, never a browser session").isNotNull();
    return cookie;
  }

  private JsonNode read(Cookie cookie, UUID revision, String resource) throws Exception {
    return mapper.readTree(mvc.perform(get("/api/v1/plan-revisions/{id}/{resource}", revision, resource).cookie(cookie))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private Map<String, String> businessState() {
    Map<String, String> state = new LinkedHashMap<>();
    for (String table : List.of("exam_plan", "exam_plan_revision", "exam_plan_session", "exam_plan_room_assignment",
        "exam_plan_duty_requirement", "exam_plan_duty_assignment", "exam_plan_approval_request",
        "legacy_exam_task_plan_link", "exam_plan_outbox", "notification", "audit_log")) {
      state.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(x) order by to_jsonb(x)::text),'[]'::jsonb)::text from "
          + table + " x where org_id=?", String.class, ORG));
    }
    return state;
  }

  private record Projection(UUID revision, UUID exam, String roomCodePrefix, Map<String, UUID> classrooms) {}
}
