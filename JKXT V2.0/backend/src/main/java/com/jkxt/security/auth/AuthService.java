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

  @Transactional
  public LoginResult login(LoginRequest request, String ip) {
    if (!captchaService.consume(request.captchaId(), request.captcha())) {
      throw new AuthException(HttpStatus.BAD_REQUEST, "AUTH_CAPTCHA_FAILED", "Captcha is invalid or expired");
    }

    String username = request.username().trim().toLowerCase(Locale.ROOT);
    UserAccount user = userRepository.findFirstByUsernameIgnoreCase(username)
        .orElseThrow(() -> new AuthException(
            HttpStatus.UNAUTHORIZED,
            "AUTH_INVALID_CREDENTIALS",
            "Invalid username or password"
        ));

    if (!"active".equalsIgnoreCase(user.getStatus())) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_DISABLED", "auth", "warn");
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
    }

    Instant now = Instant.now();
    if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_LOCKED", "auth", "warn");
      throw new AuthException(HttpStatus.LOCKED, "AUTH_LOCKED", "Account is temporarily locked");
    }

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      int attempts = user.getFailedAttempts() + 1;
      user.setFailedAttempts(attempts);
      if (attempts >= MAX_FAILED_ATTEMPTS) {
        user.setLockedUntil(now.plus(30, ChronoUnit.MINUTES));
        audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_LOCKED_AFTER_FAILURES", "auth", "high");
        userRepository.save(user);
        throw new AuthException(HttpStatus.LOCKED, "AUTH_LOCKED", "Account is temporarily locked");
      }
      audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_FAILED", "auth", "warn");
      userRepository.save(user);
      throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password");
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

    List<String> permissions = permissions(user.getOrgId(), user.getRole());
    user.setFailedAttempts(0);
    user.setLockedUntil(null);
    user.setLastLoginAt(now);
    user.setLastLoginIp(ip);
    user.setLoginCount(user.getLoginCount() + 1);
    userRepository.save(user);
    audit(user.getOrgId(), user.getId(), "AUTH_LOGIN_SUCCESS", "auth", "info");

    SessionPrincipal principal = new SessionPrincipal(
        "",
        user.getId(),
        user.getOrgId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getRole(),
        permissions
    );
    SessionService.CreatedSession session = sessionService.create(principal);
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
    UserAccount user = userRepository.findById(principal.userId())
        .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Session user is gone"));
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
    String json = jdbcTemplate.query(
        "select permissions::text from role where org_id = ? and code = ? order by system_role desc limit 1",
        ps -> {
          ps.setObject(1, orgId);
          ps.setString(2, role);
        },
        rs -> rs.next() ? rs.getString(1) : "[]"
    );
    try {
      return objectMapper.readValue(json, STRING_LIST);
    } catch (JsonProcessingException exception) {
      return List.of();
    }
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
}
