package com.jkxt.examops.api;

import com.jkxt.examops.api.ExamPlanAuditDtos.AuditEventPage;
import com.jkxt.examops.service.ExamPlanAuditQueryService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/exam-plans/{planId}/revisions/{revisionId}/audit-events")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class ExamPlanAuditController {
  private final ExamPlanAuditQueryService service;

  public ExamPlanAuditController(ExamPlanAuditQueryService service) { this.service = service; }

  @GetMapping
  public AuditEventPage events(@PathVariable UUID planId, @PathVariable UUID revisionId,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "cursor", required = false) String cursor) {
    return service.events(planId, revisionId, limit, cursor);
  }
}
