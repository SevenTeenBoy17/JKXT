package com.jkxt.platform.service;

import com.jkxt.audit.domain.AuditLog;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.security.auth.SessionPrincipal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class AdminPlatformService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  private final JdbcTemplate jdbcTemplate;
  private final AuditLogRepository auditLogRepository;
  private final DeployProperties deployProperties;

  public AdminPlatformService(
      JdbcTemplate jdbcTemplate,
      AuditLogRepository auditLogRepository,
      DeployProperties deployProperties
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.auditLogRepository = auditLogRepository;
    this.deployProperties = deployProperties;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> tenants() {
    String fixedTenantClause = deployProperties.isPrivate() ? "and o.id = ?\n" : "";
    Object[] args = deployProperties.isPrivate() ? new Object[] { deployProperties.fixedOrgUuid() } : new Object[] {};
    return jdbcTemplate.query("""
        select o.id, o.slug, o.name, o.type, o.plan, o.status, o.region, o.settings, o.contact_name,
               o.contact_phone, o.created_at,
               coalesce((o.settings->>'abbr'), upper(left(o.slug, 3))) as abbr,
               coalesce((o.settings->>'displayType'), o.type) as display_type,
               coalesce((o.settings->>'color'), '#4F46E5') as color,
               coalesce((o.settings->>'students')::int, 0) as students,
               coalesce((o.settings->>'adminCap')::int, 0) as admin_cap,
               (select count(*) from app_user u where u.org_id = o.id)::int as admins
        from organization o
        where o.type in ('school', 'group')
        %s
        order by o.created_at asc, o.slug asc
        """.formatted(fixedTenantClause), args, (rs, rowNum) -> {
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
    });
  }

  public Map<String, Object> createTenant(Map<String, Object> request) {
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
    audit("ADMIN_TENANT_CREATE", "tenant", id, "warn", Map.of("slug", slug, "name", name));
    return tenantById(id);
  }

  public Map<String, Object> updateTenant(UUID tenantId, Map<String, Object> request) {
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
    rejectCrossTenantMutationInPrivateMode(tenantId);
    String dbStatus = "restore".equals(status) || "active".equals(status) ? "active" : "suspended";
    int updated = jdbcTemplate.update("update organization set status = ?, updated_at = now() where id = ?", dbStatus, tenantId);
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
    }
    audit("suspended".equals(dbStatus) ? "ADMIN_TENANT_SUSPEND" : "ADMIN_TENANT_RESTORE", "tenant", tenantId, "high", Map.of("status", dbStatus));
    return tenantById(tenantId);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> accounts() {
    String fixedTenantClause = deployProperties.isPrivate() ? "where u.org_id = ?\n" : "";
    Object[] args = deployProperties.isPrivate() ? new Object[] { deployProperties.fixedOrgUuid() } : new Object[] {};
    return jdbcTemplate.query("""
        select u.id, u.username, u.display_name, u.email, u.phone, u.role, u.status, u.must_change_password,
               u.last_login_at, u.login_count, o.slug as tenant_slug, o.name as tenant_name
        from app_user u
        left join organization o on o.id = u.org_id
        %s
        order by u.created_at asc
        """.formatted(fixedTenantClause), args, (rs, rowNum) -> {
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
      row.put("twoFactor", !rs.getBoolean("must_change_password"));
      row.put("lastLogin", dateTime(rs.getTimestamp("last_login_at")));
      row.put("loginCount", rs.getInt("login_count"));
      return row;
    });
  }

  public Map<String, Object> createAccount(Map<String, Object> request) {
    UUID tenantId = tenantIdFromRequest(request);
    rejectCrossTenantMutationInPrivateMode(tenantId);
    String email = string(request, "email", "admin-" + Instant.now().toEpochMilli() + "@jkxt.local");
    String username = string(request, "username", email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length()));
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
        "$2a$12$3WbJqB7QYkYpZ6bDL9xqgOL9GEpZ4/9s1rLJfHIGjZ2sJ6t9I6Q8a",
        backendRole(string(request, "role", "ADMIN"))
    );
    audit("ADMIN_ACCOUNT_CREATE", "account", id, "warn", Map.of("email", email));
    return accountById(id);
  }

  public Map<String, Object> accountAction(UUID accountId, String action) {
    rejectCrossTenantMutationInPrivateMode(accountOrgId(accountId));
    String auditAction = switch (action) {
      case "reset-password" -> "ADMIN_ACCOUNT_RESET_PASSWORD";
      case "force-logout" -> "ADMIN_ACCOUNT_FORCE_LOGOUT";
      case "disable" -> "ADMIN_ACCOUNT_DISABLE";
      case "delete" -> "ADMIN_ACCOUNT_DELETE";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported account action");
    };
    if ("disable".equals(action) || "delete".equals(action)) {
      jdbcTemplate.update("update app_user set status = 'disabled', updated_at = now() where id = ?", accountId);
    }
    audit(auditAction, "account", accountId, "high", Map.of("action", action));
    return accountById(accountId);
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> auditLogs() {
    return jdbcTemplate.query("""
        select l.id, l.action, l.target_type, l.target_id, l.severity, l.created_at, l.payload,
               u.display_name as actor, o.slug as tenant
        from audit_log l
        left join app_user u on u.id = l.actor_id
        left join organization o on o.id = l.org_id
        order by l.created_at desc
        limit 200
        """, (rs, rowNum) -> {
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

  public Map<String, Object> runBackup() {
    String backupNo = "BAK-" + Instant.now().toEpochMilli();
    UUID id = jdbcTemplate.queryForObject("""
        insert into backup_record (backup_no, status, scope, storage, size_mb, checksum, started_at, finished_at, created_by, payload)
        values (?, 'succeeded', 'platform', 'local-snapshot', 4200.00, ?, now() - interval '2 minutes', now(), ?, ?::jsonb)
        returning id
        """,
        UUID.class,
        backupNo,
        "manual-" + backupNo.toLowerCase(Locale.ROOT),
        actorId(),
        "{\"trigger\":\"manual\"}"
    );
    audit("ADMIN_BACKUP_RUN", "backup", id, "high", Map.of("backupNo", backupNo));
    return backups().stream().filter(row -> backupNo.equals(row.get("id"))).findFirst().orElse(Map.of("id", backupNo));
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> announcements() {
    return jdbcTemplate.query("""
        select id, title, body, severity, target, status, publish_at, expire_at, read_by, created_at
        from announcement
        order by created_at desc
        """, (rs, rowNum) -> announcementRow(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
        rs.getString("severity"), rs.getString("target"), rs.getString("status"), rs.getTimestamp("publish_at"),
        rs.getDate("expire_at"), rs.getInt("read_by")));
  }

  public Map<String, Object> createAnnouncement(Map<String, Object> request) {
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
    String status = switch (action) {
      case "publish", "push" -> "published";
      case "retract" -> "retracted";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported announcement action");
    };
    if ("push".equals(action)) {
      jdbcTemplate.update("update announcement set read_by = read_by + 1, updated_at = now() where id = ?", id);
    } else {
      jdbcTemplate.update("""
          update announcement
          set status = ?, publish_at = case when ? = 'published' then coalesce(publish_at, now()) else publish_at end, updated_at = now()
          where id = ?
          """, status, status, id);
    }
    String auditAction = switch (action) {
      case "publish" -> "ADMIN_ANNOUNCEMENT_PUBLISH";
      case "push" -> "ADMIN_ANNOUNCEMENT_PUSH";
      default -> "ADMIN_ANNOUNCEMENT_RETRACT";
    };
    audit(auditAction, "announcement", id, "high", Map.of("action", action, "status", status));
    return announcementById(id);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> announcementReadStats(UUID id) {
    Map<String, Object> row = announcementById(id);
    int readBy = number(row, "readBy", 0);
    return Map.of("announcementId", id, "readBy", readBy, "unread", Math.max(0, tenants().size() * 20 - readBy));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> platformSettings() {
    return Map.of(
        "emailNotif", true,
        "twoFactor", true,
        "guestAccess", false,
        "autoBackup", true,
        "maintenance", false
    );
  }

  public Map<String, Object> savePlatformSettings(Map<String, Object> request) {
    audit("ADMIN_PLATFORM_SETTINGS_UPDATE", "platform_settings", null, "high", Map.of("fields", List.copyOf(request.keySet())));
    Map<String, Object> response = new LinkedHashMap<>(platformSettings());
    response.putAll(request);
    return response;
  }

  private void rejectCrossTenantMutationInPrivateMode(UUID tenantId) {
    if (!deployProperties.isPrivate()) {
      return;
    }
    if (tenantId == null || !deployProperties.fixedOrgUuid().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Private deployment is fixed to one organization");
    }
  }

  private Map<String, Object> tenantById(UUID id) {
    return tenants().stream()
        .filter(row -> id.equals(row.get("uuid")))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
  }

  private Map<String, Object> accountById(UUID id) {
    return accounts().stream()
        .filter(row -> id.equals(row.get("uuid")))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
  }

  private Map<String, Object> announcementById(UUID id) {
    return announcements().stream()
        .filter(row -> id.equals(row.get("uuid")))
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
      Date expireAt,
      int readBy
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
    row.put("readBy", readBy);
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
    return switch (role.toLowerCase(Locale.ROOT)) {
      case "super", "super_admin" -> "SUPER_ADMIN";
      case "viewer" -> "VIEWER";
      default -> "ADMIN";
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
