package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/** Public API checks: requesting a read-only teacher must never create an administrator. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherAccountRoleBindingIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  @Test
  void teacherAccountHasOnlySelfServiceAndBindsTheSelectedSchoolTeacher() throws Exception {
    Cookie admin = login("admin", "Super001!!!");
    UUID teacher = teacher(ORG);
    Map<String, Object> request = account("TEACHER_QUERY", teacher);
    JsonNode created = body(mvc.perform(post("/api/v1/admin/accounts").cookie(admin)
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
        .andExpect(status().isOk()).andReturn());
    UUID user = UUID.fromString(created.path("id").asText());
    assertThat(jdbc.queryForObject("select role from app_user where id=?", String.class, user))
        .isEqualTo("TEACHER_QUERY");
    assertThat(jdbc.queryForObject("select app_user_id from teacher where id=?", UUID.class, teacher))
        .isEqualTo(user);
    Cookie teacherCookie = login(request.get("username").toString(), created.path("initialPassword").asText());
    JsonNode me = body(mvc.perform(get("/api/v1/auth/me").cookie(teacherCookie))
        .andExpect(status().isOk()).andReturn());
    assertThat(me.path("user").path("role").asText()).isEqualTo("TEACHER_QUERY");
    assertThat(me.path("permissions").toString()).contains("duty:self")
        .doesNotContain("schedule:manage", "tenant:read", "masterdata:manage");
    mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(teacherCookie)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/teachers").cookie(teacherCookie)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/exam-plans").cookie(teacherCookie)
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Forbidden\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/accounts").cookie(teacherCookie)).andExpect(status().isForbidden());
  }

  @Test
  void unknownRolesAreRejectedBeforeAnyAccountIsCreated() throws Exception {
    Cookie admin = login("admin", "Super001!!!");
    for (String role : new String[]{"TEACHER_QUERYY", "owner", "arbitrary"}) {
      Map<String,Object> request = account(role, null);
      mvc.perform(post("/api/v1/admin/accounts").cookie(admin).contentType(MediaType.APPLICATION_JSON)
          .content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
      assertThat(jdbc.queryForObject("select count(*) from app_user where username=?", Integer.class,
          request.get("username"))).isZero();
    }
  }

  @Test
  void crossSchoolMissingAndDuplicateBindingsFailWithoutOrphanAccounts() throws Exception {
    Cookie admin = login("admin", "Super001!!!");
    UUID otherOrg = jdbc.queryForObject("select id from organization where id<>? order by id limit 1", UUID.class, ORG);
    UUID foreignTeacher = teacher(otherOrg);
    for (UUID target : new UUID[]{foreignTeacher, UUID.randomUUID()}) {
      Map<String,Object> request = account("TEACHER_QUERY", target);
      mvc.perform(post("/api/v1/admin/accounts").cookie(admin).contentType(MediaType.APPLICATION_JSON)
          .content(json.writeValueAsString(request))).andExpect(status().isNotFound());
      assertThat(jdbc.queryForObject("select count(*) from app_user where username=?", Integer.class,
          request.get("username"))).isZero();
    }
    UUID target = teacher(ORG);
    Map<String,Object> first = account("TEACHER_QUERY", target);
    mvc.perform(post("/api/v1/admin/accounts").cookie(admin).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(first))).andExpect(status().isOk());
    Map<String,Object> second = account("TEACHER_QUERY", target);
    mvc.perform(post("/api/v1/admin/accounts").cookie(admin).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(second))).andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("select count(*) from app_user where username=?", Integer.class,
        second.get("username"))).isZero();
  }

  @Test
  void adminRoleCannotBeDisguisedAsTeacherBinding() throws Exception {
    Cookie admin = login("admin", "Super001!!!");
    Map<String,Object> request = account("ADMIN", teacher(ORG));
    mvc.perform(post("/api/v1/admin/accounts").cookie(admin).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select count(*) from app_user where username=?", Integer.class,
        request.get("username"))).isZero();
  }

  private UUID teacher(UUID org) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender,status) values(?,?,?,'合成账号验收教师','unknown','active')",
        id, org, "ACC-" + id.toString().substring(0, 12));
    return id;
  }

  private Map<String,Object> account(String role, UUID teacher) {
    String username = "school-account-" + UUID.randomUUID().toString().substring(0, 12);
    Map<String,Object> data = new LinkedHashMap<>(Map.of("tenantId", ORG.toString(), "username", username,
        "name", "合成教师账号", "email", username + "@example.test", "role", role));
    if (teacher != null) data.put("teacherId", teacher.toString());
    return data;
  }

  private JsonNode body(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = json.readValue(mvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CaptchaResponse.class);
    MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("username", username, "password", password,
            "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn();
    String setCookie = result.getResponse().getHeader("Set-Cookie");
    String[] cookie = setCookie.split(";", 2)[0].split("=", 2);
    return new Cookie(cookie[0], cookie[1]);
  }
}
