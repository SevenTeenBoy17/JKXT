package com.jkxt.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.jkxt.tasks.api.ExamTaskDtos.AssignmentRow;
import com.jkxt.tasks.api.ExamTaskDtos.AssignmentsResponse;
import com.jkxt.tasks.service.ExamTaskService;
import com.lowagie.text.pdf.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Real public export/renderer; only the assignment lookup is replaced with synthetic rows. No DB or Spring. */
class ExamTaskPdfScopeTest {
  private static final UUID TASK = new UUID(0, 1);
  private static final String SCOPE = "仅监考安排，未含考生座位名单";
  private static final String DISCLAIMER = "当前任务排班快照；导出不代表已审批或已发布";

  @Test
  void everyPageOf120RowsExplainsScopeAndApprovalStatusWithoutLosingRows() throws Exception {
    byte[] pdf = renderer(120).export(TASK, "pdf");
    evidence("schedule-120.pdf", pdf);
    List<String> pages = pageText(pdf);
    assertThat(pages).as("Fixture must exercise real PDF pagination").hasSizeGreaterThanOrEqualTo(2);
    for (int index = 0; index < pages.size(); index++) {
      assertThat(pages.get(index)).as("Printable page %s", index + 1)
          .contains(SCOPE, DISCLAIMER, "考试日期", "监考教师", "角色", "来源");
    }
    String all = String.join("\n", pages);
    for (int row = 1; row <= 120; row++) {
      assertThat(all).as("Assignment %s must survive pagination exactly once", row)
          .containsOnlyOnce("ROW-%03d".formatted(row));
    }
    assertThat(pages.getLast()).contains("ROW-120", "教师120", "语文", "主监", "系统排考");
  }

  @Test
  void emptyExportStillHasScopeAndApprovalDisclaimer() throws Exception {
    byte[] pdf = renderer(0).export(TASK, "pdf");
    evidence("schedule-empty.pdf", pdf);
    assertThat(pageText(pdf)).hasSize(1).first().asString().contains(SCOPE, DISCLAIMER);
  }

  @Test
  void xlsxScopeAndOriginalHeaderAndDataRowPositionsAreUnchanged() throws Exception {
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(renderer(120).export(TASK, "xlsx")))) {
      var schedule = workbook.getSheetAt(0);
      assertThat(schedule.getSheetName()).isEqualTo("监考安排");
      assertThat(schedule.getRow(0).getCell(0).getStringCellValue()).isEqualTo("考试日期");
      assertThat(schedule.getRow(1).getCell(6).getStringCellValue()).isEqualTo("ROW-001");
      assertThat(schedule.getRow(120).getCell(6).getStringCellValue()).isEqualTo("ROW-120");
      assertThat(schedule.getLastRowNum()).isEqualTo(120);
      assertThat(schedule.getHeader().getCenter()).isEqualTo(SCOPE);
      assertThat(workbook.getSheet("使用说明").getRow(0).getCell(0).getStringCellValue()).isEqualTo(SCOPE);
      assertThat(workbook.getSheet("使用说明").getRow(1).getCell(0).getStringCellValue())
          .isEqualTo("当前任务排班快照；导出文件不代表已审批或已发布。请核对系统中的当前版本。");
    }
  }

  private static ExamTaskService renderer(int count) {
    // Stub the data boundary, never the export method, private renderer, PDF bytes, or text parser.
    var service = spy(new ExamTaskService(null, null, null, null, null, null, null, null, null, null, null, null));
    List<AssignmentRow> rows = new ArrayList<>();
    for (int index = 1; index <= count; index++) {
      rows.add(new AssignmentRow(new UUID(0, index), new UUID(1, index), new UUID(2, index), new UUID(3, index),
          "语文", "高一", LocalDate.of(2043, 4, 4), LocalTime.of(8, 0), LocalTime.of(9, 0),
          "R%03d".formatted(index), "考场" + index, "ROW-%03d".formatted(index), "教师%03d".formatted(index), "main", "solver"));
    }
    doReturn(new AssignmentsResponse(TASK, new UUID(4, 1), "succeeded", rows, List.of(), null, Map.of(), Map.of()))
        .when(service).assignments(TASK);
    return service;
  }

  private static List<String> pageText(byte[] data) throws Exception {
    var reader = new PdfReader(data);
    try {
      List<String> pages = new ArrayList<>();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        var fonts = reader.getPageN(page).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.FONT);
        var parser = new PdfContentParser(new PRTokeniser(reader.getPageContent(page)));
        var operands = new ArrayList<PdfObject>();
        var text = new StringBuilder();
        PdfName font = null;
        while (!parser.parse(operands).isEmpty()) {
          String operator = operands.getLast().toString();
          if ("Tf".equals(operator)) font = (PdfName) operands.getFirst();
          if ("Tj".equals(operator)) append(text, (PdfString) operands.getFirst(), fonts.getAsDict(font));
          if ("TJ".equals(operator)) {
            for (PdfObject item : ((PdfArray) operands.getFirst()).getElements()) {
              if (item instanceof PdfString value) append(text, value, fonts.getAsDict(font));
            }
          }
        }
        pages.add(text.toString());
      }
      return pages;
    } finally { reader.close(); }
  }

  private static void append(StringBuilder text, PdfString value, PdfDictionary font) {
    // Decode declared CJK encoding rather than assuming raw-byte string presence proves readable text.
    if (new PdfName("UniGB-UCS2-H").equals(font.getAsName(PdfName.ENCODING))) {
      text.append(new String(value.getBytes(), StandardCharsets.UTF_16BE));
    } else {
      text.append(value.toUnicodeString());
    }
    text.append('\n');
  }

  private static void evidence(String name, byte[] data) throws Exception {
    String directory = System.getProperty("jkxt.pdf.scope.evidence");
    if (directory == null || directory.isBlank()) return;
    Path target = Path.of(directory);
    Files.createDirectories(target);
    Files.write(target.resolve(name), data);
  }
}
