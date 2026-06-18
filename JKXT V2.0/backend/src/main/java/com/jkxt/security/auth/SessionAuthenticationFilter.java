package com.jkxt.security.auth;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.org.repo.OrganizationRepository;
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
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String SUPER_ADMIN = "SUPER_ADMIN";

  private final SessionService sessionService;
  private final DeployProperties deployProperties;
  private final OrganizationRepository organizationRepository;

  public SessionAuthenticationFilter(
      SessionService sessionService,
      DeployProperties deployProperties,
      OrganizationRepository organizationRepository
  ) {
    this.sessionService = sessionService;
    this.deployProperties = deployProperties;
    this.organizationRepository = organizationRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    try {
      String sessionId = sessionId(request);
      if (sessionId != null) {
        SessionPrincipal principal = sessionService.find(sessionId).orElse(null);
        if (principal != null) {
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
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
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
    String tenantHeader = request.getHeader(TENANT_HEADER);
    if (tenantHeader == null || tenantHeader.isBlank()) {
      return principal.orgId();
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
          .filter(org -> "active".equalsIgnoreCase(org.getStatus()))
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

  private String sessionId(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (sessionService.cookieName().equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
