package com.jkxt.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.config.DeployProperties;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

@Service
public class DemoVisitorService {
  public record VisitorGrant(UUID visitorId,String startupContext,Instant expiresAt) {}
  private record StoredVisitor(UUID id,String context,Instant startupExpiry,Instant cookieExpiry) {}
  private final StringRedisTemplate redis;private final ObjectMapper mapper;private final Clock clock;
  private final DemoWorkspaceRepository repository;private final boolean secure;
  public DemoVisitorService(StringRedisTemplate redis,ObjectMapper mapper,ObjectProvider<Clock> clocks,
      DemoWorkspaceRepository repository,@Value("${jkxt.auth.cookie-secure:false}")boolean secure) {
    this.redis=redis;this.mapper=mapper;this.clock=clocks.getIfAvailable(Clock::systemUTC);this.repository=repository;this.secure=secure;
  }
  public String cookieName() { return (secure?"__Host-":"")+"JKXT_DEMO_VISITOR"; }
  public VisitorGrant ensureVisitor(HttpServletRequest request,HttpServletResponse response) {
    repository.requireDemo();String secret=cookie(request);StoredVisitor stored=read(secret);
    if(stored!=null) {
      UUID priorVisitor=stored.id();
      boolean expired=repository.transaction(()->{
        var w=repository.visitorWorkspace(priorVisitor);
        if(w==null)return false;
        if(w.state().equals("EXPIRED") || !repository.now().isBefore(w.idleExpiresAt())) {
          repository.jdbc.update("update demo_workspace set state='EXPIRED',cleanup_after=? where id=?",DemoWorkspaceRepository.timestamp(repository.now()),w.id());return true;
        }
        return false;
      });
      if(expired)stored=null;
    }
    if(stored==null || !clock.instant().isBefore(stored.cookieExpiry())) {
      secret=UUID.randomUUID().toString()+UUID.randomUUID();
      stored=new StoredVisitor(UUID.randomUUID(),"",Instant.EPOCH,Instant.EPOCH);
    }
    // Only deliberate bootstrap refreshes this SAME visitor secret. The extra startup window
    // covers a workspace allocated just before the captured proof expires; no workspace activity changes.
    Instant issued=clock.instant();Duration lifetime=Duration.ofHours(2).plusMinutes(10);
    stored=new StoredVisitor(stored.id(),UUID.randomUUID().toString()+UUID.randomUUID(),issued.plus(Duration.ofMinutes(10)),issued.plus(lifetime));
    redis.opsForValue().set(key(secret),json(stored),lifetime);
    response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(cookieName(),secret).httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(lifetime).build().toString());
    return new VisitorGrant(stored.id(),stored.context(),stored.startupExpiry());
  }
  /** Recheck the captured proof, never silently upgrade to a newly refreshed startup context. */
  public void requireCurrent(VisitorGrant captured,HttpServletRequest request) {
    if(!captured.equals(authenticate(request)))throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"AUTH_CSRF_INVALID","演示启动凭据已改变");
  }
  public VisitorGrant authenticate(HttpServletRequest request) {
    repository.requireDemo();StoredVisitor stored=read(cookie(request));
    var contexts=Collections.list(request.getHeaders("X-JKXT-Startup-Context"));
    if(stored==null || contexts.size()!=1 || !clock.instant().isBefore(stored.startupExpiry()) || !clock.instant().isBefore(stored.cookieExpiry())
        || !MessageDigest.isEqual(stored.context().getBytes(StandardCharsets.UTF_8),contexts.getFirst().getBytes(StandardCharsets.UTF_8)))
      throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"AUTH_CSRF_INVALID","演示启动凭据无效或已到期");
    return new VisitorGrant(stored.id(),stored.context(),stored.startupExpiry());
  }
  /** Lifecycle recovery uses the live visitor secret, not the short-lived startup proof. No renewal. */
  public void requireWorkspaceVisitor(HttpServletRequest request,UUID workspaceId) {
    repository.requireDemo();StoredVisitor stored=read(cookie(request));
    var workspace=repository.workspace(workspaceId,false);
    if(stored==null || !clock.instant().isBefore(stored.cookieExpiry()) || workspace==null || !workspace.visitor().equals(stored.id()))
      throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","需要当前工作区的有效访客凭据");
  }
  private String cookie(HttpServletRequest request) {
    String value=null;
    if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(cookieName().equals(cookie.getName())) {
      if(value!=null)throw DemoWorkspaceRepository.error(HttpStatus.BAD_REQUEST,"AUTH_COOKIE_AMBIGUOUS","重复访客凭据");value=cookie.getValue();
    }
    return value;
  }
  private StoredVisitor read(String secret) {
    if(secret==null || !secret.matches("[0-9a-f-]{72}"))return null;
    String json=redis.opsForValue().get(key(secret));if(json==null)return null;
    try{return mapper.readValue(json,StoredVisitor.class);}catch(Exception malformed){return null;}
  }
  private String json(StoredVisitor visitor) {try{return mapper.writeValueAsString(visitor);}catch(Exception e){throw new IllegalStateException("Visitor serialization failed");}}
  private static String key(String secret) {return "jkxt:demo:visitor:"+secret;}
}
