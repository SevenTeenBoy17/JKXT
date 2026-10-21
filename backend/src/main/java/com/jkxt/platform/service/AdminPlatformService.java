package com.jkxt.platform.service;

import com.jkxt.audit.domain.AuditLog;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.security.auth.SessionPrincipal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class AdminPlatformService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  // 迭代12 A4: 超管在 SecurityContext 里的权限名。SessionAuthenticationFilter 授予的是
  // "ROLE_" + principal.role()，故超管形态为 ROLE_SUPER_ADMIN，与 @PreAuthorize("hasRole('SUPER_ADMIN')")
  // 及 SecurityConfig 的 /admin/** hasRole("SUPER_ADMIN") 完全一致。
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminPlatformService.class);
  private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
  /** 备份产物目录：与 docker-compose 的 backup 服务共享同一卷（app 侧挂载点）。 */
  private static final String BACKUP_DIR = "/var/backups/jkxt";

  // 迭代12 B4: 列表与按 id 单查共用同一段 select/from/where 骨架，by-id 只在骨架与作用域子句之后追加
  // id 条件——结构上不可能漏掉列表侧的过滤语义(type 白名单 / 软删过滤 / private 模式组织锁定)。
  private static final String TENANT_SELECT = """
      select o.id, o.slug, o.name, o.type, o.plan, o.status, o.region, o.settings, o.contact_name,
             o.contact_phone, o.created_at,
             coalesce((o.settings->>'abbr'), upper(left(o.slug, 3))) as abbr,
             coalesce((o.settings->>'displayType'), o.type) as display_type,
             coalesce((o.settings->>'color'), '#4F46E5') as color,
             coalesce((o.settings->>'students')::int, 0) as students,
             coalesce((o.settings->>'adminCap')::int, 0) as admin_cap,
             -- 必须与 accountScopeClause() 的 deleted_at is null 同口径：否则「租户列表」的 admins 会把
             -- 已删账号继续计入，而同一台账的「账号列表」已把它排除，同一页面两个数字对不上。
             (select count(*) from app_user u where u.org_id = o.id and u.deleted_at is null)::int as admins
      from organization o
      where o.type in ('school', 'group')
      """;

  private static final String ACCOUNT_SELECT = """
      select u.id, u.username, u.display_name, u.email, u.phone, u.role, u.status, u.must_change_password,
             u.last_login_at, u.login_count, o.slug as tenant_slug, o.name as tenant_name
      from app_user u
      left join organization o on o.id = u.org_id
      """;

  private static final String ANNOUNCEMENT_SELECT = """
      select id, title, body, severity, target, status, publish_at, expire_at, created_at
      from announcement
      """;

  private final JdbcTemplate jdbcTemplate;
  private final AuditLogRepository auditLogRepository;
  private final DeployProperties deployProperties;
  private final PasswordEncoder passwordEncoder;
  private final com.jkxt.security.auth.SessionService sessionService;
  private final org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry;

  public AdminPlatformService(
      JdbcTemplate jdbcTemplate,
      AuditLogRepository auditLogRepository,
      DeployProperties deployProperties,
      PasswordEncoder passwordEncoder,
      com.jkxt.security.auth.SessionService sessionService,
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry
  ) {
    this.sessionService = sessionService;
    this.meterRegistry = meterRegistry;
    this.jdbcTemplate = jdbcTemplate;
    this.auditLogRepository = auditLogRepository;
    this.deployProperties = deployProperties;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> tenants() {
    return jdbcTemplate.query(
        TENANT_SELECT + tenantScopeClause() + "order by o.created_at asc, o.slug asc",
        tenantScopeArgs(),
        tenantRowMapper()
    );
  }

  /** tenants()/tenantById() 共用的作用域子句：private 模式锁定到固定组织。 */
  private String tenantScopeClause() {
    return deployProperties.isPrivate() ? "and o.id = ?\n" : "";
  }

  private Object[] tenantScopeArgs() {
    return deployProperties.isPrivate() ? new Object[] { deployProperties.fixedOrgUuid() } : new Object[] {};
  }

  /**
   * 把 by-id 的 id 追加在作用域实参之后。by-id 必须用它拼参，不要手搓 —— 只有让子句(*ScopeClause)与实参
   * (*ScopeArgs)由同一个分支派生，日后给子句加第二个占位符时两侧才会一起变；否则 by-id 会静默留在旧参数
   * 形态，直到运行时占位符数与实参数不匹配才炸。
   */
  private static Object[] withId(Object[] scopeArgs, UUID id) {
    Object[] args = Arrays.copyOf(scopeArgs, scopeArgs.length + 1);
    args[scopeArgs.length] = id;
    return args;
  }

  private RowMapper<Map<String, Object>> tenantRowMapper() {
    return (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      UUID id = rs.getObject("id", UUID.class);
      String plan = rs.getString("plan");
      String dbStatus = rs.getString("status");
      String uiStatus = "suspended".equals(dbStatus) ? "suspended" : "trial".equals(plan) ? "trial" : "active";
      row.put("uuid", id);
      row.put("id", rs.getString("slug"));
      row.put("slug", rs.getString("slug"));
      row.put("name", rs.getString("name"));
      row.put("type", rs.getString("display_type"));
      row.put("plan", plan);
      row.put("admins", rs.getInt("admins"));
      row.put("students", rs.getInt("students"));
      row.put("status", uiStatus);
      row.put("dbStatus", dbStatus);
      row.put("createdAt", dateOnly(rs.getTimestamp("created_at")));
      row.put("city", cityFromJsonText(String.valueOf(rs.getObject("region"))));
      row.put("abbr", rs.getString("abbr"));
      row.put("active", "active".equals(uiStatus));
      row.put("color", rs.getString("color"));
      row.put("contactName", rs.getString("contact_name"));
      row.put("contactPhone", rs.getString("contact_phone"));
      row.put("adminCap", rs.getInt("admin_cap"));
      return row;
    };
  }

  public Map<String, Object> createTenant(Map<String, Object> request) {
    requireSuperAdmin();
    rejectCrossTenantMutationInPrivateMode(null);
    String name = string(request, "name", "New Tenant " + Instant.now().toEpochMilli());
    String slug = string(request, "slug", slug(name));
    UUID id = jdbcTemplate.queryForObject("""
        insert into organization (name, slug, type, plan, status, region, settings, contact_name, contact_phone)
        values (?, ?, 'school', ?, 'active', ?::jsonb, ?::jsonb, ?, ?)
        on conflict (slug) do update set
          name = excluded.name,
          plan = excluded.plan,
          region = excluded.region,
          settings = excluded.settings,
          updated_at = now()
        returning id
        """,
        UUID.class,
        name,
        slug,
        normalizePlan(string(request, "plan", "trial")),
        "{\"city\":\"" + escapeJson(string(request, "city", "Tianjin")) + "\"}",
        "{\"abbr\":\"" + escapeJson(string(request, "abbr", slug.toUpperCase(Locale.ROOT).substring(0, Math.min(3, slug.length())))) + "\",\"displayType\":\"School\",\"color\":\"#4F46E5\",\"students\":" + number(request, "students", 0) + "}",
        string(request, "contactName", null),
        string(request, "contactPhone", null)
    );
    seedInitialTerm(id);
    audit("ADMIN_TENANT_CREATE", "tenant", id, "warn", Map.of("slug", slug, "name", name));
    return tenantById(id);
  }

  /**
   * 给新租户建一个当前学期。此前新建租户的 academic_term 为空，/auth/me 的 term 恒为 null，
   * 顶栏学期徽章与报表口径开箱即处于"未设置"状态。
   *
   * <p>两条约束决定了这里必须是幂等的"仅在空时播种"，而不是直接 insert：
   * 上面的 upsert 用了 {@code on conflict (slug) do update}，因此 createTenant 可能命中一个
   * 已存在、已有学期的租户；且 V17 对 {@code (org_id) where is_current} 建了唯一索引，
   * 再插一条 is_current 会直接违约。
   */
  private void seedInitialTerm(UUID orgId) {
    LocalDate today = LocalDate.now();
    // 国内学制：秋季学期(S1)约 9 月开学跨至次年 1 月，春季学期(S2)约 2 月至 7 月。
    boolean autumn = today.getMonthValue() >= 8;
    int startYear = autumn ? today.getYear() : today.getYear() - 1;
    String yearLabel = startYear + "-" + (startYear + 1);
    String term = autumn ? "S1" : "S2";
    String name = yearLabel + "学年" + (autumn ? "第一学期" : "第二学期");
    LocalDate start = autumn ? LocalDate.of(startYear, 9, 1) : LocalDate.of(startYear + 1, 2, 1);
    LocalDate end = autumn ? LocalDate.of(startYear + 1, 1, 31) : LocalDate.of(startYear + 1, 7, 15);

    jdbcTemplate.update("""
        insert into academic_term (org_id, year_label, term, name, start_date, end_date, is_current)
        select ?, ?, ?, ?, ?, ?, true
        where not exists (select 1 from academic_term where org_id = ?)
        """,
        orgId, yearLabel, term, name, Date.valueOf(start), Date.valueOf(end), orgId);
  }

  public Map<String, Object> updateTenant(UUID tenantId, Map<String, Object> request) {
    requireSuperAdmin();
    rejectCrossTenantMutationInPrivateMode(tenantId);
    jdbcTemplate.update("""
        update organization
        set name = coalesce(?, name),
            contact_name = coalesce(?, contact_name),
            contact_phone = coalesce(?, contact_phone),
            plan = coalesce(?, plan),
            updated_at = now()
        where id = ?
        """,
        blankToNull(string(request, "name", null)),
        blankToNull(string(request, "contactName", null)),
        blankToNull(string(request, "contactPhone", null)),
        blankToNull(normalizePlan(string(request, "plan", null))),
        tenantId
    );
    audit("ADMIN_TENANT_UPDATE", "tenant", tenantId, "warn", Map.of("fields", List.copyOf(request.keySet())));
    return tenantById(tenantId);
  }

  public Map<String, Object> setTenantStatus(UUID tenantId, String status) {
    requireSuperAdmin();
    rejectCrossTenantMutationInPrivateMode(tenantId);
    String dbStatus = "restore".equals(status) || "active".equals(status) ? "active" : "suspended";
    int updated = jdbcTemplate.update("update organization set status = ?, updated_at = now() where id = ?", dbStatus, tenantId);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
    }
    audit("suspended".equals(dbStatus) ? "ADMIN_TENANT_SUSPEND" : "ADMIN_TENANT_RESTORE", "tenant", tenantId, "high", Map.of("status", dbStatus));
    if ("suspended".equals(dbStatus)) {
      // Platform operators keep only their recovery access; the request filter blocks school business.
      // 迭代11b P1: 停用租户后,该租户用户已签发的 JKXT_SESSION 在 7 天会话 TTL 内仍持完整权限(登录期
      // TENANT_SUSPENDED 只拦新登录,不撤旧会话)——suspend 对活跃用户形同虚设。与账号 disable/delete 一致,
      // 逐一撤销该租户所有用户的全部会话,真正即时切断。
      List<UUID> tenantUserIds = jdbcTemplate.queryForList(
          "select id from app_user where org_id = ? and deleted_at is null and role <> 'SUPER_ADMIN'", UUID.class, tenantId);
      for (UUID userId : tenantUserIds) {
        sessionService.revokeOtherSessions(userId, null);
      }
    }
    return tenantById(tenantId);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> accounts() {
    return jdbcTemplate.query(
        ACCOUNT_SELECT + accountScopeClause() + "order by u.created_at asc",
        accountScopeArgs(),
        accountRowMapper()
    );
  }

  /** accounts()/accountById() 共用的作用域子句：过滤软删账号(deleted_at 非空)——否则"永久删除"后仍
   * 回显在列表里(迭代11b P1)；private 模式再锁定到固定组织。 */
  private String accountScopeClause() {
    return deployProperties.isPrivate()
        ? "where u.org_id = ? and u.deleted_at is null\n"
        : "where u.deleted_at is null\n";
  }

  private Object[] accountScopeArgs() {
    return deployProperties.isPrivate() ? new Object[] { deployProperties.fixedOrgUuid() } : new Object[] {};
  }

  private RowMapper<Map<String, Object>> accountRowMapper() {
    return (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("uuid", rs.getObject("id", UUID.class));
      row.put("id", rs.getObject("id", UUID.class).toString());
      row.put("username", rs.getString("username"));
      row.put("name", rs.getString("display_name"));
      row.put("email", rs.getString("email"));
      row.put("phone", rs.getString("phone"));
      row.put("tenant", rs.getString("tenant_slug") == null ? "PLATFORM" : rs.getString("tenant_slug").toUpperCase(Locale.ROOT));
      row.put("tenantName", rs.getString("tenant_name"));
      row.put("department", roleDepartment(rs.getString("role")));
      row.put("role", uiRole(rs.getString("role")));
      row.put("status", uiAccountStatus(rs.getString("status"), rs.getTimestamp("last_login_at")));
      // Initial-password policy is not MFA enrollment; MFA is not implemented.
      row.put("twoFactor", null);
      row.put("twoFactorSupported", false);
      row.put("mustChangePassword", rs.getBoolean("must_change_password"));
      row.put("lastLogin", dateTime(rs.getTimestamp("last_login_at")));
      row.put("loginCount", rs.getInt("login_count"));
      return row;
    };
  }

  public Map<String, Object> createAccount(Map<String, Object> request) {
    requireSuperAdmin();
    UUID tenantId = tenantIdFromRequest(request);
    rejectCrossTenantMutationInPrivateMode(tenantId);
    String role = backendRole(string(request, "role", "ADMIN"));
    UUID teacherId = teacherBinding(request, tenantId, role);
    String email = string(request, "email", "admin-" + Instant.now().toEpochMilli() + "@jkxt.local");
    String username = string(request, "username", email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length()));
    // Serialize account creation within a school: its case-insensitive login identity must be unique.
    List<UUID> tenantLock = jdbcTemplate.queryForList("select id from organization where id=? for update", UUID.class, tenantId);
    if (tenantLock.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
    Integer duplicate = jdbcTemplate.queryForObject(
        "select count(*) from app_user where org_id=? and lower(username)=lower(?) and deleted_at is null",
        Integer.class, tenantId, username);
    if (duplicate != null && duplicate > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists in this school");
    }
    // 迭代9 F1(P0 终审)：原先写死同一 bcrypt 哈希=共享已知口令(安全洞)；上一版改随机 UUID 又让账号
    // 永不可登录(无落密路径,changePassword 需当前口令而当前口令无人知晓)。正解：生成一次性初始口令、
    // hash 入库、must_change=true，并把明文**一次性随本次创建响应返回**给超管转交(本系统 on-prem 无邮件
    // 通道)。用户首次登录后被引导改密。
    String initialPassword = generateTempPassword();
    UUID id = jdbcTemplate.queryForObject("""
        insert into app_user (org_id, username, display_name, email, phone, password_hash, role, status, must_change_password, onboarded)
        values (?, ?, ?, ?, ?, ?, ?, 'active', true, true)
        returning id
        """,
        UUID.class,
        tenantId,
        username,
        string(request, "name", username),
        email,
        string(request, "phone", null),
        passwordEncoder.encode(initialPassword),
        role
    );
    if (teacherId != null) {
      // The selected teacher is locked by teacherBinding. Account creation and binding
      // share this service transaction, so a failed binding cannot leave an orphan account.
      int bound = jdbcTemplate.update("""
          update teacher set app_user_id=?, updated_at=now()
          where id=? and org_id=? and app_user_id is null and status='active'
          """, id, teacherId, tenantId);
      if (bound != 1) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Teacher account binding changed; refresh and retry");
      }
      audit("ADMIN_TEACHER_ACCOUNT_BIND", "teacher", teacherId, "high", Map.of("accountId", id.toString()));
    }
    audit("ADMIN_ACCOUNT_CREATE", "account", id, "warn", Map.of("email", email));
    Map<String, Object> result = new LinkedHashMap<>(accountById(id));
    result.put("initialPassword", initialPassword);   // 一次性明文，仅本次创建响应携带
    return result;
  }

  private UUID teacherBinding(Map<String, Object> request, UUID tenantId, String role) {
    String value = string(request, "teacherId", null);
    if (value == null || value.isBlank()) return null;
    if (!"TEACHER_QUERY".equals(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a teacher-query account may bind a teacher");
    }
    UUID teacherId;
    try {
      teacherId = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid teacherId");
    }
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        select app_user_id from teacher where id=? and org_id=? and status='active' for update
        """, teacherId, tenantId);
    if (rows.isEmpty()) {
      // Do not reveal whether a teacher exists in another school.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active teacher not found in this school");
    }
    if (rows.get(0).get("app_user_id") != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Teacher already has an account");
    }
    return teacherId;
  }

  public Map<String, Object> accountAction(UUID accountId, String action) {
    requireSuperAdmin();
    rejectCrossTenantMutationInPrivateMode(accountOrgId(accountId));
    String auditAction = switch (action) {
      case "reset-password" -> "ADMIN_ACCOUNT_RESET_PASSWORD";
      case "force-logout" -> "ADMIN_ACCOUNT_FORCE_LOGOUT";
      case "disable" -> "ADMIN_ACCOUNT_DISABLE";
      case "delete" -> "ADMIN_ACCOUNT_DELETE";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported account action");
    };
    // 迭代9 F1(P0 终审)：reset-password/force-logout 此前是纯审计空动作(前端还谎报"已发送临时密码")。
    // 改为真副作用。
    String tempPassword = null;
    switch (action) {
      case "reset-password" -> {
        tempPassword = generateTempPassword();
        jdbcTemplate.update(
            "update app_user set password_hash = ?, must_change_password = true, failed_attempts = 0, "
                + "locked_until = null, updated_at = now() where id = ?",
            passwordEncoder.encode(tempPassword), accountId);
        sessionService.revokeOtherSessions(accountId, null); // 撤销该用户全部会话
      }
      case "force-logout" -> {
        jdbcTemplate.update("update app_user set auth_version=auth_version+1 where id=?", accountId);
        sessionService.revokeOtherSessions(accountId, null);
      }
      case "disable" -> {
        jdbcTemplate.update("update app_user set status = 'disabled', updated_at = now() where id = ?", accountId);
        // 迭代10 P1: 禁用必须立即撤销该账号全部会话，否则被停用/离职/被盗账号仍持已签发的 JKXT_SESSION，
        // 在 7 天会话 TTL 内继续拥有完整角色权限(增删主数据/编辑发布排班)，禁用形同虚设。
        sessionService.revokeOtherSessions(accountId, null);
      }
      case "delete" -> {
        // 迭代11b P1: "永久删除"此前只是 disable(行从不真删)，前端却谎称"永久删除、无法恢复"，且同名/同邮箱
        // 账号因唯一约束无法重建。改为真软删：置 deleted_at 标记；把 username/email 改写成带 id 后缀的墓碑值,
        // 从而在保留原唯一约束(ON CONFLICT 依赖)的前提下**释放原用户名/邮箱名额**、可重建；状态置 disabled
        // 使其无法登录；撤销全部会话。accounts() 已过滤 deleted_at 非空行不再回显。不硬删(RESTRICT 外键会 500)。
        // username varchar(60): left(.,18)+'#del-'+36 uuid=59≤60; email varchar(120): left(.,40)+'#del-'+36=81≤120。
        jdbcTemplate.update(
            "update app_user set status = 'disabled', deleted_at = now(), "
                + "username = left(username, 18) || '#del-' || id::text, "
                + "email = case when email is null then null else left(email, 40) || '#del-' || id::text end, "
                + "updated_at = now() where id = ?",
            accountId);
        sessionService.revokeOtherSessions(accountId, null);
      }
      default -> { }
    }
    audit(auditAction, "account", accountId, "high", Map.of("action", action));
    if ("delete".equals(action)) {
      // 迭代11b P1(自纠)：账号已软删(deleted_at 置位),accountById 经 accounts() 过滤已删行会抛 404——
      // 即删除其实成功、却回 404 误导前端"删除失败"。删除动作返回极简成功结果,不再回查已删行。
      return Map.of("id", accountId.toString(), "status", "deleted");
    }
    Map<String, Object> result = new LinkedHashMap<>(accountById(accountId));
    if (tempPassword != null) {
      result.put("tempPassword", tempPassword);   // 一次性明文，仅本次重置响应携带
    }
    return result;
  }

  /** 生成一次性可读初始/临时口令(10 位,大小写字母+数字,去易混字符 Il0O1)。明文仅随本次响应一次性返回、
   * 入库仅存 bcrypt。长度≥8 满足后续 changePassword 校验。 */
  private String generateTempPassword() {
    String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    java.security.SecureRandom rnd = new java.security.SecureRandom();
    StringBuilder sb = new StringBuilder(10);
    for (int i = 0; i < 10; i++) {
      sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
    }
    return sb.toString();
  }

  /**
   * P18 批次7b：平台健康度真实指标。超管仪表盘的「平台健康度」面板此前是写死的示例
   * （数据库连接 14/50、今日 API 调用 7.1万——全是编造），面板虽已标注"示例"，但正确做法是给真值。
   * 进程内读 MeterRegistry（不经 HTTP 暴露 actuator metrics 面）：HikariCP 连接池、JVM 堆、运行时长、
   * 自启动以来的 HTTP 请求数。取不到的指标如实返回 null，前端据此显示「暂无数据」而非编数。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> systemHealth() {
    requireSuperAdmin();
    Map<String, Object> out = new LinkedHashMap<>();
    io.micrometer.core.instrument.MeterRegistry registry = meterRegistry.getIfAvailable();
    out.put("dbPoolActive", gaugeValue(registry, "hikaricp.connections.active"));
    out.put("dbPoolIdle", gaugeValue(registry, "hikaricp.connections.idle"));
    out.put("dbPoolMax", gaugeValue(registry, "hikaricp.connections.max"));
    out.put("httpRequestsTotal", timerCount(registry, "http.server.requests"));
    // 7c：CPU 与磁盘——Boot 自动绑定的 ProcessorMetrics/DiskSpaceMetrics gauge（0..1 的占比、字节数）。
    // 容器内读到的是 cgroup 视角的宿主/容器值，是真实运行环境数字；读不到（如首采样 NaN）则为 null。
    out.put("cpuSystem", gaugeValue(registry, "system.cpu.usage"));
    out.put("cpuProcess", gaugeValue(registry, "process.cpu.usage"));
    out.put("cpuCount", gaugeValue(registry, "system.cpu.count"));
    out.put("diskFreeBytes", gaugeValue(registry, "disk.free"));
    out.put("diskTotalBytes", gaugeValue(registry, "disk.total"));
    Runtime rt = Runtime.getRuntime();
    out.put("heapUsedMb", Math.round((rt.totalMemory() - rt.freeMemory()) / 1048576.0));
    out.put("heapMaxMb", Math.round(rt.maxMemory() / 1048576.0));
    out.put("uptimeSeconds", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    return out;
  }

  private static Double gaugeValue(io.micrometer.core.instrument.MeterRegistry registry, String name) {
    if (registry == null) return null;
    try {
      io.micrometer.core.instrument.Gauge g = registry.find(name).gauge();
      if (g == null) return null;
      double v = g.value();
      // These gauges are counts, sizes or CPU proportions. MXBean may report -1
      // when a sample is unavailable; expose absence instead of a fabricated load.
      return Double.isFinite(v) && v >= 0 ? v : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static Long timerCount(io.micrometer.core.instrument.MeterRegistry registry, String name) {
    if (registry == null) return null;
    try {
      long total = 0; boolean found = false;
      for (io.micrometer.core.instrument.Timer t : registry.find(name).timers()) { total += t.count(); found = true; }
      return found ? total : null;
    } catch (Exception e) {
      return null;
    }
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> auditLogs(Integer hours) {
    // P18 批次7b：可选时间窗（近 N 小时）。null/非法值 = 不过滤，保持既有行为。
    String windowClause = hours != null && hours > 0
        ? "where l.created_at >= now() - make_interval(hours => ?) "
        : "";
    Object[] args = hours != null && hours > 0 ? new Object[] { hours } : new Object[] {};
    return jdbcTemplate.query("""
        select l.id, l.action, l.target_type, l.target_id, l.severity, l.created_at, l.payload,
               u.display_name as actor, o.slug as tenant
        from audit_log l
        left join app_user u on u.id = l.actor_id
        left join organization o on o.id = l.org_id
        """ + windowClause + """
        order by l.created_at desc
        limit 200
        """, args, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("uuid", rs.getObject("id", UUID.class));
      row.put("id", "L" + String.format("%04d", rowNum + 1));
      row.put("actor", rs.getString("actor") == null ? "System" : rs.getString("actor"));
      row.put("tenant", rs.getString("tenant") == null ? "-" : rs.getString("tenant").toUpperCase(Locale.ROOT));
      row.put("action", rs.getString("action"));
      row.put("target", rs.getString("target_type"));
      row.put("targetId", rs.getObject("target_id"));
      row.put("severity", rs.getString("severity"));
      row.put("ip", "-");
      row.put("at", dateTime(rs.getTimestamp("created_at")));
      row.put("payload", String.valueOf(rs.getObject("payload")));
      return row;
    });
  }

  @Transactional(readOnly = true)
  public Map<String, Object> securityOverview() {
    int failed = count("select count(*) from audit_log where action like 'AUTH_LOGIN_%' and severity in ('warn','high')");
    int suspiciousIps = count("select count(*) from security_event where status = 'open'");
    int activeRules = count("select count(*) from ip_rule where status = 'active'");
    String lastBackup = jdbcTemplate.query("""
        select finished_at from backup_record where status = 'succeeded' order by finished_at desc nulls last limit 1
        """, rs -> rs.next() ? dateTime(rs.getTimestamp(1)) : "No backup");
    int score = Math.max(60, 100 - suspiciousIps * 6 - activeRules * 2);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("score", score);
    response.put("failedLogins", failed);
    response.put("suspiciousIps", suspiciousIps);
    response.put("activeRules", activeRules);
    response.put("lastBackup", lastBackup);
    response.put("threats", securityThreats());
    response.put("backups", backups());
    return response;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> securityThreats() {
    return jdbcTemplate.query("""
        select ip, country, attempts, severity, last_at
        from security_event
        where status = 'open'
        order by severity desc, attempts desc, last_at desc
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("ip", rs.getString("ip"));
      row.put("country", rs.getString("country"));
      row.put("attempts", rs.getInt("attempts"));
      row.put("severity", rs.getString("severity"));
      row.put("lastAt", dateTime(rs.getTimestamp("last_at")));
      return row;
    });
  }

  public Map<String, Object> addIpRule(String action, Map<String, Object> request) {
    requireSuperAdmin();
    // PB-8 (round 9, ADR-08 / OPS-06): nothing enforces ip_rule (no gateway/filter integration), so
    // saving a rule that "looks active" is a false success. Until enforcement is delivered the API
    // states the capability is unavailable and persists nothing.
    if (!IP_RULE_ENFORCEMENT_AVAILABLE) {
      throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "FEATURE_NOT_AVAILABLE",
          "IP " + ("allowlist".equals(action) ? "allowlist" : "ban")
              + " rules are not enforced by this deployment; configure the firewall/gateway instead");
    }
    String ruleType = "allowlist".equals(action) ? "allow" : "ban";
    String ip = string(request, "ip", null);
    if (ip == null || ip.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ip is required");
    }
    UUID id = jdbcTemplate.queryForObject("""
        insert into ip_rule (rule_type, ip, reason, status, created_by, payload)
        values (?, ?, ?, 'active', ?, ?::jsonb)
        on conflict (rule_type, ip, status) do update set
          reason = excluded.reason,
          updated_at = now()
        returning id
        """,
        UUID.class,
        ruleType,
        ip,
        string(request, "reason", "manual-admin-action"),
        actorId(),
        "{\"source\":\"admin\"}"
    );
    String auditAction = "ban".equals(ruleType) ? "ADMIN_SECURITY_IP_BAN" : "ADMIN_SECURITY_IP_ALLOWLIST";
    audit(auditAction, "ip_rule", id, "high", Map.of("ip", ip, "ruleType", ruleType));
    return Map.of("id", id, "ip", ip, "ruleType", ruleType, "status", "active");
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> backups() {
    return jdbcTemplate.query("""
        select id, backup_no, status, scope, storage, size_mb, checksum, started_at, finished_at
        from backup_record
        order by created_at desc
        limit 50
        """, (rs, rowNum) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("uuid", rs.getObject("id", UUID.class));
      row.put("id", rs.getString("backup_no"));
      row.put("status", rs.getString("status"));
      row.put("scope", rs.getString("scope"));
      row.put("storage", rs.getString("storage"));
      row.put("sizeMb", rs.getBigDecimal("size_mb"));
      row.put("checksum", rs.getString("checksum"));
      row.put("startedAt", dateTime(rs.getTimestamp("started_at")));
      row.put("finishedAt", dateTime(rs.getTimestamp("finished_at")));
      return row;
    });
  }

  /**
   * 修复 P0（伪造成功）：此前本方法**根本不做备份**——只往 {@code backup_record} 插一条写死
   * {@code status='succeeded'}、{@code size_mb=4200.00}、假 checksum 的记录，UI 却弹「备份完成」并在列表
   * 显示 4.2GB。运维会据此相信有可恢复的备份，真出事时才发现什么都没有——这是本次审查中危害最大的一条。
   *
   * <p>真实能力边界（已实测）：app 容器内**没有 pg_dump**，也没有 docker socket，无法自行执行备份；
   * 真备份由 compose 的 {@code backup} 服务（ops profile，postgres:16 镜像）执行 pg_dump + sha256sum，
   * 产出到共享卷 {@code /var/backups/jkxt}（app 只读挂载同一卷）。
   *
   * <p>因此这里**不再伪造**：扫描备份目录里的**真实文件**，把新出现的转成 backup_record；
   * 没有新文件就如实返回 {@code status="unavailable"} 并说明触发方式，绝不谎报成功。
   */
  public Map<String, Object> runBackup() {
    requireSuperAdmin();
    List<Map<String, Object>> imported = importBackupArtifacts();
    audit("ADMIN_BACKUP_SCAN", "backup", null, "high",
        Map.of("importedCount", imported.size(), "dir", BACKUP_DIR));
    if (!imported.isEmpty()) {
      return imported.get(0);
    }
    return Map.of(
        "status", "unavailable",
        "message", "本服务无法自行执行备份（容器内无 pg_dump）。请在宿主机运行 "
            + "`docker compose -f docker-compose.private.yml --profile ops run --rm backup` "
            + "或 scripts/deploy/backup-private.ps1；完成后再次点击即可登记备份产物。",
        "scannedDir", BACKUP_DIR,
        "existingRecords", backups().size()
    );
  }

  /**
   * 只登记已完成的非空备份：必须有同名校验文件，且重算 SHA-256 一致。
   * 大小与校验和均来自**真实文件**，不再是写死的 4200MB/假 checksum。
   */
  private List<Map<String, Object>> importBackupArtifacts() {
    java.io.File dir = new java.io.File(BACKUP_DIR);
    java.io.File[] dumps = dir.isDirectory()
        ? dir.listFiles((d, name) -> name.endsWith(".sql"))
        : null;
    if (dumps == null || dumps.length == 0) {
      return List.of();
    }
    java.util.Arrays.sort(dumps, java.util.Comparator.comparingLong(java.io.File::lastModified).reversed());
    List<Map<String, Object>> created = new ArrayList<>();
    for (java.io.File dump : dumps) {
      String checksum = readChecksum(dump);
      if (checksum == null) {
        log.warn("Skipping incomplete or unverifiable backup artifact: {}", dump.getName());
        continue;
      }
      String backupNo = dump.getName();
      Integer exists = jdbcTemplate.queryForObject(
          "select count(*) from backup_record where backup_no = ?", Integer.class, backupNo);
      if (exists != null && exists > 0) {
        continue;
      }
      java.math.BigDecimal sizeMb = java.math.BigDecimal.valueOf(dump.length())
          .divide(java.math.BigDecimal.valueOf(1024L * 1024L), 2, java.math.RoundingMode.HALF_UP);
      java.sql.Timestamp finished = new java.sql.Timestamp(dump.lastModified());
      UUID id = jdbcTemplate.queryForObject("""
          insert into backup_record (backup_no, status, scope, storage, size_mb, checksum, started_at, finished_at, created_by, payload)
          values (?, 'succeeded', 'platform', 'local-volume', ?, ?, ?, ?, ?, ?::jsonb)
          returning id
          """,
          UUID.class, backupNo, sizeMb, checksum, finished, finished, actorId(),
          "{\"trigger\":\"scan\",\"source\":\"" + BACKUP_DIR + "\"}");
      // 注意：backups() 的行映射把 backup_no 放在 "id" 键上（见 :466），按该约定匹配。
      created.add(backups().stream()
          .filter(row -> backupNo.equals(row.get("id")))
          .findFirst()
          .orElse(Map.of("id", backupNo, "sizeMb", sizeMb, "checksum", checksum)));
    }
    return created;
  }

  /** Fail closed: a manifest is evidence to verify, not an assertion to trust. */
  private String readChecksum(java.io.File dump) {
    java.nio.file.Path path = dump.toPath();
    java.nio.file.Path sum = java.nio.file.Path.of(dump.getAbsolutePath() + ".sha256");
    try {
      if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          || !java.nio.file.Files.isRegularFile(sum, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          || java.nio.file.Files.size(sum) > 2048) return null;
      long size = java.nio.file.Files.size(path);
      var modified = java.nio.file.Files.getLastModifiedTime(path);
      if (size == 0) return null;
      String line = java.nio.file.Files.readString(sum, java.nio.charset.StandardCharsets.UTF_8).strip();
      var match = java.util.regex.Pattern.compile("([a-fA-F0-9]{64}) [ *]([^\\r\\n]+)").matcher(line);
      if (!match.matches()) return null;
      String namedFile = match.group(2);
      if (!namedFile.equals(dump.getName()) && !namedFile.equals("/backups/" + dump.getName())) return null;
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      String actual = java.util.HexFormat.of().formatHex(digest.digest());
      if (size != java.nio.file.Files.size(path) || !modified.equals(java.nio.file.Files.getLastModifiedTime(path))) return null;
      return actual.equalsIgnoreCase(match.group(1)) ? actual : null;
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException unreadable) {
      log.warn("backup verification unavailable: {}", dump.getName(), unreadable);
      return null;
    }
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> announcements() {
    return jdbcTemplate.query(ANNOUNCEMENT_SELECT + "order by created_at desc", announcementRowMapper());
  }

  private RowMapper<Map<String, Object>> announcementRowMapper() {
    return (rs, rowNum) -> announcementRow(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
        rs.getString("severity"), rs.getString("target"), rs.getString("status"), rs.getTimestamp("publish_at"),
        rs.getDate("expire_at"));
  }

  public Map<String, Object> createAnnouncement(Map<String, Object> request) {
    requireSuperAdmin();
    String status = normalizeAnnouncementStatus(string(request, "status", "draft"));
    UUID id = jdbcTemplate.queryForObject("""
        insert into announcement (title, body, severity, target, status, publish_at, expire_at, read_by, created_by)
        values (?, ?, ?, ?, ?, case when ? = 'published' then now() else null end, ?, 0, ?)
        returning id
        """,
        UUID.class,
        string(request, "title", "Untitled announcement"),
        string(request, "body", ""),
        normalizeSeverity(string(request, "severity", "info")),
        string(request, "target", "all"),
        status,
        status,
        localDate(request, "expireAt"),
        actorId()
    );
    audit("ADMIN_ANNOUNCEMENT_CREATE", "announcement", id, "warn", Map.of("status", status));
    return announcementById(id);
  }

  public Map<String, Object> updateAnnouncement(UUID id, Map<String, Object> request) {
    requireSuperAdmin();
    jdbcTemplate.update("""
        update announcement
        set title = coalesce(?, title),
            body = coalesce(?, body),
            severity = coalesce(?, severity),
            target = coalesce(?, target),
            expire_at = coalesce(?, expire_at),
            updated_at = now()
        where id = ?
        """,
        blankToNull(string(request, "title", null)),
        blankToNull(string(request, "body", null)),
        blankToNull(normalizeSeverity(string(request, "severity", null))),
        blankToNull(string(request, "target", null)),
        localDate(request, "expireAt"),
        id
    );
    audit("ADMIN_ANNOUNCEMENT_UPDATE", "announcement", id, "warn", Map.of("fields", List.copyOf(request.keySet())));
    return announcementById(id);
  }

  public Map<String, Object> announcementAction(UUID id, String action) {
    requireSuperAdmin();
    if ("push".equals(action)) {
      announcementById(id); // Preserve 404 without inventing delivery, readership or a success audit.
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "公告通知投递尚未接入，未发送通知");
    }
    String status = switch (action) {
      case "publish" -> "published";
      case "retract" -> "retracted";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported announcement action");
    };
    jdbcTemplate.update("""
        update announcement
        set status = ?, publish_at = case when ? = 'published' then coalesce(publish_at, now()) else publish_at end, updated_at = now()
        where id = ?
        """, status, status, id);
    String auditAction = switch (action) {
      case "publish" -> "ADMIN_ANNOUNCEMENT_PUBLISH";
      default -> "ADMIN_ANNOUNCEMENT_RETRACT";
    };
    audit(auditAction, "announcement", id, "high", Map.of("action", action, "status", status));
    return announcementById(id);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> announcementReadStats(UUID id) {
    announcementById(id);
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("announcementId", id);
    stats.put("readBy", null);
    stats.put("unread", null);
    stats.put("supported", false);
    stats.put("status", "unsupported");
    stats.put("message", "阅读回执尚未接入，无法提供阅读人数或未读人数");
    return stats;
  }

  /** 平台设置的出厂默认值：库中缺键时用它兜底，保证新增配置项无需数据迁移即可上线。 */
  private static final Map<String, Object> PLATFORM_SETTING_DEFAULTS = Map.of(
      "emailNotif", true,
      "twoFactor", false,
      "guestAccess", false,
      "autoBackup", false,
      "maintenance", false
  );

  /**
   * PB-7 / PB-8 (round 9, ADR-08): switches whose behaviour does not exist in the product. They are
   * always reported as their effective state (false) and cannot be switched on; saving true is refused
   * with 501 FEATURE_NOT_AVAILABLE instead of being stored as a misleading "enabled".
   */
  static final Set<String> UNAVAILABLE_PLATFORM_FEATURES = Set.of("twoFactor", "autoBackup", "maintenance");

  /** PB-8 (round 9): no gateway/filter enforces ip_rule; see {@link #addIpRule}. */
  static final boolean IP_RULE_ENFORCEMENT_AVAILABLE = false;

  /**
   * 修复 P0（伪造成功）：此前本方法返回**硬编码** Map、{@code savePlatformSettings} 只 audit + 原样回显，
   * 全程不写库（全库连 platform_setting 表都不存在），而 UI 弹「配置已下发,审计已记录」——刷新即回退。
   * 现改为真读 {@code platform_setting}（V16 迁移），缺键回落出厂默认值。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> platformSettings() {
    Map<String, Object> merged = new LinkedHashMap<>(PLATFORM_SETTING_DEFAULTS);
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select key, value::text as value from platform_setting")) {
      merged.put(String.valueOf(row.get("key")), parseSettingValue(String.valueOf(row.get("value"))));
    }
    // Effective state: an unimplemented feature is never reported as on, whatever an older build stored.
    UNAVAILABLE_PLATFORM_FEATURES.forEach(key -> merged.put(key, false));
    merged.put("unavailableFeatures", UNAVAILABLE_PLATFORM_FEATURES.stream().sorted().toList());
    return merged;
  }

  /** 真正落库（upsert），返回落库后的完整配置——所见即所存，刷新不再回退。 */
  public Map<String, Object> savePlatformSettings(Map<String, Object> request) {
    requireSuperAdmin();
    if (request != null) {
      List<String> refused = request.entrySet().stream()
          .filter(e -> UNAVAILABLE_PLATFORM_FEATURES.contains(e.getKey()) && Boolean.TRUE.equals(e.getValue()))
          .map(Map.Entry::getKey).sorted().toList();
      if (!refused.isEmpty()) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "FEATURE_NOT_AVAILABLE",
            "Not implemented in this deployment and cannot be enabled: " + String.join(", ", refused));
      }
    }
    if (request != null) {
      for (Map.Entry<String, Object> e : request.entrySet()) {
        String key = e.getKey();
        if (key == null || key.isBlank() || key.length() > 64 || "unavailableFeatures".equals(key)) {
          continue;   // 忽略非法键，不因单个脏键让整次保存失败
        }
        jdbcTemplate.update("""
            insert into platform_setting (key, value, updated_at, updated_by)
            values (?, ?::jsonb, now(), ?)
            on conflict (key) do update set value = excluded.value,
                                            updated_at = now(),
                                            updated_by = excluded.updated_by
            """, key, toJsonLiteral(e.getValue()), actorId());
      }
    }
    audit("ADMIN_PLATFORM_SETTINGS_UPDATE", "platform_settings", null, "high",
        Map.of("fields", request == null ? List.of() : List.copyOf(request.keySet())));
    return platformSettings();
  }

  /** jsonb 文本 → Java 值（仅需支持布尔/数字/字符串这三类配置值）。 */
  private Object parseSettingValue(String json) {
    if (json == null) {
      return null;
    }
    String v = json.trim();
    if ("true".equals(v) || "false".equals(v)) {
      return Boolean.parseBoolean(v);
    }
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      return v.substring(1, v.length() - 1);
    }
    try {
      return v.contains(".") ? Double.valueOf(v) : Long.valueOf(v);
    } catch (NumberFormatException notANumber) {
      return v;
    }
  }

  /** Java 值 → jsonb 字面量。字符串按 JSON 规则转义，避免引号/反斜杠破坏 jsonb 解析。 */
  private String toJsonLiteral(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Boolean || value instanceof Number) {
      return String.valueOf(value);
    }
    String s = String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    return "\"" + s + "\"";
  }

  /**
   * 迭代12 A4: 防御纵深——全平台运营台的**写**操作在 service 层再断言一次超管身份。
   * 现役调用路径已有两道门(AdminPlatformController 类级 @PreAuthorize("hasRole('SUPER_ADMIN')")
   * + SecurityConfig 的 /admin/** hasRole("SUPER_ADMIN"))，本断言是兜底：日后若有人给本 service 接一条
   * 非 /admin 的调用路径而漏加注解，跨租户的建租户/改账号/封 IP 等写操作仍不会失守。
   * 读方法不加断言，避免影响既有只读调用。
   */
  private void requireSuperAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean superAdmin = authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> ROLE_SUPER_ADMIN.equals(authority.getAuthority()));
    if (!superAdmin) {
      // 复用既有 403 语义(RestExceptionHandler 将 ResponseStatusException 403 映射为 code=FORBIDDEN)，
      // 与 rejectCrossTenantMutationInPrivateMode 一致，不自造新异常类型。
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administration requires SUPER_ADMIN");
    }
  }

  private void rejectCrossTenantMutationInPrivateMode(UUID tenantId) {
    if (!deployProperties.isPrivate()) {
      return;
    }
    if (tenantId == null || !deployProperties.fixedOrgUuid().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Private deployment is fixed to one organization");
    }
  }

  // 迭代12 B4: 以下三个 by-id 原先都先拉全量 list 再在内存里 filter 出一条(全表扫)，改为直接按 id 查。
  // 三者都复用列表侧的 *_SELECT 骨架与 *ScopeClause()，因此列表侧的过滤语义(type 白名单 / 软删过滤 /
  // private 模式组织锁定)被逐字保留；id 是主键，命中至多一行。用 query(...) 取列表再 findFirst 而非
  // queryForObject，是为了让"查不到"仍走 orElseThrow 抛出与改动前完全一致的 404，而不是
  // EmptyResultDataAccessException(会掉进 500)。

  private Map<String, Object> tenantById(UUID id) {
    Object[] args = withId(tenantScopeArgs(), id);
    return jdbcTemplate.query(TENANT_SELECT + tenantScopeClause() + "and o.id = ?", args, tenantRowMapper())
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
  }

  private Map<String, Object> accountById(UUID id) {
    // 注意：accountScopeClause() 的 deleted_at is null 过滤必须保留——否则会把已软删账号查出来。
    Object[] args = withId(accountScopeArgs(), id);
    return jdbcTemplate.query(ACCOUNT_SELECT + accountScopeClause() + "and u.id = ?", args, accountRowMapper())
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
  }

  private Map<String, Object> announcementById(UUID id) {
    return jdbcTemplate.query(ANNOUNCEMENT_SELECT + "where id = ?", new Object[] { id }, announcementRowMapper())
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Announcement not found"));
  }

  private Map<String, Object> announcementRow(
      UUID id,
      String title,
      String body,
      String severity,
      String target,
      String status,
      Timestamp publishAt,
      Date expireAt
  ) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("uuid", id);
    row.put("id", id.toString());
    row.put("title", title);
    row.put("body", body);
    row.put("severity", severity);
    row.put("target", target);
    row.put("status", status);
    row.put("publishAt", dateTime(publishAt));
    row.put("expireAt", expireAt == null ? "-" : DATE.format(expireAt.toLocalDate()));
    // Historical read_by values were not derived from receipts and are not verified reader counts.
    row.put("readBy", null);
    row.put("readStatsSupported", false);
    row.put("deliverySupported", false);
    return row;
  }

  private UUID tenantIdFromRequest(Map<String, Object> request) {
    Object tenantId = request == null ? null : request.get("tenantId");
    if (tenantId instanceof String text && !text.isBlank()) {
      return UUID.fromString(text);
    }
    if (deployProperties.isPrivate()) {
      return deployProperties.fixedOrgUuid();
    }
    try {
      return jdbcTemplate.queryForObject("select id from organization where type in ('school','group') order by created_at asc limit 1", UUID.class);
    } catch (EmptyResultDataAccessException exception) {
      return TenantContext.getRequired();
    }
  }

  private UUID accountOrgId(UUID accountId) {
    try {
      return jdbcTemplate.queryForObject("select org_id from app_user where id = ?", UUID.class, accountId);
    } catch (EmptyResultDataAccessException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
    }
  }

  private int count(String sql) {
    Number value = jdbcTemplate.queryForObject(sql, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private UUID actorId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
      return principal.userId();
    }
    return null;
  }

  private void audit(String action, String targetType, UUID targetId, String severity, Map<String, Object> payload) {
    AuditLog log = new AuditLog();
    log.setOrgId(TenantContext.get().orElse(null));
    log.setActorId(actorId());
    log.setAction(action);
    log.setTargetType(targetType);
    log.setTargetId(targetId);
    log.setSeverity(severity);
    log.setPayload(payload);
    auditLogRepository.save(log);
  }

  private String cityFromJsonText(String json) {
    if (json == null) {
      return "-";
    }
    int idx = json.indexOf("city");
    if (idx < 0) {
      return "-";
    }
    int colon = json.indexOf(':', idx);
    int first = json.indexOf('"', colon + 1);
    int second = json.indexOf('"', first + 1);
    return first >= 0 && second > first ? json.substring(first + 1, second) : "-";
  }

  private String string(Map<String, Object> request, String key, String fallback) {
    if (request == null) {
      return fallback;
    }
    Object value = request.get(key);
    return value == null ? fallback : String.valueOf(value).trim();
  }

  private int number(Map<String, Object> request, String key, int fallback) {
    if (request == null) {
      return fallback;
    }
    Object value = request.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      return Integer.parseInt(text);
    }
    return fallback;
  }

  private LocalDate localDate(Map<String, Object> request, String key) {
    String value = string(request, key, null);
    if (value == null || value.isBlank() || "-".equals(value)) {
      return null;
    }
    return LocalDate.parse(value);
  }

  private String dateOnly(Timestamp timestamp) {
    return timestamp == null ? "-" : DATE.format(timestamp.toLocalDateTime().toLocalDate());
  }

  private String dateTime(Timestamp timestamp) {
    return timestamp == null ? "-" : timestamp.toInstant().toString();
  }

  private String slug(String name) {
    String base = name == null ? "tenant" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    base = base.replaceAll("(^-|-$)", "");
    if (base.isBlank()) {
      base = "tenant";
    }
    return base + "-" + Long.toString(Instant.now().toEpochMilli(), 36);
  }

  private String normalizePlan(String plan) {
    if (plan == null || plan.isBlank()) {
      return null;
    }
    return switch (plan.toLowerCase(Locale.ROOT)) {
      case "standard", "premium", "private" -> plan.toLowerCase(Locale.ROOT);
      default -> "trial";
    };
  }

  private String normalizeSeverity(String severity) {
    if (severity == null || severity.isBlank()) {
      return null;
    }
    return switch (severity.toLowerCase(Locale.ROOT)) {
      case "reminder", "critical" -> severity.toLowerCase(Locale.ROOT);
      default -> "info";
    };
  }

  private String normalizeAnnouncementStatus(String status) {
    return "published".equalsIgnoreCase(status) ? "published" : "draft";
  }

  private String backendRole(String role) {
    return switch (role.trim().toLowerCase(Locale.ROOT)) {
      case "super", "super_admin" -> "SUPER_ADMIN";
      case "viewer" -> "VIEWER";
      case "teacher", "teacher_query" -> "TEACHER_QUERY";
      case "admin" -> "ADMIN";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported account role");
    };
  }

  private String uiRole(String role) {
    return switch (role) {
      case "SUPER_ADMIN" -> "super";
      case "VIEWER", "TEACHER_QUERY" -> "viewer";
      default -> "admin";
    };
  }

  private String roleDepartment(String role) {
    return switch (role) {
      case "SUPER_ADMIN" -> "Platform";
      case "VIEWER", "TEACHER_QUERY" -> "Audit";
      default -> "Academic Affairs";
    };
  }

  private String uiAccountStatus(String status, Timestamp lastLoginAt) {
    if (!"active".equals(status)) {
      return "offline";
    }
    if (lastLoginAt != null && lastLoginAt.toInstant().isAfter(Instant.now().minusSeconds(3600))) {
      return "online";
    }
    return "offline";
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String escapeJson(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
