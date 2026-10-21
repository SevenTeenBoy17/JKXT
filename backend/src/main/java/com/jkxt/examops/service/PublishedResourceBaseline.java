package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only live reservations. Historical revisions are evidence, not extra reservations. */
@Service
public class PublishedResourceBaseline {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ExamOperationsHardConstraints constraints;

  public PublishedResourceBaseline(JdbcTemplate jdbc, ObjectMapper mapper,
      ExamOperationsHardConstraints constraints) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.constraints = constraints;
  }

  public Snapshot load(UUID orgId, UUID excludedPlanId, UUID excludedLegacyTaskId) {
    Set<UUID> excludedPlans = new HashSet<>();
    Set<UUID> excludedTasks = new HashSet<>();
    if (excludedPlanId != null) excludedPlans.add(excludedPlanId);
    if (excludedLegacyTaskId != null) excludedTasks.add(excludedLegacyTaskId);
    // Resolve both bridge forms, always tenant-qualified. Excluding an edit target also excludes
    // its own projection/legacy source, never another plan's committed reservation.
    jdbc.query("""
        select p.id,coalesce(p.legacy_exam_task_id,l.exam_task_id) task_id
        from exam_plan p left join legacy_exam_task_plan_link l on l.org_id=p.org_id and l.plan_id=p.id
        where p.org_id=?
        """, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
      UUID plan = row.getObject("id", UUID.class), task = row.getObject("task_id", UUID.class);
      if (excludedPlans.contains(plan) || (task != null && excludedTasks.contains(task))) {
        excludedPlans.add(plan);
        if (task != null) excludedTasks.add(task);
      }
    }, orgId);
    List<TeacherDuty> duties = new ArrayList<>();
    List<RoomOccupation> rooms = new ArrayList<>();
    // A current published projection is authoritative even after all of its reservations were
    // cancelled. Deriving coverage from live rows would resurrect the old immutable schedule.
    Set<UUID> projectedTasks = new HashSet<>(jdbc.queryForList("""
        select distinct coalesce(p.legacy_exam_task_id,l.exam_task_id)
        from exam_plan p
        join exam_plan_revision r on r.org_id=p.org_id and r.plan_id=p.id and r.id=p.active_revision_id
        left join legacy_exam_task_plan_link l on l.org_id=p.org_id and l.plan_id=p.id
        where p.org_id=? and r.status in ('PUBLISHED','EXECUTING')
          and coalesce(p.legacy_exam_task_id,l.exam_task_id) is not null
        """, UUID.class, orgId));
    jdbc.query("""
        select a.id,a.teacher_id,a.starts_at,a.ends_at,r.plan_id,
          coalesce(p.legacy_exam_task_id,l.exam_task_id) task_id,
          pool.campus_code,c.features::text features
        from exam_plan_effective_duty a
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        join exam_plan p on p.org_id=r.org_id and p.id=r.plan_id and p.active_revision_id=r.id
        join exam_plan_duty_requirement q on q.org_id=a.org_id and q.revision_id=a.revision_id and q.id=a.requirement_id
        join exam_plan_session s on s.org_id=q.org_id and s.revision_id=q.revision_id and s.id=q.session_id
        left join exam_plan_room_assignment ra on ra.org_id=q.org_id and ra.revision_id=q.revision_id and ra.id=q.room_assignment_id
        left join classroom c on c.org_id=ra.org_id and c.id=ra.classroom_id
        left join exam_plan_room_pool pool on pool.org_id=ra.org_id and pool.revision_id=ra.revision_id and pool.classroom_id=ra.classroom_id
        left join legacy_exam_task_plan_link l on l.org_id=p.org_id and l.plan_id=p.id
        where a.org_id=? and r.status in ('PUBLISHED','EXECUTING')
          and a.status not in ('CANCELLED','DECLINED','REJECTED') and s.status<>'CANCELLED'
          and (q.room_assignment_id is null or ra.status<>'CANCELLED')
        order by a.teacher_id,a.starts_at,a.id
        """, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
      UUID plan = row.getObject("plan_id", UUID.class);
      if (excludedPlans.contains(plan)) return;
      String campus = row.getString("campus_code");
      if (campus == null) campus = constraints.campusCode(json(row.getString("features")));
      duties.add(new TeacherDuty(row.getObject("id", UUID.class), row.getObject("teacher_id", UUID.class),
          row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant(), campus));
    }, orgId);
    jdbc.query("""
        select a.classroom_id,a.starts_at,a.ends_at,r.plan_id,
          coalesce(p.legacy_exam_task_id,l.exam_task_id) task_id
        from exam_plan_room_assignment a
        join exam_plan_session s on s.org_id=a.org_id and s.revision_id=a.revision_id and s.id=a.session_id
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        join exam_plan p on p.org_id=r.org_id and p.id=r.plan_id and p.active_revision_id=r.id
        left join legacy_exam_task_plan_link l on l.org_id=p.org_id and l.plan_id=p.id
        where a.org_id=? and r.status in ('PUBLISHED','EXECUTING') and a.status<>'CANCELLED' and s.status<>'CANCELLED'
        order by a.classroom_id,a.starts_at,a.id
        """, (org.springframework.jdbc.core.RowCallbackHandler) row -> {
      if (excludedPlans.contains(row.getObject("plan_id", UUID.class))) return;
      rooms.add(new RoomOccupation(row.getObject("classroom_id", UUID.class),
          row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant()));
    }, orgId);
    List<UUID> legacyTasks = jdbc.queryForList(
        "select id from exam_task where org_id=? and status='published' order by id", UUID.class, orgId);
    for (UUID task : legacyTasks) {
      if (excludedTasks.contains(task) || projectedTasks.contains(task)) continue;
      UUID run = publishedRun(orgId, task);
      List<LegacyDuty> rows = jdbc.query("""
          select a.id,a.teacher_id,a.classroom_id,a.exam_date,a.start_time,a.end_time,c.features::text
          from schedule_assignment a
          join teacher t on t.org_id=a.org_id and t.id=a.teacher_id
          join classroom c on c.org_id=a.org_id and c.id=a.classroom_id
          join exam e on e.org_id=a.org_id and e.id=a.exam_id and e.exam_task_id=?
          where a.org_id=? and a.schedule_run_id=? order by a.id
          """, (row, index) -> {
        LocalDate date = row.getObject("exam_date", LocalDate.class);
        LocalTime start = row.getObject("start_time", LocalTime.class), end = row.getObject("end_time", LocalTime.class);
        // The legacy schema represents same-day exams. Invalid/overnight rows require explicit
        // correction; inventing a next day would hide corrupt publication history.
        if (!end.isAfter(start)) throw ambiguous();
        return new LegacyDuty(row.getObject("id", UUID.class), row.getObject("teacher_id", UUID.class),
            row.getObject("classroom_id", UUID.class), date.atTime(start).atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toInstant(),
            date.atTime(end).atZone(ExamOperationsHardConstraints.SCHOOL_ZONE).toInstant(),
            constraints.campusCode(json(row.getString("features"))));
      }, task, orgId, run);
      Integer rawCount = jdbc.queryForObject("select count(*) from schedule_assignment where org_id=? and schedule_run_id=?",
          Integer.class, orgId, run);
      if (rows.isEmpty() || rawCount == null || rawCount != rows.size()) throw ambiguous();
      for (LegacyDuty row : rows) {
        duties.add(new TeacherDuty(row.id(), row.teacher(), row.start(), row.end(), row.campus()));
        rooms.add(new RoomOccupation(row.room(), row.start(), row.end()));
      }
    }
    return new Snapshot(List.copyOf(duties), rooms.stream().distinct().toList());
  }

  private UUID publishedRun(UUID org, UUID task) {
    List<String> pointers = jdbc.queryForList("""
        select payload->>'runId' from notification where org_id=? and exam_task_id=?
          and type='schedule_published' order by published_at desc nulls last,created_at desc,id desc limit 1
        """, String.class, org, task);
    List<UUID> runs = jdbc.queryForList("select id from schedule_run where org_id=? and exam_task_id=? and status='succeeded' order by id",
        UUID.class, org, task);
    if (!pointers.isEmpty() && pointers.getFirst() != null) {
      try {
        UUID pointer = UUID.fromString(pointers.getFirst());
        if (runs.contains(pointer)) return pointer;
      } catch (IllegalArgumentException ignored) { /* fail closed below */ }
      throw ambiguous();
    }
    if (runs.size() == 1) return runs.getFirst();
    throw ambiguous();
  }

  private JsonNode json(String raw) {
    try { return mapper.readTree(raw == null ? "[]" : raw); }
    catch (Exception invalid) { throw ambiguous(); }
  }

  private ApiException ambiguous() {
    return new ApiException(HttpStatus.CONFLICT, "LEGACY_PUBLISHED_BASELINE_AMBIGUOUS",
        "旧版已发布任务缺少可核实的排班版本或完整安排，无法安全核定教师与考场占用。请管理员核对发布通知和排班记录后补齐关联，勿直接删除历史记录或重复发布。");
  }

  public record Snapshot(List<TeacherDuty> teacherDuties, List<RoomOccupation> rooms) {}
  public record TeacherDuty(UUID assignmentId, UUID teacherId, Instant startsAt, Instant endsAt, String campus) {}
  public record RoomOccupation(UUID classroomId, Instant startsAt, Instant endsAt) {}
  private record LegacyDuty(UUID id, UUID teacher, UUID room, Instant start, Instant end, String campus) {}
}
