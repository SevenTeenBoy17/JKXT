package com.jkxt.examops.api;

import com.jkxt.examops.api.NotificationDeliveryDtos.DeliveryView;
import com.jkxt.examops.api.NotificationDeliveryDtos.DispatchRequest;
import com.jkxt.examops.api.NotificationDeliveryDtos.DispatchResponse;
import com.jkxt.examops.api.NotificationDeliveryDtos.ReadReceiptRequest;
import com.jkxt.examops.service.NotificationDeliveryService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}")
public class NotificationDeliveryController {

  private final NotificationDeliveryService service;

  public NotificationDeliveryController(NotificationDeliveryService service) {
    this.service = service;
  }

  @GetMapping("/operations/notification-deliveries")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public List<DeliveryView> list(
      @RequestParam(required = false) UUID revisionId,
      @RequestParam(required = false) String status
  ) {
    return service.list(revisionId, status);
  }

  @PostMapping("/operations/notification-deliveries/dispatch")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public DispatchResponse dispatch(
      @Valid @RequestBody(required = false) DispatchRequest body,
      HttpServletRequest request
  ) {
    return service.dispatch(body, principal(request).userId());
  }

  @GetMapping("/auth/me/notification-deliveries")
  @PreAuthorize("hasAuthority('duty:self')")
  public List<DeliveryView> self(HttpServletRequest request) {
    return service.self(principal(request).userId());
  }

  @PostMapping("/auth/me/notification-deliveries/{deliveryId}/read")
  @PreAuthorize("hasAuthority('duty:self')")
  public DeliveryView markRead(
      @PathVariable UUID deliveryId,
      @RequestBody(required = false) ReadReceiptRequest body,
      HttpServletRequest request
  ) {
    return service.markRead(principal(request).userId(), deliveryId, body);
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
