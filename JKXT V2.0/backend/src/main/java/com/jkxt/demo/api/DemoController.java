package com.jkxt.demo.api;

import com.jkxt.demo.*;
import com.jkxt.demo.api.DemoDtos.*;
import com.jkxt.security.auth.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/demo")
public class DemoController {
  private final DemoVisitorService visitors;private final DemoWorkspaceService workspaces;private final DemoWorkspaceRepository repository;
  private final SessionService sessions;private final AuthService auth;private final SessionAuthenticationFilter filter;
  private final DemoRequestGuard requests;
  public DemoController(DemoVisitorService visitors,DemoWorkspaceService workspaces,DemoWorkspaceRepository repository,SessionService sessions,AuthService auth,SessionAuthenticationFilter filter,DemoRequestGuard requests) {
    this.visitors=visitors;this.workspaces=workspaces;this.repository=repository;this.sessions=sessions;this.auth=auth;this.filter=filter;
    this.requests=requests;
  }
  @PostMapping("/visitor") public DemoVisitorService.VisitorGrant visitor(@RequestBody Map<String,Object> body,HttpServletRequest request,HttpServletResponse response) {
    if(!body.isEmpty())throw DemoWorkspaceRepository.error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","访客身份由服务器分配");
    return visitors.ensureVisitor(request,response);
  }
  @PostMapping("/sessions") public WorkspaceResponse session(@Valid @RequestBody SessionRequest body,HttpServletRequest request,HttpServletResponse response) {
    var visitor=visitors.authenticate(request);String id=filter.sessionId(request,PortalPurpose.DEMO);
    Runnable proof=()->visitors.requireCurrent(visitor,request);
    if(body.intent()==Intent.RESTORE && body.operationId()!=null || body.intent()!=Intent.RESTORE && body.operationId()==null)
      throw DemoWorkspaceRepository.error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","RESTORE 不接受 operationId，其他意图必须提供 operationId");
    DemoWorkspaceService.WorkspaceGrant grant;
    boolean cookie=false;
    switch(body.intent()) {
      case START -> {
        try { grant=workspaces.start(visitor.visitorId(),body.operationId().toString(),DemoWorkspaceService.hash("START"),proof,requests.clientIp(request)); }
        catch(DemoQuotaService.RateExceeded failure){response.setHeader("Retry-After",Long.toString(failure.retryAfter()));throw failure;}
        if(id!=null && id.equals(grant.envelope().principal().sessionId()))grant=workspaces.restore(visitor.visitorId(),id,proof);
        else {grant=workspaces.initialDelivery(visitor.visitorId(),body.operationId().toString(),false,id,proof);cookie=true;}
      }
      case RECOVER_INITIAL -> {grant=workspaces.initialDelivery(visitor.visitorId(),body.operationId().toString(),true,id,proof);cookie=!grant.envelope().principal().sessionId().equals(id);}
      case REISSUE -> {grant=workspaces.reissue(visitor.visitorId(),body.operationId().toString(),proof);cookie=!grant.envelope().principal().sessionId().equals(id);}
      case RESTORE -> grant=workspaces.restore(visitor.visitorId(),id,proof);
      default -> throw new IllegalStateException("Unhandled demo intent");
    }
    WorkspaceResponse result=response(grant);
    if(cookie)response.addHeader(HttpHeaders.SET_COOKIE,sessions.demoCookie(grant.envelope(),repository.now()).toString());
    return result;
  }
  @PostMapping("/workspace/reset") public WorkspaceResponse reset(@Valid @RequestBody ResetRequest body,HttpServletRequest request) {
    if(!body.confirmed())throw DemoWorkspaceRepository.error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","请确认重置演示数据");
    if(!(request.getAttribute(AuthRequestAttributes.DEMO_LEASE) instanceof DemoLease lease))
      throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","需要演示会话");
    return response(workspaces.reset(lease,body.operationId().toString()));
  }
  private WorkspaceResponse response(DemoWorkspaceService.WorkspaceGrant grant) {
    return repository.transaction(()->{
      var w=repository.workspace(grant.lease().workspaceId(),true);repository.matches(w,grant.lease());repository.ready(w);
      var current=sessions.find(grant.envelope().principal().sessionId(),PortalPurpose.DEMO)
          .orElseThrow(()->DemoWorkspaceRepository.error(HttpStatus.UNAUTHORIZED,"AUTH_UNAUTHENTICATED","会话已撤销"));
      if(!current.context().equals(grant.envelope().context()))throw DemoWorkspaceRepository.error(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","会话已改变");
      var me=auth.me(current);
      return new WorkspaceResponse(me.user(),me.org(),me.term(),me.permissions(),me.onboarded(),me.audience(),me.sessionContext(),me.csrfToken(),me.expiresAt(),me.demo(),w.id(),w.state(),w.idleExpiresAt());
    });
  }
}
