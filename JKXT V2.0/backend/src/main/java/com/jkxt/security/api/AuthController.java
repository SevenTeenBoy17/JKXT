package com.jkxt.security.api;

import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.security.api.AuthDtos.LoginRequest;
import com.jkxt.security.api.AuthDtos.LoginResponse;
import com.jkxt.security.api.AuthDtos.LogoutResponse;
import com.jkxt.security.api.AuthDtos.MeResponse;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.AuthService;
import com.jkxt.security.auth.CaptchaService;
import com.jkxt.security.auth.SessionPrincipal;
import com.jkxt.security.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/auth")
public class AuthController {

  private final CaptchaService captchaService;
  private final AuthService authService;
  private final SessionService sessionService;

  public AuthController(
      CaptchaService captchaService,
      AuthService authService,
      SessionService sessionService
  ) {
    this.captchaService = captchaService;
    this.authService = authService;
    this.sessionService = sessionService;
  }

  @PostMapping("/captcha")
  public CaptchaResponse captcha() {
    return captchaService.create();
  }

  @PostMapping("/login")
  public LoginResponse login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse
  ) {
    AuthService.LoginResult result = authService.login(request, clientIp(servletRequest));
    servletResponse.addHeader(HttpHeaders.SET_COOKIE, result.cookie().toString());
    return result.response();
  }

  @GetMapping("/me")
  public MeResponse me(HttpServletRequest request) {
    return authService.me(principal(request));
  }

  @PostMapping("/me/onboarded")
  public MeResponse markOnboarded(HttpServletRequest request) {
    return authService.markOnboarded(principal(request));
  }

  @PostMapping("/logout")
  public LogoutResponse logout(HttpServletRequest request, HttpServletResponse response) {
    SessionPrincipal principal = principal(request);
    authService.logout(principal);
    response.addHeader(HttpHeaders.SET_COOKIE, sessionService.clearCookie().toString());
    return new LogoutResponse(true);
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object principal = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (principal instanceof SessionPrincipal sessionPrincipal) {
      return sessionPrincipal;
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
