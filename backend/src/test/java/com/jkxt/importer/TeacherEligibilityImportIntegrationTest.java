package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.importer.service.ImportCommitService;
import com.jkxt.importer.service.ImportValidationService;
import java.io.ByteArrayOutputStream;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TeacherEligibilityImportIntegrationTest {
  @Autowired ImportValidationService validation;
  @Autowired ImportCommitService commits;
  @Autowired JdbcTemplate jdbc;
  private UUID org;
  private final List<String> headers = List.of("工号", "姓名", "监考资格", "资格有效期", "每日监考上限");
  @BeforeEach void prepare() {
    org = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'资格导入隔离',?)", org, "eligibility-" + org);
    TenantContext.set(org);
  }
  @AfterEach void clear() { TenantContext.clear(); }

  @ParameterizedTest @ValueSource(strings={"xlsx","xls"})
  void authentic120TeacherFilesPersistRolesExpiryAndPersonalLimits(String format) throws Exception {
    List<List<String>> rows = new ArrayList<>();
    for(int i=0;i<120;i++) rows.add(List.of("T" + i, "测试教师" + i,
        i%2==0 ? "INVIGILATOR,CHIEF" : "INVIGILATOR", "2028-12-31", String.valueOf(1+i%4)));
    var file = workbook(format, headers, rows);
    String reports = System.getProperty("jkxt.test.reportsDirectory");
    if (reports != null) {
      var artifactDirectory = java.nio.file.Path.of(reports).resolve("fixtures");
      java.nio.file.Files.createDirectories(artifactDirectory);
      java.nio.file.Files.write(artifactDirectory.resolve("synthetic-120-teachers." + format), file.getBytes(),
          java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    assertThat(validation.validate("teachers", file).counts().error()).isZero();
    assertThat(commits.commit("teachers", null, file).inserted()).isEqualTo(120);
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?", Integer.class, org)).isEqualTo(120);
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=? and qualifications @> '[\"CHIEF\"]'::jsonb", Integer.class, org)).isEqualTo(60);
    assertThat(jdbc.queryForList("select qualification_expiry from teacher where org_id=?", Date.class, org))
        .hasSize(120).allMatch(date -> date.equals(Date.valueOf("2028-12-31")));
    for(int limit=1;limit<=4;limit++) assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=? and max_daily_sessions=?", Integer.class, org, limit)).isEqualTo(30);
  }

  @ParameterizedTest
  @CsvSource(value={"INVIGILATOR|2027-02-30|2", "UNKNOWN|2027-12-31|2", "CHIEF|2027-12-31|2",
      "INVIGILATOR,INVIGILATOR|2027-12-31|2", "INVIGILATOR|2027-12-31|0", "INVIGILATOR|2027-12-31|13",
      "INVIGILATOR|2027-12-31|1.5"}, delimiter='|')
  void invalidEligibilityIsRejectedWithFieldEvidenceBeforeAnyPersistence(String roles,String expiry,String limit) throws Exception {
    var file = workbook("xlsx", headers, List.of(List.of("INVALID", "错误教师", roles, expiry, limit)));
    var report = validation.validate("teachers", file);
    assertThat(report.counts().error()).isGreaterThan(0);
    assertThat(report.items()).filteredOn(item -> item.severity().equals("error"))
        .allMatch(item -> item.row()==2 && List.of("qualifications","qualificationExpiry","maxDailySessions").contains(item.field()));
    assertThatThrownBy(() -> commits.commit("teachers", null, file))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?", Integer.class, org)).isZero();
  }

  @Test void omittedOrBlankEligibilityPreservesExistingProfileAndDoesNotGrantNewChief() throws Exception {
    commits.commit("teachers", null, workbook("xlsx", headers, List.of(List.of("EXIST", "原教师", "INVIGILATOR,CHIEF", "2028-12-31", "2"))));
    commits.commit("teachers", null, workbook("xlsx", List.of("工号","姓名"), List.of(List.of("EXIST","更新名字"),List.of("NEW","新老师"))));
    commits.commit("teachers", null, workbook("xlsx", headers, List.of(List.of("EXIST","空白保留","","",""))));
    var old = jdbc.queryForMap("select qualifications::text roles,qualification_expiry,max_daily_sessions from teacher where org_id=? and teacher_no='EXIST'", org);
    assertThat(old.get("roles").toString()).contains("CHIEF");
    assertThat(old.get("qualification_expiry")).isEqualTo(Date.valueOf("2028-12-31"));
    assertThat(old.get("max_daily_sessions")).isEqualTo(2);
    assertThat(jdbc.queryForObject("select qualifications::text from teacher where org_id=? and teacher_no='NEW'", String.class, org)).isEqualTo("[\"INVIGILATOR\"]");
  }

  @Test void downloadedTemplateShowsAllEligibilityColumnsWithoutAutomaticallyGrantingChief() throws Exception {
    try(var book=WorkbookFactory.create(new java.io.ByteArrayInputStream(validation.template("teachers")))) {
      var row=book.getSheetAt(0).getRow(0);
      List<String> titles=new ArrayList<>();
      row.forEach(cell -> titles.add(cell.getStringCellValue()));
      assertThat(titles).contains("监考资格","资格有效期","每日监考上限");
      assertThat(book.getSheetAt(0).getRow(1).getCell(titles.indexOf("监考资格")).getStringCellValue()).isEqualTo("INVIGILATOR");
    }
  }

  private MockMultipartFile workbook(String format,List<String> columns,List<List<String>> rows) throws Exception {
    try(Workbook book=format.equals("xls")?new HSSFWorkbook():new XSSFWorkbook(); var output=new ByteArrayOutputStream()) {
      var sheet=book.createSheet("教师"); var header=sheet.createRow(0);
      for(int i=0;i<columns.size();i++) header.createCell(i).setCellValue(columns.get(i));
      for(int r=0;r<rows.size();r++) { var row=sheet.createRow(r+1); for(int c=0;c<rows.get(r).size();c++) row.createCell(c).setCellValue(rows.get(r).get(c)); }
      book.write(output);
      return new MockMultipartFile("file","teachers."+format,"application/octet-stream",output.toByteArray());
    }
  }
}
