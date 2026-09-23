package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.importer.service.ImportValidationService;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Acceptance test for P3-1: a hard-fail import (blocking error / zero importable data rows) must not
 * report a near-perfect completeness score that the frontend renders as green "良好".
 *
 * <p>The completeness formula (100 - error*5 - warn*2) is only valid for the no-error case. A
 * header-only or empty file produces a single blocking error and ZERO usable rows, which previously
 * yielded completeness=95. These tests pin completeness to 0 whenever counts.error &gt; 0 or there
 * are no data rows, while leaving the normal gradual score intact.
 *
 * <p>{@code report()} is private, so we drive it through the public {@link
 * ImportValidationService#validate} entry point (the same path the /import/validate endpoint uses).
 */
class ImportValidationAcceptanceTest {

  private final ImportValidationService service = new ImportValidationService();

  @Test
  void headerOnlyWorkbookReportsZeroCompletenessNotNearPerfect() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "header-only-teachers.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headerOnlyTeachersWorkbook()
    );

    ValidationReport report = service.validate("teachers", file);

    // Hard fail: header present but zero data rows -> a blocking error is raised.
    assertThat(report.counts().error()).isGreaterThan(0);
    assertThat(report.severity()).isEqualTo("error");
    // The defect: this used to be 95 ("良好" green) for a zero-row hard fail.
    assertThat(report.overview().completeness()).isZero();
  }

  @Test
  void emptyFileReportsZeroCompleteness() {
    MockMultipartFile empty = new MockMultipartFile(
        "file",
        "empty.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[0]
    );

    ValidationReport report = service.validate("teachers", empty);

    assertThat(report.counts().error()).isGreaterThan(0);
    assertThat(report.severity()).isEqualTo("error");
    assertThat(report.overview().completeness()).isZero();
  }

  @Test
  void cleanWorkbookKeepsHighGradualCompleteness() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "good-teachers.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        goodTeachersWorkbook()
    );

    ValidationReport report = service.validate("teachers", file);

    // Sanity: the normal no-error path is unchanged and still scores high.
    assertThat(report.counts().error()).isZero();
    assertThat(report.severity()).isNotEqualTo("error");
    assertThat(report.overview().completeness()).isEqualTo(100);
  }

  @Test
  void examEndTimeNotAfterStartIsRejectedAtValidation() throws Exception {
    // 迭代2 修复固化：结束时间不晚于开始时间(负/零时长)必须在校验阶段就以 error 拦截，否则会形成
    // 求解器半开区间重叠判定的盲区(start>=end → 空集 → 与任何场次都判不冲突)。
    MockMultipartFile file = new MockMultipartFile(
        "file", "inverted-exam.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", invertedExamWorkbook());

    ValidationReport report = service.validate("exams", file);

    assertThat(report.counts().error()).isGreaterThan(0);
    assertThat(report.items()).anyMatch(item -> "EXAM_TIME_RANGE_INVALID".equals(item.code()));
  }

  @Test
  void overLongTeacherFieldIsRejectedAtValidationWithPointer() throws Exception {
    // 迭代4 修复固化：超出 DB 列约束(name varchar(80)/teacher_no varchar(30))的字段，应在校验阶段
    // 带行/列指针报 FIELD_TOO_LONG，而不是"校验通过却 commit 时整批回滚只给笼统 400"。
    MockMultipartFile file = new MockMultipartFile(
        "file", "too-long.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", overLongNameTeachersWorkbook());

    ValidationReport report = service.validate("teachers", file);

    assertThat(report.counts().error()).isGreaterThan(0);
    assertThat(report.items()).anyMatch(item -> "FIELD_TOO_LONG".equals(item.code()));
  }

  @Test
  void decimalTextInNumericFieldIsFlaggedNotSilentlyTruncated() throws Exception {
    // 迭代10 P2 固化：数字字段的小数文本(如 "10.05")必须报 NUMBER_FORMAT_REVIEW 告警,而不是被旧的
    // replace(".0","") 静默改成 "105"(10 倍放大)后通过校验。修复用 BigDecimal.intValueExact 判整。
    MockMultipartFile file = new MockMultipartFile(
        "file", "decimal-seat.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", decimalSeatExamWorkbook());

    ValidationReport report = service.validate("exams", file);

    assertThat(report.items())
        .as("小数文本 10.05 应触发 NUMBER_FORMAT_REVIEW,而非静默截断为 105")
        .anyMatch(item -> "NUMBER_FORMAT_REVIEW".equals(item.code()));
  }

  private byte[] decimalSeatExamWorkbook() throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("exams");
      Row header = sheet.createRow(0);
      String[] cols = {"科目", "年级", "考试日期", "开始时间", "结束时间", "考场编号", "人数", "监考人数"};
      for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
      Row row = sheet.createRow(1);
      // 有效行,唯独"人数"为小数文本 10.05(旧逻辑会静默变 105)
      String[] vals = {"数学", "高一", "2026-06-22", "08:00", "10:00", "R101", "10.05", "2"};
      for (int i = 0; i < vals.length; i++) row.createCell(i).setCellValue(vals[i]);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] invertedExamWorkbook() throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("exams");
      Row header = sheet.createRow(0);
      String[] cols = {"科目", "年级", "考试日期", "开始时间", "结束时间", "考场编号", "人数", "监考人数"};
      for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
      Row row = sheet.createRow(1);
      String[] vals = {"数学", "高一", "2026-06-22", "10:00", "08:00", "R101", "40", "2"};
      for (int i = 0; i < vals.length; i++) row.createCell(i).setCellValue(vals[i]);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] overLongNameTeachersWorkbook() throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue("T2026001");
      row.createCell(1).setCellValue("长".repeat(100)); // > varchar(80)
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] headerOnlyTeachersWorkbook() throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      // No data rows on purpose.
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] goodTeachersWorkbook() throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue("T2026001");
      row.createCell(1).setCellValue("王老师");
      workbook.write(output);
      return output.toByteArray();
    }
  }
}
