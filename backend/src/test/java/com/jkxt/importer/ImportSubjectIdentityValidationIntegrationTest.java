package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.importer.service.ImportCommitService;
import com.jkxt.importer.service.ImportValidationService;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportSubjectIdentityValidationIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  @Autowired ImportValidationService validation;
  @Autowired ImportCommitService commits;
  @Autowired JdbcTemplate jdbc;

  @AfterEach void clear() { TenantContext.clear(); }

  @Test
  void previewExplainsSameNameDifferentCodeBeforeCommitAndPreservesExistingRows() throws Exception {
    TenantContext.set(ORG);
    String name = "合成科目" + shortId();
    UUID existing = subject(ORG, "OLD-" + shortId(), name);
    var file = workbook(new String[][]{{"NEW-" + shortId(), name}});
    var report = validation.validate("subjects", file);
    assertThat(report.counts().error()).isPositive();
    assertThat(report.items()).anySatisfy(item -> {
      assertThat(item.code()).isEqualTo("SUBJECT_NAME_CODE_CONFLICT");
      assertThat(item.row()).isEqualTo(2);
      assertThat(item.column()).isEqualTo("科目名称");
      assertThat(item.suggest()).contains("科目编号");
    });
    assertThatThrownBy(() -> commits.commit("subjects", null, file))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(jdbc.queryForObject("select id from subject where org_id=? and name=?", UUID.class, ORG, name))
        .isEqualTo(existing);
  }

  @Test
  void renameCannotTakeAnotherSubjectName() throws Exception {
    TenantContext.set(ORG);
    String code = "CODE-" + shortId(), name = "合成科目" + shortId();
    subject(ORG, code, "原始" + shortId());
    subject(ORG, "OTHER-" + shortId(), name);
    assertThat(validation.validate("subjects", workbook(new String[][]{{code, name}})).counts().error())
        .isPositive();
  }

  @Test
  void sameCodeAndNameRemainsAnAllowedUpsert() throws Exception {
    TenantContext.set(ORG);
    String code = "CODE-" + shortId(), name = "合成科目" + shortId();
    subject(ORG, code, name);
    assertThat(validation.validate("subjects", workbook(new String[][]{{code, name}})).counts().error())
        .isZero();
  }

  @Test
  void subjectNamesInAnotherSchoolDoNotCauseConflictsOrDisclosures() throws Exception {
    UUID other = jdbc.queryForObject("select id from organization where id<>? order by id limit 1", UUID.class, ORG);
    String name = "他校" + shortId();
    subject(other, "FOREIGN-" + shortId(), name);
    TenantContext.set(ORG);
    assertThat(validation.validate("subjects", workbook(new String[][]{{"NEW-" + shortId(), name}})).counts().error())
        .isZero();
  }

  @Test
  void duplicateNamesAndCodesWithinTheWorkbookAreExplainedAtTheirRows() throws Exception {
    TenantContext.set(ORG);
    String name = "重复" + shortId(), code = "DUP-" + shortId();
    var report = validation.validate("subjects", workbook(new String[][]{
        {code, name}, {"NEW-" + shortId(), name}, {code, "另一科目" + shortId()}}));
    assertThat(report.items()).anySatisfy(item -> {
      assertThat(item.code()).isEqualTo("SUBJECT_NAME_DUPLICATE");
      assertThat(item.row()).isEqualTo(3);
    });
    assertThat(report.items()).anySatisfy(item -> {
      assertThat(item.code()).isEqualTo("SUBJECT_CODE_DUPLICATE");
      assertThat(item.row()).isEqualTo(4);
    });
  }

  private UUID subject(UUID org, String code, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into subject(id,org_id,code,name,status) values(?,?,?,?,'active')", id, org, code, name);
    return id;
  }

  private String shortId() { return UUID.randomUUID().toString().substring(0, 8); }

  private MockMultipartFile workbook(String[][] rows) throws Exception {
    try (var wb = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
      var sheet = wb.createSheet("科目");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("科目编号");
      header.createCell(1).setCellValue("科目名称");
      header.createCell(2).setCellValue("类别");
      header.createCell(3).setCellValue("考试类型");
      for (int i = 0; i < rows.length; i++) {
        var row = sheet.createRow(i + 1);
        row.createCell(0).setCellValue(rows[i][0]);
        row.createCell(1).setCellValue(rows[i][1]);
        row.createCell(2).setCellValue("main");
        row.createCell(3).setCellValue("written");
      }
      wb.write(bytes);
      return new MockMultipartFile("file", "subjects.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
    }
  }
}
