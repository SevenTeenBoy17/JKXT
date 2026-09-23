package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.importer.service.ImportCommitService;
import com.jkxt.masterdata.service.MasterDataService;
import com.jkxt.tasks.service.ExamTaskService;
import java.io.*;
import java.util.*;
import java.time.*;
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
class DemoBusinessFlowIntegrationTest extends DemoTestFixture.IntegrationBase {
  @Autowired MockMvc http; @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired MasterDataService master; @Autowired ExamTaskService tasks; @Autowired ImportCommitService imports;
  @Autowired DemoWorkspaceRepository repository;
  @Autowired DemoTestFixture.MutableClock clock;
  @AfterEach void retire(){TenantContext.clear();DemoTestFixture.retire(jdbc);}
  static MockMultipartFile teachers(int count,boolean qualifications,int invalidRow) throws Exception {
    try(var wb=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
      var s=wb.createSheet("教师");var h=s.createRow(0);
      String[] cols=qualifications?new String[]{"工号","姓名","监考资格","资格有效期"}:new String[]{"工号","姓名"};
      for(int i=0;i<cols.length;i++)h.createCell(i).setCellValue(cols[i]);
      for(int i=1;i<=count;i++) {var r=s.createRow(i);r.createCell(0).setCellValue("SYN-"+i);r.createCell(1).setCellValue("合成"+i);
        if(qualifications){r.createCell(2).setCellValue(i==invalidRow?"ADMIN":"INVIGILATOR");r.createCell(3).setCellValue("");}}
      wb.write(bytes);return new MockMultipartFile("file","teachers.xlsx","application/octet-stream",bytes.toByteArray());
    }
  }
  @ParameterizedTest @ValueSource(ints={120,150})
  void invalidFinalRowRejectsEntireQuickBatchIncludingAllMetadata(int rows) throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    UUID task=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org());
    String before=f.fingerprint(b.visitor().id());
    var result=http.perform(f.apply(multipart("/api/v1/exam-tasks/"+task+"/uploads").file(teachers(rows,true,rows)).param("kind","teacher"),b)).andReturn().getResponse();
    assertThat(result.getStatus()).isEqualTo(400);
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
  }
  static MockMultipartFile workbook(String[] columns,List<String[]> data) throws Exception {
    try(var wb=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
      var sheet=wb.createSheet("合成资料");var header=sheet.createRow(0);
      for(int c=0;c<columns.length;c++)header.createCell(c).setCellValue(columns[c]);
      for(int r=0;r<data.size();r++){var row=sheet.createRow(r+1);for(int c=0;c<data.get(r).length;c++)row.createCell(c).setCellValue(data.get(r)[c]);}
      wb.write(bytes);return new MockMultipartFile("file","synthetic.xlsx","application/octet-stream",bytes.toByteArray());
    }
  }
  static MockMultipartFile roster(String kind,int first,int count) throws Exception {
    String[] columns=switch(kind) {
      case "teachers"->new String[]{"工号","姓名","监考资格","资格有效期"};
      case "classrooms"->new String[]{"考场编号","考场名称","容量","考试容量"};
      case "classes"->new String[]{"班级编号","班级名称","年级","学生数"};
      case "subjects"->new String[]{"科目编号","科目名称","类别","考试类型"};
      default->throw new IllegalArgumentException(kind);
    };
    List<String[]> rows=new ArrayList<>();
    for(int i=first;i<first+count;i++)rows.add(switch(kind) {
      case "teachers"->new String[]{String.format("T%03d",i),"合成教师"+i,"INVIGILATOR",""};
      case "classrooms"->new String[]{"TEST-"+i,"合成考场"+i,"40","30"};
      case "classes"->new String[]{"TEST-"+i,"合成班级"+i,"高一","30"};
      default->new String[]{"TEST-"+i,"合成科目"+i,"main","written"};
    });
    return workbook(columns,rows);
  }
  org.springframework.mock.web.MockHttpServletResponse commit(DemoTestFixture f,DemoTestFixture.Browser b,String kind,MockMultipartFile file) throws Exception {
    return http.perform(f.apply(multipart("/api/v1/import/"+kind+"/commit").file(file),b)).andReturn().getResponse();
  }
  @ParameterizedTest @ValueSource(ints={120,150})
  void canonicalWorkbooksRemainFullyImportableAndDeduplicatedAtCapacity(int count) throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var file=roster("teachers",1,count);
    var response=commit(f,b,"teachers",file);assertThat(response.getStatus()).isEqualTo(200);
    var body=mapper.readTree(response.getContentAsByteArray());assertThat(body.path("inserted").asInt()).isEqualTo(count-120);
    assertThat(body.path("updated").asInt()).isEqualTo(120);assertThat(body.path("skipped").asInt()).isZero();
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,b.org())).isEqualTo(count);
    assertThat(commit(f,b,"teachers",file).getStatus()).isEqualTo(200);
    var read=http.perform(f.apply(get("/api/v1/teachers"),b)).andReturn().getResponse();
    var teachers=mapper.readTree(read.getContentAsByteArray());assertThat(teachers.size()).isEqualTo(count);
    var first=teachers.get(0);assertThat(UUID.fromString(first.path("uuid").asText())).isNotNull();
    assertThat(first.path("id").asText()).isEqualTo("T001");assertThat(first.path("teacherNo").asText()).isEqualTo("T001");
    assertThat(first.path("uuid").asText()).isNotEqualTo(first.path("id").asText());
  }
  @Test void qualificationMeaningComesFromActualXlsxAndIsReadBackByUuid() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    LocalDate day=jdbc.queryForObject("select min(exam_date) from exam where org_id=?",java.sql.Date.class,b.org()).toLocalDate();
    String[] quals={"INVIGILATOR","监考员，主监考","CHIEF","","INVIGILATOR","INVIGILATOR"};
    String[] expiry={"",day.toString(),day.minusDays(1).toString(),"",day.minusDays(1).toString(),day.toString()};
    List<String[]> rows=new ArrayList<>();for(int i=0;i<6;i++)rows.add(new String[]{String.format("T%03d",i+1),"资格合成"+i,quals[i],expiry[i]});
    rows.add(new String[]{"T121","新建明确无资格","",""});
    assertThat(commit(f,b,"teachers",workbook(new String[]{"工号","姓名","监考资质","资格有效期至"},rows)).getStatus()).isEqualTo(200);
    for(int i=0;i<6;i++) {
      UUID id=jdbc.queryForObject("select id from teacher where org_id=? and teacher_no=?",UUID.class,b.org(),String.format("T%03d",i+1));
      var response=http.perform(f.apply(get("/api/v1/teachers/"+id),b)).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(200);
      var teacher=mapper.readTree(response.getContentAsByteArray());assertThat(teacher.path("uuid").asText()).isEqualTo(id.toString());
      assertThat(teacher.path("qualifications").toString()).isEqualTo(i==1?"[\"INVIGILATOR\",\"CHIEF\"]":i==2?"[\"CHIEF\"]":i==3?"[]":"[\"INVIGILATOR\"]");
      if(expiry[i].isBlank())assertThat(teacher.path("qualificationExpiry").isNull()).isTrue();else assertThat(teacher.path("qualificationExpiry").asText()).isEqualTo(expiry[i]);
    }
    UUID blankId=jdbc.queryForObject("select id from teacher where org_id=? and teacher_no='T121'",UUID.class,b.org());
    var blankResponse=http.perform(f.apply(get("/api/v1/teachers/"+blankId),b)).andReturn().getResponse();assertThat(blankResponse.getStatus()).isEqualTo(200);
    var blank=mapper.readTree(blankResponse.getContentAsByteArray());assertThat(blank.path("qualifications").toString()).isEqualTo("[]");assertThat(blank.path("qualificationExpiry").isNull()).isTrue();
    // Explicit blanks clear an existing dual credential; there is no REST correction after import.
    assertThat(commit(f,b,"teachers",workbook(new String[]{"teacherNo","name","qualifications","qualificationExpiry"},List.<String[]>of(new String[]{"T002","清空","",""}))).getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select qualifications::text from teacher where org_id=? and teacher_no='T002'",String.class,b.org())).isEqualTo("[]");
    assertThat(jdbc.queryForObject("select qualification_expiry from teacher where org_id=? and teacher_no='T002'",java.sql.Date.class,b.org())).isNull();
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"teachers,150,120,teacher","classrooms,40,12,classroom","classes,200,12,school_class","subjects,50,9,subject"})
  void cumulativeBatchesAndAllFourCrudCollectionsShareActualCapacity(String kind,int maximum,int seeded,String table) throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();int first=kind.equals("teachers")?121:1;
    int gap=maximum-seeded,half=gap/2;
    assertThat(commit(f,b,kind,roster(kind,first,half)).getStatus()).isEqualTo(200);
    assertThat(commit(f,b,kind,roster(kind,first+half,gap-half)).getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select count(*) from "+table+" where org_id=?",Integer.class,b.org())).isEqualTo(maximum);
    // An existing natural key is an update, including when the workspace has no free row slots.
    assertThat(commit(f,b,kind,roster(kind,first,1)).getStatus()).isEqualTo(200);
    String before=f.fingerprint(b.visitor().id());
    var denied=commit(f,b,kind,roster(kind,999,1));assertThat(denied.getStatus()).isEqualTo(429);
    assertThat(mapper.readTree(denied.getContentAsByteArray()).path("code").asText()).isEqualTo("DEMO_CAPACITY_EXCEEDED");
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
    Map<String,Object> body=new LinkedHashMap<>();body.put(kind.equals("teachers")?"teacherNo":"code","DIRECT-NEW");body.put("name","合成新增");body.put("grade","高一");
    var create=http.perform(f.apply(post("/api/v1/"+kind),b).contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse();
    assertThat(create.getStatus()).isEqualTo(429);assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
    String key=kind.equals("teachers")?String.format("T%03d",first):"TEST-"+first;
    UUID id=jdbc.queryForObject("select id from "+table+" where org_id=? and "+(kind.equals("teachers")?"teacher_no":"code")+"=?",UUID.class,b.org(),key);
    body.put(kind.equals("teachers")?"teacherNo":"code",key);body.put("name","合成更新");
    assertThat(http.perform(f.apply(put("/api/v1/"+kind+"/"+id),b).contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus()).isEqualTo(200);
    assertThat(http.perform(f.apply(delete("/api/v1/"+kind+"/"+id),b)).andReturn().getResponse().getStatus()).isEqualTo(204);
    body.put(kind.equals("teachers")?"teacherNo":"code","DIRECT-NEW");
    assertThat(http.perform(f.apply(post("/api/v1/"+kind),b).contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select count(*) from "+table+" where org_id=?",Integer.class,b.org())).isEqualTo(maximum);
  }
  @Test void taskCapacityAndSynchronousValidationDeletionRemainOwned() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var other=f.enter();String otherBefore=f.fingerprint(other.visitor().id());
    UUID last=null;for(int i=0;i<9;i++) {
      var response=http.perform(f.apply(post("/api/v1/exam-tasks"),b).contentType("application/json").content("{\"name\":\"合成任务\"}")).andReturn().getResponse();
      assertThat(response.getStatus()).isEqualTo(200);last=UUID.fromString(mapper.readTree(response.getContentAsByteArray()).path("id").asText());
    }
    assertThat(http.perform(f.apply(post("/api/v1/exam-tasks"),b).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isEqualTo(429);
    assertThat(http.perform(f.apply(post("/api/v1/exam-tasks/"+last+"/imports/validate"),b)).andReturn().getResponse().getStatus()).isEqualTo(200);
    assertThat(http.perform(f.apply(delete("/api/v1/exam-tasks/"+last),b)).andReturn().getResponse().getStatus()).isEqualTo(204);
    assertThat(jdbc.queryForObject("select count(*) from exam_task where org_id=?",Integer.class,b.org())).isEqualTo(9);
    UUID foreign=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,other.org());
    assertThat(http.perform(f.apply(delete("/api/v1/exam-tasks/"+foreign),b)).andReturn().getResponse().getStatus()).isEqualTo(403);
    assertThat(f.fingerprint(other.visitor().id())).isEqualTo(otherBefore);
  }
  @Test void mutableMultipartIsReadExactlyOnceAndStoresThatMeaningAndDigest() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID task=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org());
    byte[] accepted=roster("teachers",121,1).getBytes(),changed=teachers(1,true,1).getBytes();var reads=new AtomicInteger();
    var file=new MockMultipartFile("file","changing.xlsx","application/octet-stream",accepted) {
      @Override public InputStream getInputStream(){return new ByteArrayInputStream(reads.getAndIncrement()==0?accepted:changed);}
      @Override public byte[] getBytes(){throw new AssertionError("DEMO must use its bounded stream before any getBytes");}
    };
    var response=http.perform(f.apply(multipart("/api/v1/exam-tasks/"+task+"/uploads").file(file).param("kind","teacher"),b)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);assertThat(reads.get()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=? and teacher_no='T121'",Integer.class,b.org())).isEqualTo(1);
    assertThat(jdbc.queryForObject("select sha256 from uploaded_file where org_id=? and exam_task_id=?",String.class,b.org(),task))
        .isEqualTo(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(accepted)));
  }
  @Test void parseThenResetRejectsCapturedGenerationAndReleasesPermitWithoutPartialMutation() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var other=f.enter();String otherBefore=f.fingerprint(other.visitor().id());
    var before=orgRows(b.org());UUID task=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org());
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    var file=blocking(roster("teachers",121,1),entered,release);
    try {
      var result=pool.submit(()->http.perform(f.apply(multipart("/api/v1/exam-tasks/"+task+"/uploads").file(file).param("kind","teacher"),b)).andReturn().getResponse());
      assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      f.reset(b.workspace(),b.generation());release.countDown();
      var response=result.get(5,TimeUnit.SECONDS);assertThat(response.getStatus()).isEqualTo(409);
      assertThat(mapper.readTree(response.getContentAsByteArray()).path("code").asText()).isEqualTo("DEMO_STALE_GENERATION");
      assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,b.org())).isEqualTo(120);
      assertThat(orgRows(b.org())).isEqualTo(before);
      for(String table:List.of("uploaded_file","import_batch","notification"))assertThat(jdbc.queryForObject("select count(*) from "+table+" where org_id=?",Integer.class,b.org())).isZero();
      assertThat(f.fingerprint(other.visitor().id())).isEqualTo(otherBefore);
      assertThat(commit(f,other,"teachers",roster("teachers",121,1)).getStatus()).isEqualTo(200);
    } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
  }
  SortedSet<String> orgRows(UUID org) {
    var rows=new TreeSet<String>();
    for(String table:jdbc.queryForList("select table_name from information_schema.columns where table_schema='public' and column_name='org_id'",String.class)) {
      if(!table.matches("[a-z_]+") || table.startsWith("demo_"))continue;
      jdbc.query("select to_jsonb(t)::text from \""+table+"\" t where org_id=?",rs->{while(rs.next())rows.add(table+":"+rs.getString(1));return null;},org);
    }
    return rows;
  }
  static MockMultipartFile blocking(MockMultipartFile original,CountDownLatch entered,CountDownLatch release) throws Exception {
    return new MockMultipartFile("file",original.getOriginalFilename(),original.getContentType(),original.getBytes()) {
      @Override public InputStream getInputStream() throws IOException {
        entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test parse release not observed");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        return super.getInputStream();
      }
    };
  }
  @Test void lastTeacherSlotIsSerializedAcrossTwoRealRequests() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();
    assertThat(commit(f,b,"teachers",roster("teachers",121,29)).getStatus()).isEqualTo(200);
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(3);
    try {
      var blocker=pool.submit(()->repository.transaction(()->{repository.workspace(b.workspace(),true);locked.countDown();
        try{assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
      assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
      List<Future<org.springframework.mock.web.MockHttpServletResponse>> calls=new ArrayList<>();
      for(int i=0;i<2;i++){final int index=i;calls.add(pool.submit(()->http.perform(f.apply(post("/api/v1/teachers"),b).contentType("application/json")
          .content("{\"teacherNo\":\"LAST-"+index+"\",\"name\":\"合成最后名额\"}")).andReturn().getResponse()));}
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(jdbc.queryForObject(
          "select count(*) from pg_stat_activity where pid<>pg_backend_pid() and datname=current_database() and usename=current_user and wait_event_type='Lock' and query like '%demo_workspace%for update%'",Integer.class)).isGreaterThanOrEqualTo(2));
      release.countDown();blocker.get(5,TimeUnit.SECONDS);List<Integer> statuses=new ArrayList<>();
      for(var future:calls){var response=future.get(5,TimeUnit.SECONDS);statuses.add(response.getStatus());if(response.getStatus()==429)assertThat(mapper.readTree(response.getContentAsByteArray()).path("code").asText()).isEqualTo("DEMO_CAPACITY_EXCEEDED");}
      assertThat(statuses).containsExactlyInAnyOrder(200,429);
      assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?",Integer.class,b.org())).isEqualTo(150);
      assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=? and teacher_no like 'LAST-%'",Integer.class,b.org())).isEqualTo(1);
    } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
  }
  static MockMultipartFile exams(int count,LocalDate start) throws Exception {
    List<String[]> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(new String[]{"数学","高一",start.plusDays(i).toString(),"09:00","10:00","R1","30","2","C1"});
    return workbook(new String[]{"科目","年级","考试日期","开始时间","结束时间","考场编号","人数","监考人数","班级"},rows);
  }
  @Test void examReplacementReservesFinalWorkspaceTotalAndPreservesOtherTaskRows() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();UUID original=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org());
    // Synthetic persisted output is an isolation sentinel, not a claim that the solver ran here.
    UUID sentinel=UUID.randomUUID();jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status,solver,stats) values(?,?,?,'succeeded','synthetic-sentinel','{\"sentinel\":true}')",sentinel,b.org(),original);
    jdbc.update("insert into schedule_assignment(org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time,source) "
        +"select e.org_id,?,e.id,er.classroom_id,(select id from teacher where org_id=e.org_id order by teacher_no limit 1),'main',e.exam_date,e.start_time,e.end_time,'manual' "
        +"from exam e join exam_room er on er.exam_id=e.id where e.org_id=? and e.exam_task_id=? order by e.id limit 1",sentinel,b.org(),original);
    String originalRun=jdbc.queryForObject("select to_jsonb(r)::text from schedule_run r where id=?",String.class,sentinel);
    var originalAssignments=jdbc.queryForList("select to_jsonb(a)::text from schedule_assignment a where schedule_run_id=? order by id",String.class,sentinel);assertThat(originalAssignments).hasSize(1);
    var created=http.perform(f.apply(post("/api/v1/exam-tasks"),b).contentType("application/json").content("{\"name\":\"替换合成任务\"}")).andReturn().getResponse();
    assertThat(created.getStatus()).isEqualTo(200);UUID task=UUID.fromString(mapper.readTree(created.getContentAsByteArray()).path("id").asText());
    List<UUID> originalExams=jdbc.queryForList("select id from exam where org_id=? and exam_task_id=? order by id",UUID.class,b.org(),original);
    for(int count:List.of(94,94,93,94)) {
      var response=http.perform(f.apply(multipart("/api/v1/import/exams/commit").file(exams(count,LocalDate.of(2027,1,1))).param("taskId",task.toString()),b)).andReturn().getResponse();
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
      assertThat(jdbc.queryForObject("select count(*) from exam where org_id=?",Integer.class,b.org())).isEqualTo(count+6);
      assertThat(jdbc.queryForList("select id from exam where org_id=? and exam_task_id=? order by id",UUID.class,b.org(),original)).isEqualTo(originalExams);
      assertThat(jdbc.queryForObject("select to_jsonb(r)::text from schedule_run r where id=?",String.class,sentinel)).isEqualTo(originalRun);
      assertThat(jdbc.queryForList("select to_jsonb(a)::text from schedule_assignment a where schedule_run_id=? order by id",String.class,sentinel)).isEqualTo(originalAssignments);
    }
    String before=f.fingerprint(b.visitor().id());
    var denied=http.perform(f.apply(multipart("/api/v1/import/exams/commit").file(exams(95,LocalDate.of(2027,1,1))).param("taskId",task.toString()),b)).andReturn().getResponse();
    assertThat(denied.getStatus()).isEqualTo(429);assertThat(mapper.readTree(denied.getContentAsByteArray()).path("code").asText()).isEqualTo("DEMO_CAPACITY_EXCEEDED");
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
  }
  @ParameterizedTest @ValueSource(strings={"duplicate","email","unknownSubject"})
  void dataDroppingRowsRefuseTheWholeBatchBeforeTeacherLinksOrNotices(String invalid) throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    List<String[]> rows=new ArrayList<>();
    rows.add(new String[]{"T121","合成甲","same@example.invalid","数学","INVIGILATOR",""});
    rows.add(new String[]{invalid.equals("duplicate")?"T121":"T122","合成乙",invalid.equals("email")?"same@example.invalid":"other@example.invalid",invalid.equals("unknownSubject")?"不存在合成科目":"数学","INVIGILATOR",""});
    var file=workbook(new String[]{"工号","姓名","邮箱","任教学科","监考资格","资格有效期"},rows);
    var preview=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(file),b)).andReturn().getResponse();
    assertThat(preview.getStatus()).isEqualTo(200);
    String[] expected=switch(invalid) {
      case "duplicate"->new String[]{"IMPORT_ROW_DUPLICATE","teacherNo","工号","合并"};
      case "email"->new String[]{"IMPORT_EMAIL_CONFLICT","email","邮箱","唯一邮箱"};
      default->new String[]{"IMPORT_SUBJECT_UNKNOWN","subjects","科目","先维护科目"};
    };
    assertPreviewIssue(mapper.readTree(preview.getContentAsByteArray()),3,expected[0],expected[1],expected[2],expected[3]);
    var response=commit(f,b,"teachers",file);
    assertThat(response.getStatus()).isEqualTo(400);assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
  }
  @Test void expiryDuringParsePreventsBusinessWritesAndReleasesPermit() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());Instant original=clock.instant();
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
    try {
      var file=blocking(roster("teachers",121,1),entered,release);
      var result=pool.submit(()->commit(f,b,"teachers",file));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
      clock.advance(Duration.ofMinutes(31));release.countDown();var response=result.get(5,TimeUnit.SECONDS);
      assertThat(response.getStatus()).isEqualTo(401);assertThat(mapper.readTree(response.getContentAsByteArray()).path("code").asText()).isEqualTo("DEMO_EXPIRED");
      assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
    } finally {clock.set(original);release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    assertThat(commit(f,b,"teachers",roster("teachers",121,1)).getStatus()).isEqualTo(200);
  }
  @Test void missingDemoQualificationHeadersRefusesPreviewAndCommit() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();var file=teachers(1,false,0);String before=f.fingerprint(b.visitor().id());
    var preview=http.perform(f.apply(multipart("/api/v1/import/teachers/validate").file(file),b)).andReturn().getResponse();
    assertThat(preview.getStatus()).isEqualTo(200);
    var report=mapper.readTree(preview.getContentAsByteArray());
    assertThat(report.path("counts").path("error").asInt()).isEqualTo(2);
    assertPreviewIssue(report,1,"IMPORT_COLUMN_MISSING","qualifications","监考资格","列");
    assertPreviewIssue(report,1,"IMPORT_COLUMN_MISSING","qualificationExpiry","资格有效期","列");
    assertThat(http.perform(f.apply(multipart("/api/v1/import/teachers/commit").file(file),b)).andReturn().getResponse().getStatus()).isEqualTo(400);
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
  }
  @Test void actualDemoInternalCallsCannotUseAbsentLeaseAsFormalBypass() throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();TenantContext.set(b.org());
    UUID task=jdbc.queryForObject("select id from exam_task where org_id=? limit 1",UUID.class,b.org());
    String before=f.fingerprint(b.visitor().id());
    List<Runnable> calls=List.of(()->master.createTeacher(null),()->master.createClassroom(null),()->master.createClass(null),()->master.createSubject(null),
        ()->master.updateTeacher(UUID.randomUUID(),null),()->master.updateClassroom(UUID.randomUUID(),null),()->master.updateClass(UUID.randomUUID(),null),()->master.updateSubject(UUID.randomUUID(),null),
        ()->master.deleteTeacher(UUID.randomUUID()),()->master.deleteClassroom(UUID.randomUUID()),()->master.deleteClass(UUID.randomUUID()),()->master.deleteSubject(UUID.randomUUID()),
        ()->tasks.create(null),()->tasks.delete(task),()->tasks.validate(task),()->tasks.upload(task,"teacher",null,false),()->imports.commit("teachers",null,null),
        ()->tasks.assignments(task),()->tasks.export(task,"xlsx"),()->tasks.export(task,"pdf",UUID.randomUUID(),0));
    for(var call:calls)assertThatThrownBy(call::run).isInstanceOfSatisfying(com.jkxt.security.auth.AuthException.class,
        error->assertThat(error.getCode()).isEqualTo("DEMO_LEASE_REQUIRED"));
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
  }
  static void assertPreviewIssue(com.fasterxml.jackson.databind.JsonNode report,int row,String code,String field,String label,String action) {
    assertThat(report.path("items")).anySatisfy(item->{
      assertThat(item.path("code").asText()).isEqualTo(code);assertThat(item.path("row").asInt()).isEqualTo(row);
      assertThat(item.path("field").asText()).isEqualTo(field);assertThat(item.path("column").asText()).isEqualTo(label);
      assertThat(item.path("severity").asText()).isEqualTo("error");assertThat(item.path("problem").asText()).contains(action);
      assertThat(item.path("suggest").asText()).isNotBlank();
    });
  }
  @ParameterizedTest @ValueSource(strings={"NaN","Infinity","0x1p0"})
  void nonDecimalSubjectHoursAreExplicitErrorsWithoutAnyWrites(String hours) throws Exception {
    var f=new DemoTestFixture(application);var b=f.enter();String before=f.fingerprint(b.visitor().id());
    var file=workbook(new String[]{"科目编号","科目名称","类别","考试类型","周课时"},List.<String[]>of(new String[]{"DECIMAL-1","合成十进制课时","main","written",hours}));
    var preview=http.perform(f.apply(multipart("/api/v1/import/subjects/validate").file(file),b)).andReturn().getResponse();
    assertThat(preview.getStatus()).isEqualTo(200);
    assertPreviewIssue(mapper.readTree(preview.getContentAsByteArray()),2,"SUBJECT_HOURS_REVIEW","hours","周课时","数字");
    assertThat(commit(f,b,"subjects",file).getStatus()).isEqualTo(400);
    assertThat(f.fingerprint(b.visitor().id())).isEqualTo(before);
    var valid=workbook(new String[]{"科目编号","科目名称","类别","考试类型","周课时"},List.of(new String[]{"DECIMAL-1","合成十进制课时","main","written","2.5"},new String[]{"DECIMAL-2","合成空课时","main","written",""}));
    assertThat(commit(f,b,"subjects",valid).getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select hours from subject where org_id=? and code='DECIMAL-1'",java.math.BigDecimal.class,b.org())).isEqualByComparingTo("2.5");
    assertThat(jdbc.queryForObject("select hours from subject where org_id=? and code='DECIMAL-2'",java.math.BigDecimal.class,b.org())).isNull();
  }
}
