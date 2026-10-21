package com.jkxt.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jkxt.security.auth.AuthPortal;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PB-5 (round 9): production now enforces CSRF on every unsafe cookie-session request. The existing
 * MockMvc suites test business behaviour, not the browser carrier, so the auto-configured MockMvc
 * attaches a valid CSRF token by default (the SPA does the same through api.js). CSRF enforcement
 * itself is proven by {@code security.CsrfProtectionIntegrationTest}, which builds its own MockMvc
 * WITHOUT this default.
 */
@Configuration(proxyBeanMethods = false)
public class CsrfMockMvcTestDefaults {

  @Bean
  MockMvcBuilderCustomizer csrfTokenByDefault() {
    return builder -> builder.defaultRequest(get("/").with(csrf()).with(platformEntryHeader()));
  }

  /**
   * PB-4 (round 9): the platform SPA always sends {@code X-JKXT-Portal: platform}. Suites that act as the
   * seeded platform operator carry only its platform cookie, so mirror the SPA for them; an explicit header in
   * a test always wins. Entry isolation itself is proven by {@code security.auth.PortalSessionIsolationIntegrationTest}.
   */
  private static RequestPostProcessor platformEntryHeader() {
    return request -> {
      Cookie[] cookies = request.getCookies();
      if (request.getHeader(AuthPortal.HEADER) == null && cookies != null
          && Arrays.stream(cookies).anyMatch(c -> TestPortal.PLATFORM_COOKIE.equals(c.getName()))
          && Arrays.stream(cookies).noneMatch(c -> TestPortal.SCHOOL_COOKIE.equals(c.getName()))) {
        request.addHeader(AuthPortal.HEADER, "platform");
      }
      return request;
    };
  }
}
