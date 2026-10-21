package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiErrorResponse;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.EmergencyChangeDtos.EmergencyChangeView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamPlanEmergencyService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ExamOperationsHardConstraints hardConstraints;
  private final PublishedResourceBaseline publishedBaseline;

  public ExamPlanEmergencyService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ExamOperationsHardConstraints hardConstraints,
      PublishedResourceBaseline publishedBaseline
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.hardConstraints = hardConstraints;
    this.publishedBaseline = publishedBaseline;
  }

  @Transactional
  public EmergencyChangeView create(
      UUID planId, UUID sourceRevisionId, JsonNode request, String idempotencyKey, UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    String key = requireKey(idempotencyKey);
    // Same lock order as publication and runtime substitution: freeze the current execution
    // baseline while taking a successor snapshot, without rewriting the published version.
    jdbcTemplate.queryForObject("select id from organization where id=? for no key update", UUID.class, orgId);
    Source source = requireSource(orgId, planId, sourceRevisionId);
    jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?), hashtext(?))", Object.class,
        orgId.toString(), sourceRevisionId + ":emergency:" + key);
    EmergencyChangeView replay = replay(orgId, sourceRevisionId, key);
    if (replay != null) return replay;
    if (request == null || !request.isObject()) throw invalid("Emergency change request must be an object");
    String type = request.path("type").asText("").trim().toUpperCase();
    if (type.isBlank()) throw invalid("Emergency change type is required");
    JsonNode payload = request.path("payload");
    if (!payload.isObject()) throw invalid("Emergency change payload must be an object");

    UUID successorId = UUID.randomUUID();
    int revisionNo = jdbcTemplate.queryForObject(
        "select coalesce(max(revision_no),0)+1 from exam_plan_revision where org_id=? and plan_id=?",
        Integer.class, orgId, planId);
    jdbcTemplate.update("""
        insert into exam_plan_revision (
          id, org_id, plan_id, revision_no, predecessor_id, status, source_type, title,
          change_reason, configuration, created_by, updated_by
        ) values (?, ?, ?, ?, ?, 'DRAFT', 'SUCCESSOR', ?, ?, ?::jsonb, ?, ?)
        """, successorId, orgId, planId, revisionNo, sourceRevisionId,
        "Emergency successor R" + revisionNo, "Emergency change " + type,
        source.configuration().toString(), actorId, actorId);
    cloneRevision(orgId, sourceRevisionId, successorId, actorId);

    Impact impact = apply(orgId, successorId, type, payload, actorId);
    double minimum = request.path("minimumRetentionRate").asDouble(0.0);
    if (impact.retentionRate() + 0.0000001 < minimum) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RETENTION_TARGET_UNMET",
          "Emergency replan retains " + impact.retentionRate() + " but requires at least " + minimum);
    }
    ObjectNode analysis = impact.analysis();
    analysis.put("minimumRetentionRate", minimum);
    analysis.put("retentionRate", impact.retentionRate());
    analysis.put("dutyBaseline", "CURRENT_EXECUTION");
    analysis.put("sourceRevisionId", sourceRevisionId.toString());

    UUID changeId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_emergency_change (
          id, org_id, revision_id, successor_revision_id, emergency_type, affected_resources,
          action_taken, status, idempotency_key, retention_rate, impact_analysis, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'ACTIVE', ?, ?, ?::jsonb, ?, ?)
        """, changeId, orgId, sourceRevisionId, successorId, type,
        payload.toString(), impact.action().toString(), key, impact.retentionRate(), analysis.toString(), actorId, actorId);
    // A published/executing/closed plan keeps its last published active version until the successor
    // completes approval and publication. Creating a draft successor must not roll the live plan
    // aggregate back to REVIEW_REQUIRED.
    if ("APPROVED".equals(source.status())) {
      jdbcTemplate.update("""
          update exam_plan set status='REVIEW_REQUIRED', active_revision_id=?, updated_by=?, updated_at=now(), version=version+1
          where org_id=? and id=?
          """, successorId, actorId, orgId, planId);
    }
    jdbcTemplate.update("""
        update exam_plan_revision set status='REVIEW_REQUIRED', updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=?
        """, actorId, orgId, successorId);
    UUID outboxId = event(orgId, successorId, changeId, type, analysis, actorId);
    createDeliveries(orgId, successorId, outboxId, type, payload, actorId);
    audit(orgId, planId, successorId, actorId, type, sourceRevisionId, analysis);
    return new EmergencyChangeView(changeId, changeId, sourceRevisionId, successorId, successorId,
        "REVIEW_REQUIRED", impact.retentionRate(), analysis, false);
  }

  private Impact apply(UUID orgId, UUID successorId, String type, JsonNode payload, UUID actorId) {
    return switch (type) {
      case "TEACHER_SICK_LEAVE", "TEACHER_MASS_ABSENCE" -> teacherAbsence(orgId, successorId, payload, actorId);
      case "ROOM_UNAVAILABLE" -> roomUnavailable(orgId, successorId, payload, actorId);
      case "CANDIDATE_COUNT_INCREASE" -> candidateIncrease(orgId, successorId, payload, actorId);
      case "NOTIFICATION_CHANNEL_FAILURE" -> notificationFailure(payload);
      case "NETWORK_INTERRUPTION_RETRY" -> noScheduleChange(type);
      default -> throw invalid("Unsupported emergency change type " + type);
    };
  }

  private Impact teacherAbsence(UUID orgId, UUID revisionId, JsonNode payload, UUID actorId) {
    Set<String> unavailable = textSet(payload.path("teacherIds"));
    if (unavailable.isEmpty()) throw invalid("teacherIds must contain at least one teacher number");
    Instant effectiveAt = parseInstant(payload.path("effectiveAt").asText(null));
    List<Map<String, Object>> affected = jdbcTemplate.queryForList("""
        select a.id, a.requirement_id, a.starts_at, a.ends_at, a.duty_role, t.teacher_no,
               q.qualification::text qualification, c.features::text room_features
        from exam_plan_duty_assignment a join teacher t on t.org_id=a.org_id and t.id=a.teacher_id
        join exam_plan_duty_requirement q on q.org_id=a.org_id and q.revision_id=a.revision_id
          and q.id=a.requirement_id
        left join exam_plan_room_assignment r on r.org_id=q.org_id and r.revision_id=q.revision_id
          and r.id=q.room_assignment_id
        left join classroom c on c.org_id=r.org_id and c.id=r.classroom_id
        where a.org_id=? and a.revision_id=? and a.status<>'CANCELLED'
        """, orgId, revisionId).stream()
        .filter(row -> unavailable.contains(String.valueOf(row.get("teacher_no"))))
        .filter(row -> effectiveAt == null || ((Timestamp) row.get("ends_at")).toInstant().isAfter(effectiveAt))
        .toList();
    recordAbsenceConstraints(orgId, revisionId, unavailable, affected, payload, actorId);
    Map<String, List<ExamOperationsHardConstraints.Window>> constraintLeaves = hardTeacherLeaves(orgId, revisionId);
    int total = jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_duty_assignment where org_id=? and revision_id=? and status<>'CANCELLED'",
        Integer.class, orgId, revisionId);
    int replaced = 0;
    for (Map<String, Object> duty : affected) {
      UUID replacement = findReplacement(orgId, revisionId, unavailable, constraintLeaves,
          String.valueOf(duty.get("duty_role")), json(String.valueOf(duty.get("qualification"))),
          hardConstraints.campusCode(json(String.valueOf(duty.get("room_features")))),
          (Timestamp) duty.get("starts_at"), (Timestamp) duty.get("ends_at"));
      if (replacement == null) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVIGILATOR_SHORTAGE",
            "No conflict-free substitute exists for every affected duty; no partial emergency result was saved");
      }
      jdbcTemplate.update("""
          update exam_plan_duty_assignment set teacher_id=?, source='SUBSTITUTION', status='PENDING_CONFIRMATION',
            updated_by=?, updated_at=now(), version=version+1 where org_id=? and revision_id=? and id=?
          """, replacement, actorId, orgId, revisionId, duty.get("id"));
      replaced++;
    }
    double retention = total == 0 ? 0.0 : (double) (total - affected.size()) / total;
    ObjectNode analysis = objectMapper.createObjectNode().put("baselineDuties", total)
        .put("affectedDuties", affected.size()).put("substitutedDuties", replaced)
        .put("unchangedDuties", total - affected.size());
    ObjectNode action = objectMapper.createObjectNode().put("strategy", "MINIMAL_DUTY_SUBSTITUTION")
        .put("substitutions", replaced);
    return new Impact(retention, analysis, action);
  }

  private UUID findReplacement(
      UUID orgId,
      UUID revisionId,
      Set<String> unavailable,
      Map<String, List<ExamOperationsHardConstraints.Window>> constraintLeaves,
      String dutyRole,
      JsonNode qualification,
      String roomCampus,
      Timestamp start,
      Timestamp end
  ) {
    UUID planId = jdbcTemplate.queryForObject(
        "select plan_id from exam_plan_revision where org_id=? and id=?", UUID.class, orgId, revisionId);
    List<PublishedResourceBaseline.TeacherDuty> reservations = new ArrayList<>(
        publishedBaseline.load(orgId, planId, null).teacherDuties());
    // This candidate replaces its own published plan; combine its changing draft with other
    // plans' current execution owners and legacy reservations, never superseded snapshots.
    reservations.addAll(jdbcTemplate.query("""
        select a.id,a.teacher_id,a.starts_at,a.ends_at from exam_plan_duty_assignment a
        join exam_plan_duty_requirement q on q.org_id=a.org_id and q.id=a.requirement_id
        join exam_plan_session s on s.org_id=q.org_id and s.id=q.session_id
        where a.org_id=? and a.revision_id=? and a.status not in ('CANCELLED','DECLINED')
          and s.status<>'CANCELLED'
          and (q.room_assignment_id is null or exists (
            select 1 from exam_plan_room_assignment r where r.org_id=q.org_id
              and r.id=q.room_assignment_id and r.status<>'CANCELLED'))
        """, (rs, row) -> new PublishedResourceBaseline.TeacherDuty(
            rs.getObject("id", UUID.class), rs.getObject("teacher_id", UUID.class),
            rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(), null),
        orgId, revisionId));
    List<Map<String, Object>> teachers = jdbcTemplate.queryForList("""
        select id, teacher_no, leave_windows::text, duty_windows::text,
               qualifications::text, position, is_homeroom, qualification_expiry,
               max_daily_sessions, preferred_campus
        from teacher where org_id=? and status='active'
        """, orgId);
    teachers.sort(Comparator
        .comparingInt((Map<String, Object> teacher) -> hardConstraints.campusPreferencePenalty(
            eligibility(teacher), roomCampus))
        .thenComparing(teacher -> String.valueOf(teacher.get("teacher_no"))));
    LocalDate dutyDate = start.toInstant().atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toLocalDate();
    for (Map<String, Object> teacher : teachers) {
      if (unavailable.contains(String.valueOf(teacher.get("teacher_no")))) continue;
      UUID id = (UUID) teacher.get("id");
      String teacherNo = String.valueOf(teacher.get("teacher_no"));
      ExamOperationsHardConstraints.TeacherEligibility eligibility = eligibility(teacher);
      if (!hardConstraints.teacherEligible(dutyRole, qualification, eligibility, dutyDate)) {
        continue;
      }
      List<ExamOperationsHardConstraints.Window> hardLeaves = new ArrayList<>();
      hardLeaves.addAll(constraintLeaves.getOrDefault(teacherNo, List.of()));
      hardLeaves.addAll(constraintLeaves.getOrDefault(id.toString(), List.of()));
      hardLeaves.addAll(jdbcTemplate.query("""
          select starts_at,ends_at from exam_plan_leave_request
          where org_id=? and teacher_id=? and status='APPROVED' and starts_at<? and ends_at>?
          """, (rs, row) -> new ExamOperationsHardConstraints.Window(
              rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
          orgId, id, end, start));
      if (!hardConstraints.teacherAvailable(
          json(String.valueOf(teacher.get("leave_windows"))),
          json(String.valueOf(teacher.get("duty_windows"))),
          hardLeaves,
          start.toInstant(), end.toInstant())) {
        continue;
      }
      List<PublishedResourceBaseline.TeacherDuty> teacherDuties = reservations.stream()
          .filter(duty -> duty.teacherId().equals(id)).distinct().toList();
      if (teacherDuties.stream().anyMatch(duty -> duty.startsAt().isBefore(end.toInstant())
          && duty.endsAt().isAfter(start.toInstant()))) continue;
      long dailySessions = teacherDuties.stream().filter(duty -> duty.startsAt()
          .atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toLocalDate().equals(dutyDate)).count();
      if (dailySessions < eligibility.maxDailySessions()) return id;
    }
    return null;
  }

  private ExamOperationsHardConstraints.TeacherEligibility eligibility(Map<String, Object> teacher) {
    Object expiry = teacher.get("qualification_expiry");
    LocalDate qualificationExpiry = expiry instanceof java.sql.Date date ? date.toLocalDate()
        : expiry instanceof LocalDate date ? date : null;
    Object limit = teacher.get("max_daily_sessions");
    int maxDailySessions = limit instanceof Number number ? number.intValue() : 0;
    return new ExamOperationsHardConstraints.TeacherEligibility(
        json(String.valueOf(teacher.get("qualifications"))),
        (String) teacher.get("position"), Boolean.TRUE.equals(teacher.get("is_homeroom")),
        qualificationExpiry, maxDailySessions, (String) teacher.get("preferred_campus"));
  }

  private Impact roomUnavailable(UUID orgId, UUID revisionId, JsonNode payload, UUID actorId) {
    Set<String> roomCodes = textSet(payload.path("roomCodes"));
    if (roomCodes.isEmpty()) throw invalid("roomCodes must contain at least one classroom code");
    List<Map<String, Object>> affected = jdbcTemplate.queryForList("""
        select r.id, r.starts_at, r.ends_at, r.planned_capacity, r.required_features::text, c.code
        from exam_plan_room_assignment r join classroom c on c.org_id=r.org_id and c.id=r.classroom_id
        where r.org_id=? and r.revision_id=? and r.status<>'CANCELLED'
        """, orgId, revisionId).stream()
        .filter(row -> roomCodes.contains(String.valueOf(row.get("code"))))
        .toList();
    for (Map<String, Object> room : affected) {
      UUID alternative = null;
      JsonNode requiredFeatures = combinedRequiredFeatures(
          orgId, revisionId, (UUID) room.get("id"), json(String.valueOf(room.get("required_features"))));
      for (Map<String, Object> candidate : jdbcTemplate.queryForList("""
          select c.id, c.code, c.features::text from classroom c
          where c.org_id=? and c.status='active' and c.exam_capacity>=?
            and not exists (
              select 1 from exam_plan_room_assignment r where r.org_id=c.org_id and r.revision_id=?
                and r.classroom_id=c.id and r.status<>'CANCELLED' and r.starts_at < ? and r.ends_at > ?
            ) order by c.exam_capacity, c.code
          """, orgId, room.get("planned_capacity"), revisionId, room.get("ends_at"), room.get("starts_at"))) {
        if (!roomCodes.contains(String.valueOf(candidate.get("code")))
            && hardConstraints.roomCompatible(
                json(String.valueOf(candidate.get("features"))), requiredFeatures)) {
          alternative = (UUID) candidate.get("id");
          break;
        }
      }
      if (alternative == null) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_FEATURE_OR_CAPACITY_UNAVAILABLE",
            "No active conflict-free classroom satisfies capacity, accessibility and equipment requirements");
      }
      jdbcTemplate.update("""
          update exam_plan_room_assignment set classroom_id=?, status='PLANNED', updated_by=?, updated_at=now(),
            version=version+1 where org_id=? and revision_id=? and id=?
          """, alternative, actorId, orgId, revisionId, room.get("id"));
    }
    int total = jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_duty_assignment where org_id=? and revision_id=? and status<>'CANCELLED'",
        Integer.class, orgId, revisionId);
    ObjectNode analysis = objectMapper.createObjectNode().put("affectedRooms", affected.size())
        .put("dutyAssignmentsRetained", total);
    return new Impact(total == 0 ? 0.0 : 1.0, analysis,
        objectMapper.createObjectNode().put("strategy", "ROOM_ONLY_REASSIGNMENT"));
  }

  private Impact candidateIncrease(UUID orgId, UUID revisionId, JsonNode payload, UUID actorId) {
    String sessionCode = payload.path("sessionCode").asText("").trim();
    int delta = payload.path("delta").asInt(0);
    if (sessionCode.isBlank() || delta <= 0) throw invalid("sessionCode and a positive delta are required");
    Map<String, Object> capacity = jdbcTemplate.query("""
        select s.id, s.candidate_count, coalesce(sum(r.planned_capacity),0) as capacity
        from exam_plan_session s left join exam_plan_room_assignment r on r.org_id=s.org_id
          and r.revision_id=s.revision_id and r.session_id=s.id and r.status<>'CANCELLED'
        where s.org_id=? and s.revision_id=? and s.session_code=? group by s.id, s.candidate_count
        """, rs -> rs.next() ? Map.of("id", rs.getObject(1, UUID.class), "count", rs.getInt(2), "capacity", rs.getInt(3)) : null,
        orgId, revisionId, sessionCode);
    if (capacity == null) throw invalid("Session " + sessionCode + " does not exist");
    int updated = (Integer) capacity.get("count") + delta;
    int plannedCapacity = (Integer) capacity.get("capacity");
    if (updated > plannedCapacity) {
      int capacityGap = updated - plannedCapacity;
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_CAPACITY_UNAVAILABLE",
          "Candidate increase creates capacityGap=" + capacityGap
              + "; no successor was created because full room/seat coverage is not feasible",
          List.of(
              new ApiErrorResponse.FieldError("capacityGap", Integer.toString(capacityGap)),
              new ApiErrorResponse.FieldError("successorCreated", "false")
          ));
    }
    jdbcTemplate.update("""
        update exam_plan_session set candidate_count=?, updated_by=?, updated_at=now(), version=version+1
        where org_id=? and revision_id=? and id=?
        """, updated, actorId, orgId, revisionId, capacity.get("id"));
    int total = jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_duty_assignment where org_id=? and revision_id=? and status<>'CANCELLED'",
        Integer.class, orgId, revisionId);
    ObjectNode analysis = objectMapper.createObjectNode().put("sessionCode", sessionCode).put("candidateDelta", delta)
        .put("newCandidateCount", updated).put("plannedCapacity", plannedCapacity)
        .put("capacityGap", 0)
        .put("seatReconciliationRequired", true)
        .put("fullyFeasible", true);
    return new Impact(total == 0 ? 0.0 : 1.0, analysis,
        objectMapper.createObjectNode().put("strategy", "CAPACITY_PRESERVING_COUNT_CHANGE"));
  }

  private void recordAbsenceConstraints(
      UUID orgId,
      UUID revisionId,
      Set<String> unavailable,
      List<Map<String, Object>> affected,
      JsonNode payload,
      UUID actorId
  ) {
    Instant requestedStart = parseInstant(payload.path("effectiveAt").asText(null));
    Instant requestedEnd = parseInstant(payload.path("endsAt").asText(null));
    for (String teacherNo : unavailable) {
      UUID teacherId = jdbcTemplate.query("""
          select id from teacher where org_id=? and teacher_no=? and status='active'
          """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, teacherNo);
      if (teacherId == null) throw invalid("Teacher " + teacherNo + " is not active in the current tenant");
      List<Map<String, Object>> teacherDuties = affected.stream()
          .filter(duty -> teacherNo.equals(String.valueOf(duty.get("teacher_no")))).toList();
      Instant start = requestedStart != null ? requestedStart : teacherDuties.stream()
          .map(duty -> ((Timestamp) duty.get("starts_at")).toInstant()).min(Instant::compareTo).orElse(Instant.now());
      Instant end = requestedEnd != null ? requestedEnd : teacherDuties.stream()
          .map(duty -> ((Timestamp) duty.get("ends_at")).toInstant()).max(Instant::compareTo)
          .orElse(start.plusSeconds(24 * 60 * 60));
      if (!start.isBefore(end)) end = start.plusSeconds(24 * 60 * 60);
      ObjectNode expression = objectMapper.createObjectNode().put("approved", true)
          .put("startsAt", start.toString()).put("endsAt", end.toString())
          .put("reason", "EMERGENCY_SICK_LEAVE");
      UUID existing = jdbcTemplate.query("""
          select id from exam_plan_constraint
          where org_id=? and revision_id=? and constraint_type='TEACHER_LEAVE'
            and severity='HARD' and resource_type='teacher' and resource_id=?
          """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, revisionId, teacherNo);
      if (existing == null) {
        jdbcTemplate.update("""
            insert into exam_plan_constraint (
              org_id, revision_id, constraint_type, severity, resource_type, resource_id,
              expression, enabled, created_by, updated_by
            ) values (?, ?, 'TEACHER_LEAVE', 'HARD', 'teacher', ?, ?::jsonb, true, ?, ?)
            """, orgId, revisionId, teacherNo, expression.toString(), actorId, actorId);
      } else {
        jdbcTemplate.update("""
            update exam_plan_constraint set expression=?::jsonb, enabled=true,
              updated_by=?, updated_at=now(), version=version+1
            where org_id=? and revision_id=? and id=?
            """, expression.toString(), actorId, orgId, revisionId, existing);
      }
    }
  }

  private Map<String, List<ExamOperationsHardConstraints.Window>> hardTeacherLeaves(UUID orgId, UUID revisionId) {
    Map<String, List<ExamOperationsHardConstraints.Window>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select resource_id, expression::text from exam_plan_constraint
        where org_id=? and revision_id=? and enabled and severity='HARD'
          and constraint_type='TEACHER_LEAVE' and resource_type='teacher'
        """, rs -> {
          ExamOperationsHardConstraints.Window window = hardConstraints.expressionWindow(json(rs.getString(2)));
          if (window != null) result.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(window);
        }, orgId, revisionId);
    return result;
  }

  private JsonNode combinedRequiredFeatures(
      UUID orgId,
      UUID revisionId,
      UUID roomAssignmentId,
      JsonNode directRequirements
  ) {
    ArrayNode combined = objectMapper.createArrayNode();
    Set<String> seen = new java.util.LinkedHashSet<>();
    if (directRequirements != null && directRequirements.isArray()) {
      directRequirements.forEach(feature -> {
        if (feature.isTextual() && seen.add(feature.asText().trim().toUpperCase())) combined.add(feature.asText());
      });
    }
    jdbcTemplate.query("""
        select distinct c.expression->>'requiredRoomFeature' feature
        from exam_plan_constraint c
        join exam_plan_seat_assignment s on s.org_id=c.org_id and s.revision_id=c.revision_id
          and s.candidate_external_id=c.resource_id and s.status<>'CANCELLED'
        where c.org_id=? and c.revision_id=? and c.enabled and c.severity='HARD'
          and c.constraint_type='SPECIAL_NEED' and c.resource_type='candidate'
          and s.room_assignment_id=? and jsonb_exists(c.expression, 'requiredRoomFeature')
        """, rs -> {
          String feature = rs.getString(1);
          if (feature != null && !feature.isBlank() && seen.add(feature.trim().toUpperCase())) combined.add(feature);
        }, orgId, revisionId, roomAssignmentId);
    return combined;
  }

  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (Exception ignored) {
      try {
        return Instant.parse(value);
      } catch (Exception ignoredAgain) {
        try {
          return LocalDateTime.parse(value).atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toInstant();
        } catch (Exception ignoredLocal) {
          return null;
        }
      }
    }
  }

  private Impact notificationFailure(JsonNode payload) {
    ObjectNode analysis = objectMapper.createObjectNode().put("scheduleChanged", false)
        .put("channel", payload.path("channel").asText("UNKNOWN"))
        .put("retryPolicy", "PERSISTED_OUTBOX");
    return new Impact(1.0, analysis, objectMapper.createObjectNode().put("strategy", "CHANNEL_RETRY"));
  }

  private Impact noScheduleChange(String type) {
    return new Impact(1.0, objectMapper.createObjectNode().put("scheduleChanged", false).put("type", type),
        objectMapper.createObjectNode().put("strategy", "IDEMPOTENT_REPLAY"));
  }

  private void cloneRevision(UUID orgId, UUID source, UUID target, UUID actorId) {
    jdbcTemplate.execute("create temporary table tmp_exam_session_map(old_id uuid primary key, new_id uuid not null) on commit drop");
    jdbcTemplate.execute("create temporary table tmp_exam_room_map(old_id uuid primary key, new_id uuid not null) on commit drop");
    jdbcTemplate.execute("create temporary table tmp_exam_req_map(old_id uuid primary key, new_id uuid not null) on commit drop");
    insertMappings("tmp_exam_session_map", ids("exam_plan_session", orgId, source));
    jdbcTemplate.update("""
        insert into exam_plan_session (
          id, org_id, revision_id, exam_id, session_code, name, subject_name, grade, starts_at, ends_at,
          candidate_count, status, requirements, created_by, updated_by
        ) select m.new_id, s.org_id, ?, s.exam_id, s.session_code, s.name, s.subject_name, s.grade,
          s.starts_at, s.ends_at, s.candidate_count, s.status, s.requirements, ?, ?
        from exam_plan_session s join tmp_exam_session_map m on m.old_id=s.id
        where s.org_id=? and s.revision_id=?
        """, target, actorId, actorId, orgId, source);
    insertMappings("tmp_exam_room_map", ids("exam_plan_room_assignment", orgId, source));
    jdbcTemplate.update("""
        insert into exam_plan_room_assignment (
          id, org_id, revision_id, session_id, classroom_id, starts_at, ends_at, planned_capacity,
          allocated_candidates, status, required_features, created_by, updated_by
        ) select rm.new_id, r.org_id, ?, sm.new_id, r.classroom_id, r.starts_at, r.ends_at,
          r.planned_capacity, r.allocated_candidates, r.status, r.required_features, ?, ?
        from exam_plan_room_assignment r join tmp_exam_room_map rm on rm.old_id=r.id
        join tmp_exam_session_map sm on sm.old_id=r.session_id
        where r.org_id=? and r.revision_id=?
        """, target, actorId, actorId, orgId, source);
    jdbcTemplate.update("""
        insert into exam_plan_seat_assignment (
          id, org_id, revision_id, session_id, room_assignment_id, candidate_external_id,
          seat_label, special_arrangement, status, created_by, updated_by
        ) select gen_random_uuid(), s.org_id, ?, sm.new_id, rm.new_id, s.candidate_external_id,
          s.seat_label, s.special_arrangement, s.status, ?, ?
        from exam_plan_seat_assignment s join tmp_exam_session_map sm on sm.old_id=s.session_id
        join tmp_exam_room_map rm on rm.old_id=s.room_assignment_id
        where s.org_id=? and s.revision_id=?
        """, target, actorId, actorId, orgId, source);
    jdbcTemplate.update("""
        insert into exam_plan_constraint (
          id, org_id, revision_id, constraint_type, severity, resource_type, resource_id,
          expression, enabled, created_by, updated_by
        ) select gen_random_uuid(), org_id, ?, constraint_type, severity, resource_type, resource_id,
          expression, enabled, ?, ? from exam_plan_constraint where org_id=? and revision_id=?
        """, target, actorId, actorId, orgId, source);
    insertMappings("tmp_exam_req_map", ids("exam_plan_duty_requirement", orgId, source));
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement (
          id, org_id, revision_id, session_id, room_assignment_id, duty_role, required_count,
          qualification, created_by, updated_by
        ) select qm.new_id, q.org_id, ?, sm.new_id, rm.new_id, q.duty_role, q.required_count,
          q.qualification, ?, ? from exam_plan_duty_requirement q
        join tmp_exam_req_map qm on qm.old_id=q.id
        join tmp_exam_session_map sm on sm.old_id=q.session_id
        left join tmp_exam_room_map rm on rm.old_id=q.room_assignment_id
        where q.org_id=? and q.revision_id=?
        """, target, actorId, actorId, orgId, source);
    jdbcTemplate.update("""
        insert into exam_plan_duty_assignment (
          id, org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at,
          duty_role, status, source, created_by, updated_by
        ) select gen_random_uuid(), a.org_id, ?, qm.new_id, a.teacher_id, a.starts_at, a.ends_at,
          a.duty_role,
          case when a.status='CANCELLED' then 'CANCELLED'
               when a.status in ('DECLINED','REJECTED') then 'DECLINED'
               else 'PENDING_CONFIRMATION' end,
          case when a.engagement_no>0 then 'SUBSTITUTION' else a.source end,
          ?, ? from exam_plan_effective_duty a
        join tmp_exam_req_map qm on qm.old_id=a.requirement_id
        where a.org_id=? and a.revision_id=?
        """, target, actorId, actorId, orgId, source);
  }

  private List<UUID> ids(String table, UUID orgId, UUID revisionId) {
    return jdbcTemplate.query("select id from " + table + " where org_id=? and revision_id=? order by id",
        (rs, row) -> rs.getObject(1, UUID.class), orgId, revisionId);
  }

  private void insertMappings(String table, List<UUID> ids) {
    List<Object[]> rows = ids.stream().map(id -> new Object[]{id, UUID.randomUUID()}).toList();
    jdbcTemplate.batchUpdate("insert into " + table + "(old_id,new_id) values (?,?)", rows);
  }

  private UUID event(UUID orgId, UUID revisionId, UUID changeId, String type, JsonNode analysis, UUID actorId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_outbox (
          id, org_id, revision_id, event_type, aggregate_type, aggregate_id, payload, created_by, updated_by
        ) values (?, ?, ?, 'EMERGENCY_CHANGE_CREATED', 'exam_plan_emergency_change', ?, ?::jsonb, ?, ?)
        """, id, orgId, revisionId, changeId,
        objectMapper.createObjectNode().put("type", type).set("impactAnalysis", analysis).toString(), actorId, actorId);
    return id;
  }

  private void createDeliveries(UUID orgId, UUID revisionId, UUID outboxId, String type, JsonNode payload, UUID actorId) {
    Set<String> explicit = textSet(payload.path("recipientIds"));
    List<String> recipients = explicit.isEmpty()
        ? jdbcTemplate.query("""
            select distinct t.teacher_no from exam_plan_duty_assignment a
            join teacher t on t.org_id=a.org_id and t.id=a.teacher_id
            where a.org_id=? and a.revision_id=? and a.status<>'CANCELLED'
            """, (rs, row) -> rs.getString(1), orgId, revisionId)
        : List.copyOf(explicit);
    String channel = "NOTIFICATION_CHANNEL_FAILURE".equals(type)
        ? payload.path("channel").asText("IN_APP").toUpperCase() : "IN_APP";
    String status = "NOTIFICATION_CHANNEL_FAILURE".equals(type) ? "FAILED" : "PENDING";
    for (String recipient : recipients) {
      jdbcTemplate.update("""
          insert into exam_plan_delivery (
            org_id, outbox_id, recipient_type, recipient_id, channel, status, created_by, updated_by
          ) values (?, ?, 'TEACHER', ?, ?, ?, ?, ?)
          on conflict (org_id, outbox_id, recipient_type, recipient_id, channel) do nothing
          """, orgId, outboxId, recipient, channel, status, actorId, actorId);
    }
  }

  private EmergencyChangeView replay(UUID orgId, UUID sourceRevisionId, String key) {
    return jdbcTemplate.query("""
        select id, revision_id, successor_revision_id, status, retention_rate, impact_analysis::text
        from exam_plan_emergency_change where org_id=? and revision_id=? and idempotency_key=?
        """, rs -> {
          if (!rs.next()) return null;
          UUID id = rs.getObject(1, UUID.class);
          UUID source = rs.getObject(2, UUID.class);
          UUID successor = rs.getObject(3, UUID.class);
          return new EmergencyChangeView(id, id, source, successor, successor,
              "REVIEW_REQUIRED", rs.getDouble(5), json(rs.getString(6)), true);
        }, orgId, sourceRevisionId, key);
  }

  private Source requireSource(UUID orgId, UUID planId, UUID revisionId) {
    Source value = jdbcTemplate.query("""
        select status, configuration::text from exam_plan_revision where org_id=? and plan_id=? and id=?
        """, rs -> rs.next() ? new Source(rs.getString(1), json(rs.getString(2))) : null,
        orgId, planId, revisionId);
    if (value == null) throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    if (!Set.of("APPROVED", "PUBLISHED", "EXECUTING", "CLOSED").contains(value.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_STATE_INVALID", "Emergency change requires an approved or published source revision");
    }
    return value;
  }

  private Set<String> textSet(JsonNode array) {
    Set<String> values = new java.util.LinkedHashSet<>();
    if (array != null && array.isArray()) array.forEach(value -> {
      if (value.isTextual() && !value.asText().isBlank()) values.add(value.asText().trim());
    });
    return Set.copyOf(values);
  }

  private String requireKey(String value) {
    if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    String key = value.trim();
    if (key.length() > 160) throw invalid("Idempotency-Key exceeds 160 characters");
    return key;
  }

  private JsonNode json(String value) {
    try {
      return objectMapper.readTree(value == null ? "{}" : value);
    } catch (Exception ignored) {
      return objectMapper.createObjectNode();
    }
  }

  private void audit(UUID orgId, UUID planId, UUID revisionId, UUID actorId, String type,
      UUID sourceRevision, JsonNode analysis) {
    jdbcTemplate.update("""
        insert into exam_plan_audit_event (
          org_id, plan_id, revision_id, action, actor_id, reason, before_state, after_state, created_by, updated_by
        ) values (?, ?, ?, 'EMERGENCY_SUCCESSOR_CREATED', ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """, orgId, planId, revisionId, actorId, type,
        objectMapper.createObjectNode().put("sourceRevisionId", sourceRevision.toString()).toString(),
        analysis.toString(), actorId, actorId);
  }

  private ApiException invalid(String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMERGENCY_CHANGE_INVALID", message);
  }

  private record Source(String status, JsonNode configuration) {
  }

  private record Impact(double retentionRate, ObjectNode analysis, ObjectNode action) {
  }
}
