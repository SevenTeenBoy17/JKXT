package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class JointInputApiIntegrationTest {

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000204");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID planId;
  private UUID revisionId;
  private UUID foreignOrgId;

  @AfterEach
  void cleanup() {
    if (revisionId != null) {
      jdbcTemplate.update("delete from audit_log where target_type='EXAM_PLAN_REVISION' and target_id=?", revisionId);
    }
    if (planId != null) {
      jdbcTemplate.update("delete from exam_plan where org_id=? and id=?", ORG_ID, planId);
    }
    if (foreignOrgId != null) {
      jdbcTemplate.update("delete from organization where id=?", foreignOrgId);
    }
  }

  @Test
  void typedCollectionsAreTenantScopedAtomicCursorCheckedAndPayloadBound() throws Exception {
    Cookie admin = login("tenant.test", "Tenant001!!!");
    createPlan(admin);
    UUID subjectId = jdbcTemplate.queryForObject(
        "select id from subject where org_id=? and status='active' order by code limit 1", UUID.class, ORG_ID);
    UUID classId = jdbcTemplate.queryForObject(
        "select id from school_class where org_id=? and status='active' order by code limit 1", UUID.class, ORG_ID);
    UUID classroomId = jdbcTemplate.queryForObject(
        "select id from classroom where org_id=? and status='active' order by code limit 1", UUID.class, ORG_ID);
    int roomCapacity = jdbcTemplate.queryForObject(
        "select exam_capacity from classroom where org_id=? and id=?", Integer.class, ORG_ID, classroomId);
    UUID teacherId = jdbcTemplate.queryForObject(
        "select id from teacher where org_id=? and status='active' order by teacher_no limit 1", UUID.class, ORG_ID);

    String demandBody = """
        {"items":[{
          "demandCode":"MATH-01","name":"Mathematics","subjectId":"%s",
          "durationMinutes":120,"candidateCount":1,"invigilatorCount":1,
          "minRooms":1,"maxRooms":1,"splitAllowed":false,
          "requiredRoomFeatures":["PROJECTOR"],"settings":{"priority":"NORMAL"}
        }]}
        """.formatted(subjectId);
    JsonNode demandWrite = postInput(admin, "demands", "0", "joint-demand-0001", demandBody, 200);
    assertThat(demandWrite.path("revisionCursor").asLong()).isEqualTo(1);
    assertThat(demandWrite.path("replayed").asBoolean()).isFalse();

    JsonNode replay = postInput(admin, "demands", "0", "joint-demand-0001", demandBody, 200);
    assertThat(replay.path("revisionCursor").asLong()).isEqualTo(1);
    assertThat(replay.path("replayed").asBoolean()).isTrue();
    postInput(admin, "demands", "0", "joint-demand-0001",
        demandBody.replace("Mathematics", "Changed payload"), 409);
    postInput(admin, "demands", "0", "joint-demand-0002", demandBody, 409);

    String classBody = """
        {"items":[{"demandCode":"MATH-01","classId":"%s","expectedCandidates":1}]}
        """.formatted(classId);
    postInput(admin, "demand-classes", "1", "joint-class-0001", classBody, 200);
    assertThat(cursor()).isEqualTo(2);
    String invalidClass = """
        {"items":[{"demandCode":"MATH-01","classId":"%s","expectedCandidates":1}]}
        """.formatted(UUID.randomUUID());
    postInput(admin, "demand-classes", "2", "joint-class-0002", invalidClass, 422);
    assertThat(cursor()).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_demand_class where org_id=? and revision_id=?",
        Integer.class, ORG_ID, revisionId)).isEqualTo(1);

    postInput(admin, "candidates", "2", "joint-candidate-0001", """
        {"items":[{"candidateCode":"STU-001","name":"Student One","classId":"%s",
          "attributes":{"grade":"G10"}}]}
        """.formatted(classId), 200);
    postInput(admin, "enrollments", "3", "joint-enrollment-0001", """
        {"items":[{"candidateCode":"STU-001","demandCode":"MATH-01","status":"ENROLLED"}]}
        """, 200);
    postInput(admin, "room-pool", "4", "joint-room-0001", """
        {"items":[{"classroomId":"%s","campusCode":"MAIN","examCapacity":%d,
          "features":["PROJECTOR"],"status":"INCLUDED"}]}
        """.formatted(classroomId, Math.min(roomCapacity, 10)), 200);
    postInput(admin, "teacher-pool", "5", "joint-teacher-0001", """
        {"items":[{"teacherId":"%s","eligibleRoles":["INVIGILATOR"],
          "maxDailySessions":3,"minRestMinutes":30,"preferredCampus":"MAIN","status":"INCLUDED"}]}
        """.formatted(teacherId), 200);
    postInput(admin, "campus-travel", "6", "joint-travel-0001", """
        {"items":[{"fromCampus":"MAIN","toCampus":"EAST","travelMinutes":25,"bufferMinutes":10}]}
        """, 200);

    mockMvc.perform(get("/api/v1/plan-revisions/{revision}/inputs/demands", revisionId).cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionCursor").value(7))
        .andExpect(jsonPath("$.items[0].demandCode").value("MATH-01"))
        .andExpect(jsonPath("$.items[0].requiredRoomFeatures[0]").value("PROJECTOR"));
    mockMvc.perform(get("/api/v1/plan-revisions/{revision}/inputs/enrollments", revisionId).cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].candidateCode").value("STU-001"))
        .andExpect(jsonPath("$.items[0].demandCode").value("MATH-01"));
    mockMvc.perform(get("/api/v1/plan-revisions/{revision}/inputs/room-pool", revisionId).cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].classroomId").value(classroomId.toString()));
    mockMvc.perform(get("/api/v1/plan-revisions/{revision}/inputs/teacher-pool", revisionId).cookie(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].teacherId").value(teacherId.toString()));

    UUID foreignRevision = seedForeignRevision();
    mockMvc.perform(get("/api/v1/plan-revisions/{revision}/inputs/demands", foreignRevision).cookie(admin))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EXAM_PLAN_NOT_FOUND"));
    mockMvc.perform(post("/api/v1/plan-revisions/{revision}/inputs/demands", foreignRevision)
            .cookie(admin).header("If-Match", "0").header("Idempotency-Key", "joint-foreign-0001")
            .contentType(MediaType.APPLICATION_JSON).content(demandBody))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EXAM_PLAN_NOT_FOUND"));

    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from audit_log where org_id=? and target_id=?
          and action='JOINT_INPUT_REPLACED' and payload->>'requestHash' is not null
        """, Integer.class, ORG_ID, revisionId)).isEqualTo(7);
  }

  private JsonNode postInput(
      Cookie cookie, String resource, String cursor, String key, String body, int expectedStatus
  ) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/plan-revisions/{revision}/inputs/{resource}", revisionId, resource)
            .cookie(cookie).header("If-Match", cursor).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(expectedStatus)).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private long cursor() {
    return jdbcTemplate.queryForObject(
        "select version from exam_plan_revision where org_id=? and id=?", Long.class, ORG_ID, revisionId);
  }

  private void createPlan(Cookie cookie) throws Exception {
    JsonNode response = objectMapper.readTree(mockMvc.perform(post("/api/v1/exam-plans").cookie(cookie)
            .header("Idempotency-Key", "joint-plan-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planNo\":\"JOINT-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"name\":\"Joint input API test\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    planId = UUID.fromString(response.path("id").asText());
    revisionId = UUID.fromString(response.path("activeRevisionId").asText());
  }

  private UUID seedForeignRevision() {
    foreignOrgId = UUID.randomUUID();
    UUID foreignPlan = UUID.randomUUID();
    UUID foreignRevision = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into organization (id, name, slug, type, plan, status)
        values (?, 'Joint Foreign School', ?, 'school', 'trial', 'active')
        """, foreignOrgId, "joint-foreign-" + foreignOrgId.toString().substring(0, 8));
    jdbcTemplate.update("""
        insert into exam_plan (id, org_id, plan_no, name, status)
        values (?, ?, ?, 'Foreign joint plan', 'DRAFT')
        """, foreignPlan, foreignOrgId, "JF-" + foreignPlan.toString().substring(0, 8));
    jdbcTemplate.update("""
        insert into exam_plan_revision (id, org_id, plan_id, revision_no, status, source_type)
        values (?, ?, ?, 1, 'DRAFT', 'API')
        """, foreignRevision, foreignOrgId, foreignPlan);
    jdbcTemplate.update("update exam_plan set active_revision_id=? where id=?", foreignRevision, foreignPlan);
    return foreignRevision;
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), CaptchaResponse.class);
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","password":"%s","captchaId":"%s","captcha":"%s"}
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk()).andReturn();
    return result.getResponse().getCookie("JKXT_SESSION");
  }
}
