package com.jkxt.examops.api;

import com.jkxt.examops.api.LeaveRequestDtos.CreateLeaveRequest;
import com.jkxt.examops.api.LeaveRequestDtos.DecideLeaveRequest;
import com.jkxt.examops.api.LeaveRequestDtos.LeaveRequestView;
import com.jkxt.examops.service.LeaveRequestService;
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
public class LeaveRequestController {

  private final LeaveRequestService service;

  public LeaveRequestController(LeaveRequestService service) {
    this.service = service;
  }

  @PostMapping("/leave-requests")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public ResponseEntity<LeaveRequestView> create(
      @Valid @RequestBody CreateLeaveRequest body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      HttpServletRequest request
  ) {
    LeaveRequestView result = service.create(body, principal(request).userId(), idempotencyKey);
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
  }

  @GetMapping("/leave-requests")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public List<LeaveRequestView> list(@RequestParam(required = false) String status) {
    return service.list(status);
  }

  @PostMapping("/leave-requests/{leaveRequestId}/decision")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public LeaveRequestView decide(
      @PathVariable UUID leaveRequestId,
      @Valid @RequestBody DecideLeaveRequest body,
      HttpServletRequest request
  ) {
    return service.decide(leaveRequestId, body, principal(request).userId());
  }

  @GetMapping("/auth/me/leave-requests")
  @PreAuthorize("hasAuthority('duty:self')")
  public List<LeaveRequestView> self(HttpServletRequest request) {
    return service.self(principal(request).userId());
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
