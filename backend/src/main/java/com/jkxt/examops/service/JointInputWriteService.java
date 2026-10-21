package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.JointInputDtos.AvoidanceInput;
import com.jkxt.examops.api.JointInputDtos.BulkResponse;
import com.jkxt.examops.api.JointInputDtos.CampusTravelInput;
import com.jkxt.examops.api.JointInputDtos.CandidateInput;
import com.jkxt.examops.api.JointInputDtos.CandidateWindowInput;
import com.jkxt.examops.api.JointInputDtos.DemandClassInput;
import com.jkxt.examops.api.JointInputDtos.DemandInput;
import com.jkxt.examops.api.JointInputDtos.DemandWindowInput;
import com.jkxt.examops.api.JointInputDtos.EnrollmentInput;
import com.jkxt.examops.api.JointInputDtos.RoomPoolInput;
import com.jkxt.examops.api.JointInputDtos.RoomWindowInput;
import com.jkxt.examops.api.JointInputDtos.SeatInput;
import com.jkxt.examops.api.JointInputDtos.SpecialRequirementInput;
import com.jkxt.examops.api.JointInputDtos.TeacherPoolInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JointInputWriteService {

  private static final int MAX_BATCH = 50_000;
  private static final Set<String> RESOURCES = Set.of(
      "demands", "demand-classes", "demand-windows", "candidates", "enrollments",
      "candidate-windows", "special-requirements", "room-pool", "room-windows",
      "seat-topology", "teacher-pool", "avoidances", "campus-travel");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JointInputWriteService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public BulkResponse replace(
      UUID revisionId,
      String resource,
      List<?> rawItems,
      String idempotencyKey,
      long expectedCursor,
      UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    if (!RESOURCES.contains(resource)) notFound();
    String key = key(idempotencyKey);
    List<?> items = rawItems == null ? List.of() : new ArrayList<>(rawItems);
    if (items.size() > MAX_BATCH) invalid("items", "collection cannot exceed " + MAX_BATCH + " records");
    String requestHash = hash(items);

    jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?), hashtext(?))", Object.class,
        orgId.toString(), revisionId + ":joint-input:" + resource + ":" + key);
    RevisionState revision = revisionForUpdate(orgId, revisionId);
    if (!Set.of("DRAFT", "INVALID").contains(revision.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "REVISION_IMMUTABLE",
          "Joint inputs can only be replaced on a DRAFT or INVALID revision");
    }
    BulkResponse replay = replay(orgId, revisionId, resource, key, requestHash);
    if (replay != null) return replay;
    if (revision.cursor() != expectedCursor) {
      throw new ApiException(HttpStatus.CONFLICT, "REVISION_CURSOR_CONFLICT",
          "Revision cursor is stale; reload the revision before replacing inputs");
    }

    deleteCollection(orgId, revisionId, resource);
    switch (resource) {
      case "demands" -> demands(orgId, revisionId, cast(items, DemandInput.class), actorId);
      case "demand-classes" -> demandClasses(orgId, revisionId, cast(items, DemandClassInput.class), actorId);
      case "demand-windows" -> demandWindows(orgId, revisionId, cast(items, DemandWindowInput.class), actorId);
      case "candidates" -> candidates(orgId, revisionId, cast(items, CandidateInput.class), actorId);
      case "enrollments" -> enrollments(orgId, revisionId, cast(items, EnrollmentInput.class), actorId);
      case "candidate-windows" -> candidateWindows(orgId, revisionId, cast(items, CandidateWindowInput.class), actorId);
      case "special-requirements" -> specialRequirements(orgId, revisionId,
          cast(items, SpecialRequirementInput.class), actorId);
      case "room-pool" -> roomPool(orgId, revisionId, cast(items, RoomPoolInput.class), actorId);
      case "room-windows" -> roomWindows(orgId, revisionId, cast(items, RoomWindowInput.class), actorId);
      case "seat-topology" -> seats(orgId, revisionId, cast(items, SeatInput.class), actorId);
      case "teacher-pool" -> teacherPool(orgId, revisionId, cast(items, TeacherPoolInput.class), actorId);
      case "avoidances" -> avoidances(orgId, revisionId, cast(items, AvoidanceInput.class), actorId);
      case "campus-travel" -> campusTravel(orgId, revisionId, cast(items, CampusTravelInput.class), actorId);
      default -> throw new IllegalStateException("Unhandled joint resource " + resource);
    }

    Long cursor = jdbcTemplate.queryForObject("""
        update exam_plan_revision set status='DRAFT', validation_summary='{}'::jsonb,
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? returning version
        """, Long.class, actorId, orgId, revisionId);
    BulkResponse response = new BulkResponse(revisionId, resource, items.size(), cursor, false);
    audit(orgId, revisionId, resource, key, requestHash, response, actorId);
    return response;
  }

  private void demands(UUID orgId, UUID revisionId, List<DemandInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      DemandInput value = require(items.get(i), i);
      String code = text(value.demandCode(), "items[" + i + "].demandCode", 64);
      if (!seen.add(code)) duplicate(i, "demandCode");
      String name = text(value.name(), "items[" + i + "].name", 160);
      int duration = positive(value.durationMinutes(), "items[" + i + "].durationMinutes");
      int candidates = nonNegative(value.candidateCount(), 0, "items[" + i + "].candidateCount");
      int invigilators = positive(defaulted(value.invigilatorCount(), 1), "items[" + i + "].invigilatorCount");
      int minRooms = positive(defaulted(value.minRooms(), 1), "items[" + i + "].minRooms");
      int maxRooms = positive(defaulted(value.maxRooms(), minRooms), "items[" + i + "].maxRooms");
      if (maxRooms < minRooms) invalid("items[" + i + "].maxRooms", "must be greater than or equal to minRooms");
      if (value.subjectId() != null) requireReference(orgId, "subject", value.subjectId(), "subjectId", i);
      jdbcTemplate.update("""
          insert into exam_plan_exam_demand (
            org_id, revision_id, demand_code, name, subject_id, duration_minutes, candidate_count,
            invigilator_count, min_rooms, max_rooms, split_allowed, required_room_features,
            settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
          """, orgId, revisionId, code, name, value.subjectId(), duration, candidates,
          invigilators, minRooms, maxRooms, Boolean.TRUE.equals(value.splitAllowed()),
          json(tokens(value.requiredRoomFeatures(), false, "items[" + i + "].requiredRoomFeatures")),
          json(map(value.settings())), actorId, actorId);
    }
  }

  private void demandClasses(UUID orgId, UUID revisionId, List<DemandClassInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      DemandClassInput value = require(items.get(i), i);
      UUID demandId = demand(orgId, revisionId, value.demandCode(), i);
      UUID classId = required(value.classId(), "items[" + i + "].classId");
      requireReference(orgId, "school_class", classId, "classId", i);
      if (!seen.add(demandId + "|" + classId)) duplicate(i, "demandCode/classId");
      jdbcTemplate.update("""
          insert into exam_plan_demand_class (
            org_id, revision_id, demand_id, class_id, expected_candidates, settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, demandId, classId,
          nonNegative(value.expectedCandidates(), 0, "items[" + i + "].expectedCandidates"),
          json(map(value.settings())), actorId, actorId);
    }
  }

  private void demandWindows(UUID orgId, UUID revisionId, List<DemandWindowInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      DemandWindowInput value = require(items.get(i), i);
      UUID demandId = demand(orgId, revisionId, value.demandCode(), i);
      String code = text(value.windowCode(), "items[" + i + "].windowCode", 64);
      if (!seen.add(demandId + "|" + code)) duplicate(i, "demandCode/windowCode");
      Times times = times(value.startsAt(), value.endsAt(), i);
      int weight = defaulted(value.preferenceWeight(), 0);
      if (weight < -1000 || weight > 1000) invalid("items[" + i + "].preferenceWeight", "must be between -1000 and 1000");
      jdbcTemplate.update("""
          insert into exam_plan_demand_window (
            org_id, revision_id, demand_id, window_code, starts_at, ends_at, preference_weight,
            locked, settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, demandId, code, Timestamp.from(times.start()), Timestamp.from(times.end()),
          weight, Boolean.TRUE.equals(value.locked()), json(map(value.settings())), actorId, actorId);
    }
  }

  private void candidates(UUID orgId, UUID revisionId, List<CandidateInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      CandidateInput value = require(items.get(i), i);
      String code = text(value.candidateCode(), "items[" + i + "].candidateCode", 96);
      if (!seen.add(code)) duplicate(i, "candidateCode");
      if (value.classId() != null) requireReference(orgId, "school_class", value.classId(), "classId", i);
      jdbcTemplate.update("""
          insert into exam_plan_candidate (
            org_id, revision_id, candidate_code, name, class_id, attributes, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, code, text(value.name(), "items[" + i + "].name", 120),
          value.classId(), json(map(value.attributes())), actorId, actorId);
    }
  }

  private void enrollments(UUID orgId, UUID revisionId, List<EnrollmentInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      EnrollmentInput value = require(items.get(i), i);
      UUID candidateId = candidate(orgId, revisionId, value.candidateCode(), i);
      UUID demandId = demand(orgId, revisionId, value.demandCode(), i);
      if (!seen.add(candidateId + "|" + demandId)) duplicate(i, "candidateCode/demandCode");
      String status = choice(value.status(), "ENROLLED", Set.of("ENROLLED", "EXEMPT", "CANCELLED"),
          "items[" + i + "].status");
      jdbcTemplate.update("""
          insert into exam_plan_enrollment (
            org_id, revision_id, candidate_id, demand_id, status, settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, candidateId, demandId, status, json(map(value.settings())), actorId, actorId);
    }
  }

  private void candidateWindows(UUID orgId, UUID revisionId, List<CandidateWindowInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      CandidateWindowInput value = require(items.get(i), i);
      UUID candidateId = candidate(orgId, revisionId, value.candidateCode(), i);
      String type = choice(value.windowType(), null, Set.of("AVAILABLE", "UNAVAILABLE"),
          "items[" + i + "].windowType");
      Times times = times(value.startsAt(), value.endsAt(), i);
      String natural = candidateId + "|" + type + "|" + times.start() + "|" + times.end();
      if (!seen.add(natural)) duplicate(i, "candidate/time window");
      jdbcTemplate.update("""
          insert into exam_plan_candidate_window (
            org_id, revision_id, candidate_id, window_type, starts_at, ends_at, reason,
            settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, candidateId, type, Timestamp.from(times.start()), Timestamp.from(times.end()),
          nullableText(value.reason(), 500), json(map(value.settings())), actorId, actorId);
    }
  }

  private void specialRequirements(
      UUID orgId, UUID revisionId, List<SpecialRequirementInput> items, UUID actorId
  ) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      SpecialRequirementInput value = require(items.get(i), i);
      UUID candidateId = candidate(orgId, revisionId, value.candidateCode(), i);
      UUID demandId = value.demandCode() == null || value.demandCode().isBlank()
          ? null : demand(orgId, revisionId, value.demandCode(), i);
      String code = text(value.requirementCode(), "items[" + i + "].requirementCode", 64);
      if (!seen.add(candidateId + "|" + demandId + "|" + code)) duplicate(i, "candidate/demand/requirementCode");
      jdbcTemplate.update("""
          insert into exam_plan_special_requirement (
            org_id, revision_id, candidate_id, demand_id, requirement_code, requirement_type,
            required_features, extra_minutes, separate_room, details, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, candidateId, demandId, code,
          text(value.requirementType(), "items[" + i + "].requirementType", 64),
          json(tokens(value.requiredFeatures(), false, "items[" + i + "].requiredFeatures")),
          nonNegative(value.extraMinutes(), 0, "items[" + i + "].extraMinutes"),
          Boolean.TRUE.equals(value.separateRoom()), json(map(value.details())), actorId, actorId);
    }
  }

  private void roomPool(UUID orgId, UUID revisionId, List<RoomPoolInput> items, UUID actorId) {
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      RoomPoolInput value = require(items.get(i), i);
      UUID classroomId = required(value.classroomId(), "items[" + i + "].classroomId");
      if (!seen.add(classroomId)) duplicate(i, "classroomId");
      Integer physicalCapacity = jdbcTemplate.query("""
          select exam_capacity from classroom where org_id=? and id=? and status='active'
          """, rs -> rs.next() ? rs.getInt(1) : null, orgId, classroomId);
      if (physicalCapacity == null) invalid("items[" + i + "].classroomId", "active classroom is not available in this tenant");
      int capacity = positive(value.examCapacity(), "items[" + i + "].examCapacity");
      if (capacity > physicalCapacity) invalid("items[" + i + "].examCapacity", "cannot exceed classroom exam capacity " + physicalCapacity);
      jdbcTemplate.update("""
          insert into exam_plan_room_pool (
            org_id, revision_id, classroom_id, campus_code, exam_capacity, features, status,
            settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, classroomId,
          text(value.campusCode(), "items[" + i + "].campusCode", 120), capacity,
          json(tokens(value.features(), false, "items[" + i + "].features")),
          choice(value.status(), "INCLUDED", Set.of("INCLUDED", "EXCLUDED", "LOCKED"), "items[" + i + "].status"),
          json(map(value.settings())), actorId, actorId);
    }
  }

  private void roomWindows(UUID orgId, UUID revisionId, List<RoomWindowInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      RoomWindowInput value = require(items.get(i), i);
      UUID poolId = roomPool(orgId, revisionId, value.classroomId(), i);
      String type = choice(value.windowType(), null, Set.of("AVAILABLE", "UNAVAILABLE"),
          "items[" + i + "].windowType");
      Times times = times(value.startsAt(), value.endsAt(), i);
      if (!seen.add(poolId + "|" + type + "|" + times.start() + "|" + times.end())) duplicate(i, "room/time window");
      jdbcTemplate.update("""
          insert into exam_plan_room_window (
            org_id, revision_id, room_pool_id, window_type, starts_at, ends_at, reason,
            settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, poolId, type, Timestamp.from(times.start()), Timestamp.from(times.end()),
          nullableText(value.reason(), 500), json(map(value.settings())), actorId, actorId);
    }
  }

  private void seats(UUID orgId, UUID revisionId, List<SeatInput> items, UUID actorId) {
    Set<String> codes = new HashSet<>();
    Set<String> grids = new HashSet<>();
    Map<UUID, Integer> activeByRoom = new LinkedHashMap<>();
    for (int i = 0; i < items.size(); i++) {
      SeatInput value = require(items.get(i), i);
      UUID poolId = roomPool(orgId, revisionId, value.classroomId(), i);
      String code = text(value.seatCode(), "items[" + i + "].seatCode", 48);
      if (!codes.add(poolId + "|" + code)) duplicate(i, "classroomId/seatCode");
      if ((value.rowNo() == null) != (value.columnNo() == null)) {
        invalid("items[" + i + "]", "rowNo and columnNo must both be supplied or both be omitted");
      }
      if (value.rowNo() != null) {
        if (value.rowNo() <= 0 || value.columnNo() <= 0) invalid("items[" + i + "]", "rowNo and columnNo must be positive");
        if (!grids.add(poolId + "|" + value.rowNo() + "|" + value.columnNo())) duplicate(i, "classroom grid position");
      }
      String status = choice(value.status(), "ACTIVE", Set.of("ACTIVE", "DISABLED"), "items[" + i + "].status");
      if ("ACTIVE".equals(status)) activeByRoom.merge(poolId, 1, Integer::sum);
      jdbcTemplate.update("""
          insert into exam_plan_room_seat (
            org_id, revision_id, room_pool_id, seat_code, row_no, column_no, features,
            status, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
          """, orgId, revisionId, poolId, code, value.rowNo(), value.columnNo(),
          json(tokens(value.features(), false, "items[" + i + "].features")), status, actorId, actorId);
    }
    for (Map.Entry<UUID, Integer> entry : activeByRoom.entrySet()) {
      Integer capacity = jdbcTemplate.queryForObject(
          "select exam_capacity from exam_plan_room_pool where org_id=? and revision_id=? and id=?",
          Integer.class, orgId, revisionId, entry.getKey());
      if (entry.getValue() > capacity) invalid("items", "active seat count exceeds room-pool exam capacity");
    }
  }

  private void teacherPool(UUID orgId, UUID revisionId, List<TeacherPoolInput> items, UUID actorId) {
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      TeacherPoolInput value = require(items.get(i), i);
      UUID teacherId = required(value.teacherId(), "items[" + i + "].teacherId");
      if (!seen.add(teacherId)) duplicate(i, "teacherId");
      Integer count = jdbcTemplate.queryForObject(
          "select count(*) from teacher where org_id=? and id=? and status='active'", Integer.class, orgId, teacherId);
      if (count == null || count == 0) invalid("items[" + i + "].teacherId", "active teacher is not available in this tenant");
      List<String> roles = tokens(value.eligibleRoles(), true, "items[" + i + "].eligibleRoles");
      if (!Set.of("INVIGILATOR", "CHIEF").containsAll(roles)) {
        invalid("items[" + i + "].eligibleRoles", "only INVIGILATOR and CHIEF are supported");
      }
      int maxDaily = positive(defaulted(value.maxDailySessions(), 4), "items[" + i + "].maxDailySessions");
      if (maxDaily > 12) invalid("items[" + i + "].maxDailySessions", "cannot exceed 12");
      jdbcTemplate.update("""
          insert into exam_plan_teacher_pool (
            org_id, revision_id, teacher_id, eligible_roles, max_daily_sessions, min_rest_minutes,
            preferred_campus, status, preferences, created_by, updated_by
          ) values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, teacherId, json(roles), maxDaily,
          nonNegative(value.minRestMinutes(), 0, "items[" + i + "].minRestMinutes"),
          nullableText(value.preferredCampus(), 120),
          choice(value.status(), "INCLUDED", Set.of("INCLUDED", "EXCLUDED", "RESERVE"), "items[" + i + "].status"),
          json(map(value.preferences())), actorId, actorId);
    }
  }

  private void avoidances(UUID orgId, UUID revisionId, List<AvoidanceInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      AvoidanceInput value = require(items.get(i), i);
      UUID teacherPoolId = teacherPool(orgId, revisionId, value.teacherId(), i);
      String target = choice(value.targetType(), null, Set.of("DEMAND", "CLASS", "CANDIDATE"),
          "items[" + i + "].targetType");
      UUID demandId = null;
      UUID classId = null;
      UUID candidateId = null;
      if ("DEMAND".equals(target)) demandId = demand(orgId, revisionId, value.demandCode(), i);
      if ("CLASS".equals(target)) {
        classId = required(value.classId(), "items[" + i + "].classId");
        requireReference(orgId, "school_class", classId, "classId", i);
      }
      if ("CANDIDATE".equals(target)) candidateId = candidate(orgId, revisionId, value.candidateCode(), i);
      String severity = choice(value.severity(), "HARD", Set.of("HARD", "SOFT"), "items[" + i + "].severity");
      String natural = teacherPoolId + "|" + target + "|" + demandId + "|" + classId + "|" + candidateId + "|" + severity;
      if (!seen.add(natural)) duplicate(i, "teacher/target/severity");
      jdbcTemplate.update("""
          insert into exam_plan_teacher_avoidance (
            org_id, revision_id, teacher_pool_id, target_type, demand_id, class_id, candidate_id,
            severity, reason, details, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, teacherPoolId, target, demandId, classId, candidateId, severity,
          text(value.reason(), "items[" + i + "].reason", 500), json(map(value.details())), actorId, actorId);
    }
  }

  private void campusTravel(UUID orgId, UUID revisionId, List<CampusTravelInput> items, UUID actorId) {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      CampusTravelInput value = require(items.get(i), i);
      String from = text(value.fromCampus(), "items[" + i + "].fromCampus", 120);
      String to = text(value.toCampus(), "items[" + i + "].toCampus", 120);
      if (from.equalsIgnoreCase(to)) invalid("items[" + i + "]", "fromCampus and toCampus must differ");
      if (!seen.add(from.toLowerCase(Locale.ROOT) + "|" + to.toLowerCase(Locale.ROOT))) duplicate(i, "campus route");
      jdbcTemplate.update("""
          insert into exam_plan_campus_travel (
            org_id, revision_id, from_campus, to_campus, travel_minutes, buffer_minutes,
            settings, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, orgId, revisionId, from, to,
          positive(value.travelMinutes(), "items[" + i + "].travelMinutes"),
          nonNegative(value.bufferMinutes(), 0, "items[" + i + "].bufferMinutes"),
          json(map(value.settings())), actorId, actorId);
    }
  }

  private void deleteCollection(UUID orgId, UUID revisionId, String resource) {
    String table = switch (resource) {
      case "demands" -> "exam_plan_exam_demand";
      case "demand-classes" -> "exam_plan_demand_class";
      case "demand-windows" -> "exam_plan_demand_window";
      case "candidates" -> "exam_plan_candidate";
      case "enrollments" -> "exam_plan_enrollment";
      case "candidate-windows" -> "exam_plan_candidate_window";
      case "special-requirements" -> "exam_plan_special_requirement";
      case "room-pool" -> "exam_plan_room_pool";
      case "room-windows" -> "exam_plan_room_window";
      case "seat-topology" -> "exam_plan_room_seat";
      case "teacher-pool" -> "exam_plan_teacher_pool";
      case "avoidances" -> "exam_plan_teacher_avoidance";
      case "campus-travel" -> "exam_plan_campus_travel";
      default -> throw new IllegalStateException("Unhandled joint resource " + resource);
    };
    jdbcTemplate.update("delete from " + table + " where org_id=? and revision_id=?", orgId, revisionId);
  }

  private RevisionState revisionForUpdate(UUID orgId, UUID revisionId) {
    RevisionState state = jdbcTemplate.query("""
        select status, version from exam_plan_revision where org_id=? and id=? for update
        """, rs -> rs.next() ? new RevisionState(rs.getString(1), rs.getLong(2)) : null, orgId, revisionId);
    if (state == null) notFound();
    return state;
  }

  private BulkResponse replay(
      UUID orgId, UUID revisionId, String resource, String key, String requestHash
  ) {
    String payload = jdbcTemplate.query("""
        select payload::text from audit_log
        where org_id=? and target_type='EXAM_PLAN_REVISION' and target_id=?
          and action='JOINT_INPUT_REPLACED'
          and payload->>'resource'=? and payload->>'idempotencyKey'=?
        order by created_at desc limit 1
        """, rs -> rs.next() ? rs.getString(1) : null, orgId, revisionId, resource, key);
    if (payload == null) return null;
    try {
      JsonNode node = objectMapper.readTree(payload);
      if (!requestHash.equals(node.path("requestHash").asText())) {
        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_PAYLOAD_CONFLICT",
            "Idempotency-Key was already used with a different request payload");
      }
      return new BulkResponse(revisionId, resource, node.path("acceptedCount").asInt(),
          node.path("revisionCursor").asLong(), true);
    } catch (ApiException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Stored joint-input idempotency record is unreadable", exception);
    }
  }

  private void audit(
      UUID orgId, UUID revisionId, String resource, String key, String requestHash,
      BulkResponse response, UUID actorId
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("resource", resource);
    payload.put("idempotencyKey", key);
    payload.put("requestHash", requestHash);
    payload.put("acceptedCount", response.acceptedCount());
    payload.put("revisionCursor", response.revisionCursor());
    jdbcTemplate.update("""
        insert into audit_log (
          org_id, actor_id, action, target_type, target_id, severity, payload
        ) values (?, ?, 'JOINT_INPUT_REPLACED', 'EXAM_PLAN_REVISION', ?, 'info', ?::jsonb)
        """, orgId, actorId, revisionId, json(payload));
  }

  private UUID demand(UUID orgId, UUID revisionId, String codeValue, int index) {
    String code = text(codeValue, "items[" + index + "].demandCode", 64);
    UUID id = jdbcTemplate.query("""
        select id from exam_plan_exam_demand where org_id=? and revision_id=? and demand_code=?
        """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, revisionId, code);
    if (id == null) invalid("items[" + index + "].demandCode", "demand does not exist in this revision");
    return id;
  }

  private UUID candidate(UUID orgId, UUID revisionId, String codeValue, int index) {
    String code = text(codeValue, "items[" + index + "].candidateCode", 96);
    UUID id = jdbcTemplate.query("""
        select id from exam_plan_candidate where org_id=? and revision_id=? and candidate_code=?
        """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, revisionId, code);
    if (id == null) invalid("items[" + index + "].candidateCode", "candidate does not exist in this revision");
    return id;
  }

  private UUID roomPool(UUID orgId, UUID revisionId, UUID classroomId, int index) {
    UUID classroom = required(classroomId, "items[" + index + "].classroomId");
    UUID id = jdbcTemplate.query("""
        select id from exam_plan_room_pool where org_id=? and revision_id=? and classroom_id=?
        """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, revisionId, classroom);
    if (id == null) invalid("items[" + index + "].classroomId", "classroom is not in this revision room pool");
    return id;
  }

  private UUID teacherPool(UUID orgId, UUID revisionId, UUID teacherId, int index) {
    UUID teacher = required(teacherId, "items[" + index + "].teacherId");
    UUID id = jdbcTemplate.query("""
        select id from exam_plan_teacher_pool where org_id=? and revision_id=? and teacher_id=?
        """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, revisionId, teacher);
    if (id == null) invalid("items[" + index + "].teacherId", "teacher is not in this revision teacher pool");
    return id;
  }

  private void requireReference(UUID orgId, String table, UUID id, String field, int index) {
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where org_id=? and id=?", Integer.class, orgId, id);
    if (count == null || count == 0) invalid("items[" + index + "]." + field, "reference is not available in this tenant");
  }

  private Times times(Instant startsAt, Instant endsAt, int index) {
    if (startsAt == null) invalid("items[" + index + "].startsAt", "is required");
    if (endsAt == null) invalid("items[" + index + "].endsAt", "is required");
    if (!startsAt.isBefore(endsAt)) invalid("items[" + index + "].endsAt", "must be after startsAt");
    return new Times(startsAt, endsAt);
  }

  private List<String> tokens(List<String> values, boolean required, String field) {
    List<String> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String value : values == null ? List.<String>of() : values) {
      String token = text(value, field, 80).toUpperCase(Locale.ROOT);
      if (!seen.add(token)) invalid(field, "contains duplicate token " + token);
      result.add(token);
    }
    if (required && result.isEmpty()) invalid(field, "must contain at least one value");
    return result;
  }

  private Map<String, Object> map(Map<String, Object> value) {
    return value == null ? Map.of() : value;
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to serialize joint input", exception);
    }
  }

  private String hash(Object value) {
    try {
      JsonNode canonical = canonical(objectMapper.valueToTree(value));
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(objectMapper.writeValueAsBytes(canonical));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to hash joint input", exception);
    }
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode result = objectMapper.createObjectNode();
      List<String> fields = new ArrayList<>();
      node.fieldNames().forEachRemaining(fields::add);
      fields.stream().sorted().forEach(field -> result.set(field, canonical(node.get(field))));
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = objectMapper.createArrayNode();
      node.forEach(item -> result.add(canonical(item)));
      return result;
    }
    return node;
  }

  private String key(String value) {
    if (value == null || value.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
          "Idempotency-Key is required");
    }
    String result = value.trim();
    if (result.length() > 160) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
          "Idempotency-Key cannot exceed 160 characters");
    }
    if (result.length() < 8) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
          "Idempotency-Key must contain at least 8 characters");
    }
    return result;
  }

  private String choice(String value, String fallback, Set<String> allowed, String field) {
    String result = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    if (result == null || !allowed.contains(result)) invalid(field, "must be one of " + allowed);
    return result;
  }

  private String text(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) invalid(field, "is required");
    String result = value.trim();
    if (result.length() > maxLength) invalid(field, "cannot exceed " + maxLength + " characters");
    return result;
  }

  private String nullableText(String value, int maxLength) {
    if (value == null || value.isBlank()) return null;
    String result = value.trim();
    if (result.length() > maxLength) invalid("text", "cannot exceed " + maxLength + " characters");
    return result;
  }

  private int positive(Integer value, String field) {
    if (value == null || value <= 0) invalid(field, "must be positive");
    return value;
  }

  private int nonNegative(Integer value, int fallback, String field) {
    int result = defaulted(value, fallback);
    if (result < 0) invalid(field, "cannot be negative");
    return result;
  }

  private int defaulted(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private <T> T require(T value, int index) {
    if (value == null) invalid("items[" + index + "]", "record cannot be null");
    return value;
  }

  private UUID required(UUID value, String field) {
    if (value == null) invalid(field, "is required");
    return value;
  }

  private <T> List<T> cast(List<?> items, Class<T> type) {
    List<T> result = new ArrayList<>(items.size());
    for (Object item : items) result.add(type.cast(item));
    return result;
  }

  private void duplicate(int index, String field) {
    invalid("items[" + index + "]." + field, "duplicates another record in this collection");
  }

  private void invalid(String field, String message) {
    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "JOINT_INPUT_INVALID", field + ": " + message);
  }

  private void notFound() {
    throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
  }

  private record RevisionState(String status, long cursor) {
  }

  private record Times(Instant start, Instant end) {
  }
}
