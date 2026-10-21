package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.examops.service.PublishedResourceBaseline;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * Current substitution ownership must be real, not just a RESOLVED case or delivery receipt.
 * Uses the real HTTP/security/service/PostgreSQL path and actual published-resource loader.
 * Fixtures are synthetic and rolled back; immutable published rows are never rewritten.
 * No email/SMS dispatch, existing browser cookie, mocked service, or copied ownership algorithm.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubstitutionOwnershipIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant START = Instant.parse("2042-07-01T00:00:00Z");
  private static final Instant END = START.plusSeconds(7200);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired PublishedResourceBaseline publishedBaseline;

  private TeacherAccount original;
  private TeacherAccount replacement;
  private UUID planId;
  private UUID revisionId;
  private UUID assignmentId;
  private String immutableAssignment;
  private String immutableRevision;

  @BeforeEach
  void seedPublishedPendingDuty() {
    original = teacher("original");
    replacement = teacher("replacement");
    planId = UUID.randomUUID();
    revisionId = UUID.randomUUID();
    assignmentId = UUID.randomUUID();
    UUID session = UUID.randomUUID();
    UUID requirement = UUID.randomUUID();
    jdbc.update("""
        insert into exam_plan (id,org_id,plan_no,name,status)
        values (?,?,?,'Synthetic substitution ownership','DRAFT')
        """, planId, ORG, "SUB-" + planId);
    jdbc.update("""
        insert into exam_plan_revision (id,org_id,plan_id,revision_no,status,source_type)
        values (?,?,?,1,'DRAFT','API')
        """, revisionId, ORG, planId);
    jdbc.update("""
        insert into exam_plan_session
          (id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
        values (?,?,?,?,'Synthetic substitution session',?,?,0)
        """, session, ORG, revisionId, "SUB-" + session,
        Timestamp.from(START), Timestamp.from(END));
    jdbc.update("""
        insert into exam_plan_duty_requirement
          (id,org_id,revision_id,session_id,duty_role,required_count,qualification)
        values (?,?,?,?,'INVIGILATOR',1,'{"requiredQualifications":["INVIGILATOR"]}'::jsonb)
        """, requirement, ORG, revisionId, session);
    jdbc.update("""
        insert into exam_plan_duty_assignment
          (id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status)
        values (?,?,?,?,?,?,?,'INVIGILATOR','PENDING_CONFIRMATION')
        """, assignmentId, ORG, revisionId, requirement, original.teacherId(),
        Timestamp.from(START), Timestamp.from(END));
    // This is fixture construction, not a claim that the approval/publication workflow ran.
    jdbc.update("update exam_plan_revision set status='PUBLISHED',published_at=now() where org_id=? and id=?",
        ORG, revisionId);
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where org_id=? and id=?",
        revisionId, ORG, planId);
    immutableAssignment = storedAssignment();
    immutableRevision = storedRevision();
  }

  @Test
  void resolvedSubstituteSeesTheDutyAsPendingWithoutInheritingTheOriginalRejection() throws Exception {
    Transfer transfer = rejectAndResolve();
    assertPublishedGraphUnchanged();

    JsonNode replacementDuties = ownDuties(transfer.replacementCookie());
    assertThat(replacementDuties.findValuesAsText("id"))
        .as("B must actually receive the resolved duty, not only a notification")
        .contains(assignmentId.toString());
    JsonNode received = duty(replacementDuties, assignmentId);
    assertThat(received.path("teacher_id").asText()).isEqualTo(replacement.teacherId().toString());
    assertThat(received.path("teacher_no").asText()).isEqualTo(replacement.teacherNo());
    assertThat(received.path("status").asText())
        .as("B has not accepted; A's REJECTED response must not be inherited")
        .isEqualTo("PENDING_CONFIRMATION");
    List<JsonNode> stillOwnedByOriginal = new java.util.ArrayList<>();
    ownDuties(transfer.originalCookie()).forEach(row -> {
      if (assignmentId.toString().equals(row.path("id").asText())
          && !"REASSIGNED".equals(row.path("status").asText())) stillOwnedByOriginal.add(row);
    });
    assertThat(stillOwnedByOriginal).as("A may see history, not a current actionable duty").isEmpty();
    assertPublishedGraphUnchanged();
  }

  @Test
  void resolvedSubstituteCanActuallyAcceptThroughItsOwnAuthenticatedEndpoint() throws Exception {
    Transfer transfer = rejectAndResolve();
    assertPublishedGraphUnchanged();

    mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/accept", assignmentId)
            .cookie(transfer.replacementCookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignmentId").value(assignmentId.toString()))
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.replayed").value(false));
    JsonNode accepted = duty(ownDuties(transfer.replacementCookie()), assignmentId);
    assertThat(accepted.path("teacher_id").asText()).isEqualTo(replacement.teacherId().toString());
    assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(jdbc.queryForObject("""
        select count(*) from exam_plan_audit_event
        where org_id=? and revision_id=? and actor_id=? and action='DUTY_ACCEPTED'
          and after_state->>'assignmentId'=?
        """, Integer.class, ORG, revisionId, replacement.userId(), assignmentId.toString())).isOne();
    assertPublishedGraphUnchanged();
  }

  @Test
  void publishedResourceBaselineReservesTheReplacementRatherThanLosingOrRetainingOriginalOccupancy()
      throws Exception {
    rejectAndResolve();
    assertPublishedGraphUnchanged();

    var snapshot = publishedBaseline.load(ORG, null, null);
    var assignedOccupancy = snapshot.teacherDuties().stream()
        .filter(value -> assignmentId.equals(value.assignmentId())).toList();
    assertThat(assignedOccupancy)
        .as("The original rejection must not erase the replacement's published reservation")
        .hasSize(1);
    var current = assignedOccupancy.getFirst();
    assertThat(current.teacherId()).isEqualTo(replacement.teacherId()).isNotEqualTo(original.teacherId());
    assertThat(current.startsAt()).isEqualTo(START);
    assertThat(current.endsAt()).isEqualTo(END);
    assertThat(snapshot.teacherDuties().stream()
        .filter(value -> original.teacherId().equals(value.teacherId())).toList())
        .as("A has no other fixture duties and must not remain reserved").isEmpty();
    assertPublishedGraphUnchanged();
  }

  private Transfer rejectAndResolve() throws Exception {
    Cookie originalCookie = login(original.username(), "Demo@2025");
    Cookie replacementCookie = login(replacement.username(), "Demo@2025");
    Cookie managerCookie = login("tenant.test", "Tenant001!!!");
    assertThat(ownDuties(originalCookie).findValuesAsText("id")).contains(assignmentId.toString());
    assertThat(ownDuties(replacementCookie).findValuesAsText("id")).doesNotContain(assignmentId.toString());

    JsonNode rejected = json(mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", assignmentId)
            .cookie(originalCookie).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Synthetic absence; require a real substitute\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.substitutionCaseId").isNotEmpty()).andReturn());
    UUID caseId = UUID.fromString(rejected.path("substitutionCaseId").asText());
    mvc.perform(post("/api/v1/substitution-cases/{id}/resolve", caseId)
            .cookie(managerCookie).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("replacementTeacherId", replacement.teacherId(),
                "reason", "Synthetic eligible substitute B"))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.replacementTeacherId").value(replacement.teacherId().toString()));
    assertThat(jdbc.queryForMap("""
        select status,replacement_teacher_id from exam_plan_substitution_case
        where org_id=? and id=? and duty_assignment_id=?
        """, ORG, caseId, assignmentId))
        .containsEntry("status", "RESOLVED").containsEntry("replacement_teacher_id", replacement.teacherId());
    assertPublishedGraphUnchanged();
    return new Transfer(originalCookie, replacementCookie);
  }

  private TeacherAccount teacher(String label) {
    UUID user = UUID.randomUUID(), teacher = UUID.randomUUID();
    String username = "sub." + label + "." + user.toString().substring(0, 8);
    String teacherNo = "SUB-" + teacher.toString().substring(0, 12);
    assertThat(jdbc.update("""
        insert into app_user
          (id,org_id,username,display_name,email,password_hash,role,status,must_change_password,onboarded)
        select ?,org_id,?,'Synthetic substitute account',?,password_hash,'TEACHER_QUERY','active',false,true
        from app_user where org_id=? and username='jwc.demo'
        """, user, username, username + "@example.test", ORG)).isOne();
    assertThat(jdbc.update("""
        insert into user_role (user_id,role_id,org_id)
        select ?,id,org_id from role where org_id=? and code='TEACHER_QUERY'
        """, user, ORG)).isOne();
    jdbc.update("""
        insert into teacher
          (id,org_id,teacher_no,name,gender,email,status,qualifications,max_daily_sessions,app_user_id)
        values (?,?,?,'Synthetic substitute teacher','unknown',?,'active','["INVIGILATOR"]'::jsonb,4,?)
        """, teacher, ORG, teacherNo, teacherNo + "@example.test", user);
    return new TeacherAccount(user, teacher, username, teacherNo);
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = mapper.readValue(mvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8),
        CaptchaResponse.class);
    MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("username", username, "password", password,
                "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn();
    Cookie cookie = result.getResponse().getCookie("JKXT_SCHOOL_SESSION");
    assertThat(cookie).as("fresh session from test-profile captcha and real authentication").isNotNull();
    return cookie;
  }

  private JsonNode ownDuties(Cookie cookie) throws Exception {
    return json(mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(cookie))
        .andExpect(status().isOk()).andReturn());
  }

  private JsonNode duty(JsonNode rows, UUID id) {
    for (JsonNode row : rows) if (id.toString().equals(row.path("id").asText())) return row;
    throw new AssertionError("Missing operational duty " + id);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String storedAssignment() {
    return jdbc.queryForObject("select to_jsonb(a)::text from exam_plan_duty_assignment a where org_id=? and id=?",
        String.class, ORG, assignmentId);
  }

  private String storedRevision() {
    return jdbc.queryForObject("select to_jsonb(r)::text from exam_plan_revision r where org_id=? and id=?",
        String.class, ORG, revisionId);
  }

  private void assertPublishedGraphUnchanged() {
    assertThat(storedAssignment()).as("original published assignment remains immutable").isEqualTo(immutableAssignment);
    assertThat(storedRevision()).as("original published revision remains immutable").isEqualTo(immutableRevision);
  }

  @Test
  void substituteAcceptsAndChecksInExactlyOnceWhileFormerOwnerCannotWrite() throws Exception {
    Transfer transfer = rejectAndResolve();
    JsonNode accepted = action(transfer.replacementCookie(), assignmentId, "accept", null, 200);
    assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
    JsonNode checked = action(transfer.replacementCookie(), assignmentId, "check-in", null, 200);
    assertThat(checked.path("status").asText()).isEqualTo("CHECKED_IN");
    assertThat(checked.path("respondedAt")).isEqualTo(accepted.path("respondedAt"));
    assertThat(checked.path("checkedInAt").isNull()).isFalse();
    assertThat(checked.path("replayed").asBoolean()).isFalse();
    var beforeReplay = sideEffects(assignmentId, revisionId);
    JsonNode replay = action(transfer.replacementCookie(), assignmentId, "check-in", null, 200);
    assertThat(replay.path("replayed").asBoolean()).isTrue();
    assertThat(replay.path("checkedInAt")).isEqualTo(checked.path("checkedInAt"));
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(beforeReplay);
    for (String operation : List.of("accept", "reject", "check-in")) {
      action(transfer.originalCookie(), assignmentId, operation, null, 404);
    }
    assertThat(sideEffects(assignmentId, revisionId)).as("former owner cannot replay or mutate old response").isEqualTo(beforeReplay);
    assertEventCount("DUTY_ACCEPTED", replacement.userId(), 1);
    assertEventCount("DUTY_CHECKED_IN", replacement.userId(), 1);
    assertThat(duty(ownDuties(transfer.replacementCookie()), assignmentId).path("status").asText()).isEqualTo("CHECKED_IN");
    assertPublishedGraphUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"THIRD_TEACHER", "ORIGINAL_RETURNS"})
  void aSecondSubstitutionStartsANewEngagementWithoutInheritedResponse(String destination) throws Exception {
    Transfer transfer = rejectAndResolve();
    UUID firstCase = jdbc.queryForObject("select id from exam_plan_substitution_case where org_id=? and duty_assignment_id=?",
        UUID.class, ORG, assignmentId);
    JsonNode declined = action(transfer.replacementCookie(), assignmentId, "reject", null, 200);
    UUID secondCase = UUID.fromString(declined.path("substitutionCaseId").asText());
    assertThat(secondCase).as("each rejected engagement retains its own case history").isNotEqualTo(firstCase);
    TeacherAccount next = destination.equals("ORIGINAL_RETURNS") ? original : teacher("third");
    Cookie manager = login("tenant.test", "Tenant001!!!");
    resolve(manager, secondCase, next.teacherId(), 200);
    Cookie nextCookie = destination.equals("ORIGINAL_RETURNS") ? transfer.originalCookie() : login(next.username(), "Demo@2025");
    JsonNode current = duty(ownDuties(nextCookie), assignmentId);
    assertThat(current.path("teacher_id").asText()).isEqualTo(next.teacherId().toString());
    assertThat(current.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(current.path("reason").isNull() || current.path("reason").isMissingNode()).isTrue();
    assertThat(current.path("responded_at").isNull() || current.path("responded_at").isMissingNode()).isTrue();
    assertThat(current.path("checked_in_at").isNull() || current.path("checked_in_at").isMissingNode()).isTrue();
    assertThat(current.path("engagementNo").asLong(-1)).isEqualTo(2L);
    var pendingState = sideEffects(assignmentId, revisionId);
    action(transfer.replacementCookie(), assignmentId, "accept", null, 404);
    if (destination.equals("ORIGINAL_RETURNS")) {
      action(nextCookie, assignmentId, "accept", null, 409);
      action(nextCookie, assignmentId, "accept", 0L, 409);
    }
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(pendingState);
    JsonNode accepted = action(nextCookie, assignmentId, "accept", current.path("engagementNo").asLong(), 200);
    assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(accepted.path("replayed").asBoolean()).isFalse();
    assertEventCount("DUTY_REJECTED", original.userId(), 1);
    assertEventCount("DUTY_REJECTED", replacement.userId(), 1);
    assertEventCount("DUTY_ACCEPTED", next.userId(), 1);
    assertThat(jdbc.queryForObject("select status from exam_plan_substitution_case where org_id=? and id=?",
        String.class, ORG, firstCase)).isEqualTo("RESOLVED");
    assertThat(jdbc.queryForObject("select teacher_id from exam_plan_substitution_case where org_id=? and id=?",
        UUID.class, ORG, secondCase)).isEqualTo(replacement.teacherId());
    var occupancy = publishedBaseline.load(ORG, null, null).teacherDuties().stream()
        .filter(row -> assignmentId.equals(row.assignmentId())).toList();
    assertThat(occupancy).hasSize(1);
    assertThat(occupancy.getFirst().teacherId()).isEqualTo(next.teacherId());
    assertPublishedGraphUnchanged();
  }

  @Test
  void identicalResolutionIsIdempotentAndAnotherReplacementCannotRewriteIt() throws Exception {
    UUID caseId = rejectOwn(original, assignmentId);
    Cookie manager = login("tenant.test", "Tenant001!!!");
    JsonNode first = resolve(manager, caseId, replacement.teacherId(), 200);
    var once = sideEffects(assignmentId, revisionId);
    JsonNode duplicate = resolve(manager, caseId, replacement.teacherId(), 200);
    assertThat(duplicate.path("version")).isEqualTo(first.path("version"));
    assertThat(duplicate.path("resolvedAt")).isEqualTo(first.path("resolvedAt"));
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(once);
    TeacherAccount third = teacher("unapproved-next");
    resolve(manager, caseId, third.teacherId(), 409);
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(once);
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_audit_event where org_id=? and revision_id=? and action='SUBSTITUTION_RESOLVED'",
        Integer.class, ORG, revisionId)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where org_id=? and revision_id=? and event_type='SUBSTITUTION_RESOLVED'",
        Integer.class, ORG, revisionId)).isOne();
    assertPublishedGraphUnchanged();
  }

  @Test
  void crossTenantManagersAndTeachersCannotResolveOrReceiveThisDuty() throws Exception {
    UUID caseId = rejectOwn(original, assignmentId);
    UUID foreignOrg = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Synthetic foreign school',?)", foreignOrg, "sub-" + foreignOrg);
    TeacherAccount foreign = foreignTeacher(foreignOrg);
    Cookie foreignManager = login(foreign.username(), "Demo@2025");
    Cookie manager = login("tenant.test", "Tenant001!!!");
    var before = sideEffects(assignmentId, revisionId);
    resolve(foreignManager, caseId, replacement.teacherId(), 404);
    resolve(manager, caseId, foreign.teacherId(), 404);
    resolve(login(replacement.username(), "Demo@2025"), caseId, replacement.teacherId(), 403);
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(before);
    assertPublishedGraphUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"OPEN", "RESOLVED"})
  void supersededRevisionCannotBeResolvedOrReplayed(String state) throws Exception {
    UUID caseId = rejectOwn(original, assignmentId);
    Cookie manager = login("tenant.test", "Tenant001!!!");
    if (state.equals("RESOLVED")) resolve(manager, caseId, replacement.teacherId(), 200);
    UUID successor = UUID.randomUUID();
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type) values (?,?,?,2,'PUBLISHED','API')",
        successor, ORG, planId);
    jdbc.update("update exam_plan set active_revision_id=? where org_id=? and id=?", successor, ORG, planId);
    var before = sideEffects(assignmentId, revisionId);
    resolve(manager, caseId, replacement.teacherId(), 404);
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(before);
    assertThat(ownDuties(login(replacement.username(), "Demo@2025")).findValuesAsText("id")).doesNotContain(assignmentId.toString());
    assertPublishedGraphUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"DISABLED", "NO_QUALIFICATION", "EXPIRED", "MASTER_LEAVE", "PARTIAL_WINDOW", "APPROVED_LEAVE"})
  void unavailableReplacementCannotResolveTheCaseOrProduceSideEffects(String condition) throws Exception {
    UUID caseId = rejectOwn(original, assignmentId);
    mutateReplacement(condition);
    var before = sideEffects(assignmentId, revisionId);
    int expectedStatus = List.of("DISABLED", "NO_QUALIFICATION", "EXPIRED").contains(condition) ? 422 : 409;
    resolve(login("tenant.test", "Tenant001!!!"), caseId, replacement.teacherId(), expectedStatus);
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(before);
    assertThat(ownDuties(login(replacement.username(), "Demo@2025")).findValuesAsText("id")).doesNotContain(assignmentId.toString());
    assertPublishedGraphUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"accept", "check-in"})
  void replacementEligibilityIsRecheckedBeforeARealAcknowledgement(String operation) throws Exception {
    Transfer transfer = rejectAndResolve();
    if (operation.equals("check-in")) action(transfer.replacementCookie(), assignmentId, "accept", null, 200);
    mutateReplacement("DISABLED");
    var before = sideEffects(assignmentId, revisionId);
    JsonNode response = action(transfer.replacementCookie(), assignmentId, operation, null, 409);
    assertThat(response.path("code").asText()).isEqualTo("DUTY_TEACHER_INACTIVE");
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(before);
    assertPublishedGraphUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"OVERLAP", "DAILY_LIMIT"})
  void reservedSubstituteCannotBeAssignedToAConflictingSecondCase(String conflict) throws Exception {
    rejectAndResolve();
    TeacherAccount other = teacher("other-owner");
    Instant nextStart = conflict.equals("OVERLAP") ? START : END;
    if (conflict.equals("DAILY_LIMIT")) jdbc.update("update teacher set max_daily_sessions=1 where id=?", replacement.teacherId());
    ExtraDuty next = additionalDuty(other, nextStart, nextStart.plusSeconds(7200));
    UUID secondCase = rejectOwn(other, next.assignment());
    var before = sideEffects(next.assignment(), next.revision());
    JsonNode denied = resolve(login("tenant.test", "Tenant001!!!"), secondCase, replacement.teacherId(), 409);
    assertThat(denied.path("code").asText()).isEqualTo(conflict.equals("OVERLAP") ? "SUBSTITUTION_TEACHER_CONFLICT" : "SUBSTITUTION_DAILY_LIMIT");
    assertThat(sideEffects(next.assignment(), next.revision())).isEqualTo(before);
    assertPublishedGraphUnchanged();
  }

  @Test
  void explicitStaleEngagementIsRejectedEvenForAFirstTimeReplacement() throws Exception {
    Transfer transfer = rejectAndResolve();
    var before = sideEffects(assignmentId, revisionId);
    action(transfer.replacementCookie(), assignmentId, "accept", 0L, 409);
    assertThat(sideEffects(assignmentId, revisionId)).isEqualTo(before);
    JsonNode current = duty(ownDuties(transfer.replacementCookie()), assignmentId);
    assertThat(current.path("engagementNo").asLong(-1)).isEqualTo(1L);
    assertThat(action(transfer.replacementCookie(), assignmentId, "accept", 1L, 200).path("replayed").asBoolean()).isFalse();
    assertPublishedGraphUnchanged();
  }

  private JsonNode action(Cookie cookie, UUID assignment, String operation, Long engagement, int expected) throws Exception {
    var request = post("/api/v1/auth/me/duty-assignments/{id}/" + operation, assignment).cookie(cookie);
    if (engagement != null) request.header("X-Duty-Engagement", engagement.toString());
    if (operation.equals("reject")) request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Synthetic subsequent absence\"}");
    return json(mvc.perform(request).andExpect(status().is(expected)).andReturn());
  }

  private UUID rejectOwn(TeacherAccount account, UUID assignment) throws Exception {
    JsonNode response = action(login(account.username(), "Demo@2025"), assignment, "reject", null, 200);
    assertThat(response.path("status").asText()).isEqualTo("REJECTED");
    return UUID.fromString(response.path("substitutionCaseId").asText());
  }

  private JsonNode resolve(Cookie manager, UUID caseId, UUID teacher, int expected) throws Exception {
    return json(mvc.perform(post("/api/v1/substitution-cases/{id}/resolve", caseId).cookie(manager)
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("replacementTeacherId", teacher, "reason", "Synthetic verified replacement"))))
        .andExpect(status().is(expected)).andReturn());
  }

  private List<List<String>> sideEffects(UUID assignment, UUID revision) {
    return List.of(
        jdbc.queryForList("select to_jsonb(s)::text from exam_plan_substitution_case s where org_id=? and duty_assignment_id=? order by id", String.class, ORG, assignment),
        jdbc.queryForList("select to_jsonb(s)::text from exam_plan_duty_response s where org_id=? and assignment_id=? order by id", String.class, ORG, assignment),
        jdbc.queryForList("select to_jsonb(s)::text from exam_plan_audit_event s where org_id=? and revision_id=? order by id", String.class, ORG, revision),
        jdbc.queryForList("select to_jsonb(s)::text from exam_plan_outbox s where org_id=? and revision_id=? order by id", String.class, ORG, revision),
        jdbc.queryForList("select to_jsonb(d)::text from exam_plan_delivery d join exam_plan_outbox o on o.org_id=d.org_id and o.id=d.outbox_id where d.org_id=? and o.revision_id=? order by d.id", String.class, ORG, revision));
  }

  private void assertEventCount(String action, UUID actor, int expected) {
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_audit_event where org_id=? and revision_id=? and actor_id=? and action=? and after_state->>'assignmentId'=?",
        Integer.class, ORG, revisionId, actor, action, assignmentId.toString())).isEqualTo(expected);
    assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where org_id=? and revision_id=? and created_by=? and event_type=? and payload->>'assignmentId'=?",
        Integer.class, ORG, revisionId, actor, action, assignmentId.toString())).isEqualTo(expected);
  }

  private void mutateReplacement(String condition) {
    switch (condition) {
      case "DISABLED" -> jdbc.update("update teacher set status='disabled' where id=?", replacement.teacherId());
      case "NO_QUALIFICATION" -> jdbc.update("update teacher set qualifications='[]'::jsonb where id=?", replacement.teacherId());
      case "EXPIRED" -> jdbc.update("update teacher set qualification_expiry='2042-06-30' where id=?", replacement.teacherId());
      case "MASTER_LEAVE" -> jdbc.update("update teacher set leave_windows=?::jsonb where id=?", windows(START, END), replacement.teacherId());
      case "PARTIAL_WINDOW" -> jdbc.update("update teacher set duty_windows=?::jsonb where id=?", windows(START, END.minusSeconds(1)), replacement.teacherId());
      case "APPROVED_LEAVE" -> jdbc.update("insert into exam_plan_leave_request(org_id,teacher_id,starts_at,ends_at,reason,status,requested_by,decided_at) values (?,?,?,?,'Synthetic approved absence','APPROVED',?,now())",
          ORG, replacement.teacherId(), Timestamp.from(START), Timestamp.from(END), replacement.userId());
      default -> throw new IllegalArgumentException(condition);
    }
  }

  private String windows(Instant start, Instant end) {
    return "[{\"start\":\"" + start + "\",\"end\":\"" + end + "\"}]";
  }

  private TeacherAccount foreignTeacher(UUID org) {
    UUID user = UUID.randomUUID(), teacher = UUID.randomUUID(), role = UUID.randomUUID();
    String username = "sub.foreign." + user.toString().substring(0, 8);
    String teacherNo = "SUB-F-" + teacher.toString().substring(0, 12);
    assertThat(jdbc.update("insert into role(id,org_id,code,name,permissions,system_role) select ?,?,'ADMIN','Synthetic foreign manager',permissions,true from role where org_id=? and code='ADMIN'", role, org, ORG)).isOne();
    assertThat(jdbc.update("insert into app_user(id,org_id,username,display_name,email,password_hash,role,status,must_change_password,onboarded) select ?,?,?,'Synthetic foreign manager',?,password_hash,'ADMIN','active',false,true from app_user where org_id=? and username='jwc.demo'",
        user, org, username, username + "@example.test", ORG)).isOne();
    jdbc.update("insert into user_role(user_id,role_id,org_id) values (?,?,?)", user, role, org);
    jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender,email,status,qualifications,max_daily_sessions,app_user_id) values (?,?,?,'Synthetic foreign teacher','unknown',?,'active','[\"INVIGILATOR\"]'::jsonb,4,?)",
        teacher, org, teacherNo, teacherNo + "@example.test", user);
    return new TeacherAccount(user, teacher, username, teacherNo);
  }

  private ExtraDuty additionalDuty(TeacherAccount owner, Instant start, Instant end) {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID(), requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name,status) values (?,?,?,'Synthetic other substitution','DRAFT')", plan, ORG, "SUB-" + plan);
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type) values (?,?,?,1,'DRAFT','API')", revision, ORG, plan);
    jdbc.update("insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count) values (?,?,?,?,'Synthetic other duty',?,?,0)", session, ORG, revision, "SUB-" + session, Timestamp.from(start), Timestamp.from(end));
    jdbc.update("insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count,qualification) values (?,?,?,?,'INVIGILATOR',1,'{\"requiredQualifications\":[\"INVIGILATOR\"]}'::jsonb)", requirement, ORG, revision, session);
    jdbc.update("insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status) values (?,?,?,?,?,?,?,'INVIGILATOR','PENDING_CONFIRMATION')", assignment, ORG, revision, requirement, owner.teacherId(), Timestamp.from(start), Timestamp.from(end));
    jdbc.update("update exam_plan_revision set status='PUBLISHED',published_at=now() where id=?", revision);
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where id=?", revision, plan);
    return new ExtraDuty(plan, revision, assignment);
  }

  private record ExtraDuty(UUID plan, UUID revision, UUID assignment) {}
  private record TeacherAccount(UUID userId, UUID teacherId, String username, String teacherNo) {}
  private record Transfer(Cookie originalCookie, Cookie replacementCookie) {}
}
