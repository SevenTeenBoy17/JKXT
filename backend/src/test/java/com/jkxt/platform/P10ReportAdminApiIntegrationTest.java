package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.reporting.service.ReportingService;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P10ReportAdminApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private AuditLogRepository auditLogRepository;

  @Autowired
  private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @Autowired
  private ReportingService reportingService;

  @Test
  void dirtyAllowanceUnitSettingDoesNotBreakAllowanceReports() throws Exception {
    // 迭代9 F6(终审)：organization.settings.allowanceUnit 若为空串/非数字,原 (settings->>'allowanceUnit')::numeric
    // 抛 22P02 → DataIntegrityViolation → teacherHistory/subjectStats/gradeStats 全 400。修复用数字模式守卫回退
    // 120。此测试注入脏设置,断言相关报表仍 200(非 DATA_CONSTRAINT_VIOLATION),finally 还原。
    LoginSession jiaowu = login("jwc.demo", "Demo@2025");
    java.util.UUID orgId = jiaowu.orgId();
    String original = jdbcTemplate.queryForObject(
        "select settings->>'allowanceUnit' from organization where id = ?", String.class, orgId);
    try {
      jdbcTemplate.update(
          "update organization set settings = jsonb_set(coalesce(settings, '{}'::jsonb), '{allowanceUnit}', '\"abc\"'::jsonb) where id = ?",
          orgId);
      mockMvc.perform(get("/api/v1/stats/history/teachers").cookie(jiaowu.cookie()))
          .andExpect(status().isOk());
      mockMvc.perform(get("/api/v1/stats/supervise").param("dim", "subject").cookie(jiaowu.cookie()))
          .andExpect(status().isOk());
    } finally {
      if (original == null) {
        jdbcTemplate.update("update organization set settings = settings - 'allowanceUnit' where id = ?", orgId);
      } else {
        jdbcTemplate.update(
            "update organization set settings = jsonb_set(settings, '{allowanceUnit}', to_jsonb(?::text)) where id = ?",
            original, orgId);
      }
    }
  }

  @Test
  void tenantsReportsTimelineAndAllowanceUseBackendData() throws Exception {
    // Platform-operator endpoints require SUPER_ADMIN; tenant-scoped reports are read by a 教务 ADMIN.
    LoginSession superAdmin = login("admin", "Super001!!!");
    LoginSession jiaowu = login("jwc.demo", "Demo@2025");

    mockMvc.perform(get("/api/v1/admin/tenants").cookie(superAdmin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].uuid").exists())
        .andExpect(jsonPath("$[0].slug").exists())
        .andExpect(jsonPath("$[0].abbr").exists())
        .andExpect(jsonPath("$[0].color").exists())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(7)));

    String firstHistory = mockMvc.perform(get("/api/v1/stats/history/teachers").cookie(jiaowu.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].contributionPct").exists())
        .andExpect(jsonPath("$[0].allowance").exists())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
    String secondHistory = mockMvc.perform(get("/api/v1/stats/history/teachers").cookie(jiaowu.cookie()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
    assertThat(secondHistory).isEqualTo(firstHistory);

    mockMvc.perform(get("/api/v1/stats/supervise").param("dim", "subject").cookie(jiaowu.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].count").exists())
        .andExpect(jsonPath("$.rows[0].allowance").exists());

    MvcResult timelineResult = mockMvc.perform(get("/api/v1/timeline/schedule").cookie(jiaowu.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentDate").exists())
        .andExpect(jsonPath("$.days[0].isoDate").exists())
        .andExpect(jsonPath("$.exams[0].examDate").exists())
        .andExpect(jsonPath("$.calendarDays[0].isoDate").exists())
        .andExpect(jsonPath("$.calendarDays[0].examCount").isNumber())
        .andExpect(jsonPath("$.calendarDays[0].supervisionCount").isNumber())
        .andExpect(jsonPath("$.calendarDays[0].roomCount").isNumber())
        .andExpect(jsonPath("$.availableYears[0]").isNumber())
        .andReturn();

    int apiExamTotal = 0;
    for (JsonNode day : json(timelineResult).path("calendarDays")) {
      apiExamTotal += day.path("examCount").asInt();
    }
    Integer databaseExamTotal = jdbcTemplate.queryForObject("""
        with latest_runs as (
          select distinct on (sr.exam_task_id) sr.id, sr.exam_task_id
          from schedule_run sr
          where sr.org_id = ? and sr.status = 'succeeded'
          order by sr.exam_task_id, sr.finished_at desc nulls last, sr.created_at desc, sr.id desc
        )
        select count(distinct e.id)::int
        from latest_runs lr
        join exam e on e.exam_task_id = lr.exam_task_id
        where e.exam_date is not null
        """, Integer.class, jiaowu.orgId());
    assertThat(apiExamTotal).isPositive().isEqualTo(databaseExamTotal);

    mockMvc.perform(get("/api/v1/teachers").cookie(jiaowu.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].allowance").exists());
  }

  @Test
  void timelineCalendarUsesLatestSucceededRunPerTaskAndRejectsCrossTenantRows() throws Exception {
    LoginSession jiaowu = login("jwc.demo", "Demo@2025");
    UUID orgId = jiaowu.orgId();
    UUID otherOrgId = jdbcTemplate.queryForObject(
        "select id from organization where id <> ? order by id limit 1", UUID.class, orgId);
    UUID taskId = UUID.randomUUID();
    UUID foreignTaskId = UUID.randomUUID();
    UUID emptyOrgId = UUID.randomUUID();
    UUID firstExamId = UUID.randomUUID();
    UUID secondExamId = UUID.randomUUID();
    UUID foreignExamId = UUID.randomUUID();
    UUID foreignClassroomId = UUID.randomUUID();
    UUID oldRunId = UUID.randomUUID();
    UUID latestRunId = UUID.randomUUID();
    UUID mismatchedRunId = UUID.randomUUID();
    LocalDate firstDate = LocalDate.of(2037, 3, 10);
    LocalDate secondDate = LocalDate.of(2037, 3, 11);
    LocalDate foreignDate = LocalDate.of(2037, 3, 12);
    String foreignRoomCode = "XT-" + foreignClassroomId.toString().substring(0, 8);
    List<UUID> teacherIds = jdbcTemplate.queryForList(
        "select id from teacher where org_id = ? order by id limit 2", UUID.class, orgId);
    List<UUID> classroomIds = jdbcTemplate.queryForList(
        "select id from classroom where org_id = ? order by id limit 2", UUID.class, orgId);
    assertThat(teacherIds).hasSize(2);
    assertThat(classroomIds).hasSize(2);

    try {
      jdbcTemplate.update(
          "insert into organization (id, name, slug) values (?, ?, ?)",
          emptyOrgId, "Timeline Empty Fixture", "timeline-empty-" + emptyOrgId.toString().substring(0, 8));
      insertCalendarTask(taskId, orgId, firstDate, secondDate, "tenant");
      insertCalendarTask(foreignTaskId, otherOrgId, foreignDate, foreignDate, "foreign");
      insertCalendarExam(firstExamId, orgId, taskId, firstDate, "语文");
      insertCalendarExam(secondExamId, orgId, taskId, secondDate, "数学");
      insertCalendarExam(foreignExamId, otherOrgId, foreignTaskId, foreignDate, "跨租户数据");
      jdbcTemplate.update("""
          insert into classroom (id, org_id, code, name, capacity, exam_capacity)
          values (?, ?, ?, ?, 30, 30)
          """,
          foreignClassroomId, otherOrgId, foreignRoomCode, "Foreign room " + foreignRoomCode);

      insertSucceededRun(oldRunId, orgId, taskId,
          Instant.parse("2037-03-01T08:00:00Z"), Instant.parse("2037-03-01T08:10:00Z"));
      insertSucceededRun(latestRunId, orgId, taskId,
          Instant.parse("2037-03-02T08:00:00Z"), Instant.parse("2037-03-02T08:10:00Z"));
      // Deliberately mismatched tenant columns are valid under the legacy single-column FKs.
      // The reporting query must reject this run instead of leaking the foreign task's exam.
      insertSucceededRun(mismatchedRunId, orgId, foreignTaskId,
          Instant.parse("2037-03-03T08:00:00Z"), Instant.parse("2037-03-03T08:10:00Z"));

      insertAssignment(oldRunId, firstExamId, orgId, classroomIds.get(0), teacherIds.get(0), firstDate);
      insertAssignment(oldRunId, secondExamId, orgId, classroomIds.get(0), teacherIds.get(0), secondDate);
      insertAssignment(latestRunId, firstExamId, orgId, classroomIds.get(0), teacherIds.get(0), firstDate);
      insertAssignment(latestRunId, firstExamId, orgId, classroomIds.get(1), teacherIds.get(1), firstDate);
      // The assignment row claims this tenant but points to another tenant's classroom.
      // It must not inflate either the supervisor or room totals.
      insertAssignment(latestRunId, firstExamId, orgId, foreignClassroomId, teacherIds.get(0), firstDate);
      insertAssignment(latestRunId, secondExamId, orgId, classroomIds.get(0), teacherIds.get(0), secondDate);

      JsonNode timeline = json(mockMvc.perform(get("/api/v1/timeline/schedule").cookie(jiaowu.cookie()))
          .andExpect(status().isOk())
          .andReturn());
      JsonNode firstDay = calendarDay(timeline, firstDate);
      JsonNode secondDay = calendarDay(timeline, secondDate);
      assertThat(firstDay).isNotNull();
      assertThat(firstDay.path("examCount").asInt()).isEqualTo(1);
      assertThat(firstDay.path("supervisionCount").asInt()).isEqualTo(2);
      assertThat(firstDay.path("roomCount").asInt()).isEqualTo(2);
      assertThat(secondDay).isNotNull();
      assertThat(secondDay.path("examCount").asInt()).isEqualTo(1);
      assertThat(secondDay.path("supervisionCount").asInt()).isEqualTo(1);
      assertThat(secondDay.path("roomCount").asInt()).isEqualTo(1);
      assertThat(calendarDay(timeline, foreignDate)).isNull();
      assertThat(timeline.path("exams").toString()).doesNotContain("跨租户数据");
      JsonNode weeklyFirstExam = timelineExam(timeline, firstExamId);
      assertThat(weeklyFirstExam).isNotNull();
      assertThat(weeklyFirstExam.path("subjectName").asText()).isEqualTo("语文");
      assertThat(weeklyFirstExam.path("supN").asInt()).isEqualTo(2);
      assertThat(weeklyFirstExam.path("room").asText()).doesNotContain(foreignRoomCode);
      JsonNode weeklySecondExam = timelineExam(timeline, secondExamId);
      assertThat(weeklySecondExam).isNotNull();
      assertThat(weeklySecondExam.path("subjectName").asText()).isEqualTo("数学");
      assertThat(timeline.path("availableYears").toString()).contains("2037");

      TenantContext.set(emptyOrgId);
      try {
        Map<String, Object> emptyTimeline = reportingService.timelineSchedule();
        assertThat((List<?>) emptyTimeline.get("calendarDays")).isEmpty();
        assertThat(emptyTimeline.get("availableYears")).isEqualTo(List.of(LocalDate.now().getYear()));
      } finally {
        TenantContext.clear();
      }
    } finally {
      jdbcTemplate.update("delete from exam_task where id in (?, ?)", taskId, foreignTaskId);
      jdbcTemplate.update("delete from classroom where id = ?", foreignClassroomId);
      jdbcTemplate.update("delete from organization where id = ?", emptyOrgId);
    }
  }

  /**
   * PB-19 (round 9, REPORT-01): a newer succeeded run of a task that is not published (a scheduled
   * preview) must not replace the published schedule in history/supervise statistics or in the teacher
   * list workload. Once that task is published, the statistics follow it.
   */
  @Test
  void statisticsFollowThePublishedTaskNotANewerUnpublishedRun() throws Exception {
    LoginSession jiaowu = login("jwc.demo", "Demo@2025");
    UUID orgId = jiaowu.orgId();
    UUID taskId = UUID.randomUUID();
    UUID examId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2038, 4, 20);
    UUID teacherId = jdbcTemplate.queryForObject(
        "select id from teacher where org_id = ? order by teacher_no limit 1", UUID.class, orgId);
    String teacherNo = jdbcTemplate.queryForObject("select teacher_no from teacher where id = ?", String.class, teacherId);
    List<UUID> classroomIds = jdbcTemplate.queryForList(
        "select id from classroom where org_id = ? order by id limit 2", UUID.class, orgId);
    String historyBefore = body(get("/api/v1/stats/history/teachers"), jiaowu);
    String superviseBefore = body(get("/api/v1/stats/supervise").param("dim", "subject"), jiaowu);
    int supCountBefore = supCount(jiaowu, teacherNo);
    try {
      insertCalendarTask(taskId, orgId, date, date, "unpublished");
      insertCalendarExam(examId, orgId, taskId, date, "未发布预览");
      // Finished far in the future so it is the newest succeeded run of the whole school.
      insertSucceededRun(runId, orgId, taskId, Instant.parse("2038-04-01T08:00:00Z"), Instant.parse("2038-04-01T08:10:00Z"));
      insertAssignment(runId, examId, orgId, classroomIds.get(0), teacherId, date);
      insertAssignment(runId, examId, orgId, classroomIds.get(1), teacherId, date);

      assertThat(body(get("/api/v1/stats/history/teachers"), jiaowu)).isEqualTo(historyBefore);
      assertThat(body(get("/api/v1/stats/supervise").param("dim", "subject"), jiaowu))
          .isEqualTo(superviseBefore).doesNotContain("未发布预览");
      assertThat(supCount(jiaowu, teacherNo)).isEqualTo(supCountBefore);

      jdbcTemplate.update("update exam_task set status = 'published', published_at = now() where id = ?", taskId);
      JsonNode published = objectMapper.readTree(body(get("/api/v1/stats/history/teachers"), jiaowu));
      int total = 0;
      for (JsonNode row : published) total += row.path("count").asInt();
      assertThat(total).isEqualTo(2);
      assertThat(body(get("/api/v1/stats/supervise").param("dim", "subject"), jiaowu)).contains("未发布预览");
    } finally {
      jdbcTemplate.update("delete from exam_task where id = ?", taskId);
    }
  }

  private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      LoginSession session) throws Exception {
    return mockMvc.perform(request.cookie(session.cookie()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private int supCount(LoginSession session, String teacherNo) throws Exception {
    for (JsonNode teacher : objectMapper.readTree(body(get("/api/v1/teachers"), session))) {
      if (teacherNo.equals(teacher.path("teacherNo").asText())) return teacher.path("supCount").asInt();
    }
    throw new AssertionError("teacher " + teacherNo + " not listed");
  }

  @Test
  void tenantAdminIsForbiddenFromPlatformAdminEndpoints() throws Exception {
    // Security regression (Epic 2): a tenant 教务 ADMIN must NOT reach the cross-tenant
    // platform-operator surface — previously every authenticated user could.
    LoginSession jiaowu = login("jwc.demo", "Demo@2025");

    mockMvc.perform(get("/api/v1/admin/tenants").cookie(jiaowu.cookie()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    mockMvc.perform(post("/api/v1/admin/security/ip-ban")
            .cookie(jiaowu.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "ip": "203.0.113.9",
                  "reason": "should be blocked for tenant admin"
                }
                """))
        .andExpect(status().isForbidden());
  }

  @Test
  void highRiskSecurityMutationWritesAuditLog() throws Exception {
    LoginSession login = login("admin", "Super001!!!");
    String ip = "198.51.100.77";

    // PB-8 (round 9): IP rules are not enforced anywhere, so the ban/allowlist endpoints report the
    // capability as unavailable (501 FEATURE_NOT_AVAILABLE) and store no "active" rule that never blocks.
    mockMvc.perform(post("/api/v1/admin/security/ip-ban")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "ip": "%s",
                  "reason": "p10 integration test"
                }
                """.formatted(ip)))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("FEATURE_NOT_AVAILABLE"));
    mockMvc.perform(post("/api/v1/admin/security/ip-allowlist")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ip\":\"" + ip + "\",\"reason\":\"p10 integration test\"}"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.code").value("FEATURE_NOT_AVAILABLE"));
    assertThat(jdbcTemplate.queryForObject("select count(*) from ip_rule where ip=?", Integer.class, ip)).isZero();

    // A delivered high-risk platform mutation (settings write) is still audited with severity high.
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/admin/platform-settings")
            .cookie(login.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"emailNotif\": true}"))
        .andExpect(status().isOk());
    assertThat(auditLogRepository.findByOrgId(login.orgId()))
        .anySatisfy(log -> {
          assertThat(log.getAction()).isEqualTo("ADMIN_PLATFORM_SETTINGS_UPDATE");
          assertThat(log.getSeverity()).isEqualTo("high");
        });

    mockMvc.perform(get("/api/v1/admin/audit-logs").cookie(login.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].action").exists());
  }

  @Test
  void disablingAccountRevokesItsActiveSession() throws Exception {
    // 迭代10 P1 回归：禁用账号必须立即撤销其已签发会话。此前 accountAction 的 disable/delete 分支只
    // update status,SessionAuthenticationFilter 又不复查 app_user.status,旧 JKXT_SESSION 在 7 天会话 TTL 内
    // 仍持完整权限。修复后与 reset-password/force-logout 一致调 revokeOtherSessions(accountId, null)。
    LoginSession superAdmin = login("admin", "Super001!!!");
    String username = "iter10.revoke." + java.util.UUID.randomUUID().toString().substring(0, 8);

    // 1) 超管创建一次性账号,拿到一次性初始口令
    MvcResult created = mockMvc.perform(post("/api/v1/admin/accounts")
            .cookie(superAdmin.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username": "%s", "name": "撤销回归账号", "email": "%s@demo.local", "role": "ADMIN"}
                """.formatted(username, username)))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode createdBody = json(created);
    String accountId = createdBody.path("id").asText();
    String initialPassword = createdBody.path("initialPassword").asText();
    assertThat(accountId).isNotBlank();
    assertThat(initialPassword).isNotBlank();

    // 2) 该账号登录,确认会话有效
    LoginSession victim = login(username, initialPassword);
    mockMvc.perform(get("/api/v1/auth/me").cookie(victim.cookie())).andExpect(status().isOk());

    // 3) 超管禁用该账号
    mockMvc.perform(post("/api/v1/admin/accounts/" + accountId + "/disable").cookie(superAdmin.cookie()))
        .andExpect(status().isOk());

    // 4) 旧会话应已被撤销 → 后续请求 401(而非仍旧放行)
    mockMvc.perform(get("/api/v1/auth/me").cookie(victim.cookie())).andExpect(status().isUnauthorized());
  }

  @Test
  void deletedAccountLeavesListAndFreesUsernameAndEmailForReuse() throws Exception {
    // 迭代11b P1 回归：账号"永久删除"必须真软删——从 /admin/accounts 列表消失,且同名/同邮箱可重建。
    // 此前 delete 仅 alias 到 disable(行仍在列表、同名重建撞 (org_id,username) 唯一键 500)。
    // 修复:置 deleted_at + 把 username/email 改写成带 id 后缀墓碑值(保留原唯一约束下释放名额),accounts() 过滤已删。
    LoginSession superAdmin = login("admin", "Super001!!!");
    String username = "iter11b.del." + java.util.UUID.randomUUID().toString().substring(0, 8);
    String email = username + "@demo.local";
    String body = "{\"username\": \"%s\", \"name\": \"软删回归\", \"email\": \"%s\", \"role\": \"ADMIN\"}";

    MvcResult created = mockMvc.perform(post("/api/v1/admin/accounts")
            .cookie(superAdmin.cookie()).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted(username, email)))
        .andExpect(status().isOk())
        .andReturn();
    String accountId = json(created).path("id").asText();

    // 出现在列表
    mockMvc.perform(get("/api/v1/admin/accounts").cookie(superAdmin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$..username", org.hamcrest.Matchers.hasItem(username)));

    // 永久删除
    mockMvc.perform(post("/api/v1/admin/accounts/" + accountId + "/delete").cookie(superAdmin.cookie()))
        .andExpect(status().isOk());

    // 从列表消失(真软删,而非仍以 disabled 回显)
    mockMvc.perform(get("/api/v1/admin/accounts").cookie(superAdmin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$..username", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(username))));

    // 同名 + 同邮箱可重建(名额已释放,不撞唯一键 500)
    mockMvc.perform(post("/api/v1/admin/accounts")
            .cookie(superAdmin.cookie()).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted(username, email)))
        .andExpect(status().isOk());
  }

  @Test
  void deletedAccountIsExcludedFromTenantAdminsCount() throws Exception {
    // 迭代12 P2 回归：「租户列表」的 admins 计数必须与「账号列表」同口径(都排除软删账号)。此前 admins 的
    // count(*) 未过滤 deleted_at —— V13 引入软删后,永久删除的账号仍被计入 admins,而同一台账的账号列表已
    // 把它排除,同一页面两个数字对不上。
    LoginSession superAdmin = login("admin", "Super001!!!");
    int before = totalAdmins(superAdmin);

    String username = "iter12.cnt." + UUID.randomUUID().toString().substring(0, 8);
    String body = "{\"username\": \"%s\", \"name\": \"计数回归\", \"email\": \"%s@demo.local\", \"role\": \"ADMIN\"}";
    MvcResult created = mockMvc.perform(post("/api/v1/admin/accounts")
            .cookie(superAdmin.cookie()).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted(username, username)))
        .andExpect(status().isOk())
        .andReturn();
    String accountId = json(created).path("id").asText();
    assertThat(totalAdmins(superAdmin)).isEqualTo(before + 1);

    mockMvc.perform(post("/api/v1/admin/accounts/" + accountId + "/delete").cookie(superAdmin.cookie()))
        .andExpect(status().isOk());

    // 必须回到原值。修复前这里会是 before + 1(已删账号仍被 admins 计入)。
    assertThat(totalAdmins(superAdmin)).isEqualTo(before);
  }

  /** 各租户 admins 之和 —— 取总和以免依赖新账号落在哪个租户。 */
  private int totalAdmins(LoginSession superAdmin) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/admin/tenants").cookie(superAdmin.cookie()))
        .andExpect(status().isOk())
        .andReturn();
    int total = 0;
    for (JsonNode admins : json(result).findValues("admins")) {
      total += admins.asInt();
    }
    return total;
  }

  private void insertCalendarTask(UUID taskId, UUID orgId, LocalDate startsOn, LocalDate endsOn, String label) {
    jdbcTemplate.update("""
        insert into exam_task (id, org_id, task_no, name, status, starts_on, ends_on)
        values (?, ?, ?, ?, 'scheduled', ?, ?)
        """,
        taskId,
        orgId,
        "timeline-" + label + "-" + taskId.toString().substring(0, 8),
        "Timeline calendar " + label,
        java.sql.Date.valueOf(startsOn),
        java.sql.Date.valueOf(endsOn));
  }

  private void insertCalendarExam(UUID examId, UUID orgId, UUID taskId, LocalDate date, String subjectName) {
    jdbcTemplate.update("""
        insert into exam (
          id, org_id, exam_task_id, subject_name, exam_date, start_time, end_time,
          grade, duration_minutes, room_count
        ) values (?, ?, ?, ?, ?, '09:00', '10:00', '测试年级', 60, 2)
        """,
        examId, orgId, taskId, subjectName, java.sql.Date.valueOf(date));
  }

  private void insertSucceededRun(UUID runId, UUID orgId, UUID taskId, Instant createdAt, Instant finishedAt) {
    jdbcTemplate.update("""
        insert into schedule_run (
          id, org_id, exam_task_id, status, stage, progress, solver,
          stats, logs, started_at, finished_at, created_at, updated_at
        ) values (?, ?, ?, 'succeeded', 'succeeded', 100, 'ortools-cpsat',
                  '{}'::jsonb, '[]'::jsonb, ?, ?, ?, ?)
        """,
        runId,
        orgId,
        taskId,
        Timestamp.from(createdAt),
        Timestamp.from(finishedAt),
        Timestamp.from(createdAt),
        Timestamp.from(finishedAt));
  }

  private void insertAssignment(
      UUID runId,
      UUID examId,
      UUID orgId,
      UUID classroomId,
      UUID teacherId,
      LocalDate examDate
  ) {
    jdbcTemplate.update("""
        insert into schedule_assignment (
          id, org_id, schedule_run_id, exam_id, classroom_id, teacher_id,
          role, exam_date, start_time, end_time, source
        ) values (?, ?, ?, ?, ?, ?, 'main', ?, '09:00', '10:00', 'solver')
        """,
        UUID.randomUUID(),
        orgId,
        runId,
        examId,
        classroomId,
        teacherId,
        java.sql.Date.valueOf(examDate));
  }

  private JsonNode calendarDay(JsonNode timeline, LocalDate date) {
    for (JsonNode day : timeline.path("calendarDays")) {
      if (date.toString().equals(day.path("isoDate").asText())) {
        return day;
      }
    }
    return null;
  }

  private JsonNode timelineExam(JsonNode timeline, UUID examId) {
    for (JsonNode exam : timeline.path("exams")) {
      if (examId.toString().equals(exam.path("id").asText())) {
        return exam;
      }
    }
    return null;
  }

  private LoginSession login(String username, String password) throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult result = mockMvc.perform(post(com.jkxt.support.TestPortal.loginPath(username))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "%s",
                  "password": "%s",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    Cookie session = com.jkxt.support.TestPortal.sessionCookie(result.getResponse());
    assertThat(session).isNotNull();
    JsonNode body = json(result);
    return new LoginSession(session, UUID.fromString(body.path("org").path("id").asText()));
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private record LoginSession(Cookie cookie, UUID orgId) {
  }
}
