package com.jkxt.security;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.security.auth.PortalPurpose;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest @AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE) @ActiveProfiles("test")
class AuthIntegrationTest {
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired ApplicationContext context;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  PortalHttpFixture fixture;
  PortalHttpFixture.TestAccount account;
  @BeforeEach void setup() { fixture=new PortalHttpFixture(context); account=fixture.account("ADMIN"); }
  @AfterEach void cleanup() { fixture.close(); }

  @Test void markOnboardedPersistsServerSideAcrossSessions() throws Exception {
    jdbcTemplate.update("update app_user set onboarded=false where id=?",account.id());
    MvcResult login=mockMvc.perform(fixture.loginRequest(PortalPurpose.SCHOOL,account))
        .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(false)).andReturn();
    var session=fixture.from(login,PortalPurpose.SCHOOL);
    mockMvc.perform(fixture.apply(post("/api/v1/auth/me/onboarded"),session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(true))
        .andExpect(jsonPath("$.user.username").value(account.username()));
    mockMvc.perform(fixture.apply(get("/api/v1/auth/me"),session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.onboarded").value(true));
    mockMvc.perform(post("/api/v1/auth/me/onboarded")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
  }

  @Test void loginMeAndLogoutUseHttpOnlyCookieSession() throws Exception {
    // Legacy /login remains SCHOOL. Obsolete caller-selected role is separately tested as rejected.
    var request=fixture.loginRequest(PortalPurpose.SCHOOL,account).buildRequest(new org.springframework.mock.web.MockServletContext());
    MvcResult login=mockMvc.perform(post("/api/v1/auth/login").header("Origin",PortalHttpFixture.ORIGIN)
        .contentType("application/json").content(request.getContentAsByteArray()))
        .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.SET_COOKIE,containsString("HttpOnly")))
        .andExpect(jsonPath("$.user.username").value(account.username())).andExpect(jsonPath("$.user.role").value("ADMIN"))
        .andExpect(jsonPath("$.org.slug").value("demo")).andExpect(jsonPath("$.term.yearLabel").value("2025-2026"))
        .andExpect(jsonPath("$.onboarded").value(true)).andExpect(jsonPath("$.permissions[0]").exists()).andReturn();
    var session=fixture.from(login,PortalPurpose.SCHOOL);
    mockMvc.perform(fixture.apply(get("/api/v1/auth/me"),session)).andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value(account.username())).andExpect(jsonPath("$.org.slug").value("demo"))
        .andExpect(jsonPath("$.permissions[0]").exists());
    mockMvc.perform(fixture.apply(post("/api/v1/auth/logout"),session)).andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.SET_COOKIE,containsString("Max-Age=0"))).andExpect(jsonPath("$.ok").value(true));
    mockMvc.perform(fixture.apply(get("/api/v1/auth/me"),session)).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
  }

  @Test void invalidCaptchaAndUnauthenticatedBusinessAccessReturnStableErrorCodes() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login").header("Origin",PortalHttpFixture.ORIGIN).contentType("application/json")
        .content(objectMapper.writeValueAsString(java.util.Map.of("username",account.username(),"password",account.password(),
            "captchaId","missing","captcha","WRONG"))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH_CAPTCHA_FAILED"));
    mockMvc.perform(get("/api/v1/exam-tasks")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
  }

  @Test void concurrentSameAccountLoginAllSucceed() throws Exception {
    // Original six simultaneous successes and independent single-use captcha coverage is retained.
    int n=6;
    java.util.List<CaptchaResponse> captchas=new java.util.ArrayList<>();
    for(int i=0;i<n;i++) captchas.add(fixture.captcha());
    var pool=java.util.concurrent.Executors.newFixedThreadPool(n);
    try {
      var ready=new java.util.concurrent.CountDownLatch(n); var go=new java.util.concurrent.CountDownLatch(1);
      java.util.List<java.util.concurrent.Future<Integer>> futures=new java.util.ArrayList<>();
      for(int i=0;i<n;i++) {
        var captcha=captchas.get(i);
        futures.add(pool.submit(()->{ ready.countDown(); go.await();
          return mockMvc.perform(fixture.loginRequest(PortalPurpose.SCHOOL,account,captcha)).andReturn().getResponse().getStatus(); }));
      }
      ready.await(); go.countDown();
      for(var future:futures) assertEquals(200,future.get(),"every concurrent same-account login must return 200 (no 500/409 optimistic-lock failure)");
    } finally { pool.shutdownNow(); }
    assertEquals(n,fixture.sessionCount(account.id()));
  }

  @Test void changePasswordVerifiesCurrentRejectsWeakPersistsAndIsSelfService() throws Exception {
    var session=fixture.login(PortalPurpose.SCHOOL,account);
    mockMvc.perform(fixture.apply(post("/api/v1/auth/me/change-password"),session).contentType("application/json")
        .content(changeBody("WRONGpwd!!","NewTest@2026")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH_CURRENT_PASSWORD_INVALID"));
    mockMvc.perform(fixture.apply(post("/api/v1/auth/me/change-password"),session).contentType("application/json")
        .content(changeBody(account.password(),"short")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_TOO_WEAK"));
    mockMvc.perform(fixture.apply(post("/api/v1/auth/me/change-password"),session).contentType("application/json")
        .content(changeBody(account.password(),"NewTest@2026"))).andExpect(status().isOk());
    assertEquals(401,fixture.loginResult(PortalPurpose.SCHOOL,account).getResponse().getStatus());
    assertEquals(200,fixture.loginResult(PortalPurpose.SCHOOL,new PortalHttpFixture.TestAccount(account.id(),account.username(),"NewTest@2026")).getResponse().getStatus());
    mockMvc.perform(post("/api/v1/auth/me/change-password").contentType("application/json").content(changeBody("x","yyyyyyyy")))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
  }

  @Test void changePasswordClearsMustChangePasswordFlag() throws Exception {
    jdbcTemplate.update("update app_user set must_change_password=true where id=?",account.id());
    var session=fixture.login(PortalPurpose.SCHOOL,account);
    mockMvc.perform(fixture.apply(post("/api/v1/auth/me/change-password"),session).contentType("application/json")
        .content(changeBody(account.password(),"NewTest@2026"))).andExpect(status().isOk());
    assertEquals(Boolean.FALSE,jdbcTemplate.queryForObject("select must_change_password from app_user where id=?",Boolean.class,account.id()),
        "改密后 must_change_password 必须复位为 false,否则构成强制改密死循环");
  }

  @Test void changePasswordRevokesOtherSessionsButKeepsCurrent() throws Exception {
    var a=fixture.login(PortalPurpose.SCHOOL,account); var b=fixture.login(PortalPurpose.SCHOOL,account);
    fixture.expectMe(a,200); fixture.expectMe(b,200);
    var result=mockMvc.perform(fixture.apply(post("/api/v1/auth/me/change-password"),a).contentType("application/json")
        .content(changeBody(account.password(),"NewTest@2026"))).andExpect(status().isOk()).andReturn();
    // Current browser adopts the security rotation response; it keeps login with the new cookie.
    var rotated=fixture.from(result,PortalPurpose.SCHOOL); fixture.expectMe(rotated,200);
    assertNotEquals(a.cookie().getValue(),rotated.cookie().getValue());
    assertNotEquals(a.context(),rotated.context()); assertNotEquals(a.csrfToken(),rotated.csrfToken());
    mockMvc.perform(fixture.apply(get("/api/v1/auth/me"),b)).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    fixture.expectMe(a,401);
  }
  private String changeBody(String current,String next) throws Exception {
    return objectMapper.writeValueAsString(java.util.Map.of("currentPassword",current,"newPassword",next));
  }
}
