package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.LeaveRequestDtos.CreateLeaveRequest;
import com.jkxt.examops.api.LeaveRequestDtos.DecideLeaveRequest;
import com.jkxt.examops.api.LeaveRequestDtos.LeaveRequestView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaveRequestService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public LeaveRequestService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public LeaveRequestView create(
      CreateLeaveRequest request, UUID actorId, String idempotencyKey
  ) {
    UUID orgId = TenantContext.getRequired();
    String key = requireKey(idempotencyKey);
    LeaveRequestView replay = findByKey(orgId, key, true);
    if (replay != null) return replay;
    if (!request.startsAt().isBefore(request.endsAt())) {
      throw invalid("Leave end must be later than its start");
    }
    int teacherExists = jdbcTemplate.queryForObject(
        "select count(*) from teacher where org_id=? and id=? and status='active'",
        Integer.class, orgId, request.teacherId());
    if (teacherExists != 1) throw notFound();

    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_leave_request (
          id, org_id, teacher_id, starts_at, ends_at, reason, idempotency_key,
          requested_by, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, orgId, request.teacherId(), Timestamp.from(request.startsAt()),
        Timestamp.from(request.endsAt()), request.reason().strip(), key, actorId, actorId, actorId);
    audit(orgId, null, null, actorId, "LEAVE_REQUEST_CREATED", request.reason(),
        state(id, "PENDING", request.teacherId(), request.startsAt(), request.endsAt(), 0));
    outbox(orgId, null, "LEAVE_REQUEST_CREATED", "exam_plan_leave_request", id,
        state(id, "PENDING", request.teacherId(), request.startsAt(), request.endsAt(), 0), actorId);
    return require(orgId, id, false);
  }

  @Transactional(readOnly = true)
  public List<LeaveRequestView> list(String status) {
    UUID orgId = TenantContext.getRequired();
    String normalized = normalizeStatus(status);
    String sql = baseQuery() + (normalized == null ? "" : " and l.status=?")
        + " order by l.created_at desc, l.id";
    return normalized == null
        ? jdbcTemplate.query(sql, this::view, orgId)
        : jdbcTemplate.query(sql, this::view, orgId, normalized);
  }

  @Transactional(readOnly = true)
  public List<LeaveRequestView> self(UUID userId) {
    return jdbcTemplate.query(baseQuery() + " and t.app_user_id=? order by l.starts_at desc, l.id",
        this::view, TenantContext.getRequired(), userId);
  }

  @Transactional
  public LeaveRequestView decide(UUID leaveRequestId, DecideLeaveRequest request, UUID actorId) {
    UUID orgId = TenantContext.getRequired();
    String target = request.decision().strip().toUpperCase(Locale.ROOT);
    if (!List.of("APPROVED", "REJECTED").contains(target)) {
      throw invalid("decision must be APPROVED or REJECTED");
    }
    String decisionReason = blankToNull(request.reason());
    if (target.equals("REJECTED") && decisionReason == null) {
      throw invalid("A rejection reason is required");
    }

    // Same lock order as publication: organization -> business row. Once either commits,
    // the other transaction must see the leave or the newly published duties, never neither.
    jdbcTemplate.queryForObject("select id from organization where id=? for no key update", UUID.class, orgId);
    LeaveRow leave = lock(orgId, leaveRequestId);
    if (leave.status().equals(target)) return require(orgId, leaveRequestId, true);
    if (!leave.status().equals("PENDING")) {
      throw new ApiException(HttpStatus.CONFLICT, "LEAVE_STATE_INVALID",
          "Leave request cannot transition from " + leave.status() + " to " + target);
    }
    if (target.equals("APPROVED") && approvedOverlap(orgId, leave)) {
      throw new ApiException(HttpStatus.CONFLICT, "LEAVE_TIME_CONFLICT",
          "Teacher already has an approved overlapping leave request");
    }

    jdbcTemplate.update("""
        update exam_plan_leave_request set status=?, decided_by=?, decision_reason=?, decided_at=now(),
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='PENDING'
        """, target, actorId, decisionReason, actorId, orgId, leaveRequestId);

    int cases = 0;
    if (target.equals("APPROVED")) {
      List<Duty> duties = overlappingPublishedDuties(orgId, leave);
      for (Duty duty : duties) {
        UUID caseId = UUID.randomUUID();
        jdbcTemplate.update("""
            insert into exam_plan_substitution_case (
              id, org_id, revision_id, duty_assignment_id, engagement_no, teacher_id, leave_request_id,
              reason, created_by, updated_by
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (org_id, duty_assignment_id, engagement_no) do update set
              leave_request_id=coalesce(exam_plan_substitution_case.leave_request_id, excluded.leave_request_id),
              status=case when exam_plan_substitution_case.status='CANCELLED' then 'OPEN'
                          else exam_plan_substitution_case.status end,
              updated_by=excluded.updated_by, updated_at=now(),
              version=exam_plan_substitution_case.version+1
            """, caseId, orgId, duty.revisionId(), duty.assignmentId(), duty.engagementNo(), leave.teacherId(),
            leaveRequestId, "Approved leave: " + leave.reason(), actorId, actorId);
        cases++;
        ObjectNode payload = objectMapper.createObjectNode()
            .put("leaveRequestId", leaveRequestId.toString())
            .put("dutyAssignmentId", duty.assignmentId().toString())
            .put("teacherId", leave.teacherId().toString())
            .put("engagementNo", duty.engagementNo());
        outbox(orgId, duty.revisionId(), "SUBSTITUTION_REQUIRED",
            "exam_plan_substitution_case", substitutionId(orgId, duty.assignmentId(), duty.engagementNo()), payload, actorId);
        audit(orgId, duty.planId(), duty.revisionId(), actorId, "SUBSTITUTION_CASE_OPENED",
            leave.reason(), payload);
      }
    }

    ObjectNode after = state(leaveRequestId, target, leave.teacherId(), leave.startsAt(), leave.endsAt(), cases);
    audit(orgId, null, null, actorId, "LEAVE_REQUEST_" + target, decisionReason, after);
    outbox(orgId, null, "LEAVE_REQUEST_" + target, "exam_plan_leave_request",
        leaveRequestId, after, actorId);
    return require(orgId, leaveRequestId, false);
  }

  private LeaveRow lock(UUID orgId, UUID id) {
    List<LeaveRow> rows = jdbcTemplate.query("""
        select id, teacher_id, starts_at, ends_at, reason, status
        from exam_plan_leave_request where org_id=? and id=? for update
        """, (rs, row) -> new LeaveRow(
        rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
        rs.getString("reason"), rs.getString("status")), orgId, id);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private boolean approvedOverlap(UUID orgId, LeaveRow leave) {
    return jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_leave_request
        where org_id=? and teacher_id=? and id<>? and status='APPROVED'
          and tstzrange(starts_at, ends_at, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        """, Integer.class, orgId, leave.teacherId(), leave.id(), Timestamp.from(leave.startsAt()),
        Timestamp.from(leave.endsAt())) > 0;
  }

  private List<Duty> overlappingPublishedDuties(UUID orgId, LeaveRow leave) {
    return jdbcTemplate.query("""
        select a.id assignment_id, a.revision_id, r.plan_id, d.engagement_no
        from exam_plan_duty_assignment a
        join exam_plan_effective_duty d on d.org_id=a.org_id and d.id=a.id
        join exam_plan_duty_requirement q
          on q.org_id=a.org_id and q.id=a.requirement_id and q.revision_id=a.revision_id
        join exam_plan_session session
          on session.org_id=q.org_id and session.id=q.session_id and session.revision_id=a.revision_id
        left join exam_plan_room_assignment room
          on room.org_id=q.org_id and room.id=q.room_assignment_id and room.revision_id=a.revision_id
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        join exam_plan p on p.org_id=r.org_id and p.id=r.plan_id and p.active_revision_id=r.id
        where a.org_id=? and d.teacher_id=? and d.status not in ('CANCELLED','COMPLETED')
          and r.status in ('PUBLISHED','EXECUTING')
          and session.status<>'CANCELLED' and (q.room_assignment_id is null or room.status<>'CANCELLED')
          and tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        order by a.starts_at, a.id for update of a
        """, (rs, row) -> new Duty(rs.getObject("assignment_id", UUID.class),
        rs.getObject("revision_id", UUID.class), rs.getObject("plan_id", UUID.class), rs.getLong("engagement_no")),
        orgId, leave.teacherId(), Timestamp.from(leave.startsAt()), Timestamp.from(leave.endsAt()));
  }

  private UUID substitutionId(UUID orgId, UUID assignmentId, long engagementNo) {
    return jdbcTemplate.queryForObject(
        "select id from exam_plan_substitution_case where org_id=? and duty_assignment_id=? and engagement_no=?",
        UUID.class, orgId, assignmentId, engagementNo);
  }

  private LeaveRequestView require(UUID orgId, UUID id, boolean replayed) {
    List<LeaveRequestView> rows = jdbcTemplate.query(baseQuery() + " and l.id=?",
        this::view, orgId, id);
    if (rows.isEmpty()) throw notFound();
    LeaveRequestView value = rows.getFirst();
    return new LeaveRequestView(value.id(), value.teacherId(), value.teacherNo(), value.teacherName(),
        value.startsAt(), value.endsAt(), value.reason(), value.status(), value.requestedBy(),
        value.decidedBy(), value.decisionReason(), value.decidedAt(), value.substitutionCaseCount(),
        value.version(), value.createdAt(), value.updatedAt(), replayed);
  }

  private LeaveRequestView findByKey(UUID orgId, String key, boolean replayed) {
    List<UUID> ids = jdbcTemplate.query(
        "select id from exam_plan_leave_request where org_id=? and idempotency_key=?",
        (rs, row) -> rs.getObject(1, UUID.class), orgId, key);
    return ids.isEmpty() ? null : require(orgId, ids.getFirst(), replayed);
  }

  private String baseQuery() {
    return """
        select l.id, l.teacher_id, t.teacher_no, t.name teacher_name, l.starts_at, l.ends_at,
          l.reason, l.status, l.requested_by, l.decided_by, l.decision_reason, l.decided_at,
          (select count(*) from exam_plan_substitution_case s
             where s.org_id=l.org_id and s.leave_request_id=l.id) substitution_count,
          l.version, l.created_at, l.updated_at
        from exam_plan_leave_request l
        join teacher t on t.org_id=l.org_id and t.id=l.teacher_id
        where l.org_id=?
        """;
  }

  private LeaveRequestView view(ResultSet rs, int row) throws SQLException {
    return new LeaveRequestView(rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class),
        rs.getString("teacher_no"), rs.getString("teacher_name"),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
        rs.getString("reason"), rs.getString("status"), rs.getObject("requested_by", UUID.class),
        rs.getObject("decided_by", UUID.class), rs.getString("decision_reason"),
        instant(rs.getTimestamp("decided_at")), rs.getInt("substitution_count"), rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), false);
  }

  private ObjectNode state(
      UUID id, String status, UUID teacherId, Instant startsAt, Instant endsAt, int cases
  ) {
    return objectMapper.createObjectNode().put("leaveRequestId", id.toString())
        .put("teacherId", teacherId.toString()).put("startsAt", startsAt.toString())
        .put("endsAt", endsAt.toString()).put("status", status).put("substitutionCaseCount", cases);
  }

  private void audit(
      UUID orgId, UUID planId, UUID revisionId, UUID actorId, String action,
      String reason, ObjectNode after
  ) {
    jdbcTemplate.update("""
        insert into exam_plan_audit_event (
          org_id, plan_id, revision_id, action, actor_id, reason,
          before_state, after_state, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?::jsonb, ?, ?)
        """, orgId, planId, revisionId, action, actorId, blankToNull(reason), after.toString(), actorId, actorId);
  }

  private void outbox(
      UUID orgId, UUID revisionId, String eventType, String aggregateType,
      UUID aggregateId, ObjectNode payload, UUID actorId
  ) {
    jdbcTemplate.update("""
        insert into exam_plan_outbox (
          org_id, revision_id, event_type, aggregate_type, aggregate_id, payload,
          created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """, orgId, revisionId, eventType, aggregateType, aggregateId, payload.toString(), actorId, actorId);
  }

  private String requireKey(String value) {
    if (value == null || value.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    }
    String key = value.strip();
    if (key.length() > 160) throw invalid("Idempotency-Key exceeds 160 characters");
    return key;
  }

  private String normalizeStatus(String value) {
    if (value == null || value.isBlank()) return null;
    String status = value.strip().toUpperCase(Locale.ROOT);
    if (!List.of("PENDING", "APPROVED", "REJECTED", "CANCELLED").contains(status)) {
      throw invalid("Unknown leave status");
    }
    return status;
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "LEAVE_REQUEST_NOT_FOUND", "Leave request is unavailable");
  }

  private ApiException invalid(String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LEAVE_REQUEST_INVALID", message);
  }

  private record LeaveRow(
      UUID id, UUID teacherId, Instant startsAt, Instant endsAt, String reason, String status
  ) {
  }

  private record Duty(UUID assignmentId, UUID revisionId, UUID planId, long engagementNo) {
  }
}
