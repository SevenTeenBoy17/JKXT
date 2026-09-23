package com.jkxt.importer.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.importer.api.ImportDtos.CommitResult;
import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.importer.service.ImportValidationService.ParsedRow;
import com.jkxt.notify.service.NotificationService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.jkxt.common.web.ApiException;
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
  private final NotificationService notificationService;

  public ImportCommitService(
      ImportValidationService validationService,
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      SchoolClassRepository schoolClassRepository,
      SubjectRepository subjectRepository,
      ExamRepository examRepository,
      ExamTaskRepository examTaskRepository,
      JdbcTemplate jdbcTemplate,
      NotificationService notificationService
  ) {
    this.validationService = validationService;
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.schoolClassRepository = schoolClassRepository;
    this.subjectRepository = subjectRepository;
    this.examRepository = examRepository;
    this.examTaskRepository = examTaskRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.notificationService = notificationService;
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
    CommitResult result = switch (canonical) {
      case "teachers" -> commitTeachers(orgId, rows);
      case "classrooms" -> commitClassrooms(orgId, rows);
      case "classes" -> commitClasses(orgId, rows);
      case "subjects" -> commitSubjects(orgId, rows);
      case "exams" -> commitExams(orgId, requireTask(orgId, taskId), rows);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的导入类型: " + kind);
    };
    // P18 批次5.3：导入完成进收件箱。此前全库仅「排班发布」一个通知生产者，
    // 导入这类关键操作静默完成，其他管理员无从得知数据已变。与 commit 同一事务：导入回滚则通知不发。
    notificationService.publishSystemNotice(orgId, taskId, "import_completed",
        "数据导入完成 · " + kindLabel(canonical),
        "共 " + result.rows() + " 行：新增 " + result.inserted() + " · 更新 " + result.updated()
            + " · 跳过 " + result.skipped()
            + (result.warnings().isEmpty() ? "" : " · " + result.warnings().size() + " 条警告"),
        null,
        Map.of("kind", canonical, "inserted", result.inserted(), "updated", result.updated(),
            "skipped", result.skipped(), "warnings", result.warnings().size()));
    return result;
  }

  private static String kindLabel(String canonical) {
    return switch (canonical) {
      case "teachers" -> "监考教师";
      case "classrooms" -> "考场";
      case "classes" -> "班级";
      case "subjects" -> "科目";
      case "exams" -> "考试安排";
      default -> canonical;
    };
  }

  private CommitResult commitTeachers(UUID orgId, List<ParsedRow> rows) {
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    List<String> warnings = new ArrayList<>();
    Set<String> seenNos = new HashSet<>();
    Set<String> seenEmails = new HashSet<>();
    for (ParsedRow row : rows) {
      String no = row.get("teacherNo");
      if (no.isBlank()) {
        skipped++;
        continue;
      }
      // P3-5: a natural key repeated inside ONE upload is a data-entry mistake, not an
      // update. Skip the later row and tell the operator instead of silently counting it.
      if (!seenNos.add(no)) {
        skipped++;
        warnings.add("第 " + row.rowNo() + " 行 teacherNo " + no + " 与文件内前序行重复，已跳过");
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
      // email is UNIQUE(org_id, email) but NOT the natural key (teacherNo is). A duplicate
      // email — repeated inside the file, or already owned by a *different* teacher in the org —
      // must not crash the whole upload with a raw 500. Drop the colliding email (NULLs are
      // exempt from the unique constraint) and warn, so the teacher still imports by工号.
      teacher.setEmail(resolveUniqueEmail(orgId, no, blankToNull(row.get("email")), row.rowNo(), seenEmails, warnings));
      // The teacher template intentionally has no status column. A partial re-import may refresh
      // names/contact data, but it must not silently reactivate a teacher whom an administrator
      // already marked disabled/left. Only brand-new rows receive the default active status.
      if (existing.isEmpty()) {
        teacher.setStatus("active");
      }
      // 班主任标记 + 所带班级(均可选，驱动"班主任不监本班")。仅在本行提供了相关列时才更新，避免
      // 部分上传(无这两列)误清已有绑定。所带班级按班级编号解析，班级尚未导入则告警留空——可先导
      // 入班级、或改用班级表的"班主任工号"列建立同样的绑定(buildFixtureFromTask 取两者并集)。
      String homeroomClassCode = blankToNull(row.get("homeroomClass"));
      String isHomeroomRaw = blankToNull(row.get("isHomeroom"));
      if (homeroomClassCode != null || isHomeroomRaw != null) {
        boolean isHomeroom = parseBool(isHomeroomRaw);
        UUID homeroomClassId = null;
        if (homeroomClassCode != null) {
          // 声明了"所带班级"即视为班主任。原始 code 始终持久化，使绑定与导入顺序无关：
          // 若班级尚未导入则 homeroom_class_id 暂为 null，commitClasses 会在该班级到达时按 code 回填。
          isHomeroom = true;
          Optional<SchoolClass> klass = schoolClassRepository.findByOrgIdAndCode(orgId, homeroomClassCode);
          if (klass.isPresent()) {
            homeroomClassId = klass.get().getId();
          } else {
            warnings.add("第 " + row.rowNo() + " 行所带班级 " + homeroomClassCode
                + " 尚未导入，已记录编码，将在导入该班级后自动绑定");
          }
        }
        teacher.setHomeroom(isHomeroom);
        teacher.setHomeroomClassId(homeroomClassId);
        teacher.setHomeroomClassCode(homeroomClassCode);
      }
      // saveAndFlush: the raw JDBC teacher_subject insert below references this row's id, so
      // the parent must be flushed first (same ordering the exam_room path relies on).
      Teacher saved = teacherRepository.saveAndFlush(teacher);
      // P2-1: persist the 任教学科 column into teacher_subject (the CRUD path writes the
      // same join table); unknown subject names warn-and-skip rather than failing the row.
      linkSubjects("teacher_subject", "teacher_id", orgId, saved.getId(), row, warnings);
      if (existing.isPresent()) {
        updated++;
      } else {
        inserted++;
      }
    }
    return new CommitResult("teachers", rows.size(), inserted, updated, skipped, warnings);
  }

  /**
   * Resolve an email that is safe to persist under {@code UNIQUE(org_id, email)}. Returns the
   * email when it is free, or {@code null} (which the unique constraint exempts) plus a warning
   * when it collides — either with an earlier row in this same upload or with a different teacher
   * already in the org. The teacher itself is still imported by its 工号 natural key; only the
   * conflicting email is dropped, so one bad email never aborts the whole roster import.
   */
  private String resolveUniqueEmail(
      UUID orgId,
      String teacherNo,
      String email,
      int rowNo,
      Set<String> seenEmails,
      List<String> warnings
  ) {
    if (email == null) {
      return null;
    }
    String key = email.toLowerCase(Locale.ROOT);
    if (!seenEmails.add(key)) {
      warnings.add("第 " + rowNo + " 行邮箱 " + email + " 与文件内前序行重复，已清空该邮箱（教师仍按工号导入）");
      return null;
    }
    Optional<Teacher> owner = teacherRepository.findByOrgIdAndEmail(orgId, email);
    if (owner.isPresent() && !teacherNo.equals(owner.get().getTeacherNo())) {
      warnings.add("第 " + rowNo + " 行邮箱 " + email + " 已被工号 "
          + owner.get().getTeacherNo() + " 使用，已清空该邮箱（教师仍按工号导入）");
      return null;
    }
    return email;
  }

  private CommitResult commitClassrooms(UUID orgId, List<ParsedRow> rows) {
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    List<String> warnings = new ArrayList<>();
    Set<String> seenCodes = new HashSet<>();
    for (ParsedRow row : rows) {
      String code = row.get("code");
      if (code.isBlank()) {
        skipped++;
        continue;
      }
      // 文件内重复考场编号 — 跳过后行并告警（与教师/班级导入的去重口径一致，避免静默 last-write-wins）。
      if (!seenCodes.add(code)) {
        skipped++;
        warnings.add("第 " + row.rowNo() + " 行考场编号 " + code + " 与文件内前序行重复，已跳过");
        continue;
      }
      Optional<Classroom> existing = classroomRepository.findByOrgIdAndCode(orgId, code);
      Classroom classroom = existing.orElseGet(Classroom::new);
      classroom.setOrgId(orgId);
      classroom.setCode(code);
      classroom.setName(orDefault(row.get("name"), code));
      classroom.setBuilding(blankToNull(row.get("building")));
      classroom.setFloor(blankToNull(row.get("floor")));
      classroom.setCapacity(intOfWarned(row.get("capacity"), 0, "容量", row.rowNo(), warnings));
      classroom.setExamCapacity(intOfWarned(row.get("examCapacity"), 0, "考试容量", row.rowNo(), warnings));
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
    List<String> warnings = new ArrayList<>();
    Set<String> seenCodes = new HashSet<>();
    for (ParsedRow row : rows) {
      String code = row.get("code");
      if (code.isBlank()) {
        skipped++;
        continue;
      }
      // P3-5: in-file duplicate班级编号 — skip the later row and warn the operator.
      if (!seenCodes.add(code)) {
        skipped++;
        warnings.add("第 " + row.rowNo() + " 行班级编号 " + code + " 与文件内前序行重复，已跳过");
        continue;
      }
      Optional<SchoolClass> existing = schoolClassRepository.findByOrgIdAndCode(orgId, code);
      SchoolClass klass = existing.orElseGet(SchoolClass::new);
      klass.setOrgId(orgId);
      klass.setCode(code);
      klass.setName(orDefault(row.get("name"), code));
      klass.setGrade(orDefault(row.get("grade"), "未分年级"));
      klass.setStage(blankToNull(row.get("stage")));
      klass.setStudentCount(intOfWarned(row.get("studentCount"), 0, "学生数", row.rowNo(), warnings));
      klass.setStatus("active");
      // 班主任工号(可选)：建立 班级→班主任 绑定，是"班主任不监本班"的另一条数据来源(与教师表的
      // "所带班级"等价，取并集)。仅在本行提供该列时更新，避免部分上传误清已有班主任。
      String homeroomTeacherNo = blankToNull(row.get("homeroomTeacherNo"));
      if (homeroomTeacherNo != null) {
        Optional<Teacher> homeroom = teacherRepository.findByOrgIdAndTeacherNo(orgId, homeroomTeacherNo);
        if (homeroom.isPresent()) {
          klass.setHomeroomTeacherId(homeroom.get().getId());
        } else {
          warnings.add("第 " + row.rowNo() + " 行班主任工号 " + homeroomTeacherNo + " 不在教师名册中，已忽略");
        }
      }
      // saveAndFlush: the raw JDBC school_class_subject insert below references this row's id.
      SchoolClass saved = schoolClassRepository.saveAndFlush(klass);
      // 导入顺序无关的班主任回填：若有教师此前在"所带班级"列声明了本班编码、却因班级当时未导入而未绑定，
      // 现在补上 homeroom_class_id（修复"教师先于班级导入→班主任绑定丢失→班主任被排进本班"的缺陷）。
      for (Teacher pending : teacherRepository.findByOrgIdAndHomeroomClassCodeAndHomeroomClassIdIsNull(orgId, code)) {
        pending.setHomeroomClassId(saved.getId());
        pending.setHomeroom(true);
        teacherRepository.save(pending);
      }
      // P2-1: persist the 开设科目 column into school_class_subject (same join the CRUD
      // path writes); unknown subject names warn-and-skip rather than failing the row.
      linkSubjects("school_class_subject", "class_id", orgId, saved.getId(), row, warnings);
      if (existing.isPresent()) {
        updated++;
      } else {
        inserted++;
      }
    }
    return new CommitResult("classes", rows.size(), inserted, updated, skipped, warnings);
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
    int inserted = 0;
    int skipped = 0;
    List<String> warnings = new ArrayList<>();

    // 迭代9 T5-P0-A 两阶段守卫："守卫先于破坏"。先只读盘点能产出有效考试的行数(科目/日期/时间完整且
    // 考场存在)，确认有有效数据后才销毁旧排考。否则一次列错位/全错的重导入会静默销毁已发布排班却无新
    // 数据补上(数据丢失，原代码在删旧之后才逐行校验)。阈值 0.5 即"有效行须过半"，置 1 退回旧行为。
    int plannedValid = 0;
    int nonBlankRows = 0;
    for (ParsedRow row : rows) {
      boolean rowHasAny = !row.get("subjectName").isBlank() || !row.get("roomCode").isBlank()
          || !row.get("examDate").isBlank() || !row.get("startTime").isBlank() || !row.get("endTime").isBlank();
      if (rowHasAny) {
        nonBlankRows++;
      }
      if (row.get("subjectName").isBlank() || parseDate(row.get("examDate")) == null
          || parseTime(row.get("startTime")) == null || parseTime(row.get("endTime")) == null) {
        continue;
      }
      String roomCode = row.get("roomCode");
      if (roomCode.isBlank() || classroomRepository.findByOrgIdAndCode(orgId, roomCode).isEmpty()) {
        continue;
      }
      plannedValid++;
    }
    if (plannedValid == 0) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "EXAM_IMPORT_NO_VALID_ROW",
          "考试名册没有可导入的有效行（每行需科目/日期/起止时间完整，且考场编号已在考场名册中），原有考试与排班未改动。请核对文件后重试。");
    }
    if (nonBlankRows > 0 && plannedValid * 2 < nonBlankRows) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "EXAM_IMPORT_TOO_MANY_SKIPPED",
          "考试名册有效行占比过低（" + plannedValid + "/" + nonBlankRows + " 有效），疑似列错位或考场未先导入，原有考试与排班未改动。请核对后重试。");
    }

    List<Exam> existing = examRepository.findByOrgIdAndExamTaskId(orgId, task.getId());
    if (!existing.isEmpty()) {
      // 重新导入考试名册：旧考场/考试经 exam_id 级联清空，原有监考排班随之失效。统计将丢失的排班数并
      // 显式告警（此前静默丢失），同时清掉本任务的排程 run（连带 schedule_assignment/revision，均 ON
      // DELETE CASCADE），避免遗留"已成功但 0 分配"的陈旧 run；任务回到未排程态，需重新排考。
      Integer priorAssignments = jdbcTemplate.queryForObject(
          "SELECT count(*) FROM schedule_assignment a JOIN schedule_run r ON a.schedule_run_id = r.id "
              + "WHERE r.org_id = ? AND r.exam_task_id = ?",
          Integer.class, orgId, task.getId());
      examRepository.deleteAll(existing);
      examRepository.flush();
      jdbcTemplate.update("DELETE FROM schedule_run WHERE org_id = ? AND exam_task_id = ?", orgId, task.getId());
      if (priorAssignments != null && priorAssignments > 0) {
        warnings.add("重新导入考试名册已清空原有 " + priorAssignments + " 条监考排班，请重新排考。");
        task.setStatus("draft");
        task.setPublishedAt(null);
        examTaskRepository.save(task);
      }
    }
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
      // 迭代10 P2: 回填 exam.subject_id(学科名→编码解析)。此前恒为 null → 科目列表"考试数"永远 0、
      // 报表 subject 关联取不到。解析不到时留空(不阻断)。exam 后续会被重新赋值故不能用 lambda 捕获。
      if (subjectName != null && !subjectName.isBlank()) {
        Optional<Subject> examSubject = subjectRepository.findByOrgIdAndName(orgId, subjectName)
            .or(() -> subjectRepository.findByOrgIdAndCode(orgId, subjectName));
        if (examSubject.isPresent()) {
          exam.setSubjectId(examSubject.get().getId());
        }
      }
      exam.setGrade(blankToNull(row.get("grade")));
      exam.setExamDate(date);
      exam.setStartTime(start);
      exam.setEndTime(end);
      Map<String, Object> settings = new LinkedHashMap<>();
      settings.put("seatDemand", intOfWarned(row.get("seatDemand"),
          Math.max(1, room.get().getExamCapacity()), "人数", row.rowNo(), warnings));
      settings.put("supervisorsPerRoom", Math.max(1,
          intOfWarned(row.get("supervisorsPerRoom"), 2, "监考人数", row.rowNo(), warnings)));
      settings.put("roomCode", roomCode);
      exam.setSettings(settings);
      exam = examRepository.saveAndFlush(exam);

      jdbcTemplate.update(
          "INSERT INTO exam_room(exam_id, classroom_id, org_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
          exam.getId(), room.get().getId(), orgId);
      // 班级绑定(可选)：该场考试坐哪个/哪些班(逗号分隔班级编号) → exam_class，驱动"班主任不监本班"。
      // 缺省或班级不存在则跳过该绑定(告警)，不阻断排考。
      for (String classCode : splitList(row.get("className"))) {
        Optional<SchoolClass> klass = schoolClassRepository.findByOrgIdAndCode(orgId, classCode);
        if (klass.isEmpty()) {
          // 容错：用户把"班级名称"误填进"班级"列时，按名称匹配，保住"班主任不监本班"所需的 exam_class 绑定，
          // 避免约束因 编号/名称 不一致而静默失去数据面（迭代7 Bug）。
          klass = schoolClassRepository.findFirstByOrgIdAndName(orgId, classCode);
        }
        if (klass.isPresent()) {
          jdbcTemplate.update(
              "INSERT INTO exam_class(exam_id, class_id, org_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
              exam.getId(), klass.get().getId(), orgId);
        } else {
          warnings.add("第 " + row.rowNo() + " 行班级 " + classCode + " 不在班级名册中，已忽略该班级绑定");
        }
      }
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

  /**
   * Persist the comma-separated 任教学科 / 开设科目 column into the owner's subject join table
   * ({@code teacher_subject} / {@code school_class_subject}) — the same tables the CRUD path
   * writes via {@code MasterDataService.replaceSubjects}. Each name is matched by subject name
   * first, then code, within the org. A named subject that does not exist in the org does not
   * fail the row: it adds a {@link CommitResult} warning and is skipped. When the column is
   * absent for a row, existing links are left untouched (no accidental wipe on partial uploads).
   */
  private void linkSubjects(
      String table,
      String ownerColumn,
      UUID orgId,
      UUID ownerId,
      ParsedRow row,
      List<String> warnings
  ) {
    String raw = row.get("subjects");
    if (raw == null || raw.isBlank()) {
      return;
    }
    // 迭代10 P1: 先解析、再替换。此前无条件先 DELETE 再逐名插入 —— 若本行学科名全部解析失败(错别字，
    // 或学科主数据尚未先导入)，DELETE 已把该 owner 既有关联清空、循环又一条都插不进去，结果 owner 关联
    // 归零，而提交仍报成功，静默破坏 subject_avoid/任课不监本班 数据链。改为：先收集能解析到的学科 id；
    // 只有解析结果非空才替换该 owner 的关联集；若全部无法解析则保持既有关联不变(与"列缺失=不动"同一契约)
    // 并逐条告警。
    List<UUID> resolvedSubjectIds = new ArrayList<>();
    for (String name : splitList(raw)) {
      Optional<Subject> subject = subjectRepository.findByOrgIdAndName(orgId, name)
          .or(() -> subjectRepository.findByOrgIdAndCode(orgId, name));
      if (subject.isEmpty()) {
        warnings.add("第 " + row.rowNo() + " 行任教学科 " + name + " 未找到，已跳过该关联");
        continue;
      }
      resolvedSubjectIds.add(subject.get().getId());
    }
    if (resolvedSubjectIds.isEmpty()) {
      return;
    }
    // Re-import is idempotent for this owner: replace its association set with the file's resolved set.
    jdbcTemplate.update(
        "DELETE FROM " + table + " WHERE org_id = ? AND " + ownerColumn + " = ?", orgId, ownerId);
    for (UUID subjectId : resolvedSubjectIds) {
      jdbcTemplate.update(
          "INSERT INTO " + table + " (" + ownerColumn + ", subject_id, org_id) VALUES (?, ?, ?) "
              + "ON CONFLICT DO NOTHING",
          ownerId, subjectId, orgId);
    }
  }

  // ---- parsing helpers ----

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Parse a Chinese/English truthy cell (是/true/y/yes/1/√/班主任) into a boolean. */
  private static boolean parseBool(String value) {
    if (value == null) {
      return false;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "是", "true", "y", "yes", "1", "√", "班主任", "homeroom" -> true;
      default -> false;
    };
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static int intOf(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return parseIntegral(value);
    } catch (NumberFormatException | ArithmeticException ex) {
      return fallback;
    }
  }

  /**
   * 迭代10 P2: 容忍 Excel 把整数渲染成 "5.0" 这类小数尾零，但拒绝真正的非整数(如 "10.05")。此前用字面量
   * {@code replace(".0","")} 会把 "10.05"→"105"、"1.02"→"12" 静默放大 10 倍且校验也一并放行。
   * {@link BigDecimal#intValueExact()} 对含非零小数部分或越界的值抛 {@link ArithmeticException}，由调用方
   * 按告警/默认值处理；对 "5.0"/"5" 返回 5。
   */
  private static int parseIntegral(String value) {
    return new BigDecimal(value.trim()).intValueExact();
  }

  /**
   * 同 {@link #intOf}，但当字段非空却无法解析为数字时记一条告警——使垃圾文本（如 容量='abc'、人数='很多'）
   * 被强转为默认值这件事对教务可见，而非静默落库（迭代7 Bug：数字字段文本静默降级且 warnings 为空）。
   */
  private static int intOfWarned(String value, int fallback, String field, int rowNo, List<String> warnings) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return parseIntegral(value);
    } catch (NumberFormatException | ArithmeticException ex) {
      warnings.add("第 " + rowNo + " 行" + field + " '" + value.trim()
          + "' 不是有效数字，已按默认值 " + fallback + " 处理，请核对");
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
