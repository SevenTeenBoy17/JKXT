package com.jkxt.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiErrorResponse;
import com.jkxt.common.web.TraceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
  public static final String PORTAL="X-JKXT-Portal";
  public static final String CONTEXT="X-JKXT-Session-Context";
  public static final String CSRF="X-JKXT-CSRF";
  private final SessionService sessions;
  private final SessionGuard guard;
  private final DeployProperties deploy;
  private final ObjectMapper mapper;
  private final Set<String> origins;
  private com.jkxt.demo.DemoVisitorService demoVisitors;
  private com.jkxt.demo.DemoRequestGuard demoRequests;
  @org.springframework.beans.factory.annotation.Autowired
  public void demoVisitorResolver(com.jkxt.demo.DemoVisitorService visitors) { this.demoVisitors=visitors; }
  @org.springframework.beans.factory.annotation.Autowired
  public void demoRequestResolver(com.jkxt.demo.DemoRequestGuard requests) { this.demoRequests=requests; }
  public SessionAuthenticationFilter(SessionService sessions,SessionGuard guard,DeployProperties deploy,
      ObjectMapper mapper,
      @Value("${jkxt.cors.allowed-origins}") String origins) {
    this.sessions=sessions; this.guard=guard; this.deploy=deploy;
    this.mapper=mapper;
    this.origins=Set.copyOf(Arrays.stream(origins.split(",")).map(String::trim).filter(s->!s.isBlank()).toList());
    if(this.origins.contains("*") || this.origins.contains("null"))
      throw new IllegalArgumentException("Credential origins must be explicit");
  }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !mappedPath(request).startsWith("/api/v1/");
  }
  /** Use the container's decoded mapping path, exactly as MVC sees the route. */
  private static String mappedPath(HttpServletRequest request) {
    String servletPath=request.getServletPath();
    if(servletPath!=null && !servletPath.isEmpty())
      return servletPath+(request.getPathInfo()==null?"":request.getPathInfo());
    // MockMvc and path-mapped servlet requests may expose only pathWithinApplication.
    return org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
  }
  @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
  @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
  @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
      throws ServletException,IOException {
    TenantContext.clear();
    SecurityContextHolder.clearContext();
    try {
      try { authenticate(req,res); }
      catch(AuthException failure) { error(res,failure.getStatus().value(),failure.getCode(),failure.getMessage()); return; }
      catch(org.springframework.dao.DataAccessException failure) {
        error(res,503,"AUTH_STATE_UNAVAILABLE","Authentication state is unavailable"); return;
      }
      try { chain.doFilter(req,res); }
      catch(ServletException | RuntimeException failure) {
        if(!committedSecurityRedispatch(req,res,failure))throw failure;
      }
    } finally {
      TenantContext.clear();
      SecurityContextHolder.clearContext();
      req.removeAttribute(AuthRequestAttributes.PRINCIPAL);
      req.removeAttribute(AuthRequestAttributes.ENVELOPE);
      req.removeAttribute(AuthRequestAttributes.DEMO_LEASE);
    }
  }
  private static boolean committedSecurityRedispatch(HttpServletRequest req,HttpServletResponse res,Throwable failure) {
    if(!res.isCommitted() || (req.getDispatcherType()!=jakarta.servlet.DispatcherType.ASYNC
        && req.getDispatcherType()!=jakarta.servlet.DispatcherType.ERROR))return false;
    // Spring's ExceptionTranslationFilter wraps a denied committed response in ServletException.
    // Only that wrapper family is traversed; unrelated runtime failures remain failures.
    Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
    for(Throwable cause=failure;cause!=null && seen.size()<8 && seen.add(cause);cause=cause.getCause()) {
      if(cause instanceof org.springframework.security.access.AccessDeniedException
          || cause instanceof org.springframework.security.core.AuthenticationException)return true;
      if(!(cause instanceof ServletException))return false;
    }
    return false;
  }
  private void authenticate(HttpServletRequest req,HttpServletResponse res) {
    String path=mappedPath(req);
    if(deploy.isDemo()) {
      if(demoRequests==null)fail(HttpStatus.SERVICE_UNAVAILABLE,"DEMO_POLICY_UNAVAILABLE","演示策略暂不可用");
      demoRequests.bootstrap(req,res,path);
    }
    PortalPurpose purpose=purpose(single(req,PORTAL));
    String context=single(req,CONTEXT),csrf=single(req,CSRF);
    String id=sessionId(req,purpose);
    if("OPTIONS".equals(req.getMethod())) return;
    boolean publicWrite=Set.of("/api/v1/auth/login","/api/v1/auth/school/login",
        "/api/v1/auth/platform/login","/api/v1/demo/visitor","/api/v1/demo/sessions").contains(path);
    if(publicWrite) {
      if(!"POST".equals(req.getMethod())) fail(HttpStatus.METHOD_NOT_ALLOWED,"AUTH_METHOD_INVALID","POST is required");
      String origin=single(req,"Origin");
      if(origin==null || !origins.contains(origin)) fail(HttpStatus.FORBIDDEN,"AUTH_ORIGIN_INVALID","Trusted Origin is required");
      try {
        String contentType=single(req,"Content-Type");
        MediaType type=contentType==null ? null : MediaType.parseMediaType(contentType);
        if(type==null || !"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype()))
          fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"AUTH_JSON_REQUIRED","JSON is required");
      } catch(org.springframework.http.InvalidMediaTypeException invalid) {
        fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"AUTH_JSON_REQUIRED","JSON is required");
      }
      res.setHeader("Cache-Control","no-store");
      return; // Login does not recover, refresh or revoke the previous identity.
    }
    if(Set.of("/api/v1/auth/captcha","/api/v1/health","/api/v1/version").contains(path) && !(purpose==PortalPurpose.DEMO && id!=null)) return;
    if(id==null) return;
    SessionEnvelope envelope=sessions.find(id,purpose).orElseThrow(() ->
        new AuthException(HttpStatus.UNAUTHORIZED,"AUTH_UNAUTHENTICATED","Session is unavailable"));
    boolean demoRecovery=purpose==PortalPurpose.DEMO && "POST".equals(req.getMethod())
        && Set.of("/api/v1/demo/workspace/reset","/api/v1/auth/logout").contains(path);
    envelope=guard.validateCurrent(envelope,demoRecovery);
    var principal=envelope.principal();
    UUID tenant=principal.orgId();
    String requested=single(req,"X-Tenant-Id");
    if(requested!=null) {
      if(!deploy.isSaas() || purpose!=PortalPurpose.PLATFORM || !"SUPER_ADMIN".equals(principal.role()))
        fail(HttpStatus.FORBIDDEN,"TENANT_VIOLATION","Tenant switching is not allowed");
      try { tenant=UUID.fromString(requested); }
      catch(IllegalArgumentException invalid) { fail(HttpStatus.BAD_REQUEST,"TENANT_INVALID","Tenant must be a UUID"); }
      if(!guard.activeOrganization(tenant))
        fail(HttpStatus.NOT_FOUND,"TENANT_UNAVAILABLE","Tenant is unavailable");
    }
    if(!(path.equals("/api/v1/auth/me") && "GET".equals(req.getMethod())) && !equal(context,envelope.context()))
      fail(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","Session context changed");
    if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod()) && !equal(csrf,envelope.csrfToken()))
      fail(HttpStatus.FORBIDDEN,"AUTH_CSRF_INVALID","Session CSRF token is required");
    if(purpose==PortalPurpose.DEMO) {
      if(demoRecovery) {
        if(demoVisitors==null)fail(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","演示访客验证不可用");
        demoVisitors.requireWorkspaceVisitor(req,envelope.workspaceId());
      }
      var lease=new com.jkxt.demo.DemoLease(envelope.workspaceId(),principal.orgId(),envelope.generation(),envelope.expiresAt());
      demoRequests.check(req,res,lease,path,demoRecovery);
      req.setAttribute(AuthRequestAttributes.DEMO_LEASE,lease);
    }
    if(path.startsWith("/api/v1/admin/") && purpose!=PortalPurpose.PLATFORM)
      fail(HttpStatus.FORBIDDEN,"AUTH_PORTAL_MISMATCH","Platform session is required");
    TenantContext.set(tenant);
    res.setHeader("X-Tenant-Id",tenant.toString());
    res.setHeader("Cache-Control","no-store");
    req.setAttribute(AuthRequestAttributes.PRINCIPAL,principal);
    req.setAttribute(AuthRequestAttributes.ENVELOPE,envelope);
    var authorities=new ArrayList<SimpleGrantedAuthority>();
    authorities.add(new SimpleGrantedAuthority("ROLE_"+principal.role()));
    principal.permissions().forEach(p->authorities.add(new SimpleGrantedAuthority(p)));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,authorities));
  }
  private PortalPurpose purpose(String value) {
    if(deploy.isDemo()) {
      if(value==null || value.equals("demo"))return PortalPurpose.DEMO;
      throw new AuthException(HttpStatus.FORBIDDEN,"AUTH_PORTAL_MISMATCH","Demo instance only accepts demo sessions");
    }
    if(value==null || value.equals("school")) return PortalPurpose.SCHOOL;
    if(value.equals("platform")) return PortalPurpose.PLATFORM;
    if(value.equals("demo") && deploy.isDemo()) return PortalPurpose.DEMO;
    throw new AuthException(HttpStatus.BAD_REQUEST,"AUTH_PORTAL_INVALID","Unknown or unavailable portal");
  }
  /** Reject repeated reserved headers, including servlet representations of repeated header lines. */
  private static String single(HttpServletRequest req,String name) {
    List<String> values=Collections.list(req.getHeaders(name));
    if(values.size()>1 || (!values.isEmpty() && (values.getFirst().isBlank() || values.getFirst().contains(","))))
      fail(HttpStatus.BAD_REQUEST,"AUTH_HEADER_INVALID","Ambiguous authentication header");
    return values.isEmpty()?null:values.getFirst();
  }
  public String sessionId(HttpServletRequest req,PortalPurpose purpose) {
    String value=null;
    Set<String> seen=new HashSet<>();
    if(req.getCookies()!=null) for(Cookie c:req.getCookies()) {
      boolean reserved=Arrays.stream(PortalPurpose.values()).anyMatch(p->sessions.cookieName(p).equals(c.getName()));
      if(reserved && !seen.add(c.getName())) fail(HttpStatus.BAD_REQUEST,"AUTH_COOKIE_AMBIGUOUS","Duplicate session cookie");
      if(sessions.cookieName(purpose).equals(c.getName())) value=c.getValue();
    }
    return value;
  }
  private static boolean equal(String a,String b) {
    return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
  }
  private static void fail(HttpStatus status,String code,String message) { throw new AuthException(status,code,message); }
  private void error(HttpServletResponse res,int status,String code,String message) throws IOException {
    // ASYNC/ERROR dispatch still authenticates; an already committed stream cannot carry JSON.
    // Its failing dispatch returns without entering the chain and the container closes the response.
    if(res.isCommitted())return;
    res.setStatus(status); res.setContentType(MediaType.APPLICATION_JSON_VALUE); res.setHeader("Cache-Control","no-store");
    String trace=TraceId.current();
    if(trace.isBlank()) trace=UUID.randomUUID().toString();
    res.setHeader(TraceId.HEADER,trace);
    mapper.writeValue(res.getWriter(),new ApiErrorResponse(code,message,status,java.time.Instant.now(),trace,List.of()));
  }
}
