package com.jkxt.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasterDataApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @Test
  void teacherCreateAndDeleteWriteAuditLogRows() throws Exception {
    // 迭代9 F4(终审)：主数据 create/delete 须写 audit_log(谁改了什么)。AuditPersistenceProbeTest 只证 save
    // 机制,此测试证真实 CRUD 端点往返后审计行经服务事务 commit 存活、targetId/payload 正确、且 payload 无明文口令。
    Cookie session = login();
    String code = "T-F4-" + UUID.randomUUID().toString().substring(0, 8);
    JsonNode created = json(mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "F4 审计测试",
                  "gender": "unknown",
                  "grade": "G9",
                  "status": "active",
                  "isHomeroom": false
                }
                """.formatted(code)))
        .andExpect(status().isOk())
        .andReturn());
    String uuid = created.get("uuid").asText();

    Integer createdRows = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = 'TEACHER_CREATED' and target_id = ?::uuid",
        Integer.class, uuid);
    org.assertj.core.api.Assertions.assertThat(createdRows).isEqualTo(1);
    Integer plaintextSecrets = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where target_id = ?::uuid and payload::text ilike '%password%'",
        Integer.class, uuid);
    org.assertj.core.api.Assertions.assertThat(plaintextSecrets).isEqualTo(0);

    mockMvc.perform(delete("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().is2xxSuccessful());
    Integer deletedRows = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = 'TEACHER_DELETED' and target_id = ?::uuid",
        Integer.class, uuid);
    org.assertj.core.api.Assertions.assertThat(deletedRows).isEqualTo(1);
  }

  @Test
  void subjectCrudWritesAuditLogRows() throws Exception {
    // 深度研读(docs/CODEBASE-DEEP-READ-20260706.md #14)收口回归：subject 的 create/update/delete 此前
    // 完全无审计(commit f8ebdd3 补齐)。此测试证真实端点往返后 SUBJECT_CREATED/UPDATED/DELETED 审计行
    // 经服务事务 commit 存活、targetId 正确——锁死"主数据变更全覆盖审计"基线。
    Cookie session = login();
    String code = "S-AUD-" + UUID.randomUUID().toString().substring(0, 8);

    JsonNode created = json(mockMvc.perform(post("/api/v1/subjects")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"code": "%s", "name": "审计科目", "category": "文化课", "status": "active"}
                """.formatted(code)))
        .andExpect(status().isOk())
        .andReturn());
    String uuid = created.get("uuid").asText();
    assertThat(auditCount("SUBJECT_CREATED", uuid)).isEqualTo(1);

    mockMvc.perform(put("/api/v1/subjects/{id}", uuid)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"code": "%s", "name": "审计科目改名", "category": "文化课", "status": "active"}
                """.formatted(code)))
        .andExpect(status().isOk());
    assertThat(auditCount("SUBJECT_UPDATED", uuid)).isEqualTo(1);

    mockMvc.perform(delete("/api/v1/subjects/{id}", uuid).cookie(session))
        .andExpect(status().is2xxSuccessful());
    assertThat(auditCount("SUBJECT_DELETED", uuid)).isEqualTo(1);
  }

  private Integer auditCount(String action, String targetId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = ? and target_id = ?::uuid",
        Integer.class, action, targetId);
  }

  @Test
  void masterDataListsReturnTenantScopedDemoSeedRows() throws Exception {
    Cookie session = login();

    mockMvc.perform(get("/api/v1/teachers").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].uuid").exists())
        .andExpect(jsonPath("$[0].teacherNo").exists())
        .andExpect(jsonPath("$[0].subjects").isArray());

    mockMvc.perform(get("/api/v1/classrooms").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").exists())
        .andExpect(jsonPath("$[0].features").isArray());

    mockMvc.perform(get("/api/v1/classes").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").exists())
        .andExpect(jsonPath("$[0].subjects").exists());

    mockMvc.perform(get("/api/v1/subjects").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").exists())
        .andExpect(jsonPath("$[0].examCount").exists());
  }

  @Test
  void createTeacherWithForeignHomeroomClassIdIsRejected() throws Exception {
    // 迭代9 F5(终审)：applyTeacher 的 homeroomClassId 必须校验归属本 org(ownedSchoolClassId)。传入不存在/
    // 跨租户的 class UUID 必须 404,而非静默写入(跨租户引用注入)。缺此测试则移除归属校验的回归可过 CI。
    Cookie session = login();
    String code = "T-F5-" + UUID.randomUUID().toString().substring(0, 8);
    String foreignClassId = UUID.randomUUID().toString();
    mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "F5 越权测试",
                  "gender": "unknown",
                  "grade": "G9",
                  "status": "active",
                  "isHomeroom": true,
                  "homeroomClassId": "%s"
                }
                """.formatted(code, foreignClassId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void teacherCrudRoundTripUsesUuidPathAndTeacherNoDisplayId() throws Exception {
    Cookie session = login();
    String code = "T-IT-" + UUID.randomUUID().toString().substring(0, 8);

    JsonNode created = json(mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "Integration Teacher",
                  "gender": "unknown",
                  "grade": "G9",
                  "phone": "13812345678",
                  "email": "%s@demo.local",
                  "status": "active",
                  "isHomeroom": false
                }
                """.formatted(code, code.toLowerCase())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(code))
        .andExpect(jsonPath("$.uuid").exists())
        .andReturn());

    String uuid = created.get("uuid").asText();

    mockMvc.perform(put("/api/v1/teachers/{id}", uuid)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "Integration Teacher Updated",
                  "gender": "male",
                  "grade": "G10",
                  "phone": "13812345679",
                  "email": "%s@demo.local",
                  "status": "active",
                  "isHomeroom": false
                }
                """.formatted(code, code.toLowerCase())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Integration Teacher Updated"))
        .andExpect(jsonPath("$.gender").value("male"));

    mockMvc.perform(get("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teacherNo").value(code));

    mockMvc.perform(delete("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void teacherSchedulingProfileRoundTripsThroughCreateUpdateAndGet() throws Exception {
    Cookie session = login();
    String code = "T-POLICY-" + UUID.randomUUID().toString().substring(0, 8);

    JsonNode created = json(mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "复杂约束教师",
                  "gender": "female",
                  "grade": "G11",
                  "status": "active",
                  "isHomeroom": false,
                  "age": 63,
                  "position": "年级主任",
                  "leaveWindows": [
                    {
                      "start": "2026-10-12T08:00:00",
                      "end": "2026-10-12T12:00:00",
                      "reason": "sick-leave"
                    }
                  ],
                  "functionalAccommodations": ["NO_LONG_STANDING", "EXTRA_BREAK"],
                  "dutyWindows": [
                    {
                      "start": "2026-10-12T14:00:00",
                      "end": "2026-10-12T16:00:00"
                    }
                  ],
                  "qualifications": ["INVIGILATOR", "CHIEF"],
                  "qualificationExpiry": "2027-12-31",
                  "maxDailySessions": 3,
                  "preferredCampus": "北校区"
                }
                """.formatted(code)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.age").value(63))
        .andExpect(jsonPath("$.position").value("年级主任"))
        .andExpect(jsonPath("$.leaveWindows[0].reason").value("sick-leave"))
        .andExpect(jsonPath("$.functionalAccommodations[0]").value("NO_LONG_STANDING"))
        .andExpect(jsonPath("$.functionalAccommodations[1]").value("EXTRA_BREAK"))
        .andExpect(jsonPath("$.dutyWindows[0].start").value("2026-10-12T14:00:00"))
        .andExpect(jsonPath("$.qualifications[0]").value("INVIGILATOR"))
        .andExpect(jsonPath("$.qualifications[1]").value("CHIEF"))
        .andExpect(jsonPath("$.qualificationExpiry").value("2027-12-31"))
        .andExpect(jsonPath("$.maxDailySessions").value(3))
        .andExpect(jsonPath("$.preferredCampus").value("北校区"))
        .andReturn());

    String uuid = created.get("uuid").asText();
    mockMvc.perform(get("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.age").value(63))
        .andExpect(jsonPath("$.position").value("年级主任"))
        .andExpect(jsonPath("$.leaveWindows[0].start").value("2026-10-12T08:00:00"))
        .andExpect(jsonPath("$.leaveWindows[0].end").value("2026-10-12T12:00:00"))
        .andExpect(jsonPath("$.functionalAccommodations.length()").value(2))
        .andExpect(jsonPath("$.dutyWindows[0].end").value("2026-10-12T16:00:00"))
        .andExpect(jsonPath("$.qualifications.length()").value(2))
        .andExpect(jsonPath("$.qualificationExpiry").value("2027-12-31"))
        .andExpect(jsonPath("$.maxDailySessions").value(3))
        .andExpect(jsonPath("$.preferredCampus").value("北校区"));

    mockMvc.perform(put("/api/v1/teachers/{id}", uuid)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "复杂约束教师（更新）",
                  "gender": "female",
                  "grade": "G11",
                  "status": "active",
                  "isHomeroom": false,
                  "age": 64,
                  "position": "教务主任",
                  "leaveWindows": [],
                  "functionalAccommodations": ["EXTRA_BREAK"],
                  "dutyWindows": [
                    {
                      "start": "2026-10-13T09:00:00",
                      "end": "2026-10-13T11:00:00"
                    }
                  ],
                  "qualifications": ["INVIGILATOR"],
                  "qualificationExpiry": null,
                  "maxDailySessions": 2,
                  "preferredCampus": "南校区"
                }
                """.formatted(code)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.age").value(64))
        .andExpect(jsonPath("$.position").value("教务主任"))
        .andExpect(jsonPath("$.leaveWindows").isEmpty())
        .andExpect(jsonPath("$.functionalAccommodations[0]").value("EXTRA_BREAK"))
        .andExpect(jsonPath("$.dutyWindows[0].end").value("2026-10-13T11:00:00"))
        .andExpect(jsonPath("$.qualifications[0]").value("INVIGILATOR"))
        .andExpect(jsonPath("$.qualificationExpiry").doesNotExist())
        .andExpect(jsonPath("$.maxDailySessions").value(2))
        .andExpect(jsonPath("$.preferredCampus").value("南校区"));

    mockMvc.perform(get("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.age").value(64))
        .andExpect(jsonPath("$.position").value("教务主任"))
        .andExpect(jsonPath("$.leaveWindows").isEmpty())
        .andExpect(jsonPath("$.functionalAccommodations.length()").value(1))
        .andExpect(jsonPath("$.dutyWindows[0].start").value("2026-10-13T09:00:00"))
        .andExpect(jsonPath("$.qualifications[0]").value("INVIGILATOR"))
        .andExpect(jsonPath("$.maxDailySessions").value(2))
        .andExpect(jsonPath("$.preferredCampus").value("南校区"));

    // PUT is a complete replacement for the scheduling profile. Explicit JSON null must clear
    // nullable scalar fields; otherwise an administrator cannot undo an age/position assignment.
    mockMvc.perform(put("/api/v1/teachers/{id}", uuid)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "复杂约束教师（清空画像）",
                  "gender": "female",
                  "grade": "G11",
                  "status": "active",
                  "isHomeroom": false,
                  "age": null,
                  "position": null,
                  "leaveWindows": [],
                  "functionalAccommodations": [],
                  "dutyWindows": []
                }
                """.formatted(code)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.age").doesNotExist())
        .andExpect(jsonPath("$.position").doesNotExist())
        .andExpect(jsonPath("$.leaveWindows").isEmpty())
        .andExpect(jsonPath("$.functionalAccommodations").isEmpty())
        .andExpect(jsonPath("$.dutyWindows").isEmpty())
        .andExpect(jsonPath("$.qualifications[0]").value("INVIGILATOR"))
        .andExpect(jsonPath("$.qualifications.length()").value(1))
        .andExpect(jsonPath("$.qualificationExpiry").doesNotExist())
        .andExpect(jsonPath("$.maxDailySessions").value(4))
        .andExpect(jsonPath("$.preferredCampus").doesNotExist());

    mockMvc.perform(get("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.age").doesNotExist())
        .andExpect(jsonPath("$.position").doesNotExist())
        .andExpect(jsonPath("$.qualifications[0]").value("INVIGILATOR"))
        .andExpect(jsonPath("$.maxDailySessions").value(4));
  }

  @Test
  void teacherInvigilationProfileDefaultsToOrdinaryInvigilator() throws Exception {
    Cookie session = login();
    String code = "T-DEFAULT-" + UUID.randomUUID().toString().substring(0, 8);

    JsonNode created = json(mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "默认普通监考教师",
                  "gender": "unknown",
                  "status": "active",
                  "isHomeroom": false
                }
                """.formatted(code)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.qualifications[0]").value("INVIGILATOR"))
        .andExpect(jsonPath("$.qualifications.length()").value(1))
        .andExpect(jsonPath("$.qualificationExpiry").doesNotExist())
        .andExpect(jsonPath("$.maxDailySessions").value(4))
        .andExpect(jsonPath("$.preferredCampus").doesNotExist())
        .andReturn());

    mockMvc.perform(delete("/api/v1/teachers/{id}", created.get("uuid").asText()).cookie(session))
        .andExpect(status().isNoContent());
  }

  @Test
  void teacherInvigilationProfileRejectsUnknownMalformedAndDuplicateQualifications() throws Exception {
    Cookie session = login();
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    for (String qualifications : new String[] {
        "[\"PROCTOR\"]",
        "[\"invigilator\"]",
        "[\" INVIGILATOR \"]",
        "[\"INVIGILATOR\",\"INVIGILATOR\"]"
    }) {
      mockMvc.perform(post("/api/v1/teachers")
              .cookie(session)
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {
                    "teacherNo": "T-BAD-%s-%s",
                    "name": "错误资格教师",
                    "gender": "unknown",
                    "status": "active",
                    "isHomeroom": false,
                    "qualifications": %s
                  }
                  """.formatted(suffix, Math.abs(qualifications.hashCode()), qualifications)))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void teacherLookupDoesNotExposeAnotherTenantTeacher() throws Exception {
    Cookie session = login();
    UUID foreignOrgId = UUID.randomUUID();
    UUID foreignTeacherId = UUID.randomUUID();
    String foreignCode = "T-FOREIGN-" + foreignTeacherId.toString().substring(0, 8);
    try {
      jdbcTemplate.update("""
          insert into organization (id, name, slug, type, plan, status)
          values (?, 'Foreign Teacher School', ?, 'school', 'trial', 'active')
          """, foreignOrgId, "teacher-foreign-" + foreignOrgId.toString().substring(0, 8));
      jdbcTemplate.update("""
          insert into teacher (id, org_id, teacher_no, name, gender, status, is_homeroom)
          values (?, ?, ?, 'Foreign Teacher', 'unknown', 'active', false)
          """, foreignTeacherId, foreignOrgId, foreignCode);

      mockMvc.perform(get("/api/v1/teachers/{id}", foreignTeacherId).cookie(session))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"));
      mockMvc.perform(get("/api/v1/teachers").param("q", foreignCode).cookie(session))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$").isEmpty());
    } finally {
      jdbcTemplate.update("delete from organization where id = ?", foreignOrgId);
    }
  }

  @Test
  void teacherSchedulingProfileRejectsReversedWindows() throws Exception {
    Cookie session = login();
    String code = "T-BAD-WINDOW-" + UUID.randomUUID().toString().substring(0, 8);

    mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "错误窗口教师",
                  "gender": "unknown",
                  "status": "active",
                  "isHomeroom": false,
                  "leaveWindows": [
                    {
                      "start": "2026-10-12T12:00:00",
                      "end": "2026-10-12T08:00:00",
                      "reason": "reversed"
                    }
                  ]
                }
                """.formatted(code)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void classroomsClassesAndSubjectsSupportCrud() throws Exception {
    Cookie session = login();
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    JsonNode room = json(mockMvc.perform(post("/api/v1/classrooms")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "R-%s",
                  "name": "Integration Room",
                  "building": "Test Building",
                  "floor": "5F",
                  "capacity": 40,
                  "examCapacity": 30,
                  "roomType": "standard",
                  "features": ["广播", "监控"],
                  "status": "active"
                }
                """.formatted(suffix)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("R-" + suffix))
        .andExpect(jsonPath("$.features[0]").value("广播"))
        .andReturn());

    JsonNode schoolClass = json(mockMvc.perform(post("/api/v1/classes")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "C-%s",
                  "name": "Integration Class",
                  "grade": "G10",
                  "stage": "High",
                  "studentCount": 35,
                  "defaultClassroomId": "%s",
                  "status": "active"
                }
                """.formatted(suffix, room.get("uuid").asText())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.students").value(35))
        .andReturn());

    JsonNode subject = json(mockMvc.perform(post("/api/v1/subjects")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "S-%s",
                  "name": "Integration Subject %s",
                  "category": "secondary",
                  "examType": "written",
                  "hours": 2.5,
                  "color": "cyan",
                  "status": "active"
                }
                """.formatted(suffix, suffix)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("secondary"))
        .andReturn());

    mockMvc.perform(put("/api/v1/subjects/{id}", subject.get("uuid").asText())
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "S-%s",
                  "name": "Integration Subject %s Updated",
                  "category": "activity",
                  "examType": "practical",
                  "hours": 1,
                  "color": "emerald",
                  "status": "active"
                }
                """.formatted(suffix, suffix)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.examType").value("practical"));

    mockMvc.perform(delete("/api/v1/subjects/{id}", subject.get("uuid").asText()).cookie(session))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/v1/classes/{id}", schoolClass.get("uuid").asText()).cookie(session))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/v1/classrooms/{id}", room.get("uuid").asText()).cookie(session))
        .andExpect(status().isNoContent());
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "jwc.demo",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    Cookie session = login.getResponse().getCookie("JKXT_SESSION");
    assertThat(session).isNotNull();
    return session;
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
