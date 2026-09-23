package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
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
 * P0 安全回归：求解器 fixture 脚手架不得在非开发部署暴露。
 *
 * <p>缺陷：{@code /api/v1/scheduling/fixtures/**} 是 P6 阶段的求解器开发脚手架，却落在
 * SecurityConfig 的 {@code POST /api/v1/scheduling/**} → {@code schedule:manage} 规则下，
 * 即**任何教务管理员**都能调用。而 {@code POST /fixtures/{id}/runs} 会经
 * {@code ScheduleJobService.upsertFixtureMasterData} 按 teacherNo / roomCode 查到**真实档案后覆盖写**：
 * 教师 gender 抹成 "unknown"、grade/status/homeroom 按 fixture 覆盖；教室 building 改写成
 * "P6 Fixture"、floor 改 "1"、features 改 ["solver-fixture"]、capacity/examCapacity 按 fixture 覆盖。
 * 这不是假想——本项目基线教室 C101–C104 曾被此路径覆盖，事后靠人工清理。
 *
 * <p>本类锁两侧：私有化部署下三个 fixture 端点必须 404；同时不得误伤——真实业务路径
 * {@code /exam-tasks/{id}/schedule-jobs} 与运行查询 {@code /scheduling/runs/**} 不走此门。
 * dev 侧仍放行由既有的 ScheduleJobApiIntegrationTest / ScheduleEditApiIntegrationTest 覆盖
 * （测试 profile 未覆写 jkxt.deploy.mode，默认即 dev）。
 */
@SpringBootTest(properties = {
    "jkxt.deploy.mode=private",
    "jkxt.deploy.fixed-org-id=00000000-0000-0000-0000-000000000001"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FixtureScaffoldingProductionGuardTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void fixtureEndpointsAreNotExposedOutsideDevDeployments() throws Exception {
    Cookie session = loginAdmin();

    // 三个端点在私有化部署下都不该存在。用 404 而非 403：不对外确认其存在性。
    mockMvc.perform(get("/api/v1/scheduling/fixtures").cookie(session))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/v1/scheduling/fixtures/feasible/solve").cookie(session))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/v1/scheduling/fixtures/feasible/runs").cookie(session))
        .andExpect(status().isNotFound());
  }

  @Test
  void blockedFixtureRunLeavesRealMasterDataUntouched() throws Exception {
    Cookie session = loginAdmin();

    // 取一间真实教室的现状作为基准（fixture 会把 building 改写成 "P6 Fixture"）。
    Integer pollutedBefore = countFixturePollutedRooms();
    String snapshotBefore = masterDataSnapshot();

    mockMvc.perform(post("/api/v1/scheduling/fixtures/feasible/runs").cookie(session))
        .andExpect(status().isNotFound());

    assertThat(countFixturePollutedRooms())
        .as("被拦下的 fixture 调用不得写入任何 P6 Fixture 教室")
        .isEqualTo(pollutedBefore);
    assertThat(masterDataSnapshot())
        .as("被拦下的 fixture 调用不得改动任何真实教师/教室档案")
        .isEqualTo(snapshotBefore);
  }

  @Test
  void realSchedulingRoutesAreNotCaughtByTheGuard() throws Exception {
    Cookie session = loginAdmin();

    // 守卫只针对 /scheduling/fixtures/**。运行查询走同一控制器，必须仍然可达——
    // 用一个不存在的 runId 换取「404 Schedule run not found」，证明请求真的进了业务逻辑
    // 而不是被守卫在入口拦掉（后者的 message 是 "Not found"）。
    MvcResult result = mockMvc.perform(
            get("/api/v1/scheduling/runs/00000000-0000-0000-0000-0000000000ff").cookie(session))
        .andExpect(status().isNotFound())
        .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .as("应命中业务层的 run 查询，而非脚手架守卫")
        .contains("Schedule run not found");
  }

  // ---------- helpers ----------

  private Integer countFixturePollutedRooms() {
    return jdbcTemplate.queryForObject(
        "select count(*) from classroom where building = 'P6 Fixture'", Integer.class);
  }

  /** 教师与教室关键字段的稳定指纹；任何被 fixture 覆盖写的字段变化都会改变它。 */
  private String masterDataSnapshot() {
    return jdbcTemplate.queryForObject("""
        select coalesce(string_agg(f, '|' order by f), '')
        from (
          select teacher_no || ':' || name || ':' || coalesce(gender,'') || ':' || coalesce(status,'') as f
            from teacher
          union all
          select code || ':' || name || ':' || coalesce(building,'') || ':' || coalesce(exam_capacity::text,'')
            from classroom
        ) s
        """, String.class);
  }

  private Cookie loginAdmin() throws Exception {
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
}
