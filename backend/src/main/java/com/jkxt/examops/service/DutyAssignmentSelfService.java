package com.jkxt.examops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.DutyAssignmentSelfServiceDtos.DutyActionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DutyAssignmentSelfService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ExamOperationsHardConstraints hardConstraints;
  /**
   * PB-17 (round 9, NOTIFY-06 "窗口和资格由后端判断"): the docs define no check-in window, so a
   * conservative default is used: check-in opens 60 minutes before the duty starts and closes when it
   * ends. Both bounds are deployment-configurable; non-final only so a test can exercise the defaults.
   */
  private Duration checkInOpensBefore;
  private Duration checkInClosesAfterEnd;

  public DutyAssignmentSelfService(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ExamOperationsHardConstraints hardConstraints,
      @Value("${jkxt.duty.check-in.opens-before-minutes:60}") long opensBeforeMinutes,
      @Value("${jkxt.duty.check-in.closes-after-end-minutes:0}") long closesAfterEndMinutes
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.hardConstraints = hardConstraints;
    this.checkInOpensBefore = Duration.ofMinutes(Math.max(0, opensBeforeMinutes));
    this.checkInClosesAfterEnd = Duration.ofMinutes(Math.max(0, closesAfterEndMinutes));
  }

  static boolean withinCheckInWindow(Instant now, Instant startsAt, Instant endsAt,
      Duration opensBefore, Duration closesAfterEnd) {
    return !now.isBefore(startsAt.minus(opensBefore)) && !now.isAfter(endsAt.plus(closesAfterEnd));
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> assignments(UUID userId) {
    UUID orgId = TenantContext.getRequired();
    return jdbcTemplate.queryForList("""
        select a.id, req.session_id, s.session_code as session_external_code,
               a.requirement_id, a.teacher_id, a.original_teacher_id,
               a.engagement_no as "engagementNo",
               coalesce(snap.teacher_no, t.teacher_no) as teacher_no,
               coalesce(snap.teacher_name, t.name) as teacher_name,
               a.starts_at, a.ends_at, a.duty_role,
               a.status,
               a.source, a.version, a.revision_id,
               a.reason, a.responded_at, a.checked_in_at,
               p.name as plan_name, classroom.name as room_name, classroom.code as room_code,
               s.subject_name
        from exam_plan_effective_duty a
        join exam_plan_revision r on r.id=a.revision_id and r.org_id=a.org_id
        join exam_plan p on p.id=r.plan_id and p.org_id=r.org_id and p.active_revision_id=r.id
        join exam_plan_duty_requirement req on req.id=a.requirement_id and req.org_id=a.org_id
        join exam_plan_session s on s.id=req.session_id and s.org_id=a.org_id
        left join exam_plan_room_assignment room
          on room.id=req.room_assignment_id and room.org_id=a.org_id and room.revision_id=a.revision_id
        left join classroom on classroom.id=room.classroom_id and classroom.org_id=a.org_id
        join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
        left join exam_plan_teacher_snapshot snap
          on snap.org_id=a.org_id and snap.revision_id=a.revision_id and snap.teacher_id=a.teacher_id
        where a.org_id=? and t.app_user_id=?
          and r.status in ('PUBLISHED','EXECUTING') and a.status<>'CANCELLED'
          and s.status<>'CANCELLED' and (req.room_assignment_id is null or room.status<>'CANCELLED')
        order by a.starts_at, s.session_code
        """, orgId, userId);
  }

  @Transactional
  public DutyActionResponse accept(UUID userId, UUID assignmentId) {
    return transition(userId, assignmentId, "ACCEPTED", null, null);
  }

  @Transactional
  public DutyActionResponse accept(UUID userId, UUID assignmentId, Long engagementNo) {
    return transition(userId, assignmentId, "ACCEPTED", null, engagementNo);
  }

  @Transactional
  public DutyActionResponse reject(UUID userId, UUID assignmentId, String reason) {
    return transition(userId, assignmentId, "REJECTED", reason.strip(), null);
  }

  @Transactional
  public DutyActionResponse reject(UUID userId, UUID assignmentId, String reason, Long engagementNo) {
    return transition(userId, assignmentId, "REJECTED", reason.strip(), engagementNo);
  }

  @Transactional
  public DutyActionResponse checkIn(UUID userId, UUID assignmentId) {
    return transition(userId, assignmentId, "CHECKED_IN", null, null);
  }

  @Transactional
  public DutyActionResponse checkIn(UUID userId, UUID assignmentId, Long engagementNo) {
    return transition(userId, assignmentId, "CHECKED_IN", null, engagementNo);
  }

  private DutyActionResponse transition(
      UUID userId, UUID assignmentId, String target, String reason, Long engagementNo
  ) {
    UUID orgId = TenantContext.getRequired();
    // Serialize a response with publication so a superseded assignment cannot be accepted.
    jdbcTemplate.queryForObject("select id from organization where id=? for no key update", UUID.class, orgId);
    Assignment assignment = findOwnedAssignmentForUpdate(orgId, userId, assignmentId);
    requireCurrentEngagement(orgId, assignment, engagementNo);
    String current = assignment.status();

    if (current.equals(target)) {
      return response(assignment, current, assignment.reason(),
          substitutionId(orgId, assignmentId, assignment.engagementNo()), true);
    }
    String required = target.equals("CHECKED_IN") ? "ACCEPTED" : "PENDING_CONFIRMATION";
    if (!current.equals(required)) {
      throw new ApiException(HttpStatus.CONFLICT, "DUTY_STATE_INVALID",
          "Duty assignment cannot transition from " + current + " to " + target);
    }

    // Successful replays above retain historical truth. A newly accepted/checked-in duty must
    // still be eligible now; an unavailable teacher must remain able to reject a pending duty.
    if (!target.equals("REJECTED")) requireCurrentEligibility(orgId, userId, assignment);

    // PostgreSQL timestamptz persists microseconds. Return the exact value that a replay reads.
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    if (target.equals("CHECKED_IN") && !withinCheckInWindow(now, assignment.startsAt(), assignment.endsAt(),
        checkInOpensBefore, checkInClosesAfterEnd)) {
      throw new ApiException(HttpStatus.CONFLICT, "DUTY_CHECK_IN_WINDOW_CLOSED",
          "Check-in is only possible from " + checkInOpensBefore.toMinutes()
              + " minutes before the duty starts until it ends");
    }
    Instant respondedAt = target.equals("CHECKED_IN")
        ? (assignment.respondedAt() == null ? now : assignment.respondedAt())
        : now;
    Instant checkedInAt = target.equals("CHECKED_IN") ? now : null;
    String persistedReason = target.equals("REJECTED") ? reason : assignment.reason();

    jdbcTemplate.update("""
        insert into exam_plan_duty_response (
          org_id, assignment_id, engagement_no, teacher_id, status, reason, responded_at, checked_in_at,
          created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (org_id, assignment_id, engagement_no) do update set
          status=excluded.status, reason=excluded.reason, responded_at=excluded.responded_at,
          checked_in_at=excluded.checked_in_at, updated_by=excluded.updated_by,
          updated_at=now(), version=exam_plan_duty_response.version+1
        """, orgId, assignmentId, assignment.engagementNo(), assignment.teacherId(), target, persistedReason,
        Timestamp.from(respondedAt), checkedInAt == null ? null : Timestamp.from(checkedInAt), userId, userId);

    UUID substitutionCaseId = null;
    if (target.equals("REJECTED")) {
      UUID proposedId = UUID.randomUUID();
      jdbcTemplate.update("""
          insert into exam_plan_substitution_case (
            id, org_id, revision_id, duty_assignment_id, engagement_no, teacher_id, reason,
            created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
          on conflict (org_id, duty_assignment_id, engagement_no) do nothing
          """, proposedId, orgId, assignment.revisionId(), assignmentId, assignment.engagementNo(), assignment.teacherId(),
          persistedReason, userId, userId);
      substitutionCaseId = substitutionId(orgId, assignmentId, assignment.engagementNo());
    }

    ObjectNode before = state(assignmentId, current, assignment.reason(), null);
    ObjectNode after = state(assignmentId, target, persistedReason, substitutionCaseId);
    before.put("engagementNo", assignment.engagementNo());
    after.put("engagementNo", assignment.engagementNo());
    String action = switch (target) {
      case "ACCEPTED" -> "DUTY_ACCEPTED";
      case "REJECTED" -> "DUTY_REJECTED";
      case "CHECKED_IN" -> "DUTY_CHECKED_IN";
      default -> throw new IllegalStateException("Unsupported duty action");
    };
    jdbcTemplate.update("""
        insert into exam_plan_audit_event (
          org_id, plan_id, revision_id, action, actor_id, reason,
          before_state, after_state, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """, orgId, assignment.planId(), assignment.revisionId(), action, userId,
        persistedReason, json(before), json(after), userId, userId);
    jdbcTemplate.update("""
        insert into exam_plan_outbox (
          org_id, revision_id, event_type, aggregate_type, aggregate_id, payload,
          created_by, updated_by
        ) values (?, ?, ?, 'DUTY_ASSIGNMENT', ?, ?::jsonb, ?, ?)
        """, orgId, assignment.revisionId(), action, assignmentId, json(after), userId, userId);

    return new DutyActionResponse(assignmentId, assignment.revisionId(), target, persistedReason,
        respondedAt, checkedInAt, substitutionCaseId, false, assignment.engagementNo());
  }

  private Assignment findOwnedAssignmentForUpdate(UUID orgId, UUID userId, UUID assignmentId) {
    try {
      return jdbcTemplate.queryForObject("""
          select a.id, p.id as plan_id, a.revision_id, d.teacher_id, a.starts_at, a.ends_at,
                 d.original_teacher_id, d.engagement_no,
                 req.duty_role as required_role, req.qualification::text,
                 d.status, d.reason, d.responded_at, d.checked_in_at
          from exam_plan_duty_assignment a
          join exam_plan_effective_duty d on d.id=a.id and d.org_id=a.org_id
          join exam_plan_revision r on r.id=a.revision_id and r.org_id=a.org_id
          join exam_plan p on p.id=r.plan_id and p.org_id=r.org_id and p.active_revision_id=r.id
          join exam_plan_duty_requirement req
            on req.id=a.requirement_id and req.org_id=a.org_id and req.revision_id=a.revision_id
          join exam_plan_session s
            on s.id=req.session_id and s.org_id=a.org_id and s.revision_id=a.revision_id
          left join exam_plan_room_assignment room
            on room.id=req.room_assignment_id and room.org_id=a.org_id and room.revision_id=a.revision_id
          join teacher t on t.id=d.teacher_id and t.org_id=a.org_id
          where a.org_id=? and a.id=? and t.app_user_id=?
            and r.status in ('PUBLISHED','EXECUTING') and a.status<>'CANCELLED'
            and s.status<>'CANCELLED' and (req.room_assignment_id is null or room.status<>'CANCELLED')
          for update of a
          """, this::assignment, orgId, assignmentId, userId);
    } catch (EmptyResultDataAccessException ignored) {
      throw new ApiException(HttpStatus.NOT_FOUND, "DUTY_ASSIGNMENT_NOT_FOUND",
          "Duty assignment is unavailable");
    }
  }

  private void requireCurrentEngagement(UUID orgId, Assignment assignment, Long supplied) {
    boolean returningOwner = assignment.engagementNo() > 0
        && (assignment.teacherId().equals(assignment.originalTeacherId())
            || Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                select exists(select 1 from exam_plan_duty_engagement
                  where org_id=? and assignment_id=? and teacher_id=? and engagement_no<?)
                """, Boolean.class, orgId, assignment.id(), assignment.teacherId(), assignment.engagementNo())));
    if ((supplied != null && supplied.longValue() != assignment.engagementNo())
        || (supplied == null && returningOwner)) {
      throw new ApiException(HttpStatus.CONFLICT, "DUTY_ENGAGEMENT_STALE",
          "该岗位已重新分派，请刷新监考安排后再确认本次接岗");
    }
  }

  private void requireCurrentEligibility(UUID orgId, UUID userId, Assignment assignment) {
    Teacher teacher;
    try {
      // Keep the order explicit: organization -> assignment -> teacher. Master-data updates
      // need not take the organization lock, so read eligibility only after locking the teacher.
      // Recheck the bound user here in case it changed while waiting for the teacher row.
      teacher = jdbcTemplate.queryForObject("""
          select teacher_no, status, qualifications::text, qualification_expiry, position,
                 is_homeroom, max_daily_sessions, preferred_campus,
                 leave_windows::text, duty_windows::text
          from teacher where org_id=? and id=? and app_user_id=? for update
          """, (rs, row) -> new Teacher(
              rs.getString("teacher_no"), rs.getString("status"),
              new ExamOperationsHardConstraints.TeacherEligibility(
                  parse(rs.getString("qualifications")), rs.getString("position"),
                  rs.getBoolean("is_homeroom"), rs.getObject("qualification_expiry", LocalDate.class),
                  rs.getInt("max_daily_sessions"), rs.getString("preferred_campus")),
              parse(rs.getString("leave_windows")), parse(rs.getString("duty_windows"))),
          orgId, assignment.teacherId(), userId);
    } catch (EmptyResultDataAccessException ignored) {
      throw new ApiException(HttpStatus.NOT_FOUND, "DUTY_ASSIGNMENT_NOT_FOUND",
          "Duty assignment is unavailable");
    }
    if (!"active".equals(teacher.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "DUTY_TEACHER_INACTIVE",
          "教师当前不在岗，请联系考务人员安排替补");
    }
    LocalDate dutyDate = assignment.startsAt().atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toLocalDate();
    if (!hardConstraints.teacherEligible(
        assignment.requiredRole(), assignment.qualification(), teacher.eligibility(), dutyDate)) {
      throw new ApiException(HttpStatus.CONFLICT, "DUTY_TEACHER_UNQUALIFIED",
          "当前教师资格不满足该岗位要求，请联系考务人员核查");
    }
    List<ExamOperationsHardConstraints.Window> hardLeaves = new ArrayList<>();
    jdbcTemplate.query("""
        select expression::text from exam_plan_constraint
        where org_id=? and revision_id=? and enabled and severity='HARD'
          and constraint_type='TEACHER_LEAVE' and resource_type='teacher'
          and resource_id in (?,?)
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
          var window = hardConstraints.expressionWindow(parse(rs.getString(1)));
          if (window != null) hardLeaves.add(window);
        }, orgId, assignment.revisionId(), teacher.teacherNo(), assignment.teacherId().toString());
    // Leave approval and this transition share the organization lock. Read mutable approved
    // requests as well as revision constraints, since leave can be approved after publication.
    hardLeaves.addAll(jdbcTemplate.query("""
        select starts_at, ends_at from exam_plan_leave_request
        where org_id=? and teacher_id=? and status='APPROVED'
          and starts_at<? and ends_at>?
        """, (rs, row) -> new ExamOperationsHardConstraints.Window(
            rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
        orgId, assignment.teacherId(), Timestamp.from(assignment.endsAt()), Timestamp.from(assignment.startsAt())));
    if (!hardConstraints.teacherAvailable(teacher.leaveWindows(), teacher.dutyWindows(), hardLeaves,
        assignment.startsAt(), assignment.endsAt())) {
      throw new ApiException(HttpStatus.CONFLICT, "DUTY_TEACHER_UNAVAILABLE",
          "该岗位时段与请假或可用时间冲突，请联系考务人员安排替补");
    }
  }

  private Assignment assignment(ResultSet rs, int row) throws SQLException {
    return new Assignment(
        rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
        rs.getObject("revision_id", UUID.class), rs.getObject("teacher_id", UUID.class),
        instant(rs.getTimestamp("starts_at")), instant(rs.getTimestamp("ends_at")),
        rs.getString("required_role"), parse(rs.getString("qualification")),
        rs.getString("status"), rs.getString("reason"),
        instant(rs.getTimestamp("responded_at")), instant(rs.getTimestamp("checked_in_at")),
        rs.getObject("original_teacher_id", UUID.class), rs.getLong("engagement_no"));
  }

  private DutyActionResponse response(
      Assignment assignment, String status, String reason, UUID substitutionCaseId, boolean replayed
  ) {
    return new DutyActionResponse(assignment.id(), assignment.revisionId(), status, reason,
        assignment.respondedAt(), assignment.checkedInAt(), substitutionCaseId, replayed, assignment.engagementNo());
  }

  private UUID substitutionId(UUID orgId, UUID assignmentId, long engagementNo) {
    List<UUID> ids = jdbcTemplate.query("""
        select id from exam_plan_substitution_case
        where org_id=? and duty_assignment_id=? and engagement_no=?
        """, (rs, row) -> rs.getObject(1, UUID.class), orgId, assignmentId, engagementNo);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private ObjectNode state(UUID assignmentId, String status, String reason, UUID substitutionCaseId) {
    ObjectNode state = objectMapper.createObjectNode();
    state.put("assignmentId", assignmentId.toString());
    state.put("status", status);
    if (reason != null) state.put("reason", reason);
    if (substitutionCaseId != null) state.put("substitutionCaseId", substitutionCaseId.toString());
    return state;
  }

  private String json(ObjectNode value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize duty event", exception);
    }
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private JsonNode parse(String value) {
    try {
      return objectMapper.readTree(value == null ? "null" : value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to read duty eligibility", exception);
    }
  }

  private record Assignment(
      UUID id, UUID planId, UUID revisionId, UUID teacherId,
      Instant startsAt, Instant endsAt, String requiredRole, JsonNode qualification,
      String status, String reason,
      Instant respondedAt, Instant checkedInAt, UUID originalTeacherId, long engagementNo
  ) {
  }

  private record Teacher(
      String teacherNo, String status, ExamOperationsHardConstraints.TeacherEligibility eligibility,
      JsonNode leaveWindows, JsonNode dutyWindows
  ) {
  }
}
