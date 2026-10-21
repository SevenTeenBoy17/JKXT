package com.jkxt.examops.api;

import com.jkxt.examops.api.DutyAssignmentSelfServiceDtos.DutyActionResponse;
import com.jkxt.examops.api.DutyAssignmentSelfServiceDtos.RejectDutyRequest;
import com.jkxt.examops.service.DutyAssignmentSelfService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/auth/me/duty-assignments")
public class DutyAssignmentSelfServiceController {

  private final DutyAssignmentSelfService service;

  public DutyAssignmentSelfServiceController(DutyAssignmentSelfService service) {
    this.service = service;
  }

  @PostMapping("/{assignmentId}/accept")
  @PreAuthorize("hasAuthority('duty:self')")
  public DutyActionResponse accept(
      @PathVariable UUID assignmentId,
      @RequestHeader(name = "X-Duty-Engagement", required = false) Long engagementNo,
      HttpServletRequest request
  ) {
    return service.accept(principal(request).userId(), assignmentId, engagementNo);
  }

  @PostMapping("/{assignmentId}/reject")
  @PreAuthorize("hasAuthority('duty:self')")
  public DutyActionResponse reject(
      @PathVariable UUID assignmentId,
      @Valid @RequestBody RejectDutyRequest body,
      @RequestHeader(name = "X-Duty-Engagement", required = false) Long engagementNo,
      HttpServletRequest request
  ) {
    return service.reject(principal(request).userId(), assignmentId, body.reason(), engagementNo);
  }

  @PostMapping("/{assignmentId}/check-in")
  @PreAuthorize("hasAuthority('duty:self')")
  public DutyActionResponse checkIn(
      @PathVariable UUID assignmentId,
      @RequestHeader(name = "X-Duty-Engagement", required = false) Long engagementNo,
      HttpServletRequest request
  ) {
    return service.checkIn(principal(request).userId(), assignmentId, engagementNo);
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
