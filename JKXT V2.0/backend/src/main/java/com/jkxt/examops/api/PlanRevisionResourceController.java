package com.jkxt.examops.api;

import com.jkxt.examops.service.PlanRevisionResourceService;
import com.jkxt.examops.service.PlanRevisionResourceWriteService;
import com.jkxt.examops.api.RevisionResourceWriteDtos.BulkWriteRequest;
import com.jkxt.examops.api.RevisionResourceWriteDtos.BulkWriteResponse;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/plan-revisions/{revisionId}")
public class PlanRevisionResourceController {

  private final PlanRevisionResourceService service;
  private final PlanRevisionResourceWriteService writeService;

  public PlanRevisionResourceController(
      PlanRevisionResourceService service,
      PlanRevisionResourceWriteService writeService
  ) {
    this.service = service;
    this.writeService = writeService;
  }

  @GetMapping("/sessions")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<Map<String, Object>> sessions(@PathVariable UUID revisionId) {
    return service.resources(revisionId, "sessions");
  }

  @GetMapping("/rooms")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<Map<String, Object>> rooms(@PathVariable UUID revisionId) {
    return service.resources(revisionId, "rooms");
  }

  @GetMapping("/seats")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<Map<String, Object>> seats(@PathVariable UUID revisionId) {
    return service.resources(revisionId, "seats");
  }

  @GetMapping("/constraints")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<Map<String, Object>> constraints(@PathVariable UUID revisionId) {
    return service.resources(revisionId, "constraints");
  }

  @GetMapping("/duties")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<Map<String, Object>> duties(@PathVariable UUID revisionId) {
    return service.resources(revisionId, "duties");
  }

  /** Compatibility name used by the scheduling workbench. */
  @GetMapping("/assignments")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<Map<String, Object>> assignments(@PathVariable UUID revisionId) {
    return service.resources(revisionId, "duties");
  }

  @GetMapping("/deliveries")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<Map<String, Object>> deliveries(@PathVariable UUID revisionId) {
    return service.resources(revisionId, "deliveries");
  }

  @PostMapping("/sessions")
  @PreAuthorize("hasAuthority('exam:manage')")
  public BulkWriteResponse writeSessions(
      @PathVariable UUID revisionId,
      @Valid @RequestBody BulkWriteRequest body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      HttpServletRequest request
  ) {
    return writeService.write(revisionId, "sessions", body, idempotencyKey, principal(request).userId());
  }

  @PostMapping("/rooms")
  @PreAuthorize("hasAuthority('exam:manage')")
  public BulkWriteResponse writeRooms(
      @PathVariable UUID revisionId,
      @Valid @RequestBody BulkWriteRequest body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      HttpServletRequest request
  ) {
    return writeService.write(revisionId, "rooms", body, idempotencyKey, principal(request).userId());
  }

  @PostMapping("/seats")
  @PreAuthorize("hasAuthority('exam:manage')")
  public BulkWriteResponse writeSeats(
      @PathVariable UUID revisionId,
      @Valid @RequestBody BulkWriteRequest body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      HttpServletRequest request
  ) {
    return writeService.write(revisionId, "seats", body, idempotencyKey, principal(request).userId());
  }

  @PostMapping("/constraints")
  @PreAuthorize("hasAuthority('exam:manage')")
  public BulkWriteResponse writeConstraints(
      @PathVariable UUID revisionId,
      @Valid @RequestBody BulkWriteRequest body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      HttpServletRequest request
  ) {
    return writeService.write(revisionId, "constraints", body, idempotencyKey, principal(request).userId());
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(org.springframework.http.HttpStatus.UNAUTHORIZED,
        "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
