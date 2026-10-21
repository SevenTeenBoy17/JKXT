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

/**
 * Independent consumer contracts for a real replacement, not just a resolved case.
 * Real MVC authentication, authorization, services and isolated PostgreSQL; no mocked backend.
 * Synthetic initial published graph is fixture construction, not proof of the publication flow.
 * Every test rolls back; never reads a saved browser session or sends external notifications.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class SubstitutionConsumersIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant START = Instant.parse("2043-07-01T00:00:00Z");
  private static final Instant END = START.plusSeconds(7200);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  private Account original;
  private Account replacement;
  private UUID plan;
  private UUID revision;
  private UUID assignment;
  private UUID firstCase;
  private Cookie managerCookie;
  private Cookie replacementCookie;
  private String originalAssignment;
  private String originalRevision;

  @BeforeEach
  void createSyntheticPublishedGraph() throws Exception {
    original = teacher("original");
    replacement = teacher("replacement");
    plan = UUID.randomUUID();
    revision = UUID.randomUUID();
    assignment = UUID.randomUUID();
    UUID session = UUID.randomUUID(), requirement = UUID.randomUUID();
    jdbc.update("""
        insert into exam_plan(id,org_id,plan_no,name,status)
        values (?,?,?,'Synthetic substitution consumer plan','DRAFT')
        """, plan, ORG, "CONSUMER-" + plan);
    jdbc.update("""
        insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type)
        values (?,?,?,1,'DRAFT','API')
        """, revision, ORG, plan);
    jdbc.update("""
        insert into exam_plan_session
          (id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
        values (?,?,?,?,'Synthetic roomless duty session',?,?,0)
        """, session, ORG, revision, "CONSUMER-" + session,
        Timestamp.from(START), Timestamp.from(END));
    jdbc.update("""
        insert into exam_plan_duty_requirement
          (id,org_id,revision_id,session_id,duty_role,required_count,qualification)
        values (?,?,?,?,'INVIGILATOR',1,'{"requiredQualifications":["INVIGILATOR"]}'::jsonb)
        """, requirement, ORG, revision, session);
    jdbc.update("""
        insert into exam_plan_duty_assignment
          (id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status)
        values (?,?,?,?,?,?,?,'INVIGILATOR','PENDING_CONFIRMATION')
        """, assignment, ORG, revision, requirement, original.teacherId(),
        Timestamp.from(START), Timestamp.from(END));
    jdbc.update("update exam_plan_revision set status='PUBLISHED',published_at=now() where org_id=? and id=?",
        ORG, revision);
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where org_id=? and id=?",
        revision, ORG, plan);
    originalAssignment = assignmentState();
    originalRevision = revisionState();
    managerCookie = login("tenant.test", "Tenant001!!!");
    replacementCookie = login(replacement.username(), "Demo@2025");
  }

  @Test
  void resourceApiKeepsPublishedTeacherAndSeparatelyReportsCurrentReplacement() throws Exception {
    transferToReplacement();

    JsonNode rows = revisionDuties(revision);
    assertThat(rows.size()).isOne();
    JsonNode row = rows.get(0);
    assertThat(row.path("id").asText()).isEqualTo(assignment.toString());
    assertThat(row.path("teacher_id").asText()).as("published snapshot is not rewritten")
        .isEqualTo(original.teacherId().toString());
    assertThat(row.path("teacher_name").asText()).isEqualTo(original.name());
    assertThat(row.path("current_teacher_id").asText()).isEqualTo(replacement.teacherId().toString());
    assertThat(row.path("current_teacher_name").asText()).isEqualTo(replacement.name());
    assertThat(row.path("current_status").asText()).isEqualTo("PENDING_CONFIRMATION");
    assertThat(row.path("current_engagement_no").asLong(-1)).isEqualTo(1L);
    assertPublishedGraphUnchanged();
  }

  @Test
  void rejectingTheCurrentReplacementMakesReadinessReportOneMoreUncoveredPost() throws Exception {
    transferToReplacement();
    JsonNode before = readiness();
    assertThat(before.path("totals").path("uncoveredDutyPosts").isIntegralNumber()).isTrue();
    int previousUncovered = before.path("totals").path("uncoveredDutyPosts").intValue();
    int previousOpen = before.path("totals").path("openSubstitutions").intValue();

    UUID replacementCase = rejectReplacement();
    JsonNode after = readiness();
    assertThat(after.path("totals").path("uncoveredDutyPosts").intValue())
        .as("a historical assignment and old RESOLVED case do not cover a rejected current engagement")
        .isEqualTo(previousUncovered + 1);
    assertThat(after.path("totals").path("openSubstitutions").intValue()).isEqualTo(previousOpen + 1);
    assertThat(after.path("ready").asBoolean(true)).isFalse();
    assertThat(after.path("issues").findValuesAsText("code")).contains("UNCOVERED_DUTY_POSTS");
    assertThat(jdbc.queryForMap("select teacher_id,status from exam_plan_substitution_case where org_id=? and id=?",
        ORG, replacementCase)).containsEntry("teacher_id", replacement.teacherId()).containsEntry("status", "OPEN");
    assertPublishedGraphUnchanged();
  }

  @Test
  void approvedLeaveForCurrentReplacementCreatesANewCaseAndReplaysWithoutDuplicateEffects() throws Exception {
    transferToReplacement();
    String resolvedOriginalCase = caseState(firstCase);
    JsonNode created = json(mvc.perform(post("/api/v1/leave-requests").cookie(managerCookie)
            .header("Idempotency-Key", "consumer-leave-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("teacherId", replacement.teacherId(),
                "startsAt", START.toString(), "endsAt", END.toString(), "reason", "Synthetic replacement absence"))))
        .andExpect(status().isCreated()).andReturn());
    UUID leave = UUID.fromString(created.path("id").asText());
    JsonNode approved = approveLeave(leave);

    assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
    assertThat(approved.path("substitutionCaseCount").asInt()).isOne();
    List<Map<String, Object>> cases = jdbc.queryForList("""
        select id,teacher_id,status,leave_request_id from exam_plan_substitution_case
        where org_id=? and duty_assignment_id=? and leave_request_id=?
        """, ORG, assignment, leave);
    assertThat(cases).hasSize(1);
    assertThat(cases.getFirst()).containsEntry("teacher_id", replacement.teacherId())
        .containsEntry("status", "OPEN").containsEntry("leave_request_id", leave);
    assertThat(cases.getFirst().get("id")).isNotEqualTo(firstCase);
    assertThat(caseState(firstCase)).as("A's resolved case is immutable history").isEqualTo(resolvedOriginalCase);
    var beforeReplay = leaveEffects(leave);
    JsonNode replay = approveLeave(leave);
    assertThat(replay.path("replayed").asBoolean()).isTrue();
    assertThat(leaveEffects(leave)).isEqualTo(beforeReplay);
    assertThat(jdbc.queryForObject("select status from exam_plan_leave_request where org_id=? and id=?",
        String.class, ORG, leave)).isEqualTo("APPROVED");
    assertPublishedGraphUnchanged();
  }

  @Test
  void emergencySuccessorCarriesCurrentReplacementWithoutResurrectingPublishedOriginal() throws Exception {
    transferToReplacement();
    JsonNode change = json(mvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/emergency-changes", plan, revision)
            .cookie(managerCookie).header("Idempotency-Key", "consumer-emergency-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("type", "NETWORK_INTERRUPTION_RETRY", "payload", Map.of()))))
        .andExpect(status().isCreated()).andReturn());
    UUID successor = UUID.fromString(change.path("successorRevisionId").asText());
    assertThat(successor).isNotEqualTo(revision);
    assertThat(change.path("status").asText()).isEqualTo("REVIEW_REQUIRED");

    JsonNode successorDuties = revisionDuties(successor);
    assertThat(successorDuties.size()).isOne();
    assertThat(successorDuties.get(0).path("teacher_id").asText()).isEqualTo(replacement.teacherId().toString());
    assertThat(jdbc.queryForList("select teacher_id from exam_plan_duty_assignment where org_id=? and revision_id=?",
        UUID.class, ORG, successor)).containsExactly(replacement.teacherId());
    assertThat(jdbc.queryForObject("select predecessor_id from exam_plan_revision where org_id=? and id=?",
        UUID.class, ORG, successor)).isEqualTo(revision);
    assertThat(jdbc.queryForObject("select active_revision_id from exam_plan where org_id=? and id=?",
        UUID.class, ORG, plan)).as("unapproved successor does not replace the live version").isEqualTo(revision);
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where org_id=? and id=?",
        String.class, ORG, successor)).isEqualTo("REVIEW_REQUIRED");
    assertPublishedGraphUnchanged();
  }

  @Test
  void currentReplacementRejectionBlocksExecutionWithoutPublishingPartialState() throws Exception {
    transferToReplacement();
    rejectReplacement();
    var before = runtimeEffects();
    long cursor = jdbc.queryForObject("select version from exam_plan_revision where org_id=? and id=?",
        Long.class, ORG, revision);
    JsonNode rejected = json(mvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/execute", plan, revision)
            .cookie(managerCookie).header("If-Match", Long.toString(cursor)))
        .andExpect(status().isUnprocessableEntity()).andReturn());
    assertThat(rejected.path("code").asText()).isEqualTo("OPERATIONS_NOT_READY");
    assertThat(rejected.path("errors").findValuesAsText("field")).contains("OPEN_SUBSTITUTIONS");
    assertThat(runtimeEffects()).as("failed lifecycle gate has zero persistence effects").isEqualTo(before);
    assertThat(jdbc.queryForObject("select status from exam_plan where org_id=? and id=?",
        String.class, ORG, plan)).isEqualTo("PUBLISHED");
    assertPublishedGraphUnchanged();
  }

  @Test
  void emergencyCannotChooseAReplacementCurrentlyHoldingAnotherPublishedDuty() throws Exception {
    Account freeCandidate = teacher("free-emergency-candidate");
    orderEmergencyCandidates(replacement, freeCandidate);
    Account otherOriginal = teacher("other-published-original");
    AdditionalDuty occupied = additionalPublishedDuty(otherOriginal);
    JsonNode refused = json(mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", occupied.assignment())
            .cookie(login(otherOriginal.username(), "Demo@2025")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Synthetic absence on the other live plan\"}"))
        .andExpect(status().isOk()).andReturn());
    UUID occupiedCase = UUID.fromString(refused.path("substitutionCaseId").asText());
    JsonNode resolved = json(mvc.perform(post("/api/v1/substitution-cases/{id}/resolve", occupiedCase)
            .cookie(managerCookie).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("replacementTeacherId", replacement.teacherId(),
                "reason", "Synthetic actual current reservation"))))
        .andExpect(status().isOk()).andReturn());
    assertThat(resolved.path("status").asText()).isEqualTo("RESOLVED");
    JsonNode held = json(mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(replacementCookie))
        .andExpect(status().isOk()).andReturn());
    assertThat(held.findValuesAsText("id")).contains(occupied.assignment().toString());
    assertThat(jdbc.queryForObject("select teacher_id from exam_plan_duty_assignment where org_id=? and id=?",
        UUID.class, ORG, occupied.assignment())).isEqualTo(otherOriginal.teacherId());
    String resolvedCase = caseState(occupiedCase);

    // B sorts ahead of C, but only C is free. Reading the original teacher column misses B.
    JsonNode changed = emergencyWithOnlyCandidates(replacement, freeCandidate);
    assertEmergencySuccessorTeacher(changed, freeCandidate);
    assertThat(caseState(occupiedCase)).isEqualTo(resolvedCase);
    assertAdditionalDutyUnchanged(occupied);
    assertPublishedGraphUnchanged();
  }

  @Test
  void emergencyCannotChooseATeacherWithAnApprovedOverlappingLeaveRequest() throws Exception {
    Account freeCandidate = teacher("free-after-approved-leave");
    orderEmergencyCandidates(replacement, freeCandidate);
    JsonNode created = json(mvc.perform(post("/api/v1/leave-requests").cookie(managerCookie)
            .header("Idempotency-Key", "consumer-emergency-leave-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("teacherId", replacement.teacherId(),
                "startsAt", START.toString(), "endsAt", END.toString(), "reason", "Synthetic approved emergency exclusion"))))
        .andExpect(status().isCreated()).andReturn());
    UUID leave = UUID.fromString(created.path("id").asText());
    assertThat(approveLeave(leave).path("status").asText()).isEqualTo("APPROVED");
    String approved = stored("exam_plan_leave_request", leave);

    // B is not in this emergency's absence list: the persisted approved request must exclude B.
    JsonNode changed = emergencyWithOnlyCandidates(replacement, freeCandidate);
    assertEmergencySuccessorTeacher(changed, freeCandidate);
    assertThat(stored("exam_plan_leave_request", leave)).isEqualTo(approved);
    assertPublishedGraphUnchanged();
  }

  @Test
  void emergencyIgnoresOccupancyInAHistoricalNonActivePublishedRevision() throws Exception {
    AdditionalDuty historical = additionalPublishedDuty(replacement);
    UUID currentEmptyRevision = UUID.randomUUID();
    jdbc.update("""
        insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type)
        values (?,?,?,2,'PUBLISHED','API')
        """, currentEmptyRevision, ORG, historical.plan());
    jdbc.update("update exam_plan set active_revision_id=? where org_id=? and id=?",
        currentEmptyRevision, ORG, historical.plan());
    assertThat(jdbc.queryForObject("select status from exam_plan_revision where org_id=? and id=?",
        String.class, ORG, historical.revision())).isEqualTo("PUBLISHED");
    JsonNode actualDuties = json(mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(replacementCookie))
        .andExpect(status().isOk()).andReturn());
    assertThat(actualDuties.findValuesAsText("id")).doesNotContain(historical.assignment().toString());

    // The sole available teacher is B. Historical PUBLISHED state is not an active reservation.
    JsonNode changed = emergencyWithOnlyCandidates(replacement);
    assertEmergencySuccessorTeacher(changed, replacement);
    assertThat(jdbc.queryForObject("select active_revision_id from exam_plan where org_id=? and id=?",
        UUID.class, ORG, historical.plan())).isEqualTo(currentEmptyRevision);
    assertAdditionalDutyUnchanged(historical);
    assertPublishedGraphUnchanged();
  }

  private void orderEmergencyCandidates(Account preferredButUnavailable, Account free) {
    assertThat(jdbc.update("update teacher set teacher_no=? where org_id=? and id=?",
        "000-CONSUMER-" + preferredButUnavailable.teacherId().toString().substring(0, 12), ORG, preferredButUnavailable.teacherId())).isOne();
    assertThat(jdbc.update("update teacher set teacher_no=? where org_id=? and id=?",
        "001-CONSUMER-" + free.teacherId().toString().substring(0, 12), ORG, free.teacherId())).isOne();
  }

  private AdditionalDuty additionalPublishedDuty(Account owner) {
    UUID otherPlan = UUID.randomUUID(), otherRevision = UUID.randomUUID(), otherAssignment = UUID.randomUUID();
    UUID session = UUID.randomUUID(), requirement = UUID.randomUUID();
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name,status) values (?,?,?,'Synthetic other consumer plan','DRAFT')",
        otherPlan, ORG, "CONSUMER-" + otherPlan);
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type) values (?,?,?,1,'DRAFT','API')",
        otherRevision, ORG, otherPlan);
    jdbc.update("""
        insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
        values (?,?,?,?,'Synthetic overlapping other session',?,?,0)
        """, session, ORG, otherRevision, "CONSUMER-" + session, Timestamp.from(START), Timestamp.from(END));
    jdbc.update("""
        insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count,qualification)
        values (?,?,?,?,'INVIGILATOR',1,'{"requiredQualifications":["INVIGILATOR"]}'::jsonb)
        """, requirement, ORG, otherRevision, session);
    jdbc.update("""
        insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status)
        values (?,?,?,?,?,?,?,'INVIGILATOR','PENDING_CONFIRMATION')
        """, otherAssignment, ORG, otherRevision, requirement, owner.teacherId(), Timestamp.from(START), Timestamp.from(END));
    // Synthetic source graph only: no approval/publication workflow claim, no immutable-trigger bypass.
    jdbc.update("update exam_plan_revision set status='PUBLISHED',published_at=now() where org_id=? and id=?", ORG, otherRevision);
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where org_id=? and id=?", otherRevision, ORG, otherPlan);
    return new AdditionalDuty(otherPlan, otherRevision, otherAssignment,
        stored("exam_plan_duty_assignment", otherAssignment), stored("exam_plan_revision", otherRevision));
  }

  private JsonNode emergencyWithOnlyCandidates(Account... allowed) throws Exception {
    List<UUID> eligibleIds = java.util.Arrays.stream(allowed).map(Account::teacherId).toList();
    List<String> unavailable = jdbc.queryForList("select id,teacher_no from teacher where org_id=? and status='active'", ORG)
        .stream().filter(row -> !eligibleIds.contains(row.get("id")))
        .map(row -> String.valueOf(row.get("teacher_no"))).toList();
    assertThat(unavailable).contains(jdbc.queryForObject("select teacher_no from teacher where org_id=? and id=?",
        String.class, ORG, original.teacherId()));
    for (Account candidate : allowed) {
      assertThat(unavailable).doesNotContain(jdbc.queryForObject("select teacher_no from teacher where org_id=? and id=?",
          String.class, ORG, candidate.teacherId()));
    }
    return json(mvc.perform(post("/api/v1/exam-plans/{plan}/revisions/{revision}/emergency-changes", plan, revision)
            .cookie(managerCookie).header("Idempotency-Key", "consumer-emergency-selection-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("type", "TEACHER_MASS_ABSENCE", "payload",
                Map.of("teacherIds", unavailable, "effectiveAt", START.toString(), "endsAt", END.toString())))))
        .andExpect(status().isCreated()).andReturn());
  }

  private void assertEmergencySuccessorTeacher(JsonNode changed, Account expected) throws Exception {
    assertThat(changed.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
    assertThat(changed.path("impactAnalysis").path("substitutedDuties").asInt(-1)).isOne();
    UUID successor = UUID.fromString(changed.path("successorRevisionId").asText());
    assertThat(successor).isNotEqualTo(revision);
    JsonNode duties = revisionDuties(successor);
    assertThat(duties.size()).isOne();
    assertThat(duties.get(0).path("teacher_id").asText()).isEqualTo(expected.teacherId().toString());
    assertThat(jdbc.queryForList("select teacher_id from exam_plan_duty_assignment where org_id=? and revision_id=?",
        UUID.class, ORG, successor)).containsExactly(expected.teacherId());
    assertThat(jdbc.queryForObject("select active_revision_id from exam_plan where org_id=? and id=?",
        UUID.class, ORG, plan)).as("an unapproved emergency draft cannot replace the live revision").isEqualTo(revision);
    assertThat(jdbc.queryForObject("select predecessor_id from exam_plan_revision where org_id=? and id=?",
        UUID.class, ORG, successor)).isEqualTo(revision);
  }

  private void assertAdditionalDutyUnchanged(AdditionalDuty value) {
    assertThat(stored("exam_plan_duty_assignment", value.assignment())).isEqualTo(value.assignmentState());
    assertThat(stored("exam_plan_revision", value.revision())).isEqualTo(value.revisionState());
  }

  private String stored(String table, UUID id) {
    // Only private literal table names above reach this helper; never a request value.
    return jdbc.queryForObject("select to_jsonb(v)::text from " + table + " v where org_id=? and id=?", String.class, ORG, id);
  }

  private record AdditionalDuty(UUID plan, UUID revision, UUID assignment, String assignmentState, String revisionState) {}

  @Test
  void readinessCountsAssignedReplacementAsPendingUntilItsOwnRealAcceptance() throws Exception {
    transferToReplacement();
    String resolvedOriginalCase = caseState(firstCase);
    JsonNode pending = readiness();
    JsonNode pendingCount = pending.path("totals").path("pendingDutyConfirmations");
    assertThat(pendingCount.isIntegralNumber()).as("pending confirmation must be an explicit measured total").isTrue();
    assertThat(pendingCount.intValue()).isGreaterThanOrEqualTo(1);
    JsonNode assigned = revisionDuties(revision).get(0);
    assertThat(assigned.path("current_teacher_id").asText()).isEqualTo(replacement.teacherId().toString());
    assertThat(assigned.path("current_status").asText()).isEqualTo("PENDING_CONFIRMATION");

    JsonNode accepted = json(mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/accept", assignment)
            .cookie(replacementCookie).header("X-Duty-Engagement", "1"))
        .andExpect(status().isOk()).andReturn());
    assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
    assertThat(accepted.path("engagementNo").asLong(-1)).isEqualTo(1L);
    assertThat(jdbc.queryForMap("""
        select teacher_id,status from exam_plan_duty_response
        where org_id=? and assignment_id=? and engagement_no=1
        """, ORG, assignment)).containsEntry("teacher_id", replacement.teacherId()).containsEntry("status", "ACCEPTED");

    JsonNode confirmed = readiness();
    assertThat(confirmed.path("totals").path("pendingDutyConfirmations").isIntegralNumber()).isTrue();
    assertThat(confirmed.path("totals").path("pendingDutyConfirmations").intValue())
        .as("only B's actual acceptance removes this pending confirmation")
        .isEqualTo(pendingCount.intValue() - 1);
    assertThat(confirmed.path("totals").path("openSubstitutions"))
        .as("confirmation information does not rewrite resolved substitution state")
        .isEqualTo(pending.path("totals").path("openSubstitutions"));
    assertThat(caseState(firstCase)).isEqualTo(resolvedOriginalCase);
    assertThat(revisionDuties(revision).get(0).path("current_status").asText()).isEqualTo("ACCEPTED");
    assertPublishedGraphUnchanged();
  }

  private void transferToReplacement() throws Exception {
    Cookie originalCookie = login(original.username(), "Demo@2025");
    JsonNode rejected = json(mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", assignment)
            .cookie(originalCookie).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Synthetic original absence\"}"))
        .andExpect(status().isOk()).andReturn());
    assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
    firstCase = UUID.fromString(rejected.path("substitutionCaseId").asText());
    JsonNode resolved = json(mvc.perform(post("/api/v1/substitution-cases/{id}/resolve", firstCase)
            .cookie(managerCookie).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("replacementTeacherId", replacement.teacherId(),
                "reason", "Synthetic consumer-contract handover"))))
        .andExpect(status().isOk()).andReturn());
    assertThat(resolved.path("status").asText()).isEqualTo("RESOLVED");
    assertThat(resolved.path("replacementTeacherId").asText()).isEqualTo(replacement.teacherId().toString());
    assertThat(jdbc.queryForMap("select replacement_teacher_id,status from exam_plan_substitution_case where org_id=? and id=?",
        ORG, firstCase)).containsEntry("replacement_teacher_id", replacement.teacherId()).containsEntry("status", "RESOLVED");
    assertPublishedGraphUnchanged();
  }

  private UUID rejectReplacement() throws Exception {
    // B is a first-time holder: omission remains compatible, explicit stale tokens are tested elsewhere.
    JsonNode response = json(mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", assignment)
            .cookie(replacementCookie).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Synthetic current replacement also becomes unavailable\"}"))
        .andExpect(status().isOk()).andReturn());
    assertThat(response.path("status").asText()).isEqualTo("REJECTED");
    UUID currentCase = UUID.fromString(response.path("substitutionCaseId").asText());
    assertThat(currentCase).isNotEqualTo(firstCase);
    return currentCase;
  }

  private JsonNode approveLeave(UUID leave) throws Exception {
    return json(mvc.perform(post("/api/v1/leave-requests/{id}/decision", leave).cookie(managerCookie)
            .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\",\"reason\":\"Synthetic approval\"}"))
        .andExpect(status().isOk()).andReturn());
  }

  private JsonNode readiness() throws Exception {
    return json(mvc.perform(get("/api/v1/operations/readiness").cookie(managerCookie))
        .andExpect(status().isOk()).andReturn());
  }

  private JsonNode revisionDuties(UUID value) throws Exception {
    return json(mvc.perform(get("/api/v1/plan-revisions/{id}/duties", value).cookie(managerCookie))
        .andExpect(status().isOk()).andReturn());
  }

  private Account teacher(String label) {
    UUID user = UUID.randomUUID(), teacher = UUID.randomUUID();
    String username = "consumer." + label + "." + user.toString().substring(0, 8);
    String teacherNo = "CON-" + teacher.toString().substring(0, 12);
    String name = "Synthetic consumer " + label;
    assertThat(jdbc.update("""
        insert into app_user
          (id,org_id,username,display_name,email,password_hash,role,status,must_change_password,onboarded)
        select ?,org_id,?,?,?,password_hash,'TEACHER_QUERY','active',false,true
        from app_user where org_id=? and username='jwc.demo'
        """, user, username, name, username + "@example.test", ORG)).isOne();
    assertThat(jdbc.update("""
        insert into user_role(user_id,role_id,org_id)
        select ?,id,org_id from role where org_id=? and code='TEACHER_QUERY'
        """, user, ORG)).isOne();
    jdbc.update("""
        insert into teacher
          (id,org_id,teacher_no,name,gender,email,status,qualifications,max_daily_sessions,app_user_id)
        values (?,?,?,?,'unknown',?,'active','["INVIGILATOR"]'::jsonb,4,?)
        """, teacher, ORG, teacherNo, name, teacherNo + "@example.test", user);
    return new Account(user, teacher, username, name);
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = mapper.readValue(mvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
    MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("username", username, "password", password,
                "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn();
    Cookie cookie = result.getResponse().getCookie("JKXT_SCHOOL_SESSION");
    assertThat(cookie).as("fresh test-profile authentication session").isNotNull();
    return cookie;
  }

  private JsonNode json(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String assignmentState() {
    return jdbc.queryForObject("select to_jsonb(a)::text from exam_plan_duty_assignment a where org_id=? and id=?",
        String.class, ORG, assignment);
  }

  private String revisionState() {
    return jdbc.queryForObject("select to_jsonb(r)::text from exam_plan_revision r where org_id=? and id=?",
        String.class, ORG, revision);
  }

  private String caseState(UUID value) {
    return jdbc.queryForObject("select to_jsonb(c)::text from exam_plan_substitution_case c where org_id=? and id=?",
        String.class, ORG, value);
  }

  private List<List<String>> runtimeEffects() {
    return List.of(
        jdbc.queryForList("select to_jsonb(c)::text from exam_plan_substitution_case c where org_id=? and duty_assignment_id=? order by id", String.class, ORG, assignment),
        jdbc.queryForList("select to_jsonb(r)::text from exam_plan_duty_response r where org_id=? and assignment_id=? order by id", String.class, ORG, assignment),
        jdbc.queryForList("select to_jsonb(a)::text from exam_plan_audit_event a where org_id=? and revision_id=? order by id", String.class, ORG, revision),
        jdbc.queryForList("select to_jsonb(o)::text from exam_plan_outbox o where org_id=? and revision_id=? order by id", String.class, ORG, revision),
        jdbc.queryForList("select to_jsonb(d)::text from exam_plan_delivery d join exam_plan_outbox o on o.org_id=d.org_id and o.id=d.outbox_id where d.org_id=? and o.revision_id=? order by d.id", String.class, ORG, revision));
  }

  private List<List<String>> leaveEffects(UUID leave) {
    var values = new java.util.ArrayList<>(runtimeEffects());
    values.add(jdbc.queryForList("select to_jsonb(l)::text from exam_plan_leave_request l where org_id=? and id=?",
        String.class, ORG, leave));
    values.add(jdbc.queryForList("select to_jsonb(a)::text from exam_plan_audit_event a where org_id=? and after_state->>'leaveRequestId'=? order by id",
        String.class, ORG, leave.toString()));
    values.add(jdbc.queryForList("select to_jsonb(o)::text from exam_plan_outbox o where org_id=? and aggregate_type='exam_plan_leave_request' and aggregate_id=? order by id",
        String.class, ORG, leave));
    return values;
  }

  private void assertPublishedGraphUnchanged() {
    assertThat(assignmentState()).isEqualTo(originalAssignment);
    assertThat(revisionState()).isEqualTo(originalRevision);
  }

  private record Account(UUID userId, UUID teacherId, String username, String name) {}
}
