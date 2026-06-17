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
    double unitPrice = allowanceUnit(orgId);
    List<Map<String, Object>> raw = jdbcTemplate.query("""
        select
          t.id as teacher_id,
          t.teacher_no as code,
          t.name,
          coalesce(t.grade, '-') as grade,
          coalesce(string_agg(distinct s.name, '/'), '-') as subjects,
          count(sa.id)::int as assignment_count,
          coalesce(sum(extract(epoch from (sa.end_time - sa.start_time)) / 3600.0), 0) as duration_hours
        from teacher t
        left join teacher_subject ts on ts.org_id = t.org_id and ts.teacher_id = t.id
        left join subject s on s.id = ts.subject_id
        left join schedule_assignment sa on sa.org_id = t.org_id and sa.teacher_id = t.id
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
    }, orgId);

    int denominator = raw.stream().mapToInt(row -> intValue(row.get("count"))).sum();
    int ranking = 1;
    for (Map<String, Object> row : raw) {
      row.put("ranking", ranking++);
      row.put("contributionPct", denominator == 0 ? 0.0 : round(intValue(row.get("count")) * 100.0 / denominator, 1));
      row.put("recent", recentForTeacher(orgId, (UUID) row.get("teacherId"), unitPrice));
    }
    return raw;
  }

  public List<Map<String, Object>> historyByGrade() {
    UUID orgId = TenantContext.getRequired();
    List<Map<String, Object>> rows = jdbcTemplate.query("""
        select coalesce(e.grade, t.grade, 'Unknown') as grade, count(sa.id)::int as count
        from schedule_assignment sa
        left join exam e on e.id = sa.exam_id
        left join teacher t on t.id = sa.teacher_id
        where sa.org_id = ?
        group by coalesce(e.grade, t.grade, 'Unknown')
        order by count desc, grade asc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("grade", rs.getString("grade"));
      row.put("count", rs.getInt("count"));
      row.put("color", COLORS.get(rowNum % COLORS.size()));
      return row;
    }, orgId);
    if (!rows.isEmpty()) {
      return rows;
    }
    return jdbcTemplate.query("""
        select coalesce(grade, 'Unknown') as grade, count(*)::int as count
        from teacher
        where org_id = ?
        group by coalesce(grade, 'Unknown')
        order by count desc, grade asc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("grade", rs.getString("grade"));
      row.put("count", rs.getInt("count"));
      row.put("color", COLORS.get(rowNum % COLORS.size()));
      return row;
    }, orgId);
  }

  public Map<String, Object> monthlyTrend() {
    UUID orgId = TenantContext.getRequired();
    List<Map<String, Object>> rows = jdbcTemplate.query("""
        select date_trunc('month', sa.exam_date)::date as month_start, count(sa.id)::int as count
        from schedule_assignment sa
        where sa.org_id = ?
        group by date_trunc('month', sa.exam_date)::date
        order by month_start asc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      LocalDate month = rs.getDate("month_start").toLocalDate();
      row.put("label", MONTH.format(month));
      row.put("count", rs.getInt("count"));
      return row;
    }, orgId);
    List<String> labels = rows.stream().map(row -> String.valueOf(row.get("label"))).toList();
    List<Integer> data = rows.stream().map(row -> intValue(row.get("count"))).toList();
    double trendPct = 0.0;
    if (data.size() >= 2 && data.get(0) > 0) {
      trendPct = round((data.get(data.size() - 1) - data.get(0)) * 100.0 / data.get(0), 1);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("labels", labels);
    response.put("data", data);
    response.put("trendPct", trendPct);
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
    LocalDate start = runId == null ? LocalDate.now() : minExamDateForRun(runId);
    if (start == null) {
      start = LocalDate.now();
    }
    LocalDate today = LocalDate.now();
    if (!today.isBefore(start) && !today.isAfter(start.plusDays(6))) {
      start = today.minusDays(2);
    }
    List<Map<String, Object>> days = days(start, today);
    List<Map<String, Object>> exams = runId == null ? List.of() : timelineExams(runId, start);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("generatedAt", java.time.Instant.now().toString());
    response.put("currentDate", DATE.format(today));
    response.put("currentTime", TIME.format(LocalTime.now()));
    response.put("currentMonth", today.getMonthValue());
    response.put("weekStart", DATE.format(start));
    response.put("days", days);
    response.put("exams", exams);
    response.put("month", Map.of("year", today.getYear(), "month", today.getMonthValue()));
    return response;
  }

  private List<Map<String, Object>> subjectStats() {
    UUID orgId = TenantContext.getRequired();
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
        where sa.org_id = ?
        group by coalesce(e.subject_name, 'Unknown')
        order by assignment_count desc, name asc
        """, (rs, rowNum) -> statsRow(rs.getString("name"), rs.getString("sub"), rs.getInt("assignment_count"), rs.getDouble("duration_hours"), unitPrice), orgId);
  }

  private List<Map<String, Object>> gradeStats() {
    UUID orgId = TenantContext.getRequired();
    double unitPrice = allowanceUnit(orgId);
    return jdbcTemplate.query("""
        select
          coalesce(e.grade, 'Unknown') as name,
          count(distinct e.id)::int as exam_count,
          count(sa.id)::int as assignment_count,
          coalesce(sum(extract(epoch from (sa.end_time - sa.start_time)) / 3600.0), 0) as duration_hours
        from schedule_assignment sa
        left join exam e on e.id = sa.exam_id
        where sa.org_id = ?
        group by coalesce(e.grade, 'Unknown')
        order by assignment_count desc, name asc
        """, (rs, rowNum) -> statsRow(
        rs.getString("name"),
        rs.getInt("exam_count") + " exams",
        rs.getInt("assignment_count"),
        rs.getDouble("duration_hours"),
        unitPrice
    ), orgId);
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

  private List<Map<String, Object>> recentForTeacher(UUID orgId, UUID teacherId, double unitPrice) {
    return jdbcTemplate.query("""
        select sa.exam_date, sa.start_time, e.subject_name, e.grade, c.code as room_code,
               extract(epoch from (sa.end_time - sa.start_time)) / 3600.0 as duration_hours
        from schedule_assignment sa
        left join exam e on e.id = sa.exam_id
        left join classroom c on c.id = sa.classroom_id
        where sa.org_id = ? and sa.teacher_id = ?
        order by sa.exam_date desc, sa.start_time desc
        limit 4
        """, (rs, rowNum) -> {
      LocalDate date = rs.getDate("exam_date").toLocalDate();
      LocalTime time = rs.getTime("start_time").toLocalTime();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("d", date.format(DateTimeFormatter.ofPattern("MM-dd")) + " " + TIME.format(time));
      row.put("l", rs.getString("grade") + " · " + rs.getString("subject_name") + " (" + rs.getString("room_code") + ")");
      row.put("amt", round(rs.getDouble("duration_hours") * unitPrice, 2));
      return row;
    }, orgId, teacherId);
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
    totals.put("teachers", rows.size());
    totals.put("denominator", rows.stream().mapToInt(row -> intValue(row.get("count"))).sum());
    return totals;
  }

  private UUID latestSucceededRun(UUID orgId) {
    try {
      return jdbcTemplate.queryForObject("""
          select sr.id
          from schedule_run sr
          join exam_task et on et.id = sr.exam_task_id
          where sr.org_id = ? and sr.status = 'succeeded'
          order by sr.created_at desc
          limit 1
          """, UUID.class, orgId);
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    }
  }

  private LocalDate minExamDateForRun(UUID runId) {
    try {
      Date value = jdbcTemplate.queryForObject("""
          select min(e.exam_date)
          from exam e
          join schedule_run sr on sr.exam_task_id = e.exam_task_id
          where sr.id = ?
          """, Date.class, runId);
      return value == null ? null : value.toLocalDate();
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    }
  }

  private List<Map<String, Object>> timelineExams(UUID runId, LocalDate start) {
    return jdbcTemplate.query("""
        select e.id, e.subject_name, e.grade, e.exam_date, e.start_time, e.end_time, et.name as task_name,
               count(sa.id)::int as supervisor_count,
               coalesce(string_agg(distinct c.code, ', '), '-') as room
        from schedule_run sr
        join exam_task et on et.id = sr.exam_task_id
        join exam e on e.exam_task_id = et.id
        left join schedule_assignment sa on sa.schedule_run_id = sr.id and sa.exam_id = e.id
        left join classroom c on c.id = sa.classroom_id
        where sr.id = ?
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
      row.put("meta", rs.getString("task_name") + " · " + rs.getInt("supervisor_count") + " supervisors");
      row.put("color", color);
      row.put("supN", rs.getInt("supervisor_count"));
      row.put("time", TIME.format(startTime));
      row.put("room", rs.getString("room"));
      row.put("examDate", DATE.format(examDate));
      row.put("subjectName", rs.getString("subject_name"));
      row.put("grade", rs.getString("grade"));
      return row;
    }, runId);
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
      case MONDAY -> "Mon";
      case TUESDAY -> "Tue";
      case WEDNESDAY -> "Wed";
      case THURSDAY -> "Thu";
      case FRIDAY -> "Fri";
      case SATURDAY -> "Sat";
      case SUNDAY -> "Sun";
    };
  }

  private double allowanceUnit(UUID orgId) {
    try {
      Number value = jdbcTemplate.queryForObject(
          "select coalesce((settings->>'allowanceUnit')::numeric, 120) from organization where id = ?",
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
