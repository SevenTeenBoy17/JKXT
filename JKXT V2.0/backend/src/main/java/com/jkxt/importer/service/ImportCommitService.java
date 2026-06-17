package com.jkxt.importer.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.importer.api.ImportDtos.CommitResult;
import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.importer.service.ImportValidationService.ParsedRow;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.SchoolClass;
import com.jkxt.masterdata.domain.Subject;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.SchoolClassRepository;
import com.jkxt.masterdata.repo.SubjectRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.tasks.domain.Exam;
import com.jkxt.tasks.domain.ExamTask;
import com.jkxt.tasks.repo.ExamRepository;
import com.jkxt.tasks.repo.ExamTaskRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists a validated import into the database (real data flow, Epic 1).
 *
 * <p>Model (confirmed 2026-06-18): school roster (teachers/classrooms/classes/subjects)
 * is org-level and upserted by natural key; the exam schedule is per task and bound to
 * {@code exam_task_id}. This is the data the scheduler later reads via
 * {@code buildFixtureFromTask} — replacing the hardcoded fixture.
 */
@Service
public class ImportCommitService {

  private final ImportValidationService validationService;
  private final TeacherRepository teacherRepository;
  private final ClassroomRepository classroomRepository;
  private final SchoolClassRepository schoolClassRepository;
  private final SubjectRepository subjectRepository;
  private final ExamRepository examRepository;
  private final ExamTaskRepository examTaskRepository;
  private final JdbcTemplate jdbcTemplate;

  public ImportCommitService(
      ImportValidationService validationService,
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      SchoolClassRepository schoolClassRepository,
      SubjectRepository subjectRepository,
      ExamRepository examRepository,
      ExamTaskRepository examTaskRepository,
      JdbcTemplate jdbcTemplate
  ) {
    this.validationService = validationService;
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.schoolClassRepository = schoolClassRepository;
    this.subjectRepository = subjectRepository;
    this.examRepository = examRepository;
    this.examTaskRepository = examTaskRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional
  public CommitResult commit(String kind, UUID taskId, MultipartFile file) {
    UUID orgId = TenantContext.getRequired();
    String canonical = validationService.normalizeKind(kind);

    // Gate: never persist a file that fails validation.
    ValidationReport report = validationService.validate(kind, file);
    if (report.counts().error() > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "导入校验未通过，存在 " + report.counts().error() + " 处错误，请修正后再提交");
    }

    List<ParsedRow> rows = validationService.parseRows(kind, file);
    return switch (canonical) {
      case "teachers" -> commitTeachers(orgId, rows);
      case "classrooms" -> commitClassrooms(orgId, rows);
      case "classes" -> commitClasses(orgId, rows);
      case "subjects" -> commitSubjects(orgId, rows);
      case "exams" -> commitExams(orgId, requireTask(orgId, taskId), rows);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的导入类型: " + kind);
    };
  }

  private CommitResult commitTeachers(UUID orgId, List<ParsedRow> rows) {
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    List<String> warnings = new ArrayList<>();
    for (ParsedRow row : rows) {
      String no = row.get("teacherNo");
      if (no.isBlank()) {
        skipped++;
        continue;
      }
      Optional<Teacher> existing = teacherRepository.findByOrgIdAndTeacherNo(orgId, no);
      Teacher teacher = existing.orElseGet(Teacher::new);
      teacher.setOrgId(orgId);
      teacher.setTeacherNo(no);
      teacher.setName(orDefault(row.get("name"), no));
      teacher.setGender(normalizeGender(row.get("gender")));
      teacher.setGrade(blankToNull(row.get("grade")));
      teacher.setPhone(blankToNull(row.get("phone")));
      teacher.setEmail(blankToNull(row.get("email")));
      teacher.setStatus("active");
      teacherRepository.save(teacher);
      if (existing.isPresent()) {
        updated++;
      } else {
        inserted++;
      }
    }
    return new CommitResult("teachers", rows.size(), inserted, updated, skipped, warnings);
  }

  private CommitResult commitClassrooms(UUID orgId, List<ParsedRow> rows) {
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    List<String> warnings = new ArrayList<>();
    for (ParsedRow row : rows) {
      String code = row.get("code");
      if (code.isBlank()) {
        skipped++;
        continue;
      }
      Optional<Classroom> existing = classroomRepository.findByOrgIdAndCode(orgId, code);
      Classroom classroom = existing.orElseGet(Classroom::new);
      classroom.setOrgId(orgId);
      classroom.setCode(code);
      classroom.setName(orDefault(row.get("name"), code));
      classroom.setBuilding(blankToNull(row.get("building")));
      classroom.setFloor(blankToNull(row.get("floor")));
      classroom.setCapacity(intOf(row.get("capacity"), 0));
      classroom.setExamCapacity(intOf(row.get("examCapacity"), 0));
      classroom.setRoomType(normalizeRoomType(row.get("roomType")));
      classroom.setFeatures(splitList(row.get("features")));
      classroom.setStatus("active");
      classroomRepository.save(classroom);
      if (existing.isPresent()) {
        updated++;
      } else {
        inserted++;
      }
    }
    return new CommitResult("classrooms", rows.size(), inserted, updated, skipped, warnings);
  }

  private CommitResult commitClasses(UUID orgId, List<ParsedRow> rows) {
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    for (ParsedRow row : rows) {
      String code = row.get("code");
      if (code.isBlank()) {
        skipped++;
        continue;
      }
      Optional<SchoolClass> existing = schoolClassRepository.findByOrgIdAndCode(orgId, code);
      SchoolClass klass = existing.orElseGet(SchoolClass::new);
      klass.setOrgId(orgId);
      klass.setCode(code);
      klass.setName(orDefault(row.get("name"), code));
      klass.setGrade(orDefault(row.get("grade"), "未分年级"));
      klass.setStage(blankToNull(row.get("stage")));
      klass.setStudentCount(intOf(row.get("studentCount"), 0));
      klass.setStatus("active");
      schoolClassRepository.save(klass);
      if (existing.isPresent()) {
        updated++;
      } else {
        inserted++;
      }
    }
    return new CommitResult("classes", rows.size(), inserted, updated, skipped, List.of());
  }

  private CommitResult commitSubjects(UUID orgId, List<ParsedRow> rows) {
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    for (ParsedRow row : rows) {
      String code = row.get("code");
      String name = row.get("name");
      if (code.isBlank() && name.isBlank()) {
        skipped++;
        continue;
      }
      Optional<Subject> existing = !code.isBlank()
          ? subjectRepository.findByOrgIdAndCode(orgId, code)
          : subjectRepository.findByOrgIdAndName(orgId, name);
      Subject subject = existing.orElseGet(Subject::new);
      subject.setOrgId(orgId);
      subject.setCode(blankToNull(code));
      subject.setName(orDefault(name, code));
      subject.setCategory(normalizeCategory(row.get("category")));
      subject.setExamType(orDefault(row.get("examType"), "written"));
      subject.setHours(decimalOrNull(row.get("hours")));
      subject.setColor(blankToNull(row.get("color")));
      subject.setStatus("active");
      subjectRepository.save(subject);
      if (existing.isPresent()) {
        updated++;
      } else {
        inserted++;
      }
    }
    return new CommitResult("subjects", rows.size(), inserted, updated, skipped, List.of());
  }

  private CommitResult commitExams(UUID orgId, ExamTask task, List<ParsedRow> rows) {
    // Re-import is idempotent: drop this task's existing exams (exam_room/exam_class
    // cascade via FK ON DELETE CASCADE) before writing the new schedule.
    List<Exam> existing = examRepository.findByOrgIdAndExamTaskId(orgId, task.getId());
    if (!existing.isEmpty()) {
      examRepository.deleteAll(existing);
      examRepository.flush();
    }

    int inserted = 0;
    int skipped = 0;
    List<String> warnings = new ArrayList<>();
    for (ParsedRow row : rows) {
      String subjectName = row.get("subjectName");
      String roomCode = row.get("roomCode");
      LocalDate date = parseDate(row.get("examDate"));
      LocalTime start = parseTime(row.get("startTime"));
      LocalTime end = parseTime(row.get("endTime"));
      if (subjectName.isBlank() || date == null || start == null || end == null) {
        skipped++;
        warnings.add("第 " + row.rowNo() + " 行科目/日期/时间不完整，已跳过");
        continue;
      }
      Optional<Classroom> room = roomCode.isBlank()
          ? Optional.empty()
          : classroomRepository.findByOrgIdAndCode(orgId, roomCode);
      if (room.isEmpty()) {
        skipped++;
        warnings.add("第 " + row.rowNo() + " 行考场编号 '" + roomCode + "' 不在考场名册中，请先导入/维护考场后再排考");
        continue;
      }

      Exam exam = new Exam();
      exam.setOrgId(orgId);
      exam.setExamTaskId(task.getId());
      exam.setSubjectName(subjectName);
      exam.setGrade(blankToNull(row.get("grade")));
      exam.setExamDate(date);
      exam.setStartTime(start);
      exam.setEndTime(end);
      Map<String, Object> settings = new LinkedHashMap<>();
      settings.put("seatDemand", intOf(row.get("seatDemand"), Math.max(1, room.get().getExamCapacity())));
      settings.put("supervisorsPerRoom", Math.max(1, intOf(row.get("supervisorsPerRoom"), 2)));
      settings.put("roomCode", roomCode);
      exam.setSettings(settings);
      exam = examRepository.saveAndFlush(exam);

      jdbcTemplate.update(
          "INSERT INTO exam_room(exam_id, classroom_id, org_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          exam.getId(), room.get().getId(), orgId);
      inserted++;
    }

    if (inserted > 0) {
      task.setStatus("ready");
      examTaskRepository.save(task);
    }
    return new CommitResult("exams", rows.size(), inserted, 0, skipped, warnings);
  }

  private ExamTask requireTask(UUID orgId, UUID taskId) {
    if (taskId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入考试安排必须指定 taskId");
    }
    return examTaskRepository.findByOrgIdAndId(orgId, taskId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam task not found: " + taskId));
  }

  // ---- parsing helpers ----

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static int intOf(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim().replace(".0", ""));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static BigDecimal decimalOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static List<String> splitList(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String part : value.split("[,，;；/]")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private static String normalizeGender(String value) {
    if (value == null) {
      return "unknown";
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "male", "男", "m" -> "male";
      case "female", "女", "f" -> "female";
      default -> "unknown";
    };
  }

  private static String normalizeRoomType(String value) {
    if (value == null || value.isBlank()) {
      return "standard";
    }
    String v = value.trim().toLowerCase(Locale.ROOT);
    return switch (v) {
      case "standard", "corridor", "lab", "meeting", "other" -> v;
      case "normal" -> "standard";
      default -> "standard";
    };
  }

  private static String normalizeCategory(String value) {
    if (value == null || value.isBlank()) {
      return "main";
    }
    String v = value.trim().toLowerCase(Locale.ROOT);
    return switch (v) {
      case "main", "主科" -> "main";
      case "secondary", "副科" -> "secondary";
      case "activity", "术科" -> "activity";
      default -> "main";
    };
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static LocalTime parseTime(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalTime.parse(value.trim());
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
