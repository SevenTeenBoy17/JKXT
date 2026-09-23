package com.jkxt.security.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SessionGuard {
  private final CurrentAccountAccess accounts;
  private com.jkxt.demo.DemoWorkspaceRepository demo;
  public SessionGuard(CurrentAccountAccess accounts) { this.accounts=accounts; }
  @org.springframework.beans.factory.annotation.Autowired
  public void demoResolver(com.jkxt.demo.DemoWorkspaceRepository demo) { this.demo=demo; }
  public boolean activeOrganization(java.util.UUID orgId) { return accounts.activeOrganization(orgId); }
  public SessionEnvelope validateCurrent(SessionEnvelope envelope) {
    return validateCurrent(envelope,false);
  }
  public SessionEnvelope validateCurrent(SessionEnvelope envelope,boolean recoveryOnly) {
    if(envelope.audience()==PortalPurpose.DEMO) {
      if(demo==null)throw new AuthException(HttpStatus.FORBIDDEN,"DEMO_DISABLED","Demo workspace validation is unavailable");
      demo.requireDemo();
      if(envelope.workspaceId()==null || envelope.generation()==null)throw new AuthException(HttpStatus.UNAUTHORIZED,"AUTH_SESSION_INVALID","Demo identity is incomplete");
      var w=demo.workspace(envelope.workspaceId(),false);
      demo.matches(w,new com.jkxt.demo.DemoLease(envelope.workspaceId(),envelope.principal().orgId(),envelope.generation(),envelope.expiresAt()));
      if(!recoveryOnly)demo.ready(w);
      if(!demo.user(w.id(),w.generation()).equals(envelope.principal().userId()))throw new AuthException(HttpStatus.CONFLICT,"DEMO_STALE_GENERATION","Demo ownership changed");
    }
    var current=accounts.load(envelope.principal().userId(),envelope.principal().sessionId());
    if(current.authVersion()!=envelope.authVersion()
        || !current.principal().orgId().equals(envelope.principal().orgId())
        || !PortalSessionPolicy.allows(envelope.audience(),current.principal().role()))
      throw new AuthException(HttpStatus.UNAUTHORIZED,"AUTH_SESSION_INVALID","Account authorization changed");
    return envelope.withPrincipal(current.principal());
  }
}
