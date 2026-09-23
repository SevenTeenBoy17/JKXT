package com.jkxt.examops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.DutyAssignmentSelfServiceDtos.DutyActionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DutyAssignmentSelfService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public DutyAssignmentSelfService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> assignments(UUID userId) {
    UUID orgId = TenantContext.getRequired();
    return jdbcTemplate.queryForList("""
        select a.id, req.session_id, s.session_code as session_external_code,
               a.requirement_id, a.teacher_id, t.teacher_no, t.name as teacher_name,
               a.starts_at, a.ends_at, a.duty_role,
               coalesce(resp.status,
                 case a.status
                   when 'DRAFT' then 'PENDING_CONFIRMATION'
                   when 'PENDING_CONFIRMATION' then 'PENDING_CONFIRMATION'
                   when 'DECLINED' then 'REJECTED'
                   else a.status
                 end) as status,
               a.source, a.version, a.revision_id,
               resp.reason, resp.responded_at, resp.checked_in_at
        from exam_plan_duty_assignment a
        join exam_plan_revision r on r.id=a.revision_id and r.org_id=a.org_id
        join exam_plan_duty_requirement req on req.id=a.requirement_id and req.org_id=a.org_id
        join exam_plan_session s on s.id=req.session_id and s.org_id=a.org_id
        join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
        left join exam_plan_duty_response resp
          on resp.assignment_id=a.id and resp.org_id=a.org_id
        where a.org_id=? and t.app_user_id=?
          and r.status in ('PUBLISHED','EXECUTING') and a.status<>'CANCELLED'
        order by a.starts_at, s.session_code
        """, orgId, userId);
  }

  @Transactional
  public DutyActionResponse accept(UUID userId, UUID assignmentId) {
    return transition(userId, assignmentId, "ACCEPTED", null);
  }

  @Transactional
  public DutyActionResponse reject(UUID userId, UUID assignmentId, String reason) {
    return transition(userId, assignmentId, "REJECTED", reason.strip());
  }

  @Transactional
  public DutyActionResponse checkIn(UUID userId, UUID assignmentId) {
    return transition(userId, assignmentId, "CHECKED_IN", null);
  }

  private DutyActionResponse transition(UUID userId, UUID assignmentId, String target, String reason) {
    UUID orgId = TenantContext.getRequired();
    Assignment assignment = findOwnedAssignmentForUpdate(orgId, userId, assignmentId);
    String current = effectiveStatus(assignment.assignmentStatus(), assignment.responseStatus());

    if (current.equals(target)) {
      return response(assignment, current, assignment.reason(), substitutionId(orgId, assignmentId), true);
    }
    String required = target.equals("CHECKED_IN") ? "ACCEPTED" : "PENDING_CONFIRMATION";
    if (!current.equals(required)) {
      throw new ApiException(HttpStatus.CONFLICT, "DUTY_STATE_INVALID",
          "Duty assignment cannot transition from " + current + " to " + target);
    }

    Instant now = Instant.now();
    Instant respondedAt = target.equals("CHECKED_IN")
        ? (assignment.respondedAt() == null ? now : assignment.respondedAt())
        : now;
    Instant checkedInAt = target.equals("CHECKED_IN") ? now : null;
    String persistedReason = target.equals("REJECTED") ? reason : assignment.reason();

    jdbcTemplate.update("""
        insert into exam_plan_duty_response (
          org_id, assignment_id, teacher_id, status, reason, responded_at, checked_in_at,
          created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (org_id, assignment_id) do update set
          status=excluded.status, reason=excluded.reason, responded_at=excluded.responded_at,
          checked_in_at=excluded.checked_in_at, updated_by=excluded.updated_by,
          updated_at=now(), version=exam_plan_duty_response.version+1
        """, orgId, assignmentId, assignment.teacherId(), target, persistedReason,
        Timestamp.from(respondedAt), checkedInAt == null ? null : Timestamp.from(checkedInAt), userId, userId);

    UUID substitutionCaseId = null;
    if (target.equals("REJECTED")) {
      UUID proposedId = UUID.randomUUID();
      jdbcTemplate.update("""
          insert into exam_plan_substitution_case (
            id, org_id, revision_id, duty_assignment_id, teacher_id, reason,
            created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?)
          on conflict (org_id, duty_assignment_id) do nothing
          """, proposedId, orgId, assignment.revisionId(), assignmentId, assignment.teacherId(),
          persistedReason, userId, userId);
      substitutionCaseId = substitutionId(orgId, assignmentId);
    }

    ObjectNode before = state(assignmentId, current, assignment.reason(), null);
    ObjectNode after = state(assignmentId, target, persistedReason, substitutionCaseId);
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
        respondedAt, checkedInAt, substitutionCaseId, false);
  }

  private Assignment findOwnedAssignmentForUpdate(UUID orgId, UUID userId, UUID assignmentId) {
    try {
      return jdbcTemplate.queryForObject("""
          select a.id, p.id as plan_id, a.revision_id, a.teacher_id,
                 a.status as assignment_status, resp.status as response_status,
                 resp.reason, resp.responded_at, resp.checked_in_at
          from exam_plan_duty_assignment a
          join exam_plan_revision r on r.id=a.revision_id and r.org_id=a.org_id
          join exam_plan p on p.id=r.plan_id and p.org_id=r.org_id
          join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
          left join exam_plan_duty_response resp
            on resp.assignment_id=a.id and resp.org_id=a.org_id
          where a.org_id=? and a.id=? and t.app_user_id=?
            and r.status in ('PUBLISHED','EXECUTING') and a.status<>'CANCELLED'
          for update of a
          """, this::assignment, orgId, assignmentId, userId);
    } catch (EmptyResultDataAccessException ignored) {
      throw new ApiException(HttpStatus.NOT_FOUND, "DUTY_ASSIGNMENT_NOT_FOUND",
          "Duty assignment is unavailable");
    }
  }

  private Assignment assignment(ResultSet rs, int row) throws SQLException {
    return new Assignment(
        rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
        rs.getObject("revision_id", UUID.class), rs.getObject("teacher_id", UUID.class),
        rs.getString("assignment_status"), rs.getString("response_status"), rs.getString("reason"),
        instant(rs.getTimestamp("responded_at")), instant(rs.getTimestamp("checked_in_at")));
  }

  private DutyActionResponse response(
      Assignment assignment, String status, String reason, UUID substitutionCaseId, boolean replayed
  ) {
    return new DutyActionResponse(assignment.id(), assignment.revisionId(), status, reason,
        assignment.respondedAt(), assignment.checkedInAt(), substitutionCaseId, replayed);
  }

  private UUID substitutionId(UUID orgId, UUID assignmentId) {
    List<UUID> ids = jdbcTemplate.query("""
        select id from exam_plan_substitution_case where org_id=? and duty_assignment_id=?
        """, (rs, row) -> rs.getObject(1, UUID.class), orgId, assignmentId);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private String effectiveStatus(String assignmentStatus, String responseStatus) {
    if (responseStatus != null) return responseStatus;
    return switch (assignmentStatus) {
      case "DRAFT", "PENDING_CONFIRMATION" -> "PENDING_CONFIRMATION";
      case "DECLINED" -> "REJECTED";
      default -> assignmentStatus;
    };
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

  private record Assignment(
      UUID id, UUID planId, UUID revisionId, UUID teacherId,
      String assignmentStatus, String responseStatus, String reason,
      Instant respondedAt, Instant checkedInAt
  ) {
  }
}
