package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.importer.service.ImportValidationService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

/**
 * PB-1 (2026-10-18): the downloadable/sample exam template must reference a room that the
 * sample classroom template and the V5 demo roster actually contain; otherwise the shipped
 * "sample" quick flow fails with 400 EXAM_IMPORT_NO_VALID_ROW. Pure unit test, no database.
 */
class ImportTemplateSampleConsistencyTest {

  private static final int EXAM_ROOM_CODE_COLUMN = 5;
  private static final int CLASSROOM_CODE_COLUMN = 0;

  @Test
  void sampleExamRoomCodeExistsInSampleClassroomTemplateAndDemoSeed() throws Exception {
    ImportValidationService validation = new ImportValidationService();
    assertThat(header(validation.template("exams"), EXAM_ROOM_CODE_COLUMN)).isEqualTo("roomCode");
    String examRoom = sample(validation.template("exams"), EXAM_ROOM_CODE_COLUMN);
    String classroomCode = sample(validation.template("classrooms"), CLASSROOM_CODE_COLUMN);

    assertThat(examRoom)
        .as("sample exam roomCode must match the sample classroom code")
        .isEqualTo(classroomCode);
    try (InputStream seed = getClass().getResourceAsStream("/db/migration/V5__p5_masterdata_seed.sql")) {
      assertThat(seed).isNotNull();
      String sql = new String(seed.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(sql)
          .as("sample exam roomCode must be a classroom seeded for the demo organization")
          .contains("'00000000-0000-0000-0000-000000000001','" + examRoom + "'");
    }
  }

  private static String sample(byte[] workbook, int column) throws Exception {
    return cell(workbook, 1, column);
  }

  private static String header(byte[] workbook, int column) throws Exception {
    return cell(workbook, 0, column);
  }

  private static String cell(byte[] workbook, int rowIndex, int column) throws Exception {
    try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
      Row row = book.getSheetAt(0).getRow(rowIndex);
      return new DataFormatter().formatCellValue(row.getCell(column)).trim();
    }
  }
}