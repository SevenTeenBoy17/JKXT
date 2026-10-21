package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.examops.api.OperationsDtos.ApprovalRequestView;
import com.jkxt.examops.api.OperationsDtos.EmergencyChangeView;
import com.jkxt.examops.api.OperationsDtos.ReadinessIssue;
import com.jkxt.examops.api.OperationsDtos.ReadinessResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsQueryService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public OperationsQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public ReadinessResponse readiness() {
    UUID orgId = TenantContext.getRequired();
    Map<String, Integer> totals = new LinkedHashMap<>();
    totals.put("plans", count("select count(*) from exam_plan where org_id=? and status<>'ARCHIVED'", orgId));
    totals.put("publishedPlans", count("select count(*) from exam_plan where org_id=? and status in ('PUBLISHED','EXECUTING','CLOSED')", orgId));
    totals.put("pendingApprovals", count("select count(*) from exam_plan_approval_request where org_id=? and status='PENDING'", orgId));
    totals.put("activeEmergencies", count("select count(*) from exam_plan_emergency_change where org_id=? and status='ACTIVE'", orgId));
    totals.put("openSubstitutions", count("""
        select count(*) from exam_plan_substitution_case c
        join exam_plan_effective_duty d on d.org_id=c.org_id and d.id=c.duty_assignment_id
          and d.engagement_no=c.engagement_no
        join exam_plan_revision r on r.org_id=d.org_id and r.id=d.revision_id
        join exam_plan p on p.org_id=r.org_id and p.id=r.plan_id and p.active_revision_id=r.id
        join exam_plan_duty_requirement q on q.org_id=d.org_id and q.id=d.requirement_id
        join exam_plan_session s on s.org_id=q.org_id and s.id=q.session_id
        where c.org_id=? and c.status in ('OPEN','ASSIGNED')
          and r.status in ('PUBLISHED','EXECUTING') and d.status<>'CANCELLED' and s.status<>'CANCELLED'
          and (q.room_assignment_id is null or exists (
            select 1 from exam_plan_room_assignment ra where ra.org_id=q.org_id
              and ra.id=q.room_assignment_id and ra.status<>'CANCELLED'))
        """, orgId));
    // Assignment is not acknowledgement. Informational only: retain the existing execution
    // policy instead of inventing a new mandatory confirmation gate.
    totals.put("pendingDutyConfirmations", count("""
        select count(*) from exam_plan_effective_duty d
        join exam_plan_revision r on r.org_id=d.org_id and r.id=d.revision_id
        join exam_plan p on p.org_id=r.org_id and p.id=r.plan_id and p.active_revision_id=r.id
        join exam_plan_duty_requirement q on q.org_id=d.org_id and q.id=d.requirement_id
        join exam_plan_session s on s.org_id=q.org_id and s.id=q.session_id
        where d.org_id=? and d.status='PENDING_CONFIRMATION'
          and r.status in ('PUBLISHED','EXECUTING') and s.status<>'CANCELLED'
          and (q.room_assignment_id is null or exists (
            select 1 from exam_plan_room_assignment ra where ra.org_id=q.org_id
              and ra.id=q.room_assignment_id and ra.status<>'CANCELLED'))
          and not exists (select 1 from exam_plan_substitution_case c
            where c.org_id=d.org_id and c.duty_assignment_id=d.id and c.engagement_no=d.engagement_no
              and c.status in ('OPEN','ASSIGNED'))
        """, orgId));
    totals.put("failedDeliveries", count("""
        select count(*) from exam_plan_delivery where org_id=? and status='FAILED'
        """, orgId));
    totals.put("blockingIncidents", count("""
        select count(*) from exam_plan_incident
        where org_id=? and status='OPEN' and severity in ('HIGH','CRITICAL')
        """, orgId));
    totals.put("requiredChecklistPending", count("""
        select count(*) from exam_plan_checklist_item
        where org_id=? and required and status='PENDING'
        """, orgId));
    totals.put("invalidRevisions", count("select count(*) from exam_plan_revision where org_id=? and status in ('INVALID','INFEASIBLE','FAILED')", orgId));
    totals.put("sessionsWithoutRooms", count("""
        select count(*) from exam_plan_session s
        where s.org_id=? and s.status<>'CANCELLED'
          and exists (select 1 from exam_plan_revision r where r.id=s.revision_id and r.status not in ('ARCHIVED','CANCELLED'))
          and not exists (select 1 from exam_plan_room_assignment a where a.org_id=s.org_id and a.session_id=s.id and a.status<>'CANCELLED')
        """, orgId));
    totals.put("uncoveredDutyPosts", number("""
        select coalesce(sum(greatest(req.required_count - coalesce(a.assigned,0), 0)),0)
        from exam_plan_duty_requirement req
        join exam_plan_revision rev on rev.id=req.revision_id and rev.org_id=req.org_id
        join exam_plan p on p.org_id=rev.org_id and p.id=rev.plan_id
        join exam_plan_session s on s.org_id=req.org_id and s.id=req.session_id and s.revision_id=req.revision_id
        left join (
          select d.org_id, d.requirement_id, count(*) assigned from exam_plan_effective_duty d
          where d.status not in ('CANCELLED','DECLINED','REJECTED')
            and not exists (select 1 from exam_plan_substitution_case c
              where c.org_id=d.org_id and c.duty_assignment_id=d.id and c.engagement_no=d.engagement_no
                and c.status in ('OPEN','ASSIGNED'))
          group by d.org_id, d.requirement_id
        ) a on a.org_id=req.org_id and a.requirement_id=req.id
        where req.org_id=? and p.status<>'ARCHIVED' and s.status<>'CANCELLED'
          and rev.status not in ('ARCHIVED','CANCELLED','CLOSED')
          and (rev.status not in ('PUBLISHED','EXECUTING') or p.active_revision_id=rev.id)
          and (req.room_assignment_id is null or exists (
            select 1 from exam_plan_room_assignment ra where ra.org_id=req.org_id
              and ra.revision_id=req.revision_id and ra.id=req.room_assignment_id and ra.status<>'CANCELLED'))
        """, orgId));

    List<ReadinessIssue> issues = new ArrayList<>();
    add(issues, "NO_EXAM_PLANS", "No active exam plan exists", totals.get("plans") == 0 ? 1 : 0);
    add(issues, "PENDING_APPROVALS", "Plan revisions are awaiting approval", totals.get("pendingApprovals"));
    add(issues, "ACTIVE_EMERGENCIES", "Emergency changes still require resolution", totals.get("activeEmergencies"));
    add(issues, "OPEN_SUBSTITUTIONS", "Invigilation substitutions still require resolution", totals.get("openSubstitutions"));
    add(issues, "FAILED_DELIVERIES", "Notification deliveries require retry", totals.get("failedDeliveries"));
    add(issues, "BLOCKING_INCIDENTS", "High or critical incidents require resolution", totals.get("blockingIncidents"));
    add(issues, "CHECKLIST_INCOMPLETE", "Required operational checklist items remain pending", totals.get("requiredChecklistPending"));
    add(issues, "INVALID_REVISIONS", "Plan revisions contain invalid or infeasible results", totals.get("invalidRevisions"));
    add(issues, "SESSIONS_WITHOUT_ROOMS", "Exam sessions have no room allocation", totals.get("sessionsWithoutRooms"));
    add(issues, "UNCOVERED_DUTY_POSTS", "Invigilation posts remain uncovered", totals.get("uncoveredDutyPosts"));
    int blockers = issues.stream().mapToInt(ReadinessIssue::count).sum();
    return new ReadinessResponse(Instant.now(), blockers == 0, blockers, Map.copyOf(totals), List.copyOf(issues));
  }

  @Transactional(readOnly = true)
  public List<ApprovalRequestView> approvals() {
    return jdbcTemplate.query("""
        select a.id, p.id plan_id, p.name plan_name, r.id revision_id, r.revision_no, a.status,
               a.requested_by, requester.display_name requested_by_name,
               a.decided_by, decider.display_name decided_by_name, a.decision_reason,
               a.decided_at, a.created_at, a.version
        from exam_plan_approval_request a
        join exam_plan_revision r on r.id=a.revision_id and r.org_id=a.org_id
        join exam_plan p on p.id=r.plan_id and p.org_id=r.org_id
        join app_user requester on requester.id=a.requested_by
        left join app_user decider on decider.id=a.decided_by
        where a.org_id=? order by a.created_at desc
        """, this::approval, TenantContext.getRequired());
  }

  @Transactional(readOnly = true)
  public List<EmergencyChangeView> emergencies() {
    return jdbcTemplate.query("""
        select e.id, p.id plan_id, p.name plan_name, r.id revision_id, r.revision_no,
               e.emergency_type, e.status, e.affected_resources::text, e.action_taken::text,
               e.created_at, e.updated_at, e.version
        from exam_plan_emergency_change e
        join exam_plan_revision r on r.id=e.revision_id and r.org_id=e.org_id
        join exam_plan p on p.id=r.plan_id and p.org_id=r.org_id
        where e.org_id=? order by e.created_at desc
        """, this::emergency, TenantContext.getRequired());
  }

  private ApprovalRequestView approval(ResultSet result, int row) throws SQLException {
    return new ApprovalRequestView(
        result.getObject("id", UUID.class), result.getObject("plan_id", UUID.class), result.getString("plan_name"),
        result.getObject("revision_id", UUID.class), result.getInt("revision_no"), result.getString("status"),
        result.getObject("requested_by", UUID.class), result.getString("requested_by_name"),
        result.getObject("decided_by", UUID.class), result.getString("decided_by_name"),
        result.getString("decision_reason"), instant(result, "decided_at"), instant(result, "created_at"),
        result.getLong("version"));
  }

  private EmergencyChangeView emergency(ResultSet result, int row) throws SQLException {
    return new EmergencyChangeView(
        result.getObject("id", UUID.class), result.getObject("plan_id", UUID.class), result.getString("plan_name"),
        result.getObject("revision_id", UUID.class), result.getInt("revision_no"), result.getString("emergency_type"),
        result.getString("status"), json(result.getString("affected_resources")), json(result.getString("action_taken")),
        instant(result, "created_at"), instant(result, "updated_at"), result.getLong("version"));
  }

  private int count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  private int number(String sql, Object... args) {
    Number value = jdbcTemplate.queryForObject(sql, Number.class, args);
    return value == null ? 0 : value.intValue();
  }

  private void add(List<ReadinessIssue> issues, String code, String message, int count) {
    if (count > 0) issues.add(new ReadinessIssue(code, message, count));
  }

  private Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp timestamp = result.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private JsonNode json(String value) {
    try {
      return objectMapper.readTree(value == null ? "{}" : value);
    } catch (Exception ignored) {
      return objectMapper.createObjectNode();
    }
  }
}
