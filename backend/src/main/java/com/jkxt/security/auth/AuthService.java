package com.jkxt.security.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.audit.domain.AuditLog;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.common.config.DeployProperties;
import com.jkxt.org.domain.AcademicTerm;
import com.jkxt.org.domain.Organization;
import com.jkxt.org.repo.AcademicTermRepository;
import com.jkxt.org.repo.OrganizationRepository;
import com.jkxt.security.api.AuthDtos.LoginRequest;
import com.jkxt.security.api.AuthDtos.LoginResponse;
import com.jkxt.security.api.AuthDtos.MeResponse;
import com.jkxt.security.api.AuthDtos.OrgView;
import com.jkxt.security.api.AuthDtos.TermView;
import com.jkxt.security.api.AuthDtos.UserView;
import com.jkxt.security.domain.UserAccount;
import com.jkxt.security.repo.UserAccountRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuthService {

  private static final int MAX_FAILED_ATTEMPTS = 5;
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };

  private final UserAccountRepository userRepository;
  private final OrganizationRepository organizationRepository;
  private final AcademicTermRepository termRepository;
  private final AuditLogRepository auditLogRepository;
  private final PasswordEncoder passwordEncoder;
  private final CaptchaService captchaService;
  private final SessionService sessionService;
  private final DeployProperties deployProperties;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AuthService(
      UserAccountRepository userRepository,
      OrganizationRepository organizationRepository,
      AcademicTermRepository termRepository,
      AuditLogRepository auditLogRepository,
      PasswordEncoder passwordEncoder,
      CaptchaService captchaService,
      SessionService sessionService,
      DeployProperties deployProperties,
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper
  ) {
    this.userRepository = userRepository;
    this.organizationRepository = organizationRepository;
    this.termRepository = termRepository;
    this.auditLogRepository = auditLogRepository;
    this.passwordEncoder = passwordEncoder;
    this.captchaService = captchaService;
    this.sessionService = sessionService;
    this.deployProperties = deployProperties;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  // noRollbackFor AuthException: the failed-login / lockout branches persist failed_attempts, the
  // lock timestamp, and the AUTH_LOGIN_FAILED / AUTH_LOGIN_LOCKED audit rows and THEN throw an
  // AuthException. Without this, the default rollback-on-RuntimeException would discard all of that —
  // so the counter never accumulates, the account never locks (brute-force protection silently
  // broken), and failed-login audit/KPIs are all lost. We want those writes committed on auth failure.
  @Transactional(noRollbackFor = AuthException.class)
  public LoginResult login(LoginRequest request, String ip) {
    return login(request, ip, AuthPortal.SCHOOL);
  }

  /**
   * PB-4 (spec 2026-09-20 §4.1): the entry is decided by the endpoint, never by the request body. Order:
   * captcha -> credentials -> entry match. A correct password on the wrong entry answers 403
   * AUTH_PORTAL_MISMATCH without issuing/replacing a session and without success bookkeeping or audit.
   */
  @Transactional(noRollbackFor = AuthException.class)
  public LoginResult login(LoginRequest request, String ip, AuthPortal portal) {
    if (!captchaService.consume(request.captchaId(), request.captcha())) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "AUTH_CAPTCHA_FAILED", "Captcha is invalid or expired");
    }

    String username = request.username().trim().toLowerCase(Locale.ROOT);
    List<UserAccount> candidates = userRepository.findAllByUsernameIgnoreCase(username);
    if (request.organization() != null && !request.organization().isBlank()) {
      UUID requestedOrg = organizationRepository.findBySlug(request.organization().trim())
          .map(Organization::getId).orElse(null);
      candidates = candidates.stream().filter(account -> account.getOrgId().equals(requestedOrg)).toList();
    } else if (deployProperties.isPrivate() && candidates.size() > 1) {
      candidates = candidates.stream().filter(account -> deployProperties.fixedOrgUuid().equals(account.getOrgId())
          || "SUPER_ADMIN".equals(account.getRole())).toList();
    }
    if (candidates.size() > 1) {
      if (request.organization() != null && !request.organization().isBlank()) {
        throw new AuthException(HttpStatus.CONFLICT, "AUTH_USERNAME_AMBIGUOUS", "该学校存在同名账号，请联系管理员处理后重试");
      }
      throw new AuthException(HttpStatus.BAD_REQUEST, "AUTH_TENANT_REQUIRED", "请输入学校编码以确定登录账号");
    }
    if (candidates.isEmpty()) {
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
    }
    UserAccount user = candidates.getFirst();

    if (!"active".equalsIgnoreCase(user.getStatus())) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_DISABLED", "auth", "warn");
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
    }

    Instant now = Instant.now();
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_LOCKED", "auth", "warn");
      // PB-6 (round 9, AUTH-05 "响应不泄露账号详情"): a locked account answers exactly like an unknown
      // account or a wrong password. A distinct 423 told an attacker that the username exists (and, for
      // a correct password, that the password is right). The lock itself is kept in app_user and audited.
      throw invalidCredentials();
    }

    // A null/blank stored hash (e.g. an account provisioned without a password yet) must be treated
    // as a normal credential mismatch — feeding the lockout counter and returning 401 — rather than
    // letting BCrypt throw IllegalArgumentException (which would surface as a 400 and leak the reason).
    boolean passwordMatches = user.getPasswordHash() != null
        && !user.getPasswordHash().isBlank()
        && passwordEncoder.matches(request.password(), user.getPasswordHash());
    if (!passwordMatches) {
      // 迭代9 T6-P0-2：失败计数原子自增 + 锁定，避免并发错密码在 JPA @Version 上争用导致丢失计数
      // (lost update → 绕过锁定阈值)。与登录成功路径(下方)同样走直接 UPDATE，不脏写托管实体。
      Integer attempts;
      try {
        attempts = jdbcTemplate.queryForObject(
            "update app_user set failed_attempts = failed_attempts + 1, "
                + "locked_until = case when failed_attempts + 1 >= ? then ? else locked_until end, "
                + "updated_at = now() where id = ? returning failed_attempts",
            Integer.class,
            MAX_FAILED_ATTEMPTS,
            java.sql.Timestamp.from(now.plus(30, ChronoUnit.MINUTES)),
            user.getId());
      } catch (org.springframework.dao.EmptyResultDataAccessException accountGone) {
        // 迭代9 F7：账号在校验口令与自增之间被并发删除 → UPDATE 0 行 → queryForObject 抛异常。
        // 该异常非 AuthException 会逃逸 noRollbackFor 白名单致 500；按"凭据无效"返回 401。
        throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
      }
      int attemptCount = attempts == null ? 0 : attempts;
      if (attemptCount >= MAX_FAILED_ATTEMPTS) {
        audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_LOCKED_AFTER_FAILURES", "auth", "high");
        throw invalidCredentials();
      }
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_FAILED", "auth", "warn");
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
    }

    if (!portal.admits(user.getRole())) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_PORTAL_MISMATCH", "auth", "warn");
      throw new AuthException(HttpStatus.FORBIDDEN, "AUTH_PORTAL_MISMATCH",
          portal == AuthPortal.PLATFORM ? "该账号不能从平台入口登录，请使用学校入口" : "该账号不能从学校入口登录，请使用平台入口");
    }

    Organization org = organizationRepository.findById(user.getOrgId())
        .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Tenant is unavailable"));
    if (deployProperties.isPrivate() && !"SUPER_ADMIN".equals(user.getRole())
        && !deployProperties.fixedOrgUuid().equals(org.getId())) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_PRIVATE_ORG_REJECTED", "auth", "high");
      throw new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Private deployment is fixed to one organization");
    }
    if (!"active".equalsIgnoreCase(org.getStatus()) && !"SUPER_ADMIN".equals(user.getRole())) {
      throw new AuthException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED", "Tenant is not active");
    }

    List<String> permissions = permissions(user.getOrgId(), user.getRole());
    // Persist login-success bookkeeping via a direct UPDATE (not userRepository.save) so that
    // concurrent successful logins for the same account do not contend on the JPA @Version and
    // throw ObjectOptimisticLockingFailureException (P1-6). login_count is incremented in-place.
    int authenticated = jdbcTemplate.update(
        "update app_user set failed_attempts = 0, locked_until = null, last_login_at = ?, "
            + "last_login_ip = ?, login_count = login_count + 1, updated_at = now() "
            + "where id = ? and auth_version = ? and status = 'active' and deleted_at is null",
        ps -> {
          ps.setObject(1, java.sql.Timestamp.from(now));
          ps.setString(2, ip);
          ps.setObject(3, user.getId());
          ps.setLong(4, user.getAuthVersion());
        });
    if (authenticated != 1) {
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Account credentials changed; sign in again");
    }
    // Deliberately DO NOT mutate the managed `user` entity here: the login response and session
    // principal read only id/username/displayName/role/onboarded (all unchanged), and mutating the
    // managed entity would make Hibernate flush a second, @Version-checked UPDATE at commit — which
    // is exactly the optimistic-lock race two concurrent logins would lose (P1-6). The JDBC UPDATE
    // above is the sole persistence of the bookkeeping columns.
    audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_SUCCESS", "auth", "info");

    SessionPrincipal principal = new SessionPrincipal(
        "",
        user.getId(),
        user.getOrgId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getRole(),
        permissions
    ).withAuthVersion(user.getAuthVersion());
    SessionService.CreatedSession session = sessionService.create(principal, portal);
    return new LoginResult(toLoginResponse(user, org, permissions), session.cookie());
  }

  @Transactional(readOnly = true)
  public MeResponse me(SessionPrincipal principal) {
    UserAccount user = userRepository.findById(principal.userId())
        .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session user is gone"));
    Organization org = organizationRepository.findById(principal.orgId())
        .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Tenant is unavailable"));
    List<String> permissions = permissions(principal.orgId(), user.getRole());
    return toMeResponse(user, org, permissions);
  }

  @Transactional
  public void logout(SessionPrincipal principal) {
    audit(principal.orgId(), principal.userId(), "AUTH_LOGOUT", "auth", "info");
    sessionService.revoke(principal.sessionId());
  }

  @Transactional
  public MeResponse markOnboarded(SessionPrincipal principal) {
    UserAccount user = userRepository.findForUpdateById(principal.userId())
        .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session user is gone"));
    if (user.getAuthVersion() != principal.authVersion() || !"active".equalsIgnoreCase(user.getStatus())) {
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session credentials changed");
    }
    if (!user.isOnboarded()) {
      user.setOnboarded(true);
      userRepository.save(user);
      audit(user.getOrgId(), user.getId(), "AUTH_ONBOARDED", "auth", "info");
    }
    Organization org = organizationRepository.findById(principal.orgId())
        .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Tenant is unavailable"));
    List<String> permissions = permissions(principal.orgId(), user.getRole());
    return toMeResponse(user, org, permissions);
  }

  @Transactional
  public MeResponse changePassword(SessionPrincipal principal, com.jkxt.security.api.AuthDtos.ChangePasswordRequest request) {
    UserAccount user = userRepository.findForUpdateById(principal.userId())
        .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session user is gone"));
    if (user.getAuthVersion() != principal.authVersion() || !"active".equalsIgnoreCase(user.getStatus())) {
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session credentials changed");
    }
    String next = request.newPassword() == null ? "" : request.newPassword().trim();
    if (next.length() < 8) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK", "新密码至少 8 位");
    }
    boolean currentMatches = user.getPasswordHash() != null && !user.getPasswordHash().isBlank()
        && request.currentPassword() != null
        && passwordEncoder.matches(request.currentPassword(), user.getPasswordHash());
    if (!currentMatches) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "AUTH_CURRENT_PASSWORD_INVALID", "当前密码不正确");
    }
    user.setPasswordHash(passwordEncoder.encode(next));
    // 迭代9 T4-P0-3：清除强制改密标记，否则 must_change_password 永不复位 → 用户改了密码仍被反复
    // 要求改密(功能死循环)。强制改密门(后续 B3)将据此放行。
    user.setMustChangePassword(false);
    user.setAuthVersion(user.getAuthVersion() + 1);
    userRepository.saveAndFlush(user);
    // 安全加固(迭代6)：改密码后撤销该用户的其它会话(其它设备/旧 cookie 立即失效)，仅保留当前设备。
    long committedVersion = user.getAuthVersion();
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() {
        sessionService.revokeOtherSessions(user.getId(), principal.sessionId());
        sessionService.updateRetainedVersion(principal.sessionId(), committedVersion);
      }
    });
    audit(user.getOrgId(), user.getId(), "AUTH_PASSWORD_CHANGED", "auth", "info");
    Organization org = organizationRepository.findById(principal.orgId())
        .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Tenant is unavailable"));
    return toMeResponse(user, org, permissions(principal.orgId(), user.getRole()));
  }

  private LoginResponse toLoginResponse(UserAccount user, Organization org, List<String> permissions) {
    return new LoginResponse(
        userView(user),
        orgView(org),
        currentTermView(user.getOrgId()),
        permissions,
        user.isOnboarded()
    );
  }

  private MeResponse toMeResponse(UserAccount user, Organization org, List<String> permissions) {
    return new MeResponse(
        userView(user),
        orgView(org),
        currentTermView(user.getOrgId()),
        permissions,
        user.isOnboarded()
    );
  }

  private UserView userView(UserAccount user) {
    return new UserView(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getRole(),
        user.isMustChangePassword()
    );
  }

  private OrgView orgView(Organization org) {
    return new OrgView(org.getId(), org.getSlug(), org.getName(), org.getStatus());
  }

  private TermView currentTermView(UUID orgId) {
    AcademicTerm term = termRepository.findByOrgIdAndCurrentTrue(orgId).stream()
        .findFirst()
        .orElse(null);
    if (term == null) {
      return null;
    }
    return new TermView(
        term.getId(),
        term.getYearLabel(),
        term.getTerm(),
        term.getName(),
        term.getStartDate(),
        term.getEndDate(),
        term.isCurrent()
    );
  }

  private List<String> permissions(UUID orgId, String role) {
    // 先按 (org_id, code) 取该 org 自己的角色权限。
    String json = jdbcTemplate.query(
        "select permissions::text from role where org_id = ? and code = ? order by system_role desc limit 1",
        ps -> {
          ps.setObject(1, orgId);
          ps.setString(2, role);
        },
        rs -> rs.next() ? rs.getString(1) : null
    );
    // P17 修复（对抗审查 P1）：运行时新建的租户——createTenant 只插 organization、不建 role 行；createAccount
    // 也不建——其 app_user 会解析到【空】权限。在 A1 的 hasAuthority 门下，空权限意味着读(tenant:read)与写
    // (*:manage)全 403，整个 SaaS 运行时租户被锁死。系统角色权限集与 org 无关、各 org 一致，故本 org 无行/为空
    // 时回落到【任一已 seed 的同名系统角色】副本作规范集：DB 取值、不在代码里复制权限集（避免与迁移漂移）。
    if (isBlankPermissions(json)) {
      json = jdbcTemplate.query(
          "select permissions::text from role where code = ? and system_role = true "
              + "and permissions <> '[]'::jsonb order by org_id limit 1",
          ps -> ps.setString(1, role),
          rs -> rs.next() ? rs.getString(1) : "[]"
      );
    }
    try {
      return objectMapper.readValue(json, STRING_LIST);
    } catch (JsonProcessingException exception) {
      return List.of();
    }
  }

  private boolean isBlankPermissions(String json) {
    if (json == null) {
      return true;
    }
    String trimmed = json.trim();
    return trimmed.isEmpty() || trimmed.equals("[]");
  }

  private void audit(UUID orgId, UUID actorId, String action, String targetType, String severity) {
    AuditLog log = new AuditLog();
    log.setOrgId(orgId);
    log.setActorId(actorId);
    log.setAction(action);
    log.setTargetType(targetType);
    log.setSeverity(severity);
    auditLogRepository.save(log);
  }

  public record LoginResult(LoginResponse response, org.springframework.http.ResponseCookie cookie) {
  }

  private static AuthException invalidCredentials() {
    return new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
  }
}
