package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 新建租户必须带一个当前学期。
 *
 * <p>此前 {@code createTenant} 只插 organization，academic_term 为空，于是 {@code /auth/me} 的
 * term 恒为 null——顶栏学期徽章显示「未设置学期」，报表口径也没有学期锚点，新学校开箱即残。
 *
 * <p>第二个用例守的是播种方式本身：createTenant 用了 {@code on conflict (slug) do update}，
 * 因此同一 slug 再次调用会命中**已存在**的租户；而 V17 对 {@code (org_id) where is_current}
 * 建了唯一索引。若播种写成无条件 insert，第二次调用会直接违约 500。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantInitialTermSeedTest {

  private static final String SLUG = "seed-term-probe";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    // academic_term 对 organization 是 ON DELETE CASCADE，删组织即连带清掉学期。
    jdbcTemplate.update("delete from organization where slug = ?", SLUG);
  }

  @Test
  void newTenantGetsACurrentTerm() throws Exception {
    Cookie session = loginSuperAdmin();

    UUID orgId = UUID.fromString(createTenant(session, SLUG).path("uuid").asText());

    List<Map<String, Object>> terms = jdbcTemplate.queryForList(
        "select year_label, term, name, is_current from academic_term where org_id = ?", orgId);

    assertThat(terms).as("新建租户必须带一个学期，否则 /auth/me 的 term 恒为 null").hasSize(1);
    Map<String, Object> seeded = terms.get(0);
    assertThat((Boolean) seeded.get("is_current")).as("播种的学期必须是当前学期").isTrue();
    assertThat((String) seeded.get("term")).isIn("S1", "S2");
    // 形如 2025-2026：跨年标签，两段必须是相邻年份。
    String yearLabel = (String) seeded.get("year_label");
    String[] years = yearLabel.split("-");
    assertThat(years).as("学年标签应为 起始年-结束年").hasSize(2);
    assertThat(Integer.parseInt(years[1])).isEqualTo(Integer.parseInt(years[0]) + 1);
    assertThat((String) seeded.get("name")).contains(yearLabel).contains("学期");
  }

  @Test
  void reCreatingSameSlugDoesNotViolateTheSingleCurrentTermIndex() throws Exception {
    Cookie session = loginSuperAdmin();

    UUID orgId = UUID.fromString(createTenant(session, SLUG).path("uuid").asText());
    // 同 slug 再来一次：走 on conflict do update 分支，命中已有学期的租户。
    // 播种若非幂等，这里会因 uq_academic_term_org_current 唯一索引冲突而 500。
    UUID again = UUID.fromString(createTenant(session, SLUG).path("uuid").asText());

    assertThat(again).as("同 slug 应 upsert 到同一租户").isEqualTo(orgId);
    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from academic_term where org_id = ?", Integer.class, orgId);
    assertThat(count).as("重复创建不得堆积重复学期").isEqualTo(1);
  }

  // ---------- helpers ----------

  /** 注意返回体里 {@code id} 是 slug（前端表格主键），真正的组织 UUID 在 {@code uuid} 键下。 */
  private JsonNode createTenant(Cookie session, String slug) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/admin/tenants")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Seed Term Probe\",\"slug\":\"" + slug + "\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    return json(result);
  }

  private Cookie loginSuperAdmin() throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        CaptchaResponse.class);
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"admin","password":"Super001!!!","captchaId":"%s","captcha":"%s"}
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie("JKXT_SESSION");
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
