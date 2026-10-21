package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Round 11 review R11-F3: behind a TLS-terminating proxy the app sees plain HTTP (no forwarded-header
 * strategy is configured), so a CSRF cookie whose Secure flag follows request.isSecure() was sent without
 * Secure even when jkxt.auth.cookie-secure=true. The XSRF-TOKEN cookie must follow the same switch as the
 * session cookies.
 */
@SpringBootTest(properties = "jkxt.auth.cookie-secure=true")
@ActiveProfiles("test")
class CsrfCookieSecureFlagIntegrationTest {

  @Autowired
  private WebApplicationContext context;

  @Test
  void csrfCookieIsSecureWhenCookieSecureIsOnEvenOverProxiedPlainHttp() throws Exception {
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    Cookie xsrf = mvc.perform(get("/api/v1/auth/captcha").secure(false)).andReturn().getResponse().getCookie("XSRF-TOKEN");
    assertThat(xsrf).isNotNull();
    assertThat(xsrf.getSecure()).as("XSRF-TOKEN must carry Secure when jkxt.auth.cookie-secure=true").isTrue();
    assertThat(xsrf.isHttpOnly()).isFalse();
  }
}