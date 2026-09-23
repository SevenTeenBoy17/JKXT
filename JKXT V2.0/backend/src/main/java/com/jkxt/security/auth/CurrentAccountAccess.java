package com.jkxt.security.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.config.DeployProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Fresh SQL avoids a stale JPA persistence context, including DB-owned auth_version. */
@Service
public class CurrentAccountAccess {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final DeployProperties deploy;
  public CurrentAccountAccess(JdbcTemplate jdbc, ObjectMapper mapper, DeployProperties deploy) {
    this.jdbc = jdbc; this.mapper = mapper; this.deploy = deploy;
  }
  public record Current(SessionPrincipal principal, long authVersion) {}
  public SessionPrincipal load(UUID userId) { return load(userId, "").principal(); }
  public boolean activeOrganization(UUID orgId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists(select 1 from organization where id=? and status='active')",Boolean.class,orgId));
  }
  public Current load(UUID userId, String sessionId) {
    Current current = jdbc.query("""
        select u.id,u.org_id,u.username,u.display_name,u.role,u.auth_version
        from app_user u join organization o on o.id=u.org_id
        where u.id=? and u.status='active' and u.deleted_at is null and o.status='active'
        """, rs -> rs.next() ? new Current(new SessionPrincipal(sessionId,
            rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class),
            rs.getString("username"), rs.getString("display_name"), rs.getString("role"),
            List.of()), rs.getLong("auth_version")) : null, userId);
    if (current == null) throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_INVALID", "Account or tenant is unavailable");
    SessionPrincipal p = current.principal();
    if (deploy.isPrivate() && !deploy.fixedOrgUuid().equals(p.orgId()))
      throw new AuthException(HttpStatus.FORBIDDEN, "TENANT_VIOLATION", "Private deployment is fixed to one organization");
    return new Current(new SessionPrincipal(sessionId, p.userId(), p.orgId(), p.username(),
        p.displayName(), p.role(), permissions(p.orgId(), p.role())), current.authVersion());
  }
  public List<String> permissions(UUID org, String role) {
    if ("DEMO".equals(role)) return deploy.isDemo() ? com.jkxt.demo.DemoRoutePolicy.PERMISSIONS : List.of();
    String json = jdbc.query("select permissions::text from role where org_id=? and code=? order by system_role desc limit 1",
        rs -> rs.next() ? rs.getString(1) : null, org, role);
    // Only a missing role row may use the canonical system-role fallback. [] means revoked.
    if (json == null) json = jdbc.query("select permissions::text from role where code=? and system_role=true order by org_id limit 1",
        rs -> rs.next() ? rs.getString(1) : "[]", role);
    try { return List.copyOf(mapper.readValue(json, new TypeReference<List<String>>() {})); }
    catch (Exception invalid) { throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_STATE_UNAVAILABLE", "Permission state cannot be read"); }
  }
}
