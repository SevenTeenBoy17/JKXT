package com.jkxt.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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

/**
 * 学期管理 API 验收 —— 修复用户实测的死循环：顶栏提示「切换学期需在信息设置中操作」，
 * 但信息设置页没有学期区块，且**全库没有任何 term Controller**（academic_term 有表有实体有仓储，
 * 却只被 AuthService 只读下发）。
 *
 * <p>同时锁住「同一 org 至多一条当前学期」——此前 is_current 只有普通索引、仓储返回 List、
 * AuthService 静默取第一条，一旦出现两条 current，「当前学期」就是不确定值（V17 加了部分唯一索引）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AcademicTermApiIntegrationTest {

  private static final String YEAR = "2099-2100";   // 远期年份，避免与种子/其它用例冲突

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  /**
   * 本类的 activate/create(current=true) 会把既有当前学期的 is_current 夺走（这是正确的产品行为，
   * 同一 org 只许一条 current）。若只删测试学期而不还原，该 org 会被留在**零当前学期**状态，
   * 从而污染同库运行的其它用例——实测曾导致 P2DatabaseIntegrationTest 与 AuthIntegrationTest
   * 各挂一条（$.term 为 null）。故这里先记原状、后精确还原。
   */
  private List<UUID> previousCurrentTermIds = List.of();

  @BeforeEach
  void captureCurrentTerms() {
    previousCurrentTermIds = jdbcTemplate.queryForList(
        "select id from academic_term where is_current and year_label <> ?", UUID.class, YEAR);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("delete from academic_term where year_label = ?", YEAR);
    // 仅当该 org 此刻确实没有 current 时才还原，避免与 V17 的部分唯一索引
    // uq_academic_term_org_current(org_id) WHERE is_current 冲突。
    for (UUID id : previousCurrentTermIds) {
      jdbcTemplate.update("""
          update academic_term t set is_current = true, updated_at = now()
          where t.id = ?
            and not exists (select 1 from academic_term x where x.org_id = t.org_id and x.is_current)
          """, id);
    }
  }

  @Test
  void termsAreListableAndCreatable() throws Exception {
    Cookie session = login();

    // 缺陷点：此前 GET /api/v1/terms 是 404（全库无 term Controller）
    mockMvc.perform(get("/api/v1/terms").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    MvcResult created = mockMvc.perform(post("/api/v1/terms").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"yearLabel":"%s","term":"S1","startDate":"2099-09-01","endDate":"2100-01-31"}
                """.formatted(YEAR)))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode dto = json(created);
    assertThat(dto.path("yearLabel").asText()).isEqualTo(YEAR);
    assertThat(dto.path("termLabel").asText()).isEqualTo("第一学期");
    // 未显式要求设为当前时不得抢占既有当前学期
    assertThat(dto.path("current").asBoolean()).isFalse();
  }

  @Test
  void activateKeepsAtMostOneCurrentTermPerOrg() throws Exception {
    Cookie session = login();
    String id = json(mockMvc.perform(post("/api/v1/terms").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"yearLabel":"%s","term":"S2","startDate":"2100-02-01","endDate":"2100-07-15"}
                """.formatted(YEAR)))
        .andExpect(status().isCreated()).andReturn()).path("id").asText();

    mockMvc.perform(post("/api/v1/terms/" + id + "/activate").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.current").value(true));

    // 核心不变量：整个 org 只能有一条 current（V17 部分唯一索引 + service 先清后置）
    Integer currents = jdbcTemplate.queryForObject(
        "select count(*) from academic_term t where t.is_current and t.org_id = "
            + "(select org_id from academic_term where id = ?::uuid)", Integer.class, id);
    assertThat(currents).as("同一 org 至多一条当前学期").isEqualTo(1);

    // /terms/current 应指向刚激活的这条
    mockMvc.perform(get("/api/v1/terms/current").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));
  }

  @Test
  void currentTermCannotBeDeleted() throws Exception {
    Cookie session = login();
    String id = json(mockMvc.perform(post("/api/v1/terms").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"yearLabel":"%s","term":"S1","startDate":"2099-09-01","endDate":"2100-01-31","current":true}
                """.formatted(YEAR)))
        .andExpect(status().isCreated()).andReturn()).path("id").asText();

    mockMvc.perform(delete("/api/v1/terms/" + id).cookie(session))
        .andExpect(status().isConflict());
  }

  @Test
  void invalidDateRangeIsRejected() throws Exception {
    Cookie session = login();
    mockMvc.perform(post("/api/v1/terms").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"yearLabel":"%s","term":"S1","startDate":"2100-01-31","endDate":"2099-09-01"}
                """.formatted(YEAR)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidTermCodeIsRejected() throws Exception {
    Cookie session = login();
    // term 只能 S1/S2（与 V2 迁移的 CHECK 约束一致），不合法值应在入口被挡，而非穿到 DB 才 500
    mockMvc.perform(post("/api/v1/terms").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"yearLabel":"%s","term":"S9","startDate":"2099-09-01","endDate":"2100-01-31"}
                """.formatted(YEAR)))
        .andExpect(status().isBadRequest());
  }

  // ---------- helpers ----------

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
    return login.getResponse().getCookie("JKXT_SESSION");
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
