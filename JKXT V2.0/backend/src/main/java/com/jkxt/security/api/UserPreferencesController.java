package com.jkxt.security.api;

import com.jkxt.security.api.UserPreferencesDtos.PatchUserPreferencesRequest;
import com.jkxt.security.api.UserPreferencesDtos.UserPreferencesResponse;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import com.jkxt.security.service.UserPreferencesService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/auth/me/preferences")
public class UserPreferencesController {

  private final UserPreferencesService service;

  public UserPreferencesController(UserPreferencesService service) {
    this.service = service;
  }

  @GetMapping
  public UserPreferencesResponse get(HttpServletRequest request) {
    SessionPrincipal principal = principal(request);
    return service.get(principal.orgId(), principal.userId());
  }

  @PatchMapping
  public UserPreferencesResponse patch(
      @Valid @RequestBody PatchUserPreferencesRequest body,
      HttpServletRequest request
  ) {
    SessionPrincipal principal = principal(request);
    return service.patch(principal.orgId(), principal.userId(), body);
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) {
      return principal;
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
