package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExamPlanApiIntegrationTest {

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID TENANT_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000204");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID createdPlanId;
  private UUID foreignOrgId;
  private UUID teacherUserId;
  private UUID linkedTeacherId;
  private String teacherUsername;

  @AfterEach
  void cleanup() {
    if (createdPlanId != null) {
      int immutable = jdbcTemplate.queryForObject("""
          select count(*) from exam_plan_revision where plan_id=?
            and status in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
          """, Integer.class, createdPlanId);
      if (immutable == 0) {
        jdbcTemplate.update("update exam_plan_revision set status='CANCELLED' where plan_id=?", createdPlanId);
        jdbcTemplate.update("delete from exam_plan where id=?", createdPlanId);
      }
    }
    if (foreignOrgId != null) {
      jdbcTemplate.update("delete from organization where id=?", foreignOrgId);
    }
    if (teacherUserId != null) {
      jdbcTemplate.update("update teacher set app_user_id=null where app_user_id=?", teacherUserId);
      jdbcTemplate.update("delete from user_role where user_id=?", teacherUserId);
      jdbcTemplate.update("delete from app_user where id=?", teacherUserId);
    }
  }

  @Test
  void lifecycleIsTenantScopedOptimisticAndTruthful() throws Exception {
    Cookie submitter = login("tenant.test", "Tenant001!!!");
    Cookie approver = login("admin", "Super001!!!");
    seedTeacherUser();
    Cookie teacherOnly = login(teacherUsername, "Demo@2025");
    String key = "exam-plan-test-" + UUID.randomUUID();
    String planNo = "T-" + UUID.randomUUID().toString().substring(0, 10);
    String body = "{\"planNo\":\"" + planNo + "\",\"name\":\"Integration plan\",\"initialRevisionTitle\":\"Baseline\"}";

    JsonNode created = json(mockMvc.perform(post("/api/v1/exam-plans").cookie(submitter)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn());
    createdPlanId = UUID.fromString(created.path("id").asText());
    assertThat(created.path("activeRevisionId").asText()).isNotBlank();

    JsonNode replay = json(mockMvc.perform(post("/api/v1/exam-plans").cookie(submitter)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn());
    assertThat(replay.path("id").asText()).isEqualTo(createdPlanId.toString());
    // PB-14 (round 9): the same key with the same JSON in another key order still replays ...
    String reordered = "{\"initialRevisionTitle\":\"Baseline\",\"name\":\"Integration plan\",\"planNo\":\"" + planNo + "\"}";
    JsonNode reorderedReplay = json(mockMvc.perform(post("/api/v1/exam-plans").cookie(submitter)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(reordered))
        .andExpect(status().isCreated()).andReturn());
    assertThat(reorderedReplay.path("id").asText()).isEqualTo(createdPlanId.toString());
    // ... but the same key with a different body is a conflict and creates nothing.
    mockMvc.perform(post("/api/v1/exam-plans").cookie(submitter)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("Integration plan", "Different plan body")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan where upper(plan_no)=upper(?)", Integer.class, planNo)).isOne();
    assertThat(jdbcTemplate.queryForObject(
        "select request_hash from api_idempotency_record where idempotency_key=? and operation='exam-plan:create'",
        String.class, key)).startsWith("sha256:");

    JsonNode revisions = json(mockMvc.perform(get("/api/v1/exam-plans/{id}/revisions", createdPlanId).cookie(submitter))
        .andExpect(status().isOk()).andReturn());
    UUID revisionId = UUID.fromString(revisions.get(0).path("id").asText());
    assertThat(created.path("activeRevisionId").asText()).isEqualTo(revisionId.toString());

    mockMvc.perform(get("/api/v1/exam-plans").cookie(teacherOnly))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(post("/api/v1/exam-plans").cookie(teacherOnly)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Teacher must not create this\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/validate", createdPlanId, revisionId)
            .cookie(teacherOnly).header("If-Match", "0"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/publish", createdPlanId, revisionId)
            .cookie(teacherOnly).header("If-Match", "0"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/validate", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "\"0\""))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PLAN_INVALID"))
        .andExpect(jsonPath("$.hardViolationCount").value(1));

    seedFeasibleRevision(revisionId);

    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/validate", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "\"1\""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true))
        .andExpect(jsonPath("$.status").value("READY"));

    // A stale READY flag must not bypass per-session seat validation at the approval gate.
    jdbcTemplate.update("update exam_plan_seat_assignment set status='CANCELLED' where revision_id=?", revisionId);
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/submit-approval", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "2"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SESSION_SEATS_UNASSIGNED")));
    jdbcTemplate.update("update exam_plan_seat_assignment set status='ASSIGNED' where revision_id=?", revisionId);

    // A READY snapshot also cannot hide a per-session room-capacity deficit.
    jdbcTemplate.update("update exam_plan_session set candidate_count=2 where revision_id=?", revisionId);
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/submit-approval", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "2"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SESSION_ROOM_CAPACITY_UNCOVERED")));
    jdbcTemplate.update("update exam_plan_session set candidate_count=1 where revision_id=?", revisionId);

    // Coverage is checked per requirement/role, not only against a tenant-wide duty total.
    jdbcTemplate.update("update exam_plan_duty_assignment set status='CANCELLED' where revision_id=?", revisionId);
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/submit-approval", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "2"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SESSION_DUTY_ROLE_UNCOVERED")));
    jdbcTemplate.update("update exam_plan_duty_assignment set status='DRAFT' where revision_id=?", revisionId);

    UUID assignedChief = jdbcTemplate.queryForObject(
        "select teacher_id from exam_plan_duty_assignment where revision_id=? limit 1", UUID.class, revisionId);
    jdbcTemplate.update("update teacher set qualifications='[\"INVIGILATOR\"]'::jsonb where id=?", assignedChief);
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/submit-approval", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "2"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("TEACHER_DUTY_QUALIFICATION_MISSING")));
    jdbcTemplate.update("update teacher set qualifications='[\"INVIGILATOR\",\"CHIEF\"]'::jsonb where id=?", assignedChief);

    java.sql.Date originalExpiry = jdbcTemplate.queryForObject(
        "select qualification_expiry from teacher where id=?", java.sql.Date.class, assignedChief);
    try {
      LocalDate dutyDate = jdbcTemplate.queryForObject("""
          select starts_at from exam_plan_duty_assignment where revision_id=? and teacher_id=? limit 1
          """, (result, row) -> result.getTimestamp(1).toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
          revisionId, assignedChief);
      jdbcTemplate.update("update teacher set qualification_expiry=? where id=?",
          java.sql.Date.valueOf(dutyDate.minusDays(1)), assignedChief);
      mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/submit-approval", createdPlanId, revisionId)
              .cookie(submitter).header("If-Match", "2"))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"))
          .andExpect(jsonPath("$.message").value(
              org.hamcrest.Matchers.containsString("TEACHER_DUTY_QUALIFICATION_MISSING")));
    } finally {
      jdbcTemplate.update("update teacher set qualification_expiry=? where id=?", originalExpiry, assignedChief);
    }

    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/submit-approval", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/approve", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "3").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"self review\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("APPROVAL_SELF_REVIEW_FORBIDDEN"));

    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/approve", createdPlanId, revisionId)
            .cookie(approver).header("If-Match", "3").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"independent review passed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    var chiefDuty = jdbcTemplate.queryForMap("""
        select starts_at, ends_at from exam_plan_duty_assignment
        where revision_id=? and teacher_id=? limit 1
        """, revisionId, assignedChief);
    int originalDailyLimit = jdbcTemplate.queryForObject(
        "select max_daily_sessions from teacher where id=?", Integer.class, assignedChief);
    Instant extraStart = ((Timestamp) chiefDuty.get("ends_at")).toInstant().plusSeconds(3_600);
    PublishedBaseline baseline = seedPublishedBaselineDuty(
        assignedChief, extraStart, extraStart.plusSeconds(7_200));
    try {
      jdbcTemplate.update("update teacher set max_daily_sessions=1 where id=?", assignedChief);
      mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/publish", createdPlanId, revisionId)
              .cookie(submitter).header("If-Match", "4"))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"))
          .andExpect(jsonPath("$.message").value(
              org.hamcrest.Matchers.containsString("TEACHER_DAILY_SESSION_LIMIT_EXCEEDED")));
    } finally {
      jdbcTemplate.update("update teacher set max_daily_sessions=? where id=?", originalDailyLimit, assignedChief);
      jdbcTemplate.update("update exam_plan_revision set status='ARCHIVED' where id=?", baseline.revisionId());
      jdbcTemplate.update("update exam_plan set status='ARCHIVED' where id=?", baseline.planId());
    }

    // Approval is not a snapshot exemption: publish must re-check current child resources.
    jdbcTemplate.update("update teacher set qualifications='[\"INVIGILATOR\"]'::jsonb where id=?", assignedChief);
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/publish", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "4"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("TEACHER_DUTY_QUALIFICATION_MISSING")));
    jdbcTemplate.update("update teacher set qualifications='[\"INVIGILATOR\",\"CHIEF\"]'::jsonb where id=?", assignedChief);

    jdbcTemplate.update("update exam_plan_seat_assignment set status='CANCELLED' where revision_id=?", revisionId);
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/publish", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "4"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PLAN_NOT_READY"));
    jdbcTemplate.update("update exam_plan_seat_assignment set status='ASSIGNED' where revision_id=?", revisionId);

    UUID samePlanPublishedRevision = seedSamePlanPublishedBaselineDuty(
        createdPlanId, assignedChief, extraStart, extraStart.plusSeconds(7_200));
    try {
      jdbcTemplate.update("update teacher set max_daily_sessions=1 where id=?", assignedChief);
      mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/publish", createdPlanId, revisionId)
              .cookie(submitter).header("If-Match", "4"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PUBLISHED"));
    } finally {
      jdbcTemplate.update("update teacher set max_daily_sessions=? where id=?", originalDailyLimit, assignedChief);
      jdbcTemplate.update("update exam_plan_revision set status='ARCHIVED' where id=?", samePlanPublishedRevision);
    }

    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_outbox where revision_id=? and event_type='PLAN_PUBLISHED'",
        Integer.class, revisionId)).isEqualTo(1);

    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/validate", createdPlanId, revisionId)
            .cookie(submitter).header("If-Match", "5"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REVISION_IMMUTABLE"));

    assertThatThrownBy(() -> jdbcTemplate.update(
        "update exam_plan_session set name='mutated' where revision_id=?", revisionId))
        .isInstanceOf(DataAccessException.class);

    mockMvc.perform(get("/api/v1/plan-revisions/{id}/sessions", revisionId).cookie(submitter))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("S-1"));
    mockMvc.perform(get("/api/v1/plan-revisions/{id}/assignments", revisionId).cookie(submitter))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].duty_role").value("CHIEF"));
    mockMvc.perform(get("/api/v1/plan-revisions/{id}/duties", revisionId).cookie(teacherOnly))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get("/api/v1/plan-revisions/{id}/assignments", revisionId).cookie(teacherOnly))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    for (String resource : new String[]{"sessions", "rooms", "seats", "constraints"}) {
      mockMvc.perform(get("/api/v1/plan-revisions/{id}/{resource}", revisionId, resource).cookie(teacherOnly))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }
    JsonNode ownDuties = objectMapper.readTree(mockMvc.perform(
            get("/api/v1/auth/me/duty-assignments").cookie(teacherOnly))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(ownDuties).isNotEmpty();
    assertThat(ownDuties.findValuesAsText("teacher_id")).containsOnly(linkedTeacherId.toString());
    assertThat(ownDuties.findValuesAsText("revision_id")).contains(revisionId.toString());
    JsonNode readiness = objectMapper.readTree(mockMvc.perform(get("/api/v1/operations/readiness").cookie(submitter))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(readiness.path("totals").path("publishedPlans").asInt()).isGreaterThanOrEqualTo(1);
    JsonNode approvalRequests = objectMapper.readTree(mockMvc.perform(
            get("/api/v1/approval-requests").cookie(submitter))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    assertThat(approvalRequests).anySatisfy(request -> {
      assertThat(request.path("planId").asText()).isEqualTo(createdPlanId.toString());
      assertThat(request.path("status").asText()).isEqualTo("APPROVED");
    });
    mockMvc.perform(get("/api/v1/approval-requests").cookie(teacherOnly))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get("/api/v1/emergency-changes").cookie(submitter))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/emergency-changes").cookie(teacherOnly))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get("/api/v1/operations/readiness").cookie(teacherOnly))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    assertThatThrownBy(() -> jdbcTemplate.update(
        "update exam_plan_revision set status='DRAFT' where id=?", revisionId))
        .isInstanceOf(DataAccessException.class);

    seedForeignPlan();
    UUID foreignPlan = jdbcTemplate.queryForObject(
        "select id from exam_plan where org_id=? limit 1", UUID.class, foreignOrgId);
    mockMvc.perform(get("/api/v1/exam-plans/{id}", foreignPlan).cookie(submitter))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EXAM_PLAN_NOT_FOUND"));
    assertThatThrownBy(() -> jdbcTemplate.update("""
        insert into exam_plan_revision (
          org_id, plan_id, revision_no, status, source_type, change_reason
        ) values (?, ?, 1, 'DRAFT', 'API', 'cross-tenant mix must fail')
        """, ORG_ID, foreignPlan))
        .isInstanceOf(DataIntegrityViolationException.class);

    mockMvc.perform(get("/api/v1/exam-tasks").cookie(submitter))
        .andExpect(status().isOk());
  }

  private void seedFeasibleRevision(UUID revisionId) {
    UUID session = UUID.randomUUID();
    UUID roomAssignment = UUID.randomUUID();
    UUID dutyRequirement = UUID.randomUUID();
    UUID classroom = jdbcTemplate.queryForObject(
        "select id from classroom where org_id=? and status='active' order by code limit 1", UUID.class, ORG_ID);
    UUID teacher = jdbcTemplate.queryForObject(
        "select id from teacher where org_id=? and status='active' order by teacher_no limit 1", UUID.class, ORG_ID);
    jdbcTemplate.update("update teacher set qualifications='[\"INVIGILATOR\",\"CHIEF\"]'::jsonb where id=?", teacher);
    LocalDate isolatedDay = LocalDate.of(2040, 1, 1)
        .plusDays(Math.floorMod(revisionId.hashCode(), 20_000));
    Instant start = isolatedDay.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant end = start.plusSeconds(7_200);

    jdbcTemplate.update("""
        insert into exam_plan_session (
          id, org_id, revision_id, session_code, name, starts_at, ends_at, candidate_count, created_by, updated_by
        ) values (?, ?, ?, 'S-1', 'Mathematics', ?, ?, 1, ?, ?)
        """, session, ORG_ID, revisionId, Timestamp.from(start), Timestamp.from(end), TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_room_assignment (
          id, org_id, revision_id, session_id, classroom_id, starts_at, ends_at,
          planned_capacity, allocated_candidates, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?)
        """, roomAssignment, ORG_ID, revisionId, session, classroom,
        Timestamp.from(start), Timestamp.from(end), TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_seat_assignment (
          org_id, revision_id, session_id, room_assignment_id, candidate_external_id, seat_label,
          created_by, updated_by
        ) values (?, ?, ?, ?, 'C-1', 'A01', ?, ?)
        """, ORG_ID, revisionId, session, roomAssignment, TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement (
          id, org_id, revision_id, session_id, room_assignment_id, duty_role, required_count,
          qualification, created_by, updated_by
        ) values (?, ?, ?, ?, ?, 'CHIEF', 1,
          '{"requiredQualifications":["INVIGILATOR","CHIEF"]}'::jsonb, ?, ?)
        """, dutyRequirement, ORG_ID, revisionId, session, roomAssignment, TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_duty_assignment (
          org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at, duty_role,
          created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, 'CHIEF', ?, ?)
        """, ORG_ID, revisionId, dutyRequirement, teacher,
        Timestamp.from(start), Timestamp.from(end), TENANT_ADMIN, TENANT_ADMIN);
  }

  private PublishedBaseline seedPublishedBaselineDuty(UUID teacherId, Instant start, Instant end) {
    UUID planId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID requirementId = UUID.randomUUID();
    String planNo = "BASELINE-" + planId.toString().substring(0, 12);
    jdbcTemplate.update("""
        insert into exam_plan (id, org_id, plan_no, name, status, created_by, updated_by)
        values (?, ?, ?, 'Published daily-limit baseline', 'DRAFT', ?, ?)
        """, planId, ORG_ID, planNo, TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_revision (
          id, org_id, plan_id, revision_no, status, source_type, title, created_by, updated_by
        ) values (?, ?, ?, 1, 'DRAFT', 'API', 'Published daily-limit baseline', ?, ?)
        """, revisionId, ORG_ID, planId, TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("update exam_plan set active_revision_id=? where id=?", revisionId, planId);
    jdbcTemplate.update("""
        insert into exam_plan_session (
          id, org_id, revision_id, session_code, name, starts_at, ends_at, candidate_count,
          created_by, updated_by
        ) values (?, ?, ?, ?, 'Published baseline session', ?, ?, 0, ?, ?)
        """, sessionId, ORG_ID, revisionId, "BASE-" + sessionId.toString().substring(0, 8),
        Timestamp.from(start), Timestamp.from(end), TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement (
          id, org_id, revision_id, session_id, duty_role, required_count, qualification,
          created_by, updated_by
        ) values (?, ?, ?, ?, 'CHIEF', 1,
          '{"requiredQualifications":["INVIGILATOR","CHIEF"]}'::jsonb, ?, ?)
        """, requirementId, ORG_ID, revisionId, sessionId, TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_duty_assignment (
          org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at, duty_role,
          status, source, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, 'CHIEF', 'ACCEPTED', 'MANUAL', ?, ?)
        """, ORG_ID, revisionId, requirementId, teacherId, Timestamp.from(start), Timestamp.from(end),
        TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update(
        "update exam_plan_revision set status='PUBLISHED', published_at=now() where id=?", revisionId);
    jdbcTemplate.update("update exam_plan set status='PUBLISHED' where id=?", planId);
    return new PublishedBaseline(planId, revisionId);
  }

  private UUID seedSamePlanPublishedBaselineDuty(
      UUID planId,
      UUID teacherId,
      Instant start,
      Instant end
  ) {
    UUID revisionId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID requirementId = UUID.randomUUID();
    int revisionNo = jdbcTemplate.queryForObject(
        "select coalesce(max(revision_no),0)+1 from exam_plan_revision where org_id=? and plan_id=?",
        Integer.class, ORG_ID, planId);
    jdbcTemplate.update("""
        insert into exam_plan_revision (
          id, org_id, plan_id, revision_no, status, source_type, title, created_by, updated_by
        ) values (?, ?, ?, ?, 'DRAFT', 'SUCCESSOR', 'Same-plan published baseline', ?, ?)
        """, revisionId, ORG_ID, planId, revisionNo, TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_session (
          id, org_id, revision_id, session_code, name, starts_at, ends_at, candidate_count,
          created_by, updated_by
        ) values (?, ?, ?, ?, 'Same-plan baseline session', ?, ?, 0, ?, ?)
        """, sessionId, ORG_ID, revisionId, "SAME-" + sessionId.toString().substring(0, 8),
        Timestamp.from(start), Timestamp.from(end), TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement (
          id, org_id, revision_id, session_id, duty_role, required_count, qualification,
          created_by, updated_by
        ) values (?, ?, ?, ?, 'CHIEF', 1,
          '{"requiredQualifications":["INVIGILATOR","CHIEF"]}'::jsonb, ?, ?)
        """, requirementId, ORG_ID, revisionId, sessionId, TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update("""
        insert into exam_plan_duty_assignment (
          org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at, duty_role,
          status, source, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, 'CHIEF', 'ACCEPTED', 'MANUAL', ?, ?)
        """, ORG_ID, revisionId, requirementId, teacherId, Timestamp.from(start), Timestamp.from(end),
        TENANT_ADMIN, TENANT_ADMIN);
    jdbcTemplate.update(
        "update exam_plan_revision set status='PUBLISHED', published_at=now() where id=?", revisionId);
    return revisionId;
  }

  private record PublishedBaseline(UUID planId, UUID revisionId) {
  }

  private void seedForeignPlan() {
    foreignOrgId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into organization (id, name, slug, type, plan, status)
        values (?, 'Other School', ?, 'school', 'trial', 'active')
        """, foreignOrgId, "other-" + foreignOrgId.toString().substring(0, 8));
    jdbcTemplate.update("""
        insert into exam_plan (org_id, plan_no, name, status)
        values (?, 'FOREIGN-1', 'Foreign plan', 'DRAFT')
        """, foreignOrgId);
  }

  private void seedTeacherUser() {
    teacherUserId = UUID.randomUUID();
    linkedTeacherId = jdbcTemplate.queryForObject(
        "select id from teacher where org_id=? and status='active' order by teacher_no limit 1", UUID.class, ORG_ID);
    teacherUsername = "teacher.examops." + teacherUserId.toString().substring(0, 8);
    jdbcTemplate.update("""
        insert into app_user (
          id, org_id, username, display_name, email, password_hash, role, status,
          must_change_password, onboarded
        )
        select ?, org_id, ?, 'ExamOps Self Service Teacher', ?, password_hash,
               'TEACHER_QUERY', 'active', false, true
        from app_user where org_id=? and username='jwc.demo'
        """, teacherUserId, teacherUsername, "teacher.examops." + teacherUserId + "@example.test", ORG_ID);
    jdbcTemplate.update("""
        insert into user_role (user_id, role_id, org_id)
        select ?, id, org_id from role where org_id=? and code='TEACHER_QUERY'
        """, teacherUserId, ORG_ID);
    jdbcTemplate.update("update teacher set app_user_id=? where org_id=? and id=?",
        teacherUserId, ORG_ID, linkedTeacherId);
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), CaptchaResponse.class);
    MvcResult result = mockMvc.perform(post(com.jkxt.support.TestPortal.loginPath(username))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","password":"%s","captchaId":"%s","captcha":"%s"}
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk()).andReturn();
    return com.jkxt.support.TestPortal.sessionCookie(result.getResponse());
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
