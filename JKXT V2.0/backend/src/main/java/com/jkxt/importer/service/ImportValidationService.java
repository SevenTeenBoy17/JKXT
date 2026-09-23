package com.jkxt.importer.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.demo.DemoImportLimits;
import com.jkxt.demo.DemoLease;
import com.jkxt.masterdata.domain.Subject;
import com.jkxt.masterdata.repo.SubjectRepository;
import com.jkxt.importer.api.ImportDtos.Counts;
import com.jkxt.importer.api.ImportDtos.Overview;
import com.jkxt.importer.api.ImportDtos.ValidationItem;
import com.jkxt.importer.api.ImportDtos.ValidationReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportValidationService {

  private static final List<String> CATEGORIES = List.of("fields", "avail", "cap", "time", "rules", "notes");

  private final DataFormatter formatter = new DataFormatter(Locale.ROOT);
  private final SubjectRepository subjectRepository;
  private final DemoImportLimits demoLimits;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  /** Standalone workbook checks used by offline parser tests and template tooling. */
  public ImportValidationService() {
    this.subjectRepository = null;
    this.demoLimits = null;
    this.jdbc = null;
  }

  /** The running application always includes same-school persistence checks. */
  @Autowired
  public ImportValidationService(SubjectRepository subjectRepository, DemoImportLimits demoLimits,
      org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.subjectRepository = subjectRepository;
    this.demoLimits = java.util.Objects.requireNonNull(demoLimits);
    this.jdbc = jdbc;
  }

  public byte[] template(String kind) {
    TemplateSpec spec = spec(kind);
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(spec.sheetName());
      Row header = sheet.createRow(0);
      Row sample = sheet.createRow(1);
      for (int i = 0; i < spec.columns().size(); i++) {
        ColumnSpec column = spec.columns().get(i);
        header.createCell(i).setCellValue(column.title());
        sample.createCell(i).setCellValue(column.sample());
        if (Set.of("qualifications", "qualificationExpiry").contains(column.field())) {
          var helper=workbook.getCreationHelper();
          var comment=sheet.createDrawingPatriarch().createCellComment(helper.createClientAnchor());
          comment.setString(helper.createRichTextString("qualifications".equals(column.field())
              ? "INVIGILATOR（监考员）、CHIEF（主监考），使用逗号分隔且不可重复；CHIEF 不包含 INVIGILATOR。保留列但空白表示明确无资格；正式旧文件缺列保持原值，演示必须保留此列。"
              : "填写 yyyy-MM-dd 或无时间部分的 Excel 日期。保留列但空白清空有效期，不代表已验证永久有效；正式旧文件缺列保持原值，演示必须保留此列。到期当日仍有效。"));
          header.getCell(i).setCellComment(comment);
        }
        sheet.autoSizeColumn(i);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create template", exception);
    }
  }

  public String templateFilename(String kind) {
    return "jkxt-" + spec(kind).kind() + "-template.xlsx";
  }

  /** Canonical kind id (teachers/classrooms/classes/subjects/exams). */
  public String normalizeKind(String kind) {
    return spec(kind).kind();
  }

  /** One parsed, non-blank data row mapped by template field name. */
  public record ParsedRow(int rowNo, Map<String, String> values) {
    public ParsedRow { values=Map.copyOf(values); }
    public String get(String field) {
      return values.getOrDefault(field, "");
    }
  }

  /**
   * Parse a workbook into typed, header-mapped data rows for the given kind.
   * Reuses the same column/alias spec and cell extraction as {@link #validate}.
   * Callers should run {@link #validate} first and only commit when error count is 0.
   */
  public List<ParsedRow> parseRows(String kind, MultipartFile file) {
    if(demoLimits!=null && demoLimits.capture().isPresent()) {
      try(var prepared=prepare(kind,file)){return prepared.rows();}
    }
    TemplateSpec spec = spec(kind);
    List<ParsedRow> rows = new ArrayList<>();
    if (file == null || file.isEmpty()) {
      return rows;
    }
    try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
      return parseRows(spec,workbook);
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "无法读取上传文件，可能不是有效的 xlsx/xls", exception);
    }
  }

  private List<ParsedRow> parseRows(TemplateSpec spec,Workbook workbook) {
      List<ParsedRow> rows=new ArrayList<>();
      Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
      if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
        return rows;
      }
      Map<String, Integer> indexes = readHeaderIndexes(spec, sheet.getRow(0));
      for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlank(row)) {
          continue;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (ColumnSpec column : spec.columns()) {
          if ("teachers".equals(spec.kind()) && indexes.containsKey(column.field())) {
            if ("qualifications".equals(column.field())) {
              values.put(column.field(),String.join(",",TeacherQualificationImportParser.qualifications(row.getCell(indexes.get(column.field())))));
              continue;
            }
            if ("qualificationExpiry".equals(column.field())) {
              LocalDate expiry=TeacherQualificationImportParser.expiry(row.getCell(indexes.get(column.field())),workbook);
              values.put(column.field(),expiry==null?"":expiry.toString());continue;
            }
          }
          String cell = value(row, indexes.get(column.field()));
          if (!isBlank(cell)) {
            values.put(column.field(), cell.trim());
          }
        }
        rows.add(new ParsedRow(rowIndex + 1, values));
      }
      return rows;
  }

  public ValidationReport validate(String kind, MultipartFile file) {
    if(demoLimits!=null && demoLimits.capture().isPresent()) {
      try(var prepared=prepare(kind,file)){return prepared.report();}
    }
    TemplateSpec spec = spec(kind);
    IssueCollector collector = new IssueCollector(spec.source());
    if (file == null || file.isEmpty()) {
      collector.add("error", "fields", null, "文件", "file", null, "IMPORT_FILE_EMPTY",
          "未收到需要校验的 Excel 文件", "请选择对应模板文件后重新上传");
      return collector.report(spec, 0);
    }

    try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
      return validateWorkbook(spec,workbook,false);
    } catch (IOException exception) {
      collector.add("error", "fields", null, "文件", "file", file.getOriginalFilename(), "IMPORT_FILE_UNREADABLE",
          "无法读取上传文件，可能不是有效的 xlsx/xls", "请确认文件未加密且格式正确");
      return collector.report(spec, 0);
    }
  }

  private ValidationReport validateWorkbook(TemplateSpec spec,Workbook workbook,boolean demo) {
      IssueCollector collector=new IssueCollector(spec.source(),demo);
      Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
      if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
        collector.add("error", "fields", null, "工作表", "sheet", null, "IMPORT_SHEET_EMPTY",
            "Excel 内没有可读取的工作表", "使用系统模板重新填写后上传");
        return collector.report(spec, 0);
      }

      Row header = sheet.getRow(0);
      Map<String, Integer> indexes = readHeaderIndexes(spec, header);
      validateQualificationHeaders(spec,header,collector);
      if(demo && "teachers".equals(spec.kind()))for(String field:List.of("qualifications","qualificationExpiry")) {
        if(!indexes.containsKey(field))collector.add("error","fields",1,qualificationTitle(field),field,null,"IMPORT_COLUMN_MISSING",
            "演示教师导入必须明确提供 "+qualificationTitle(field)+" 列","下载最新模板；保留空白单元格可明确清空该项");
      }
      for (ColumnSpec column : spec.requiredColumns()) {
        if (!indexes.containsKey(column.field())) {
          collector.add("error", "fields", 1, column.title(), column.field(), null, "IMPORT_COLUMN_MISSING",
              "缺少必填列: " + column.title(), "请下载最新模板并保留该列");
        }
      }

      int rowCount = 0;
      // 考试表的**跨行**占用表：逐行校验看不到其它行，故在此累积后统一做互斥判定（见 validateExamRoomOccupancy）。
      List<ExamSlot> examSlots = new ArrayList<>();
      for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlank(row)) {
          continue;
        }
        rowCount++;
        validateRow(spec, indexes, row, rowIndex + 1, collector, examSlots);
      }
      validateExamRoomOccupancy(examSlots, collector);
      if ("subjects".equals(spec.kind())) {
        validateSubjectIdentities(sheet, indexes, collector);
      }

      if (rowCount == 0) {
        collector.add("error", "fields", 2, "数据行", "rows", null, "IMPORT_NO_DATA_ROWS",
            "模板中没有可校验的数据行", "至少保留一行真实数据后重新上传");
      }
      return collector.report(spec, rowCount);
  }

  /** One owned parse permit and one immutable workbook meaning, shared with Quick commit. */
  public static final class PreparedImport implements AutoCloseable {
    private final String kind,originalName,sha256;
    private final int size;
    private final java.util.Optional<DemoLease> lease;
    private final List<ParsedRow> rows;
    private final ValidationReport report;
    private final DemoImportLimits.ParsePermit permit;
    private boolean closed;
    private PreparedImport(String kind,String name,byte[] bytes,java.util.Optional<DemoLease> lease,
        List<ParsedRow> rows,ValidationReport report,DemoImportLimits.ParsePermit permit) {
      this.kind=kind;this.originalName=name;this.size=bytes.length;this.lease=lease;this.rows=List.copyOf(rows);this.report=report;this.permit=permit;
      try{this.sha256=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
      catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public String kind(){return kind;} public String originalName(){return originalName;}
    public int size(){return size;} public String sha256(){return sha256;}
    public java.util.Optional<DemoLease> lease(){return lease;}
    public List<ParsedRow> rows(){return rows;} public ValidationReport report(){return report;}
    public void requireOpen(){if(closed)throw new IllegalStateException("Import parse context is closed");}
    @Override public void close(){if(!closed){closed=true;permit.close();}}
  }
  public PreparedImport prepare(String kind,MultipartFile file) {
    return prepare(kind,file,demoLimits.capture());
  }
  public PreparedImport prepare(String kind,MultipartFile file,java.util.Optional<DemoLease> captured) {
    demoLimits.readable(captured);
    var permit=demoLimits.begin(captured);
    try {
      TemplateSpec spec=spec(kind);boolean demo=captured.isPresent();
      String name=file==null || file.getOriginalFilename()==null?spec.kind()+".xlsx":file.getOriginalFilename();
      byte[] bytes=new byte[0];List<ParsedRow> rows=List.of();ValidationReport report;
      try {
        bytes=DemoImportLimits.freeze(file,demo);
        if(bytes.length==0)throw new ApiException(HttpStatus.BAD_REQUEST,"IMPORT_FILE_EMPTY","未收到需要校验的 Excel 文件");
        if(demo)DemoImportLimits.inspectPackage(bytes);
        try(Workbook workbook=WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
          report=validateWorkbook(spec,workbook,demo);
          if(report.counts().error()==0)rows=parseRows(spec,workbook);
        }
      } catch(ApiException invalid) {
        var issues=new IssueCollector(spec.source());
        issues.add("error","fields",null,"文件","file",name,invalid.getCode(),invalid.getMessage(),"修正文件后重新上传");
        report=issues.report(spec,0);
      } catch(IOException | org.apache.poi.EncryptedDocumentException | org.apache.poi.openxml4j.exceptions.InvalidOperationException | org.apache.poi.ooxml.POIXMLException invalid) {
        var issues=new IssueCollector(spec.source());issues.add("error","fields",null,"文件","file",name,"IMPORT_FILE_UNREADABLE",
            "无法读取工作簿，请确认文件完整、格式正确且未加密","使用系统 XLSX 模板重新上传");report=issues.report(spec,0);
      }
      // Database/authorization failures are not file-decoding errors.
      if(demo && report.counts().error()==0)report=validateDemoRows(spec.kind(),rows,report,captured.orElseThrow().orgId());
      demoLimits.readable(captured);
      return new PreparedImport(spec.kind(),name,bytes,captured,rows,report,permit);
    } catch(RuntimeException | Error failure){permit.close();throw failure;}
  }

  /** Persistence-sensitive checks run again under the workspace lock immediately before writes. */
  public ValidationReport validateDemoRows(String kind,List<ParsedRow> rows,ValidationReport original,java.util.UUID org) {
    TemplateSpec spec=spec(kind);var collector=new IssueCollector(spec.source(),true);
    for(var item:original.items())collector.add(item.severity(),item.category(),item.row(),item.column(),item.field(),item.rawValue(),item.code(),item.problem(),item.suggest());
    var keys=new LinkedHashSet<String>();var emails=new LinkedHashSet<String>();
    for(var row:rows) {
      String keyField="teachers".equals(kind)?"teacherNo":"code";
      if(!kind.equals("exams") && !keys.add(row.get(keyField)))demoIssue(collector,row,keyField,"IMPORT_ROW_DUPLICATE","同一自然编号重复，后续行会被丢弃，请合并后重试");
      if(kind.equals("teachers")) {
        String email=row.get("email");
        if(!email.isBlank() && (!emails.add(email.toLowerCase(Locale.ROOT)) || exists("select exists(select 1 from teacher where org_id=? and lower(email)=lower(?) and teacher_no<>?)",org,email,row.get("teacherNo"))))
          demoIssue(collector,row,"email","IMPORT_EMAIL_CONFLICT","邮箱与另一名教师冲突，请为每名教师提供唯一邮箱或明确留空");
      }
      if(Set.of("teachers","classes").contains(kind))for(String subject:row.get("subjects").split("[,，;；/]")) {
        if(!subject.isBlank() && !exists("select exists(select 1 from subject where org_id=? and (name=? or code=?))",org,subject.trim(),subject.trim()))
          demoIssue(collector,row,"subjects","IMPORT_SUBJECT_UNKNOWN","科目未在本工作区建立，请先维护科目再导入");
      }
      if(kind.equals("classes") && !row.get("homeroomTeacherNo").isBlank() && !exists("select exists(select 1 from teacher where org_id=? and teacher_no=?)",org,row.get("homeroomTeacherNo")))
        demoIssue(collector,row,"homeroomTeacherNo","IMPORT_TEACHER_UNKNOWN","班主任工号不存在，请先导入教师");
      if(kind.equals("subjects") && exists("select exists(select 1 from subject where org_id=? and name=? and code<>?)",org,row.get("name"),row.get("code")))
        demoIssue(collector,row,"name","SUBJECT_NAME_CODE_CONFLICT","科目名称已由不同编号使用");
      if(kind.equals("exams")) {
        if(!exists("select exists(select 1 from classroom where org_id=? and code=?)",org,row.get("roomCode")))demoIssue(collector,row,"roomCode","IMPORT_ROOM_UNKNOWN","考场未在本工作区建立，请先导入考场");
        if(!exists("select exists(select 1 from subject where org_id=? and (name=? or code=?))",org,row.get("subjectName"),row.get("subjectName")))demoIssue(collector,row,"subjectName","IMPORT_SUBJECT_UNKNOWN","考试科目未在本工作区建立，请先维护科目");
        for(String code:row.get("className").split("[,，;；/]"))if(!code.isBlank() && !exists("select exists(select 1 from school_class where org_id=? and (code=? or name=?))",org,code.trim(),code.trim()))demoIssue(collector,row,"className","IMPORT_CLASS_UNKNOWN","考试班级不存在，请先导入班级");
      }
    }
    return collector.report(spec,rows.size());
  }
  private boolean exists(String sql,Object... parameters){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,parameters));}
  private static void demoIssue(IssueCollector collector,ParsedRow row,String field,String code,String message) {
    String label=switch(field){case "teacherNo"->"工号";case "email"->"邮箱";case "subjects","subjectName"->"科目";case "roomCode"->"考场编号";case "className"->"班级";case "homeroomTeacherNo"->"班主任工号";case "name"->"名称";default->"编号";};
    collector.add("error","fields",row.rowNo(),label,field,row.get(field),code,message,"修正本行后重新上传，整批未提交");
  }

  private void validateSubjectIdentities(Sheet sheet, Map<String, Integer> indexes, IssueCollector collector) {
    Map<String, Integer> codes = new LinkedHashMap<>();
    Map<String, Integer> names = new LinkedHashMap<>();
    Map<String, Subject> existingNames = new LinkedHashMap<>();
    if (subjectRepository != null) {
      for (Subject subject : subjectRepository.findByOrgIdOrderByCodeAscNameAsc(TenantContext.getRequired())) {
        existingNames.put(subject.getName(), subject);
      }
    }
    for (int i = 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (isBlank(row)) continue;
      String code = value(row, indexes.get("code")).trim();
      String name = value(row, indexes.get("name")).trim();
      if (!code.isBlank()) {
        Integer previous = codes.putIfAbsent(code, i + 1);
        if (previous != null) collector.add("error", "fields", i + 1, "科目编号", "code", code,
            "SUBJECT_CODE_DUPLICATE", "科目编号与第 " + previous + " 行重复",
            "每个科目编号只保留一行，核对后重新上传");
      }
      if (!name.isBlank()) {
        Integer previous = names.putIfAbsent(name, i + 1);
        if (previous != null) collector.add("error", "fields", i + 1, "科目名称", "name", name,
            "SUBJECT_NAME_DUPLICATE", "科目名称与第 " + previous + " 行重复",
            "同一学校的科目名称须唯一，请核对科目编号和名称");
        Subject existing = existingNames.get(name);
        if (existing != null && !code.isBlank() && !code.equals(existing.getCode())) {
          collector.add("error", "fields", i + 1, "科目名称", "name", name,
              "SUBJECT_NAME_CODE_CONFLICT", "本校已存在同名科目，但本行科目编号与现有记录不一致",
              "请在科目管理中核对并使用现有科目编号；如确为新科目，请使用不同名称。原数据不会被覆盖");
        }
      }
    }
  }

  private Map<String, Integer> readHeaderIndexes(TemplateSpec spec, Row header) {
    Map<String, Integer> indexes = new LinkedHashMap<>();
    if (header == null) {
      return indexes;
    }
    for (Cell cell : header) {
      String normalized = normalizeHeader(text(cell));
      spec.fieldByAlias(normalized).ifPresent(field -> indexes.put(field, cell.getColumnIndex()));
    }
    return indexes;
  }

  private void validateQualificationHeaders(TemplateSpec spec,Row header,IssueCollector collector) {
    if (!"teachers".equals(spec.kind()) || header==null)return;
    Set<String> seen=new LinkedHashSet<>();
    for(Cell cell:header)spec.fieldByAlias(normalizeHeader(text(cell))).ifPresent(field->{
      if(Set.of("qualifications","qualificationExpiry").contains(field) && !seen.add(field))
        collector.add("error","fields",1,qualificationTitle(field),field,text(cell),"IMPORT_DUPLICATE_COLUMN",
            "监考资格或有效期列重复（包含同义列名）","每项只保留一列后重新上传");
    });
  }
  private static String qualificationTitle(String field){return "qualifications".equals(field)?"监考资格":"资格有效期";}

  private void validateRow(
      TemplateSpec spec,
      Map<String, Integer> indexes,
      Row row,
      int displayRow,
      IssueCollector collector,
      List<ExamSlot> examSlots
  ) {
    for (ColumnSpec column : spec.requiredColumns()) {
      if (isBlank(value(row, indexes.get(column.field())))) {
        collector.add("error", "fields", displayRow, column.title(), column.field(), null, "IMPORT_REQUIRED_EMPTY",
            "第 " + displayRow + " 行缺少必填项: " + column.title(), "补齐该单元格后重新校验");
      }
    }

    if ("teachers".equals(spec.kind())) {
      validateTeacherRow(row, indexes, displayRow, collector);
    } else if ("classrooms".equals(spec.kind())) {
      validateClassroomRow(row, indexes, displayRow, collector);
    } else if ("classes".equals(spec.kind())) {
      validateClassRow(row, indexes, displayRow, collector);
    } else if ("subjects".equals(spec.kind())) {
      validateSubjectRow(row, indexes, displayRow, collector);
    } else if ("exams".equals(spec.kind())) {
      validateExamRow(row, indexes, displayRow, collector, examSlots);
    }
  }

  private void validateTeacherRow(Row row, Map<String, Integer> indexes, int displayRow, IssueCollector collector) {
    for(String field:List.of("qualifications","qualificationExpiry"))if(indexes.containsKey(field)) {
      Cell cell=row.getCell(indexes.get(field));
      try {
        if("qualifications".equals(field))TeacherQualificationImportParser.qualifications(cell);
        else TeacherQualificationImportParser.expiry(cell,row.getSheet().getWorkbook());
      } catch(TeacherQualificationImportParser.Invalid invalid) {
        collector.add("error","fields",displayRow,qualificationTitle(field),field,value(row,indexes.get(field)),
            invalid.code(),invalid.getMessage(),"请按最新模板说明修正本行后重新上传");
      }
    }
    // 字段长度上限要与 DB 列约束(teacher_no varchar(30) / name varchar(80))一致，在校验阶段就带行/列指针报错，
    // 避免"校验通过却在 commit 时整批回滚、只给笼统 400"的不一致体验。
    String teacherNo = value(row, indexes.get("teacherNo"));
    if (teacherNo.length() > 30) {
      collector.add("error", "fields", displayRow, "工号", "teacherNo", null, "FIELD_TOO_LONG",
          "第 " + displayRow + " 行工号超过 30 字符(当前 " + teacherNo.length() + ")", "请将工号控制在 30 字符以内");
    }
    String name = value(row, indexes.get("name"));
    if (name.length() > 80) {
      collector.add("error", "fields", displayRow, "姓名", "name", null, "FIELD_TOO_LONG",
          "第 " + displayRow + " 行姓名超过 80 字符(当前 " + name.length() + ")", "请将姓名控制在 80 字符以内");
    }
    String gender = value(row, indexes.get("gender"));
    if (!isBlank(gender) && !Set.of("male", "female", "unknown", "男", "女").contains(gender.trim().toLowerCase(Locale.ROOT))) {
      collector.add("warn", "fields", displayRow, "性别", "gender", gender, "TEACHER_GENDER_UNKNOWN",
          "第 " + displayRow + " 行性别值不在推荐范围内", "建议使用 male/female/unknown 或 男/女");
    }
    String email = value(row, indexes.get("email"));
    if (!isBlank(email) && !email.contains("@")) {
      collector.add("suggest", "fields", displayRow, "邮箱", "email", email, "TEACHER_EMAIL_REVIEW",
          "第 " + displayRow + " 行邮箱格式看起来不完整", "该项不阻塞导入，但建议补全标准邮箱");
    }
  }

  private void validateClassroomRow(Row row, Map<String, Integer> indexes, int displayRow, IssueCollector collector) {
    validatePositiveInt(row, indexes, displayRow, "capacity", "容量", collector);
    validatePositiveInt(row, indexes, displayRow, "examCapacity", "考试容量", collector);
    String roomType = value(row, indexes.get("roomType"));
    if (!isBlank(roomType) && !Set.of("standard", "corridor", "lab", "meeting", "other", "normal").contains(roomType.trim().toLowerCase(Locale.ROOT))) {
      collector.add("warn", "rules", displayRow, "考场类型", "roomType", roomType, "ROOM_TYPE_REVIEW",
          "第 " + displayRow + " 行考场类型不在系统枚举内", "建议使用 standard/corridor/lab/meeting/other");
    }
  }

  private void validateClassRow(Row row, Map<String, Integer> indexes, int displayRow, IssueCollector collector) {
    validatePositiveInt(row, indexes, displayRow, "studentCount", "学生数", collector);
  }

  private void validateSubjectRow(Row row, Map<String, Integer> indexes, int displayRow, IssueCollector collector) {
    String category = value(row, indexes.get("category"));
    if (!isBlank(category) && !Set.of("main", "secondary", "activity", "主科", "副科", "术科").contains(category.trim().toLowerCase(Locale.ROOT))) {
      collector.add("warn", "rules", displayRow, "类别", "category", category, "SUBJECT_CATEGORY_REVIEW",
          "第 " + displayRow + " 行科目类别不在推荐范围内", "建议使用 main/secondary/activity");
    }
    String hours = value(row, indexes.get("hours"));
    if (!isBlank(hours) && !(collector.demo ? isPersistableDecimal(hours) : isDecimal(hours))) {
      collector.add("warn", "time", displayRow, "周课时", "hours", hours, "SUBJECT_HOURS_REVIEW",
          "第 " + displayRow + " 行周课时不是数字", "建议填写数字，例如 5 或 2.5");
    }
  }

  private void validateExamRow(Row row, Map<String, Integer> indexes, int displayRow, IssueCollector collector,
      List<ExamSlot> examSlots) {
    validatePositiveInt(row, indexes, displayRow, "seatDemand", "seatDemand", collector);
    validatePositiveInt(row, indexes, displayRow, "supervisorsPerRoom", "supervisorsPerRoom", collector);
    String date = value(row, indexes.get("examDate"));
    if (!isBlank(date) && !isDate(date)) {
      collector.add("warn", "time", displayRow, "examDate", "examDate", date, "EXAM_DATE_REVIEW",
          "Row " + displayRow + " examDate is not ISO yyyy-MM-dd", "Use a standard date such as 2026-06-08");
    }
    String start = value(row, indexes.get("startTime"));
    if (!isBlank(start) && !isTime(start)) {
      collector.add("warn", "time", displayRow, "startTime", "startTime", start, "EXAM_START_REVIEW",
          "Row " + displayRow + " startTime is not HH:mm", "Use a standard time such as 08:30");
    }
    String end = value(row, indexes.get("endTime"));
    if (!isBlank(end) && !isTime(end)) {
      collector.add("warn", "time", displayRow, "endTime", "endTime", end, "EXAM_END_REVIEW",
          "Row " + displayRow + " endTime is not HH:mm", "Use a standard time such as 10:00");
    }
    // 结束时间必须晚于开始时间。否则负/零时长考试会全程静默通过(校验/导入/排考)，且求解器的
    // 半开区间重叠判定对 start>=end 退化为空集，形成墙钟时间冲突检测盲区。此处直接判 error 阻止提交。
    if (!isBlank(start) && isTime(start) && !isBlank(end) && isTime(end)
        && !LocalTime.parse(end.trim()).isAfter(LocalTime.parse(start.trim()))) {
      collector.add("error", "time", displayRow, "结束时间", "endTime", start + "→" + end, "EXAM_TIME_RANGE_INVALID",
          "第 " + displayRow + " 行结束时间不晚于开始时间(" + start + "→" + end + ")", "请填写晚于开始时间的结束时间");
    }

    // 收集本行的考场占用（仅当考场/日期/时间都合法且时长为正时入池）——非法行已由上面的规则报错，
    // 重复报会制造噪声；且退化区间(start>=end)在半开判据下恒不重叠，入池无意义。
    String roomCode = value(row, indexes.get("roomCode"));
    if (examSlots != null && !isBlank(roomCode) && !isBlank(date) && isDate(date)
        && !isBlank(start) && isTime(start) && !isBlank(end) && isTime(end)
        && LocalTime.parse(end.trim()).isAfter(LocalTime.parse(start.trim()))) {
      examSlots.add(new ExamSlot(displayRow, roomCode.trim(), date.trim(),
          LocalTime.parse(start.trim()), LocalTime.parse(end.trim()),
          String.valueOf(value(row, indexes.get("subjectName"))).trim(),
          String.valueOf(value(row, indexes.get("grade"))).trim()));
    }
  }

  /** 考试表一行的考场占用，用于跨行互斥判定。 */
  private record ExamSlot(int rowNo, String roomCode, String date, LocalTime start, LocalTime end,
      String subjectName, String grade) {
  }

  /**
   * 跨行校验：同一考场在重叠时段内只能进行一场考试。
   *
   * <p>此前**完全没有**这条校验（逐行校验看不到其它行），导致自相矛盾的考试表被静默放行：实测一份
   * 「A101 同日 08:30-10:00 同时排三年级语文与五年级英语」的表返回 severity=pass/error=0，落库后求解器
   * 把每个 (exam,room) 当独立槽位各派 2 名监考 → 产出"一个教室同一时刻坐两批学生考两门课"的不可执行方案。
   * 物理上不可能的安排必须在导入阶段就拦下，而不是排考后才由人肉发现。
   *
   * <p>判据与 {@code CpSatScheduleSolver.overlaps()} 逐字一致（半开区间 a.start &lt; b.end && b.start &lt; a.end），
   * 保证校验层与求解器对"重叠"的定义永不分叉；相邻不重叠的连续场次（如上午语文、下午数学）正常放行。
   * 判为 error 而非 warn：两条落库路径（ImportCommitService.commit 与 ExamTaskService.upload）都只以
   * {@code counts().error() == 0} 为闸门，warn 拦不住。
   */
  private void validateExamRoomOccupancy(List<ExamSlot> slots, IssueCollector collector) {
    if (slots == null || slots.size() < 2) {
      return;
    }
    Map<String, List<ExamSlot>> byRoomDate = new LinkedHashMap<>();
    for (ExamSlot s : slots) {
      byRoomDate.computeIfAbsent(s.roomCode() + "|" + s.date(), k -> new ArrayList<>()).add(s);
    }
    // 每行最多报一次，避免一个考场被 N 场占用时产生 N² 条噪声。
    Set<Integer> reported = new LinkedHashSet<>();
    for (List<ExamSlot> group : byRoomDate.values()) {
      if (group.size() < 2) {
        continue;
      }
      for (int i = 0; i < group.size(); i++) {
        for (int j = i + 1; j < group.size(); j++) {
          ExamSlot a = group.get(i);
          ExamSlot b = group.get(j);
          if (!(a.start().isBefore(b.end()) && b.start().isBefore(a.end()))) {
            continue;   // 不重叠：合法的连续场次
          }
          if (!reported.add(b.rowNo())) {
            continue;
          }
          boolean duplicateRow = a.subjectName().equals(b.subjectName()) && a.grade().equals(b.grade())
              && a.start().equals(b.start()) && a.end().equals(b.end());
          if (duplicateRow) {
            collector.add("error", "time", b.rowNo(), "考场编号", "roomCode",
                b.roomCode() + "@" + b.date() + " " + b.start() + "-" + b.end(), "EXAM_ROOM_ROW_DUPLICATE",
                "第 " + b.rowNo() + " 行与第 " + a.rowNo() + " 行完全重复(同考场同时段同科目 " + b.subjectName() + ")",
                "请删除重复行，同一场考试在同一考场只需填写一行");
          } else {
            collector.add("error", "time", b.rowNo(), "考场编号", "roomCode",
                b.roomCode() + "@" + b.date() + " " + b.start() + "-" + b.end(), "EXAM_ROOM_TIME_CONFLICT",
                "第 " + b.rowNo() + " 行考场 " + b.roomCode() + " 在 " + b.date() + " " + b.start() + "→" + b.end()
                    + " 与第 " + a.rowNo() + " 行(" + a.subjectName() + "/" + a.grade() + ") 时段重叠，"
                    + "同一考场同一时间只能进行一场考试",
                "请把其中一场改到其他考场，或调整到不重叠的时段");
          }
        }
      }
    }
  }

  private void validatePositiveInt(
      Row row,
      Map<String, Integer> indexes,
      int displayRow,
      String field,
      String title,
      IssueCollector collector
  ) {
    String raw = value(row, indexes.get(field));
    if (isBlank(raw)) {
      return;
    }
    try {
      // 迭代10 P2: 用 BigDecimal 判整，替换会把 "10.05"→"105" 静默放大的 replace(".0","")；
      // 非整数(含 "10.05")经 intValueExact() 抛 ArithmeticException → 落入下方 NUMBER_FORMAT_REVIEW 告警。
      if (new java.math.BigDecimal(raw.trim()).intValueExact() < 0) {
        collector.add("error", "cap", displayRow, title, field, raw, "NEGATIVE_NUMBER",
            "第 " + displayRow + " 行 " + title + " 不能为负数", "请改为大于等于 0 的整数");
      }
    } catch (NumberFormatException | ArithmeticException exception) {
      collector.add("warn", "cap", displayRow, title, field, raw, "NUMBER_FORMAT_REVIEW",
          "第 " + displayRow + " 行 " + title + " 不是整数", "请填写整数，避免后续容量判断误差");
    }
  }

  private String value(Row row, Integer index) {
    if (row == null || index == null) {
      return "";
    }
    Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    return text(cell);
  }

  private String text(Cell cell) {
    if (cell == null || cell.getCellType() == CellType.BLANK) {
      return "";
    }
    return formatter.formatCellValue(cell).trim();
  }

  private boolean isBlank(Row row) {
    if (row == null) {
      return true;
    }
    for (Cell cell : row) {
      if (!isBlank(text(cell))) {
        return false;
      }
    }
    return true;
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private boolean isDecimal(String value) {
    try {
      Double.parseDouble(value);
      return true;
    } catch (NumberFormatException exception) {
      return false;
    }
  }
  private boolean isPersistableDecimal(String value) {
    try {new java.math.BigDecimal(value.trim());return true;}
    catch(NumberFormatException invalid){return false;}
  }

  private boolean isDate(String value) {
    try {
      LocalDate.parse(value.trim());
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private boolean isTime(String value) {
    try {
      LocalTime.parse(value.trim());
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private String normalizeHeader(String value) {
    return value == null
        ? ""
        : value.trim()
            .toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
            .replace("（", "(")
            .replace("）", ")");
  }

  private TemplateSpec spec(String rawKind) {
    String kind = rawKind == null ? "teachers" : rawKind.trim().toLowerCase(Locale.ROOT);
    kind = switch (kind) {
      case "teacher", "teachers", "t01" -> "teachers";
      case "classroom", "classrooms", "rooms", "room", "t02" -> "classrooms";
      case "exam", "exams", "schedule", "schedules", "t03" -> "exams";
      case "class", "classes" -> "classes";
      case "subject", "subjects" -> "subjects";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported import kind: " + rawKind);
    };
    return switch (kind) {
      case "exams" -> new TemplateSpec(kind, "Quick exams", "Exam sheet", List.of(
          new ColumnSpec("subjectName", "subjectName", true, "Math", "subject", "subjectname", "科目", "考试科目"),
          new ColumnSpec("grade", "grade", true, "G10", "grade", "年级"),
          new ColumnSpec("examDate", "examDate", true, "2026-06-08", "date", "examdate", "考试日期"),
          new ColumnSpec("startTime", "startTime", true, "08:30", "start", "starttime", "开始时间"),
          new ColumnSpec("endTime", "endTime", true, "10:00", "end", "endtime", "结束时间"),
          new ColumnSpec("roomCode", "roomCode", true, "P6-R101", "room", "roomcode", "考场编号"),
          new ColumnSpec("seatDemand", "seatDemand", true, "36", "students", "seatdemand", "人数"),
          new ColumnSpec("supervisorsPerRoom", "supervisorsPerRoom", true, "2", "supervisors", "proctors", "监考人数"),
          // 班级：该场考试坐哪个/哪些班(逗号分隔班级编号)，驱动"班主任不监本班"。可选，缺省则不做班级回避。
          new ColumnSpec("className", "班级", false, "G10-01", "classname", "班级", "班级编号", "考试班级")
      ));
      case "teachers" -> new TemplateSpec(kind, "教师主数据", "教师表", List.of(
          new ColumnSpec("teacherNo", "工号", true, "T2026001", "teacherno", "teacherid", "工号", "教师工号"),
          new ColumnSpec("name", "姓名", true, "王老师", "name", "姓名", "教师姓名"),
          new ColumnSpec("gender", "性别", false, "female", "gender", "性别"),
          new ColumnSpec("grade", "年级", false, "高一", "grade", "年级"),
          new ColumnSpec("subjects", "任教学科", false, "语文,历史", "subjects", "subject", "任教学科", "科目"),
          new ColumnSpec("phone", "手机号", false, "13800000001", "phone", "手机号", "电话"),
          new ColumnSpec("email", "邮箱", false, "teacher@example.com", "email", "邮箱"),
          // 班主任标记 + 所带班级(班级编号)，用于"班主任不监本班"。两者皆可选；也可改在班级表填班主任工号。
          new ColumnSpec("isHomeroom", "班主任", false, "否", "ishomeroom", "班主任", "是否班主任"),
          new ColumnSpec("homeroomClass", "所带班级", false, "G10-01", "homeroomclass", "所带班级", "班主任班级"),
          new ColumnSpec("qualifications", "监考资格", false, "INVIGILATOR", "qualifications", "监考资格", "监考资质"),
          new ColumnSpec("qualificationExpiry", "资格有效期", false, "2026-12-31", "qualificationExpiry", "资格有效期", "资格有效期至")
      ));
      case "classrooms" -> new TemplateSpec(kind, "考场主数据", "考场表", List.of(
          new ColumnSpec("code", "考场编号", true, "R101", "code", "roomcode", "考场编号", "教室编号"),
          new ColumnSpec("name", "考场名称", true, "第一考场", "name", "考场名称", "教室名称"),
          new ColumnSpec("building", "楼栋", false, "明德楼", "building", "楼栋"),
          new ColumnSpec("floor", "楼层", false, "3F", "floor", "楼层"),
          new ColumnSpec("capacity", "容量", true, "48", "capacity", "容量"),
          new ColumnSpec("examCapacity", "考试容量", true, "36", "examcapacity", "考试容量"),
          new ColumnSpec("roomType", "考场类型", false, "standard", "roomtype", "type", "考场类型"),
          new ColumnSpec("features", "设施标签", false, "广播,监控", "features", "设施", "设施标签")
      ));
      case "classes" -> new TemplateSpec(kind, "班级主数据", "班级表", List.of(
          new ColumnSpec("code", "班级编号", true, "G10-01", "code", "classcode", "班级编号"),
          new ColumnSpec("name", "班级名称", true, "高一(1)班", "name", "班级名称"),
          new ColumnSpec("grade", "年级", true, "高一", "grade", "年级"),
          new ColumnSpec("stage", "学段", false, "高中", "stage", "学段"),
          new ColumnSpec("studentCount", "学生数", true, "46", "studentcount", "students", "学生数", "人数"),
          new ColumnSpec("subjects", "开设科目", false, "语文,数学,英语", "subjects", "科目"),
          // 班主任工号：与教师表的"所带班级"二选一即可绑定班主任→班级，驱动"班主任不监本班"。
          new ColumnSpec("homeroomTeacherNo", "班主任工号", false, "T2026001", "homeroomteacherno", "班主任工号", "班主任")
      ));
      case "subjects" -> new TemplateSpec(kind, "科目主数据", "科目表", List.of(
          new ColumnSpec("code", "科目编号", true, "SUB-CN", "code", "subjectcode", "科目编号"),
          new ColumnSpec("name", "科目名称", true, "语文", "name", "科目名称"),
          new ColumnSpec("category", "类别", true, "main", "category", "类别"),
          new ColumnSpec("examType", "考试类型", true, "written", "examtype", "考试类型"),
          new ColumnSpec("hours", "周课时", false, "5", "hours", "周课时"),
          new ColumnSpec("color", "颜色", false, "rose", "color", "颜色")
      ));
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported import kind: " + rawKind);
    };
  }

  private record ColumnSpec(String field, String title, boolean required, String sample, List<String> aliases) {
    ColumnSpec(String field, String title, boolean required, String sample, String... aliases) {
      this(field, title, required, sample, List.of(aliases));
    }
  }

  private record TemplateSpec(String kind, String sheetName, String source, List<ColumnSpec> columns) {
    List<ColumnSpec> requiredColumns() {
      return columns.stream().filter(ColumnSpec::required).toList();
    }

    java.util.Optional<String> fieldByAlias(String alias) {
      return columns.stream()
          .filter(column -> normalize(column.field()).equals(alias)
              || normalize(column.title()).equals(alias)
              || column.aliases().stream().map(TemplateSpec::normalize).anyMatch(alias::equals))
          .map(ColumnSpec::field)
          .findFirst();
    }

    private static String normalize(String value) {
      return value == null
          ? ""
          : value.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }
  }

  private static final class IssueCollector {

    private final String source;
    private final boolean demo;
    private final List<ValidationItem> items = new ArrayList<>();
    private final MutableCounts total = new MutableCounts();
    private final Map<String, MutableCounts> cats = new LinkedHashMap<>();

    private IssueCollector(String source) {
      this(source,false);
    }
    private IssueCollector(String source,boolean demo) {
      this.source = source;
      this.demo = demo;
      CATEGORIES.forEach(category -> cats.put(category, new MutableCounts()));
    }

    private void add(
        String severity,
        String category,
        Integer row,
        String column,
        String field,
        String rawValue,
        String code,
        String problem,
        String suggest
    ) {
      if(demo && Set.of("TEACHER_GENDER_UNKNOWN","ROOM_TYPE_REVIEW","SUBJECT_CATEGORY_REVIEW","SUBJECT_HOURS_REVIEW",
          "NUMBER_FORMAT_REVIEW","EXAM_DATE_REVIEW","EXAM_START_REVIEW","EXAM_END_REVIEW").contains(code))severity="error";
      MutableCounts counts = cats.computeIfAbsent(category, ignored -> new MutableCounts());
      counts.inc(severity);
      total.inc(severity);
      String ref = row == null ? column : "R" + row + "C" + (column == null ? "-" : column);
      items.add(new ValidationItem(
          "I" + (items.size() + 1),
          severity,
          category,
          source,
          ref,
          row,
          column,
          field,
          status(severity),
          problem,
          suggest,
          rawValue,
          code
      ));
    }

    private ValidationReport report(TemplateSpec spec, int rowCount) {
      int checks = Math.max(0, rowCount * Math.max(1, spec.requiredColumns().size()));
      int pass = Math.max(0, checks - total.error - total.warn - total.suggest);
      total.pass = pass;
      cats.get("fields").pass = Math.max(0, pass);
      Map<String, Counts> catCounts = new LinkedHashMap<>();
      CATEGORIES.forEach(category -> catCounts.put(category, cats.getOrDefault(category, new MutableCounts()).toCounts()));
      Counts counts = total.toCounts();
      String severity = counts.error() > 0 ? "error" : counts.warn() > 0 ? "warn" : counts.suggest() > 0 ? "suggest" : "pass";
      String category = items.isEmpty() ? "fields" : items.get(0).category();
      // A hard fail (any blocking error, or no importable data rows) must not score near-perfect:
      // an empty/header-only file would otherwise report ~95 and show as green "良好". Force 0.
      int completeness = (counts.error() > 0 || rowCount == 0)
          ? 0
          : Math.max(0, Math.min(100, 100 - counts.error() * 5 - counts.warn() * 2));
      Overview overview = overview(spec.kind(), rowCount, completeness);
      return new ValidationReport(severity, category, overview, counts, catCounts, List.copyOf(items));
    }

    private Overview overview(String kind, int rowCount, int completeness) {
      return switch (kind) {
        case "teachers" -> new Overview(completeness, rowCount, 0, 0, rowCount * 2);
        case "classrooms" -> new Overview(completeness, 0, rowCount, 0, 0);
        case "classes" -> new Overview(completeness, 0, 0, rowCount, rowCount);
        case "subjects" -> new Overview(completeness, 0, 0, rowCount, 0);
        case "exams" -> new Overview(completeness, 0, 0, rowCount, rowCount * 2);
        default -> new Overview(completeness, 0, 0, 0, 0);
      };
    }

    private String status(String severity) {
      return switch (severity) {
        case "error" -> "需修改";
        case "warn" -> "建议复核";
        default -> "可继续";
      };
    }
  }

  private static final class MutableCounts {
    private int pass;
    private int suggest;
    private int warn;
    private int error;

    private void inc(String severity) {
      switch (severity) {
        case "error" -> error++;
        case "warn" -> warn++;
        case "suggest" -> suggest++;
        default -> pass++;
      }
    }

    private Counts toCounts() {
      return new Counts(pass, suggest, warn, error);
    }
  }
}
