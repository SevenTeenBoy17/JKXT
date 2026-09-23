package com.jkxt.tasks;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.jkxt.demo.*;
import com.lowagie.text.pdf.*;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties={"jkxt.auth.captcha-debug=false","jkxt.notify.email.enabled=false","jkxt.notify.sms.enabled=false"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles("test") @Import(DemoTestFixture.Time.class)
public class ExamTaskExportArtifactIntegrationTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http; @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired DemoTestFixture.MutableClock clock;
  @AfterEach void retire(){DemoTestFixture.retire(jdbc);}
  public static final List<String> COLUMNS=List.of("考试日期","开始时间","结束时间","科目","年级","考场","工号","监考教师","角色","来源");
  public static String safe(String value){return value!=null && !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0))>=0?"'"+value:value==null?"":value;}
  static String label(String role){return role.equals("main")?"主监":"副监";}
  static String source(String value){return value.equals("solver")?"系统排考":value.equals("manual")?"人工调整":value;}
  public static List<String> cells(JsonNode row) {
    return List.of(row.path("examDate").asText(),java.time.LocalTime.parse(row.path("startTime").asText()).toString(),java.time.LocalTime.parse(row.path("endTime").asText()).toString(),
        safe(row.path("subjectName").asText("")),safe(row.path("grade").asText("")),safe(row.path("roomCode").asText("")),
        safe(row.path("teacherNo").asText("")),safe(row.path("teacherName").asText("")),label(row.path("role").asText()),source(row.path("source").asText()));
  }
  public static Map<String,String> assertWorkbook(byte[] bytes,JsonNode rows) throws Exception {
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(2);assertThat(wb.getSheetName(0)).isEqualTo("监考安排");assertThat(wb.getSheetName(1)).isEqualTo("导出说明");
      var sheet=wb.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(rows.size());
      for(int r=0;r<=rows.size();r++) {
        var expected=r==0?COLUMNS:cells(rows.get(r-1));var row=sheet.getRow(r);assertThat(row.getLastCellNum()).isEqualTo((short)10);
        for(int c=0;c<10;c++){var cell=row.getCell(c);assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getStringCellValue()).as("row %s col %s",r,c).isEqualTo(expected.get(c));}
      }
      var metadata=new LinkedHashMap<String,String>();
      for(var row:wb.getSheetAt(1)){assertThat(row.getLastCellNum()).isEqualTo((short)2);metadata.put(row.getCell(0).getStringCellValue(),row.getCell(1).getStringCellValue());}
      for(var s:wb)for(var row:s)for(var cell:row)assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
      return metadata;
    }
  }
  public static String assertPdf(byte[] bytes,JsonNode rows,Map<String,String> metadata,boolean multi) throws Exception {
    var reader=new PdfReader(bytes);
    try {
      if(multi)assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(2);
      var pages=new ArrayList<String>();var extractor=new PdfTextExtractor(reader);
      for(int p=1;p<=reader.getNumberOfPages();p++) {
        String text=extractor.getTextFromPage(p);pages.add(text);
        for(String value:metadata.values())assertThat(text).as("page %s metadata %s",p,value).contains(value);
        assertThat(text).contains("第 "+p+" 页","监考教师","考试日期").doesNotContain("\uFFFD");
        var fonts=reader.getPageN(p).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.FONT);assertThat(fonts).isNotNull();boolean embedded=false;
        for(var key:fonts.getKeys()) {
          var font=(PdfDictionary)PdfReader.getPdfObject(fonts.get(key));
          assertThat(font.getAsName(PdfName.BASEFONT).toString()).doesNotContain("Helvetica","STSong");
          if(PdfName.TYPE0.equals(font.getAsName(PdfName.SUBTYPE))) {
            assertThat(PdfReader.getPdfObject(font.get(PdfName.TOUNICODE))).isInstanceOf(PRStream.class);
            var descendant=(PdfDictionary)PdfReader.getPdfObject(font.getAsArray(PdfName.DESCENDANTFONTS).getPdfObject(0));
            var descriptor=descendant.getAsDict(PdfName.FONTDESCRIPTOR);
            var stream=PdfReader.getPdfObject(descriptor.get(PdfName.FONTFILE2));if(stream==null)stream=PdfReader.getPdfObject(descriptor.get(PdfName.FONTFILE3));
            assertThat(stream).isInstanceOf(PRStream.class);assertThat(PdfReader.getStreamBytes((PRStream)stream)).isNotEmpty();embedded=true;
          }
        }
        assertThat(embedded).isTrue();
      }
      for(String value:metadata.values()){assertThat(reader.getInfo().get("Subject")).contains(value);assertThat(reader.getInfo().get("Keywords")).contains(value);}
      assertThat(reader.getInfo().get("Title")).contains(metadata.get("数据说明"));
      String all=String.join("\n",pages).replaceAll("\\s+","");
      for(var row:rows) {
        for(String key:List.of("examDate","subjectName","grade","roomCode","teacherNo","teacherName"))
          assertThat(all).as("PDF row %s field %s",row.path("assignmentId"),key).contains(row.path(key).asText("").replaceAll("\\s+",""));
        assertThat(all).contains(label(row.path("role").asText()),source(row.path("source").asText()));
        var sequence=new ArrayList<String>();sequence.add(row.path("examDate").asText());
        sequence.add(LocalTime.parse(row.path("startTime").asText()).toString());sequence.add(LocalTime.parse(row.path("endTime").asText()).toString());
        for(String key:List.of("subjectName","grade","roomCode","teacherNo","teacherName"))sequence.add(row.path(key).asText(""));
        sequence.add(label(row.path("role").asText()));sequence.add(source(row.path("source").asText()));
        String exactRow=String.join("",sequence).replaceAll("\\s+","");
        assertThat(all).as("all ten PDF cells for assignment %s",row.path("assignmentId")).contains(exactRow);
        assertThat(all.indexOf(exactRow)).isEqualTo(all.lastIndexOf(exactRow));
      }
      return String.join("\n",pages);
    }finally{reader.close();}
  }
  byte[] export(DemoTestFixture f,DemoTestFixture.Browser b,UUID task,UUID run,long version,String format) throws Exception {
    var response=http.perform(f.apply(get("/api/v1/exam-tasks/"+task+"/export").param("format",format).param("runId",run.toString()).param("revision",Long.toString(version)),b)).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(format.equals("pdf")?"application/pdf":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");return response.getContentAsByteArray();
  }
  Map<String,String> expectedMetadata(DemoTestFixture.Browser b,UUID task,UUID run,long version) {
    Instant snapshot=jdbc.queryForObject("select snapshot_at from demo_schedule_export_snapshot where run_id=? and export_revision=?",java.sql.Timestamp.class,run,version).toInstant();
    var m=new LinkedHashMap<String,String>();m.put("数据说明","演示·模拟数据");m.put("任务",task.toString());m.put("运行",run.toString());m.put("代次",Long.toString(b.generation()));
    m.put("导出版本",Long.toString(version));m.put("快照时间",snapshot.toString());m.put("快照格式版本","1");m.put("导出时间",clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());return m;
  }
  @Test void actualMultipagePdfAndWorkbookReadEveryRowPageAndEmbeddedChineseFont() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b);
    assertThat(jdbc.queryForObject("select count(*) from subject where org_id=? and name='语文'",Integer.class,b.org())).isEqualTo(1);
    String room=jdbc.queryForObject("select code from classroom where org_id=? order by code limit 1",String.class,b.org());
    try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
      var sheet=wb.createSheet("考试");String[] columns={"subjectName","grade","examDate","startTime","endTime","roomCode","seatDemand","supervisorsPerRoom"};
      var head=sheet.createRow(0);for(int c=0;c<columns.length;c++)head.createCell(c).setCellValue(columns[c]);
      for(int i=1;i<=30;i++) {
        var row=sheet.createRow(i);String[] values={"语文","高一",LocalDate.of(2028,6,1).plusDays(i).toString(),"09:00","10:30",room,"20","2"};
        for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);
      }
      wb.write(out);var file=new MockMultipartFile("file","synthetic.xlsx","application/octet-stream",out.toByteArray());
      var imported=http.perform(f.apply(multipart("/api/v1/import/exams/commit").file(file).param("taskId",task.toString()),b)).andReturn().getResponse();
      assertThat(imported.getStatus()).as(imported.getContentAsString()).isEqualTo(200);
    }
    // Exam import replaces this task's earlier one-row batch; the accepted batch has 30 exams.
    assertThat(jdbc.queryForObject("select count(*) from exam where org_id=? and exam_task_id=?",Integer.class,b.org(),task)).isEqualTo(30);
    UUID run=f.solve(b,task);var rows=f.storedSnapshot(b,run,0).path("rows");assertThat(rows.size()).isEqualTo(60);
    byte[] xlsx=export(f,b,task,run,0,"xlsx"),pdf=export(f,b,task,run,0,"pdf");
    var metadata=assertWorkbook(xlsx,rows);assertThat(metadata).isEqualTo(expectedMetadata(b,task,run,0));assertPdf(pdf,rows,metadata,true);
    Path dir=Path.of("target/task6c-export-artifacts");Files.createDirectories(dir);
    Files.write(dir.resolve("demo-exact-version-multipage.pdf"),pdf);Files.write(dir.resolve("demo-exact-version-multipage.xlsx"),xlsx);
    Files.writeString(dir.resolve("demo-exact-version-multipage.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metadata",metadata,"rows",rows)));
  }
  @Test void eachDangerousPrefixStaysRawInSnapshotsAndPdfAndIsEscapedOnceInEveryWorkbookTextColumn() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b),run=f.solve(b,task);
    long version=0;
    for(String prefix:List.of("=","+","-","@","\t","\r")) {
      jdbc.update("update exam set subject_name=?,grade=? where org_id=? and exam_task_id=?",prefix+"语文",prefix+"高一",b.org(),task);
      jdbc.update("update classroom set code=? where id=(select classroom_id from schedule_assignment where schedule_run_id=? limit 1)",prefix+"考场",run);
      jdbc.update("update teacher set name=?||teacher_no,teacher_no=?||teacher_no where id in(select teacher_id from schedule_assignment where schedule_run_id=?)",prefix,prefix,run);
      var current=f.assignments(b,task);var row=current.path("rows").get(0);
      var patched=http.perform(f.apply(patch("/api/v1/tasks/"+task+"/schedule").header("If-Match",current.path("stats").path("outputToken").asText()),b)
          .contentType("application/json").content(mapper.writeValueAsString(Map.of("assignmentId",row.path("assignmentId").asText(),"teacherId",row.path("teacherId").asText(),"expectedRevision",version)))).andReturn().getResponse();
      assertThat(patched.getStatus()).as(patched.getContentAsString()).isEqualTo(200);version++;
      var snapshot=f.storedSnapshot(b,run,version);for(var raw:snapshot.path("rows"))for(String key:List.of("subjectName","grade","roomCode","teacherNo","teacherName"))assertThat(raw.path(key).asText()).startsWith(prefix);
      var xlsx=export(f,b,task,run,version,"xlsx");var metadata=assertWorkbook(xlsx,snapshot.path("rows"));assertThat(metadata).isEqualTo(expectedMetadata(b,task,run,version));
      assertWorkbook(export(f,b,task,run,version,"xlsx"),snapshot.path("rows"));
      assertPdf(export(f,b,task,run,version,"pdf"),snapshot.path("rows"),metadata,false);
    }
  }
  @Test void unsupportedBusinessGlyphRejectsPdfWithActionableExcelAlternativeWithoutLosingWorkbookContent() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b);
    jdbc.update("update teacher set name=?||teacher_no where org_id=?","\uD842\uDFB7老师",b.org());
    UUID run=f.solve(b,task);var rows=f.storedSnapshot(b,run,0).path("rows");
    for(var row:rows)assertThat(row.path("teacherName").asText()).startsWith("\uD842\uDFB7老师");
    assertWorkbook(export(f,b,task,run,0,"xlsx"),rows);
    var response=http.perform(f.apply(get("/api/v1/exam-tasks/"+task+"/export").param("format","pdf")
        .param("runId",run.toString()).param("revision","0"),b)).andReturn().getResponse();
    // Preserve actual corrupt output on RED; status alone does not establish silent content loss.
    if(response.getStatus()==200) {
      var reader=new PdfReader(response.getContentAsByteArray());var text=new StringBuilder();
      try{var extractor=new PdfTextExtractor(reader);for(int p=1;p<=reader.getNumberOfPages();p++)text.append(extractor.getTextFromPage(p));}finally{reader.close();}
      Path dir=Path.of("target/task6c-export-artifacts");Files.createDirectories(dir);
      Files.write(dir.resolve("unsupported-glyph-red.pdf"),response.getContentAsByteArray());
      Files.writeString(dir.resolve("unsupported-glyph-red.txt"),text.toString());
      assertThat(text.toString()).contains("老师").doesNotContain("\uD842\uDFB7");
    }
    assertThat(response.getStatus()).as("unsupported business glyph PDF status").isEqualTo(422);
    var error=mapper.readTree(response.getContentAsByteArray());
    assertThat(error.path("code").asText()).isEqualTo("PDF_FONT_GLYPH_UNAVAILABLE");
    assertThat(error.path("message").asText()).contains("U+20BB7","Excel");
  }
  @Test void supportedNonBmpBusinessGlyphSurvivesActualPdfAndWorkbook() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b);
    String supported=new String(Character.toChars(0x2CB76));
    jdbc.update("update teacher set name=?||teacher_no where org_id=?",supported+"老师",b.org());
    UUID run=f.solve(b,task);var rows=f.storedSnapshot(b,run,0).path("rows");
    for(var row:rows)assertThat(row.path("teacherName").asText()).startsWith(supported+"老师");
    byte[] workbook=export(f,b,task,run,0,"xlsx"),pdf=export(f,b,task,run,0,"pdf");
    var metadata=assertWorkbook(workbook,rows);
    assertThat(assertPdf(pdf,rows,metadata,false)).contains(supported+"老师");
    Path dir=Path.of("target/task6c-export-artifacts");Files.createDirectories(dir);
    Files.write(dir.resolve("supported-non-bmp.pdf"),pdf);Files.write(dir.resolve("supported-non-bmp.xlsx"),workbook);
  }
  @Test void importedMillisecondTimesSurvivePersistedExamAssignmentSnapshotAndBothFiles() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b);
    String room=jdbc.queryForObject("select code from classroom where org_id=? order by code limit 1",String.class,b.org());
    LocalTime start=LocalTime.parse("09:00:00.123"),end=LocalTime.parse("10:30:00.654");
    try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
      var sheet=wb.createSheet("考试");String[] columns={"subjectName","grade","examDate","startTime","endTime","roomCode","seatDemand","supervisorsPerRoom"};
      String[] values={"语文","高一","2028-06-01",start.toString(),end.toString(),room,"20","2"};
      var header=sheet.createRow(0);var row=sheet.createRow(1);for(int c=0;c<columns.length;c++){header.createCell(c).setCellValue(columns[c]);row.createCell(c).setCellValue(values[c]);}
      wb.write(out);var file=new MockMultipartFile("file","millisecond-times.xlsx","application/octet-stream",out.toByteArray());
      var imported=http.perform(f.apply(multipart("/api/v1/import/exams/commit").file(file).param("taskId",task.toString()),b)).andReturn().getResponse();
      assertThat(imported.getStatus()).as(imported.getContentAsString()).isEqualTo(200);
    }
    var exams=jdbc.query("select start_time,end_time from exam where org_id=? and exam_task_id=?",
        (rs,n)->List.of(rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class)),b.org(),task);
    assertThat(exams).as("persisted imported exam precision").containsExactly(List.of(start,end));
    UUID run=f.solve(b,task);
    var persisted=jdbc.query("select start_time,end_time from schedule_assignment where org_id=? and schedule_run_id=?",
        (rs,n)->List.of(rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class)),b.org(),run);
    assertThat(persisted).as("persisted solver assignment precision").hasSize(2).containsOnly(List.of(start,end));
    var rows=f.storedSnapshot(b,run,0).path("rows");assertThat(rows.size()).isEqualTo(2);
    for(var row:rows){assertThat(LocalTime.parse(row.path("startTime").asText())).as("snapshot start precision").isEqualTo(start);
      assertThat(LocalTime.parse(row.path("endTime").asText())).as("snapshot end precision").isEqualTo(end);}
    byte[] workbook=export(f,b,task,run,0,"xlsx"),pdf=export(f,b,task,run,0,"pdf");
    var metadata=assertWorkbook(workbook,rows);assertThat(metadata).isEqualTo(expectedMetadata(b,task,run,0));
    assertPdf(pdf,rows,metadata,false);
    Path dir=Path.of("target/task6c-export-artifacts");Files.createDirectories(dir);
    Files.write(dir.resolve("millisecond-precision.pdf"),pdf);Files.write(dir.resolve("millisecond-precision.xlsx"),workbook);
    Files.writeString(dir.resolve("millisecond-precision.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metadata",metadata,"rows",rows)));
  }
  @Test void importedMicrosecondTimesSurvivePersistedExamAssignmentSnapshotAndBothFiles() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=f.smallTask(b);
    String room=jdbc.queryForObject("select code from classroom where org_id=? order by code limit 1",String.class,b.org());
    LocalTime start=LocalTime.parse("09:00:00.123456"),end=LocalTime.parse("10:30:00.654321");
    try(var wb=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
      var sheet=wb.createSheet("考试");String[] columns={"subjectName","grade","examDate","startTime","endTime","roomCode","seatDemand","supervisorsPerRoom"};
      String[] values={"语文","高一","2028-06-01",start.toString(),end.toString(),room,"20","2"};
      var header=sheet.createRow(0);var row=sheet.createRow(1);for(int c=0;c<columns.length;c++){header.createCell(c).setCellValue(columns[c]);row.createCell(c).setCellValue(values[c]);}
      wb.write(out);var file=new MockMultipartFile("file","precise-times.xlsx","application/octet-stream",out.toByteArray());
      var imported=http.perform(f.apply(multipart("/api/v1/import/exams/commit").file(file).param("taskId",task.toString()),b)).andReturn().getResponse();
      assertThat(imported.getStatus()).as(imported.getContentAsString()).isEqualTo(200);
    }
    var exams=jdbc.query("select start_time,end_time from exam where org_id=? and exam_task_id=?",
        (rs,n)->List.of(rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class)),b.org(),task);
    assertThat(exams).as("persisted imported exam precision").containsExactly(List.of(start,end));
    UUID run=f.solve(b,task);
    var persisted=jdbc.query("select start_time,end_time from schedule_assignment where org_id=? and schedule_run_id=?",
        (rs,n)->List.of(rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class)),b.org(),run);
    assertThat(persisted).as("persisted solver assignment precision").hasSize(2).containsOnly(List.of(start,end));
    var rows=f.storedSnapshot(b,run,0).path("rows");assertThat(rows.size()).isEqualTo(2);
    for(var row:rows){assertThat(LocalTime.parse(row.path("startTime").asText())).as("snapshot start precision").isEqualTo(start);
      assertThat(LocalTime.parse(row.path("endTime").asText())).as("snapshot end precision").isEqualTo(end);}
    byte[] workbook=export(f,b,task,run,0,"xlsx"),pdf=export(f,b,task,run,0,"pdf");
    var metadata=assertWorkbook(workbook,rows);assertThat(metadata).isEqualTo(expectedMetadata(b,task,run,0));
    assertPdf(pdf,rows,metadata,false);
    Path dir=Path.of("target/task6c-export-artifacts");Files.createDirectories(dir);
    Files.write(dir.resolve("microsecond-precision.pdf"),pdf);Files.write(dir.resolve("microsecond-precision.xlsx"),workbook);
    Files.writeString(dir.resolve("microsecond-precision.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metadata",metadata,"rows",rows)));
  }
}
