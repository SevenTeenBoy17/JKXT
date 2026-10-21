package com.jkxt.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 津贴单价只读缺口的验收（P18 批次 5.1）。
 *
 * <p>缺陷：{@code organization.settings.allowanceUnit} 决定全部津贴金额（教师列表 / 历史统计 /
 * 导出报表的钱数都按 单价×时长 现算），onboarding 承诺「场次×时长×单价」，但此前全库
 * **没有任何接口能改它**——建租户后单价焊死在默认 120 元/小时。与学期缺口同构。
 *
 * <p>额外锁两条容易翻车的边：jsonb_set 不得吞掉 settings 里的其它键（abbr/color/students…）；
 * settings 里的脏值不得把读路径炸成 500（数字模式守卫回退 120，与 MasterDataService 同款）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgSettingsApiIntegrationTest {

  private static final String DEMO_ORG = "00000000-0000-0000-0000-000000000001";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String settingsBefore;

  @BeforeEach
  void captureSettings() {
    settingsBefore = jdbcTemplate.queryForObject(
        "select settings::text from organization where id = ?::uuid", String.class, DEMO_ORG);
  }

  @AfterEach
  void restoreSettings() {
    jdbcTemplate.update(
        "update organization set settings = ?::jsonb, updated_at = now() where id = ?::uuid",
        settingsBefore, DEMO_ORG);
  }

  @Test
  void allowanceUnitIsReadableAndUpdatable() throws Exception {
    Cookie session = login();

    // 读：无论历史值如何，必须给出数值（未设置时回退默认 120）
    JsonNode before = json(mockMvc.perform(get("/api/v1/org/settings").cookie(session))
        .andExpect(status().isOk()).andReturn());
    assertThat(before.path("allowanceUnit").isNumber()).isTrue();

    // 写：改成 150 并读回
    JsonNode updated = json(mockMvc.perform(put("/api/v1/org/settings").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"allowanceUnit\":150}"))
        .andExpect(status().isOk()).andReturn());
    assertThat(updated.path("allowanceUnit").asDouble()).isEqualTo(150.0);

    JsonNode after = json(mockMvc.perform(get("/api/v1/org/settings").cookie(session))
        .andExpect(status().isOk()).andReturn());
    assertThat(after.path("allowanceUnit").asDouble()).isEqualTo(150.0);

    // jsonb_set 只许动 allowanceUnit 一个键：settings 里的其它键必须原样保留
    String abbrBefore = extractKey(settingsBefore, "abbr");
    String abbrAfter = jdbcTemplate.queryForObject(
        "select settings->>'abbr' from organization where id = ?::uuid", String.class, DEMO_ORG);
    assertThat(abbrAfter).as("jsonb_set 不得吞掉 settings 的其它键").isEqualTo(abbrBefore);
  }

  @Test
  void invalidUnitsAreRejectedAtTheDoor() throws Exception {
    Cookie session = login();
    for (String bad : new String[] {"0", "-5", "10000"}) {
      mockMvc.perform(put("/api/v1/org/settings").cookie(session)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"allowanceUnit\":" + bad + "}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void dirtyStoredValueFallsBackToDefaultInsteadOf500() throws Exception {
    Cookie session = login();
    // 直接把脏值塞进库（历史上的确出现过：迭代9 F6 记载空串曾把裸 ::numeric 炸成 22P02→500）
    jdbcTemplate.update(
        "update organization set settings = jsonb_set(coalesce(settings,'{}'::jsonb), "
            + "'{allowanceUnit}', '\"not-a-number\"'::jsonb) where id = ?::uuid", DEMO_ORG);

    JsonNode dto = json(mockMvc.perform(get("/api/v1/org/settings").cookie(session))
        .andExpect(status().isOk()).andReturn());
    assertThat(dto.path("allowanceUnit").asDouble())
        .as("脏值必须回退默认 120，不得 500")
        .isEqualTo(120.0);
  }

  // ---------- helpers ----------

  private String extractKey(String settingsJson, String key) {
    try {
      JsonNode node = objectMapper.readTree(settingsJson == null ? "{}" : settingsJson);
      return node.path(key).isMissingNode() || node.path(key).isNull() ? null : node.path(key).asText();
    } catch (Exception e) {
      return null;
    }
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        CaptchaResponse.class);
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"jwc.demo","password":"Demo@2025","captchaId":"%s","captcha":"%s"}
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
