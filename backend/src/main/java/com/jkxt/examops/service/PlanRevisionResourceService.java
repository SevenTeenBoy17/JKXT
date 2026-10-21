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
                 s.name as session_name, s.subject_name,
                 req.room_assignment_id, room.classroom_id,
                 classroom.name as room_name, classroom.code as room_code,
                 a.requirement_id, a.teacher_id,
                 coalesce(snap.teacher_no, t.teacher_no) as teacher_no,
                 coalesce(snap.teacher_name, t.name) as teacher_name,
                 a.starts_at, a.ends_at, a.duty_role, a.status, a.source, a.version,
                 e.teacher_id as current_teacher_id,
                 coalesce(current_snap.teacher_name, current_teacher.name) as current_teacher_name,
                 coalesce(current_snap.teacher_no, current_teacher.teacher_no) as current_teacher_no,
                 e.status as current_status, e.engagement_no as current_engagement_no
          from exam_plan_duty_assignment a
          join exam_plan_duty_requirement req
            on req.id=a.requirement_id and req.org_id=a.org_id and req.revision_id=a.revision_id
          join exam_plan_session s
            on s.id=req.session_id and s.org_id=a.org_id and s.revision_id=a.revision_id
          -- The session belongs to this revision; room labels are current master data, not a publication snapshot.
          left join exam_plan_room_assignment room
            on room.id=req.room_assignment_id and room.org_id=a.org_id
              and room.revision_id=a.revision_id and room.session_id=req.session_id
          left join classroom on classroom.id=room.classroom_id and classroom.org_id=a.org_id
          join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
          join exam_plan_effective_duty e on e.org_id=a.org_id and e.id=a.id
          join teacher current_teacher on current_teacher.org_id=e.org_id and current_teacher.id=e.teacher_id
          -- PB-10 (round 9): a published roster shows the teacher as published (V36 snapshot).
          left join exam_plan_teacher_snapshot snap
            on snap.org_id=a.org_id and snap.revision_id=a.revision_id and snap.teacher_id=a.teacher_id
          left join exam_plan_teacher_snapshot current_snap
            on current_snap.org_id=a.org_id and current_snap.revision_id=a.revision_id and current_snap.teacher_id=e.teacher_id
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
               a.requirement_id, a.teacher_id,
               coalesce(snap.teacher_no, t.teacher_no) as teacher_no,
               coalesce(snap.teacher_name, t.name) as teacher_name,
               a.starts_at, a.ends_at, a.duty_role, a.status, a.source, a.version,
               a.revision_id, a.engagement_no as "engagementNo"
        from exam_plan_effective_duty a
        join exam_plan_revision rev on rev.org_id=a.org_id and rev.id=a.revision_id
        join exam_plan p on p.org_id=rev.org_id and p.id=rev.plan_id and p.active_revision_id=rev.id
        join exam_plan_duty_requirement req on req.id=a.requirement_id and req.org_id=a.org_id
        join exam_plan_session s on s.id=req.session_id and s.org_id=a.org_id
        join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
        left join exam_plan_teacher_snapshot snap
          on snap.org_id=a.org_id and snap.revision_id=a.revision_id and snap.teacher_id=a.teacher_id
        where a.org_id=? and t.app_user_id=? and a.status<>'CANCELLED'
          and rev.status in ('PUBLISHED','EXECUTING') and s.status<>'CANCELLED'
          and (req.room_assignment_id is null or exists (
            select 1 from exam_plan_room_assignment ra where ra.org_id=req.org_id
              and ra.revision_id=req.revision_id and ra.id=req.room_assignment_id and ra.status<>'CANCELLED'))
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
