package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.DutyAssignmentSelfServiceDtos.DutyActionResponse;
import com.jkxt.examops.api.LeaveRequestDtos.CreateLeaveRequest;
import com.jkxt.examops.api.LeaveRequestDtos.DecideLeaveRequest;
import com.jkxt.examops.service.DutyAssignmentSelfService;
import com.jkxt.examops.service.LeaveRequestService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real service/PostgreSQL regression, isolated tenant and rolled-back fixtures; no mocked backend.
 * Authentication/HTTP remain covered by DutyAssignmentSelfServiceIntegrationTest.
 * The dates test Shanghai qualification validity, not an invented check-in minute window.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DutyAssignmentEligibilityIntegrationTest {
  private static final Instant START = Instant.parse("2042-03-03T16:30:00Z");
  private static final Instant END = START.plusSeconds(7200);
  @Autowired JdbcTemplate jdbc;
  @Autowired DutyAssignmentSelfService duties;
  @Autowired LeaveRequestService leaves;
  private UUID org;
  private UUID teacher;
  private UUID user;

  @BeforeEach
  void isolateFixtures() {
    org = UUID.randomUUID();
    teacher = UUID.randomUUID();
    user = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Duty eligibility regression',?)",
        org, "duty-eligibility-" + org);
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password)
        values (?,?,'duty-owner','Duty owner','test-only-unused-hash','TEACHER_QUERY','active',false)
        """, user, org);
    jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,app_user_id,status,qualifications,
          qualification_expiry,position,is_homeroom,max_daily_sessions,leave_windows,duty_windows)
        values (?,?,'ELIG-001','Synthetic duty teacher','unknown',?,'active',
          '["INVIGILATOR","CHIEF"]'::jsonb,'2042-03-04','SENIOR',true,4,'[]'::jsonb,'[]'::jsonb)
        """, teacher, org, user);
    TenantContext.set(org);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  static Stream<Arguments> revokedEligibility() {
    return Stream.of("accept", "check-in").flatMap(action -> Stream.of(
        Arguments.of(action, "disabled", "DUTY_TEACHER_INACTIVE"),
        Arguments.of(action, "left", "DUTY_TEACHER_INACTIVE"),
        Arguments.of(action, "no-chief", "DUTY_TEACHER_UNQUALIFIED"),
        Arguments.of(action, "no-invigilator", "DUTY_TEACHER_UNQUALIFIED"),
        Arguments.of(action, "expired-shanghai", "DUTY_TEACHER_UNQUALIFIED"),
        Arguments.of(action, "wrong-position", "DUTY_TEACHER_UNQUALIFIED"),
        Arguments.of(action, "not-homeroom", "DUTY_TEACHER_UNQUALIFIED"),
        Arguments.of(action, "overlapping-leave", "DUTY_TEACHER_UNAVAILABLE"),
        Arguments.of(action, "partial-duty-window", "DUTY_TEACHER_UNAVAILABLE")));
  }

  @ParameterizedTest(name = "{0} refuses {1} with no response/event changes")
  @MethodSource("revokedEligibility")
  void currentTeacherEligibilityIsRecheckedBeforeEachNewTransition(
      String action, String mutation, String code
  ) {
    Fixture fixture = publishedFixture();
    if (action.equals("check-in")) duties.accept(user, fixture.assignment());
    mutateTeacher(mutation);
    Snapshot before = snapshot(fixture);
    assertDenied(() -> act(action, fixture), code);
    assertThat(snapshot(fixture)).isEqualTo(before);
    assertThat(jdbc.queryForObject("select status from app_user where id=?", String.class, user))
        .isEqualTo("active");
  }

  @ParameterizedTest
  @ValueSource(strings = {"accept", "check-in"})
  void newlyApprovedOperationalLeaveBlocksBothTransitionsWithoutAdditionalEvents(String action) {
    Fixture fixture = publishedFixture();
    if (action.equals("check-in")) duties.accept(user, fixture.assignment());
    var request = leaves.create(new CreateLeaveRequest(teacher, START, END, "Synthetic absence"),
        user, "duty-leave-" + UUID.randomUUID());
    var approved = leaves.decide(request.id(), new DecideLeaveRequest("APPROVED", "Verified absence"), user);
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(approved.substitutionCaseCount()).isEqualTo(1);
    Snapshot before = snapshot(fixture);
    assertDenied(() -> act(action, fixture), "DUTY_TEACHER_UNAVAILABLE");
    assertThat(snapshot(fixture)).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"accept,number", "accept,uuid", "check-in,number", "check-in,uuid"})
  void enabledHardLeaveHonorsBothSupportedTeacherIdentifiers(String action, String identifier) {
    Fixture fixture = draftFixture();
    constraint(fixture, identifier.equals("uuid") ? teacher.toString() : "ELIG-001",
        "HARD", true, true, START, END);
    publishFixture(fixture);
    // Characterize already-confirmed legacy duties without editing an immutable published graph.
    if (action.equals("check-in")) seedAcceptedResponse(fixture);
    Snapshot before = snapshot(fixture);
    assertDenied(() -> act(action, fixture), "DUTY_TEACHER_UNAVAILABLE");
    assertThat(snapshot(fixture)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"disabled", "soft", "unapproved", "other-teacher", "boundary"})
  void inactiveOrNonOverlappingHardLeaveDoesNotRejectAValidTeacher(String variant) {
    Fixture fixture = draftFixture();
    constraint(fixture, variant.equals("other-teacher") ? "UNRELATED" : teacher.toString(),
        variant.equals("soft") ? "SOFT" : "HARD", !variant.equals("disabled"),
        !variant.equals("unapproved"), variant.equals("boundary") ? END : START,
        variant.equals("boundary") ? END.plusSeconds(3600) : END);
    publishFixture(fixture);
    assertThat(duties.accept(user, fixture.assignment()).status()).isEqualTo("ACCEPTED");
    assertThat(duties.checkIn(user, fixture.assignment()).status()).isEqualTo("CHECKED_IN");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "REJECTED", "CANCELLED", "OTHER_TEACHER", "BOUNDARY"})
  void onlyApprovedOverlappingLeaveForTheAssignedTeacherBlocks(String state) {
    Fixture fixture = publishedFixture();
    UUID absentTeacher = teacher;
    if (state.equals("OTHER_TEACHER")) {
      absentTeacher = UUID.randomUUID();
      jdbc.update("""
          insert into teacher(id,org_id,teacher_no,name,gender)
          values (?,?,'ELIG-002','Other synthetic teacher','unknown')
          """, absentTeacher, org);
    }
    Instant start = state.equals("BOUNDARY") ? END : START;
    Instant end = state.equals("BOUNDARY") ? END.plusSeconds(3600) : END;
    String status = List.of("OTHER_TEACHER", "BOUNDARY").contains(state) ? "APPROVED" : state;
    jdbc.update("""
        insert into exam_plan_leave_request
          (org_id,teacher_id,starts_at,ends_at,reason,status,requested_by,decided_at)
        values (?,?,?,?,'Synthetic leave',?,?,case when ?='PENDING' then null else now() end)
        """, org, absentTeacher, Timestamp.from(start), Timestamp.from(end), status, user, status);
    assertThat(duties.accept(user, fixture.assignment()).status()).isEqualTo("ACCEPTED");
    assertThat(duties.checkIn(user, fixture.assignment()).status()).isEqualTo("CHECKED_IN");
  }

  @Test
  void shanghaiExpiryDayAndHalfOpenLeaveBoundariesRemainValid() {
    Fixture fixture = publishedFixture();
    // UTC March 3 is already March 4 in Shanghai; expiry on March 4 remains valid.
    jdbc.update("""
        update teacher set leave_windows=?::jsonb, duty_windows=?::jsonb where id=?
        """, "[" + window(START.minusSeconds(3600), START) + "," + window(END, END.plusSeconds(3600)) + "]",
        "[" + window(START, END) + "]", teacher);
    var accepted = duties.accept(user, fixture.assignment());
    var checkedIn = duties.checkIn(user, fixture.assignment());
    assertThat(accepted.replayed()).isFalse();
    assertThat(checkedIn.respondedAt()).isEqualTo(accepted.respondedAt());
    assertThat(checkedIn.checkedInAt()).isNotNull();
    assertThat(checkedIn.status()).isEqualTo("CHECKED_IN");
    assertEventsExactlyOnce(fixture, "DUTY_ACCEPTED");
    assertEventsExactlyOnce(fixture, "DUTY_CHECKED_IN");
  }

  @ParameterizedTest
  @ValueSource(strings = {"accept", "check-in"})
  void successfulReplayReturnsStoredHistoryEvenAfterEligibilityChanges(String action) {
    Fixture fixture = publishedFixture();
    DutyActionResponse original = duties.accept(user, fixture.assignment());
    if (action.equals("check-in")) original = duties.checkIn(user, fixture.assignment());
    mutateTeacher("disabled");
    Snapshot before = snapshot(fixture);
    DutyActionResponse replay = act(action, fixture);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.status()).isEqualTo(original.status());
    assertThat(replay.respondedAt()).isEqualTo(original.respondedAt());
    assertThat(replay.checkedInAt()).isEqualTo(original.checkedInAt());
    assertThat(snapshot(fixture)).isEqualTo(before);
    assertEventsExactlyOnce(fixture, action.equals("accept") ? "DUTY_ACCEPTED" : "DUTY_CHECKED_IN");
  }

  @Test
  void unavailableTeacherCanStillRejectAPendingAssignment() {
    Fixture fixture = publishedFixture();
    mutateTeacher("disabled");
    mutateTeacher("no-chief");
    mutateTeacher("overlapping-leave");
    var result = duties.reject(user, fixture.assignment(), "Cannot attend");
    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.substitutionCaseId()).isNotNull();
    Snapshot before = snapshot(fixture);
    assertThat(duties.reject(user, fixture.assignment(), "Cannot attend").replayed()).isTrue();
    assertThat(snapshot(fixture)).isEqualTo(before);
    assertEventsExactlyOnce(fixture, "DUTY_REJECTED");
  }

  @Test
  void defaultOrdinaryQualificationAndEmptyRequirementRemainSupported() {
    Fixture fixture = draftFixture();
    jdbc.update("update teacher set qualifications='[\"INVIGILATOR\"]'::jsonb,qualification_expiry=null where id=?", teacher);
    jdbc.update("update exam_plan_duty_requirement set duty_role='INVIGILATOR',qualification='{}'::jsonb where id=?",
        fixture.requirement());
    jdbc.update("update exam_plan_duty_assignment set duty_role='INVIGILATOR' where id=?", fixture.assignment());
    publishFixture(fixture);
    assertThat(duties.accept(user, fixture.assignment()).status()).isEqualTo("ACCEPTED");
    assertThat(duties.checkIn(user, fixture.assignment()).status()).isEqualTo("CHECKED_IN");
  }

  private void mutateTeacher(String mutation) {
    switch (mutation) {
      case "disabled", "left" -> jdbc.update("update teacher set status=? where id=?", mutation, teacher);
      case "no-chief" -> jdbc.update("update teacher set qualifications='[\"INVIGILATOR\"]'::jsonb where id=?", teacher);
      case "no-invigilator" -> jdbc.update("update teacher set qualifications='[\"CHIEF\"]'::jsonb where id=?", teacher);
      case "expired-shanghai" -> jdbc.update("update teacher set qualification_expiry='2042-03-03' where id=?", teacher);
      case "wrong-position" -> jdbc.update("update teacher set position='JUNIOR' where id=?", teacher);
      case "not-homeroom" -> jdbc.update("update teacher set is_homeroom=false where id=?", teacher);
      case "overlapping-leave" -> jdbc.update("update teacher set leave_windows=?::jsonb where id=?",
          "[" + window(START.plusSeconds(1), END) + "]", teacher);
      case "partial-duty-window" -> jdbc.update("update teacher set duty_windows=?::jsonb where id=?",
          "[" + window(START, END.minusSeconds(1)) + "]", teacher);
      default -> throw new IllegalArgumentException(mutation);
    }
  }

  private DutyActionResponse act(String action, Fixture fixture) {
    return action.equals("accept") ? duties.accept(user, fixture.assignment())
        : duties.checkIn(user, fixture.assignment());
  }

  private void assertDenied(Runnable action, String code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
      assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(error.getCode()).isEqualTo(code);
    });
  }

  private Snapshot snapshot(Fixture fixture) {
    return new Snapshot(
        jdbc.queryForList("select to_jsonb(r)::text from exam_plan_duty_response r where org_id=? and assignment_id=? order by id",
            String.class, org, fixture.assignment()),
        jdbc.queryForList("select to_jsonb(r)::text from exam_plan_audit_event r where org_id=? order by id", String.class, org),
        jdbc.queryForList("select to_jsonb(r)::text from exam_plan_outbox r where org_id=? order by id", String.class, org),
        jdbc.queryForList("select to_jsonb(r)::text from exam_plan_substitution_case r where org_id=? order by id", String.class, org));
  }

  private void assertEventsExactlyOnce(Fixture fixture, String action) {
    assertThat(jdbc.queryForObject("""
        select count(*) from exam_plan_audit_event
        where org_id=? and after_state->>'assignmentId'=? and action=?
        """, Integer.class, org, fixture.assignment().toString(), action)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*) from exam_plan_outbox where org_id=? and aggregate_id=? and event_type=?
        """, Integer.class, org, fixture.assignment(), action)).isEqualTo(1);
  }

  private Fixture publishedFixture() {
    Fixture fixture = draftFixture();
    publishFixture(fixture);
    return fixture;
  }

  private Fixture draftFixture() {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID();
    UUID requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Duty eligibility fixture')",
        plan, org, plan.toString());
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status) values (?,?,?,1,'DRAFT')",
        revision, org, plan);
    jdbc.update("""
        insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
        values (?,?,?,'ELIGIBILITY','Duty eligibility',?,?,0)
        """, session, org, revision, Timestamp.from(START), Timestamp.from(END));
    jdbc.update("""
        insert into exam_plan_duty_requirement
          (id,org_id,revision_id,session_id,duty_role,required_count,qualification)
        values (?,?,?,?,'CHIEF',1,
          '{"requiredQualifications":["INVIGILATOR","CHIEF"],"requiredPositions":["SENIOR"],"requiresHomeroom":true}'::jsonb)
        """, requirement, org, revision, session);
    jdbc.update("""
        insert into exam_plan_duty_assignment
          (id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role)
        values (?,?,?,?,?,?,?,'CHIEF')
        """, assignment, org, revision, requirement, teacher, Timestamp.from(START), Timestamp.from(END));
    return new Fixture(plan, revision, requirement, assignment);
  }

  private void publishFixture(Fixture fixture) {
    // Fixture construction only: publication correctness is tested in PublicationIntegrityIntegrationTest.
    jdbc.update("update exam_plan_revision set status='PUBLISHED',published_at=now() where id=?", fixture.revision());
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where id=?", fixture.revision(), fixture.plan());
  }

  private void seedAcceptedResponse(Fixture fixture) {
    jdbc.update("""
        insert into exam_plan_duty_response(org_id,assignment_id,teacher_id,status,responded_at,created_by,updated_by)
        values (?,?,?,'ACCEPTED',?,?,?)
        """, org, fixture.assignment(), teacher, Timestamp.from(START.minusSeconds(86400)), user, user);
  }

  private void constraint(Fixture fixture, String identifier, String severity, boolean enabled,
      boolean approved, Instant start, Instant end) {
    String expression = "{\"approved\":" + approved + ",\"startsAt\":\"" + start
        + "\",\"endsAt\":\"" + end + "\"}";
    jdbc.update("""
        insert into exam_plan_constraint(org_id,revision_id,constraint_type,severity,resource_type,resource_id,expression,enabled)
        values (?,?,'TEACHER_LEAVE',?,'teacher',?,?::jsonb,?)
        """, org, fixture.revision(), severity, identifier, expression, enabled);
  }

  private String window(Instant start, Instant end) {
    return "{\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
  }

  private record Fixture(UUID plan, UUID revision, UUID requirement, UUID assignment) {}
  private record Snapshot(List<String> response, List<String> audit, List<String> outbox, List<String> substitution) {}
}
