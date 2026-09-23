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
import com.jkxt.security.auth.SessionService;
import com.jkxt.security.auth.SessionEnvelope;
import com.jkxt.security.auth.PortalPurpose;
import com.jkxt.security.auth.SessionAuthenticationFilter;
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
  private final SessionAuthenticationFilter sessionFilter;

  public AuthController(
      CaptchaService captchaService,
      AuthService authService,
      SessionService sessionService,
      ClientIpResolver clientIpResolver,
      LoginIpThrottle loginIpThrottle,
      SessionAuthenticationFilter sessionFilter
  ) {
    this.captchaService = captchaService;
    this.authService = authService;
    this.sessionService = sessionService;
    this.clientIpResolver = clientIpResolver;
    this.loginIpThrottle = loginIpThrottle;
    this.sessionFilter = sessionFilter;
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
    return loginFor(PortalPurpose.SCHOOL, request, servletRequest, servletResponse);
  }

  @PostMapping("/school/login")
  public LoginResponse schoolLogin(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
    return loginFor(PortalPurpose.SCHOOL, body, req, res);
  }

  @PostMapping("/platform/login")
  public LoginResponse platformLogin(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
    return loginFor(PortalPurpose.PLATFORM, body, req, res);
  }

  private LoginResponse loginFor(PortalPurpose purpose, LoginRequest request,
      HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
    // A3② 按 IP 节流：先挡住已超限的 IP（429，不再进入验证码/查库），再执行登录；任何登录失败都计一次数。
    // 只在此层包裹，AuthService.login 那段带 noRollbackFor 的事务性账号锁定逻辑完全不动。
    String ip = clientIpResolver.resolve(servletRequest);
    loginIpThrottle.checkAllowed(ip);
    try {
      AuthService.LoginResult result = authService.login(request, ip, purpose, sessionFilter.sessionId(servletRequest,purpose));
      servletResponse.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
      servletResponse.addHeader(HttpHeaders.SET_COOKIE, result.cookie().toString());
      return result.response();
    } catch (AuthException failure) {
      loginIpThrottle.recordFailure(ip);
      throw failure;
    } catch (org.springframework.dao.DataIntegrityViolationException commitRejected) {
      throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_REAUTHENTICATION_REQUIRED","Login could not be committed; sign in again");
    } catch (org.springframework.dao.DataAccessException unavailable) {
      // The service transaction has already rolled back login-success bookkeeping here.
      throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_STATE_UNAVAILABLE","Authentication state is unavailable");
    } catch (org.springframework.transaction.TransactionException uncertainCommit) {
      throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_REAUTHENTICATION_REQUIRED","Login could not be committed; sign in again");
    }
  }

  @GetMapping("/me")
  public MeResponse me(HttpServletRequest request) {
    return authService.me(envelope(request));
  }

  @PostMapping("/me/onboarded")
  public MeResponse markOnboarded(HttpServletRequest request) {
    return authService.markOnboarded(envelope(request));
  }

  @PostMapping("/me/change-password")
  public MeResponse changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
    var result=authService.changePassword(envelope(request), body);
    response.addHeader(HttpHeaders.SET_COOKIE,result.cookie().toString());
    return result.response();
  }

  @PostMapping("/logout")
  public LogoutResponse logout(HttpServletRequest request, HttpServletResponse response) {
    var envelope=envelope(request);
    authService.logout(envelope);
    response.addHeader(HttpHeaders.SET_COOKIE, sessionService.clearCookie(envelope.audience()).toString());
    return new LogoutResponse(true);
  }

  private SessionEnvelope envelope(HttpServletRequest request) {
    Object envelope = request.getAttribute(AuthRequestAttributes.ENVELOPE);
    if (envelope instanceof SessionEnvelope current) {
      return current;
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
