package com.jkxt.scheduling.service;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.demo.*;
import com.jkxt.security.auth.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Captures immutable server identity and reauthorizes without renewing session/activity. */
@Component
public class ScheduleStreamAuthorization {
  public record Owner(UUID orgId,UUID runId,DemoLease lease,UUID reservationId) {}
  public record Subscription(SessionEnvelope envelope,UUID effectiveTenant,Owner owner) {}
  private final SessionService sessions;private final SessionGuard guard;private final WorkspaceFence fence;
  private final JdbcTemplate jdbc;private final DeployProperties deploy;
  private final DemoSolveJobService jobs;
  public ScheduleStreamAuthorization(SessionService sessions,SessionGuard guard,WorkspaceFence fence,JdbcTemplate jdbc,DeployProperties deploy,DemoSolveJobService jobs) {
    this.sessions=sessions;this.guard=guard;this.fence=fence;this.jdbc=jdbc;this.deploy=deploy;
    this.jobs=jobs;
  }
  public Subscription capture(UUID runId,SessionEnvelope envelope,UUID tenant,DemoLease lease) {
    if(envelope==null || tenant==null || deploy.isDemo()!=(lease!=null))throw denied();
    UUID reservation=null;
    if(lease!=null)reservation=jdbc.query("select reservation_id from demo_solve_job where run_id=? and workspace_id=? and generation=? and org_id=?",
        rs->rs.next()?rs.getObject(1,UUID.class):null,runId,lease.workspaceId(),lease.generation(),lease.orgId());
    var captured=new Subscription(envelope,tenant,new Owner(tenant,runId,lease,reservation));
    if(!allowed(captured))throw denied();return captured;
  }
  public Owner formalOwner(UUID runId) {
    if(deploy.isDemo())throw denied();
    UUID org=jdbc.query("select org_id from schedule_run where id=?",rs->rs.next()?rs.getObject(1,UUID.class):null,runId);
    if(org==null)throw denied();return new Owner(org,runId,null,null);
  }
  public boolean allowed(Subscription captured) {
    try {
      var old=captured.envelope();var oldPrincipal=old.principal();
      var found=sessions.find(oldPrincipal.sessionId(),old.audience()).orElse(null);
      if(found==null || !Objects.equals(old.context(),found.context()) || old.authVersion()!=found.authVersion()
          || old.audience()!=found.audience() || !old.expiresAt().equals(found.expiresAt())
          || !Objects.equals(old.workspaceId(),found.workspaceId()) || !Objects.equals(old.generation(),found.generation())
          || !oldPrincipal.userId().equals(found.principal().userId()) || !oldPrincipal.orgId().equals(found.principal().orgId()))return false;
      var current=guard.validateCurrent(found);var principal=current.principal();
      if(!principal.permissions().contains("tenant:read"))return false;
      if(!captured.effectiveTenant().equals(principal.orgId())
          && !(deploy.isSaas() && current.audience()==PortalPurpose.PLATFORM && principal.role().equals("SUPER_ADMIN")
              && guard.activeOrganization(captured.effectiveTenant())))return false;
      var owner=captured.owner();if(!owner.orgId().equals(captured.effectiveTenant()))return false;
      if(owner.lease()!=null) {
        var lease=owner.lease();
        if(current.audience()!=PortalPurpose.DEMO || !lease.orgId().equals(principal.orgId())
            || !lease.workspaceId().equals(current.workspaceId()) || !Objects.equals(lease.generation(),current.generation())
            || !lease.expiresAt().equals(current.expiresAt()) || owner.reservationId()==null)return false;
        fence.assertReadable(lease);
        if(!jobs.streamAllowed(owner.reservationId()) || !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from demo_solve_job where reservation_id=? and run_id=? and workspace_id=? and generation=? and org_id=? and state not in ('CANCEL_REQUESTED','CANCELLED'))",
            Boolean.class,owner.reservationId(),owner.runId(),lease.workspaceId(),lease.generation(),lease.orgId())))return false;
      } else if(deploy.isDemo() || current.audience()==PortalPurpose.DEMO)return false;
      return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from schedule_run where id=? and org_id=?)",Boolean.class,owner.runId(),owner.orgId()));
    }catch(RuntimeException unavailable){return false;}
  }
  private static AuthException denied(){return new AuthException(HttpStatus.FORBIDDEN,"SCHEDULE_STREAM_FORBIDDEN","当前会话无权订阅此排考进度");}
}
