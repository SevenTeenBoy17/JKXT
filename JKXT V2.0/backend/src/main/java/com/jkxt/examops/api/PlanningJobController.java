package com.jkxt.examops.api;

import com.jkxt.examops.api.PlanningJobDtos.PlanningJobRequest;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobView;
import com.jkxt.examops.service.ExamPlanPlanningService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}")
public class PlanningJobController {

  private final ExamPlanPlanningService service;

  public PlanningJobController(ExamPlanPlanningService service) {
    this.service = service;
  }

  @PostMapping("/exam-plans/{planId}/revisions/{revisionId}/planning-jobs")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public ResponseEntity<PlanningJobView> start(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestBody(required = false) PlanningJobRequest body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(service.start(planId, revisionId, body, idempotencyKey, principal(request).userId()));
  }

  @GetMapping("/planning-jobs/{jobId}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public PlanningJobView get(@PathVariable UUID jobId) {
    return service.get(jobId);
  }

  @PostMapping("/planning-jobs/{jobId}/cancel")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public PlanningJobView cancel(@PathVariable UUID jobId, HttpServletRequest request) {
    return service.cancel(jobId, principal(request).userId());
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
