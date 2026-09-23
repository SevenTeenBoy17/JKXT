package com.jkxt.security;

import com.jkxt.security.auth.PortalPurpose;
import com.jkxt.security.auth.PortalSessionPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSessionPolicyTest {
  @Test void unavailableRedisReturnsStructured503AndDoesNotEnterBusiness() throws Exception {
    boolean stopped=Boolean.getBoolean("jkxt.test.redis-stopped");
    var config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
        stopped?System.getenv("JKXT_TEST_REDIS_HOST"):"127.0.0.1",
        stopped?Integer.parseInt(System.getenv("JKXT_TEST_REDIS_PORT")):1);
    var client=org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
        .commandTimeout(java.time.Duration.ofMillis(200)).shutdownTimeout(java.time.Duration.ZERO).build();
    var connection=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config,client);
    connection.afterPropertiesSet(); connection.start();
    try {
      var redis=new org.springframework.data.redis.core.StringRedisTemplate(connection);
      var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
      var service=new com.jkxt.security.auth.SessionService(redis,mapper,null,
          org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
          org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class),false,"Lax",600);
      var filter=new com.jkxt.security.auth.SessionAuthenticationFilter(service,null,
          new com.jkxt.common.config.DeployProperties(),mapper,"http://localhost:8080");
      var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/auth/me");
      request.setCookies(new jakarta.servlet.http.Cookie("JKXT_SCHOOL_SESSION",java.util.UUID.randomUUID().toString()));
      var response=new org.springframework.mock.web.MockHttpServletResponse();
      var reached=new java.util.concurrent.atomic.AtomicBoolean();
      filter.doFilter(request,response,(req,res)->reached.set(true));
      assertEquals(503,response.getStatus()); assertFalse(reached.get());
      var body=mapper.readTree(response.getContentAsString());
      assertEquals("AUTH_STATE_UNAVAILABLE",body.path("code").asText());
      assertFalse(body.path("traceId").asText().isBlank());
      assertTrue(com.jkxt.common.tenant.TenantContext.get().isEmpty());
    } finally { connection.destroy(); }
  }
  @Test void exactRolePurposeMatrix() {
    for (PortalPurpose purpose : PortalPurpose.values()) {
      for (String role : new String[]{"ADMIN", "VIEWER", "TEACHER_QUERY", "SUPER_ADMIN", "DEMO", "unknown", "admin", ""}) {
        boolean expected = switch (purpose) {
          case SCHOOL -> java.util.Set.of("ADMIN", "VIEWER", "TEACHER_QUERY").contains(role);
          case PLATFORM -> "SUPER_ADMIN".equals(role);
          case DEMO -> "DEMO".equals(role);
        };
        assertEquals(expected, PortalSessionPolicy.allows(purpose, role));
      }
      assertFalse(PortalSessionPolicy.allows(purpose, null));
    }
    assertFalse(PortalSessionPolicy.allows(null, "ADMIN"));
  }
}
