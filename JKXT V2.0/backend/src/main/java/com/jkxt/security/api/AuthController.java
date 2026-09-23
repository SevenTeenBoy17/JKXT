package com.jkxt.security.api;

import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.security.api.AuthDtos.ChangePasswordRequest;
import com.jkxt.security.api.AuthDtos.LoginRequest;
import com.jkxt.security.api.AuthDtos.LoginResponse;
import com.jkxt.security.api.AuthDtos.LogoutResponse;
import com.jkxt.security.api.AuthDtos.MeResponse;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.AuthService;
import com.jkxt.security.auth.CaptchaService;
import com.jkxt.security.auth.ClientIpResolver;
import com.jkxt.security.auth.LoginIpThrottle;
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
  private final ClientIpResolver clientIpResolver;
  private final LoginIpThrottle loginIpThrottle;

  public AuthController(
      CaptchaService captchaService,
      AuthService authService,
      SessionService sessionService,
      ClientIpResolver clientIpResolver,
      LoginIpThrottle loginIpThrottle
  ) {
    this.captchaService = captchaService;
    this.authService = authService;
    this.sessionService = sessionService;
    this.clientIpResolver = clientIpResolver;
    this.loginIpThrottle = loginIpThrottle;
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
    // A3② 按 IP 节流：先挡住已超限的 IP（429，不再进入验证码/查库），再执行登录；任何登录失败都计一次数。
    // 只在此层包裹，AuthService.login 那段带 noRollbackFor 的事务性账号锁定逻辑完全不动。
    String ip = clientIpResolver.resolve(servletRequest);
    loginIpThrottle.checkAllowed(ip);
    try {
      AuthService.LoginResult result = authService.login(request, ip);
      servletResponse.addHeader(HttpHeaders.SET_COOKIE, result.cookie().toString());
      return result.response();
    } catch (AuthException failure) {
      loginIpThrottle.recordFailure(ip);
      throw failure;
    }
  }

  @GetMapping("/me")
  public MeResponse me(HttpServletRequest request) {
    return authService.me(principal(request));
  }

  @PostMapping("/me/onboarded")
  public MeResponse markOnboarded(HttpServletRequest request) {
    return authService.markOnboarded(principal(request));
  }

  @PostMapping("/me/change-password")
  public MeResponse changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
    return authService.changePassword(principal(request), body);
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
}
