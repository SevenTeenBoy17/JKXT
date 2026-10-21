package com.jkxt.scheduling.api;

import com.jkxt.scheduling.api.LegacyPublicationDtos.PublicationView;
import com.jkxt.scheduling.api.LegacyPublicationDtos.SubmitPublicationRequest;
import com.jkxt.scheduling.service.LegacyPublicationService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/tasks/{taskId}/publication")
public class LegacyPublicationController {
  private final LegacyPublicationService service;

  public LegacyPublicationController(LegacyPublicationService service) { this.service = service; }

  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public PublicationView get(@PathVariable UUID taskId) { return service.get(taskId); }

  @PostMapping("/submit-approval")
  @PreAuthorize("hasAuthority('schedule:manage')")
  public PublicationView submit(@PathVariable UUID taskId, @Valid @RequestBody SubmitPublicationRequest body,
      HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (!(value instanceof SessionPrincipal principal)) {
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
    }
    return service.submit(taskId, body, principal.userId());
  }
}
