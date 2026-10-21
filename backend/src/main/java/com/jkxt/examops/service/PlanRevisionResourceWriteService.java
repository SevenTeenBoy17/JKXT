package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.RevisionResourceWriteDtos.BulkWriteRequest;
import com.jkxt.examops.api.RevisionResourceWriteDtos.BulkWriteResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanRevisionResourceWriteService {

  private static final Set<String> IMMUTABLE = Set.of("PUBLISHED", "EXECUTING", "CLOSED", "ARCHIVED");
  private static final Set<String> KINDS = Set.of("sessions", "rooms", "seats", "constraints");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public PlanRevisionResourceWriteService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public BulkWriteResponse write(
      UUID revisionId,
      String kind,
      BulkWriteRequest request,
      String idempotencyKey,
      UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    if (!KINDS.contains(kind)) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource kind is unavailable");
    String key = requireIdempotencyKey(idempotencyKey);
    // Serialize equal tenant/revision/kind/key requests so a concurrent replay cannot execute twice.
    jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?), hashtext(?))", Object.class,
        orgId.toString(), revisionId + ":" + kind + ":" + key);
    BulkWriteResponse replay = replay(orgId, revisionId, kind, key);
    if (replay != null) return replay;

    requireMutableRevision(orgId, revisionId);
    List<JsonNode> items = request == null ? List.of() : request.items();
    if (items == null || items.isEmpty()) {
      throw invalid("items", "At least one resource item is required");
    }
    if (items.size() > 1_000) {
      throw invalid("items", "A resource batch cannot exceed 1000 items");
    }
    switch (kind) {
      case "sessions" -> sessions(orgId, revisionId, items, actorId);
      case "rooms" -> rooms(orgId, revisionId, items, actorId);
      case "seats" -> seats(orgId, revisionId, items, actorId);
      case "constraints" -> constraints(orgId, revisionId, items, actorId);
      default -> throw new IllegalStateException("Unexpected resource kind " + kind);
    }
    UUID batchId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_bulk_ingest (
          id, org_id, revision_id, resource_kind, idempotency_key, accepted_count,
          result, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """, batchId, orgId, revisionId, kind, key, items.size(),
        "{\"acceptedCount\":" + items.size() + "}", actorId, actorId);
    touchRevision(orgId, revisionId, actorId);
    return new BulkWriteResponse(batchId, revisionId, kind, items.size(), false);
  }

  private void sessions(UUID orgId, UUID revisionId, List<JsonNode> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < items.size(); index++) {
      JsonNode item = object(items.get(index), index);
      String code = requiredText(item, "externalCode", index, 64);
      if (!seen.add(code)) throw invalid("items[" + index + "].externalCode", "Duplicate session externalCode in batch");
      String name = optionalText(item, "name", code, 160);
      Instant start = instant(item, "startsAt", index);
      Instant end = instant(item, "endsAt", index);
      if (!start.isBefore(end)) throw invalid("items[" + index + "].endsAt", "Session end must be after start");
      int candidateCount = nonNegative(item, "candidateCount", index, 0);
      int invigilators = positive(item, "requiredInvigilators", index, 1);
      int chiefs = nonNegative(item, "requiredChiefInvigilators", index, 1);
      if (chiefs > invigilators) {
        throw invalid("items[" + index + "].requiredChiefInvigilators", "Chief count cannot exceed required invigilators");
      }
      JsonNode requirements = objectMapper.createObjectNode()
          .put("examType", optionalText(item, "examType", "STANDARD", 40))
          .put("classCode", optionalText(item, "classCode", "", 40))
          .put("requiredInvigilators", invigilators)
          .put("requiredChiefInvigilators", chiefs)
          .put("preferredCampus", optionalText(item, "preferredCampus", "", 80));
      jdbcTemplate.update("""
          insert into exam_plan_session (
            org_id, revision_id, session_code, name, subject_name, grade, starts_at, ends_at,
            candidate_count, requirements, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, code, name, nullableText(item, "subjectName", 80),
          nullableText(item, "grade", 40), Timestamp.from(start), Timestamp.from(end), candidateCount,
          requirements.toString(), actorId, actorId);
    }
  }

  private void rooms(UUID orgId, UUID revisionId, List<JsonNode> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < items.size(); index++) {
      JsonNode item = object(items.get(index), index);
      String sessionCode = requiredText(item, "sessionExternalCode", index, 64);
      String roomCode = requiredText(item, "roomExternalCode", index, 30);
      if (!seen.add(sessionCode + "|" + roomCode)) throw invalid("items[" + index + "]", "Duplicate session/room allocation in batch");
      SessionRef session = session(orgId, revisionId, sessionCode);
      ClassroomRef room = classroom(orgId, roomCode);
      Instant start = instant(item, "startsAt", index);
      Instant end = instant(item, "endsAt", index);
      if (!start.equals(session.start()) || !end.equals(session.end())) {
        throw invalid("items[" + index + "]", "Room allocation time must exactly match its session");
      }
      int planned = nonNegative(item, "plannedCapacity", index, room.examCapacity());
      int allocated = nonNegative(item, "allocatedCandidates", index, 0);
      if (planned > room.examCapacity()) {
        throw invalid("items[" + index + "].plannedCapacity", "Planned capacity exceeds classroom exam capacity " + room.examCapacity());
      }
      if (allocated > planned) throw invalid("items[" + index + "].allocatedCandidates", "Allocated candidates exceed planned capacity");
      JsonNode features = item.path("requiredFeatures");
      if (!features.isMissingNode() && !features.isArray()) throw invalid("items[" + index + "].requiredFeatures", "requiredFeatures must be an array");
      jdbcTemplate.update("""
          insert into exam_plan_room_assignment (
            org_id, revision_id, session_id, classroom_id, starts_at, ends_at, planned_capacity,
            allocated_candidates, status, required_features, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, session.id(), room.id(), Timestamp.from(start), Timestamp.from(end),
          planned, allocated, item.path("locked").asBoolean(false) ? "LOCKED" : "PLANNED",
          features.isArray() ? features.toString() : "[]", actorId, actorId);
    }
  }

  private void seats(UUID orgId, UUID revisionId, List<JsonNode> items, UUID actorId) {
    Set<String> candidates = new HashSet<>();
    Set<String> roomSeats = new HashSet<>();
    for (int index = 0; index < items.size(); index++) {
      JsonNode item = object(items.get(index), index);
      String sessionCode = requiredText(item, "sessionExternalCode", index, 64);
      String roomCode = requiredText(item, "roomExternalCode", index, 30);
      String candidate = requiredText(item, "candidateExternalId", index, 96);
      String seatLabel = requiredText(item, "seatLabel", index, 48);
      if (!candidates.add(sessionCode + "|" + candidate)) throw invalid("items[" + index + "]", "Candidate is assigned twice in this batch");
      if (!roomSeats.add(sessionCode + "|" + roomCode + "|" + seatLabel)) throw invalid("items[" + index + "]", "Seat label is assigned twice in this batch");
      SessionRef session = session(orgId, revisionId, sessionCode);
      UUID roomAssignmentId = jdbcTemplate.query("""
          select r.id from exam_plan_room_assignment r
          join classroom c on c.org_id=r.org_id and c.id=r.classroom_id
          where r.org_id=? and r.revision_id=? and r.session_id=? and c.code=? and r.status<>'CANCELLED'
          """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
          orgId, revisionId, session.id(), roomCode);
      if (roomAssignmentId == null) throw invalid("items[" + index + "].roomExternalCode", "Room allocation does not exist in this revision");
      jdbcTemplate.update("""
          insert into exam_plan_seat_assignment (
            org_id, revision_id, session_id, room_assignment_id, candidate_external_id,
            seat_label, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?)
          """, orgId, revisionId, session.id(), roomAssignmentId, candidate, seatLabel, actorId, actorId);
    }
    assertRoomSeatCapacity(orgId, revisionId);
  }

  private void constraints(UUID orgId, UUID revisionId, List<JsonNode> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int index = 0; index < items.size(); index++) {
      JsonNode item = object(items.get(index), index);
      String type = requiredText(item, "constraintType", index, 64).toUpperCase(Locale.ROOT);
      String severity = requiredText(item, "severity", index, 12).toUpperCase(Locale.ROOT);
      if (!Set.of("HARD", "SOFT").contains(severity)) throw invalid("items[" + index + "].severity", "Severity must be HARD or SOFT");
      String resourceType = nullableText(item, "resourceType", 40);
      String resourceId = nullableText(item, "resourceExternalId", 96);
      String natural = type + "|" + severity + "|" + resourceType + "|" + resourceId;
      if (!seen.add(natural)) throw invalid("items[" + index + "]", "Duplicate constraint in batch");
      JsonNode expression = item.path("expression");
      if (!expression.isMissingNode() && !expression.isObject()) throw invalid("items[" + index + "].expression", "expression must be an object");
      validateConstraintReference(orgId, type, resourceType, resourceId, index);
      jdbcTemplate.update("""
          insert into exam_plan_constraint (
            org_id, revision_id, constraint_type, severity, resource_type, resource_id,
            expression, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, type, severity, resourceType, resourceId,
          expression.isObject() ? expression.toString() : "{}", actorId, actorId);
    }
  }

  private void validateConstraintReference(UUID orgId, String type, String resourceType, String resourceId, int index) {
    if (resourceId == null || resourceType == null) return;
    if ("teacher".equalsIgnoreCase(resourceType)) {
      int count = jdbcTemplate.queryForObject(
          "select count(*) from teacher where org_id=? and (teacher_no=? or id::text=?)", Integer.class,
          orgId, resourceId, resourceId);
      if (count == 0) throw invalid("items[" + index + "].resourceExternalId", "Teacher is not available in the current tenant");
    }
  }

  private void assertRoomSeatCapacity(UUID orgId, UUID revisionId) {
    Integer exceeded = jdbcTemplate.queryForObject("""
        select count(*) from (
          select r.id from exam_plan_room_assignment r
          left join exam_plan_seat_assignment s on s.org_id=r.org_id and s.room_assignment_id=r.id and s.status<>'CANCELLED'
          where r.org_id=? and r.revision_id=? group by r.id, r.allocated_candidates
          having count(s.id) > r.allocated_candidates
        ) x
        """, Integer.class, orgId, revisionId);
    if (exceeded != null && exceeded > 0) throw invalid("items", "Seat assignments exceed allocated candidates for a room");
  }

  private SessionRef session(UUID orgId, UUID revisionId, String code) {
    SessionRef value = jdbcTemplate.query("""
        select id, starts_at, ends_at from exam_plan_session
        where org_id=? and revision_id=? and session_code=? and status<>'CANCELLED'
        """, rs -> rs.next() ? new SessionRef(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant()) : null,
        orgId, revisionId, code);
    if (value == null) throw invalid("sessionExternalCode", "Session does not exist in this revision");
    return value;
  }

  private ClassroomRef classroom(UUID orgId, String code) {
    ClassroomRef value = jdbcTemplate.query("""
        select id, exam_capacity from classroom where org_id=? and code=? and status='active'
        """, rs -> rs.next() ? new ClassroomRef(rs.getObject(1, UUID.class), rs.getInt(2)) : null, orgId, code);
    if (value == null) throw invalid("roomExternalCode", "Active classroom does not exist in the current tenant");
    return value;
  }

  private void requireMutableRevision(UUID orgId, UUID revisionId) {
    String status = jdbcTemplate.query(
        "select status from exam_plan_revision where org_id=? and id=?",
        rs -> rs.next() ? rs.getString(1) : null, orgId, revisionId);
    if (status == null) throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    if (IMMUTABLE.contains(status)) throw new ApiException(HttpStatus.CONFLICT, "REVISION_IMMUTABLE", "Published revision is immutable; create a successor revision");
    if ("SOLVING".equals(status)) throw new ApiException(HttpStatus.CONFLICT, "REVISION_SOLVING", "Resources cannot change while planning is running");
  }

  private BulkWriteResponse replay(UUID orgId, UUID revisionId, String kind, String key) {
    return jdbcTemplate.query("""
        select id, accepted_count from exam_plan_bulk_ingest
        where org_id=? and revision_id=? and resource_kind=? and idempotency_key=?
        """, rs -> rs.next()
            ? new BulkWriteResponse(rs.getObject(1, UUID.class), revisionId, kind, rs.getInt(2), true)
            : null, orgId, revisionId, kind, key);
  }

  private void touchRevision(UUID orgId, UUID revisionId, UUID actorId) {
    jdbcTemplate.update("""
        update exam_plan_revision set status='DRAFT', validation_summary='{}'::jsonb,
          updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?
        """, actorId, orgId, revisionId);
  }

  private String requireIdempotencyKey(String value) {
    if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required for collection writes");
    String key = value.trim();
    if (key.length() > 160) throw invalid("Idempotency-Key", "Idempotency-Key exceeds 160 characters");
    return key;
  }

  private JsonNode object(JsonNode item, int index) {
    if (item == null || !item.isObject()) throw invalid("items[" + index + "]", "Resource item must be an object");
    return item;
  }

  private String requiredText(JsonNode item, String field, int index, int max) {
    String value = nullableText(item, field, max);
    if (value == null) throw invalid("items[" + index + "]." + field, field + " is required");
    return value;
  }

  private String nullableText(JsonNode item, String field, int max) {
    JsonNode node = item.get(field);
    if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) return null;
    String value = node.asText().trim();
    if (value.length() > max) throw invalid(field, field + " exceeds " + max + " characters");
    return value;
  }

  private String optionalText(JsonNode item, String field, String fallback, int max) {
    String value = nullableText(item, field, max);
    return value == null ? fallback : value;
  }

  private int nonNegative(JsonNode item, String field, int index, int fallback) {
    JsonNode node = item.get(field);
    if (node == null || node.isNull()) return fallback;
    if (!node.canConvertToInt() || node.intValue() < 0) throw invalid("items[" + index + "]." + field, field + " must be a non-negative integer");
    return node.intValue();
  }

  private int positive(JsonNode item, String field, int index, int fallback) {
    int value = nonNegative(item, field, index, fallback);
    if (value < 1) throw invalid("items[" + index + "]." + field, field + " must be positive");
    return value;
  }

  private Instant instant(JsonNode item, String field, int index) {
    String value = requiredText(item, field, index, 64);
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException exception) {
      try {
        return Instant.parse(value);
      } catch (DateTimeParseException ignored) {
        throw invalid("items[" + index + "]." + field, field + " must be an ISO-8601 timestamp with offset");
      }
    }
  }

  private ApiException invalid(String field, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_RESOURCE_INVALID", field + ": " + message);
  }

  private record SessionRef(UUID id, Instant start, Instant end) {
  }

  private record ClassroomRef(UUID id, int examCapacity) {
  }
}
