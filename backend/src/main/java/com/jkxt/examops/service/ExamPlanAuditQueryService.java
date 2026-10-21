package com.jkxt.examops.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.ExamPlanAuditDtos.AuditEventPage;
import com.jkxt.examops.api.ExamPlanAuditDtos.AuditEventView;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads only explicitly version-linked domain events, not a complete/tamper-proof history. */
@Service
public class ExamPlanAuditQueryService {
  private final JdbcTemplate jdbc;

  public ExamPlanAuditQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Transactional(readOnly = true)
  public AuditEventPage events(UUID planId, UUID revisionId, int limit, String cursor) {
    UUID orgId = TenantContext.getRequired();
    if (limit < 1 || limit > 100) throw invalid("Audit page limit must be between 1 and 100");
    Position position = decode(cursor, orgId, planId, revisionId);
    Integer exists = jdbc.queryForObject(
        "select count(*) from exam_plan_revision where org_id=? and plan_id=? and id=?",
        Integer.class, orgId, planId, revisionId);
    if (exists == null || exists == 0) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    }
    List<Object> args = new ArrayList<>(List.of(orgId, planId, revisionId));
    String boundary = "";
    if (position != null) {
      boundary = " and (e.created_at, e.id) < (?, ?)";
      args.add(Timestamp.from(position.at())); args.add(position.id());
    }
    args.add(limit + 1);
    // A nullable/deleted/cross-tenant actor must neither remove the event nor disclose another
    // tenant's current account name. Names are current metadata, not historical snapshots.
    List<AuditEventView> rows = jdbc.query("""
        select e.id, e.action, e.created_at, u.display_name as actor_display_name
        from exam_plan_audit_event e
        left join app_user u on u.id=e.actor_id and u.org_id=e.org_id and u.deleted_at is null
        where e.org_id=? and e.plan_id=? and e.revision_id=?
        """ + boundary + " order by e.created_at desc, e.id desc limit ?",
        (rs, row) -> new AuditEventView(rs.getObject("id", UUID.class), rs.getString("action"),
            rs.getTimestamp("created_at").toInstant(), rs.getString("actor_display_name")), args.toArray());
    boolean more = rows.size() > limit;
    List<AuditEventView> items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
    String next = more ? encode(orgId, planId, revisionId, items.getLast()) : null;
    return new AuditEventPage(planId, revisionId, "EXAM_PLAN_AUDIT_EVENT", items, next, more);
  }

  private String encode(UUID org, UUID plan, UUID revision, AuditEventView last) {
    String value = String.join("|", "1", org.toString(), plan.toString(), revision.toString(),
        last.occurredAt().toString(), last.id().toString());
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private Position decode(String value, UUID org, UUID plan, UUID revision) {
    if (value == null) return null;
    if (value.isEmpty() || value.length() > 1024 || !value.matches("[A-Za-z0-9_-]+")) throw invalid("Audit cursor is invalid");
    try {
      String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
      if (parts.length != 6 || !parts[0].equals("1") || !parts[1].equals(org.toString())
          || !parts[2].equals(plan.toString()) || !parts[3].equals(revision.toString())
          || !parts[4].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")) {
        throw invalid("Audit cursor is invalid for this version");
      }
      Instant at = Instant.parse(parts[4]);
      UUID id = UUID.fromString(parts[5]);
      if (!id.toString().equals(parts[5])) throw invalid("Audit cursor ID is invalid");
      return new Position(at, id);
    } catch (IllegalArgumentException | DateTimeException exception) {
      throw invalid("Audit cursor is invalid");
    }
  }

  private ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "AUDIT_PAGE_INVALID", message);
  }

  private record Position(Instant at, UUID id) {}
}
