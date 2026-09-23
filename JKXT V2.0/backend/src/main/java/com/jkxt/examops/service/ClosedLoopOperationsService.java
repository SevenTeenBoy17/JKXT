package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiErrorResponse;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.ClosedLoopDtos.ChecklistItemView;
import com.jkxt.examops.api.ClosedLoopDtos.CompleteChecklistItemRequest;
import com.jkxt.examops.api.ClosedLoopDtos.CreateChecklistItemRequest;
import com.jkxt.examops.api.ClosedLoopDtos.CreateIncidentRequest;
import com.jkxt.examops.api.ClosedLoopDtos.IncidentView;
import com.jkxt.examops.api.ClosedLoopDtos.LifecycleIssue;
import com.jkxt.examops.api.ClosedLoopDtos.LifecycleTransitionView;
import com.jkxt.examops.api.ClosedLoopDtos.ResolveIncidentRequest;
import com.jkxt.examops.api.ClosedLoopDtos.ResolveSubstitutionRequest;
import com.jkxt.examops.api.ClosedLoopDtos.SubstitutionCaseView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClosedLoopOperationsService {

  private static final Set<String> LIVE_REVISION_STATES = Set.of("PUBLISHED", "EXECUTING", "CLOSED");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ExamOperationsHardConstraints hardConstraints;

  public ClosedLoopOperationsService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ExamOperationsHardConstraints hardConstraints
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.hardConstraints = hardConstraints;
  }

  @Transactional(readOnly = true)
  public List<SubstitutionCaseView> substitutions(String status) {
    UUID orgId = TenantContext.getRequired();
    String normalized = normalizeOptional(status,
        Set.of("OPEN", "ASSIGNED", "RESOLVED", "CANCELLED"), "SUBSTITUTION_STATUS_INVALID");
    String sql = substitutionQuery() + (normalized == null ? "" : " and s.status=?")
        + " order by a.starts_at, s.created_at, s.id";
    return normalized == null
        ? jdbcTemplate.query(sql, this::substitutionView, orgId)
        : jdbcTemplate.query(sql, this::substitutionView, orgId, normalized);
  }

  @Transactional
  public SubstitutionCaseView resolveSubstitution(
      UUID caseId, ResolveSubstitutionRequest request, UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    SubstitutionRow substitution = lockSubstitution(orgId, caseId);
    if (substitution.status().equals("RESOLVED")) {
      if (request.replacementTeacherId().equals(substitution.replacementTeacherId())) {
        return requireSubstitution(orgId, caseId);
      }
      throw conflict("SUBSTITUTION_STATE_INVALID", "Substitution case is already resolved");
    }
    if (!Set.of("OPEN", "ASSIGNED").contains(substitution.status())) {
      throw conflict("SUBSTITUTION_STATE_INVALID", "Substitution case is not open");
    }
    if (request.replacementTeacherId().equals(substitution.teacherId())) {
      throw invalid("SUBSTITUTION_REPLACEMENT_INVALID", "Replacement must be a different teacher");
    }

    TeacherRow replacement = requireQualifiedReplacement(orgId, substitution, request.replacementTeacherId());
    jdbcTemplate.update("""
        update exam_plan_substitution_case set replacement_teacher_id=?, status='RESOLVED',
          resolution_reason=?, resolved_by=?, resolved_at=now(), updated_by=?, updated_at=now(),
          version=version+1 where org_id=? and id=? and status in ('OPEN','ASSIGNED')
        """, replacement.id(), request.reason().strip(), actorId, actorId, orgId, caseId);

    ObjectNode payload = objectMapper.createObjectNode()
        .put("substitutionCaseId", caseId.toString())
        .put("dutyAssignmentId", substitution.assignmentId().toString())
        .put("originalTeacherId", substitution.teacherId().toString())
        .put("replacementTeacherId", replacement.id().toString())
        .put("replacementTeacherNo", replacement.teacherNo());
    UUID outboxId = outbox(orgId, substitution.revisionId(), "SUBSTITUTION_RESOLVED",
        "exam_plan_substitution_case", caseId, payload, actorId);
    jdbcTemplate.update("""
        insert into exam_plan_delivery (
          org_id, outbox_id, recipient_type, recipient_id, channel, status,
          created_by, updated_by
        ) values (?, ?, 'TEACHER', ?, 'IN_APP', 'PENDING', ?, ?)
        on conflict (org_id, outbox_id, recipient_type, recipient_id, channel) do nothing
        """, orgId, outboxId, replacement.teacherNo(), actorId, actorId);
    audit(orgId, substitution.planId(), substitution.revisionId(), actorId,
        "SUBSTITUTION_RESOLVED", request.reason(), objectMapper.createObjectNode(), payload);
    return requireSubstitution(orgId, caseId);
  }

  @Transactional
  public LifecycleTransitionView transition(
      UUID planId, UUID revisionId, String targetStatus, UUID actorId, long expectedCursor
  ) {
    UUID orgId = TenantContext.getRequired();
    String target = targetStatus.strip().toUpperCase(Locale.ROOT);
    String requiredSource = switch (target) {
      case "EXECUTING" -> "PUBLISHED";
      case "CLOSED" -> "EXECUTING";
      case "ARCHIVED" -> "CLOSED";
      default -> throw invalid("LIFECYCLE_TARGET_INVALID", "Unsupported lifecycle target " + target);
    };
    RevisionRow revision = lockRevision(orgId, planId, revisionId);
    if (revision.status().equals(target)) {
      return new LifecycleTransitionView(planId, revisionId, target, target, revision.version(),
          Instant.now(), List.of());
    }
    if (!revision.status().equals(requiredSource)) {
      throw conflict("PLAN_STATE_INVALID",
          "Lifecycle transition requires " + requiredSource + " but current status is " + revision.status());
    }
    if (revision.version() != expectedCursor) {
      throw conflict("REVISION_CONFLICT", "Revision changed; reload and retry with the latest cursor");
    }
    List<LifecycleIssue> issues = readinessIssues(orgId, revisionId, target);
    if (!issues.isEmpty()) {
      List<ApiErrorResponse.FieldError> errors = issues.stream()
          .map(issue -> new ApiErrorResponse.FieldError(issue.code(), Integer.toString(issue.count())))
          .toList();
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OPERATIONS_NOT_READY",
          issues.getFirst().message(), errors);
    }

    int changed = jdbcTemplate.update("""
        update exam_plan_revision set status=?, updated_by=?, updated_at=now(), version=version+1
        where org_id=? and plan_id=? and id=? and status=? and version=?
        """, target, actorId, orgId, planId, revisionId, requiredSource, expectedCursor);
    if (changed != 1) throw conflict("REVISION_CONFLICT", "Revision changed; reload and retry");
    int planChanged = jdbcTemplate.update("""
        update exam_plan set status=?, updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and active_revision_id=? and status=?
        """, target, actorId, orgId, planId, revisionId, requiredSource);
    if (planChanged != 1) {
      throw conflict("PLAN_STATE_CONFLICT", "Plan no longer points at this lifecycle revision");
    }

    ObjectNode before = objectMapper.createObjectNode().put("status", requiredSource)
        .put("revisionCursor", expectedCursor);
    ObjectNode after = objectMapper.createObjectNode().put("status", target)
        .put("revisionCursor", expectedCursor + 1);
    audit(orgId, planId, revisionId, actorId, "REVISION_" + target, null, before, after);
    outbox(orgId, revisionId, "REVISION_" + target, "exam_plan_revision", revisionId, after, actorId);
    return new LifecycleTransitionView(planId, revisionId, requiredSource, target,
        expectedCursor + 1, Instant.now(), List.of());
  }

  @Transactional
  public IncidentView createIncident(UUID revisionId, CreateIncidentRequest request, UUID actorId) {
    UUID orgId = TenantContext.getRequired();
    RevisionRow revision = requireLiveRevision(orgId, revisionId, false);
    String severity = normalize(request.severity(),
        Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"), "INCIDENT_SEVERITY_INVALID");
    String code = request.incidentCode().strip().toUpperCase(Locale.ROOT);
    List<UUID> existing = jdbcTemplate.query("""
        select id from exam_plan_incident where org_id=? and revision_id=? and incident_code=?
        """, (rs, row) -> rs.getObject(1, UUID.class), orgId, revisionId, code);
    if (!existing.isEmpty()) return requireIncident(orgId, revisionId, existing.getFirst());

    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_incident (
          id, org_id, revision_id, incident_code, incident_type, severity, summary, details,
          reported_by, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
        """, id, orgId, revisionId, code, request.incidentType().strip().toUpperCase(Locale.ROOT),
        severity, request.summary().strip(), json(request.details()), actorId, actorId, actorId);
    ObjectNode payload = objectMapper.createObjectNode().put("incidentId", id.toString())
        .put("incidentCode", code).put("severity", severity).put("status", "OPEN");
    audit(orgId, revision.planId(), revisionId, actorId, "INCIDENT_RECORDED", null,
        objectMapper.createObjectNode(), payload);
    outbox(orgId, revisionId, "INCIDENT_RECORDED", "exam_plan_incident", id, payload, actorId);
    return requireIncident(orgId, revisionId, id);
  }

  @Transactional(readOnly = true)
  public List<IncidentView> incidents(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    requireLiveRevision(orgId, revisionId, true);
    return jdbcTemplate.query(incidentQuery() + " and i.revision_id=? order by i.created_at desc, i.id",
        this::incidentView, orgId, revisionId);
  }

  @Transactional
  public IncidentView resolveIncident(
      UUID revisionId, UUID incidentId, ResolveIncidentRequest request, UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    IncidentView incident = requireIncident(orgId, revisionId, incidentId);
    if (incident.status().equals("RESOLVED")) return incident;
    if (!incident.status().equals("OPEN")) {
      throw conflict("INCIDENT_STATE_INVALID", "Incident is not open");
    }
    int changed = jdbcTemplate.update("""
        update exam_plan_incident set status='RESOLVED', resolution=?, resolved_by=?, resolved_at=now(),
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and revision_id=? and id=? and status='OPEN' and version=?
        """, request.resolution().strip(), actorId, actorId, orgId, revisionId, incidentId,
        incident.version());
    if (changed != 1) throw conflict("INCIDENT_CONFLICT", "Incident changed; reload and retry");
    RevisionRow revision = requireLiveRevision(orgId, revisionId, true);
    ObjectNode after = objectMapper.createObjectNode().put("incidentId", incidentId.toString())
        .put("status", "RESOLVED");
    audit(orgId, revision.planId(), revisionId, actorId, "INCIDENT_RESOLVED", request.resolution(),
        objectMapper.createObjectNode().put("status", incident.status()), after);
    outbox(orgId, revisionId, "INCIDENT_RESOLVED", "exam_plan_incident", incidentId, after, actorId);
    return requireIncident(orgId, revisionId, incidentId);
  }

  @Transactional
  public ChecklistItemView createChecklist(
      UUID revisionId, CreateChecklistItemRequest request, UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    requireLiveRevision(orgId, revisionId, false);
    String code = request.itemCode().strip().toUpperCase(Locale.ROOT);
    List<UUID> existing = jdbcTemplate.query("""
        select id from exam_plan_checklist_item where org_id=? and revision_id=? and item_code=?
        """, (rs, row) -> rs.getObject(1, UUID.class), orgId, revisionId, code);
    if (!existing.isEmpty()) return requireChecklist(orgId, revisionId, existing.getFirst());
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_checklist_item (
          id, org_id, revision_id, item_code, label, required, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, ?)
        """, id, orgId, revisionId, code, request.label().strip(), request.required(), actorId, actorId);
    return requireChecklist(orgId, revisionId, id);
  }

  @Transactional(readOnly = true)
  public List<ChecklistItemView> checklists(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    requireLiveRevision(orgId, revisionId, true);
    return jdbcTemplate.query(checklistQuery() + " and c.revision_id=? order by c.created_at, c.id",
        this::checklistView, orgId, revisionId);
  }

  @Transactional
  public ChecklistItemView completeChecklist(
      UUID revisionId, UUID itemId, CompleteChecklistItemRequest request, UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    ChecklistItemView item = requireChecklist(orgId, revisionId, itemId);
    String target = normalize(request.status(), Set.of("COMPLETED", "WAIVED"),
        "CHECKLIST_STATUS_INVALID");
    if (item.status().equals(target)) return item;
    if (!item.status().equals("PENDING")) {
      throw conflict("CHECKLIST_STATE_INVALID", "Checklist item is already final");
    }
    int changed = jdbcTemplate.update("""
        update exam_plan_checklist_item set status=?, evidence=?::jsonb, completed_by=?,
          completed_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and revision_id=? and id=? and status='PENDING' and version=?
        """, target, json(request.evidence()), actorId, actorId, orgId, revisionId, itemId, item.version());
    if (changed != 1) throw conflict("CHECKLIST_CONFLICT", "Checklist item changed; reload and retry");
    RevisionRow revision = requireLiveRevision(orgId, revisionId, true);
    ObjectNode after = objectMapper.createObjectNode().put("checklistItemId", itemId.toString())
        .put("status", target);
    audit(orgId, revision.planId(), revisionId, actorId, "CHECKLIST_ITEM_" + target, null,
        objectMapper.createObjectNode().put("status", item.status()), after);
    return requireChecklist(orgId, revisionId, itemId);
  }

  private TeacherRow requireQualifiedReplacement(
      UUID orgId, SubstitutionRow substitution, UUID replacementTeacherId
  ) {
    List<TeacherRow> rows = jdbcTemplate.query("""
        select id, teacher_no, status, qualifications::text, qualification_expiry,
          max_daily_sessions, position, is_homeroom, preferred_campus
        from teacher where org_id=? and id=?
        """, (rs, row) -> new TeacherRow(rs.getObject("id", UUID.class), rs.getString("teacher_no"),
        rs.getString("status"), parse(rs.getString("qualifications")),
        rs.getObject("qualification_expiry", LocalDate.class), rs.getInt("max_daily_sessions"),
        rs.getString("position"), rs.getBoolean("is_homeroom"), rs.getString("preferred_campus")),
        orgId, replacementTeacherId);
    if (rows.isEmpty()) throw notFound("SUBSTITUTION_CASE_NOT_FOUND", "Substitution case is unavailable");
    TeacherRow teacher = rows.getFirst();
    LocalDate dutyDate = substitution.startsAt().atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toLocalDate();
    ExamOperationsHardConstraints.TeacherEligibility eligibility =
        new ExamOperationsHardConstraints.TeacherEligibility(teacher.qualifications(), teacher.position(),
            teacher.homeroom(), teacher.qualificationExpiry(), teacher.maxDailySessions(),
            teacher.preferredCampus());
    if (!teacher.status().equals("active") || !hardConstraints.teacherEligible(
        substitution.dutyRole(), substitution.qualification(), eligibility, dutyDate)) {
      throw invalid("SUBSTITUTION_TEACHER_UNQUALIFIED",
          "Replacement teacher is inactive, expired, or lacks the required qualification");
    }
    int leaveConflict = jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_leave_request where org_id=? and teacher_id=? and status='APPROVED'
          and tstzrange(starts_at, ends_at, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        """, Integer.class, orgId, replacementTeacherId, Timestamp.from(substitution.startsAt()),
        Timestamp.from(substitution.endsAt()));
    int dutyConflict = jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_duty_assignment a
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where a.org_id=? and a.teacher_id=? and a.id<>? and a.status not in ('CANCELLED','COMPLETED')
          and r.status in ('PUBLISHED','EXECUTING')
          and tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        """, Integer.class, orgId, replacementTeacherId, substitution.assignmentId(),
        Timestamp.from(substitution.startsAt()), Timestamp.from(substitution.endsAt()));
    int substitutionConflict = jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_substitution_case s
        join exam_plan_duty_assignment a on a.org_id=s.org_id and a.id=s.duty_assignment_id
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where s.org_id=? and s.replacement_teacher_id=? and s.id<>? and s.status='RESOLVED'
          and r.status in ('PUBLISHED','EXECUTING')
          and tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')
        """, Integer.class, orgId, replacementTeacherId, substitution.id(),
        Timestamp.from(substitution.startsAt()), Timestamp.from(substitution.endsAt()));
    int daily = jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_duty_assignment a
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where a.org_id=? and a.teacher_id=? and a.status not in ('CANCELLED','COMPLETED')
          and r.status in ('PUBLISHED','EXECUTING') and (a.starts_at at time zone 'Asia/Shanghai')::date=?
        """, Integer.class, orgId, replacementTeacherId, dutyDate);
    int substitutedDaily = jdbcTemplate.queryForObject("""
        select count(*) from exam_plan_substitution_case s
        join exam_plan_duty_assignment a on a.org_id=s.org_id and a.id=s.duty_assignment_id
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where s.org_id=? and s.replacement_teacher_id=? and s.status='RESOLVED'
          and r.status in ('PUBLISHED','EXECUTING') and (a.starts_at at time zone 'Asia/Shanghai')::date=?
        """, Integer.class, orgId, replacementTeacherId, dutyDate);
    if (leaveConflict + dutyConflict + substitutionConflict > 0) {
      throw conflict("SUBSTITUTION_TEACHER_CONFLICT", "Replacement teacher is unavailable for this duty interval");
    }
    if (daily + substitutedDaily >= teacher.maxDailySessions()) {
      throw conflict("SUBSTITUTION_DAILY_LIMIT", "Replacement teacher has reached the daily duty limit");
    }
    return teacher;
  }

  private List<LifecycleIssue> readinessIssues(UUID orgId, UUID revisionId, String target) {
    List<LifecycleIssue> issues = new ArrayList<>();
    addIssue(issues, "OPEN_SUBSTITUTIONS", "Open substitution cases must be resolved",
        count("select count(*) from exam_plan_substitution_case where org_id=? and revision_id=? and status in ('OPEN','ASSIGNED')",
            orgId, revisionId));
    if (target.equals("CLOSED") || target.equals("ARCHIVED")) {
      addIssue(issues, "BLOCKING_INCIDENTS", "High or critical incidents must be resolved",
          count("""
              select count(*) from exam_plan_incident where org_id=? and revision_id=? and status='OPEN'
                and severity in ('HIGH','CRITICAL')
              """, orgId, revisionId));
      addIssue(issues, "CHECKLIST_INCOMPLETE", "Required checklist items must be completed or waived",
          count("""
              select count(*) from exam_plan_checklist_item
              where org_id=? and revision_id=? and required and status='PENDING'
              """, orgId, revisionId));
    }
    if (target.equals("ARCHIVED")) {
      addIssue(issues, "INCIDENTS_OPEN", "All incidents must be resolved before archiving",
          count("select count(*) from exam_plan_incident where org_id=? and revision_id=? and status='OPEN'",
              orgId, revisionId));
      addIssue(issues, "NOTIFICATIONS_UNSETTLED", "Notifications must be delivered or cancelled before archiving",
          count("""
              select count(*) from exam_plan_delivery d join exam_plan_outbox o
                on o.org_id=d.org_id and o.id=d.outbox_id
              where d.org_id=? and o.revision_id=? and d.status in ('PENDING','FAILED','SENT')
              """, orgId, revisionId));
    }
    return List.copyOf(issues);
  }

  private SubstitutionRow lockSubstitution(UUID orgId, UUID id) {
    List<SubstitutionRow> rows = jdbcTemplate.query("""
        select s.id, s.revision_id, s.duty_assignment_id, s.teacher_id,
          s.replacement_teacher_id, s.status, p.id plan_id, a.starts_at, a.ends_at,
          a.duty_role, q.qualification::text
        from exam_plan_substitution_case s
        join exam_plan_duty_assignment a on a.org_id=s.org_id and a.id=s.duty_assignment_id
        join exam_plan_duty_requirement q on q.org_id=a.org_id and q.id=a.requirement_id
        join exam_plan_revision r on r.org_id=s.org_id and r.id=s.revision_id
        join exam_plan p on p.org_id=r.org_id and p.id=r.plan_id
        where s.org_id=? and s.id=? and r.status in ('PUBLISHED','EXECUTING')
        for update of s
        """, (rs, row) -> new SubstitutionRow(rs.getObject("id", UUID.class),
        rs.getObject("revision_id", UUID.class), rs.getObject("duty_assignment_id", UUID.class),
        rs.getObject("teacher_id", UUID.class), rs.getObject("replacement_teacher_id", UUID.class),
        rs.getString("status"), rs.getObject("plan_id", UUID.class),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
        rs.getString("duty_role"), parse(rs.getString("qualification"))), orgId, id);
    if (rows.isEmpty()) throw notFound("SUBSTITUTION_CASE_NOT_FOUND", "Substitution case is unavailable");
    return rows.getFirst();
  }

  private RevisionRow lockRevision(UUID orgId, UUID planId, UUID revisionId) {
    List<RevisionRow> rows = jdbcTemplate.query("""
        select id, plan_id, status, version from exam_plan_revision
        where org_id=? and plan_id=? and id=? for update
        """, (rs, row) -> new RevisionRow(rs.getObject("id", UUID.class),
        rs.getObject("plan_id", UUID.class), rs.getString("status"), rs.getLong("version")),
        orgId, planId, revisionId);
    if (rows.isEmpty()) throw notFound("EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    return rows.getFirst();
  }

  private RevisionRow requireLiveRevision(UUID orgId, UUID revisionId, boolean includeClosed) {
    Set<String> states = includeClosed ? LIVE_REVISION_STATES : Set.of("PUBLISHED", "EXECUTING");
    List<RevisionRow> rows = jdbcTemplate.query("""
        select id, plan_id, status, version from exam_plan_revision where org_id=? and id=?
        """, (rs, row) -> new RevisionRow(rs.getObject("id", UUID.class),
        rs.getObject("plan_id", UUID.class), rs.getString("status"), rs.getLong("version")),
        orgId, revisionId);
    if (rows.isEmpty() || !states.contains(rows.getFirst().status())) {
      throw notFound("PLAN_REVISION_NOT_FOUND", "Plan revision is unavailable");
    }
    return rows.getFirst();
  }

  private SubstitutionCaseView requireSubstitution(UUID orgId, UUID id) {
    List<SubstitutionCaseView> rows = jdbcTemplate.query(substitutionQuery() + " and s.id=?",
        this::substitutionView, orgId, id);
    if (rows.isEmpty()) throw notFound("SUBSTITUTION_CASE_NOT_FOUND", "Substitution case is unavailable");
    return rows.getFirst();
  }

  private String substitutionQuery() {
    return """
        select s.id, s.revision_id, s.duty_assignment_id, s.teacher_id, t.teacher_no,
          s.replacement_teacher_id, replacement.teacher_no replacement_teacher_no,
          s.leave_request_id, a.duty_role, a.starts_at, a.ends_at, s.status, s.reason,
          s.resolution_reason, s.resolved_by, s.resolved_at, s.version, s.created_at, s.updated_at
        from exam_plan_substitution_case s
        join exam_plan_duty_assignment a on a.org_id=s.org_id and a.id=s.duty_assignment_id
        join teacher t on t.org_id=s.org_id and t.id=s.teacher_id
        left join teacher replacement on replacement.org_id=s.org_id and replacement.id=s.replacement_teacher_id
        where s.org_id=?
        """;
  }

  private SubstitutionCaseView substitutionView(ResultSet rs, int row) throws SQLException {
    return new SubstitutionCaseView(rs.getObject("id", UUID.class),
        rs.getObject("revision_id", UUID.class), rs.getObject("duty_assignment_id", UUID.class),
        rs.getObject("teacher_id", UUID.class), rs.getString("teacher_no"),
        rs.getObject("replacement_teacher_id", UUID.class), rs.getString("replacement_teacher_no"),
        rs.getObject("leave_request_id", UUID.class), rs.getString("duty_role"),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
        rs.getString("status"), rs.getString("reason"), rs.getString("resolution_reason"),
        rs.getObject("resolved_by", UUID.class), instant(rs.getTimestamp("resolved_at")),
        rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private IncidentView requireIncident(UUID orgId, UUID revisionId, UUID id) {
    List<IncidentView> rows = jdbcTemplate.query(incidentQuery() + " and i.revision_id=? and i.id=?",
        this::incidentView, orgId, revisionId, id);
    if (rows.isEmpty()) throw notFound("INCIDENT_NOT_FOUND", "Incident is unavailable");
    return rows.getFirst();
  }

  private String incidentQuery() {
    return """
        select i.id, i.revision_id, i.incident_code, i.incident_type, i.severity, i.summary,
          i.details::text, i.status, i.reported_by, i.resolved_by, i.resolved_at, i.resolution,
          i.version, i.created_at, i.updated_at
        from exam_plan_incident i where i.org_id=?
        """;
  }

  private IncidentView incidentView(ResultSet rs, int row) throws SQLException {
    return new IncidentView(rs.getObject("id", UUID.class), rs.getObject("revision_id", UUID.class),
        rs.getString("incident_code"), rs.getString("incident_type"), rs.getString("severity"),
        rs.getString("summary"), parse(rs.getString("details")), rs.getString("status"),
        rs.getObject("reported_by", UUID.class), rs.getObject("resolved_by", UUID.class),
        instant(rs.getTimestamp("resolved_at")), rs.getString("resolution"), rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
  }

  private ChecklistItemView requireChecklist(UUID orgId, UUID revisionId, UUID id) {
    List<ChecklistItemView> rows = jdbcTemplate.query(
        checklistQuery() + " and c.revision_id=? and c.id=?", this::checklistView, orgId, revisionId, id);
    if (rows.isEmpty()) throw notFound("CHECKLIST_ITEM_NOT_FOUND", "Checklist item is unavailable");
    return rows.getFirst();
  }

  private String checklistQuery() {
    return """
        select c.id, c.revision_id, c.item_code, c.label, c.required, c.status,
          c.evidence::text, c.completed_by, c.completed_at, c.version, c.created_at, c.updated_at
        from exam_plan_checklist_item c where c.org_id=?
        """;
  }

  private ChecklistItemView checklistView(ResultSet rs, int row) throws SQLException {
    return new ChecklistItemView(rs.getObject("id", UUID.class),
        rs.getObject("revision_id", UUID.class), rs.getString("item_code"), rs.getString("label"),
        rs.getBoolean("required"), rs.getString("status"), parse(rs.getString("evidence")),
        rs.getObject("completed_by", UUID.class), instant(rs.getTimestamp("completed_at")),
        rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private UUID outbox(
      UUID orgId, UUID revisionId, String eventType, String aggregateType,
      UUID aggregateId, ObjectNode payload, UUID actorId
  ) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_outbox (
          id, org_id, revision_id, event_type, aggregate_type, aggregate_id, payload,
          created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """, id, orgId, revisionId, eventType, aggregateType, aggregateId,
        payload.toString(), actorId, actorId);
    return id;
  }

  private void audit(
      UUID orgId, UUID planId, UUID revisionId, UUID actorId, String action,
      String reason, ObjectNode before, ObjectNode after
  ) {
    jdbcTemplate.update("""
        insert into exam_plan_audit_event (
          org_id, plan_id, revision_id, action, actor_id, reason,
          before_state, after_state, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """, orgId, planId, revisionId, action, actorId, blankToNull(reason),
        before.toString(), after.toString(), actorId, actorId);
  }

  private int count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private void addIssue(List<LifecycleIssue> issues, String code, String message, int count) {
    if (count > 0) issues.add(new LifecycleIssue(code, message, count));
  }

  private String normalizeOptional(String value, Set<String> allowed, String code) {
    return value == null || value.isBlank() ? null : normalize(value, allowed, code);
  }

  private String normalize(String value, Set<String> allowed, String code) {
    String normalized = value.strip().toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) throw invalid(code, "Unsupported value " + normalized);
    return normalized;
  }

  private String json(JsonNode value) {
    return (value == null ? objectMapper.createObjectNode() : value).toString();
  }

  private JsonNode parse(String value) {
    try {
      return objectMapper.readTree(value == null ? "{}" : value);
    } catch (Exception ignored) {
      return objectMapper.createObjectNode();
    }
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private ApiException invalid(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  private ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  private ApiException notFound(String code, String message) {
    return new ApiException(HttpStatus.NOT_FOUND, code, message);
  }

  private record SubstitutionRow(
      UUID id,
      UUID revisionId,
      UUID assignmentId,
      UUID teacherId,
      UUID replacementTeacherId,
      String status,
      UUID planId,
      Instant startsAt,
      Instant endsAt,
      String dutyRole,
      JsonNode qualification
  ) {
  }

  private record TeacherRow(
      UUID id,
      String teacherNo,
      String status,
      JsonNode qualifications,
      LocalDate qualificationExpiry,
      int maxDailySessions,
      String position,
      boolean homeroom,
      String preferredCampus
  ) {
  }

  private record RevisionRow(UUID id, UUID planId, String status, long version) {
  }
}
