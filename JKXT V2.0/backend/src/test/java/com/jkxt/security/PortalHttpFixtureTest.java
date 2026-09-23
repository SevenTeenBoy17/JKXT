package com.jkxt.security;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.jkxt.security.auth.PortalPurpose;

@SpringBootTest @AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE) @ActiveProfiles("test")
class PortalHttpFixtureTest {
  @Autowired ApplicationContext context;
  @Test void createsRealAccountsAndIndependentCaptchaSessions() throws Exception {
    try(var f=new PortalHttpFixture(context)) {
      assertNotEquals(f.captcha().captchaId(),f.captcha().captchaId());
      var a=f.account("ADMIN"); var s=f.login(PortalPurpose.SCHOOL,a);
      f.expectMe(s,200); assertEquals(1,f.sessionCount(a.id()));
      assertTrue(s.cookie().isHttpOnly());
      assertThrows(IllegalArgumentException.class,()->f.apply(get("/api/v1/auth/me")
          .header("X-JKXT-CSRF","deliberately-wrong"),s));
      assertThrows(IllegalArgumentException.class,()->f.apply(get("/api/v1/auth/me")
          .header("Origin","http://evil.example"),s));
      assertEquals(PortalHttpFixture.ORIGIN,f.apply(get("/api/v1/auth/me"),s)
          .buildRequest(new org.springframework.mock.web.MockServletContext()).getHeader("Origin"));
    }
  }
}
