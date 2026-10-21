package com.jkxt.reporting.service;

import com.jkxt.common.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReportingService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final List<String> COLORS = List.of("#EF4444", "#F59E0B", "#06B6D4", "#6366F1", "#10B981", "#9333EA");

  private final JdbcTemplate jdbcTemplate;

  public ReportingService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Map<String, Object> historySummary() {
    List<Map<String, Object>> rows = teacherHistory();
    Map<String, Object> totals = totals(rows);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("rows", rows);
    response.put("totals", totals);
    response.put("byGrade", historyByGrade());
    response.put("monthlyTrend", monthlyTrend());
    return response;
  }

  public List<Map<String, Object>> teacherHistory() {
    UUID orgId = TenantContext.getRequired();
    UUID runId = activeRunId(orgId);
    double unitPrice = allowanceUnit(orgId);
    // Subjects are read via a correlated subquery (NOT a top-level join) so a multi-subject teacher
    // no longer fans out count/hours/allowance by their subject count (P1-2). Assignments are scoped
    // to the active run via the join condition (P1-3); teachers with no load still list with count 0.
    List<Map<String, Object>> raw = jdbcTemplate.query("""
        select
          t.id as teacher_id,
          t.teacher_no as code,
          t.name,
          coalesce(t.grade, '-') as grade,
          coalesce((
            select string_agg(distinct s.name, '/')
            from teacher_subject ts
            join subject s on s.id = ts.subject_id
            where ts.org_id = t.org_id and ts.teacher_id = t.id
          ), '-') as subjects,
          count(sa.id)::int as assignment_count,
          coalesce(sum(extract(epoch from (sa.end_time - sa.start_time)) / 3600.0), 0) as duration_hours
        from teacher t
        left join schedule_assignment sa
          on sa.org_id = t.org_id and sa.teacher_id = t.id and sa.schedule_run_id = ?
        where t.org_id = ?
        group by t.id, t.teacher_no, t.name, t.grade
        order by assignment_count desc, t.teacher_no asc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("teacherId", rs.getObject("teacher_id", UUID.class));
      row.put("code", rs.getString("code"));
      row.put("name", rs.getString("name"));
      row.put("grade", rs.getString("grade"));
      row.put("subjects", rs.getString("subjects"));
      row.put("count", rs.getInt("assignment_count"));
      double duration = round(rs.getDouble("duration_hours"), 1);
      row.put("duration", duration);
      row.put("hours", duration);
      row.put("avgDur", rs.getInt("assignment_count") == 0 ? 0.0 : round(duration / rs.getInt("assignment_count"), 1));
      double allowance = round(duration * unitPrice, 2);
      row.put("total", allowance);
      row.put("allowance", allowance);
      return row;
    }, runId, orgId);

    int denominator = raw.stream().mapToInt(row -> intValue(row.get("count"))).sum();
    // 迭代12 P2(性能): 一次性批量取所有教师在 active run 下的最近监考,替代逐教师 N+1 的 recentForTeacher。
    Map<UUID, List<Map<String, Object>>> recentByTeacher = recentByTeacher(orgId, runId, unitPrice);
    int ranking = 1;
    for (Map<String, Object> row : raw) {
      row.put("ranking", ranking++);
      row.put("contributionPct", denominator == 0 ? 0.0 : round(intValue(row.get("count")) * 100.0 / denominator, 1));
      row.put("recent", recentByTeacher.getOrDefault((UUID) row.get("teacherId"), List.of()));
    }
    return raw;
  }

  public List<Map<String, Object>> historyByGrade() {
    UUID orgId = TenantContext.getRequired();
    UUID runId = activeRunId(orgId);
    List<Map<String, Object>> rows = jdbcTemplate.query("""
        select coalesce(e.grade, t.grade, 'Unknown') as grade, count(sa.id)::int as count
        from schedule_assignment sa
        left join exam e on e.id = sa.exam_id
        left join teacher t on t.id = sa.teacher_id
        where sa.org_id = ? and sa.schedule_run_id = ?
        group by coalesce(e.grade, t.grade, 'Unknown')
        order by count desc, grade asc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("grade", rs.getString("grade"));
      row.put("count", rs.getInt("count"));
      row.put("color", COLORS.get(rowNum % COLORS.size()));
      return row;
    }, orgId, runId);
    // 迭代10 P2: 删除"按教师花名册年级分布"的回退。此前活动 run 无 assignment 时改为返回全体教师按年级
    // 计数，前端把它当"累计 N 场监考"渲染，与同页 run-scoped 的总计 0 直接矛盾(把花名册人头当监考场次)。
    // 无 assignment 时返回空列表(rows 此处已空)，让前端显示一致的"暂无数据"。
    return rows;
  }

  public Map<String, Object> monthlyTrend() {
    UUID orgId = TenantContext.getRequired();
    UUID runId = activeRunId(orgId);
    List<Map<String, Object>> rows = jdbcTemplate.query("""
        select date_trunc('month', sa.exam_date)::date as month_start, count(sa.id)::int as count
        from schedule_assignment sa
        where sa.org_id = ? and sa.schedule_run_id = ?
        group by date_trunc('month', sa.exam_date)::date
        order by month_start asc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      LocalDate month = rs.getDate("month_start").toLocalDate();
      row.put("label", MONTH.format(month));
      row.put("count", rs.getInt("count"));
      return row;
    }, orgId, runId);
    List<String> labels = rows.stream().map(row -> String.valueOf(row.get("label"))).toList();
    List<Integer> data = rows.stream().map(row -> intValue(row.get("count"))).toList();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("labels", labels);
    response.put("data", data);
    // With fewer than two months there is no real period-over-period trend; return null (not a
    // misleading flat 0%) so the UI can show "趋势数据不足" instead of implying zero change (P2-2).
    if (data.size() >= 2 && data.get(0) > 0) {
      response.put("trendPct", round((data.get(data.size() - 1) - data.get(0)) * 100.0 / data.get(0), 1));
    } else {
      response.put("trendPct", null);
    }
    return response;
  }

  public Map<String, Object> supervise(String dim, String metric) {
    String dimension = switch (dim == null ? "" : dim.toLowerCase(Locale.ROOT)) {
      case "subject", "grade" -> dim.toLowerCase(Locale.ROOT);
      default -> "teacher";
    };
    List<Map<String, Object>> rows = switch (dimension) {
      case "subject" -> subjectStats();
      case "grade" -> gradeStats();
      default -> teacherHistory().stream()
          .map(row -> superviseRow(row.get("name"), row.get("grade") + " · " + row.get("subjects"), row))
          .toList();
    };
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("dim", dimension);
    response.put("metric", metric == null || metric.isBlank() ? "count" : metric);
    response.put("rows", rows);
    response.put("totals", totals(rows));
    return response;
  }

  public Map<String, Object> timelineSchedule() {
    UUID orgId = TenantContext.getRequired();
    UUID runId = latestSucceededRun(orgId);
    LocalDate start = runId == null ? LocalDate.now() : minExamDateForRun(orgId, runId);
    if (start == null) {
      start = LocalDate.now();
    }
    LocalDate today = LocalDate.now();
    if (!today.isBefore(start) && !today.isAfter(start.plusDays(6))) {
      start = today.minusDays(2);
    }
    List<Map<String, Object>> days = days(start, today);
    List<Map<String, Object>> exams = runId == null ? List.of() : timelineExams(orgId, runId, start);
    List<Map<String, Object>> calendarDays = timelineCalendarDays(orgId);
    List<Integer> availableYears = calendarDays.stream()
        .map(row -> LocalDate.parse(String.valueOf(row.get("isoDate")), DATE).getYear())
        .distinct()
        .toList();
    if (availableYears.isEmpty()) {
      availableYears = List.of(today.getYear());
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("generatedAt", java.time.Instant.now().toString());
    response.put("currentDate", DATE.format(today));
    response.put("currentTime", TIME.format(LocalTime.now()));
    response.put("currentMonth", today.getMonthValue());
    response.put("weekStart", DATE.format(start));
    response.put("days", days);
    response.put("exams", exams);
    response.put("calendarDays", calendarDays);
    response.put("availableYears", availableYears);
    response.put("month", Map.of("year", today.getYear(), "month", today.getMonthValue()));
    return response;
  }

  private List<Map<String, Object>> subjectStats() {
    UUID orgId = TenantContext.getRequired();
    UUID runId = activeRunId(orgId);
    double unitPrice = allowanceUnit(orgId);
    return jdbcTemplate.query("""
        select
          coalesce(e.subject_name, 'Unknown') as name,
          coalesce(max(s.category), 'subject') as sub,
          count(sa.id)::int as assignment_count,
          coalesce(sum(extract(epoch from (sa.end_time - sa.start_time)) / 3600.0), 0) as duration_hours
        from schedule_assignment sa
        left join exam e on e.id = sa.exam_id
        left join subject s on s.id = e.subject_id
        where sa.org_id = ? and sa.schedule_run_id = ?
        group by coalesce(e.subject_name, 'Unknown')
        order by assignment_count desc, name asc
        """, (rs, rowNum) -> statsRow(rs.getString("name"), rs.getString("sub"), rs.getInt("assignment_count"), rs.getDouble("duration_hours"), unitPrice), orgId, runId);
  }

  private List<Map<String, Object>> gradeStats() {
    UUID orgId = TenantContext.getRequired();
    UUID runId = activeRunId(orgId);
    double unitPrice = allowanceUnit(orgId);
    return jdbcTemplate.query("""
        select
          coalesce(e.grade, 'Unknown') as name,
          count(distinct e.id)::int as exam_count,
          count(sa.id)::int as assignment_count,
          coalesce(sum(extract(epoch from (sa.end_time - sa.start_time)) / 3600.0), 0) as duration_hours
        from schedule_assignment sa
        left join exam e on e.id = sa.exam_id
        where sa.org_id = ? and sa.schedule_run_id = ?
        group by coalesce(e.grade, 'Unknown')
        order by assignment_count desc, name asc
        """, (rs, rowNum) -> statsRow(
        rs.getString("name"),
        rs.getInt("exam_count") + " exams",
        rs.getInt("assignment_count"),
        rs.getDouble("duration_hours"),
        unitPrice
    ), orgId, runId);
  }

  private Map<String, Object> superviseRow(Object name, Object sub, Map<String, Object> source) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    row.put("sub", sub);
    row.put("count", intValue(source.get("count")));
    row.put("hours", doubleValue(source.get("hours")));
    row.put("allowance", doubleValue(source.get("allowance")));
    return row;
  }

  private Map<String, Object> statsRow(String name, String sub, int count, double duration, double unitPrice) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("name", name);
    row.put("sub", sub);
    row.put("count", count);
    row.put("hours", round(duration, 1));
    row.put("allowance", round(duration * unitPrice, 2));
    return row;
  }

  private Map<UUID, List<Map<String, Object>>> recentByTeacher(UUID orgId, UUID runId, double unitPrice) {
    // 迭代12 P2(性能): 一次窗口查询取所有教师在 active run 下的最近 4 场监考,替代 teacherHistory 逐教师
    // 的 N+1 recentForTeacher。ROW_NUMBER 按 teacher 分区、date/time 倒序取前 4(与旧 per-teacher limit 4 同序),
    // Java 侧按 teacher_id 分组。runId 为空/zero-UUID(尚无成功 run)时无匹配→各教师空列表,语义与旧实现一致。
    // 迭代9 P0-A(C1) 的 active-run 过滤在此保留(where schedule_run_id = ?)。
    Map<UUID, List<Map<String, Object>>> byTeacher = new LinkedHashMap<>();
    if (runId == null) {
      return byTeacher;
    }
    List<Map<String, Object>> flat = jdbcTemplate.query("""
        select teacher_id, exam_date, start_time, subject_name, grade, room_code, duration_hours from (
          select sa.teacher_id, sa.exam_date, sa.start_time, e.subject_name, e.grade, c.code as room_code,
                 extract(epoch from (sa.end_time - sa.start_time)) / 3600.0 as duration_hours,
                 row_number() over (partition by sa.teacher_id order by sa.exam_date desc, sa.start_time desc) as rn
          from schedule_assignment sa
          left join exam e on e.id = sa.exam_id
          left join classroom c on c.id = sa.classroom_id
          where sa.org_id = ? and sa.schedule_run_id = ?
        ) ranked
        where rn <= 4
        order by teacher_id, exam_date desc, start_time desc
        """, (rs, rowNum) -> {
      LocalDate date = rs.getDate("exam_date").toLocalDate();
      LocalTime time = rs.getTime("start_time").toLocalTime();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("teacherId", rs.getObject("teacher_id", UUID.class));
      row.put("d", date.format(DateTimeFormatter.ofPattern("MM-dd")) + " " + TIME.format(time));
      row.put("l", rs.getString("grade") + " · " + rs.getString("subject_name") + " (" + rs.getString("room_code") + ")");
      row.put("amt", round(rs.getDouble("duration_hours") * unitPrice, 2));
      return row;
    }, orgId, runId);
    for (Map<String, Object> row : flat) {
      UUID teacherId = (UUID) row.remove("teacherId"); // 移除临时键,使 recent 项保持 {d,l,amt} 旧结构
      byTeacher.computeIfAbsent(teacherId, k -> new ArrayList<>()).add(row);
    }
    return byTeacher;
  }

  private Map<String, Object> totals(List<Map<String, Object>> rows) {
    double duration = rows.stream().mapToDouble(row -> doubleValue(row.get("duration")) + doubleValue(row.get("hours"))).sum();
    if (duration > 0 && rows.stream().anyMatch(row -> row.containsKey("duration") && row.containsKey("hours"))) {
      duration = rows.stream().mapToDouble(row -> doubleValue(row.get("duration"))).sum();
    }
    double allowance = rows.stream().mapToDouble(row -> doubleValue(row.get("allowance"))).sum();
    if (allowance == 0.0) {
      allowance = rows.stream().mapToDouble(row -> doubleValue(row.get("total"))).sum();
    }
    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("count", rows.stream().mapToInt(row -> intValue(row.get("count"))).sum());
    totals.put("duration", round(duration, 1));
    totals.put("hours", round(duration, 1));
    totals.put("allowance", round(allowance, 2));
    // "参与教师/参与项" counts only rows that actually have >=1 supervision in the active run, so a
    // zero-load teacher on the roster is not counted as a participant (P2-4).
    totals.put("teachers", (int) rows.stream().filter(row -> intValue(row.get("count")) > 0).count());
    totals.put("denominator", rows.stream().mapToInt(row -> intValue(row.get("count"))).sum());
    return totals;
  }

  // The active schedule for an org is its latest succeeded run. All reporting aggregates scope to it
  // (P1-3) so history/supervise match what /timeline/schedule shows, instead of pooling every
  // historical/superseded solver run. A zero UUID (no run yet) matches no assignment -> empty/zero.
  // PB-19 (round 9, REPORT-01): statistics (history, by-grade, trend, supervise) are official figures and
  // follow the published schedule only. A newer succeeded run of a task that is still a draft/scheduled
  // preview must not replace the published task. The operational timeline keeps latestSucceededRun.
  private UUID activeRunId(UUID orgId) {
    UUID runId = latestPublishedSucceededRun(orgId);
    return runId == null ? new UUID(0L, 0L) : runId;
  }

  private UUID latestPublishedSucceededRun(UUID orgId) {
    try {
      return jdbcTemplate.queryForObject("""
          select sr.id
          from schedule_run sr
          join exam_task et on et.id = sr.exam_task_id and et.org_id = sr.org_id
          where sr.org_id = ? and sr.status = 'succeeded'
            and (et.status = 'published' or et.published_at is not null)
          order by sr.finished_at desc nulls last, sr.created_at desc, sr.id desc
          limit 1
          """, UUID.class, orgId);
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    }
  }

  private UUID latestSucceededRun(UUID orgId) {
    try {
      return jdbcTemplate.queryForObject("""
          select sr.id
          from schedule_run sr
          join exam_task et on et.id = sr.exam_task_id and et.org_id = sr.org_id
          where sr.org_id = ? and sr.status = 'succeeded'
          order by sr.finished_at desc nulls last, sr.created_at desc, sr.id desc
          limit 1
          """, UUID.class, orgId);
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    }
  }

  private LocalDate minExamDateForRun(UUID orgId, UUID runId) {
    try {
      Date value = jdbcTemplate.queryForObject("""
          select min(e.exam_date)
          from schedule_run sr
          join exam_task et on et.id = sr.exam_task_id and et.org_id = sr.org_id
          join exam e on e.exam_task_id = et.id and e.org_id = sr.org_id
          where sr.id = ? and sr.org_id = ?
          """, Date.class, runId, orgId);
      return value == null ? null : value.toLocalDate();
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    }
  }

  private List<Map<String, Object>> timelineExams(UUID orgId, UUID runId, LocalDate start) {
    return jdbcTemplate.query("""
        select e.id, e.subject_name, e.grade, e.exam_date, e.start_time, e.end_time, et.name as task_name,
               (count(sa.id) filter (where c.id is not null and t.id is not null))::int as supervisor_count,
               coalesce(string_agg(distinct c.code, ', ') filter (where t.id is not null), '-') as room
        from schedule_run sr
        join exam_task et on et.id = sr.exam_task_id and et.org_id = sr.org_id
        join exam e on e.exam_task_id = et.id and e.org_id = sr.org_id
        left join schedule_assignment sa on sa.schedule_run_id = sr.id and sa.exam_id = e.id and sa.org_id = sr.org_id
        left join classroom c on c.id = sa.classroom_id and c.org_id = sr.org_id
        left join teacher t on t.id = sa.teacher_id and t.org_id = sr.org_id
        where sr.id = ? and sr.org_id = ? and e.exam_date between ? and ?
        group by e.id, e.subject_name, e.grade, e.exam_date, e.start_time, e.end_time, et.name
        order by e.exam_date asc, e.start_time asc
        """, (rs, rowNum) -> {
      LocalDate examDate = rs.getDate("exam_date").toLocalDate();
      LocalTime startTime = rs.getTime("start_time").toLocalTime();
      int day = (int) java.time.temporal.ChronoUnit.DAYS.between(start, examDate);
      int color = Math.floorMod(rs.getString("subject_name").hashCode(), 6) + 1;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", rs.getObject("id", UUID.class));
      row.put("row", rowNum % 5);
      row.put("start", Math.max(1, Math.min(7, day + 1)));
      row.put("span", 1);
      row.put("label", rs.getString("grade") + " · " + rs.getString("subject_name"));
      row.put("meta", rs.getString("task_name") + " · " + rs.getInt("supervisor_count") + " 名监考");
      row.put("color", color);
      row.put("supN", rs.getInt("supervisor_count"));
      row.put("time", TIME.format(startTime));
      row.put("room", rs.getString("room"));
      row.put("examDate", DATE.format(examDate));
      row.put("subjectName", rs.getString("subject_name"));
      row.put("grade", rs.getString("grade"));
      return row;
      // 迭代10 P2: 只取可见 7 天窗口 [start, start+6] 内的考试，避免此前把窗口外考试 clamp 到第 1/7 列、
      // 让跨越 7 天以上的排考在"近 7 天"卡片上堆到边列、显示错误日期。
    }, runId, orgId, java.sql.Date.valueOf(start), java.sql.Date.valueOf(start.plusDays(6)));
  }

  private List<Map<String, Object>> timelineCalendarDays(UUID orgId) {
    return jdbcTemplate.query("""
        with latest_runs as (
          select distinct on (sr.exam_task_id) sr.id, sr.exam_task_id, sr.org_id
          from schedule_run sr
          where sr.org_id = ? and sr.status = 'succeeded'
          order by sr.exam_task_id, sr.finished_at desc nulls last, sr.created_at desc, sr.id desc
        )
        select e.exam_date,
               count(distinct e.id)::int as exam_count,
               (count(sa.id) filter (where c.id is not null and t.id is not null))::int as supervision_count,
               (count(distinct c.id) filter (where t.id is not null))::int as room_count
        from latest_runs lr
        join exam_task et on et.id = lr.exam_task_id and et.org_id = lr.org_id
        join exam e on e.exam_task_id = lr.exam_task_id and e.org_id = lr.org_id
        left join schedule_assignment sa on sa.schedule_run_id = lr.id and sa.exam_id = e.id and sa.org_id = lr.org_id
        left join classroom c on c.id = sa.classroom_id and c.org_id = lr.org_id
        left join teacher t on t.id = sa.teacher_id and t.org_id = lr.org_id
        where e.exam_date is not null
        group by e.exam_date
        order by e.exam_date asc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("isoDate", DATE.format(rs.getDate("exam_date").toLocalDate()));
      row.put("examCount", rs.getInt("exam_count"));
      row.put("supervisionCount", rs.getInt("supervision_count"));
      row.put("roomCount", rs.getInt("room_count"));
      return row;
    }, orgId);
  }

  private List<Map<String, Object>> days(LocalDate start, LocalDate today) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      LocalDate day = start.plusDays(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("label", dayLabel(day.getDayOfWeek()));
      row.put("date", String.valueOf(day.getDayOfMonth()));
      row.put("dow", day.getDayOfWeek().toString().substring(0, 3));
      row.put("dim", day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY);
      row.put("today", day.equals(today));
      row.put("isoDate", DATE.format(day));
      rows.add(row);
    }
    return rows;
  }

  private String dayLabel(DayOfWeek dayOfWeek) {
    return switch (dayOfWeek) {
      case MONDAY -> "周一";
      case TUESDAY -> "周二";
      case WEDNESDAY -> "周三";
      case THURSDAY -> "周四";
      case FRIDAY -> "周五";
      case SATURDAY -> "周六";
      case SUNDAY -> "周日";
    };
  }

  private double allowanceUnit(UUID orgId) {
    try {
      // 迭代9：原 (settings->>'allowanceUnit')::numeric 在该设置为空串/非数字时抛 22P02(invalid input
      // syntax for numeric)→Spring 映射 DataIntegrityViolationException→整个报表(teacherHistory/subjectStats/
      // gradeStats)全 400 DATA_CONSTRAINT_VIOLATION。改为仅当值匹配数字模式才 cast，否则回退默认 120，
      // 使一条脏设置不再打挂所有津贴/统计报表。
      Number value = jdbcTemplate.queryForObject(
          "select coalesce(case when trim(coalesce(settings->>'allowanceUnit','')) ~ '^[0-9]+([.][0-9]+)?$' "
              + "then trim(settings->>'allowanceUnit')::numeric else null end, 120) "
              + "from organization where id = ?",
          Number.class,
          orgId
      );
      return value == null ? 120.0 : value.doubleValue();
    } catch (EmptyResultDataAccessException ignored) {
      return 120.0;
    }
  }

  private int intValue(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private double doubleValue(Object value) {
    return value instanceof Number number ? number.doubleValue() : 0.0;
  }

  private double round(double value, int scale) {
    return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }
}
