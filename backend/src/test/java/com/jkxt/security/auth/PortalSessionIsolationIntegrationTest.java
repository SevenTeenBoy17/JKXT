package com.jkxt.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PB-4 (round 9, spec 2026-09-20 login-entry isolation). Reproduces the reported defect: signing in as the
 * platform super admin in another tab replaced the school session, because both entries shared one cookie
 * name (JKXT_SESSION) and /auth/login accepted SUPER_ADMIN. All requests here set X-JKXT-Portal explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalSessionIsolationIntegrationTest {

  private static final String SCHOOL = "JKXT_SCHOOL_SESSION";
  private static final String PLATFORM = "JKXT_PLATFORM_SESSION";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void platformLoginInAnotherTabDoesNotReplaceTheSchoolSession() throws Exception {
    MvcResult school = login("/api/v1/auth/school/login", "jwc.demo", "Demo@2025");
    Cookie schoolCookie = school.getResponse().getCookie(SCHOOL);
    assertThat(schoolCookie).isNotNull();
    assertThat(school.getResponse().getCookie(PLATFORM)).isNull();

    MvcResult platform = login("/api/v1/auth/platform/login", "admin", "Super001!!!");
    Cookie platformCookie = platform.getResponse().getCookie(PLATFORM);
    assertThat(platformCookie).isNotNull();
    // The browser keeps the school cookie: the platform login never writes or clears it.
    assertThat(platform.getResponse().getCookie(SCHOOL)).isNull();
    assertThat(platformCookie.getValue()).isNotEqualTo(schoolCookie.getValue());

    // A browser holding both cookies: each entry still resolves its own account.
    mvc.perform(get("/api/v1/auth/me").cookie(schoolCookie, platformCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("jwc.demo"));
    mvc.perform(get("/api/v1/auth/me").header(AuthPortal.HEADER, "school").cookie(schoolCookie, platformCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("jwc.demo"));
    mvc.perform(get("/api/v1/auth/me").header(AuthPortal.HEADER, "platform").cookie(schoolCookie, platformCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("admin"));

    // Platform administration needs the platform entry, even when the platform cookie is present:
    // without the header the school session is read, and a school account is never a platform admin.
    mvc.perform(get("/api/v1/admin/tenants").cookie(schoolCookie, platformCookie))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/tenants").header(AuthPortal.HEADER, "platform").cookie(schoolCookie, platformCookie))
        .andExpect(status().isOk());

    // Logging out of the platform clears only the platform cookie; the school session keeps working.
    MvcResult logout = mvc.perform(post("/api/v1/auth/logout").header(AuthPortal.HEADER, "platform")
            .cookie(schoolCookie, platformCookie)).andExpect(status().isOk()).andReturn();
    assertThat(logout.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
        .anySatisfy(c -> assertThat(c).startsWith(PLATFORM + "=;"))
        .noneSatisfy(c -> assertThat(c).startsWith(SCHOOL + "="));
    mvc.perform(get("/api/v1/auth/me").header(AuthPortal.HEADER, "platform").cookie(platformCookie))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/auth/me").cookie(schoolCookie))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value("jwc.demo"));
  }

  /**
   * Endpoint-agnostic reproduction of the reported defect with a browser-like cookie jar shared by two tabs,
   * using only the pre-isolation /auth/login: tab 1 signs in to the school, tab 2 signs in with the platform
   * super admin. Before PB-4 the second login answered 200 with Set-Cookie JKXT_SESSION, the jar replaced the
   * school cookie and tab 1 silently became the super admin.
   */
  @Test
  void sharedBrowserJarSchoolTabKeepsItsIdentityWhenSuperAdminSignsInFromAnotherTab() throws Exception {
    java.util.Map<String, String> jar = new java.util.LinkedHashMap<>();
    absorb(jar, attempt("/api/v1/auth/login", "jwc.demo", "Demo@2025"));
    String before = me(jar);
    MvcResult superTab = attempt("/api/v1/auth/login", "admin", "Super001!!!");
    absorb(jar, superTab);
    assertThat(before).isEqualTo("jwc.demo");
    assertThat(me(jar)).as("school tab identity after a super-admin login in another tab").isEqualTo("jwc.demo");
  }

  private static void absorb(java.util.Map<String, String> jar, MvcResult result) {
    for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
      String pair = header.split(";", 2)[0];
      String name = pair.substring(0, pair.indexOf('='));
      String value = pair.substring(pair.indexOf('=') + 1);
      if (value.isEmpty() || header.toLowerCase(java.util.Locale.ROOT).contains("max-age=0")) jar.remove(name);
      else jar.put(name, value);
    }
  }

  private String me(java.util.Map<String, String> jar) throws Exception {
    Cookie[] cookies = jar.entrySet().stream().map(e -> new Cookie(e.getKey(), e.getValue())).toArray(Cookie[]::new);
    MvcResult result = mvc.perform(get("/api/v1/auth/me").cookie(cookies)).andReturn();
    return result.getResponse().getStatus() == 200
        ? mapper.readTree(result.getResponse().getContentAsString()).path("user").path("username").asText()
        : "HTTP " + result.getResponse().getStatus();
  }

  @Test
  void wrongEntryWithCorrectPasswordIsRejectedWithoutIssuingASession() throws Exception {
    Integer successBefore = jdbc.queryForObject(
        "select count(*) from audit_log a join app_user u on u.id = a.actor_id where u.username = 'admin' "
            + "and a.action = 'AUTH_LOGIN_SUCCESS'", Integer.class);
    for (String legacyOrSchool : new String[] {"/api/v1/auth/login", "/api/v1/auth/school/login"}) {
      MvcResult rejected = attempt(legacyOrSchool, "admin", "Super001!!!");
      assertThat(rejected.getResponse().getStatus()).isEqualTo(403);
      assertThat(mapper.readTree(rejected.getResponse().getContentAsString()).path("code").asText())
          .isEqualTo("AUTH_PORTAL_MISMATCH");
      assertThat(rejected.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
    MvcResult schoolOnPlatform = attempt("/api/v1/auth/platform/login", "jwc.demo", "Demo@2025");
    assertThat(schoolOnPlatform.getResponse().getStatus()).isEqualTo(403);
    assertThat(mapper.readTree(schoolOnPlatform.getResponse().getContentAsString()).path("code").asText())
        .isEqualTo("AUTH_PORTAL_MISMATCH");
    assertThat(schoolOnPlatform.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    assertThat(jdbc.queryForObject(
        "select count(*) from audit_log a join app_user u on u.id = a.actor_id where u.username = 'admin' "
            + "and a.action = 'AUTH_LOGIN_SUCCESS'", Integer.class)).isEqualTo(successBefore);
  }

  @Test
  void aCookieInTheOtherSlotOrAnUnknownEntryHeaderNeverAuthenticates() throws Exception {
    Cookie platformCookie = login("/api/v1/auth/platform/login", "admin", "Super001!!!").getResponse().getCookie(PLATFORM);
    // Platform session value replayed in the school cookie slot: not a school session.
    mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(SCHOOL, platformCookie.getValue())))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/auth/me").header(AuthPortal.HEADER, "admin").cookie(platformCookie))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH_PORTAL_INVALID"));
    // The pre-isolation cookie name is no longer read.
    mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("JKXT_SESSION", platformCookie.getValue())))
        .andExpect(status().isUnauthorized());
  }

  private MvcResult login(String path, String username, String password) throws Exception {
    MvcResult result = attempt(path, username, password);
    assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    return result;
  }

  private MvcResult attempt(String path, String username, String password) throws Exception {
    JsonNode captcha = mapper.readTree(mvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
        java.util.Map.of("username", username, "password", password,
            "captchaId", captcha.path("captchaId").asText(), "captcha", captcha.path("debugAnswer").asText()))))
        .andReturn();
  }
}
