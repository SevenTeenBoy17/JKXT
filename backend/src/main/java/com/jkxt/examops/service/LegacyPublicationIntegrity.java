package com.jkxt.examops.service;

import com.jkxt.common.web.ApiException;
import com.jkxt.scheduling.domain.ScheduleRun;
import com.jkxt.scheduling.repo.ScheduleRunRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Server-owned V30 provenance, never a caller-controlled configuration flag. */
@Service
public class LegacyPublicationIntegrity {
  public static final String MODE = "INVIGILATOR_ONLY";
  public static final String NOTICE = "仅监考安排，未含考生座位名单";
  private final JdbcTemplate jdbc;
  private final ScheduleRunRepository runs;

  public LegacyPublicationIntegrity(JdbcTemplate jdbc, ScheduleRunRepository runs) {
    this.jdbc = jdbc;
    this.runs = runs;
  }

  public Map<String, Object> binding(UUID org, UUID task) {
    return jdbc.query("select * from legacy_exam_task_plan_link where org_id=? and exam_task_id=?",
        rs -> rs.next() ? Map.of(
            "planId", rs.getObject("plan_id", UUID.class),
            "revisionId", rs.getObject("active_revision_id", UUID.class),
            "fingerprint", rs.getString("source_fingerprint") == null ? "" : rs.getString("source_fingerprint"),
            "runId", rs.getObject("projected_run_id") == null ? "" : rs.getObject("projected_run_id", UUID.class),
            "runCursor", rs.getObject("projected_run_revision_cursor") == null ? -1L : rs.getLong("projected_run_revision_cursor")) : Map.of(),
        org, task);
  }

  public ScheduleRun activeRun(UUID org, UUID task) {
    return runs.findActiveRun(org, task)
        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "LEGACY_SCHEDULE_NOT_READY", "请先完成当前任务的监考排班"));
  }

  /** Must be invoked in the same transaction as validation, approval or publication. */
  public boolean requireTrustedProjection(UUID org, UUID revision) {
    String source = jdbc.queryForObject("select source_type from exam_plan_revision where org_id=? and id=?",
        String.class, org, revision);
    if (!"LEGACY".equals(source)) return false;
    List<UUID> tasks = jdbc.queryForList("""
        select exam_task_id from legacy_exam_task_plan_link where org_id=? and active_revision_id=?
        """, UUID.class, org, revision);
    if (tasks.size() != 1) throw stale();
    UUID task = tasks.getFirst();
    String taskStatus = jdbc.queryForObject("select status from exam_task where org_id=? and id=? for update", String.class, org, task);
    if (!List.of("scheduled", "published").contains(taskStatus)) throw stale();
    if (!current(org, task, binding(org, task))) throw stale();
    return true;
  }

  public boolean current(UUID org, UUID task, Map<String, Object> binding) {
    if (binding.isEmpty() || !(binding.get("runId") instanceof UUID runId)) return false;
    ScheduleRun active = activeRun(org, task);
    return "succeeded".equals(active.getStatus()) && runId.equals(active.getId())
        && ((Number) binding.get("runCursor")).longValue() == active.getRevisionCursor()
        && fingerprint(org, task, runId, (UUID) binding.get("revisionId")).equals(binding.get("fingerprint"));
  }

  /** Canonical PostgreSQL JSON covers source content AND projected resources, excluding audit clocks. */
  public String fingerprint(UUID org, UUID task, UUID run, UUID revision) {
    StringBuilder canonical = new StringBuilder();
    append(canonical, "task", jdbc.queryForObject("""
        select jsonb_build_object('id',id,'taskNo',task_no,'name',name,'settings',settings)::text
        from exam_task where org_id=? and id=?
        """, String.class, org, task));
    append(canonical, "run", jdbc.queryForObject("""
        select jsonb_build_object('id',id,'cursor',revision_cursor,'policy',stats->'policySnapshot')::text
        from schedule_run where org_id=? and id=?
        """, String.class, org, run));
    append(canonical, "exams", rows("exam", "exam_task_id", org, task));
    append(canonical, "assignments", rows("schedule_assignment", "schedule_run_id", org, run));
    for (String join : List.of("exam_room", "exam_class")) {
      String order = join.equals("exam_room") ? "classroom_id" : "class_id";
      append(canonical, join, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(x) order by x.exam_id,x."
          + order + "),'[]'::jsonb)::text from " + join + " x where x.org_id=? and x.exam_id in "
          + "(select id from exam where org_id=? and exam_task_id=?)", String.class, org, org, task));
    }
    append(canonical, "teachers", jdbc.queryForObject("""
        select coalesce(jsonb_agg(to_jsonb(t)-'updated_at'-'created_at'-'version' order by t.id),'[]'::jsonb)::text
        from teacher t where t.org_id=? and t.id in (
          select teacher_id from schedule_assignment where org_id=? and schedule_run_id=?)
        """, String.class, org, org, run));
    append(canonical, "classes", jdbc.queryForObject("""
        select coalesce(jsonb_agg(to_jsonb(c)-'updated_at'-'created_at'-'version' order by c.id),'[]'::jsonb)::text
        from school_class c where c.org_id=? and c.id in (
          select ec.class_id from exam_class ec join exam e on e.org_id=ec.org_id and e.id=ec.exam_id
          where e.org_id=? and e.exam_task_id=?)
        """, String.class, org, org, task));
    append(canonical, "revision", jdbc.queryForObject("""
        select jsonb_build_object('sourceType',source_type,'configuration',configuration)::text
        from exam_plan_revision where org_id=? and id=?
        """, String.class, org, revision));
    for (String table : List.of("exam_plan_session", "exam_plan_room_assignment", "exam_plan_seat_assignment",
        "exam_plan_duty_requirement", "exam_plan_duty_assignment", "exam_plan_constraint")) {
      append(canonical, table, rows(table, "revision_id", org, revision));
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private String rows(String table, String owner, UUID org, UUID id) {
    // Table/column names only come from the literal allow-lists above, never requests.
    return jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(x)-'created_at'-'updated_at'-'version'"
        + "-'created_by'-'updated_by' order by x.id),'[]'::jsonb)::text from " + table
        + " x where x.org_id=? and x." + owner + "=?", String.class, org, id);
  }

  private void append(StringBuilder canonical, String key, String value) {
    canonical.append(key).append(':').append(value).append('\n');
  }

  private ApiException stale() {
    return new ApiException(HttpStatus.CONFLICT, "LEGACY_PROJECTION_STALE", "原任务或审批投影已变化，请重新提交审批");
  }
}
