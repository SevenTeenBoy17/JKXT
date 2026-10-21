package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A1（P17）细粒度权限矩阵验收：证明鉴权已按【能力(permission)】生效，且四个现役角色【无一被误挡】。
 *
 * <p>矩阵（org 0001，V22 教师本人任务隔离后）：
 * <pre>
 *                | GET /teachers | POST /teachers(写主数据) | POST /tasks/{id}/publish(写排考)
 *   VIEWER       |     200       |         403             |          403
 *   TEACHER_QUERY|     403       |         403             |          403
 *   ADMIN        |     200       |     非403(400 校验)      |         非403
 *   SUPER_ADMIN  |     200       |     非403(400 校验)      |         非403（V15 补业务写）
 * </pre>
 * VIEWER 可读全机构(tenant:read)，TEACHER_QUERY 仅可查询本人任务(duty:self)，两者均不能写业务数据；
 * ADMIN/SUPER_ADMIN 过安全门后才碰业务校验
 * （非 403 即证明安全门放行）。SUPER_ADMIN 的业务写放行验证「超管可代管租户业务写」这一产品决策。
 * 关键防回归点：VIEWER 保留机构读取权限，TEACHER_QUERY 不得重新获得全机构读取权限，且本人任务入口仍可用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RbacPermissionMatrixAcceptanceTest {

  private static final String VIEWER = "matrix.viewer";
  private static final String TEACHER_QUERY = "matrix.teacher";
  private static final String RUNTIME_ADMIN = "matrix.runtime.admin";
  private static final String PW = "Matrix@2025";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("delete from app_user where username in (?, ?, ?)", VIEWER, TEACHER_QUERY, RUNTIME_ADMIN);
  }

  @Test
  void userInRuntimeCreatedTenantWithoutSeededRolesStillResolvesPermissions() throws Exception {
    // 复现对抗审查 P1：createTenant 只插 organization、不建 role 行 → 该 org 的 app_user 权限解析为空 →
    // A1 的 hasAuthority 门下读写全 403（整个 SaaS 运行时租户被锁死）。这里直接造出「有 org、有 ADMIN 用户、
    // 但无任何 role 行」的等价状态，验证 permissions() 的规范回落救场。
    UUID orgId = UUID.randomUUID();
    jdbcTemplate.update("insert into organization (id, name, slug) values (?, ?, ?)",
        orgId, "Runtime Tenant", "runtime-" + orgId.toString().substring(0, 8));
    try {
      seedUser(orgId, RUNTIME_ADMIN, "ADMIN");
      Integer roleRows = jdbcTemplate.queryForObject(
          "select count(*) from role where org_id = ?", Integer.class, orgId);
      assertThat(roleRows).as("模拟运行时租户应无任何 role 行").isZero();

      Cookie c = login(RUNTIME_ADMIN, PW);
      // 无回落时 permissions=[] → 读 403；有回落（借规范 ADMIN 集，含 tenant:read）→ 200。
      assertThat(getStatus(c, "/api/v1/teachers?page=1&size=1"))
          .as("新租户 ADMIN 应能读（权限回落到规范 ADMIN 集）").isEqualTo(200);
      // 业务写过安全门（回落集含 masterdata:manage）→ 非 403。
      assertThat(postStatus(c, "/api/v1/teachers", "{}"))
          .as("新租户 ADMIN 写主数据应过安全门（非 403）").isNotEqualTo(403);
    } finally {
      jdbcTemplate.update("delete from app_user where username = ?", RUNTIME_ADMIN);
      jdbcTemplate.update("delete from organization where id = ?", orgId);
    }
  }

  @Test
  void permissionMatrixHoldsForAllFourRoles() throws Exception {
    UUID orgId = jdbcTemplate.queryForObject(
        "select org_id from app_user where username = 'jwc.demo'", UUID.class);
    seedUser(orgId, VIEWER, "VIEWER");
    seedUser(orgId, TEACHER_QUERY, "TEACHER_QUERY");
    String someTask = UUID.randomUUID().toString();

    // ---- V22: VIEWER 可读机构数据，TEACHER_QUERY 仅本人任务；两类写都被挡 ----
    for (String reader : new String[] {VIEWER, TEACHER_QUERY}) {
      Cookie c = login(reader, PW);
      int expectedReadStatus = TEACHER_QUERY.equals(reader) ? 403 : 200;
      assertThat(getStatus(c, "/api/v1/teachers?page=1&size=1"))
          .as("%s 的机构教师名册权限遵守 V22 隔离范围", reader).isEqualTo(expectedReadStatus);
      assertThat(postStatus(c, "/api/v1/teachers", "{}"))
          .as("%s 写主数据应 403(无 masterdata:manage)", reader).isEqualTo(403);
      assertThat(postStatus(c, "/api/v1/tasks/" + someTask + "/publish", null))
          .as("%s 写排考应 403(无 schedule:manage)", reader).isEqualTo(403);
      if (TEACHER_QUERY.equals(reader)) {
        var me = objectMapper.readTree(mockMvc.perform(get("/api/v1/auth/me").cookie(c))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String[] permissions = objectMapper.convertValue(me.path("permissions"), String[].class);
        assertThat(permissions).contains("duty:self")
            .doesNotContain("tenant:read", "masterdata:manage", "schedule:manage");
        var ownDuties = objectMapper.readTree(mockMvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(c))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ownDuties.isArray()).isTrue();
        assertThat(ownDuties.size()).as("未关联教师档案的账号不能读取其他教师任务").isZero();
        assertThat(getStatus(c, "/api/v1/exam-plans"))
            .as("教师不得读取机构考务计划").isEqualTo(403);
      }
    }

    // ---- ADMIN / SUPER_ADMIN：可读，且两类写都过安全门(非 403) ----
    for (String[] admin : new String[][] {{"jwc.demo", "Demo@2025"}, {"admin", "Super001!!!"}}) {
      Cookie c = login(admin[0], admin[1]);
      assertThat(getStatus(c, "/api/v1/teachers?page=1&size=1"))
          .as("%s 读 teachers 应 200", admin[0]).isEqualTo(200);
      assertThat(postStatus(c, "/api/v1/teachers", "{}"))
          .as("%s 写主数据应过安全门(非 403，空体触发 400 校验)", admin[0]).isNotEqualTo(403);
      assertThat(postStatus(c, "/api/v1/tasks/" + someTask + "/publish", null))
          .as("%s 写排考应过安全门(非 403)", admin[0]).isNotEqualTo(403);
    }

    // SUPER_ADMIN 平台台访问不受本次改动影响。
    assertThat(getStatus(login("admin", "Super001!!!"), "/api/v1/admin/tenants"))
        .as("SUPER_ADMIN 仍可访问平台台").isEqualTo(200);
  }

  private void seedUser(UUID orgId, String username, String role) {
    jdbcTemplate.update("""
        insert into app_user
          (id, org_id, username, display_name, email, phone, password_hash, role, status,
           must_change_password, onboarded)
        values (?, ?, ?, ?, ?, ?, ?, ?, 'active', false, true)
        """,
        UUID.randomUUID(), orgId, username, username, username + "@test.local",
        "139" + Math.abs(username.hashCode() % 100000000), passwordEncoder.encode(PW), role);
  }

  private int getStatus(Cookie session, String path) throws Exception {
    return mockMvc.perform(get(path).cookie(session)).andReturn().getResponse().getStatus();
  }

  private int postStatus(Cookie session, String path, String body) throws Exception {
    var req = post(path).cookie(session);
    if (body != null) {
      req = req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(req).andReturn().getResponse().getStatus();
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult login = mockMvc.perform(post(com.jkxt.support.TestPortal.loginPath(username))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"%s","password":"%s","captchaId":"%s","captcha":"%s"}
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    return com.jkxt.support.TestPortal.sessionCookie(login.getResponse());
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }
}
