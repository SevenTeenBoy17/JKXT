package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class DemoImportLimitsTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http; @Autowired ObjectMapper mapper; @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @AfterEach void retire(){DemoTestFixture.retire(jdbc);}
  @Test void downloadedTeacherTemplateWithQualificationCommentsIsAcceptedWithoutRepacking() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    var download=http.perform(f.apply(get("/api/v1/import/teachers/template"),b)).andReturn().getResponse();assertThat(download.getStatus()).isEqualTo(200);
    var file=new MockMultipartFile("file","teacher-template.xlsx","application/octet-stream",download.getContentAsByteArray());
    var preview=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(file),b)).andReturn().getResponse();assertThat(preview.getStatus()).isEqualTo(200);
    var report=mapper.readTree(preview.getContentAsByteArray());assertThat(report.path("counts").path("error").asInt()).as(report.toString()).isZero();
    var commit=http.perform(f.apply(multipart("/api/v1/import/teachers/commit").file(file),b)).andReturn().getResponse();assertThat(commit.getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select qualifications::text from teacher where org_id=? and teacher_no='T2026001'",String.class,b.org())).isEqualTo("[\"INVIGILATOR\"]");
    assertThat(jdbc.queryForObject("select qualification_expiry from teacher where org_id=? and teacher_no='T2026001'",java.sql.Date.class,b.org())).isEqualTo(java.sql.Date.valueOf("2026-12-31"));
  }
  @ParameterizedTest @ValueSource(strings={"sheets","row","column","formula","ruleFormula","tableFormula","namedFormula","external","embedded","binary","entries","xmlDepth","xmlText","vmlText","cellLength","dtd"})
  void hiddenUnmappedAndPackageHazardsRefuseTheFileBeforeAnyMutation(String hazard) throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    byte[] bytes=hazard(hazard);
    org.springframework.mock.web.MockHttpServletResponse response;
    try(var poi=org.mockito.Mockito.mockStatic(org.apache.poi.ss.usermodel.WorkbookFactory.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
      response=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(new MockMultipartFile("file","disguised.xlsx","application/octet-stream",bytes)),b)).andReturn().getResponse();
      poi.verifyNoInteractions();
    }
    assertThat(response.getStatus()).isEqualTo(200);
    var body=mapper.readTree(response.getContentAsByteArray());
    assertThat(body.path("counts").path("error").asInt()).as(hazard).isPositive();
    assertThat(body.path("items").toString()).contains("IMPORT_");
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
  }
  static byte[] hazard(String kind) throws Exception {
    byte[] base=DemoBusinessFlowIntegrationTest.teachers(1,true,0).getBytes();
    if(kind.equals("binary"))try(var wb=new org.apache.poi.hssf.usermodel.HSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
      var s=wb.createSheet();var h=s.createRow(0);h.createCell(0).setCellValue("工号");h.createCell(1).setCellValue("姓名");
      var r=s.createRow(1);r.createCell(0).setCellValue("BINARY");r.createCell(1).setCellValue("合成");wb.write(bytes);return bytes.toByteArray();
    }
    if(Set.of("sheets","row","column","formula").contains(kind))try(var wb=new XSSFWorkbook(new ByteArrayInputStream(base));var bytes=new ByteArrayOutputStream()) {
      var hidden=wb.createSheet("不可见");wb.setSheetHidden(1,true);
      if(kind.equals("sheets"))for(int i=2;i<11;i++)wb.createSheet("S"+i);
      if(kind.equals("row"))hidden.createRow(10000).createCell(0).setCellValue("X");
      if(kind.equals("column"))hidden.createRow(0).createCell(64).setCellValue("X");
      if(kind.equals("formula"))hidden.createRow(0).createCell(0).setCellFormula("1+1");
      wb.write(bytes);return bytes.toByteArray();
    }
    Map<String,String> additions=new LinkedHashMap<>();
    switch(kind) {
      case "ruleFormula" -> additions.put("xl/worksheets/extra.xml","<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><conditionalFormatting sqref=\"A1\"><cfRule type=\"expression\" priority=\"1\"><formula>1+1</formula></cfRule></conditionalFormatting></worksheet>");
      case "tableFormula" -> additions.put("xl/tables/extra.xml","<table xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><tableColumns><tableColumn id=\"1\" name=\"unused\"><calculatedColumnFormula>1+1</calculatedColumnFormula></tableColumn></tableColumns></table>");
      case "namedFormula" -> additions.put("unused/names.xml","<definedNames xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><definedName name=\"computed\">1+1</definedName></definedNames>");
      case "external" -> additions.put("xl/_rels/extra.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" Type=\"x\" Target=\"http://127.0.0.1:1/no-network\" TargetMode=\"External\"/></Relationships>");
      case "embedded" -> additions.put("xl/embeddings/object.bin","fake");
      case "entries" -> {for(int i=0;i<257;i++)additions.put("parts/"+i+".xml","<a/>");}
      case "xmlDepth" -> additions.put("parts/deep.xml","<a>".repeat(65)+"</a>".repeat(65));
      case "xmlText" -> additions.put("parts/text.xml","<a>"+"X".repeat(4000001)+"</a>");
      case "vmlText" -> additions.put("xl/drawings/unused.vml","<xml>"+"X".repeat(4000001)+"</xml>");
      case "cellLength" -> additions.put("xl/worksheets/hidden.xml","<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>"+"X".repeat(32768)+"</t></is></c></row></sheetData></worksheet>");
      case "dtd" -> additions.put("parts/unsafe.xml","<!DOCTYPE a [<!ENTITY x SYSTEM \"http://127.0.0.1:1/no-network\">]><a>&x;</a>");
      default -> throw new IllegalArgumentException(kind);
    }
    return extraParts(base,additions);
  }
  static byte[] extraParts(byte[] base,Map<String,String> extras) throws Exception {
    try(var output=new ByteArrayOutputStream();var zout=new ZipOutputStream(output);var zin=new ZipInputStream(new ByteArrayInputStream(base))) {
      ZipEntry entry;while((entry=zin.getNextEntry())!=null){zout.putNextEntry(new ZipEntry(entry.getName()));zin.transferTo(zout);zout.closeEntry();}
      for(var e:extras.entrySet()){zout.putNextEntry(new ZipEntry(e.getKey()));zout.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zout.closeEntry();}
      zout.finish();return output.toByteArray();
    }
  }
  @Test void onlyOneActualHttpParseIsAdmittedAndBusyDoesNotReadBytesOrRefundAttempts() throws Exception {
    var f=new DemoTestFixture(application);var first=f.enter();var other=f.enter();String otherBefore=f.fingerprint(other.visitor().id());
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    var file=DemoBusinessFlowIntegrationTest.blocking(DemoBusinessFlowIntegrationTest.roster("teachers",121,1),entered,release);
    try {
      var running=pool.submit(()->http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(file),first)).andReturn().getResponse());
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      var untouched=new MockMultipartFile("file","busy.xlsx","application/octet-stream",new byte[]{1}) {
        @Override public InputStream getInputStream(){throw new AssertionError("busy admission must happen before stream access");}
        @Override public byte[] getBytes(){throw new AssertionError("busy admission must happen before copying");}
      };
      var busy=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(untouched),other)).andReturn().getResponse();
      assertThat(busy.getStatus()).isEqualTo(503);assertThat(busy.getHeader("Retry-After")).isEqualTo("5");
      assertThat(mapper.readTree(busy.getContentAsByteArray()).path("code").asText()).isEqualTo("DEMO_IMPORT_BUSY");
      assertThat(f.fingerprint(other.visitor().id())).isEqualTo(otherBefore);
      assertThat(application.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).opsForZSet().zCard(DemoQuotaService.rateKey("upload",other.workspace().toString()))).isEqualTo(1);
      release.countDown();assertThat(running.get(5,TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      assertThat(validPreview(f,other).getStatus()).isEqualTo(200);
    } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
  }
  org.springframework.mock.web.MockHttpServletResponse validPreview(DemoTestFixture f,DemoTestFixture.Browser b) throws Exception {
    var response=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(DemoBusinessFlowIntegrationTest.roster("teachers",121,1)),b)).andReturn().getResponse();
    assertThat(mapper.readTree(response.getContentAsByteArray()).path("counts").path("error").asInt()).isZero();return response;
  }
  @Test void cancelledAndMalformedParsesReleaseTheProcessPermit() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
    var pool=Executors.newSingleThreadExecutor();
    try {
      var file=DemoBusinessFlowIntegrationTest.blocking(DemoBusinessFlowIntegrationTest.roster("teachers",121,1),entered,release);
      var running=pool.submit(()->{try{return http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(file),b)).andReturn();}finally{finished.countDown();}});
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(running.cancel(true)).isTrue();
      assertThat(finished.await(5,TimeUnit.SECONDS)).isTrue();assertThat(validPreview(f,b).getStatus()).isEqualTo(200);
      var malformed=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(new MockMultipartFile("file","bad.xlsx","application/octet-stream",new byte[]{'P','K',3,4,0})),b)).andReturn().getResponse();
      assertThat(malformed.getStatus()).isEqualTo(200);assertThat(mapper.readTree(malformed.getContentAsByteArray()).path("counts").path("error").asInt()).isPositive();
      assertThat(validPreview(f,b).getStatus()).isEqualTo(200);
    } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
  }
  @Test void structurallyDamagedWorkbookReturnsAnExplicitReportAndReleasesPermit() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    // Well-formed bounded XML is not necessarily a valid OPC workbook; exercise POI rejection too.
    var invalid=new MockMultipartFile("file","damaged.xlsx","application/octet-stream",zip(minimal("<worksheet/>")));
    var response=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(invalid),b)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    var report=mapper.readTree(response.getContentAsByteArray());
    assertThat(report.path("counts").path("error").asInt()).isPositive();
    assertThat(report.path("items").toString()).contains("IMPORT_FILE_UNREADABLE");
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);assertThat(validPreview(f,b).getStatus()).isEqualTo(200);
  }
  @ParameterizedTest @ValueSource(strings={"smallFormula","stringsNPlusOne"})
  void relationshipSelectedNonXmlPartIsRefusedBeforePoiAndPermitIsReusable(String content) throws Exception {
    var parts=readParts(DemoBusinessFlowIntegrationTest.teachers(1,true,0).getBytes());
    assertThat(parts.remove("xl/sharedStrings.xml")).isNotNull();
    parts.put("xl/strings.dat",content.equals("smallFormula")
        ?"<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>safe</t></si><definedName name=\"bad\">1+1</definedName></sst>"
        :"<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+"<si><t/></si>".repeat(200001)+"</sst>");
    parts.compute("xl/_rels/workbook.xml.rels",(key,value)->value.replace("sharedStrings.xml","strings.dat"));
    parts.compute("[Content_Types].xml",(key,value)->value.replace("/xl/sharedStrings.xml","/xl/strings.dat")
        .replace("application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml","application/octet-stream"));
    assertThat(parts.get("xl/_rels/workbook.xml.rels")).contains("/sharedStrings","strings.dat");
    assertThat(parts.get("[Content_Types].xml")).contains("/xl/strings.dat","application/octet-stream");
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    assertPrePoiRefusal(f,b,zip(parts),"IMPORT_PART_INVALID");
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
    assertThat(validPreview(f,b).getStatus()).isEqualTo(200);
  }
  @ParameterizedTest @ValueSource(strings={"preview","commit","quick"})
  void missingWorkbookSheetsHasStableMalformedResultAndNoWrites(String route) throws Exception {
    var parts=readParts(DemoBusinessFlowIntegrationTest.teachers(1,true,0).getBytes());
    String workbook=parts.get("xl/workbook.xml");
    parts.put("xl/workbook.xml",workbook.replaceAll("(?s)<sheets>.*?</sheets>",""));
    assertThat(parts.get("xl/workbook.xml")).isNotEqualTo(workbook);
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    UUID task=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org());
    String path=route.equals("quick")?"/api/v1/exam-tasks/"+task+"/uploads":"/api/v1/import/teachers/"+(route.equals("preview")?"validate":"commit");
    var response=http.perform(f.apply(multipart(path).file(new MockMultipartFile("file","missing-sheets.xlsx","application/octet-stream",zip(parts))).param("kind","teacher"),b)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(route.equals("preview")?200:400);
    var body=mapper.readTree(response.getContentAsByteArray());
    if(route.equals("preview"))assertThat(body.path("items")).anySatisfy(item->{
      assertThat(item.path("code").asText()).isEqualTo("IMPORT_FILE_UNREADABLE");assertThat(item.path("field").asText()).isEqualTo("file");
      assertThat(item.path("problem").asText()).contains("工作簿");assertThat(item.path("suggest").asText()).isNotBlank();
    });
    else {assertThat(body.path("code").asText()).isEqualTo("BAD_REQUEST");assertThat(body.path("message").asText()).contains("导入校验未通过");}
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);assertThat(validPreview(f,b).getStatus()).isEqualTo(200);
  }
  @ParameterizedTest @ValueSource(strings={"merge","filter","conditional","validation","table"})
  void declaredRangesHaveExactColumnAndRowEndpointsBeforePoi(String construct) throws Exception {
    for(String endpoint:List.of("BL1","A10000"))DemoImportLimits.inspectPackage(rangePackage(construct,endpoint));
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    for(String endpoint:List.of("BM1","A10001"))assertPrePoiRefusal(f,b,rangePackage(construct,endpoint),"IMPORT_COMPLEXITY_EXCEEDED");
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);assertThat(validPreview(f,b).getStatus()).isEqualTo(200);
  }
  @Test void unsupportedTextFormExtensionRangeIsExplicitlyRefused() throws Exception {
    var parts=readParts(DemoBusinessFlowIntegrationTest.teachers(1,true,0).getBytes());
    parts.compute("xl/worksheets/sheet1.xml",(key,value)->value.replace("</worksheet>",
        "<extLst><ext uri=\"synthetic-range\"><x:sqref xmlns:x=\"http://schemas.microsoft.com/office/excel/2006/main\">A1 BM1</x:sqref></ext></extLst></worksheet>"));
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    assertPrePoiRefusal(f,b,zip(parts),"IMPORT_PART_INVALID");
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);assertThat(validPreview(f,b).getStatus()).isEqualTo(200);
  }
  @Test void relationshipTargetsMustExistInTheInspectedSetAfterRelativeResolution() throws Exception {
    var parts=readParts(DemoBusinessFlowIntegrationTest.teachers(1,true,0).getBytes());
    parts.compute("xl/_rels/workbook.xml.rels",(key,value)->value.replace("sharedStrings.xml","%73haredStrings.xml"));
    DemoImportLimits.inspectPackage(zip(parts));
    parts.remove("xl/sharedStrings.xml");
    assertCode(()->DemoImportLimits.inspectPackage(zip(parts)),"IMPORT_PART_INVALID");
    // The linked table fixture uses ../tables/table1.xml, unlike root-relative workbook targets.
    DemoImportLimits.inspectPackage(rangePackage("table","BL10000"));
  }
  void assertPrePoiRefusal(DemoTestFixture f,DemoTestFixture.Browser b,byte[] bytes,String code) throws Exception {
    org.springframework.mock.web.MockHttpServletResponse response;
    // A call-through spy proves admission occurred before the real POI decoder, not a mocked failure.
    try(var poi=org.mockito.Mockito.mockStatic(org.apache.poi.ss.usermodel.WorkbookFactory.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
      response=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(new MockMultipartFile("file","bounded-hostile.xlsx","application/octet-stream",bytes)),b)).andReturn().getResponse();
      var commit=http.perform(f.apply(multipart("/api/v1/import/teachers/commit").file(new MockMultipartFile("file","bounded-hostile.xlsx","application/octet-stream",bytes)),b)).andReturn().getResponse();
      assertThat(commit.getStatus()).isEqualTo(400);
      assertThat(mapper.readTree(commit.getContentAsByteArray()).path("code").asText()).isEqualTo("BAD_REQUEST");
      poi.verifyNoInteractions();
    }
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(mapper.readTree(response.getContentAsByteArray()).path("items")).anySatisfy(item->{
      assertThat(item.path("code").asText()).isEqualTo(code);assertThat(item.path("field").asText()).isEqualTo("file");
      assertThat(item.path("severity").asText()).isEqualTo("error");assertThat(item.path("problem").asText()).isNotBlank();
      assertThat(item.path("suggest").asText()).isNotBlank();
    });
  }
  static LinkedHashMap<String,String> readParts(byte[] bytes) throws Exception {
    var parts=new LinkedHashMap<String,String>();
    try(var in=new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;while((entry=in.getNextEntry())!=null)parts.put(entry.getName(),new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    }
    return parts;
  }
  static byte[] rangePackage(String construct,String endpoint) throws Exception {
    var parts=readParts(DemoBusinessFlowIntegrationTest.teachers(1,true,0).getBytes());
    String reference="A1:"+endpoint;
    String declaration=switch(construct) {
      case "merge"->"<mergeCells count=\"1\"><mergeCell ref=\""+reference+"\"/></mergeCells>";
      case "filter"->"<autoFilter ref=\""+reference+"\"/>";
      case "conditional"->"<conditionalFormatting sqref=\"A1 "+reference+"\"><cfRule type=\"duplicateValues\" priority=\"1\"/></conditionalFormatting>";
      case "validation"->"<dataValidations count=\"1\"><dataValidation type=\"none\" sqref=\"A1 "+reference+"\"/></dataValidations>";
      case "table"->"<tableParts count=\"1\"><tablePart xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"rTable\"/></tableParts>";
      default->throw new IllegalArgumentException(construct);
    };
    parts.compute("xl/worksheets/sheet1.xml",(key,value)->value.replace("</worksheet>",declaration+"</worksheet>"));
    if(construct.equals("table")) {
      parts.put("xl/tables/table1.xml","<table xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" id=\"1\" name=\"SyntheticTable\" displayName=\"SyntheticTable\" ref=\""+reference+"\"><tableColumns count=\"1\"><tableColumn id=\"1\" name=\"工号\"/></tableColumns></table>");
      parts.put("xl/worksheets/_rels/sheet1.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rTable\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table1.xml\"/></Relationships>");
      parts.compute("[Content_Types].xml",(key,value)->value.replace("</Types>","<Override PartName=\"/xl/tables/table1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/></Types>"));
    }
    return zip(parts);
  }
  @Test void inputBytesAreBoundedByActualStreamAndDeclaredOversizeIsNeverCopied() throws Exception {
    var declared=new MockMultipartFile("file","large.xlsx","application/octet-stream",new byte[]{1}) {
      @Override public long getSize(){return 10L*1024*1024+1;}
      @Override public InputStream getInputStream(){throw new AssertionError("must reject declared oversize before stream");}
      @Override public byte[] getBytes(){throw new AssertionError("must not call unbounded getBytes");}
    };
    assertCode(()->DemoImportLimits.freeze(declared,true),"IMPORT_FILE_TOO_LARGE");
    for(int size:List.of(10*1024*1024,10*1024*1024+1)) {
      var misleading=new MockMultipartFile("file","stream.xlsx","application/octet-stream",new byte[0]) {
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(new byte[size]);}
      };
      if(size==10*1024*1024)assertThat(DemoImportLimits.freeze(misleading,true)).hasSize(size);
      else assertCode(()->DemoImportLimits.freeze(misleading,true),"IMPORT_FILE_TOO_LARGE");
    }
  }
  private static final String XML="<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>";
  private static final int BASE_XML_CHARS="http://schemas.openxmlformats.org/package/2006/content-types".length()
      +"/xl/workbook.xml".length()+"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml".length();
  static LinkedHashMap<String,String> minimal(String sheet) {
    var parts=new LinkedHashMap<String,String>();parts.put("[Content_Types].xml",XML);parts.put("xl/workbook.xml","<workbook/>");parts.put("xl/worksheets/sheet1.xml",sheet);return parts;
  }
  static byte[] zip(Map<String,String> parts) throws Exception {
    try(var bytes=new ByteArrayOutputStream();var out=new ZipOutputStream(bytes)) {
      for(var part:parts.entrySet()){out.putNextEntry(new ZipEntry(part.getKey()));out.write(part.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();}
      out.finish();return bytes.toByteArray();
    }
  }
  static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(com.jkxt.common.web.ApiException.class,e->assertThat(e.getCode()).isEqualTo(code));
  }
  @Test void streamPreflightChecksExactEntryAndDepthBudgetsWithoutPoiConstruction() throws Exception {
    var parts=minimal("<worksheet/>");for(int i=3;i<256;i++)parts.put("unused/"+i+".dat","");
    DemoImportLimits.inspectPackage(zip(parts));parts.put("unused/256.dat","");assertCode(()->DemoImportLimits.inspectPackage(zip(parts)),"IMPORT_COMPLEXITY_EXCEEDED");
    for(int depth:List.of(64,65)) {
      var nested=minimal("<worksheet/>");nested.put("unused/depth.xml","<a>".repeat(depth)+"</a>".repeat(depth));
      if(depth==64)DemoImportLimits.inspectPackage(zip(nested));else assertCode(()->DemoImportLimits.inspectPackage(zip(nested)),"IMPORT_COMPLEXITY_EXCEEDED");
    }
  }
  @Test void streamPreflightCountsUnusedStringsCellsTextAndElementsAtNAndNPlusOne() throws Exception {
    for(int count:List.of(200000,200001)) {
      var strings=minimal("<worksheet/>");strings.put("xl/sharedStrings.xml","<sst>"+"<si><t/></si>".repeat(count)+"</sst>");
      if(count==200000)DemoImportLimits.inspectPackage(zip(strings));else assertCode(()->DemoImportLimits.inspectPackage(zip(strings)),"IMPORT_COMPLEXITY_EXCEEDED");
      var xml=new StringBuilder("<worksheet><sheetData>");
      for(int r=1;r<=10000;r++){xml.append("<row r=\"").append(r).append("\">");for(int c=0;c<(r==10000 && count==200001?21:20);c++)xml.append("<c r=\"").append((char)('A'+c)).append(r).append("\"><v>0</v></c>");xml.append("</row>");}
      xml.append("</sheetData></worksheet>");var cells=minimal(xml.toString());
      if(count==200000)DemoImportLimits.inspectPackage(zip(cells));else assertCode(()->DemoImportLimits.inspectPackage(zip(cells)),"IMPORT_COMPLEXITY_EXCEEDED");
    }
    for(int n:List.of(0,1)) {
      var text=minimal("<worksheet/>");text.put("unused/text.xml","<a>"+"x".repeat(4000000-BASE_XML_CHARS+n)+"</a>");
      if(n==0)DemoImportLimits.inspectPackage(zip(text));else assertCode(()->DemoImportLimits.inspectPackage(zip(text)),"IMPORT_COMPLEXITY_EXCEEDED");
      // Minimal package has four elements; this extra root and children reach exactly one million.
      var elements=minimal("<worksheet/>");elements.put("unused/elements.xml","<a>"+"<b/>".repeat(999995+n)+"</a>");
      if(n==0)DemoImportLimits.inspectPackage(zip(elements));else assertCode(()->DemoImportLimits.inspectPackage(zip(elements)),"IMPORT_COMPLEXITY_EXCEEDED");
    }
  }
  @Test void decodedAttributesAndNamespaceUrisShareTheExactTextBudget() throws Exception {
    for(int over:List.of(0,1)) {
      var attrs=minimal("<worksheet/>");attrs.put("unused/attributes.xml","<a value=\""+"x".repeat(4000000-BASE_XML_CHARS+over)+"\"/>");
      if(over==0)DemoImportLimits.inspectPackage(zip(attrs));else assertCode(()->DemoImportLimits.inspectPackage(zip(attrs)),"IMPORT_COMPLEXITY_EXCEEDED");
      String namespace="urn:synthetic:test";
      var mixed=minimal("<worksheet/>");mixed.put("unused/mixed.xml","<a xmlns:p=\""+namespace+"\" value=\""+"&amp;".repeat(1000000)+"\">"
          +"x".repeat(4000000-BASE_XML_CHARS-namespace.length()-1000000+over)+"</a>");
      if(over==0)DemoImportLimits.inspectPackage(zip(mixed));else assertCode(()->DemoImportLimits.inspectPackage(zip(mixed)),"IMPORT_COMPLEXITY_EXCEEDED");
    }
  }
  @Test void worksheetAndCellBudgetsIncludeHiddenSparseDeclaredAndImplicitCoordinates() throws Exception {
    for(int sheets:List.of(10,11)) {
      var parts=minimal("<worksheet/>");for(int i=1;i<sheets;i++)parts.put("xl/worksheets/s"+i+".xml","<worksheet/>");
      if(sheets==10)DemoImportLimits.inspectPackage(zip(parts));else assertCode(()->DemoImportLimits.inspectPackage(zip(parts)),"IMPORT_COMPLEXITY_EXCEEDED");
    }
    for(String allowed:List.of("<dimension ref=\"A1:BL10000\"/>","<cols><col min=\"1\" max=\"64\"/></cols>","<sheetData><row r=\"10000\"><c r=\"BL10000\"/></row></sheetData>","<sheetData><row>"+"<c/>".repeat(64)+"</row></sheetData>"))
      DemoImportLimits.inspectPackage(zip(minimal("<worksheet>"+allowed+"</worksheet>")));
    for(String denied:List.of("<dimension ref=\"A1:BM1\"/>","<cols><col min=\"1\" max=\"65\"/></cols>","<sheetData><row r=\"10001\"/></sheetData>","<sheetData><row>"+"<c/>".repeat(65)+"</row></sheetData>","<sheetData><row><c r=\"BL1\"/><c/></row></sheetData>"))
      assertCode(()->DemoImportLimits.inspectPackage(zip(minimal("<worksheet>"+denied+"</worksheet>"))),"IMPORT_COMPLEXITY_EXCEEDED");
    for(int chars:List.of(32767,32768)) {
      var parts=minimal("<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>"+"x".repeat(chars)+"</t></is></c></row></sheetData></worksheet>");
      if(chars==32767)DemoImportLimits.inspectPackage(zip(parts));else assertCode(()->DemoImportLimits.inspectPackage(zip(parts)),"IMPORT_COMPLEXITY_EXCEEDED");
    }
  }
  @Test void expandedBudgetCountsActualCumulativeBytesAndConflictingPartsAreRefused() throws Exception {
    for(int extra:List.of(0,1)) {
      var parts=minimal("<worksheet/>");long used=parts.values().stream().mapToLong(s->s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
      byte[] packageBytes;
      try(var bytes=new ByteArrayOutputStream();var out=new ZipOutputStream(bytes)) {
        for(var p:parts.entrySet()){out.putNextEntry(new ZipEntry(p.getKey()));out.write(p.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();}
        long remaining=64L*1024*1024+extra-used;
        // Two separately expanded parts prove this is a cumulative budget, not per-entry metadata.
        for(int p=0;p<2;p++){long n=p==0?remaining/2:remaining-remaining/2;out.putNextEntry(new ZipEntry("unused/p"+p+".dat"));byte[] zeros=new byte[8192];while(n>0){int count=(int)Math.min(n,zeros.length);out.write(zeros,0,count);n-=count;}out.closeEntry();}
        out.finish();packageBytes=bytes.toByteArray();
      }
      if(extra==0)DemoImportLimits.inspectPackage(packageBytes);else assertCode(()->DemoImportLimits.inspectPackage(packageBytes),"IMPORT_EXPANDED_TOO_LARGE");
    }
    for(String conflict:List.of("xl/%77orkbook.xml","XL/WORKBOOK.XML")) {
      var parts=minimal("<worksheet/>");parts.put(conflict,"<workbook/>");assertCode(()->DemoImportLimits.inspectPackage(zip(parts)),"IMPORT_PART_DUPLICATE");
    }
  }
  @Test void implicitRowPositionsAndActualCrossSheetCountsHaveIndependentBounds() throws Exception {
    var sparse=minimal("<worksheet><sheetData><row r=\"10000\"/></sheetData></worksheet>");
    sparse.put("xl/worksheets/second.xml","<worksheet><dimension ref=\"A1:A10000\"/><sheetData><row r=\"10000\"/></sheetData></worksheet>");
    DemoImportLimits.inspectPackage(zip(sparse));
    for(int total:List.of(10000,10001)) {
      var actual=minimal("<worksheet><sheetData>"+"<row/>".repeat(5000)+"</sheetData></worksheet>");
      actual.put("xl/worksheets/second.xml","<worksheet><sheetData>"+"<row/>".repeat(total-5000)+"</sheetData></worksheet>");
      if(total==10000)DemoImportLimits.inspectPackage(zip(actual));else assertCode(()->DemoImportLimits.inspectPackage(zip(actual)),"IMPORT_COMPLEXITY_EXCEEDED");
    }
    DemoImportLimits.inspectPackage(zip(minimal("<worksheet><sheetData><row r=\"9999\"/><row/></sheetData></worksheet>")));
    assertCode(()->DemoImportLimits.inspectPackage(zip(minimal("<worksheet><sheetData><row r=\"10000\"/><row/></sheetData></worksheet>"))),"IMPORT_COMPLEXITY_EXCEEDED");
  }
}
