package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"jkxt.deploy.mode=private", "jkxt.deploy.fixed-org-id=00000000-0000-0000-0000-000000000001"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrivateOperatorLoginIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired PasswordEncoder encoder;

  @Test void globalOperatorCanLoginButPrivateBusinessContextRemainsFixed() throws Exception {
    UUID org = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    String name = "global." + user;
    jdbc.update("insert into organization(id,name,slug,type,status) values (?,?,?,'school','active')", org, name, name);
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password,onboarded)
        values (?,?,?,? ,?,'SUPER_ADMIN','active',false,true)
        """, user, org, name, name, encoder.encode("PrivateOperator@2026"));
    try {
      var challenge = mvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk()).andReturn();
      var captcha = mapper.readTree(challenge.getResponse().getContentAsString());
      var result = mvc.perform(post("/api/v1/auth/platform/login").contentType(MediaType.APPLICATION_JSON)
          .content(mapper.writeValueAsString(Map.of("username", name, "password", "PrivateOperator@2026",
              "captchaId", captcha.path("captchaId").asText(), "captcha", captcha.path("debugAnswer").asText()))))
          .andExpect(status().isOk()).andReturn();
      Cookie cookie = result.getResponse().getCookie("JKXT_PLATFORM_SESSION");
      assertThat(cookie).isNotNull();
      mvc.perform(get("/api/v1/teachers").cookie(cookie)).andExpect(status().isOk())
          .andExpect(header().string("X-Tenant-Id", "00000000-0000-0000-0000-000000000001"));
      mvc.perform(get("/api/v1/teachers").cookie(cookie).header("X-Tenant-Id", org.toString()))
          .andExpect(status().isForbidden());
    } finally {
      jdbc.update("delete from audit_log where actor_id=?", user);
      jdbc.update("delete from app_user where id=?", user);
      jdbc.update("delete from organization where id=?", org);
    }
  }
}
