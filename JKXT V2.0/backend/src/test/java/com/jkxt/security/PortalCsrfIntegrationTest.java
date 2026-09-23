package com.jkxt.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PortalCsrfIntegrationTest extends PortalSessionFixture {
  @Test void multipartUploadKeepsItsContentTypeAndStillRequiresCsrf() throws Exception {
    var s=account("ADMIN");
    byte[] workbook=new com.jkxt.importer.service.ImportValidationService().template("teachers");
    var file=new org.springframework.mock.web.MockMultipartFile("file","teachers.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook);
    http.perform(multipart("/api/v1/import/teachers").file(file).cookie(cookie(s))
        .header("X-JKXT-Session-Context",s.envelope().context()))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
    http.perform(multipart("/api/v1/import/teachers").file(file).cookie(cookie(s))
        .header("X-JKXT-Session-Context",s.envelope().context()).header("X-JKXT-CSRF",s.envelope().csrfToken()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.overview.teachers").value(1));
    assertEquals(0,jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,s.principal().orgId()));
  }
  @Test void correctContextAndCsrfPersistActualBusinessChange() throws Exception {
    var s=account("ADMIN");
    http.perform(write(s)).andExpect(status().isOk()).andExpect(jsonPath("$.theme.mode").value("dark"));
    assertEquals("dark",jdbc.queryForObject("select preferences->'theme'->>'mode' from app_user where id=?",String.class,s.principal().userId()));
  }
  @Test void missingOrWrongContextBlocksReadAndWrite() throws Exception {
    var s=account("ADMIN");
    for(String context:new String[]{null,"wrong"}) {
      var read=get("/api/v1/auth/me/preferences").cookie(cookie(s));
      if(context!=null) read.header("X-JKXT-Session-Context",context);
      http.perform(read).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_CONTEXT_CHANGED"));
      var write=patch("/api/v1/auth/me/preferences").cookie(cookie(s)).header("X-JKXT-CSRF",s.envelope().csrfToken());
      if(context!=null) write.header("X-JKXT-Session-Context",context);
      http.perform(write).andExpect(status().isConflict()); assertUnchanged(s);
    }
  }
  @Test void missingWrongOrCrossPurposeCsrfCannotWrite() throws Exception {
    var s=account("ADMIN"); var p=account("SUPER_ADMIN");
    for(String token:new String[]{null,"wrong",p.envelope().csrfToken()}) {
      var request=patch("/api/v1/auth/me/preferences").cookie(cookie(s)).contentType("application/json")
          .header("X-JKXT-Session-Context",s.envelope().context()).content("{\"theme\":{\"mode\":\"dark\"}}");
      if(token!=null) request.header("X-JKXT-CSRF",token);
      http.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
      assertUnchanged(s);
    }
  }
  @Test void publicLoginRequiresExactOriginAndJsonBeforeAuthentication() throws Exception {
    var s=account("ADMIN");
    for(String path:new String[]{"/api/v1/auth/login","/api/v1/auth/school/login","/api/v1/auth/platform/login","/api/v1/demo/visitor","/api/v1/demo/sessions"}) {
      for(String origin:new String[]{null,"null","http://evil.example","https://localhost:8080","http://localhost:8081"}) {
        var r=post(path).cookie(cookie(s)).contentType("application/json").content("{}");
        if(origin!=null) r.header("Origin",origin);
        http.perform(r).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_ORIGIN_INVALID"))
            .andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(header().doesNotExist("Set-Cookie"));
      }
      for(String type:new String[]{"text/plain","application/*","*/*"})
        http.perform(post(path).header("Origin","http://localhost:8080").contentType(type).content("{}"))
            .andExpect(status().isUnsupportedMediaType());
    }
    assertTrue(sessions.find(s.principal().sessionId(),s.envelope().audience()).isPresent()); assertUnchanged(s);
  }
  @Test void validPreflightDoesNotNeedSessionOrCsrf() throws Exception {
    http.perform(options("/api/v1/auth/school/login").header("Origin","http://localhost:8080")
        .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Content-Type,X-JKXT-Portal"))
        .andExpect(status().isOk());
  }
  @Test void duplicateReservedContextAndCsrfRejected() throws Exception {
    var s=account("ADMIN");
    http.perform(write(s).header("X-JKXT-CSRF","extra")).andExpect(status().isBadRequest());
    http.perform(write(s).header("X-JKXT-Session-Context","extra")).andExpect(status().isBadRequest()); assertUnchanged(s);
  }
}
