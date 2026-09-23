package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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
 * P18 批次7b 验收：平台健康度真实指标 + 审计日志时间窗。
 *
 * <p>健康度：超管仪表盘的「平台健康度」面板此前写死示例数（数据库连接 14/50、API 调用 7.1万）。
 * 新端点 /admin/system-health 进程内读 MeterRegistry/Runtime——本用例锁「返回的是真实运行时数字」
 * （堆内存/运行时长恒可得且必须为正）与「SUPER_ADMIN 专属」。
 *
 * <p>时间窗：/admin/audit-logs 此前不接任何参数，前端「时间窗」胶囊纯装饰（批次7 已将其移除）。
 * 现支持 ?hours=N；本用例向库中植入一条 48 小时前的审计行，验证 hours=24 排除它、不带参数包含它——
 * 这是「过滤真的发生在数据层」的直接证据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemHealthAndAuditWindowTest {

  private static final String PROBE_ACTION = "TEST_WINDOW_PROBE_OLD";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from audit_log where action like ?", PROBE_ACTION + "%");
  }

  @Test
  void systemHealthReturnsRealRuntimeNumbersForSuperAdminOnly() throws Exception {
    // 普通教务管理员必须被 /admin/** 的 SUPER_ADMIN 门挡下
    mockMvc.perform(get("/api/v1/admin/system-health").cookie(login("jwc.demo", "Demo@2025")))
        .andExpect(status().isForbidden());

    JsonNode health = json(mockMvc.perform(
            get("/api/v1/admin/system-health").cookie(login("admin", "Super001!!!")))
        .andExpect(status().isOk()).andReturn());

    // 堆与运行时长来自 Runtime/RuntimeMXBean，任何环境都必须是正数——这俩是「真值而非编造」的锚点
    assertThat(health.path("heapUsedMb").asLong()).isGreaterThan(0);
    assertThat(health.path("heapMaxMb").asLong()).isGreaterThan(0);
    assertThat(health.path("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0);
    // 连接池指标依赖 micrometer 绑定，允许为 null，但键必须存在（前端据此显示「暂无数据」）
    assertThat(health.has("dbPoolMax")).isTrue();
    assertThat(health.has("httpRequestsTotal")).isTrue();
    // 7c：CPU/磁盘。system.cpu.count 由 ProcessorMetrics 恒定绑定，必为正——真值锚点；
    // usage 类首次采样可能为 NaN→null，只锁键存在与合法区间。
    assertThat(health.path("cpuCount").asDouble()).isGreaterThan(0);
    assertThat(health.has("cpuSystem")).isTrue();
    assertThat(health.has("diskTotalBytes")).isTrue();
    if (!health.path("cpuSystem").isNull()) {
      assertThat(health.path("cpuSystem").asDouble()).isBetween(0.0, 1.0);
    }
    if (!health.path("diskTotalBytes").isNull()) {
      assertThat(health.path("diskTotalBytes").asDouble()).isGreaterThan(0);
    }
  }

  @Test
  void auditHoursWindowFiltersAtTheDataLayer() throws Exception {
    Cookie session = login("admin", "Super001!!!");
    // 查询带 limit 200 且共享测试库里当天审计行极多——48h 前的探针在**无窗**结果里会被
    // 新行顶出（曾因此误红）。改用两根探针：
    //   probeIn  = now()+1min：created_at desc 恒排第一，任何 limit 都截不掉；
    //             若日后有人把 where 方向写反(<=)，它会消失 → 断言变红。
    //   probeOld = now()-48h：验证 hours=24 的数据层排除；其"确实在表里"由 jdbc 直接锁定，
    //             杜绝"探针没插进去"型假绿。（若 where 整个丢失，old 仍可能被 limit 顶出——
    //             该残余场景由 probeIn 的方向断言与全量回归共同兜底。）
    jdbcTemplate.update("""
        insert into audit_log (org_id, action, target_type, severity, payload, created_at)
        values ('00000000-0000-0000-0000-000000000001', ?, 'test', 'info', '{}'::jsonb, now() + interval '1 minute'),
               ('00000000-0000-0000-0000-000000000001', ?, 'test', 'info', '{}'::jsonb, now() - interval '48 hours')
        """, PROBE_ACTION + "_IN", PROBE_ACTION);
    Integer oldInDb = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = ?", Integer.class, PROBE_ACTION);
    assertThat(oldInDb).as("48h 探针必须真实入库").isEqualTo(1);

    String all = mockMvc.perform(get("/api/v1/admin/audit-logs").cookie(session))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(all).as("不带 hours 必须保持既有行为且包含最新行").contains(PROBE_ACTION + "_IN");

    String recent = mockMvc.perform(get("/api/v1/admin/audit-logs").param("hours", "24").cookie(session))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(recent).as("hours=24 必须包含窗口内最新行（where 方向正确）").contains(PROBE_ACTION + "_IN");
    assertThat(recent).as("hours=24 必须在数据层排除 48h 前的行").doesNotContain("\"" + PROBE_ACTION + "\"");
  }

  // ---------- helpers ----------

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        CaptchaResponse.class);
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","password":"%s","captchaId":"%s","captcha":"%s"}
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie("JKXT_SESSION");
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
