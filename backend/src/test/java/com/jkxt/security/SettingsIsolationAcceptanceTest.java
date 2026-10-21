package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jkxt.security.auth.SessionPrincipal;
import com.jkxt.security.auth.SessionService;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Public HTTP + real isolated PostgreSQL/Redis; no live account or mock authorization. */
@SpringBootTest(properties = "jkxt.deploy.mode=saas")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SettingsIsolationAcceptanceTest {
  @Autowired MockMvc http;
  @Autowired JdbcTemplate jdbc;
  @Autowired SessionService sessions;
  private final List<String> sessionIds = new ArrayList<>();

  @AfterEach
  void revokeFixtureSessions() {
    sessionIds.forEach(sessions::revoke);
  }

  @Test
  void anonymousCannotReadOrWriteEitherSetting() throws Exception {
    http.perform(get("/api/v1/auth/me/preferences")).andExpect(status().isUnauthorized());
    http.perform(patch("/api/v1/auth/me/preferences").contentType(MediaType.APPLICATION_JSON)
        .content("{\"theme\":{\"mode\":\"dark\"}}"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/api/v1/org/settings/appearance")).andExpect(status().isUnauthorized());
    http.perform(put("/api/v1/org/settings/appearance").contentType(MediaType.APPLICATION_JSON)
        .content("{\"defaultPalette\":\"green\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void viewerCanSaveOwnPreferenceButCannotChangeSchoolAppearance() throws Exception {
    Account viewer = account("VIEWER");
    http.perform(patch("/api/v1/auth/me/preferences").cookie(viewer.cookie())
        .contentType(MediaType.APPLICATION_JSON).content("{\"theme\":{\"mode\":\"dark\"}}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.theme.mode").value("dark"));
    http.perform(get("/api/v1/auth/me/preferences").cookie(viewer.cookie()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.theme.mode").value("dark"));
    String before = school(viewer.org());
    http.perform(put("/api/v1/org/settings/appearance").cookie(viewer.cookie())
        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPalette\":\"green\"}"))
        .andExpect(status().isForbidden());
    assertThat(school(viewer.org())).isEqualTo(before);
  }

  @Test
  void schoolUpdatesAreTenantScopedAndPreserveOtherSettings() throws Exception {
    Account first = account("ADMIN");
    Account second = account("ADMIN");
    String otherBefore = school(second.org());
    http.perform(put("/api/v1/org/settings/appearance").cookie(first.cookie())
        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPalette\":\"cyan\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.defaultPalette").value("cyan"));
    http.perform(get("/api/v1/org/settings/appearance").cookie(first.cookie()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.defaultPalette").value("cyan"));
    assertThat(school(second.org())).isEqualTo(otherBefore);
    assertThat(jdbc.queryForObject("select settings->>'unrelated' from organization where id=?",
        String.class, first.org())).isEqualTo("preserve");
    http.perform(put("/api/v1/org/settings/appearance").cookie(first.cookie())
        .header("X-Tenant-Id", second.org()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"defaultPalette\":\"purple\"}"))
        .andExpect(status().isForbidden());
    assertThat(school(second.org())).isEqualTo(otherBefore);
  }

  @Test
  void preferencePayloadCannotTargetAnotherUserOrOrganization() throws Exception {
    Account first = account("ADMIN");
    Account second = account("ADMIN");
    String otherBefore = preference(second.user());
    http.perform(patch("/api/v1/auth/me/preferences").cookie(first.cookie())
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"orgId":"%s","userId":"%s","theme":{"paletteSource":"user","palette":"rose"}}
            """.formatted(second.org(), second.user())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.theme.palette").value("rose"));
    assertThat(preference(second.user())).isEqualTo(otherBefore);
    assertThat(jdbc.queryForObject("select preferences->>'unrelated' from app_user where id=?",
        String.class, first.user())).isEqualTo("preserve");
  }

  @Test
  void invalidOrMissingValuesDoNotPersist() throws Exception {
    Account account = account("ADMIN");
    String before = preference(account.user());
    for (String body : List.of("{}", "{\"theme\":null}",
        "{\"theme\":{\"version\":2}}", "{\"theme\":{\"mode\":\"neon\"}}",
        "{\"theme\":{\"paletteSource\":\"external\"}}", "{\"theme\":{\"palette\":\"#fff\"}}")) {
      http.perform(patch("/api/v1/auth/me/preferences").cookie(account.cookie())
          .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
      assertThat(preference(account.user())).isEqualTo(before);
    }
    String schoolBefore = school(account.org());
    for (String body : List.of("{}", "{\"defaultPalette\":null}",
        "{\"defaultPalette\":\" \"}", "{\"version\":2,\"defaultPalette\":\"blue\"}")) {
      http.perform(put("/api/v1/org/settings/appearance").cookie(account.cookie())
          .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
      assertThat(school(account.org())).isEqualTo(schoolBefore);
    }
  }

  private Account account(String role) {
    UUID org = UUID.randomUUID(), user = UUID.randomUUID();
    String username = "settings-" + user;
    jdbc.update("insert into organization(id,name,slug,settings) values (?,? ,?,?::jsonb)",
        org, "Isolated settings school", "settings-" + org, "{\"unrelated\":\"preserve\"}");
    // These settings fixtures represent users who already completed their initial password change.
    // The temporary-password gate itself is exercised through real HTTP in AuthHardeningIntegrationTest.
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,preferences,must_change_password)
        values (?,?,?,?,?,?,?::jsonb,false)
        """, user, org, username, "Isolated settings account", "not-a-login-hash", role,
        "{\"unrelated\":\"preserve\",\"theme\":{\"version\":1,\"mode\":\"system\",\"paletteSource\":\"school\",\"palette\":\"indigo\"}}");
    var session = sessions.create(new SessionPrincipal("", user, org, username, "Fixture", role,
        List.of("tenant:read")));
    sessionIds.add(session.cookie().getValue());
    return new Account(org, user, new Cookie(session.cookie().getName(), session.cookie().getValue()));
  }

  private String preference(UUID user) {
    return jdbc.queryForObject("select preferences::text from app_user where id=?", String.class, user);
  }

  private String school(UUID org) {
    return jdbc.queryForObject("select settings::text from organization where id=?", String.class, org);
  }

  private record Account(UUID org, UUID user, Cookie cookie) {}
}
