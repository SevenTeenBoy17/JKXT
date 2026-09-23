package com.jkxt.security.auth;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.security.api.AuthDtos.DemoView;

@Service
public class AuthService {

  private static final int MAX_FAILED_ATTEMPTS = 5;

  private final UserAccountRepository userRepository;
  private final OrganizationRepository organizationRepository;
  private final AcademicTermRepository termRepository;
  private final AuditLogRepository auditLogRepository;
  private final PasswordEncoder passwordEncoder;
  private final CaptchaService captchaService;
  private final SessionService sessionService;
  private final DeployProperties deployProperties;
  private final JdbcTemplate jdbcTemplate;
  private final CurrentAccountAccess accounts;
  private final TransactionTemplate transactions;
  private com.jkxt.demo.DemoWorkspaceRepository demo;

  @org.springframework.beans.factory.annotation.Autowired
  public void demoResolver(com.jkxt.demo.DemoWorkspaceRepository demo) { this.demo=demo; }

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
      CurrentAccountAccess accounts,
      PlatformTransactionManager transactionManager
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
    this.accounts = accounts;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  // noRollbackFor AuthException: the failed-login / lockout branches persist failed_attempts, the
  // lock timestamp, and the AUTH_LOGIN_FAILED / AUTH_LOGIN_LOCKED audit rows and THEN throw an
  // AuthException. Without this, the default rollback-on-RuntimeException would discard all of that —
  // so the counter never accumulates, the account never locks (brute-force protection silently
  // broken), and failed-login audit/KPIs are all lost. We want those writes committed on auth failure.
  @Transactional(noRollbackFor = AuthException.class)
  public LoginResult login(LoginRequest request, String ip, PortalPurpose purpose) {
    return login(request,ip,purpose,null);
  }

  @Transactional(noRollbackFor = AuthException.class)
  public LoginResult login(LoginRequest request, String ip, PortalPurpose purpose, String previousId) {
    if(deployProperties.isDemo())throw new AuthException(HttpStatus.FORBIDDEN,"AUTH_PORTAL_MISMATCH","演示实例不提供账号密码登录");
    if (!captchaService.consume(request.captchaId(), request.captcha())) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "AUTH_CAPTCHA_FAILED", "Captcha is invalid or expired");
    }

    String username = request.username().trim().toLowerCase(Locale.ROOT);
    // Keep credential verification and the issued auth_version on one locked account revision.
    // Successful browser logins serialize their bookkeeping without optimistic entity updates.
    jdbcTemplate.query("select id from app_user where lower(username)=? for update",
        rs -> { while(rs.next()) { /* acquire the matching row lock */ } return null; },username);
    UserAccount user = userRepository.findFirstByUsernameIgnoreCase(username)
        .orElseThrow(() -> new AuthException(
            HttpStatus.UNAUTHORIZED,
            "AUTH_INVALID_CREDENTIALS",
            "Invalid username or password"
        ));

    if (!"active".equalsIgnoreCase(user.getStatus()) || user.getDeletedAt()!=null) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_DISABLED", "auth", "warn");
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
    }

    Instant now = Instant.now();
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_LOCKED", "auth", "warn");
      throw new AuthException(HttpStatus.LOCKED, "AUTH_LOCKED", "Account is temporarily locked");
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
        throw new AuthException(HttpStatus.LOCKED, "AUTH_LOCKED", "Account is temporarily locked");
      }
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_FAILED", "auth", "warn");
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
    }

    if (!PortalSessionPolicy.allows(purpose,user.getRole()) || purpose==PortalPurpose.DEMO) {
      throw new AuthException(HttpStatus.FORBIDDEN,"AUTH_PORTAL_MISMATCH","Account cannot use this portal");
    }
    Organization org = organizationRepository.findById(user.getOrgId())
        .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Tenant is unavailable"));
    if (deployProperties.isPrivate() && !deployProperties.fixedOrgUuid().equals(org.getId())) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_PRIVATE_ORG_REJECTED", "auth", "high");
      throw new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Private deployment is fixed to one organization");
    }
    if (!"active".equalsIgnoreCase(org.getStatus())) {
      throw new AuthException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED", "Tenant is not active");
    }

    var current=accounts.load(user.getId(),"");
    List<String> permissions = current.principal().permissions();
    // Complete response reads before any Redis preparation. A term/read failure cannot disturb
    // this browser's existing identity or leave an undisclosed prepared payload behind.
    UserView loginUser=userView(user);
    OrgView loginOrg=orgView(org);
    TermView loginTerm=currentTermView(org.getId());
    boolean onboarded=user.isOnboarded();
    // Persist login-success bookkeeping via a direct UPDATE (not userRepository.save) so that
    // concurrent successful logins for the same account do not contend on the JPA @Version and
    // throw ObjectOptimisticLockingFailureException (P1-6). login_count is incremented in-place.
    jdbcTemplate.update(
        "update app_user set failed_attempts = 0, locked_until = null, last_login_at = ?, "
            + "last_login_ip = ?, login_count = login_count + 1, updated_at = now() where id = ?",
        ps -> {
          ps.setObject(1, java.sql.Timestamp.from(now));
          ps.setString(2, ip);
          ps.setObject(3, user.getId());
        });
    // Deliberately DO NOT mutate the managed `user` entity here: the login response and session
    // principal read only id/username/displayName/role/onboarded (all unchanged), and mutating the
    // managed entity would make Hibernate flush a second, @Version-checked UPDATE at commit — which
    // is exactly the optimistic-lock race two concurrent logins would lose (P1-6). The JDBC UPDATE
    // above is the sole persistence of the bookkeeping columns.
    audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_SUCCESS", "auth", "info");
    userRepository.flush();

    // Infrastructure exceptions stay outside noRollbackFor: failed issuance rolls bookkeeping back.
    SessionService.CreatedSession session = sessionService.replace(current.principal(),purpose,current.authVersion(),previousId);
    var envelope=session.envelope();
    return new LoginResult(new LoginResponse(loginUser,loginOrg,loginTerm,permissions,onboarded,
        envelope.audience(),envelope.context(),envelope.csrfToken(),envelope.expiresAt(),new DemoView(false)),session.cookie());
  }

  @Transactional(readOnly = true)
  public MeResponse me(SessionEnvelope envelope) {
    SessionPrincipal principal=envelope.principal();
    UserAccount user = userRepository.findById(principal.userId())
        .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session user is gone"));
    Organization org = organizationRepository.findById(envelope.audience()==PortalPurpose.DEMO?principal.orgId():TenantContext.get().orElse(principal.orgId()))
        .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Tenant is unavailable"));
    List<String> permissions = accounts.permissions(principal.orgId(), user.getRole());
    return toMeResponse(user, org, permissions,envelope);
  }

  @Transactional
  public void logout(SessionEnvelope envelope) {
    var principal=envelope.principal();
    try {
      if(envelope.audience()==PortalPurpose.DEMO)sessionService.revokeDemo(envelope);
      else sessionService.revoke(principal.sessionId(),envelope.audience());
    }
    catch(AuthException changed) {throw changed;}
    catch (RuntimeException unavailable) {
      throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_STATE_UNAVAILABLE","Logout could not be confirmed; retry");
    }
    audit(principal.orgId(), principal.userId(), "AUTH_LOGOUT", "auth", "info");
  }

  @Transactional
  public MeResponse markOnboarded(SessionEnvelope envelope) {
    var principal=envelope.principal();
    UserAccount user = userRepository.findById(principal.userId())
        .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session user is gone"));
    if (!user.isOnboarded()) {
      user.setOnboarded(true);
      userRepository.save(user);
      audit(user.getOrgId(), user.getId(), "AUTH_ONBOARDED", "auth", "info");
    }
    Organization org = organizationRepository.findById(TenantContext.get().orElse(principal.orgId()))
        .orElseThrow(() -> new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Tenant is unavailable"));
    List<String> permissions = accounts.permissions(principal.orgId(), user.getRole());
    return toMeResponse(user, org, permissions,envelope);
  }

  public PasswordResult changePassword(SessionEnvelope envelope, com.jkxt.security.api.AuthDtos.ChangePasswordRequest request) {
    var principal=envelope.principal();
    String next = request.newPassword() == null ? "" : request.newPassword().trim();
    if (next.length() < 8) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK", "新密码至少 8 位");
    }
    CurrentAccountAccess.Current changed;
    try {
      // Commit the DB-owned version before touching Redis. A failed Redis rotation cannot resurrect
      // a session with the old password/version. Row locking serializes simultaneous password changes.
      changed=transactions.execute(status -> {
        String hash=jdbcTemplate.query("select password_hash from app_user where id=? for update",
            rs->rs.next()?rs.getString(1):null,principal.userId());
        var current=accounts.load(principal.userId(),principal.sessionId());
        if(current.authVersion()!=envelope.authVersion() || !PortalSessionPolicy.allows(envelope.audience(),current.principal().role()))
          throw new AuthException(HttpStatus.UNAUTHORIZED,"AUTH_SESSION_INVALID","Identity changed; sign in again");
        if(hash==null || hash.isBlank() || request.currentPassword()==null || !passwordEncoder.matches(request.currentPassword(),hash))
          throw new AuthException(HttpStatus.BAD_REQUEST,"AUTH_CURRENT_PASSWORD_INVALID","当前密码不正确");
        jdbcTemplate.update("update app_user set password_hash=?,must_change_password=false,updated_at=now() where id=?",
            passwordEncoder.encode(next),principal.userId());
        audit(principal.orgId(),principal.userId(),"AUTH_PASSWORD_CHANGED","auth","info");
        userRepository.flush();
        return accounts.load(principal.userId(),"");
      });
    } catch(AuthException validation) { throw validation; }
    catch(RuntimeException databaseFailure) {
      // Commit may be uncertain. Require fresh credentials even when the transaction rolled back.
      try { sessionService.revokeOtherSessions(principal.userId(),null); }
      catch(RuntimeException unavailable) { databaseFailure.addSuppressed(unavailable); }
      throw reauthenticationRequired();
    }
    SessionService.CreatedSession rotated=null;
    try {
      rotated=sessionService.replace(changed.principal(),envelope.audience(),changed.authVersion(),principal.sessionId());
      sessionService.revokeOtherSessions(principal.userId(),rotated.principal().sessionId());
      return new PasswordResult(me(rotated.envelope()),rotated.cookie());
    } catch(RuntimeException redisFailure) {
      if(rotated!=null) try { sessionService.revoke(rotated.principal().sessionId(),envelope.audience()); }
      catch(RuntimeException unavailable) { redisFailure.addSuppressed(unavailable); }
      throw reauthenticationRequired();
    }
  }

  private AuthException reauthenticationRequired() {
    return new AuthException(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_REAUTHENTICATION_REQUIRED","Password update could not finish; sign in again");
  }

  private MeResponse toMeResponse(UserAccount user, Organization org, List<String> permissions,SessionEnvelope envelope) {
    return new MeResponse(
        userView(user),
        orgView(org),
        currentTermView(org.getId()),
        permissions,
        user.isOnboarded(),envelope.audience(),envelope.context(),envelope.csrfToken(),envelope.expiresAt(),
        envelope.audience()==PortalPurpose.DEMO?new DemoView(true,envelope.generation(),true):new DemoView(false),
        demoIdleDeadline(envelope)
    );
  }

  private Instant demoIdleDeadline(SessionEnvelope envelope) {
    if(envelope.audience()!=PortalPurpose.DEMO)return null;
    if(demo==null)throw new AuthException(HttpStatus.FORBIDDEN,"DEMO_DISABLED","Demo workspace validation unavailable");
    var w=demo.workspace(envelope.workspaceId(),false);
    demo.matches(w,new com.jkxt.demo.DemoLease(envelope.workspaceId(),envelope.principal().orgId(),envelope.generation(),envelope.expiresAt()));
    demo.ready(w);return w.idleExpiresAt();
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
  public record PasswordResult(MeResponse response, org.springframework.http.ResponseCookie cookie) {}
}
