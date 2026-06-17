package com.jkxt.reporting.api;

import com.jkxt.reporting.service.ReportingService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReportingController {

  private final ReportingService reportingService;

  public ReportingController(ReportingService reportingService) {
    this.reportingService = reportingService;
  }

  @GetMapping({"/stats/history", "/history/summary"})
  public Map<String, Object> historySummary() {
    return reportingService.historySummary();
  }

  @GetMapping({"/stats/history/teachers", "/history/teachers"})
  public List<Map<String, Object>> historyTeachers() {
    return reportingService.teacherHistory();
  }

  @GetMapping({"/stats/history/by-grade", "/history/by-grade"})
  public List<Map<String, Object>> historyByGrade() {
    return reportingService.historyByGrade();
  }

  @GetMapping({"/stats/history/monthly-trend", "/history/monthly-trend"})
  public Map<String, Object> monthlyTrend() {
    return reportingService.monthlyTrend();
  }

  @GetMapping("/stats/supervise")
  public Map<String, Object> supervise(
      @RequestParam(defaultValue = "teacher") String dim,
      @RequestParam(defaultValue = "count") String metric
  ) {
    return reportingService.supervise(dim, metric);
  }

  @GetMapping({"/timeline/schedule", "/stats/timeline/schedule"})
  public Map<String, Object> timelineSchedule() {
    return reportingService.timelineSchedule();
  }
}
