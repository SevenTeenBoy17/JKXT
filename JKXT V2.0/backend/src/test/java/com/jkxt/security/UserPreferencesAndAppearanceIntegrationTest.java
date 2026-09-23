package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserPreferencesAndAppearanceIntegrationTest {

  private static final String ORG_ID = "00000000-0000-0000-0000-000000000001";
  private static final String USER_ID = "00000000-0000-0000-0000-000000000204";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  private String preferencesBefore;
  private String settingsBefore;

  @BeforeEach
  void capture() {
    preferencesBefore = jdbcTemplate.queryForObject(
        "select preferences::text from app_user where id=?::uuid", String.class, USER_ID);
    settingsBefore = jdbcTemplate.queryForObject(
        "select settings::text from organization where id=?::uuid", String.class, ORG_ID);
  }

  @AfterEach
  void restore() {
    jdbcTemplate.update("update app_user set preferences=?::jsonb where id=?::uuid", preferencesBefore, USER_ID);
    jdbcTemplate.update("update organization set settings=?::jsonb where id=?::uuid", settingsBefore, ORG_ID);
  }

  @Test
  void preferencesPersistAndRejectArbitraryColors() throws Exception {
    Cookie session = login("tenant.test", "Tenant001!!!");
    jdbcTemplate.update("""
        update app_user set preferences=jsonb_set(preferences, '{notifications}',
          '{"digest":true,"channel":"email"}'::jsonb, true) where id=?::uuid
        """, USER_ID);

    mockMvc.perform(get("/api/v1/auth/me/preferences").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.theme.version").value(1))
        .andExpect(jsonPath("$.theme.mode").value("system"));

    mockMvc.perform(patch("/api/v1/auth/me/preferences").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"theme":{"mode":"dark","paletteSource":"user","palette":"cyan"}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.theme.mode").value("dark"))
        .andExpect(jsonPath("$.theme.palette").value("cyan"));

    String stored = jdbcTemplate.queryForObject(
        "select preferences::text from app_user where id=?::uuid", String.class, USER_ID);
    assertThat(objectMapper.readTree(stored).path("theme").path("palette").asText()).isEqualTo("cyan");
    assertThat(objectMapper.readTree(stored).path("notifications").path("digest").asBoolean())
        .as("PATCH theme must preserve unrelated future preference keys")
        .isTrue();

    mockMvc.perform(patch("/api/v1/auth/me/preferences").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"theme":{"paletteSource":"user","palette":"#ff00ff"}}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PREFERENCE_INVALID"));
  }

  @Test
  void schoolAppearancePreservesOtherSettingsAndUsesAllowlistedPalette() throws Exception {
    Cookie session = login("tenant.test", "Tenant001!!!");
    JsonNode before = objectMapper.readTree(settingsBefore);

    mockMvc.perform(get("/api/v1/org/settings/appearance").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availablePalettes.length()").value(6));

    mockMvc.perform(put("/api/v1/org/settings/appearance").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":1,\"defaultPalette\":\"green\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultPalette").value("green"));

    JsonNode after = objectMapper.readTree(jdbcTemplate.queryForObject(
        "select settings::text from organization where id=?::uuid", String.class, ORG_ID));
    assertThat(after.path("appearance").path("defaultPalette").asText()).isEqualTo("green");
    assertThat(after.path("allowanceUnit")).isEqualTo(before.path("allowanceUnit"));

    mockMvc.perform(put("/api/v1/org/settings/appearance").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":1,\"defaultPalette\":\"neon\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("APPEARANCE_INVALID"));
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        CaptchaResponse.class);
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","password":"%s","captchaId":"%s","captcha":"%s"}
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk()).andReturn();
    return result.getResponse().getCookie("JKXT_SESSION");
  }
}
