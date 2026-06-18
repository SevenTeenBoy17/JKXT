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
