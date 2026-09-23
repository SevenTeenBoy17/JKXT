package com.jkxt.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.security.auth.PortalPurpose;
import com.jkxt.security.auth.SessionService;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real isolated-profile accounts, captcha and HTTP authentication; never injects an identity. */
public final class PortalHttpFixture implements AutoCloseable {
  public static final String ORIGIN="http://localhost:8080";
  public record TestAccount(UUID id,String username,String password) {}
  public record AuthenticatedSession(Cookie cookie,String context,String csrfToken,PortalPurpose purpose) {}
  private final MockMvc http;
  private final ObjectMapper mapper;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder encoder;
  private final SessionService sessions;
  private final List<TestAccount> accounts=new ArrayList<>();
  private TestAccount superAdmin;
  public PortalHttpFixture(ApplicationContext context) {
    if(!context.getEnvironment().matchesProfiles("test")) throw new IllegalStateException("Test profile required");
    http=context.getBean(MockMvc.class); mapper=context.getBean(ObjectMapper.class);
    jdbc=context.getBean(JdbcTemplate.class); encoder=context.getBean(PasswordEncoder.class);
    sessions=context.getBean(SessionService.class);
    redis=context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
  }
  public TestAccount account(String role) {
    UUID org=jdbc.queryForObject("select id from organization where slug='demo' and status='active'",UUID.class);
    var a=new TestAccount(UUID.randomUUID(),"portal."+UUID.randomUUID(),"T!"+UUID.randomUUID());
    jdbc.update("insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password,onboarded,failed_attempts) values(?,?,?,?,?,?,'active',false,true,0)",
        a.id(),org,a.username(),"Portal HTTP test",encoder.encode(a.password()),role);
    accounts.add(a); return a;
  }
  public TestAccount superAdmin() { if(superAdmin==null) superAdmin=account("SUPER_ADMIN"); return superAdmin; }
  public CaptchaResponse captcha() throws Exception {
    var r=http.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
        .andExpect(jsonPath("$.captchaId").exists()).andExpect(jsonPath("$.imageBase64").exists())
        .andExpect(jsonPath("$.debugAnswer").exists()).andReturn();
    return mapper.readValue(r.getResponse().getContentAsString(),CaptchaResponse.class);
  }
  public MockHttpServletRequestBuilder loginRequest(PortalPurpose p,TestAccount a) throws Exception {
    return loginRequest(p,a,captcha());
  }
  public MockHttpServletRequestBuilder loginRequest(PortalPurpose p,TestAccount a,CaptchaResponse captcha) throws Exception {
    return post("/api/v1/auth/"+p.name().toLowerCase(Locale.ROOT)+"/login")
        .header("Origin",ORIGIN).contentType("application/json")
        .content(mapper.writeValueAsString(Map.of("username",a.username(),"password",a.password(),
            "captchaId",captcha.captchaId(),"captcha",captcha.debugAnswer())));
  }
  public MvcResult loginResult(PortalPurpose p,TestAccount a) throws Exception {
    return http.perform(loginRequest(p,a)).andReturn();
  }
  public AuthenticatedSession login(PortalPurpose p,TestAccount a) throws Exception {
    var r=http.perform(loginRequest(p,a)).andExpect(status().isOk()).andReturn();
    return from(r,p);
  }
  public AuthenticatedSession from(MvcResult result,PortalPurpose p) throws Exception {
    var json=mapper.readTree(result.getResponse().getContentAsString());
    Cookie cookie=result.getResponse().getCookie(sessions.cookieName(p));
    if(cookie==null || !json.path("audience").asText().equals(p.name())
        || json.path("sessionContext").asText().isBlank() || json.path("csrfToken").asText().isBlank())
      throw new AssertionError("Missing authenticated protocol fields");
    return new AuthenticatedSession(cookie,json.get("sessionContext").asText(),json.get("csrfToken").asText(),p);
  }
  /** Negative tests omit apply and send their missing/wrong headers explicitly. Never repairs them. */
  public MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder r,AuthenticatedSession s) {
    var request=r.buildRequest(new org.springframework.mock.web.MockServletContext());
    for(String name:List.of("Origin","X-JKXT-Portal","X-JKXT-Session-Context","X-JKXT-CSRF"))
      if(request.getHeader(name)!=null) throw new IllegalArgumentException("Authentication header already supplied: "+name);
    if(request.getCookies()!=null) throw new IllegalArgumentException("Cookies already supplied");
    return r.cookie(s.cookie()).header("Origin",ORIGIN).header("X-JKXT-Portal",s.purpose().name().toLowerCase(Locale.ROOT))
        .header("X-JKXT-Session-Context",s.context()).header("X-JKXT-CSRF",s.csrfToken());
  }
  public void expectMe(AuthenticatedSession s,int status) throws Exception {
    http.perform(apply(get("/api/v1/auth/me"),s)).andExpect(status().is(status));
  }
  public long sessionCount(UUID user) {
    var keys=redis.opsForSet().members("jkxt:user-sessions:v2:"+user);
    if(keys==null) return 0;
    long count=0;
    for(String key:keys) for(var purpose:PortalPurpose.values()) {
      String prefix="jkxt:session:"+purpose.name().toLowerCase(Locale.ROOT)+":";
      if(key.startsWith(prefix) && sessions.find(key.substring(prefix.length()),purpose).isPresent()) count++;
    }
    return count;
  }
  private final org.springframework.data.redis.core.StringRedisTemplate redis;
  @Override public void close() {
    for(var a:accounts) {
      sessions.revokeOtherSessions(a.id(),null);
      jdbc.update("delete from audit_log where actor_id=?",a.id());
      jdbc.update("delete from app_user where id=?",a.id());
    }
  }
}
