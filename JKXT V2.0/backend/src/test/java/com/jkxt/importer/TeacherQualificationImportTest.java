package com.jkxt.importer;

import static org.assertj.core.api.Assertions.*;
import com.jkxt.importer.service.ImportValidationService;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

/** Spreadsheet meaning, deliberately independent of request identity and database fixtures. */
class TeacherQualificationImportTest {
  final ImportValidationService service=new ImportValidationService();
  static MockMultipartFile file(XSSFWorkbook workbook) throws Exception {
    try(var bytes=new ByteArrayOutputStream()){workbook.write(bytes);return new MockMultipartFile("file","teachers.xlsx","application/octet-stream",bytes.toByteArray());}
  }
  static XSSFWorkbook workbook(String qualifications,String expiry) {
    var wb=new XSSFWorkbook();var s=wb.createSheet("教师");var h=s.createRow(0);
    String[] headers={"工号","姓名","监考资格","资格有效期"};
    for(int i=0;i<headers.length;i++)h.createCell(i).setCellValue(headers[i]);
    var row=s.createRow(1);row.createCell(0).setCellValue("Q001");row.createCell(1).setCellValue("合成教师");
    row.createCell(2).setCellValue(qualifications);row.createCell(3).setCellValue(expiry);return wb;
  }
  @Test void canonicalChineseAndExplicitEmptyRemainDistinctFromAbsent() throws Exception {
    try(var wb=workbook("监考员，主监考","2026-06-08")) {
      var row=service.parseRows("teachers",file(wb)).getFirst();
      assertThat(row.get("qualifications")).isEqualTo("INVIGILATOR,CHIEF");
      assertThat(row.get("qualificationExpiry")).isEqualTo("2026-06-08");
      wb.getSheetAt(0).getRow(1).getCell(2).setBlank();wb.getSheetAt(0).getRow(1).getCell(3).setBlank();
      var blank=service.parseRows("teachers",file(wb)).getFirst();
      assertThat(blank.values()).containsEntry("qualifications","").containsEntry("qualificationExpiry","");
      wb.getSheetAt(0).getRow(0).removeCell(wb.getSheetAt(0).getRow(0).getCell(2));
      wb.getSheetAt(0).getRow(0).removeCell(wb.getSheetAt(0).getRow(0).getCell(3));
      assertThat(service.parseRows("teachers",file(wb)).getFirst().values()).doesNotContainKeys("qualifications","qualificationExpiry");
      assertThat(service.validate("teachers",file(wb)).counts().error()).isZero();
    }
  }
  @ParameterizedTest @ValueSource(strings={"ADMIN","INVIGILATOR,INVIGILATOR","监考员,INVIGILATOR","chief","INVIGILATOR;CHIEF"})
  void invalidOrRepeatedQualificationsHaveRowFieldAndStableCode(String value) throws Exception {
    try(var wb=workbook(value,"")) {
      assertThat(service.validate("teachers",file(wb)).items()).anySatisfy(i->{
        assertThat(i.row()).isEqualTo(2);assertThat(i.field()).isEqualTo("qualifications");
        assertThat(i.column()).isEqualTo("监考资格");assertThat(i.severity()).isEqualTo("error");
        String expected=Map.of("ADMIN","IMPORT_QUALIFICATION_INVALID","INVIGILATOR,INVIGILATOR","IMPORT_QUALIFICATION_DUPLICATE",
            "监考员,INVIGILATOR","IMPORT_QUALIFICATION_DUPLICATE","chief","IMPORT_QUALIFICATION_INVALID","INVIGILATOR;CHIEF","IMPORT_QUALIFICATION_INVALID").get(value);
        assertThat(i.code()).isEqualTo(expected);
        assertThat(i.problem()).contains(expected.equals("IMPORT_QUALIFICATION_DUPLICATE")?"每项仅填写一次":"仅接受");
        assertThat(i.suggest()).isNotBlank();
      });
    }
  }
  @Test void duplicateCanonicalAndAliasHeadersAreNotLastColumnWins() throws Exception {
    try(var wb=workbook("INVIGILATOR","")) {
      wb.getSheetAt(0).getRow(0).createCell(4).setCellValue("监考资质");
      wb.getSheetAt(0).getRow(0).createCell(5).setCellValue("qualificationExpiry");
      assertThat(service.validate("teachers",file(wb)).items().stream().filter(i->i.code().equals("IMPORT_DUPLICATE_COLUMN"))).hasSize(2);
    }
  }
  @Test void nativeDateUsesWorkbook1900Or1904SystemWithoutDisplayParsing() throws Exception {
    for(boolean date1904:List.of(false,true))try(var wb=workbook("CHIEF","")) {
      wb.getCTWorkbook().getWorkbookPr().setDate1904(date1904);
      var cell=wb.getSheetAt(0).getRow(1).getCell(3);
      cell.setCellValue(DateUtil.getExcelDate(LocalDate.of(2026,6,8),date1904));
      var style=wb.createCellStyle();style.setDataFormat(wb.createDataFormat().getFormat("yyyy/mm/dd"));cell.setCellStyle(style);
      assertThat(service.validate("teachers",file(wb)).counts().error()).isZero();
      assertThat(service.parseRows("teachers",file(wb)).getFirst().get("qualificationExpiry")).isEqualTo("2026-06-08");
      assertThat(service.parseRows("teachers",file(wb)).getFirst().get("qualifications")).isEqualTo("CHIEF");
    }
  }
  @ParameterizedTest @ValueSource(strings={"2026-02-30","06/08/2026","2026-06-08T00:00:00"})
  void ambiguousOrImpossibleTextDatesAreErrors(String expiry) throws Exception {
    try(var wb=workbook("INVIGILATOR",expiry)) {
      assertThat(service.validate("teachers",file(wb)).items()).anyMatch(i->i.code().equals("IMPORT_QUALIFICATION_EXPIRY_INVALID"));
    }
  }
  @Test void timeFractionExcelLeapBugAndFormulaAreErrors() throws Exception {
    try(var wb=workbook("INVIGILATOR","")) {
      var cell=wb.getSheetAt(0).getRow(1).getCell(3);var style=wb.createCellStyle();style.setDataFormat((short)14);cell.setCellStyle(style);
      for(double n:List.of(60d,46000.5d,-1d)) {
        cell.setCellValue(n);
        assertThat(service.validate("teachers",file(wb)).items()).anyMatch(i->i.code().equals("IMPORT_QUALIFICATION_EXPIRY_INVALID"));
      }
      cell.setCellFormula("DATE(2026,6,8)");
      assertThat(service.validate("teachers",file(wb)).items()).anyMatch(i->i.code().equals("IMPORT_QUALIFICATION_EXPIRY_INVALID"));
    }
  }
  @Test void templateContainsExplicitFieldsAndTheirMeaning() throws Exception {
    try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(service.template("teachers")))) {
      assertThat(wb.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short)11);
      assertThat(wb.getSheetAt(0).getRow(0).getCell(9).getStringCellValue()).isEqualTo("监考资格");
      assertThat(wb.getSheetAt(0).getRow(0).getCell(10).getStringCellValue()).isEqualTo("资格有效期");
      assertThat(wb.getSheetAt(0).getRow(0).getCell(9).getCellComment().getString().getString()).contains("空白","INVIGILATOR","CHIEF");
    }
  }
}

/** Formal compatibility is exercised only by the isolated formal runner and real portal login. */
@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@org.springframework.test.context.ActiveProfiles("test")
class FormalTeacherQualificationPersistenceTest {
  @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext application;
  @org.springframework.beans.factory.annotation.Autowired org.springframework.test.web.servlet.MockMvc http;
  @org.springframework.beans.factory.annotation.Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
  @Test void formalLegacyNonDecimalSubjectHoursToleranceRemainsExplicit() throws Exception {
    try(var f=new com.jkxt.security.PortalHttpFixture(application);var wb=new XSSFWorkbook()) {
      var session=f.login(com.jkxt.security.auth.PortalPurpose.SCHOOL,f.account("ADMIN"));
      String key="H-"+UUID.randomUUID().toString().substring(0,8);var sheet=wb.createSheet();var header=sheet.createRow(0);
      String[] columns={"科目编号","科目名称","类别","考试类型","周课时"};
      for(int c=0;c<columns.length;c++)header.createCell(c).setCellValue(columns[c]);
      String[] values={"NaN","Infinity","0x1p0","2.5",""};
      for(int r=0;r<values.length;r++){var row=sheet.createRow(r+1);String[] cells={key+r,key+"合成科目"+r,"main","written",values[r]};for(int c=0;c<cells.length;c++)row.createCell(c).setCellValue(cells[c]);}
      var file=TeacherQualificationImportTest.file(wb);
      var preview=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/import/subjects/validate").file(file),session)).andReturn().getResponse();
      assertThat(preview.getStatus()).isEqualTo(200);assertThat(mapper.readTree(preview.getContentAsByteArray()).path("counts").path("error").asInt()).isZero();
      var result=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/import/subjects/commit").file(file),session)).andReturn().getResponse();
      assertThat(result.getStatus()).isEqualTo(200);
      var jdbc=application.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
      for(int r=0;r<values.length;r++) {
        var hours=jdbc.queryForObject("select hours from subject where org_id=(select id from organization where slug='demo') and code=?",java.math.BigDecimal.class,key+r);
        if(r==3)assertThat(hours).isEqualByComparingTo("2.5");else assertThat(hours).isNull();
      }
    }
  }
  @Test void oldNineColumnsPreserveExistingCredentialsAndDisabledStatusWhileNewRowsGetSafeDefault() throws Exception {
    try(var f=new com.jkxt.security.PortalHttpFixture(application)) {
      var session=f.login(com.jkxt.security.auth.PortalPurpose.SCHOOL,f.account("ADMIN"));
      String key="F-"+UUID.randomUUID().toString().substring(0,8);
      try(var wb=TeacherQualificationImportTest.workbook("INVIGILATOR,CHIEF","2027-06-08")) {
        wb.getSheetAt(0).getRow(1).getCell(0).setCellValue(key);
        var result=commit(f,session,TeacherQualificationImportTest.file(wb));assertThat(result.getStatus()).isEqualTo(200);
      }
      var teacher=read(f,session,key);String uuid=teacher.path("uuid").asText();
      var body=mapper.createObjectNode().put("teacherNo",key).put("name","已停用合成教师").put("status","disabled").put("qualificationExpiry","2027-06-08");
      body.putArray("qualifications").add("INVIGILATOR").add("CHIEF");
      assertThat(http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/teachers/"+uuid),session).contentType("application/json").content(body.toString())).andReturn().getResponse().getStatus()).isEqualTo(200);
      assertThat(commit(f,session,nineColumns(key)).getStatus()).isEqualTo(200);
      teacher=read(f,session,key);assertThat(teacher.path("qualifications").toString()).isEqualTo("[\"INVIGILATOR\",\"CHIEF\"]");
      assertThat(teacher.path("qualificationExpiry").asText()).isEqualTo("2027-06-08");assertThat(teacher.path("status").asText()).isEqualTo("disabled");
      String fresh="N-"+UUID.randomUUID().toString().substring(0,8);assertThat(commit(f,session,nineColumns(fresh)).getStatus()).isEqualTo(200);
      var added=read(f,session,fresh);assertThat(added.path("qualifications").toString()).isEqualTo("[\"INVIGILATOR\"]");assertThat(added.path("qualificationExpiry").isNull()).isTrue();
      try(var wb=TeacherQualificationImportTest.workbook("","")) {
        wb.getSheetAt(0).getRow(1).getCell(0).setCellValue(key);assertThat(commit(f,session,TeacherQualificationImportTest.file(wb)).getStatus()).isEqualTo(200);
      }
      teacher=read(f,session,key);assertThat(teacher.path("qualifications").toString()).isEqualTo("[]");assertThat(teacher.path("qualificationExpiry").isNull()).isTrue();assertThat(teacher.path("status").asText()).isEqualTo("disabled");
      String blankKey="B-"+UUID.randomUUID().toString().substring(0,8);
      try(var wb=TeacherQualificationImportTest.workbook("","")) {
        wb.getSheetAt(0).getRow(1).getCell(0).setCellValue(blankKey);assertThat(commit(f,session,TeacherQualificationImportTest.file(wb)).getStatus()).isEqualTo(200);
      }
      var blank=read(f,session,blankKey);assertThat(blank.path("qualifications").toString()).isEqualTo("[]");assertThat(blank.path("qualificationExpiry").isNull()).isTrue();
    }
  }
  @Test void formalXlsCompatibilityAndNative1904ExpiryAreBothRetained() throws Exception {
    try(var f=new com.jkxt.security.PortalHttpFixture(application)) {
      var session=f.login(com.jkxt.security.auth.PortalPurpose.SCHOOL,f.account("ADMIN"));String key="X-"+UUID.randomUUID().toString().substring(0,8);
      try(var wb=new org.apache.poi.hssf.usermodel.HSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
        var sheet=wb.createSheet();var h=sheet.createRow(0);h.createCell(0).setCellValue("工号");h.createCell(1).setCellValue("姓名");
        var row=sheet.createRow(1);row.createCell(0).setCellValue(key);row.createCell(1).setCellValue("合成二进制旧文件");wb.write(bytes);
        assertThat(commit(f,session,new MockMultipartFile("file","old.xls","application/octet-stream",bytes.toByteArray())).getStatus()).isEqualTo(200);
      }
      assertThat(read(f,session,key).path("qualifications").toString()).isEqualTo("[\"INVIGILATOR\"]");
      try(var wb=TeacherQualificationImportTest.workbook("CHIEF","")) {
        wb.getCTWorkbook().getWorkbookPr().setDate1904(true);var row=wb.getSheetAt(0).getRow(1);row.getCell(0).setCellValue(key);
        var date=row.getCell(3);date.setCellValue(DateUtil.getExcelDate(LocalDate.of(2026,6,8),true));var style=wb.createCellStyle();style.setDataFormat((short)14);date.setCellStyle(style);
        assertThat(commit(f,session,TeacherQualificationImportTest.file(wb)).getStatus()).isEqualTo(200);
      }
      var read=read(f,session,key);assertThat(read.path("qualifications").toString()).isEqualTo("[\"CHIEF\"]");assertThat(read.path("qualificationExpiry").asText()).isEqualTo("2026-06-08");
    }
  }
  MockMultipartFile nineColumns(String key) throws Exception {
    try(var wb=new XSSFWorkbook()) {
      var sheet=wb.createSheet();var h=sheet.createRow(0);String[] headers={"工号","姓名","性别","年级","任教学科","手机号","邮箱","班主任","所带班级"};
      for(int c=0;c<headers.length;c++)h.createCell(c).setCellValue(headers[c]);var row=sheet.createRow(1);row.createCell(0).setCellValue(key);row.createCell(1).setCellValue("正式旧模板合成教师");return TeacherQualificationImportTest.file(wb);
    }
  }
  org.springframework.mock.web.MockHttpServletResponse commit(com.jkxt.security.PortalHttpFixture f,com.jkxt.security.PortalHttpFixture.AuthenticatedSession session,MockMultipartFile file) throws Exception {
    return http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/import/teachers/commit").file(file),session)).andReturn().getResponse();
  }
  com.fasterxml.jackson.databind.JsonNode read(com.jkxt.security.PortalHttpFixture f,com.jkxt.security.PortalHttpFixture.AuthenticatedSession session,String key) throws Exception {
    var response=http.perform(f.apply(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/teachers").param("q",key),session)).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(200);
    var rows=mapper.readTree(response.getContentAsByteArray());assertThat(rows.size()).isEqualTo(1);var row=rows.get(0);
    assertThat(row.path("teacherNo").asText()).isEqualTo(key);assertThat(row.path("id").asText()).isEqualTo(key);assertThat(UUID.fromString(row.path("uuid").asText())).isNotNull();return row;
  }
}
