package com.jkxt.platform;

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
 * P0「伪造成功」回归：平台运营台曾有两处前后端合谋谎报成功，会让人据此做出错误决策。
 *
 * <p><b>缺陷 1 · 备份是假的</b>：{@code runBackup()} 根本不执行备份，只往 backup_record 插一条写死
 * {@code status='succeeded'} / {@code size_mb=4200.00} / 假 checksum 的记录，UI 弹「备份完成」并显示 4.2GB。
 * 运维会据此相信有可恢复的备份——真出事时才发现什么都没有。
 * 真实边界：app 容器内无 pg_dump，备份由 compose 的 backup 服务（ops profile）产出到共享卷；
 * 因此正确行为是**扫描真实产物**，无产物时如实返回 unavailable 并给出触发方式，而不是编造。
 *
 * <p><b>缺陷 2 · 系统设置保存是假的</b>：{@code savePlatformSettings()} 只 audit + 原样回显，不写任何库
 * （全库连 platform_setting 表都不存在），UI 弹「配置已下发,审计已记录」，刷新即回退。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformHonestyAcceptanceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void platformSettingsArePersistedNotEchoedBack() throws Exception {
    Cookie session = loginSuperAdmin();

    // 读初值（应来自库/默认值，而非硬编码常量）
    boolean original = json(mockMvc.perform(get("/api/v1/admin/platform-settings").cookie(session))
        .andExpect(status().isOk()).andReturn()).path("guestAccess").asBoolean();

    // 写入取反值
    boolean flipped = !original;
    mockMvc.perform(put("/api/v1/admin/platform-settings").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"guestAccess\": " + flipped + "}"))
        .andExpect(status().isOk());

    // 缺陷点：此前只回显不落库 —— 重新 GET 会拿回旧值（刷新即回退）
    boolean afterReload = json(mockMvc.perform(get("/api/v1/admin/platform-settings").cookie(session))
        .andExpect(status().isOk()).andReturn()).path("guestAccess").asBoolean();
    assertThat(afterReload).as("保存后重新读取应拿到新值，而不是回退").isEqualTo(flipped);

    // 真落库的铁证：直接查表
    Integer rows = jdbcTemplate.queryForObject(
        "select count(*) from platform_setting where key = 'guestAccess'", Integer.class);
    assertThat(rows).as("platform_setting 表中应有该键的真实行").isEqualTo(1);

    // 还原，避免污染后续用例
    mockMvc.perform(put("/api/v1/admin/platform-settings").cookie(session)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"guestAccess\": " + original + "}"));
  }

  @Test
  void backupRunDoesNotFabricateSuccessWhenNoArtifactExists() throws Exception {
    Cookie session = loginSuperAdmin();
    Integer before = jdbcTemplate.queryForObject("select count(*) from backup_record", Integer.class);
    // 注意：库里可能已有 Flyway 种子的演示记录（如 BAK-P10-SEED，同样是 4200MB/local-snapshot），
    // 那是历史数据而非本次调用产物，故这里用"调用前后的增量"判定，不能用"表中是否存在"。
    Integer fakeBefore = jdbcTemplate.queryForObject(
        "select count(*) from backup_record where size_mb = 4200.00 and storage = 'local-snapshot'",
        Integer.class);

    MvcResult result = mockMvc.perform(post("/api/v1/admin/backups/run").cookie(session))
        .andExpect(status().isOk()).andReturn();
    JsonNode body = json(result);

    // 测试环境的备份目录不存在/为空 → 必须如实报告，不得再插假的 succeeded 记录。
    assertThat(body.path("status").asText()).as("无真实备份产物时不得谎报成功").isEqualTo("unavailable");
    assertThat(body.path("message").asText()).contains("pg_dump");

    Integer after = jdbcTemplate.queryForObject("select count(*) from backup_record", Integer.class);
    assertThat(after).as("不得凭空新增备份记录").isEqualTo(before);

    // 并且本次调用绝不能**新增**写死 4200MB 的伪造记录（历史种子数据不计）
    Integer fakeAfter = jdbcTemplate.queryForObject(
        "select count(*) from backup_record where size_mb = 4200.00 and storage = 'local-snapshot'",
        Integer.class);
    assertThat(fakeAfter).as("不得新增写死 4200MB 的伪造备份记录").isEqualTo(fakeBefore);
  }

  // ---------- helpers ----------

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
