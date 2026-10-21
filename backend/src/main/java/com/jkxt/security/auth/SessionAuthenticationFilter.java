package com.jkxt.security.auth;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.org.repo.OrganizationRepository;
import com.jkxt.security.repo.UserAccountRepository;
import com.jkxt.security.domain.UserAccount;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String SUPER_ADMIN = "SUPER_ADMIN";

  private final SessionService sessionService;
  private final DeployProperties deployProperties;
  private final OrganizationRepository organizationRepository;
  private final UserAccountRepository userRepository;

  public SessionAuthenticationFilter(
      SessionService sessionService,
      DeployProperties deployProperties,
      OrganizationRepository organizationRepository,
      UserAccountRepository userRepository
  ) {
    this.sessionService = sessionService;
    this.deployProperties = deployProperties;
    this.organizationRepository = organizationRepository;
    this.userRepository = userRepository;
  }

  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/school/login")
        || path.equals("/api/v1/auth/platform/login") || path.equals("/api/v1/auth/captcha")
        || path.equals("/api/v1/health") || path.startsWith("/actuator/health");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    try {
      try {
      // PB-4: X-JKXT-Portal only chooses which entry's cookie is read (missing = SCHOOL). An unknown value
      // is refused instead of falling back to whichever cookie happens to be present.
      AuthPortal portal;
      try {
        portal = AuthPortal.fromHeader(request.getHeader(AuthPortal.HEADER));
      } catch (AuthException invalid) {
        reject(response, 400, "AUTH_PORTAL_INVALID", "X-JKXT-Portal must be school or platform");
        return;
      }
      request.setAttribute(AuthRequestAttributes.PORTAL, portal);
      String sessionId = sessionId(request, portal);
      if (sessionId != null) {
        SessionPrincipal principal = sessionService.find(sessionId).orElse(null);
        if (principal != null && principal.portal() == null) {
          // Pre-isolation session without an audience: never silently upgraded to either entry.
          sessionService.revoke(sessionId);
          reject(response, 401, "AUTH_UNAUTHENTICATED", "Session predates portal isolation; sign in again");
          return;
        }
        if (principal != null && (principal.portal() != portal || !portal.admits(principal.role()))) {
          // A credential presented in the other entry's cookie slot is not this entry's session.
          reject(response, 401, "AUTH_UNAUTHENTICATED", "Session does not belong to this login entry");
          return;
        }
        if (principal != null) {
          UserAccount user = userRepository.findById(principal.userId()).orElse(null);
          if (user == null || !"active".equalsIgnoreCase(user.getStatus())
              || user.getAuthVersion() != principal.authVersion()
              || !user.getOrgId().equals(principal.orgId()) || !user.getRole().equals(principal.role())) {
            sessionService.revoke(sessionId);
            reject(response, 401, "AUTH_UNAUTHENTICATED", "Session credentials changed; sign in again");
            return;
          }
          boolean platformRecovery = SUPER_ADMIN.equals(principal.role()) && recoveryEndpoint(request);
          boolean organizationActive = organizationRepository.findById(principal.orgId())
              .filter(org -> "active".equalsIgnoreCase(org.getStatus())).isPresent();
          if (!organizationActive && !platformRecovery) {
            reject(response, 401, "AUTH_UNAUTHENTICATED", "Organization is unavailable");
            return;
          }
          if (user.isMustChangePassword() && !passwordChangeEndpoint(request)) {
            reject(response, 403, "AUTH_PASSWORD_CHANGE_REQUIRED", "请先修改初始密码后使用业务功能");
            return;
          }
          UUID contextOrgId = resolveTenantContextOrg(request, response, principal);
          if (contextOrgId == null) {
            return;
          }
          TenantContext.set(contextOrgId);
          response.setHeader(TENANT_HEADER, contextOrgId.toString());
          request.setAttribute(AuthRequestAttributes.PRINCIPAL, principal);
          SecurityContextHolder.getContext().setAuthentication(authentication(principal));
        }
      }
      } catch (DataAccessException unavailable) {
        // Account state is authoritative. Never reuse cached privileges during a storage outage.
        reject(response, 503, "AUTH_STATE_UNAVAILABLE", "暂时无法验证账号状态，请稍后重试");
        return;
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private boolean passwordChangeEndpoint(HttpServletRequest request) {
    String path = request.getRequestURI();
    return ("GET".equals(request.getMethod()) && "/api/v1/auth/me".equals(path))
        || ("POST".equals(request.getMethod()) && ("/api/v1/auth/logout".equals(path)
            || "/api/v1/auth/me/change-password".equals(path)));
  }

  private boolean recoveryEndpoint(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/api/v1/admin/") || passwordChangeEndpoint(request);
  }

  private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"status\":" + status + "}");
  }

  private UsernamePasswordAuthenticationToken authentication(SessionPrincipal principal) {
    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.role()));
    principal.permissions().forEach(permission ->
        authorities.add(new SimpleGrantedAuthority(permission))
    );
    return new UsernamePasswordAuthenticationToken(principal, null, authorities);
  }

  private UUID resolveTenantContextOrg(
      HttpServletRequest request,
      HttpServletResponse response,
      SessionPrincipal principal
  ) throws IOException {
    boolean platformRecovery = SUPER_ADMIN.equals(principal.role()) && recoveryEndpoint(request);
    String tenantHeader = request.getHeader(TENANT_HEADER);
    if (tenantHeader == null || tenantHeader.isBlank()) {
      UUID target = deployProperties.isPrivate() ? deployProperties.fixedOrgUuid() : principal.orgId();
      if (deployProperties.isPrivate() && !SUPER_ADMIN.equals(principal.role()) && !target.equals(principal.orgId())) {
        reject(response, 403, "TENANT_VIOLATION", "Private deployment is fixed to one organization");
        return null;
      }
      if (!platformRecovery && !target.equals(principal.orgId()) && !organizationRepository.findById(target)
          .filter(org -> "active".equalsIgnoreCase(org.getStatus())).isPresent()) {
        reject(response, 403, "TENANT_SUSPENDED", "Tenant is not active");
        return null;
      }
      return target;
    }
    if (!deployProperties.isSaas()) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, TENANT_HEADER + " is only enabled in SaaS mode");
      return null;
    }
    if (!SUPER_ADMIN.equalsIgnoreCase(principal.role())) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "Only SUPER_ADMIN can switch SaaS tenant context");
      return null;
    }
    try {
      UUID requestedOrgId = UUID.fromString(tenantHeader.trim());
      boolean activeTenant = organizationRepository.findById(requestedOrgId)
          .filter(org -> platformRecovery || "active".equalsIgnoreCase(org.getStatus()))
          .isPresent();
      if (!activeTenant) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND, "Tenant is unavailable");
        return null;
      }
      return requestedOrgId;
    } catch (IllegalArgumentException ex) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, TENANT_HEADER + " must be a UUID");
      return null;
    }
  }

  private String sessionId(HttpServletRequest request, AuthPortal portal) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (sessionService.cookieName(portal).equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
