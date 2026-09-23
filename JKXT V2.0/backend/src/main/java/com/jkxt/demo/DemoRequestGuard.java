package com.jkxt.demo;

import com.jkxt.security.auth.ClientIpResolver;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Runs after session/context/CSRF validation and before an admitted DEMO handler. */
@Component
public class DemoRequestGuard {
  public static final String ACTIVITY="X-JKXT-Activity";
  private static final String BOOTSTRAP_DONE=DemoRequestGuard.class.getName()+".bootstrap";
  private static final String BUSINESS_DONE=DemoRequestGuard.class.getName()+".business";
  private static final Map<String,String> MASTER=Map.of("teachers","teacher","classes","school_class","classrooms","classroom","subjects","subject");
  private static final Set<String> UPLOAD_KINDS=Set.of("teacher","teachers","t01","classroom","classrooms","room","rooms","t02","exam","exams","schedule","schedules","t03");
  private final DemoRoutePolicy routes;private final DemoQuotaService quotas;
  private final DemoWorkspaceRepository repository;private final ClientIpResolver ips;
  public DemoRequestGuard(DemoRoutePolicy routes,DemoQuotaService quotas,DemoWorkspaceRepository repository,ClientIpResolver ips){this.routes=routes;this.quotas=quotas;this.repository=repository;this.ips=ips;}
  public String clientIp(HttpServletRequest request){return ips.resolve(request);}
  public void bootstrap(HttpServletRequest request,HttpServletResponse response,String path) {
    if("POST".equals(request.getMethod()) && Set.of("/api/v1/demo/visitor","/api/v1/demo/sessions").contains(path)
        && request.getDispatcherType()==DispatcherType.REQUEST && request.getAttribute(BOOTSTRAP_DONE)==null) {
      request.setAttribute(BOOTSTRAP_DONE,Boolean.TRUE);
      attempt("bootstrap",clientIp(request),response);
    }
  }
  public void check(HttpServletRequest request,HttpServletResponse response,DemoLease lease,String path,boolean recovery) {
    if(!DemoRoutePolicy.safePath(request.getRequestURI()) || !routes.allows(request.getMethod(),path))throw forbidden();
    boolean initial=request.getDispatcherType()==DispatcherType.REQUEST && request.getAttribute(BUSINESS_DONE)==null;
    if(initial) {
      request.setAttribute(BUSINESS_DONE,Boolean.TRUE);
      if("POST".equals(request.getMethod())) {
        if(path.equals("/api/v1/demo/workspace/reset"))attempt("reset",repository.workspace(lease.workspaceId(),false).visitor().toString(),response);
        else if(path.startsWith("/api/v1/import/") || path.endsWith("/uploads"))attempt("upload",lease.workspaceId().toString(),response);
        else if(path.endsWith("/schedule-jobs"))attempt("solve",lease.workspaceId().toString(),response);
      }
    }
    validateParameters(request,path);
    ownership(lease,path);
    var activities=Collections.list(request.getHeaders(ACTIVITY));
    if(activities.size()>1)throw DemoWorkspaceRepository.error(HttpStatus.BAD_REQUEST,"DEMO_ACTIVITY_INVALID","活动标识重复");
    if(initial && !recovery && activities.size()==1 && "foreground".equals(activities.getFirst()) && routes.foregroundEligible(request.getMethod(),path))repository.touchActivity(lease);
  }
  private void validateParameters(HttpServletRequest request,String path) {
    if(path.equals("/api/v1/import/validate")) {
      String kind=parameter(request,"kind");
      if(kind!=null && !DemoRoutePolicy.IMPORT_KINDS.contains(kind))throw forbidden();
    }
    if(path.endsWith("/uploads")) {
      String kind=parameter(request,"kind"),sample=parameter(request,"sample");
      if(kind!=null && !UPLOAD_KINDS.contains(kind))throw forbidden();
      if(sample!=null && !sample.equals("false"))throw forbidden();
    }
    if(path.endsWith("/export")) {
      String format=parameter(request,"format");
      if(format!=null && !Set.of("xlsx","pdf").contains(format))throw forbidden();
    }
  }
  private static String parameter(HttpServletRequest request,String name) {
    String[] values=request.getParameterValues(name);
    if(values!=null && values.length!=1)throw DemoWorkspaceRepository.error(HttpStatus.BAD_REQUEST,"DEMO_PARAMETER_INVALID","参数重复");
    return values==null?null:values[0];
  }
  private void ownership(DemoLease lease,String path) {
    String[] parts=path.substring("/api/v1/".length()).split("/");
    String table=null,id=null;
    if(parts.length>1 && MASTER.containsKey(parts[0])){table=MASTER.get(parts[0]);id=parts[1];}
    else if(parts.length>1 && Set.of("exam-tasks","tasks").contains(parts[0])){table="exam_task";id=parts[1];}
    else if(parts.length>1 && parts[0].equals("schedule-jobs")){table="schedule_run";id=parts[1];}
    else if(parts.length==4 && parts[0].equals("demo") && parts[1].equals("tasks")){table="exam_task";id=parts[2];}
    if(table!=null && !Boolean.TRUE.equals(repository.jdbc.queryForObject("select exists(select 1 from "+table+" where id=? and org_id=?)",Boolean.class,UUID.fromString(id),lease.orgId())))throw forbidden();
  }
  private void attempt(String operation,String key,HttpServletResponse response){
    try{quotas.consumeAttempt(operation,key);}catch(DemoQuotaService.RateExceeded failure){response.setHeader("Retry-After",Long.toString(failure.retryAfter()));throw failure;}
  }
  private static com.jkxt.security.auth.AuthException forbidden(){return DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","演示环境不允许此操作或资源");}
}
