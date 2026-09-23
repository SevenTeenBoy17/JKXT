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

  // P18 批次6.2：砍掉 /history/* 与 /stats/timeline/* 别名——每条别名都是一份要维护的鉴权面与
  // 文档面，而全部消费方（前端 src/、e2e 脚本、源码快照）实际只用 /stats/history* 与
  // /timeline/schedule 这套规范路径（已逐一核实为零引用后才删）。

  @GetMapping("/stats/history")
  public Map<String, Object> historySummary() {
    return reportingService.historySummary();
  }

  @GetMapping("/stats/history/teachers")
  public List<Map<String, Object>> historyTeachers() {
    return reportingService.teacherHistory();
  }

  @GetMapping("/stats/history/by-grade")
  public List<Map<String, Object>> historyByGrade() {
    return reportingService.historyByGrade();
  }

  @GetMapping("/stats/history/monthly-trend")
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

  @GetMapping("/timeline/schedule")
  public Map<String, Object> timelineSchedule() {
    return reportingService.timelineSchedule();
  }
}
