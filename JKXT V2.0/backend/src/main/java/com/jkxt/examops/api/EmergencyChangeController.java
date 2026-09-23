package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.jkxt.examops.api.EmergencyChangeDtos.EmergencyChangeView;
import com.jkxt.examops.service.ExamPlanEmergencyService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/exam-plans/{planId}/revisions/{revisionId}/emergency-changes")
public class EmergencyChangeController {

  private final ExamPlanEmergencyService service;

  public EmergencyChangeController(ExamPlanEmergencyService service) {
    this.service = service;
  }

  @PostMapping
  @PreAuthorize("hasAuthority('schedule:manage')")
  public ResponseEntity<EmergencyChangeView> create(
      @PathVariable UUID planId,
      @PathVariable UUID revisionId,
      @RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      HttpServletRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(service.create(planId, revisionId, body, idempotencyKey, principal(request).userId()));
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
