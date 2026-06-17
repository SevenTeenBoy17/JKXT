package com.jkxt.importer.service;

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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportValidationService {

  private static final List<String> CATEGORIES = List.of("fields", "avail", "cap", "time", "rules", "notes");

  private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

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
    TemplateSpec spec = spec(kind);
    List<ParsedRow> rows = new ArrayList<>();
    if (file == null || file.isEmpty()) {
      return rows;
    }
    try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
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
          String cell = value(row, indexes.get(column.field()));
          if (!isBlank(cell)) {
            values.put(column.field(), cell.trim());
          }
        }
        rows.add(new ParsedRow(rowIndex + 1, values));
      }
      return rows;
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "无法读取上传文件，可能不是有效的 xlsx/xls", exception);
    }
  }

  public ValidationReport validate(String kind, MultipartFile file) {
    TemplateSpec spec = spec(kind);
    IssueCollector collector = new IssueCollector(spec.source());
    if (file == null || file.isEmpty()) {
      collector.add("error", "fields", null, "文件", "file", null, "IMPORT_FILE_EMPTY",
          "未收到需要校验的 Excel 文件", "请选择对应模板文件后重新上传");
      return collector.report(spec, 0);
    }

    try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
      Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
      if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
        collector.add("error", "fields", null, "工作表", "sheet", null, "IMPORT_SHEET_EMPTY",
            "Excel 内没有可读取的工作表", "使用系统模板重新填写后上传");
        return collector.report(spec, 0);
      }

      Row header = sheet.getRow(0);
      Map<String, Integer> indexes = readHeaderIndexes(spec, header);
      for (ColumnSpec column : spec.requiredColumns()) {
        if (!indexes.containsKey(column.field())) {
          collector.add("error", "fields", 1, column.title(), column.field(), null, "IMPORT_COLUMN_MISSING",
              "缺少必填列: " + column.title(), "请下载最新模板并保留该列");
        }
      }

      int rowCount = 0;
      for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlank(row)) {
          continue;
        }
        rowCount++;
        validateRow(spec, indexes, row, rowIndex + 1, collector);
      }

      if (rowCount == 0) {
        collector.add("error", "fields", 2, "数据行", "rows", null, "IMPORT_NO_DATA_ROWS",
            "模板中没有可校验的数据行", "至少保留一行真实数据后重新上传");
      }
      return collector.report(spec, rowCount);
    } catch (IOException exception) {
      collector.add("error", "fields", null, "文件", "file", file.getOriginalFilename(), "IMPORT_FILE_UNREADABLE",
          "无法读取上传文件，可能不是有效的 xlsx/xls", "请确认文件未加密且格式正确");
      return collector.report(spec, 0);
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

  private void validateRow(
      TemplateSpec spec,
      Map<String, Integer> indexes,
      Row row,
      int displayRow,
      IssueCollector collector
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
      validateExamRow(row, indexes, displayRow, collector);
    }
  }

  private void validateTeacherRow(Row row, Map<String, Integer> indexes, int displayRow, IssueCollector collector) {
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
    if (!isBlank(hours) && !isDecimal(hours)) {
      collector.add("warn", "time", displayRow, "周课时", "hours", hours, "SUBJECT_HOURS_REVIEW",
          "第 " + displayRow + " 行周课时不是数字", "建议填写数字，例如 5 或 2.5");
    }
  }

  private void validateExamRow(Row row, Map<String, Integer> indexes, int displayRow, IssueCollector collector) {
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
      if (Integer.parseInt(raw.replace(".0", "")) < 0) {
        collector.add("error", "cap", displayRow, title, field, raw, "NEGATIVE_NUMBER",
            "第 " + displayRow + " 行 " + title + " 不能为负数", "请改为大于等于 0 的整数");
      }
    } catch (NumberFormatException exception) {
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
          new ColumnSpec("supervisorsPerRoom", "supervisorsPerRoom", true, "2", "supervisors", "proctors", "监考人数")
      ));
      case "teachers" -> new TemplateSpec(kind, "教师主数据", "教师表", List.of(
          new ColumnSpec("teacherNo", "工号", true, "T2026001", "teacherno", "teacherid", "工号", "教师工号"),
          new ColumnSpec("name", "姓名", true, "王老师", "name", "姓名", "教师姓名"),
          new ColumnSpec("gender", "性别", false, "female", "gender", "性别"),
          new ColumnSpec("grade", "年级", false, "高一", "grade", "年级"),
          new ColumnSpec("subjects", "任教学科", false, "语文,历史", "subjects", "subject", "任教学科", "科目"),
          new ColumnSpec("phone", "手机号", false, "13800000001", "phone", "手机号", "电话"),
          new ColumnSpec("email", "邮箱", false, "teacher@example.com", "email", "邮箱")
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
          new ColumnSpec("subjects", "开设科目", false, "语文,数学,英语", "subjects", "科目")
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
    private final List<ValidationItem> items = new ArrayList<>();
    private final MutableCounts total = new MutableCounts();
    private final Map<String, MutableCounts> cats = new LinkedHashMap<>();

    private IssueCollector(String source) {
      this.source = source;
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
      int completeness = Math.max(0, Math.min(100, 100 - counts.error() * 5 - counts.warn() * 2));
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
