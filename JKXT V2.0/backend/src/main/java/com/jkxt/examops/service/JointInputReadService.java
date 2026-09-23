package com.jkxt.examops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.JointInputDtos.AvoidanceView;
import com.jkxt.examops.api.JointInputDtos.CampusTravelView;
import com.jkxt.examops.api.JointInputDtos.CandidateView;
import com.jkxt.examops.api.JointInputDtos.CandidateWindowView;
import com.jkxt.examops.api.JointInputDtos.DemandClassView;
import com.jkxt.examops.api.JointInputDtos.DemandView;
import com.jkxt.examops.api.JointInputDtos.DemandWindowView;
import com.jkxt.examops.api.JointInputDtos.EnrollmentView;
import com.jkxt.examops.api.JointInputDtos.ResourceSnapshot;
import com.jkxt.examops.api.JointInputDtos.RoomPoolView;
import com.jkxt.examops.api.JointInputDtos.RoomWindowView;
import com.jkxt.examops.api.JointInputDtos.SeatView;
import com.jkxt.examops.api.JointInputDtos.SpecialRequirementView;
import com.jkxt.examops.api.JointInputDtos.TeacherPoolView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JointInputReadService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };
  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
  };

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JointInputReadService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public ResourceSnapshot<DemandView> demands(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<DemandView> items = jdbcTemplate.query("""
        select id, demand_code, name, subject_id, duration_minutes, candidate_count,
          invigilator_count, min_rooms, max_rooms, split_allowed,
          required_room_features::text, settings::text
        from exam_plan_exam_demand where org_id=? and revision_id=? order by demand_code
        """, (rs, row) -> new DemandView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
        rs.getObject(4, UUID.class), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
        rs.getInt(9), rs.getBoolean(10), list(rs.getString(11)), map(rs.getString(12))), orgId, revisionId);
    return snapshot(revisionId, "demands", cursor, items);
  }

  public ResourceSnapshot<DemandClassView> demandClasses(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<DemandClassView> items = jdbcTemplate.query("""
        select dc.id, d.demand_code, dc.class_id, dc.expected_candidates, dc.settings::text
        from exam_plan_demand_class dc
        join exam_plan_exam_demand d on d.org_id=dc.org_id and d.revision_id=dc.revision_id and d.id=dc.demand_id
        where dc.org_id=? and dc.revision_id=? order by d.demand_code, dc.class_id
        """, (rs, row) -> new DemandClassView(rs.getObject(1, UUID.class), rs.getString(2),
        rs.getObject(3, UUID.class), rs.getInt(4), map(rs.getString(5))), orgId, revisionId);
    return snapshot(revisionId, "demand-classes", cursor, items);
  }

  public ResourceSnapshot<DemandWindowView> demandWindows(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<DemandWindowView> items = jdbcTemplate.query("""
        select w.id, d.demand_code, w.window_code, w.starts_at, w.ends_at,
          w.preference_weight, w.locked, w.settings::text
        from exam_plan_demand_window w
        join exam_plan_exam_demand d on d.org_id=w.org_id and d.revision_id=w.revision_id and d.id=w.demand_id
        where w.org_id=? and w.revision_id=? order by d.demand_code, w.starts_at, w.window_code
        """, (rs, row) -> new DemandWindowView(rs.getObject(1, UUID.class), rs.getString(2),
        rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getInt(6),
        rs.getBoolean(7), map(rs.getString(8))), orgId, revisionId);
    return snapshot(revisionId, "demand-windows", cursor, items);
  }

  public ResourceSnapshot<CandidateView> candidates(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<CandidateView> items = jdbcTemplate.query("""
        select id, candidate_code, name, class_id, attributes::text
        from exam_plan_candidate where org_id=? and revision_id=? order by candidate_code
        """, (rs, row) -> new CandidateView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
        rs.getObject(4, UUID.class), map(rs.getString(5))), orgId, revisionId);
    return snapshot(revisionId, "candidates", cursor, items);
  }

  public ResourceSnapshot<EnrollmentView> enrollments(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<EnrollmentView> items = jdbcTemplate.query("""
        select e.id, c.candidate_code, d.demand_code, e.status, e.settings::text
        from exam_plan_enrollment e
        join exam_plan_candidate c on c.org_id=e.org_id and c.revision_id=e.revision_id and c.id=e.candidate_id
        join exam_plan_exam_demand d on d.org_id=e.org_id and d.revision_id=e.revision_id and d.id=e.demand_id
        where e.org_id=? and e.revision_id=? order by c.candidate_code, d.demand_code
        """, (rs, row) -> new EnrollmentView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
        rs.getString(4), map(rs.getString(5))), orgId, revisionId);
    return snapshot(revisionId, "enrollments", cursor, items);
  }

  public ResourceSnapshot<CandidateWindowView> candidateWindows(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<CandidateWindowView> items = jdbcTemplate.query("""
        select w.id, c.candidate_code, w.window_type, w.starts_at, w.ends_at, w.reason, w.settings::text
        from exam_plan_candidate_window w
        join exam_plan_candidate c on c.org_id=w.org_id and c.revision_id=w.revision_id and c.id=w.candidate_id
        where w.org_id=? and w.revision_id=? order by c.candidate_code, w.starts_at, w.window_type
        """, (rs, row) -> new CandidateWindowView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getString(6), map(rs.getString(7))),
        orgId, revisionId);
    return snapshot(revisionId, "candidate-windows", cursor, items);
  }

  public ResourceSnapshot<SpecialRequirementView> specialRequirements(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<SpecialRequirementView> items = jdbcTemplate.query("""
        select r.id, c.candidate_code, d.demand_code, r.requirement_code, r.requirement_type,
          r.required_features::text, r.extra_minutes, r.separate_room, r.details::text
        from exam_plan_special_requirement r
        join exam_plan_candidate c on c.org_id=r.org_id and c.revision_id=r.revision_id and c.id=r.candidate_id
        left join exam_plan_exam_demand d on d.org_id=r.org_id and d.revision_id=r.revision_id and d.id=r.demand_id
        where r.org_id=? and r.revision_id=? order by c.candidate_code, d.demand_code nulls first, r.requirement_code
        """, (rs, row) -> new SpecialRequirementView(rs.getObject(1, UUID.class), rs.getString(2),
        rs.getString(3), rs.getString(4), rs.getString(5), list(rs.getString(6)), rs.getInt(7),
        rs.getBoolean(8), map(rs.getString(9))), orgId, revisionId);
    return snapshot(revisionId, "special-requirements", cursor, items);
  }

  public ResourceSnapshot<RoomPoolView> roomPool(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<RoomPoolView> items = jdbcTemplate.query("""
        select id, classroom_id, campus_code, exam_capacity, features::text, status, settings::text
        from exam_plan_room_pool where org_id=? and revision_id=? order by campus_code, classroom_id
        """, (rs, row) -> new RoomPoolView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
        rs.getString(3), rs.getInt(4), list(rs.getString(5)), rs.getString(6), map(rs.getString(7))),
        orgId, revisionId);
    return snapshot(revisionId, "room-pool", cursor, items);
  }

  public ResourceSnapshot<RoomWindowView> roomWindows(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<RoomWindowView> items = jdbcTemplate.query("""
        select w.id, p.classroom_id, w.window_type, w.starts_at, w.ends_at, w.reason, w.settings::text
        from exam_plan_room_window w
        join exam_plan_room_pool p on p.org_id=w.org_id and p.revision_id=w.revision_id and p.id=w.room_pool_id
        where w.org_id=? and w.revision_id=? order by p.classroom_id, w.starts_at, w.window_type
        """, (rs, row) -> new RoomWindowView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
        rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getString(6),
        map(rs.getString(7))), orgId, revisionId);
    return snapshot(revisionId, "room-windows", cursor, items);
  }

  public ResourceSnapshot<SeatView> seats(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<SeatView> items = jdbcTemplate.query("""
        select s.id, p.classroom_id, s.seat_code, s.row_no, s.column_no, s.features::text, s.status
        from exam_plan_room_seat s
        join exam_plan_room_pool p on p.org_id=s.org_id and p.revision_id=s.revision_id and p.id=s.room_pool_id
        where s.org_id=? and s.revision_id=? order by p.classroom_id, s.seat_code
        """, (rs, row) -> new SeatView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
        rs.getString(3), integer(rs.getObject(4)), integer(rs.getObject(5)), list(rs.getString(6)), rs.getString(7)),
        orgId, revisionId);
    return snapshot(revisionId, "seat-topology", cursor, items);
  }

  public ResourceSnapshot<TeacherPoolView> teacherPool(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<TeacherPoolView> items = jdbcTemplate.query("""
        select id, teacher_id, eligible_roles::text, max_daily_sessions, min_rest_minutes,
          preferred_campus, status, preferences::text
        from exam_plan_teacher_pool where org_id=? and revision_id=? order by teacher_id
        """, (rs, row) -> new TeacherPoolView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
        list(rs.getString(3)), rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7), map(rs.getString(8))),
        orgId, revisionId);
    return snapshot(revisionId, "teacher-pool", cursor, items);
  }

  public ResourceSnapshot<AvoidanceView> avoidances(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<AvoidanceView> items = jdbcTemplate.query("""
        select a.id, p.teacher_id, a.target_type, d.demand_code, a.class_id, c.candidate_code,
          a.severity, a.reason, a.details::text
        from exam_plan_teacher_avoidance a
        join exam_plan_teacher_pool p on p.org_id=a.org_id and p.revision_id=a.revision_id and p.id=a.teacher_pool_id
        left join exam_plan_exam_demand d on d.org_id=a.org_id and d.revision_id=a.revision_id and d.id=a.demand_id
        left join exam_plan_candidate c on c.org_id=a.org_id and c.revision_id=a.revision_id and c.id=a.candidate_id
        where a.org_id=? and a.revision_id=? order by p.teacher_id, a.target_type, a.severity
        """, (rs, row) -> new AvoidanceView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
        rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
        rs.getString(8), map(rs.getString(9))), orgId, revisionId);
    return snapshot(revisionId, "avoidances", cursor, items);
  }

  public ResourceSnapshot<CampusTravelView> campusTravel(UUID revisionId) {
    UUID orgId = TenantContext.getRequired();
    long cursor = cursor(orgId, revisionId);
    List<CampusTravelView> items = jdbcTemplate.query("""
        select id, from_campus, to_campus, travel_minutes, buffer_minutes, settings::text
        from exam_plan_campus_travel where org_id=? and revision_id=? order by from_campus, to_campus
        """, (rs, row) -> new CampusTravelView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
        rs.getInt(4), rs.getInt(5), map(rs.getString(6))), orgId, revisionId);
    return snapshot(revisionId, "campus-travel", cursor, items);
  }

  private long cursor(UUID orgId, UUID revisionId) {
    Long cursor = jdbcTemplate.query("""
        select version from exam_plan_revision where org_id=? and id=?
        """, rs -> rs.next() ? rs.getLong(1) : null, orgId, revisionId);
    if (cursor == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    }
    return cursor;
  }

  private <T> ResourceSnapshot<T> snapshot(UUID revisionId, String resource, long cursor, List<T> items) {
    return new ResourceSnapshot<>(revisionId, resource, cursor, List.copyOf(items));
  }

  private List<String> list(String json) {
    return read(json, STRING_LIST);
  }

  private Map<String, Object> map(String json) {
    return read(json, OBJECT_MAP);
  }

  private <T> T read(String json, TypeReference<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception exception) {
      throw new IllegalStateException("Stored joint input JSON is unreadable", exception);
    }
  }

  private Integer integer(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }
}
