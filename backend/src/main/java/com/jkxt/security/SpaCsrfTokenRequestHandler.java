package com.jkxt.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * PB-5 (round 9): SPA CSRF handling as documented for Spring Security 6.4+/7 ("Single-Page
 * Applications"). The JavaScript-readable XSRF-TOKEN cookie is separate from the HttpOnly session
 * cookie (ADR-06). The SPA echoes the raw cookie value in X-XSRF-TOKEN; server-rendered values keep
 * BREACH protection through the XOR handler. {@code csrfToken.get()} renders the cookie eagerly so the
 * first page load (captcha/login/me) already provides a token for the following writes.
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

  private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
  private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
    this.xor.handle(request, response, csrfToken);
    csrfToken.get();
  }

  @Override
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
    String headerValue = request.getHeader(csrfToken.getHeaderName());
    return (StringUtils.hasText(headerValue) ? this.plain : this.xor).resolveCsrfTokenValue(request, csrfToken);
  }
}
