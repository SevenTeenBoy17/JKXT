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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class ExamOperationsClosedLoopIntegrationTest {

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID actorId;
  private UUID teacherUserId;
  private UUID teacherId;
  private UUID replacementTeacherId;
  private String teacherNo;
  private String teacherUsername;
  private UUID planId;
  private UUID revisionId;
  private UUID assignmentId;
  private Instant dutyStart;
  private Instant dutyEnd;

  @BeforeEach
  void seedOperationalPlan() {
    actorId = jdbcTemplate.queryForObject(
        "select id from app_user where org_id=? and username='tenant.test'", UUID.class, ORG_ID);
    teacherUserId = UUID.randomUUID();
    teacherId = UUID.randomUUID();
    replacementTeacherId = UUID.randomUUID();
    teacherNo = "CL-" + teacherId.toString().substring(0, 8);
    teacherUsername = "closed.loop." + teacherUserId.toString().substring(0, 8);
    String replacementNo = "CL-" + replacementTeacherId.toString().substring(0, 8);
    jdbcTemplate.update("""
        insert into app_user (
          id, org_id, username, display_name, email, password_hash, role, status,
          must_change_password, onboarded
        ) select ?, org_id, ?, 'Closed loop teacher', ?, password_hash,
            'TEACHER_QUERY', 'active', false, true
          from app_user where org_id=? and username='jwc.demo'
        """, teacherUserId, teacherUsername, teacherUsername + "@example.test", ORG_ID);
    jdbcTemplate.update("""
        insert into user_role (user_id, role_id, org_id)
        select ?, id, org_id from role where org_id=? and code='TEACHER_QUERY'
        """, teacherUserId, ORG_ID);
    jdbcTemplate.update("""
        insert into teacher (
          id, org_id, teacher_no, name, gender, email, status, qualifications,
          max_daily_sessions, app_user_id
        ) values (?, ?, ?, 'Closed Loop Original', 'unknown', ?, 'active',
          '["INVIGILATOR"]'::jsonb, 4, ?)
        """, teacherId, ORG_ID, teacherNo, teacherNo + "@example.test", teacherUserId);
    jdbcTemplate.update("""
        insert into teacher (
          id, org_id, teacher_no, name, gender, email, status, qualifications,
          max_daily_sessions
        ) values (?, ?, ?, 'Closed Loop Replacement', 'unknown', ?, 'active',
          '["INVIGILATOR","CHIEF"]'::jsonb, 4)
        """, replacementTeacherId, ORG_ID, replacementNo, replacementNo + "@example.test");

    planId = UUID.randomUUID();
    revisionId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID requirementId = UUID.randomUUID();
    assignmentId = UUID.randomUUID();
    LocalDate day = LocalDate.of(2070, 1, 1).plusDays(Math.floorMod(planId.hashCode(), 10_000));
    dutyStart = day.atTime(8, 0).toInstant(ZoneOffset.ofHours(8));
    dutyEnd = dutyStart.plusSeconds(7_200);
    jdbcTemplate.update("""
        insert into exam_plan (id, org_id, plan_no, name, status, created_by, updated_by)
        values (?, ?, ?, 'Closed loop integration', 'DRAFT', ?, ?)
        """, planId, ORG_ID, "CLOSED-" + planId.toString().substring(0, 8), actorId, actorId);
    jdbcTemplate.update("""
        insert into exam_plan_revision (
          id, org_id, plan_id, revision_no, status, source_type, title, published_at,
          created_by, updated_by
        ) values (?, ?, ?, 1, 'DRAFT', 'API', 'Closed loop integration', now(), ?, ?)
        """, revisionId, ORG_ID, planId, actorId, actorId);
    jdbcTemplate.update("update exam_plan set active_revision_id=? where id=?", revisionId, planId);
    jdbcTemplate.update("""
        insert into exam_plan_session (
          id, org_id, revision_id, session_code, name, starts_at, ends_at,
          candidate_count, created_by, updated_by
        ) values (?, ?, ?, ?, 'Closed loop session', ?, ?, 0, ?, ?)
        """, sessionId, ORG_ID, revisionId, "CL-" + sessionId.toString().substring(0, 8),
        Timestamp.from(dutyStart), Timestamp.from(dutyEnd), actorId, actorId);
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement (
          id, org_id, revision_id, session_id, duty_role, required_count, qualification,
          created_by, updated_by
        ) values (?, ?, ?, ?, 'INVIGILATOR', 1,
          '{"requiredQualifications":["INVIGILATOR"]}'::jsonb, ?, ?)
        """, requirementId, ORG_ID, revisionId, sessionId, actorId, actorId);
    jdbcTemplate.update("""
        insert into exam_plan_duty_assignment (
          id, org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at,
          duty_role, status, source, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, 'INVIGILATOR', 'ACCEPTED', 'MANUAL', ?, ?)
        """, assignmentId, ORG_ID, revisionId, requirementId, teacherId,
        Timestamp.from(dutyStart), Timestamp.from(dutyEnd), actorId, actorId);
    jdbcTemplate.update("update exam_plan_revision set status='PUBLISHED' where id=?", revisionId);
    jdbcTemplate.update("update exam_plan set status='PUBLISHED' where id=?", planId);
  }

  @Test
  void leaveSubstitutionLifecycleAndOperationalRecordsAreClosedLoopAndTenantSafe() throws Exception {
    Cookie manager = login("tenant.test", "Tenant001!!!");
    Cookie teacher = login(teacherUsername, "Demo@2025");
    String createBody = """
        {"teacherId":"%s","startsAt":"%s","endsAt":"%s","reason":"illness"}
        """.formatted(teacherId, dutyStart.minusSeconds(300), dutyEnd.plusSeconds(300));
    JsonNode leave = json(mockMvc.perform(post("/api/v1/leave-requests").cookie(manager)
            .header("Idempotency-Key", "leave-" + planId).contentType(MediaType.APPLICATION_JSON)
            .content(createBody))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andReturn());
    UUID leaveId = UUID.fromString(leave.path("id").asText());
    mockMvc.perform(post("/api/v1/leave-requests").cookie(manager)
            .header("Idempotency-Key", "leave-" + planId).contentType(MediaType.APPLICATION_JSON)
            .content(createBody))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(leaveId.toString()))
        .andExpect(jsonPath("$.replayed").value(true));
    mockMvc.perform(get("/api/v1/auth/me/leave-requests").cookie(teacher))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].teacherId").value(teacherId.toString()));
    mockMvc.perform(post("/api/v1/leave-requests").cookie(teacher)
            .header("Idempotency-Key", "forbidden-" + planId).contentType(MediaType.APPLICATION_JSON)
            .content(createBody))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    mockMvc.perform(post("/api/v1/leave-requests/{id}/decision", leaveId).cookie(manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.substitutionCaseCount").value(1));
    assertThat(jdbcTemplate.queryForMap(
        "select status, version from exam_plan_revision where id=?", revisionId))
        .containsEntry("status", "PUBLISHED").containsEntry("version", 0L);
    assertThat(jdbcTemplate.queryForObject(
        "select teacher_id from exam_plan_duty_assignment where id=?", UUID.class, assignmentId))
        .isEqualTo(teacherId);
    UUID substitutionId = jdbcTemplate.queryForObject(
        "select id from exam_plan_substitution_case where leave_request_id=?", UUID.class, leaveId);

    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/execute", planId, revisionId)
            .cookie(manager).header("If-Match", "0"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("OPERATIONS_NOT_READY"));
    mockMvc.perform(post("/api/v1/substitution-cases/{id}/resolve", substitutionId).cookie(manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"replacementTeacherId":"%s","reason":"qualified reserve"}
                """.formatted(replacementTeacherId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.replacementTeacherId").value(replacementTeacherId.toString()));
    assertThat(jdbcTemplate.queryForObject(
        "select teacher_id from exam_plan_duty_assignment where id=?", UUID.class, assignmentId))
        .isEqualTo(teacherId);
    mockMvc.perform(get("/api/v1/substitution-cases").cookie(teacher))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    UUID foreignLeave = seedForeignLeave();
    mockMvc.perform(post("/api/v1/leave-requests/{id}/decision", foreignLeave).cookie(manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"decision\":\"REJECTED\",\"reason\":\"foreign\"}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LEAVE_REQUEST_NOT_FOUND"));

    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/execute", planId, revisionId).cookie(manager))
        .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/execute", planId, revisionId)
            .cookie(manager).header("If-Match", "99"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));
    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/execute", planId, revisionId)
            .cookie(manager).header("If-Match", "0"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTING"))
        .andExpect(jsonPath("$.revisionCursor").value(1));

    JsonNode checklist = json(mockMvc.perform(post("/api/v1/plan-revisions/{r}/checklists", revisionId)
            .cookie(manager).contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemCode\":\"ROOM-SEAL\",\"label\":\"Seal rooms\",\"required\":true}"))
        .andExpect(status().isCreated()).andReturn());
    JsonNode incident = json(mockMvc.perform(post("/api/v1/plan-revisions/{r}/incidents", revisionId)
            .cookie(manager).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"incidentCode":"INC-1","incidentType":"ROOM","severity":"HIGH",
                 "summary":"Door seal broken","details":{"room":"R1"}}
                """))
        .andExpect(status().isCreated()).andReturn());
    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/close", planId, revisionId)
            .cookie(manager).header("If-Match", "1"))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OPERATIONS_NOT_READY"));
    mockMvc.perform(post("/api/v1/plan-revisions/{r}/incidents/{i}/resolve", revisionId,
            UUID.fromString(incident.path("id").asText())).cookie(manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\":\"Seal replaced\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
    mockMvc.perform(post("/api/v1/plan-revisions/{r}/checklists/{i}/complete", revisionId,
            UUID.fromString(checklist.path("id").asText())).cookie(manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"COMPLETED\",\"evidence\":{\"checked\":true}}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/close", planId, revisionId)
            .cookie(manager).header("If-Match", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.revisionCursor").value(2));

    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/archive", planId, revisionId)
            .cookie(manager).header("If-Match", "2"))
        .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OPERATIONS_NOT_READY"));
    UUID deliveryId = jdbcTemplate.queryForObject("""
        select d.id from exam_plan_delivery d join exam_plan_outbox o on o.id=d.outbox_id
        where d.org_id=? and o.revision_id=? and o.event_type='SUBSTITUTION_RESOLVED'
        """, UUID.class, ORG_ID, revisionId);
    jdbcTemplate.update("update exam_plan_delivery set next_attempt_at='1900-01-01' where id=?", deliveryId);
    mockMvc.perform(post("/api/v1/operations/notification-deliveries/dispatch").cookie(manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.delivered").value(1));
    mockMvc.perform(post("/api/v1/exam-plans/{p}/revisions/{r}/archive", planId, revisionId)
            .cookie(manager).header("If-Match", "2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));

    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_audit_event where org_id=? and revision_id=?
          and action in ('SUBSTITUTION_RESOLVED','REVISION_EXECUTING','INCIDENT_RESOLVED','REVISION_CLOSED')
        """, Integer.class, ORG_ID, revisionId)).isEqualTo(4);
  }

  @Test
  void dispatcherRetriesIdempotentlyAndOnlyRecipientCanRead() throws Exception {
    Cookie manager = login("tenant.test", "Tenant001!!!");
    Cookie teacher = login(teacherUsername, "Demo@2025");
    UUID outboxId = UUID.randomUUID();
    UUID deliveryId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_outbox (
          id, org_id, revision_id, event_type, aggregate_type, aggregate_id, payload,
          created_by, updated_by
        ) values (?, ?, ?, 'TEST_DELIVERY', 'exam_plan_revision', ?, '{}'::jsonb, ?, ?)
        """, outboxId, ORG_ID, revisionId, revisionId, actorId, actorId);
    jdbcTemplate.update("""
        insert into exam_plan_delivery (
          id, org_id, outbox_id, recipient_type, recipient_id, channel, status,
          next_attempt_at, created_by, updated_by
        ) values (?, ?, ?, 'TEACHER', ?, 'IN_APP', 'PENDING', '1900-01-01', ?, ?)
        """, deliveryId, ORG_ID, outboxId, teacherNo, actorId, actorId);

    mockMvc.perform(post("/api/v1/operations/notification-deliveries/dispatch").cookie(teacher)
            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(post("/api/v1/operations/notification-deliveries/dispatch").cookie(manager)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"limit":1,"includeDeferred":true,"failDeliveryIds":["%s"]}
                """.formatted(deliveryId)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(1));
    assertThat(jdbcTemplate.queryForMap(
        "select status, attempt_count from exam_plan_delivery where id=?", deliveryId))
        .containsEntry("status", "FAILED").containsEntry("attempt_count", 1);
    jdbcTemplate.update("update exam_plan_delivery set next_attempt_at='1900-01-01' where id=?", deliveryId);
    mockMvc.perform(post("/api/v1/operations/notification-deliveries/dispatch").cookie(manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.delivered").value(1));
    assertThat(jdbcTemplate.queryForMap("""
        select status, attempt_count, provider_message_id from exam_plan_delivery where id=?
        """, deliveryId)).containsEntry("status", "DELIVERED").containsEntry("attempt_count", 2)
        .containsEntry("provider_message_id", "local:" + deliveryId);

    mockMvc.perform(post("/api/v1/operations/notification-deliveries/dispatch").cookie(manager)
            .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
        .andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject(
        "select attempt_count from exam_plan_delivery where id=?", Integer.class, deliveryId)).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_delivery
        where org_id=? and outbox_id=? and recipient_type='TEACHER' and recipient_id=? and channel='IN_APP'
        """, Integer.class, ORG_ID, outboxId, teacherNo)).isOne();

    mockMvc.perform(get("/api/v1/auth/me/notification-deliveries").cookie(teacher))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='" + deliveryId + "')].status").value("DELIVERED"));
    mockMvc.perform(post("/api/v1/auth/me/notification-deliveries/{id}/read", deliveryId).cookie(teacher)
            .contentType(MediaType.APPLICATION_JSON).content("{\"receipt\":{\"client\":\"web\"}}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READ"))
        // PB-15 (round 9): the client receipt is kept apart from the dispatcher's provider proof.
        .andExpect(jsonPath("$.receipt.clientReceipt.client").value("web"))
        .andExpect(jsonPath("$.receipt.provider").value("LOCAL_DISPATCH"));
  }

  private UUID seedForeignLeave() {
    UUID orgId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID foreignTeacher = UUID.randomUUID();
    UUID leaveId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into organization (id, name, slug, type, plan, status)
        values (?, 'Closed Loop Foreign', ?, 'school', 'trial', 'active')
        """, orgId, "closed-loop-" + orgId.toString().substring(0, 8));
    jdbcTemplate.update("""
        insert into app_user (
          id, org_id, username, display_name, email, password_hash, role, status,
          must_change_password, onboarded
        ) select ?, ?, ?, 'Foreign actor', ?, password_hash, 'ADMIN', 'active', false, true
          from app_user where org_id=? and username='jwc.demo'
        """, userId, orgId, "foreign-" + userId.toString().substring(0, 8),
        "foreign-" + userId + "@example.test", ORG_ID);
    jdbcTemplate.update("""
        insert into teacher (id, org_id, teacher_no, name, gender, email, status)
        values (?, ?, 'FOREIGN-1', 'Foreign teacher', 'unknown', ?, 'active')
        """, foreignTeacher, orgId, "foreign-teacher-" + foreignTeacher + "@example.test");
    jdbcTemplate.update("""
        insert into exam_plan_leave_request (
          id, org_id, teacher_id, starts_at, ends_at, reason, requested_by, created_by, updated_by
        ) values (?, ?, ?, ?, ?, 'foreign', ?, ?, ?)
        """, leaveId, orgId, foreignTeacher, Timestamp.from(dutyStart), Timestamp.from(dutyEnd),
        userId, userId, userId);
    return leaveId;
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
    return result.getResponse().getCookie("JKXT_SCHOOL_SESSION");
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
