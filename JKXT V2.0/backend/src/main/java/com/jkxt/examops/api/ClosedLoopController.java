package com.jkxt.examops.api;

import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.ClosedLoopDtos.ChecklistItemView;
import com.jkxt.examops.api.ClosedLoopDtos.CompleteChecklistItemRequest;
import com.jkxt.examops.api.ClosedLoopDtos.CreateChecklistItemRequest;
import com.jkxt.examops.api.ClosedLoopDtos.CreateIncidentRequest;
import com.jkxt.examops.api.ClosedLoopDtos.IncidentView;
import com.jkxt.examops.api.ClosedLoopDtos.LifecycleTransitionView;
import com.jkxt.examops.api.ClosedLoopDtos.ResolveIncidentRequest;
import com.jkxt.examops.api.ClosedLoopDtos.ResolveSubstitutionRequest;
import com.jkxt.examops.api.ClosedLoopDtos.SubstitutionCaseView;
import com.jkxt.examops.service.ClosedLoopOperationsService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}")
@PreAuthorize("hasAuthority('schedule:manage')")
public class ClosedLoopController {

  private final ClosedLoopOperationsService service;

  public ClosedLoopController(ClosedLoopOperationsService service) {
    this.service = service;
  }

  @GetMapping("/substitution-cases")
  public List<SubstitutionCaseView> substitutions(@RequestParam(required = false) String status) {
    return service.substitutions(status);
  }

  @PostMapping("/substitution-cases/{caseId}/resolve")
  public SubstitutionCaseView resolveSubstitution(
      @PathVariable UUID caseId,
      @Valid @RequestBody ResolveSubstitutionRequest body,
      HttpServletRequest request
  ) {
    return service.resolveSubstitution(caseId, body, principal(request).userId());
  }

  @PostMapping("/exam-plans/{planId}/revisions/{revisionId}/execute")
  public LifecycleTransitionView execute(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    return service.transition(planId, revisionId, "EXECUTING", principal(request).userId(), cursor(ifMatch));
  }

  @PostMapping("/exam-plans/{planId}/revisions/{revisionId}/close")
  public LifecycleTransitionView close(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    return service.transition(planId, revisionId, "CLOSED", principal(request).userId(), cursor(ifMatch));
  }

  @PostMapping("/exam-plans/{planId}/revisions/{revisionId}/archive")
  public LifecycleTransitionView archive(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestHeader(name = "If-Match", required = false) String ifMatch,
      HttpServletRequest request
  ) {
    return service.transition(planId, revisionId, "ARCHIVED", principal(request).userId(), cursor(ifMatch));
  }

  @PostMapping("/plan-revisions/{revisionId}/incidents")
  public ResponseEntity<IncidentView> createIncident(
      @PathVariable UUID revisionId,
      @Valid @RequestBody CreateIncidentRequest body,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createIncident(revisionId, body, principal(request).userId()));
  }

  @GetMapping("/plan-revisions/{revisionId}/incidents")
  public List<IncidentView> incidents(@PathVariable UUID revisionId) {
    return service.incidents(revisionId);
  }

  @PostMapping("/plan-revisions/{revisionId}/incidents/{incidentId}/resolve")
  public IncidentView resolveIncident(
      @PathVariable UUID revisionId,
      @PathVariable UUID incidentId,
      @Valid @RequestBody ResolveIncidentRequest body,
      HttpServletRequest request
  ) {
    return service.resolveIncident(revisionId, incidentId, body, principal(request).userId());
  }

  @PostMapping("/plan-revisions/{revisionId}/checklists")
  public ResponseEntity<ChecklistItemView> createChecklist(
      @PathVariable UUID revisionId,
      @Valid @RequestBody CreateChecklistItemRequest body,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.createChecklist(revisionId, body, principal(request).userId()));
  }

  @GetMapping("/plan-revisions/{revisionId}/checklists")
  public List<ChecklistItemView> checklists(@PathVariable UUID revisionId) {
    return service.checklists(revisionId);
  }

  @PostMapping("/plan-revisions/{revisionId}/checklists/{itemId}/complete")
  public ChecklistItemView completeChecklist(
      @PathVariable UUID revisionId,
      @PathVariable UUID itemId,
      @Valid @RequestBody CompleteChecklistItemRequest body,
      HttpServletRequest request
  ) {
    return service.completeChecklist(revisionId, itemId, body, principal(request).userId());
  }

  private long cursor(String value) {
    if (value == null || value.isBlank()) {
      throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED",
          "If-Match with the current revision cursor is required");
    }
    String normalized = value.trim().replace("W/", "").replace("\"", "");
    try {
      return Long.parseLong(normalized);
    } catch (NumberFormatException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IF_MATCH_INVALID",
          "If-Match must contain a numeric revision cursor");
    }
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
