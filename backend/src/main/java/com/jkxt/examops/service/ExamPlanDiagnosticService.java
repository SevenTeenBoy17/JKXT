package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.DiagnosticCaseDtos.DiagnosticCaseView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamPlanDiagnosticService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ExamPlanDiagnosticService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public DiagnosticCaseView diagnose(
      UUID planId, UUID revisionId, JsonNode request, String idempotencyKey, UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    requireRevision(orgId, planId, revisionId);
    String key = requireKey(idempotencyKey);
    String operation = "plan-revision:diagnostic:" + revisionId;
    jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?), hashtext(?))", Object.class,
        orgId.toString(), operation + ":" + key);
    UUID existing = jdbcTemplate.query(
        "select resource_id from api_idempotency_record where org_id=? and operation=? and idempotency_key=?",
        rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, operation, key);
    if (existing != null) return get(orgId, existing);
    if (request == null || !request.isObject()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "DIAGNOSTIC_REQUEST_INVALID", "Diagnostic request must be an object");
    }

    ArrayNode issues = objectMapper.createArrayNode();
    boolean recognized = false;
    if ("ACCESSIBLE".equalsIgnoreCase(request.path("disableRoomFeature").asText())) {
      recognized = true;
      int affected = jdbcTemplate.queryForObject("""
          select count(*) from exam_plan_constraint
          where org_id=? and revision_id=? and constraint_type='SPECIAL_NEED' and severity='HARD'
            and expression->>'requiredRoomFeature'='ACCESSIBLE'
          """, Integer.class, orgId, revisionId);
      if (affected > 0) {
        issues.add(issue("ACCESSIBLE_ROOM_CAPACITY_UNAVAILABLE", "student", affected,
            "Disabling the ACCESSIBLE feature removes the required accessible-room capacity for this scenario",
            "Restore an accessible room or move affected candidates to an equivalent accessible venue"));
      }
    }

    JsonNode unavailable = request.path("unavailableTeacherIds");
    if (unavailable.isArray()) {
      recognized = true;
      Set<String> teacherNos = new HashSet<>();
      unavailable.forEach(value -> { if (value.isTextual()) teacherNos.add(value.asText()); });
      Instant start = parse(request.path("startsAt").asText(null));
      Instant end = parse(request.path("endsAt").asText(null));
      if (start == null || end == null || !start.isBefore(end)) {
        issues.add(issue("DIAGNOSTIC_INTERVAL_INVALID", "revision", revisionId,
            "Teacher-unavailability diagnostics require a valid startsAt/endsAt interval",
            "Provide an ISO-8601 interval with startsAt before endsAt"));
      } else {
        int active = jdbcTemplate.queryForObject("select count(*) from teacher where org_id=? and status='active'", Integer.class, orgId);
        int unavailableKnown = (int) jdbcTemplate.query(
            "select teacher_no from teacher where org_id=? and status='active'",
            (rs, row) -> rs.getString(1), orgId).stream().filter(teacherNos::contains).count();
        int demand = jdbcTemplate.queryForObject("""
            select coalesce(sum((requirements->>'requiredInvigilators')::int),0)
            from exam_plan_session where org_id=? and revision_id=? and status<>'CANCELLED'
              and starts_at < ? and ends_at > ?
            """, Integer.class, orgId, revisionId, Timestamp.from(end), Timestamp.from(start));
        int available = Math.max(0, active - unavailableKnown);
        if (available < demand) {
          issues.add(issue("INVIGILATOR_SHORTAGE", "duty", "interval",
              "Counterfactual demand is " + demand + " duties but only " + available + " active teachers remain",
              "Move sessions, approve substitutes, or restore teacher availability"));
          issues.add(issue("DUTIES_UNCOVERED", "duty", "interval",
              (demand - available) + " simultaneous duties cannot be covered", "Open substitution cases for the uncovered posts"));
        }
      }
    }

    if (!recognized) {
      issues.add(issue("DIAGNOSTIC_CHANGE_UNSUPPORTED", "revision", revisionId,
          "The supplied counterfactual did not map to a supported diagnostic rule",
          "Use disableRoomFeature or unavailableTeacherIds with an effective interval"));
    }
    String status;
    if (issues.isEmpty()) {
      status = "FEASIBLE";
    } else {
      String firstCode = issues.get(0).path("code").asText();
      status = firstCode.equals("DIAGNOSTIC_CHANGE_UNSUPPORTED")
          || firstCode.equals("DIAGNOSTIC_INTERVAL_INVALID") ? "INVALID" : "INFEASIBLE";
    }
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_diagnostic_case (
          id, org_id, plan_id, revision_id, request, status, issues, created_by, updated_by
        ) values (?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
        """, id, orgId, planId, revisionId, request.toString(), status, issues.toString(), actorId, actorId);
    jdbcTemplate.update("""
        insert into api_idempotency_record (
          org_id, idempotency_key, operation, resource_type, resource_id, created_by, updated_by
        ) values (?, ?, ?, 'exam_plan_diagnostic_case', ?, ?, ?)
        """, orgId, key, operation, id, actorId, actorId);
    return new DiagnosticCaseView(id, planId, revisionId, status, issues);
  }

  private DiagnosticCaseView get(UUID orgId, UUID id) {
    return jdbcTemplate.query("""
        select id, plan_id, revision_id, status, issues::text from exam_plan_diagnostic_case
        where org_id=? and id=?
        """, rs -> {
          if (!rs.next()) return null;
          return new DiagnosticCaseView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
              rs.getObject(3, UUID.class), rs.getString(4), json(rs.getString(5)));
        }, orgId, id);
  }

  private void requireRevision(UUID orgId, UUID planId, UUID revisionId) {
    int count = jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_revision where org_id=? and plan_id=? and id=?", Integer.class,
        orgId, planId, revisionId);
    if (count == 0) throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
  }

  private String requireKey(String value) {
    if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    String key = value.trim();
    if (key.length() > 160) throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key exceeds 160 characters");
    return key;
  }

  private Instant parse(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (Exception ignored) {
      try {
        return Instant.parse(value);
      } catch (Exception ignoredAgain) {
        return null;
      }
    }
  }

  private JsonNode json(String value) {
    try {
      return objectMapper.readTree(value == null ? "[]" : value);
    } catch (Exception ignored) {
      return objectMapper.createArrayNode();
    }
  }

  private ObjectNode issue(String code, String resourceType, Object resourceId, String message, String suggestion) {
    return objectMapper.createObjectNode().put("code", code).put("severity", "HARD")
        .put("resourceType", resourceType).put("resourceId", String.valueOf(resourceId))
        .put("message", message).put("suggestion", suggestion);
  }
}
