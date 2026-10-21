package com.jkxt.examops.api;

import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.ExamPlanDtos.CreateExamPlanRequest;
import com.jkxt.examops.api.ExamPlanDtos.CreateRevisionRequest;
import com.jkxt.examops.api.ExamPlanDtos.DecisionRequest;
import com.jkxt.examops.api.ExamPlanDtos.ExamPlanView;
import com.jkxt.examops.api.ExamPlanDtos.PlanRevisionView;
import com.jkxt.examops.api.ExamPlanDtos.PlanValidationResult;
import com.jkxt.examops.service.ExamPlanService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("${jkxt.api-base:/api/v1}/exam-plans")
public class ExamPlanController {

  private final ExamPlanService service;

  public ExamPlanController(ExamPlanService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<ExamPlanView> list() {
    return service.list();
  }

  @PostMapping
  @PreAuthorize("hasAuthority('exam:manage')")
  public ResponseEntity<ExamPlanView> create(
      @Valid @RequestBody CreateExamPlanRequest body,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.create(body, principal(request).userId(), idempotencyKey));
  }

  @GetMapping("/{planId}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public ExamPlanView get(@PathVariable UUID planId) {
    return service.get(planId);
  }

  @GetMapping("/{planId}/revisions")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<PlanRevisionView> revisions(@PathVariable UUID planId) {
    return service.revisions(planId);
  }

  @PostMapping("/{planId}/revisions")
  @PreAuthorize("hasAuthority('exam:manage')")
  public ResponseEntity<PlanRevisionView> createRevision(
      @PathVariable UUID planId,
      @Valid @RequestBody CreateRevisionRequest body,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createRevision(planId, body, principal(request).userId(), idempotencyKey));
  }

  @GetMapping("/{planId}/revisions/{revisionId}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public PlanRevisionView revision(@PathVariable UUID planId, @PathVariable UUID revisionId) {
    return service.revision(planId, revisionId);
  }

  @PostMapping("/{planId}/revisions/{revisionId}/validate")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public ResponseEntity<PlanValidationResult> validate(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    PlanValidationResult result = service.validate(
        planId, revisionId, principal(request).userId(), cursor(ifMatch));
    return ResponseEntity.status(result.valid() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(result);
  }

  @PostMapping("/{planId}/revisions/{revisionId}/submit-approval")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public PlanRevisionView submitApproval(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    return service.submitApproval(planId, revisionId, principal(request).userId(), cursor(ifMatch));
  }

  @PostMapping("/{planId}/revisions/{revisionId}/approve")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public PlanRevisionView approve(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestBody(required = false) DecisionRequest body,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    return service.approve(planId, revisionId, principal(request).userId(), body == null ? null : body.reason(), cursor(ifMatch));
  }

  @PostMapping("/{planId}/revisions/{revisionId}/reject")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public PlanRevisionView reject(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestBody(required = false) DecisionRequest body,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    return service.reject(planId, revisionId, principal(request).userId(), body == null ? null : body.reason(), cursor(ifMatch));
  }

  @PostMapping("/{planId}/revisions/{revisionId}/publish")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public PlanRevisionView publish(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    return service.publish(planId, revisionId, principal(request).userId(), cursor(ifMatch));
  }

  private Long cursor(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().replace("W/", "").replace("\"", "");
    try {
      return Long.parseLong(normalized);
    } catch (NumberFormatException ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IF_MATCH_INVALID", "If-Match must contain a numeric revision cursor");
    }
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
