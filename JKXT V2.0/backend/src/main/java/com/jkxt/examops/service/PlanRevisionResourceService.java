package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanRevisionResourceService {

  private static final Map<String, String> QUERIES = Map.of(
      "sessions", """
          select id, session_code as code, name, subject_name, grade, starts_at, ends_at,
                 candidate_count, status, requirements::text as requirements, version
          from exam_plan_session where org_id=? and revision_id=? order by starts_at, session_code
          """,
      "rooms", """
          select r.id, r.session_id, r.classroom_id, c.code as room_code, c.name as room_name,
                 r.starts_at, r.ends_at, r.planned_capacity, r.allocated_candidates, r.status,
                 r.required_features::text as required_features, r.version
          from exam_plan_room_assignment r join classroom c on c.id=r.classroom_id and c.org_id=r.org_id
          where r.org_id=? and r.revision_id=? order by r.starts_at, c.code
          """,
      "seats", """
          select id, session_id, room_assignment_id, candidate_external_id, seat_label,
                 (special_arrangement <> '{}'::jsonb) as has_special_arrangement, status, version
          from exam_plan_seat_assignment where org_id=? and revision_id=? order by session_id, seat_label
          """,
      "constraints", """
          select id, constraint_type, severity, resource_type, resource_id, expression::text as expression,
                 enabled, version
          from exam_plan_constraint where org_id=? and revision_id=? order by severity, constraint_type
          """,
      "duties", """
          select a.id, req.session_id, s.session_code as session_external_code,
                 a.requirement_id, a.teacher_id, t.teacher_no, t.name as teacher_name,
                 a.starts_at, a.ends_at, a.duty_role, a.status, a.source, a.version
          from exam_plan_duty_assignment a
          join exam_plan_duty_requirement req on req.id=a.requirement_id and req.org_id=a.org_id
          join exam_plan_session s on s.id=req.session_id and s.org_id=a.org_id
          join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
          where a.org_id=? and a.revision_id=? order by a.starts_at, t.teacher_no
          """,
      "deliveries", """
          select d.id, d.outbox_id, d.outbox_id as event_id, d.recipient_type, d.recipient_id,
                 d.channel, d.status, d.receipt::text as receipt, d.version, d.created_at, d.updated_at
          from exam_plan_delivery d
          join exam_plan_outbox o on o.id=d.outbox_id and o.org_id=d.org_id
          where d.org_id=? and o.revision_id=? order by d.created_at, d.id
          """
  );

  private static final Set<String> JSON_COLUMNS = Set.of("requirements", "required_features", "expression", "receipt");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public PlanRevisionResourceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> resources(UUID revisionId, String kind) {
    UUID orgId = TenantContext.getRequired();
    Integer exists = jdbcTemplate.queryForObject(
        "select count(*) from exam_plan_revision where org_id=? and id=?", Integer.class, orgId, revisionId);
    if (exists == null || exists == 0) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    }
    String query = QUERIES.get(kind);
    if (query == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Plan revision resource is unavailable");
    }
    return jdbcTemplate.query(
        query,
        (ResultSetExtractor<List<Map<String, Object>>>) this::rows,
        orgId,
        revisionId);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> ownAssignments(UUID userId) {
    UUID orgId = TenantContext.getRequired();
    return jdbcTemplate.query("""
        select a.id, req.session_id, s.session_code as session_external_code,
               a.requirement_id, a.teacher_id, t.teacher_no, t.name as teacher_name,
               a.starts_at, a.ends_at, a.duty_role, a.status, a.source, a.version,
               a.revision_id
        from exam_plan_duty_assignment a
        join exam_plan_duty_requirement req on req.id=a.requirement_id and req.org_id=a.org_id
        join exam_plan_session s on s.id=req.session_id and s.org_id=a.org_id
        join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
        where a.org_id=? and t.app_user_id=? and a.status<>'CANCELLED'
        order by a.starts_at, s.session_code
        """, (ResultSetExtractor<List<Map<String, Object>>>) this::rows, orgId, userId);
  }

  private List<Map<String, Object>> rows(ResultSet result) throws SQLException {
    List<Map<String, Object>> rows = new ArrayList<>();
    ResultSetMetaData metadata = result.getMetaData();
    while (result.next()) {
      Map<String, Object> row = new LinkedHashMap<>();
      for (int column = 1; column <= metadata.getColumnCount(); column++) {
        String name = metadata.getColumnLabel(column);
        Object value = result.getObject(column);
        if (value instanceof Timestamp timestamp) value = timestamp.toInstant();
        if (value instanceof String text && JSON_COLUMNS.contains(name)) value = json(text);
        row.put(name, value);
      }
      rows.add(row);
    }
    return List.copyOf(rows);
  }

  private JsonNode json(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (Exception ignored) {
      return objectMapper.createObjectNode();
    }
  }
}
