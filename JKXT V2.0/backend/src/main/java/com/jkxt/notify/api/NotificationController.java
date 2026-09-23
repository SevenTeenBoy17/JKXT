package com.jkxt.notify.api;

import com.jkxt.notify.api.NotificationDtos.MarkReadResult;
import com.jkxt.notify.api.NotificationDtos.NotificationView;
import com.jkxt.notify.api.NotificationDtos.UnreadCount;
import com.jkxt.notify.service.NotificationService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app notification center (R3). Read endpoints are open to any authenticated user; the POST
 * read/read-all endpoints are self-service (see SecurityConfig allowlist) so even read-only roles
 * can manage their own read state.
 */
@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/notifications")
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping
  public List<NotificationView> list(HttpServletRequest request) {
    SessionPrincipal principal = principal(request);
    return notificationService.list(principal.orgId(), principal.userId());
  }

  @GetMapping("/unread-count")
  public UnreadCount unreadCount(HttpServletRequest request) {
    SessionPrincipal principal = principal(request);
    return new UnreadCount(notificationService.unreadCount(principal.orgId(), principal.userId()));
  }

  @PostMapping("/{id}/read")
  public MarkReadResult read(@PathVariable UUID id, HttpServletRequest request) {
    SessionPrincipal principal = principal(request);
    int updated = notificationService.markRead(principal.orgId(), principal.userId(), id);
    long unread = notificationService.unreadCount(principal.orgId(), principal.userId());
    return new MarkReadResult(updated, unread);
  }

  @PostMapping("/read-all")
  public MarkReadResult readAll(HttpServletRequest request) {
    SessionPrincipal principal = principal(request);
    int updated = notificationService.markAllRead(principal.orgId(), principal.userId());
    return new MarkReadResult(updated, notificationService.unreadCount(principal.orgId(), principal.userId()));
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object principal = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (principal instanceof SessionPrincipal sessionPrincipal) {
      return sessionPrincipal;
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
