package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.ExamPlanDtos.CreateExamPlanRequest;
import com.jkxt.examops.api.ExamPlanDtos.CreateRevisionRequest;
import com.jkxt.examops.api.ExamPlanDtos.ExamPlanView;
import com.jkxt.examops.api.ExamPlanDtos.PlanRevisionView;
import com.jkxt.examops.api.ExamPlanDtos.PlanValidationResult;
import com.jkxt.examops.api.ExamPlanDtos.ValidationIssue;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamPlanService {

  private static final DateTimeFormatter PLAN_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
      .withZone(ZoneOffset.UTC);
  private static final List<String> IMMUTABLE = List.of("PUBLISHED", "EXECUTING", "CLOSED", "ARCHIVED");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ExamOperationsHardConstraints hardConstraints;

  public ExamPlanService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ExamOperationsHardConstraints hardConstraints
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.hardConstraints = hardConstraints;
  }

  @Transactional
  public ExamPlanView create(CreateExamPlanRequest request, UUID actorId, String idempotencyKey) {
    UUID orgId = TenantContext.getRequired();
    UUID existing = idempotentResource(orgId, "exam-plan:create", idempotencyKey);
    if (existing != null) {
      return get(existing);
    }

    UUID planId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    String planNo = normalizePlanNo(request.planNo());
    String settings = json(request.settings(), objectMapper.createObjectNode());
    jdbcTemplate.update("""
        insert into exam_plan (
          id, org_id, plan_no, name, term_id, status, settings, created_by, updated_by
        ) values (?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, ?, ?)
        """, planId, orgId, planNo, request.name().trim(), request.termId(), settings, actorId, actorId);
    jdbcTemplate.update("""
        insert into exam_plan_revision (
          id, org_id, plan_id, revision_no, status, source_type, title, change_reason,
          configuration, created_by, updated_by
        ) values (?, ?, ?, 1, 'DRAFT', 'MANUAL', ?, 'Initial revision', '{}'::jsonb, ?, ?)
        """, revisionId, orgId, planId, blankToNull(request.initialRevisionTitle()), actorId, actorId);
    jdbcTemplate.update(
        "update exam_plan set active_revision_id=?, updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?",
        revisionId, actorId, orgId, planId);
    rememberIdempotency(orgId, actorId, "exam-plan:create", idempotencyKey, "exam_plan", planId);
    audit(orgId, planId, revisionId, actorId, "PLAN_CREATED", null, null, "{\"status\":\"DRAFT\"}");
    return get(planId);
  }

  @Transactional(readOnly = true)
  public List<ExamPlanView> list() {
    return jdbcTemplate.query("""
        select id, plan_no, name, term_id, status, active_revision_id, legacy_exam_task_id,
               settings::text, version, created_at, updated_at
        from exam_plan where org_id = ? order by updated_at desc, created_at desc
        """, this::plan, TenantContext.getRequired());
  }

  @Transactional(readOnly = true)
  public ExamPlanView get(UUID planId) {
    List<ExamPlanView> rows = jdbcTemplate.query("""
        select id, plan_no, name, term_id, status, active_revision_id, legacy_exam_task_id,
               settings::text, version, created_at, updated_at
        from exam_plan where org_id = ? and id = ?
        """, this::plan, TenantContext.getRequired(), planId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  @Transactional(readOnly = true)
  public List<PlanRevisionView> revisions(UUID planId) {
    get(planId);
    return jdbcTemplate.query("""
        select id, plan_id, revision_no, predecessor_id, status, source_type, title, change_reason,
               configuration::text, validation_summary::text, version, submitted_at, approved_at,
               published_at, created_at, updated_at
        from exam_plan_revision where org_id = ? and plan_id = ? order by revision_no desc
        """, this::revision, TenantContext.getRequired(), planId);
  }

  @Transactional(readOnly = true)
  public PlanRevisionView revision(UUID planId, UUID revisionId) {
    return requireRevision(planId, revisionId);
  }

  @Transactional
  public PlanRevisionView createRevision(
      UUID planId,
      CreateRevisionRequest request,
      UUID actorId,
      String idempotencyKey
  ) {
    UUID orgId = TenantContext.getRequired();
    ExamPlanView plan = get(planId);
    String operation = "exam-plan:" + planId + ":revision:create";
    UUID existing = idempotentResource(orgId, operation, idempotencyKey);
    if (existing != null) {
      return requireRevision(planId, existing);
    }
    PlanRevisionView predecessor = predecessor(planId, request.predecessorId());
    int next = jdbcTemplate.queryForObject(
        "select coalesce(max(revision_no),0) + 1 from exam_plan_revision where org_id = ? and plan_id = ?",
        Integer.class, orgId, planId);
    UUID id = UUID.randomUUID();
    JsonNode config = request.configuration() != null
        ? request.configuration()
        : predecessor == null ? objectMapper.createObjectNode() : predecessor.configuration();
    jdbcTemplate.update("""
        insert into exam_plan_revision (
          id, org_id, plan_id, revision_no, predecessor_id, status, source_type, title,
          change_reason, configuration, created_by, updated_by
        ) values (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?::jsonb, ?, ?)
        """, id, orgId, planId, next, predecessor == null ? null : predecessor.id(),
        predecessor == null ? "MANUAL" : "SUCCESSOR", blankToNull(request.title()),
        request.changeReason().trim(), json(config, objectMapper.createObjectNode()), actorId, actorId);
    if (!IMMUTABLE.contains(plan.status())) {
      jdbcTemplate.update(
          "update exam_plan set status='DRAFT', active_revision_id=?, updated_by=?, updated_at=now(), "
              + "version=version+1 where org_id=? and id=?",
          id, actorId, orgId, planId);
    }
    rememberIdempotency(orgId, actorId, operation, idempotencyKey, "exam_plan_revision", id);
    audit(orgId, planId, id, actorId, "REVISION_CREATED", request.changeReason(), null,
        "{\"revisionNo\":" + next + "}");
    return requireRevision(planId, id);
  }

  @Transactional
  public PlanValidationResult validate(UUID planId, UUID revisionId, UUID actorId, Long expectedCursor) {
    UUID orgId = TenantContext.getRequired();
    PlanRevisionView revision = requireMutableRevision(planId, revisionId);
    assertCursor(revision, expectedCursor);
    List<ValidationIssue> issues = new ArrayList<>();

    int sessions = count("select count(*) from exam_plan_session where org_id=? and revision_id=? and status<>'CANCELLED'", orgId, revisionId);
    int candidates = number("select coalesce(sum(candidate_count),0) from exam_plan_session where org_id=? and revision_id=? and status<>'CANCELLED'", orgId, revisionId);
    int allocated = number("select coalesce(sum(allocated_candidates),0) from exam_plan_room_assignment where org_id=? and revision_id=? and status<>'CANCELLED'", orgId, revisionId);
    int seats = count("select count(*) from exam_plan_seat_assignment where org_id=? and revision_id=? and status<>'CANCELLED'", orgId, revisionId);
    int dutiesRequired = number("select coalesce(sum(required_count),0) from exam_plan_duty_requirement where org_id=? and revision_id=?", orgId, revisionId);
    int dutiesAssigned = count("select count(*) from exam_plan_duty_assignment where org_id=? and revision_id=? and status<>'CANCELLED'", orgId, revisionId);

    addSessionCoverageIssues(orgId, revisionId, issues);
    if (allocated < candidates) {
      issues.add(issue("ROOM_CAPACITY_UNCOVERED", "revision", revisionId,
          "Room allocations cover " + allocated + " of " + candidates + " candidates",
          "Add capacity, split sessions, or move the session"));
    }
    if (seats < candidates) {
      issues.add(issue("SEATS_UNASSIGNED", "revision", revisionId,
          "Seat assignments cover " + seats + " of " + candidates + " candidates",
          "Generate or import the missing seat assignments"));
    }
    if (dutiesRequired == 0 && sessions > 0) {
      issues.add(issue("DUTY_REQUIREMENTS_MISSING", "revision", revisionId,
          "No invigilation duty requirements are defined", "Define chief and invigilator requirements for each room"));
    } else if (dutiesAssigned < dutiesRequired) {
      issues.add(issue("DUTIES_UNCOVERED", "revision", revisionId,
          "Duty assignments cover " + dutiesAssigned + " of " + dutiesRequired + " required posts",
          "Assign qualified invigilators or revise the affected session"));
    }
    issues.addAll(strictPublicationIssues(orgId, revisionId));

    boolean valid = issues.isEmpty();
    boolean incomplete = issues.stream().anyMatch(item -> item.code().equals("PLAN_NO_SESSIONS"));
    String status = valid ? "READY" : incomplete ? "INVALID" : "INFEASIBLE";
    String code = valid ? "PLAN_VALID" : incomplete ? "PLAN_INVALID" : "PLAN_INFEASIBLE";
    ObjectNode summary = objectMapper.createObjectNode();
    summary.put("code", code);
    summary.put("valid", valid);
    summary.put("hardViolationCount", issues.size());
    ArrayNode issueArray = summary.putArray("issues");
    issues.forEach(value -> issueArray.addPOJO(value));
    mutateRevision(revision, expectedCursor, status, actorId,
        "validation_summary=?::jsonb", summary.toString());
    updatePlanStatus(orgId, planId, status, actorId);
    audit(orgId, planId, revisionId, actorId, "REVISION_VALIDATED", null,
        "{\"status\":\"" + revision.status() + "\"}", summary.toString());
    PlanRevisionView refreshed = requireRevision(planId, revisionId);
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("sessions", sessions);
    metrics.put("candidates", candidates);
    metrics.put("allocatedCandidates", allocated);
    metrics.put("seats", seats);
    metrics.put("dutiesRequired", dutiesRequired);
    metrics.put("dutiesAssigned", dutiesAssigned);
    return new PlanValidationResult(code, valid, status, issues.size(), List.copyOf(issues), metrics, refreshed);
  }

  @Transactional
  public PlanRevisionView submitApproval(UUID planId, UUID revisionId, UUID actorId, Long expectedCursor) {
    UUID orgId = TenantContext.getRequired();
    PlanRevisionView revision = requireRevision(planId, revisionId);
    assertStatus(revision, List.of("READY", "REVIEW_REQUIRED"), "Only a validated revision can be submitted");
    assertCursor(revision, expectedCursor);
    assertStrictlyPublishable(orgId, revisionId);
    UUID approvalId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_approval_request (
          id, org_id, revision_id, status, requested_by, created_by, updated_by
        ) values (?, ?, ?, 'PENDING', ?, ?, ?)
        """, approvalId, orgId, revisionId, actorId, actorId, actorId);
    mutateRevision(revision, expectedCursor, "PENDING_APPROVAL", actorId, "submitted_at=now()", null);
    updatePlanStatus(orgId, planId, "PENDING_APPROVAL", actorId);
    audit(orgId, planId, revisionId, actorId, "APPROVAL_SUBMITTED", null, null, "{}");
    return requireRevision(planId, revisionId);
  }

  @Transactional
  public PlanRevisionView approve(
      UUID planId,
      UUID revisionId,
      UUID actorId,
      String reason,
      Long expectedCursor
  ) {
    return decide(planId, revisionId, actorId, reason, expectedCursor, true);
  }

  @Transactional
  public PlanRevisionView reject(
      UUID planId,
      UUID revisionId,
      UUID actorId,
      String reason,
      Long expectedCursor
  ) {
    return decide(planId, revisionId, actorId, reason, expectedCursor, false);
  }

  @Transactional
  public PlanRevisionView publish(UUID planId, UUID revisionId, UUID actorId, Long expectedCursor) {
    UUID orgId = TenantContext.getRequired();
    PlanRevisionView revision = requireRevision(planId, revisionId);
    assertStatus(revision, List.of("APPROVED"), "Only an approved revision can be published");
    assertCursor(revision, expectedCursor);
    assertStrictlyPublishable(orgId, revisionId);
    mutateRevision(revision, expectedCursor, "PUBLISHED", actorId, "published_at=now()", null);
    jdbcTemplate.update("""
        update exam_plan set status='PUBLISHED', active_revision_id=?, updated_by=?, updated_at=now(),
          version=version+1 where org_id=? and id=?
        """, revisionId, actorId, orgId, planId);
    UUID outboxId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_outbox (
          id, org_id, revision_id, event_type, aggregate_type, aggregate_id, payload, created_by, updated_by
        ) values (?, ?, ?, 'PLAN_PUBLISHED', 'exam_plan_revision', ?, ?::jsonb, ?, ?)
        """, outboxId, orgId, revisionId, revisionId,
        "{\"planId\":\"" + planId + "\",\"revisionId\":\"" + revisionId + "\"}", actorId, actorId);
    jdbcTemplate.update("""
        insert into exam_plan_delivery (
          org_id, outbox_id, recipient_type, recipient_id, channel, status, created_by, updated_by
        )
        select distinct ?, ?, 'TEACHER', t.teacher_no, 'IN_APP', 'PENDING', ?, ?
        from exam_plan_duty_assignment a
        join teacher t on t.org_id=a.org_id and t.id=a.teacher_id
        where a.org_id=? and a.revision_id=? and a.status<>'CANCELLED'
        on conflict (org_id, outbox_id, recipient_type, recipient_id, channel) do nothing
        """, orgId, outboxId, actorId, actorId, orgId, revisionId);
    audit(orgId, planId, revisionId, actorId, "REVISION_PUBLISHED", null, null, "{}");
    return requireRevision(planId, revisionId);
  }

  private PlanRevisionView decide(
      UUID planId,
      UUID revisionId,
      UUID actorId,
      String reason,
      Long expectedCursor,
      boolean approve
  ) {
    UUID orgId = TenantContext.getRequired();
    PlanRevisionView revision = requireRevision(planId, revisionId);
    assertStatus(revision, List.of("PENDING_APPROVAL"), "Revision is not awaiting approval");
    assertCursor(revision, expectedCursor);
    Map<String, Object> approval = jdbcTemplate.query("""
        select id, requested_by from exam_plan_approval_request
        where org_id=? and revision_id=? and status='PENDING' order by created_at desc limit 1
        """, result -> {
          if (!result.next()) return Map.of();
          return Map.of("id", result.getObject("id", UUID.class), "requestedBy", result.getObject("requested_by", UUID.class));
        }, orgId, revisionId);
    if (approval.isEmpty()) {
      throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_NOT_PENDING", "No pending approval request exists");
    }
    if (actorId.equals(approval.get("requestedBy"))) {
      throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_SELF_REVIEW_FORBIDDEN", "Submitter cannot approve or reject their own revision");
    }
    String approvalStatus = approve ? "APPROVED" : "REJECTED";
    String revisionStatus = approve ? "APPROVED" : "REVIEW_REQUIRED";
    jdbcTemplate.update("""
        update exam_plan_approval_request set status=?, decided_by=?, decision_reason=?, decided_at=now(),
          updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?
        """, approvalStatus, actorId, blankToNull(reason), actorId, orgId, approval.get("id"));
    mutateRevision(revision, expectedCursor, revisionStatus, actorId,
        approve ? "approved_at=now()" : "approved_at=null", null);
    updatePlanStatus(orgId, planId, revisionStatus, actorId);
    audit(orgId, planId, revisionId, actorId,
        approve ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED", reason, null,
        "{\"status\":\"" + approvalStatus + "\"}");
    return requireRevision(planId, revisionId);
  }

  private void addSessionCoverageIssues(UUID orgId, UUID revisionId, List<ValidationIssue> issues) {
    jdbcTemplate.query("""
        select s.id, s.session_code
        from exam_plan_session s
        left join exam_plan_room_assignment r on r.org_id=s.org_id and r.session_id=s.id and r.status<>'CANCELLED'
        where s.org_id=? and s.revision_id=? and s.status<>'CANCELLED'
        group by s.id, s.session_code having count(r.id)=0
        """, result -> {
          issues.add(issue("SESSION_ROOM_MISSING", "session", result.getObject("id", UUID.class),
              "Session " + result.getString("session_code") + " has no room allocation",
              "Assign an available room before publishing"));
        }, orgId, revisionId);
  }

  /**
   * Recomputes publication-critical coverage from the current child rows. This intentionally runs
   * again at submit and publish time: a previous READY result is not a durable substitute for
   * checking every session after concurrent/manual edits.
   */
  private List<ValidationIssue> strictPublicationIssues(UUID orgId, UUID revisionId) {
    List<ValidationIssue> issues = new ArrayList<>();
    int activeSessions = count(
        "select count(*) from exam_plan_session where org_id=? and revision_id=? and status<>'CANCELLED'",
        orgId, revisionId);
    if (activeSessions == 0) {
      issues.add(issue("PLAN_NO_SESSIONS", "revision", revisionId,
          "No active examination sessions are defined", "Restore or add at least one session"));
    }
    jdbcTemplate.query("""
        select s.id, s.session_code, s.candidate_count,
          (select count(*) from exam_plan_room_assignment r
             where r.org_id=s.org_id and r.revision_id=s.revision_id and r.session_id=s.id
               and r.status<>'CANCELLED') room_count,
          (select coalesce(sum(r.allocated_candidates),0) from exam_plan_room_assignment r
             where r.org_id=s.org_id and r.revision_id=s.revision_id and r.session_id=s.id
               and r.status<>'CANCELLED') allocated,
          (select count(*) from exam_plan_seat_assignment a
             where a.org_id=s.org_id and a.revision_id=s.revision_id and a.session_id=s.id
               and a.status<>'CANCELLED') seats,
          (select count(*) from exam_plan_duty_requirement q
             where q.org_id=s.org_id and q.revision_id=s.revision_id and q.session_id=s.id) requirement_roles
        from exam_plan_session s
        where s.org_id=? and s.revision_id=? and s.status<>'CANCELLED'
        order by s.starts_at, s.session_code
        """, result -> {
          UUID sessionId = result.getObject("id", UUID.class);
          String code = result.getString("session_code");
          int candidates = result.getInt("candidate_count");
          int roomCount = result.getInt("room_count");
          int allocated = result.getInt("allocated");
          int seats = result.getInt("seats");
          int roles = result.getInt("requirement_roles");
          if (roomCount == 0) {
            issues.add(issue("SESSION_ROOM_MISSING", "session", sessionId,
                "Session " + code + " has no active room allocation", "Assign an available room"));
          } else if (allocated < candidates) {
            issues.add(issue("SESSION_ROOM_CAPACITY_UNCOVERED", "session", sessionId,
                "Session " + code + " room allocations cover " + allocated + " of " + candidates + " candidates",
                "Add room capacity or split/reschedule the session"));
          }
          if (seats < candidates) {
            issues.add(issue("SESSION_SEATS_UNASSIGNED", "session", sessionId,
                "Session " + code + " seats cover " + seats + " of " + candidates + " candidates",
                "Generate or import all remaining seats"));
          }
          if (roles == 0) {
            issues.add(issue("SESSION_DUTY_REQUIREMENTS_MISSING", "session", sessionId,
                "Session " + code + " has no duty role requirements", "Define chief and invigilator roles"));
          }
        }, orgId, revisionId);

    jdbcTemplate.query("""
        select r.id, s.session_code, c.code room_code, r.allocated_candidates,
          count(a.id) filter (where a.status<>'CANCELLED') seat_count
        from exam_plan_room_assignment r
        join exam_plan_session s on s.org_id=r.org_id and s.id=r.session_id
        join classroom c on c.org_id=r.org_id and c.id=r.classroom_id
        left join exam_plan_seat_assignment a on a.org_id=r.org_id and a.room_assignment_id=r.id
        where r.org_id=? and r.revision_id=? and r.status<>'CANCELLED' and s.status<>'CANCELLED'
        group by r.id, s.session_code, c.code, r.allocated_candidates
        having count(a.id) filter (where a.status<>'CANCELLED') < r.allocated_candidates
        """, (org.springframework.jdbc.core.RowCallbackHandler) result -> issues.add(issue("ROOM_SEATS_UNASSIGNED", "roomAssignment",
            result.getObject("id", UUID.class),
            "Session " + result.getString("session_code") + " room " + result.getString("room_code")
                + " has " + result.getInt("seat_count") + " seats for "
                + result.getInt("allocated_candidates") + " allocated candidates",
            "Generate every allocated seat for this room")), orgId, revisionId);

    jdbcTemplate.query("""
        select q.id, q.session_id, s.session_code, q.duty_role, q.required_count,
          count(a.id) filter (where a.status not in ('CANCELLED','DECLINED') and a.duty_role=q.duty_role) assigned
        from exam_plan_duty_requirement q
        join exam_plan_session s on s.org_id=q.org_id and s.id=q.session_id
        left join exam_plan_duty_assignment a on a.org_id=q.org_id and a.requirement_id=q.id
        where q.org_id=? and q.revision_id=? and s.status<>'CANCELLED'
        group by q.id, q.session_id, s.session_code, q.duty_role, q.required_count
        having count(a.id) filter (where a.status not in ('CANCELLED','DECLINED') and a.duty_role=q.duty_role) < q.required_count
        """, (org.springframework.jdbc.core.RowCallbackHandler) result -> issues.add(issue("SESSION_DUTY_ROLE_UNCOVERED", "dutyRequirement",
            result.getObject("id", UUID.class),
            "Session " + result.getString("session_code") + " role " + result.getString("duty_role")
                + " covers " + result.getInt("assigned") + " of " + result.getInt("required_count"),
            "Assign eligible invigilators for every role")), orgId, revisionId);

    jdbcTemplate.query("""
        select r.id, s.session_code, c.code room_code, c.status room_status, c.exam_capacity,
               r.planned_capacity, r.required_features::text, c.features::text
        from exam_plan_room_assignment r
        join exam_plan_session s on s.org_id=r.org_id and s.id=r.session_id
        join classroom c on c.org_id=r.org_id and c.id=r.classroom_id
        where r.org_id=? and r.revision_id=? and r.status<>'CANCELLED' and s.status<>'CANCELLED'
        """, result -> {
          UUID roomAssignmentId = result.getObject("id", UUID.class);
          String roomCode = result.getString("room_code");
          if (!"active".equals(result.getString("room_status"))) {
            issues.add(issue("ROOM_INACTIVE", "roomAssignment", roomAssignmentId,
                "Classroom " + roomCode + " is no longer active", "Replace or reactivate the classroom"));
          }
          if (result.getInt("exam_capacity") < result.getInt("planned_capacity")) {
            issues.add(issue("ROOM_CAPACITY_STALE", "roomAssignment", roomAssignmentId,
                "Classroom " + roomCode + " now has capacity " + result.getInt("exam_capacity")
                    + " below planned capacity " + result.getInt("planned_capacity"),
                "Reduce allocation or select a larger classroom"));
          }
          JsonNode required = parse(result.getString("required_features"));
          JsonNode actual = parse(result.getString("features"));
          if (!hardConstraints.roomCompatible(actual, required)) {
            issues.add(issue("ROOM_FEATURES_MISSING", "roomAssignment", roomAssignmentId,
                "Classroom " + roomCode + " is missing required features "
                    + hardConstraints.missingFeatures(actual, required),
                "Select a classroom with all accessibility/equipment features"));
          }
        }, orgId, revisionId);

    jdbcTemplate.query("""
        select r.id, s.session_code, c.code room_code
        from exam_plan_room_assignment r
        join exam_plan_session s on s.org_id=r.org_id and s.id=r.session_id
        join classroom c on c.org_id=r.org_id and c.id=r.classroom_id
        where r.org_id=? and r.revision_id=? and r.status<>'CANCELLED' and s.status<>'CANCELLED'
          and not exists (
            select 1 from exam_plan_duty_requirement q
            where q.org_id=r.org_id and q.revision_id=r.revision_id and q.room_assignment_id=r.id
          )
        """, (org.springframework.jdbc.core.RowCallbackHandler) result -> issues.add(issue(
            "ROOM_DUTY_REQUIREMENTS_MISSING", "roomAssignment", result.getObject("id", UUID.class),
            "Session " + result.getString("session_code") + " room " + result.getString("room_code")
                + " has no duty requirements",
            "Define the required chief/invigilator roles for every room")), orgId, revisionId);

    Map<String, List<ExamOperationsHardConstraints.Window>> hardLeaves = hardTeacherLeaves(orgId, revisionId);
    Map<TeacherDay, Integer> dailySessionCounts = publishedDailySessionCounts(orgId, revisionId);
    jdbcTemplate.query("""
        select a.id, a.starts_at, a.ends_at, t.id teacher_id, t.teacher_no, t.status teacher_status,
               t.leave_windows::text, t.duty_windows::text, t.qualifications::text,
               t.position, t.is_homeroom, t.qualification_expiry, t.max_daily_sessions,
               t.preferred_campus, q.duty_role required_role, q.qualification::text
        from exam_plan_duty_assignment a
        join teacher t on t.org_id=a.org_id and t.id=a.teacher_id
        join exam_plan_duty_requirement q on q.org_id=a.org_id and q.revision_id=a.revision_id
          and q.id=a.requirement_id
        where a.org_id=? and a.revision_id=? and a.status not in ('CANCELLED','DECLINED')
        """, result -> {
          UUID assignmentId = result.getObject("id", UUID.class);
          UUID teacherId = result.getObject("teacher_id", UUID.class);
          String teacherNo = result.getString("teacher_no");
          Instant startsAt = result.getTimestamp("starts_at").toInstant();
          LocalDate dutyDate = startsAt.atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toLocalDate();
          if (!"active".equals(result.getString("teacher_status"))) {
            issues.add(issue("TEACHER_INACTIVE", "dutyAssignment", assignmentId,
                "Teacher " + teacherNo + " is no longer active", "Assign an active substitute"));
            return;
          }
          List<ExamOperationsHardConstraints.Window> constraints = new ArrayList<>();
          constraints.addAll(hardLeaves.getOrDefault(teacherNo, List.of()));
          constraints.addAll(hardLeaves.getOrDefault(teacherId.toString(), List.of()));
          if (!hardConstraints.teacherAvailable(
              parse(result.getString("leave_windows")), parse(result.getString("duty_windows")), constraints,
              startsAt, result.getTimestamp("ends_at").toInstant())) {
            issues.add(issue("TEACHER_UNAVAILABLE", "dutyAssignment", assignmentId,
                "Teacher " + teacherNo + " is on leave, outside a duty window, or blocked by a hard leave constraint",
                "Assign an eligible substitute for this interval"));
          }
          ExamOperationsHardConstraints.TeacherEligibility eligibility =
              new ExamOperationsHardConstraints.TeacherEligibility(
                  parse(result.getString("qualifications")), result.getString("position"),
                  result.getBoolean("is_homeroom"), result.getObject("qualification_expiry", LocalDate.class),
                  result.getInt("max_daily_sessions"), result.getString("preferred_campus"));
          if (!hardConstraints.teacherEligible(result.getString("required_role"),
              parse(result.getString("qualification")),
              eligibility, dutyDate)) {
            issues.add(issue("TEACHER_DUTY_QUALIFICATION_MISSING", "dutyAssignment", assignmentId,
                "Teacher " + teacherNo + " does not satisfy the " + result.getString("required_role")
                    + " duty qualification",
                "Assign a teacher with every required qualification before publishing"));
          }
          int assignedToday = dailySessionCounts.merge(new TeacherDay(teacherId, dutyDate), 1, Integer::sum);
          if (assignedToday > eligibility.maxDailySessions()) {
            issues.add(issue("TEACHER_DAILY_SESSION_LIMIT_EXCEEDED", "dutyAssignment", assignmentId,
                "Teacher " + teacherNo + " has " + assignedToday + " duties on " + dutyDate
                    + " but the configured daily limit is " + eligibility.maxDailySessions(),
                "Reassign duties or explicitly raise the teacher's daily limit"));
          }
        }, orgId, revisionId);

    jdbcTemplate.query("""
        select c.id constraint_id, c.resource_id candidate_id,
               c.expression->>'requiredRoomFeature' required_feature,
               s.id seat_id, cl.code room_code, cl.features::text
        from exam_plan_constraint c
        left join exam_plan_seat_assignment s on s.org_id=c.org_id and s.revision_id=c.revision_id
          and s.candidate_external_id=c.resource_id and s.status<>'CANCELLED'
        left join exam_plan_room_assignment r on r.org_id=s.org_id and r.id=s.room_assignment_id
          and r.status<>'CANCELLED'
        left join classroom cl on cl.org_id=r.org_id and cl.id=r.classroom_id
        where c.org_id=? and c.revision_id=? and c.enabled and c.severity='HARD'
          and c.constraint_type='SPECIAL_NEED' and c.resource_type='candidate'
          and jsonb_exists(c.expression, 'requiredRoomFeature')
        """, result -> {
          String required = result.getString("required_feature");
          if (result.getObject("seat_id") == null
              || !hardConstraints.roomSupports(parse(result.getString("features")), required)) {
            issues.add(issue("SPECIAL_NEED_UNSATISFIED", "constraint",
                result.getObject("constraint_id", UUID.class),
                "Candidate " + result.getString("candidate_id") + " is not seated in a room with " + required,
                "Move the candidate to a compatible accessible/equipped room"));
          }
        }, orgId, revisionId);
    return List.copyOf(issues);
  }

  private Map<String, List<ExamOperationsHardConstraints.Window>> hardTeacherLeaves(UUID orgId, UUID revisionId) {
    Map<String, List<ExamOperationsHardConstraints.Window>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select resource_id, expression::text from exam_plan_constraint
        where org_id=? and revision_id=? and enabled and severity='HARD'
          and constraint_type='TEACHER_LEAVE' and resource_type='teacher'
        """, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
          ExamOperationsHardConstraints.Window window = hardConstraints.expressionWindow(parse(row.getString(2)));
          if (window != null) result.computeIfAbsent(row.getString(1), ignored -> new ArrayList<>()).add(window);
        }, orgId, revisionId);
    return result;
  }

  private void assertStrictlyPublishable(UUID orgId, UUID revisionId) {
    List<ValidationIssue> issues = strictPublicationIssues(orgId, revisionId);
    if (!issues.isEmpty()) {
      ValidationIssue first = issues.getFirst();
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_NOT_READY",
          first.code() + ": " + first.message() + " (" + issues.size() + " blocking issue(s))");
    }
  }

  private PlanRevisionView predecessor(UUID planId, UUID requested) {
    if (requested != null) {
      return requireRevision(planId, requested);
    }
    List<PlanRevisionView> existing = revisions(planId);
    return existing.isEmpty() ? null : existing.getFirst();
  }

  private PlanRevisionView requireMutableRevision(UUID planId, UUID revisionId) {
    PlanRevisionView value = requireRevision(planId, revisionId);
    if (IMMUTABLE.contains(value.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "REVISION_IMMUTABLE", "Published revision is immutable; create a successor revision");
    }
    return value;
  }

  private PlanRevisionView requireRevision(UUID planId, UUID revisionId) {
    List<PlanRevisionView> rows = jdbcTemplate.query("""
        select id, plan_id, revision_no, predecessor_id, status, source_type, title, change_reason,
               configuration::text, validation_summary::text, version, submitted_at, approved_at,
               published_at, created_at, updated_at
        from exam_plan_revision where org_id=? and plan_id=? and id=?
        """, this::revision, TenantContext.getRequired(), planId, revisionId);
    if (rows.isEmpty()) {
      throw notFound();
    }
    return rows.getFirst();
  }

  private void mutateRevision(
      PlanRevisionView revision,
      Long expectedCursor,
      String status,
      UUID actorId,
      String extraAssignment,
      Object extraValue
  ) {
    long cursor = expectedCursor == null ? revision.revisionCursor() : expectedCursor;
    String extra = extraAssignment == null ? "" : ", " + extraAssignment;
    String sql = "update exam_plan_revision set status=?, updated_by=?, updated_at=now(), version=version+1" + extra
        + " where org_id=? and id=? and version=?";
    int changed = extraValue == null
        ? jdbcTemplate.update(sql, status, actorId, TenantContext.getRequired(), revision.id(), cursor)
        : jdbcTemplate.update(sql, status, actorId, extraValue, TenantContext.getRequired(), revision.id(), cursor);
    if (changed != 1) {
      throw conflict();
    }
  }

  private void assertCursor(PlanRevisionView revision, Long expected) {
    if (expected != null && expected != revision.revisionCursor()) {
      throw conflict();
    }
  }

  private void assertStatus(PlanRevisionView revision, List<String> allowed, String message) {
    if (!allowed.contains(revision.status())) {
      throw new ApiException(HttpStatus.CONFLICT, "PLAN_STATE_INVALID", message + " (current=" + revision.status() + ")");
    }
  }

  private void updatePlanStatus(UUID orgId, UUID planId, String status, UUID actorId) {
    jdbcTemplate.update("""
        update exam_plan set status=?, updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status not in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
        """, status, actorId, orgId, planId);
  }

  private int count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private int number(String sql, Object... args) {
    Number value = jdbcTemplate.queryForObject(sql, Number.class, args);
    return value == null ? 0 : value.intValue();
  }

  private ValidationIssue issue(String code, String resourceType, Object resourceId, String message, String suggestion) {
    return new ValidationIssue(code, "HARD", resourceType, String.valueOf(resourceId), message, suggestion);
  }

  private void audit(
      UUID orgId, UUID planId, UUID revisionId, UUID actorId, String action, String reason,
      String before, String after
  ) {
    jdbcTemplate.update("""
        insert into exam_plan_audit_event (
          org_id, plan_id, revision_id, action, actor_id, reason, before_state, after_state, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
        """, orgId, planId, revisionId, action, actorId, blankToNull(reason),
        before == null ? "{}" : before, after == null ? "{}" : after, actorId, actorId);
  }

  private UUID idempotentResource(UUID orgId, String operation, String key) {
    if (key == null || key.isBlank()) return null;
    if (key.length() > 160) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key exceeds 160 characters");
    }
    return jdbcTemplate.query(
        "select resource_id from api_idempotency_record where org_id=? and operation=? and idempotency_key=?",
        result -> result.next() ? result.getObject(1, UUID.class) : null,
        orgId, operation, key.trim());
  }

  private void rememberIdempotency(
      UUID orgId, UUID actorId, String operation, String key, String resourceType, UUID resourceId
  ) {
    if (key == null || key.isBlank()) return;
    jdbcTemplate.update("""
        insert into api_idempotency_record (
          org_id, idempotency_key, operation, resource_type, resource_id, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?)
        """, orgId, key.trim(), operation, resourceType, resourceId, actorId, actorId);
  }

  private ExamPlanView plan(ResultSet result, int row) throws SQLException {
    return new ExamPlanView(
        result.getObject("id", UUID.class), result.getString("plan_no"), result.getString("name"),
        result.getObject("term_id", UUID.class), result.getString("status"),
        result.getObject("active_revision_id", UUID.class), result.getObject("legacy_exam_task_id", UUID.class),
        parse(result.getString("settings")), result.getLong("version"), instant(result, "created_at"),
        instant(result, "updated_at"));
  }

  private PlanRevisionView revision(ResultSet result, int row) throws SQLException {
    return new PlanRevisionView(
        result.getObject("id", UUID.class), result.getObject("plan_id", UUID.class), result.getInt("revision_no"),
        result.getObject("predecessor_id", UUID.class), result.getString("status"), result.getString("source_type"),
        result.getString("title"), result.getString("change_reason"), parse(result.getString("configuration")),
        parse(result.getString("validation_summary")), result.getLong("version"), instant(result, "submitted_at"),
        instant(result, "approved_at"), instant(result, "published_at"), instant(result, "created_at"),
        instant(result, "updated_at"));
  }

  private Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private JsonNode parse(String value) {
    try {
      return objectMapper.readTree(value == null ? "{}" : value);
    } catch (Exception ignored) {
      return objectMapper.createObjectNode();
    }
  }

  private String json(JsonNode value, JsonNode fallback) {
    return (value == null ? fallback : value).toString();
  }

  private String normalizePlanNo(String requested) {
    if (requested != null && !requested.isBlank()) {
      return requested.trim().toUpperCase(Locale.ROOT);
    }
    return "PLAN-" + PLAN_NO_DATE.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Exam plan is unavailable");
  }

  private ApiException conflict() {
    return new ApiException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "Revision changed; reload and retry with the latest cursor");
  }

  private Map<TeacherDay, Integer> publishedDailySessionCounts(UUID orgId, UUID currentRevisionId) {
    Map<TeacherDay, Integer> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select a.teacher_id, a.starts_at
        from exam_plan_duty_assignment a
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where a.org_id=? and a.revision_id<>? and r.status in ('PUBLISHED','EXECUTING')
          and r.plan_id<>(
            select current_revision.plan_id from exam_plan_revision current_revision
            where current_revision.org_id=? and current_revision.id=?
          )
          and a.status not in ('CANCELLED','DECLINED')
        """, row -> {
          UUID teacherId = row.getObject("teacher_id", UUID.class);
          LocalDate date = row.getTimestamp("starts_at").toInstant()
              .atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toLocalDate();
          result.merge(new TeacherDay(teacherId, date), 1, Integer::sum);
        }, orgId, currentRevisionId, orgId, currentRevisionId);
    return result;
  }

  private record TeacherDay(UUID teacherId, LocalDate date) {
  }
}
