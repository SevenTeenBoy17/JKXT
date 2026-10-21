package com.jkxt.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 通知生产者验收（P18 批次 5.3）。
 *
 * <p>缺陷：全库唯一的通知生产者是「排班发布」（ScheduleEditService.publish）——数据导入完成、
 * 排考失败/无解这类关键事件都进不了收件箱，顶栏铃铛长期近乎空转，管理员必须一直盯着进度条
 * 才能得知任务结局。本类锁「导入完成 → 收件箱有一条 import_completed」。
 * （排考失败/无解的生产者走同一 publishSystemNotice 入口，其触发依赖异步求解崩溃场景，
 * 这里不专门制造求解崩溃——入口行为已由本用例覆盖。）
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationProducersAcceptanceTest {

  private static final UUID DEMO_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String teacherNo;

  @AfterEach
  void cleanUp() {
    if (teacherNo != null) {
      jdbcTemplate.update("delete from teacher where org_id = ? and teacher_no = ?", DEMO_ORG, teacherNo);
    }
    jdbcTemplate.update("delete from notification where org_id = ? and type = 'import_completed'", DEMO_ORG);
  }

  @Test
  void importCommitDropsANoticeIntoTheInbox() throws Exception {
    Cookie session = login();
    teacherNo = "NOTIFY-IMP-" + System.nanoTime();

    Integer before = countImportNotices();
    // 共享测试库里其它用例（QuickFlow/ImportCommit 系列）也会产生 import_completed——
    // 不能取「最新一条」当作自己的（曾因此拿到别的用例的「考场」行而误红；created_at 是
    // DB 事务开始时间，跨用例排序并不保证与提交序一致）。改按内容指纹定位本次的行：
    // 本类删光后重导，kind=teachers 的行在断言时刻应恰好一条、且就是本次产生的。
    Integer teachersBefore = countTeacherImportNotices();

    MockMultipartFile file = new MockMultipartFile("file", "teachers.xlsx", XLSX, teacherWorkbook(teacherNo));
    mockMvc.perform(multipart("/api/v1/import/teachers/commit").file(file).cookie(session))
        .andExpect(status().isOk());

    assertThat(countImportNotices())
        .as("导入完成必须往收件箱写一条 import_completed（此前只有排班发布一个生产者）")
        .isEqualTo(before + 1);
    assertThat(countTeacherImportNotices())
        .as("新增的必须是 kind=teachers 的那一条")
        .isEqualTo(teachersBefore + 1);

    Map<String, Object> notice = jdbcTemplate.queryForMap("""
        select title, body, status from notification
        where org_id = ? and type = 'import_completed' and payload->>'kind' = 'teachers'
        order by created_at desc limit 1
        """, DEMO_ORG);
    assertThat(String.valueOf(notice.get("title"))).contains("监考教师");
    assertThat(String.valueOf(notice.get("body"))).contains("新增");
    assertThat(String.valueOf(notice.get("status"))).isEqualTo("sent");
  }

  // ---------- helpers ----------

  private Integer countImportNotices() {
    return jdbcTemplate.queryForObject(
        "select count(*) from notification where org_id = ? and type = 'import_completed'",
        Integer.class, DEMO_ORG);
  }

  private Integer countTeacherImportNotices() {
    return jdbcTemplate.queryForObject(
        "select count(*) from notification where org_id = ? and type = 'import_completed' and payload->>'kind' = 'teachers'",
        Integer.class, DEMO_ORG);
  }

  private byte[] teacherWorkbook(String no) throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      header.createCell(2).setCellValue("性别");
      header.createCell(3).setCellValue("年级");
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue(no);
      row.createCell(1).setCellValue("通知生产者测试教师");
      row.createCell(2).setCellValue("女");
      row.createCell(3).setCellValue("高一");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        CaptchaResponse.class);
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"username":"jwc.demo","password":"Demo@2025","captchaId":"%s","captcha":"%s"}
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
  }
}
