package com.jkxt.examops.api;

import com.jkxt.examops.service.DutyAssignmentSelfService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Teacher self-service projection. It never accepts a caller-supplied teacher or revision id. */
@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/auth/me")
public class MyDutyAssignmentController {

  private final DutyAssignmentSelfService service;

  public MyDutyAssignmentController(DutyAssignmentSelfService service) {
    this.service = service;
  }

  @GetMapping("/duty-assignments")
  @PreAuthorize("hasAuthority('duty:self')")
  public List<Map<String, Object>> assignments(HttpServletRequest request) {
    return service.assignments(principal(request).userId());
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
