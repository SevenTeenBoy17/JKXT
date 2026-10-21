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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExamOperationsExecutionIntegrationTest {

  private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  private UUID planId;
  private UUID modifiedClassroomId;
  private String originalClassroomFeatures;
  private List<Map<String, Object>> qualificationsBeforeTest;

  @AfterEach
  void cleanup() {
    if (modifiedClassroomId != null && originalClassroomFeatures != null) {
      jdbcTemplate.update("update classroom set features=?::jsonb where id=?",
          originalClassroomFeatures, modifiedClassroomId);
    }
    if (qualificationsBeforeTest != null) {
      for (Map<String, Object> teacher : qualificationsBeforeTest) {
        jdbcTemplate.update("update teacher set qualifications=?::jsonb where id=?",
            teacher.get("qualifications"), teacher.get("id"));
      }
    }
    if (planId != null) {
      int immutable = jdbcTemplate.queryForObject("""
          select count(*) from exam_plan_revision where plan_id=?
            and status in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
          """, Integer.class, planId);
      if (immutable == 0) {
        jdbcTemplate.update("update exam_plan_revision set status='CANCELLED' where plan_id=?", planId);
        jdbcTemplate.update("delete from exam_plan where id=?", planId);
      }
    }
  }

  @Test
  void collectionPlanningDiagnosticsAndEmergencyArePersistedIdempotentAndTruthful() throws Exception {
    Cookie submitter = login("tenant.test", "Tenant001!!!");
    Cookie approver = login("admin", "Super001!!!");
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from teacher t
        join flyway_schema_history h on h.version='23' and h.success
        where t.created_at<=h.installed_on and t.qualifications ? 'INVIGILATOR'
          and t.qualifications ? 'CHIEF'
          and not exists (
            select 1 from exam_plan_duty_assignment a
            where a.org_id=t.org_id and a.teacher_id=t.id and upper(a.duty_role)='CHIEF'
          )
          -- V34 explicit grant: only the unmodified synthetic V5 demo seed rows (fixed ids, *@demo.local).
          and not (t.org_id='00000000-0000-0000-0000-000000000001'::uuid and t.email like '%@demo.local'
            and t.id in ('00000000-0000-0000-0000-000000004001','00000000-0000-0000-0000-000000004002',
              '00000000-0000-0000-0000-000000004003','00000000-0000-0000-0000-000000004004',
              '00000000-0000-0000-0000-000000004005'))
        """, Integer.class)).isZero();
    JsonNode plan = json(mockMvc.perform(post("/api/v1/exam-plans").cookie(submitter)
            .header("Idempotency-Key", "exec-plan-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Execution integration\"}"))
        .andExpect(status().isCreated()).andReturn());
    planId = UUID.fromString(plan.path("id").asText());
    UUID revisionId = UUID.fromString(plan.path("activeRevisionId").asText());
    Map<String, Object> classroom = jdbcTemplate.queryForMap(
        "select id, code, exam_capacity, features::text from classroom where org_id=? and status='active' order by code limit 1", ORG_ID);
    modifiedClassroomId = (UUID) classroom.get("id");
    originalClassroomFeatures = String.valueOf(classroom.get("features"));
    jdbcTemplate.update("update classroom set features=features || '[\"ACCESSIBLE\"]'::jsonb where id=?",
        modifiedClassroomId);
    String roomCode = String.valueOf(classroom.get("code"));
    int roomCapacity = ((Number) classroom.get("exam_capacity")).intValue();
    LocalDate isolatedDay = LocalDate.of(2050, 1, 1)
        .plusDays(Math.floorMod(planId.hashCode(), 20_000));
    OffsetDateTime start = isolatedDay.atTime(8, 0).atOffset(ZoneOffset.ofHours(8));
    OffsetDateTime end = start.plusHours(2);
    qualificationsBeforeTest = jdbcTemplate.queryForList(
        "select id, qualifications::text qualifications from teacher where org_id=? and status='active'",
        ORG_ID);
    List<UUID> explicitlyQualifiedChiefs = jdbcTemplate.queryForList(
        "select id from teacher where org_id=? and status='active' order by teacher_no limit 3",
        UUID.class, ORG_ID);
    assertThat(explicitlyQualifiedChiefs).hasSize(3);
    explicitlyQualifiedChiefs.forEach(teacherId -> jdbcTemplate.update(
        "update teacher set qualifications='[\"INVIGILATOR\",\"CHIEF\"]'::jsonb where id=?", teacherId));

    String sessionBatch = """
        {"items":[{"externalCode":"EXEC-001","name":"Math","subjectName":"Math","grade":"G1",
        "startsAt":"%s","endsAt":"%s","candidateCount":1,"requiredInvigilators":2,
        "requiredChiefInvigilators":1}]}
        """.formatted(start, end);
    JsonNode first = bulk(submitter, revisionId, "sessions", "exec-session", sessionBatch);
    JsonNode replay = bulk(submitter, revisionId, "sessions", "exec-session", sessionBatch);
    assertThat(replay.path("id").asText()).isEqualTo(first.path("id").asText());
    assertThat(replay.path("replayed").asBoolean()).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_session where revision_id=?", Integer.class, revisionId)).isEqualTo(1);

    bulk(submitter, revisionId, "rooms", "exec-room", """
        {"items":[{"sessionExternalCode":"EXEC-001","roomExternalCode":"%s",
        "startsAt":"%s","endsAt":"%s","plannedCapacity":%d,"allocatedCandidates":1}]}
        """.formatted(roomCode, start, end, roomCapacity));
    bulk(submitter, revisionId, "seats", "exec-seat", """
        {"items":[{"sessionExternalCode":"EXEC-001","roomExternalCode":"%s",
        "candidateExternalId":"ST-001","seatLabel":"01"}]}
        """.formatted(roomCode));

    jdbcTemplate.update("update exam_plan_revision set status='SOLVING' where org_id=? and id=?", ORG_ID, revisionId);
    JsonNode solvingWrite = json(mockMvc.perform(post(
            "/api/v1/plan-revisions/{revision}/sessions", revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-session-solving")
            .contentType(MediaType.APPLICATION_JSON).content(sessionBatch))
        .andExpect(status().isConflict()).andReturn());
    assertThat(solvingWrite.path("code").asText()).isEqualTo("REVISION_SOLVING");
    jdbcTemplate.update("update exam_plan_revision set status='DRAFT' where org_id=? and id=?", ORG_ID, revisionId);

    JsonNode feasibleDiagnostic = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/diagnostic-cases", planId, revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-diag")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"disableRoomFeature\":\"ACCESSIBLE\"}"))
        .andExpect(status().isOk()).andReturn());
    assertThat(feasibleDiagnostic.path("status").asText()).isEqualTo("FEASIBLE");
    assertThat(feasibleDiagnostic.path("issues").size()).isZero();
    jdbcTemplate.update("""
        insert into exam_plan_constraint (
          org_id, revision_id, constraint_type, severity, resource_type, resource_id, expression
        ) values (?, ?, 'SPECIAL_NEED', 'HARD', 'candidate', 'ST-001',
          '{"requiredRoomFeature":"ACCESSIBLE"}'::jsonb)
        """, ORG_ID, revisionId);

    // JOINT is supported by the current planner; exercise an actually invalid mode.
    JsonNode unsupportedMode = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/planning-jobs", planId, revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-job-unsupported")
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"UNKNOWN_MODE\",\"seed\":42}"))
        .andExpect(status().isUnprocessableEntity()).andReturn());
    assertThat(unsupportedMode.path("code").asText()).isEqualTo("PLANNING_MODE_UNSUPPORTED");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_planning_job where revision_id=?", Integer.class, revisionId)).isZero();

    UUID blockingJob = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_planning_job (
          id, org_id, plan_id, revision_id, status, stage, progress, request
        ) values (?, ?, ?, ?, 'QUEUED', 'QUEUED', 0, '{}'::jsonb)
        """, blockingJob, ORG_ID, planId, revisionId);
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/planning-jobs", planId, revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-job-concurrent")
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"INVIGILATOR_ONLY\"}"))
        .andExpect(status().isConflict());
    assertThatThrownBy(() -> jdbcTemplate.update("""
        insert into exam_plan_planning_job (
          id, org_id, plan_id, revision_id, status, stage, progress, request
        ) values (?, ?, ?, ?, 'RUNNING', 'SOLVING', 20, '{}'::jsonb)
        """, UUID.randomUUID(), ORG_ID, planId, revisionId))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbcTemplate.update("delete from exam_plan_planning_job where id=?", blockingJob);

    UUID customRequirement = UUID.randomUUID();
    Map<String, Object> customRequirementTarget = jdbcTemplate.queryForMap("""
        select s.id session_id, r.id room_assignment_id
        from exam_plan_session s join exam_plan_room_assignment r
          on r.org_id=s.org_id and r.revision_id=s.revision_id and r.session_id=s.id
        where s.org_id=? and s.revision_id=? limit 1
        """, ORG_ID, revisionId);
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement (
          id, org_id, revision_id, session_id, room_assignment_id, duty_role, required_count,
          qualification, created_by, updated_by
        ) values (?, ?, ?, ?, ?, 'CHIEF', 1,
          '{"requiredPositions":["NO_SUCH_POSITION"]}'::jsonb, ?, ?)
        """, customRequirement, ORG_ID, revisionId, customRequirementTarget.get("session_id"),
        customRequirementTarget.get("room_assignment_id"),
        UUID.fromString("00000000-0000-0000-0000-000000000204"),
        UUID.fromString("00000000-0000-0000-0000-000000000204"));
    JsonNode customContractJob = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/planning-jobs", planId, revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-job-custom-qualification")
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"INVIGILATOR_ONLY\",\"seed\":42}"))
        .andExpect(status().isAccepted()).andReturn());
    JsonNode customContractTerminal = poll(
        submitter, UUID.fromString(customContractJob.path("jobId").asText()));
    assertThat(customContractTerminal.path("status").asText()).isEqualTo("INFEASIBLE");
    assertThat(customContractTerminal.path("diagnostics").findValuesAsText("code"))
        .contains("DUTY_QUALIFICATION_SHORTAGE");
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_duty_requirement
        where id=? and qualification='{"requiredPositions":["NO_SUCH_POSITION"]}'::jsonb
        """, Integer.class, customRequirement)).isOne();
    jdbcTemplate.update("delete from exam_plan_duty_requirement where id=?", customRequirement);

    List<Map<String, Object>> planningQualifications = jdbcTemplate.queryForList(
        "select id, qualifications::text qualifications from teacher where org_id=? and status='active'", ORG_ID);
    try {
      jdbcTemplate.update("""
          update teacher set qualifications='["INVIGILATOR"]'::jsonb
          where org_id=? and status='active'
          """, ORG_ID);
      JsonNode unqualifiedJob = json(mockMvc.perform(post(
              "/api/v1/exam-plans/{plan}/revisions/{revision}/planning-jobs", planId, revisionId)
              .cookie(submitter).header("Idempotency-Key", "exec-job-unqualified")
              .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"INVIGILATOR_ONLY\",\"seed\":42}"))
          .andExpect(status().isAccepted()).andReturn());
      JsonNode unqualifiedTerminal = poll(submitter, UUID.fromString(unqualifiedJob.path("jobId").asText()));
      assertThat(unqualifiedTerminal.path("status").asText()).isEqualTo("INFEASIBLE");
      assertThat(unqualifiedTerminal.path("diagnostics").findValuesAsText("code"))
          .contains("DUTY_QUALIFICATION_SHORTAGE");
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revisionId)).isZero();
    } finally {
      for (Map<String, Object> teacher : planningQualifications) {
        jdbcTemplate.update("update teacher set qualifications=?::jsonb where id=?",
            teacher.get("qualifications"), teacher.get("id"));
      }
    }

    JsonNode job = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/planning-jobs", planId, revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-job")
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"INVIGILATOR_ONLY\",\"seed\":42}"))
        .andExpect(status().isAccepted()).andReturn());
    UUID jobId = UUID.fromString(job.path("jobId").asText());
    JsonNode terminal = poll(submitter, jobId);
    assertThat(terminal.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, revisionId)).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_duty_requirement
        where revision_id=? and duty_role='CHIEF'
          and qualification @> '{"requiredQualifications":["INVIGILATOR","CHIEF"]}'::jsonb
        """, Integer.class, revisionId)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_duty_requirement
        where revision_id=? and duty_role='INVIGILATOR'
          and qualification @> '{"requiredQualifications":["INVIGILATOR"]}'::jsonb
        """, Integer.class, revisionId)).isEqualTo(1);

    JsonNode current = json(mockMvc.perform(get("/api/v1/exam-plans/{plan}/revisions/{revision}", planId, revisionId)
            .cookie(submitter)).andExpect(status().isOk()).andReturn());
    JsonNode validation = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/validate", planId, revisionId)
            .cookie(submitter).header("If-Match", current.path("revisionCursor").asText()))
        .andExpect(status().isOk()).andReturn());
    JsonNode ready = validation.path("revision");
    JsonNode pending = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/submit-approval", planId, revisionId)
            .cookie(submitter).header("If-Match", ready.path("revisionCursor").asText()))
        .andExpect(status().isOk()).andReturn());
    JsonNode approved = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/approve", planId, revisionId)
            .cookie(approver).header("If-Match", pending.path("revisionCursor").asText())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"integration\"}"))
        .andExpect(status().isOk()).andReturn());
    json(mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/publish", planId, revisionId)
            .cookie(submitter).header("If-Match", approved.path("revisionCursor").asText()))
        .andExpect(status().isOk()).andReturn());

    JsonNode diagnostic = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/diagnostic-cases", planId, revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-diag-affected")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"disableRoomFeature\":\"ACCESSIBLE\"}"))
        .andExpect(status().isUnprocessableEntity()).andReturn());
    assertThat(diagnostic.path("issues").get(0).path("code").asText())
        .isEqualTo("ACCESSIBLE_ROOM_CAPACITY_UNAVAILABLE");

    int revisionCountBeforeGap = jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_revision where org_id=? and plan_id=?", Integer.class, ORG_ID, planId);
    JsonNode capacityGap = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/emergency-changes", planId, revisionId)
            .cookie(submitter).header("Idempotency-Key", "exec-emergency-gap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"CANDIDATE_COUNT_INCREASE","payload":{"sessionCode":"EXEC-001","delta":%d}}
                """.formatted(roomCapacity)))
        .andExpect(status().isUnprocessableEntity()).andReturn());
    assertThat(capacityGap.path("code").asText()).isEqualTo("ROOM_CAPACITY_UNAVAILABLE");
    assertThat(capacityGap.path("message").asText()).contains("capacityGap=1");
    assertThat(capacityGap.path("errors").get(0).path("field").asText()).isEqualTo("capacityGap");
    assertThat(capacityGap.path("errors").get(0).path("reason").asText()).isEqualTo("1");
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_revision where org_id=? and plan_id=?", Integer.class, ORG_ID, planId))
        .isEqualTo(revisionCountBeforeGap);

    String teacherNo = jdbcTemplate.queryForObject("""
        select t.teacher_no from exam_plan_duty_assignment a
        join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
        where a.revision_id=? and a.duty_role='CHIEF' order by t.teacher_no limit 1
        """, String.class, revisionId);
    String emergencyBody = """
        {"type":"TEACHER_SICK_LEAVE","payload":{"teacherIds":["%s"],"effectiveAt":"%s"}}
        """.formatted(teacherNo, start);
    List<Map<String, Object>> originalQualifications = jdbcTemplate.queryForList(
        "select id, qualifications::text qualifications from teacher where org_id=? and status='active'", ORG_ID);
    int revisionsBeforeQualificationFailure = jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_revision where org_id=? and plan_id=?", Integer.class, ORG_ID, planId);
    try {
      jdbcTemplate.update("""
          update teacher set qualifications='["INVIGILATOR"]'::jsonb
          where org_id=? and status='active' and teacher_no<>?
          """, ORG_ID, teacherNo);
      mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/emergency-changes", planId, revisionId)
              .cookie(submitter).header("Idempotency-Key", "exec-emergency-unqualified")
              .contentType(MediaType.APPLICATION_JSON).content(emergencyBody))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("INVIGILATOR_SHORTAGE"));
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from exam_plan_revision where org_id=? and plan_id=?", Integer.class, ORG_ID, planId))
          .isEqualTo(revisionsBeforeQualificationFailure);
    } finally {
      for (Map<String, Object> teacher : originalQualifications) {
        jdbcTemplate.update("update teacher set qualifications=?::jsonb where id=?",
            teacher.get("qualifications"), teacher.get("id"));
      }
    }
    JsonNode emergency = emergency(submitter, revisionId, "exec-emergency", emergencyBody);
    JsonNode emergencyReplay = emergency(submitter, revisionId, "exec-emergency", emergencyBody);
    assertThat(emergencyReplay.path("id").asText()).isEqualTo(emergency.path("id").asText());
    assertThat(emergencyReplay.path("replayed").asBoolean()).isTrue();
    UUID successor = UUID.fromString(emergency.path("successorRevisionId").asText());
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_duty_assignment where revision_id=?", Integer.class, successor)).isEqualTo(2);
    JsonNode successorPlanningJob = json(mockMvc.perform(post(
            "/api/v1/exam-plans/{plan}/revisions/{revision}/planning-jobs", planId, successor)
            .cookie(submitter).header("Idempotency-Key", "exec-successor-job")
            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"INVIGILATOR_ONLY\",\"seed\":42}"))
        .andExpect(status().isAccepted()).andReturn());
    JsonNode successorPlanningTerminal = poll(
        submitter, UUID.fromString(successorPlanningJob.path("jobId").asText()));
    assertThat(successorPlanningTerminal.path("status").asText()).isEqualTo("SUCCEEDED");
    JsonNode successorView = json(mockMvc.perform(get(
            "/api/v1/exam-plans/{plan}/revisions/{revision}", planId, successor).cookie(submitter))
        .andExpect(status().isOk()).andReturn());
    mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/validate", planId, successor)
            .cookie(submitter).header("If-Match", successorView.path("revisionCursor").asText()))
        .andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject("select status from exam_plan where id=?", String.class, planId))
        .isEqualTo("PUBLISHED");
  }

  private JsonNode bulk(Cookie cookie, UUID revisionId, String kind, String key, String body) throws Exception {
    return json(mockMvc.perform(post("/api/v1/plan-revisions/{revision}/{kind}", revisionId, kind)
            .cookie(cookie).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn());
  }

  private JsonNode emergency(Cookie cookie, UUID revisionId, String key, String body) throws Exception {
    return json(mockMvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/emergency-changes", planId, revisionId)
            .cookie(cookie).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn());
  }

  private JsonNode poll(Cookie cookie, UUID jobId) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      JsonNode value = json(mockMvc.perform(get("/api/v1/planning-jobs/{id}", jobId).cookie(cookie))
          .andExpect(status().isOk()).andReturn());
      if (Set.of("SUCCEEDED", "INFEASIBLE", "FAILED", "CANCELLED").contains(value.path("status").asText())) return value;
      Thread.sleep(100);
    }
    throw new AssertionError("Planning job did not reach a terminal state");
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
