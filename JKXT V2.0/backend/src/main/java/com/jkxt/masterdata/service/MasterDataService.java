package com.jkxt.masterdata.service;

import com.jkxt.audit.domain.AuditLog;
import com.jkxt.audit.repo.AuditLogRepository;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.masterdata.api.MasterDataDtos.ClassroomDto;
import com.jkxt.masterdata.api.MasterDataDtos.ClassroomRequest;
import com.jkxt.masterdata.api.MasterDataDtos.SchoolClassDto;
import com.jkxt.masterdata.api.MasterDataDtos.SchoolClassRequest;
import com.jkxt.masterdata.api.MasterDataDtos.SubjectDto;
import com.jkxt.masterdata.api.MasterDataDtos.SubjectRequest;
import com.jkxt.masterdata.api.MasterDataDtos.TeacherDto;
import com.jkxt.masterdata.api.MasterDataDtos.TeacherRequest;
import com.jkxt.masterdata.api.MasterDataDtos.TeacherWindow;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.SchoolClass;
import com.jkxt.masterdata.domain.Subject;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.SchoolClassRepository;
import com.jkxt.masterdata.repo.SubjectRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import com.jkxt.security.auth.SessionPrincipal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class MasterDataService {

  private static final List<String> DEFAULT_TEACHER_QUALIFICATIONS = List.of("INVIGILATOR");
  private static final Set<String> ALLOWED_TEACHER_QUALIFICATIONS = Set.of("INVIGILATOR", "CHIEF");
  private static final int DEFAULT_MAX_DAILY_SESSIONS = 4;

  private final TeacherRepository teacherRepository;
  private final ClassroomRepository classroomRepository;
  private final SchoolClassRepository schoolClassRepository;
  private final SubjectRepository subjectRepository;
  private final JdbcTemplate jdbcTemplate;
  private final AuditLogRepository auditLogRepository;
  private final com.jkxt.demo.DemoImportLimits demoLimits;

  public MasterDataService(
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      SchoolClassRepository schoolClassRepository,
      SubjectRepository subjectRepository,
      JdbcTemplate jdbcTemplate,
      AuditLogRepository auditLogRepository,
      com.jkxt.demo.DemoImportLimits demoLimits
  ) {
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.schoolClassRepository = schoolClassRepository;
    this.subjectRepository = subjectRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.auditLogRepository = auditLogRepository;
    this.demoLimits = Objects.requireNonNull(demoLimits);
  }

  @Transactional(readOnly = true)
  public List<TeacherDto> listTeachers(String q, String grade, String subject, Boolean homeroom, String status) {
    UUID orgId = TenantContext.getRequired();
    // 迭代12 P2: allowanceUnit 是每 org 的常量,hoist 出循环只查一次(此前每教师重复查同一行)。
    double allowanceUnit = allowanceUnit(orgId);
    return teacherRepository.findByOrgIdOrderByTeacherNoAsc(orgId).stream()
        .map(teacher -> toTeacherDto(orgId, teacher, allowanceUnit))
        .filter(dto -> matchesTeacher(dto, q, grade, subject, homeroom, status))
        .toList();
  }

  @Transactional(readOnly = true)
  public TeacherDto getTeacher(UUID id) {
    UUID orgId = TenantContext.getRequired();
    return toTeacherDto(orgId, teacher(orgId, id));
  }

  public TeacherDto createTeacher(TeacherRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"teachers",1,()->createTeacherLocked(request));
  }
  private TeacherDto createTeacherLocked(TeacherRequest request) {
    UUID orgId = TenantContext.getRequired();
    requireUniqueTeacherNo(orgId, null, request.teacherNo());
    Teacher teacher = new Teacher();
    teacher.setOrgId(orgId);
    applyTeacher(orgId, teacher, request);
    Teacher saved = teacherRepository.saveAndFlush(teacher);
    replaceSubjects("teacher_subject", "teacher_id", orgId, saved.getId(), request.subjectIds());
    audit(orgId, "TEACHER_CREATED", "teacher", saved.getId(),
        Map.of("teacherNo", String.valueOf(saved.getTeacherNo()), "name", String.valueOf(saved.getName())));
    return toTeacherDto(orgId, saved);
  }

  public TeacherDto updateTeacher(UUID id, TeacherRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"teachers",0,()->updateTeacherLocked(id,request));
  }
  private TeacherDto updateTeacherLocked(UUID id, TeacherRequest request) {
    UUID orgId = TenantContext.getRequired();
    Teacher teacher = teacher(orgId, id);
    requireUniqueTeacherNo(orgId, id, request.teacherNo());
    applyTeacher(orgId, teacher, request);
    Teacher saved = teacherRepository.saveAndFlush(teacher);
    if (request.subjectIds() != null) {
      replaceSubjects("teacher_subject", "teacher_id", orgId, saved.getId(), request.subjectIds());
    }
    audit(orgId, "TEACHER_UPDATED", "teacher", saved.getId(),
        Map.of("teacherNo", String.valueOf(saved.getTeacherNo()), "name", String.valueOf(saved.getName())));
    return toTeacherDto(orgId, saved);
  }

  public void deleteTeacher(UUID id) {
    var captured=demoLimits.capture();
    demoLimits.fenced(captured,()->{deleteTeacherLocked(id);return null;});
  }
  private void deleteTeacherLocked(UUID id) {
    UUID orgId = TenantContext.getRequired();
    Teacher teacher = teacher(orgId, id);
    requireNotReferenced(orgId, "schedule_assignment", "teacher_id", id, "该教师已被排进监考结果");
    jdbcTemplate.update(
        "update school_class set homeroom_teacher_id = null where org_id = ? and homeroom_teacher_id = ?",
        orgId,
        id
    );
    jdbcTemplate.update("delete from teacher_subject where org_id = ? and teacher_id = ?", orgId, id);
    teacherRepository.delete(teacher);
    audit(orgId, "TEACHER_DELETED", "teacher", id,
        Map.of("teacherNo", String.valueOf(teacher.getTeacherNo()), "name", String.valueOf(teacher.getName())));
  }

  @Transactional(readOnly = true)
  public List<ClassroomDto> listClassrooms(String q, String status, String roomType) {
    UUID orgId = TenantContext.getRequired();
    return classroomRepository.findByOrgIdOrderByCodeAsc(orgId).stream()
        .map(this::toClassroomDto)
        .filter(dto -> matchesClassroom(dto, q, status, roomType))
        .toList();
  }

  @Transactional(readOnly = true)
  public ClassroomDto getClassroom(UUID id) {
    UUID orgId = TenantContext.getRequired();
    return toClassroomDto(classroom(orgId, id));
  }

  public ClassroomDto createClassroom(ClassroomRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"classrooms",1,()->createClassroomLocked(request));
  }
  private ClassroomDto createClassroomLocked(ClassroomRequest request) {
    UUID orgId = TenantContext.getRequired();
    requireUniqueClassroomCode(orgId, null, request.code());
    Classroom classroom = new Classroom();
    classroom.setOrgId(orgId);
    applyClassroom(classroom, request);
    Classroom saved = classroomRepository.saveAndFlush(classroom);
    audit(orgId, "CLASSROOM_CREATED", "classroom", saved.getId(),
        Map.of("code", String.valueOf(saved.getCode()), "name", String.valueOf(saved.getName())));
    return toClassroomDto(saved);
  }

  public ClassroomDto updateClassroom(UUID id, ClassroomRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"classrooms",0,()->updateClassroomLocked(id,request));
  }
  private ClassroomDto updateClassroomLocked(UUID id, ClassroomRequest request) {
    UUID orgId = TenantContext.getRequired();
    Classroom classroom = classroom(orgId, id);
    requireUniqueClassroomCode(orgId, id, request.code());
    applyClassroom(classroom, request);
    Classroom saved = classroomRepository.saveAndFlush(classroom);
    audit(orgId, "CLASSROOM_UPDATED", "classroom", saved.getId(),
        Map.of("code", String.valueOf(saved.getCode()), "name", String.valueOf(saved.getName())));
    return toClassroomDto(saved);
  }

  public void deleteClassroom(UUID id) {
    var captured=demoLimits.capture();
    demoLimits.fenced(captured,()->{deleteClassroomLocked(id);return null;});
  }
  private void deleteClassroomLocked(UUID id) {
    UUID orgId = TenantContext.getRequired();
    Classroom classroom = classroom(orgId, id);
    requireNotReferenced(orgId, "exam_room", "classroom_id", id, "该考场已被考试占用");
    jdbcTemplate.update(
        "update school_class set default_classroom_id = null where org_id = ? and default_classroom_id = ?",
        orgId,
        id
    );
    classroomRepository.delete(classroom);
    audit(orgId, "CLASSROOM_DELETED", "classroom", id,
        Map.of("code", String.valueOf(classroom.getCode()), "name", String.valueOf(classroom.getName())));
  }

  @Transactional(readOnly = true)
  public List<SchoolClassDto> listClasses(String q, String grade, String status) {
    UUID orgId = TenantContext.getRequired();
    // 迭代12 P2(性能): 与上面 hoist allowanceUnit 同一范式——班主任名/默认考场名/科目数此前逐班级各查一次
    // (N+1)，改为整页一次性批量取回后在内存里按 id 命中。必须在 filter 之前批量：matchesSchoolClass 要按
    // homeroom(班主任名)过滤，该字段正是批量查询的产物。
    List<SchoolClass> classes = schoolClassRepository.findByOrgIdOrderByGradeAscCodeAsc(orgId);
    SchoolClassRefs refs = schoolClassRefs(orgId, classes);
    return classes.stream()
        .map(schoolClass -> toSchoolClassDto(schoolClass, refs))
        .filter(dto -> matchesSchoolClass(dto, q, grade, status))
        .toList();
  }

  @Transactional(readOnly = true)
  public SchoolClassDto getClass(UUID id) {
    UUID orgId = TenantContext.getRequired();
    return toSchoolClassDto(orgId, schoolClass(orgId, id));
  }

  public SchoolClassDto createClass(SchoolClassRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"classes",1,()->createClassLocked(request));
  }
  private SchoolClassDto createClassLocked(SchoolClassRequest request) {
    UUID orgId = TenantContext.getRequired();
    requireUniqueClassCode(orgId, null, request.code());
    SchoolClass schoolClass = new SchoolClass();
    schoolClass.setOrgId(orgId);
    applySchoolClass(orgId, schoolClass, request);
    SchoolClass saved = schoolClassRepository.saveAndFlush(schoolClass);
    replaceSubjects("school_class_subject", "class_id", orgId, saved.getId(), request.subjectIds());
    audit(orgId, "CLASS_CREATED", "school_class", saved.getId(),
        Map.of("code", String.valueOf(saved.getCode()), "name", String.valueOf(saved.getName())));
    return toSchoolClassDto(orgId, saved);
  }

  public SchoolClassDto updateClass(UUID id, SchoolClassRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"classes",0,()->updateClassLocked(id,request));
  }
  private SchoolClassDto updateClassLocked(UUID id, SchoolClassRequest request) {
    UUID orgId = TenantContext.getRequired();
    SchoolClass schoolClass = schoolClass(orgId, id);
    requireUniqueClassCode(orgId, id, request.code());
    applySchoolClass(orgId, schoolClass, request);
    SchoolClass saved = schoolClassRepository.saveAndFlush(schoolClass);
    if (request.subjectIds() != null) {
      replaceSubjects("school_class_subject", "class_id", orgId, saved.getId(), request.subjectIds());
    }
    audit(orgId, "CLASS_UPDATED", "school_class", saved.getId(),
        Map.of("code", String.valueOf(saved.getCode()), "name", String.valueOf(saved.getName())));
    return toSchoolClassDto(orgId, saved);
  }

  public void deleteClass(UUID id) {
    var captured=demoLimits.capture();
    demoLimits.fenced(captured,()->{deleteClassLocked(id);return null;});
  }
  private void deleteClassLocked(UUID id) {
    UUID orgId = TenantContext.getRequired();
    SchoolClass schoolClass = schoolClass(orgId, id);
    requireNotReferenced(orgId, "exam_class", "class_id", id, "该班级已被考试绑定");
    // 迭代11 P2: 删除班级时,除清空 homeroom_class_id 外,也清空 homeroom_class_code(V12 回填真源)——
    // 否则重新导入同 code 的班级会按陈旧 code 把班主任重新绑回一个已删除又重建的班级。按 id(已绑定)或
    // code(导入待回填)匹配,彻底断开该班级的班主任绑定线索。
    jdbcTemplate.update(
        "update teacher set homeroom_class_id = null, is_homeroom = false, homeroom_class_code = null "
            + "where org_id = ? and (homeroom_class_id = ? or homeroom_class_code = ?)",
        orgId,
        id,
        schoolClass.getCode()
    );
    jdbcTemplate.update("delete from school_class_subject where org_id = ? and class_id = ?", orgId, id);
    schoolClassRepository.delete(schoolClass);
    audit(orgId, "CLASS_DELETED", "school_class", id,
        Map.of("code", String.valueOf(schoolClass.getCode()), "name", String.valueOf(schoolClass.getName())));
  }

  @Transactional(readOnly = true)
  public List<SubjectDto> listSubjects(String q, String category, String status) {
    UUID orgId = TenantContext.getRequired();
    // 迭代12 P2(性能): grades/examCount 此前逐科目各查一次(N+1)，改为整页两次批量查询。
    // 保持"先 map 再 filter"的既有顺序：过滤前后 DTO 集合与旧实现逐字段一致。
    List<Subject> subjects = subjectRepository.findByOrgIdOrderByCodeAscNameAsc(orgId);
    SubjectUsage usage = subjectUsage(orgId, subjects.stream().map(Subject::getId).toList());
    return subjects.stream()
        .map(subject -> toSubjectDto(subject, usage))
        .filter(dto -> matchesSubject(dto, q, category, status))
        .toList();
  }

  @Transactional(readOnly = true)
  public SubjectDto getSubject(UUID id) {
    UUID orgId = TenantContext.getRequired();
    return toSubjectDto(orgId, subject(orgId, id));
  }

  public SubjectDto createSubject(SubjectRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"subjects",1,()->createSubjectLocked(request));
  }
  private SubjectDto createSubjectLocked(SubjectRequest request) {
    UUID orgId = TenantContext.getRequired();
    requireUniqueSubject(orgId, null, request.code(), request.name());
    Subject subject = new Subject();
    subject.setOrgId(orgId);
    applySubject(subject, request);
    Subject saved = subjectRepository.saveAndFlush(subject);
    audit(orgId, "SUBJECT_CREATED", "subject", saved.getId(),
        Map.of("code", String.valueOf(saved.getCode()), "name", String.valueOf(saved.getName())));
    return toSubjectDto(orgId, saved);
  }

  public SubjectDto updateSubject(UUID id, SubjectRequest request) {
    var captured=demoLimits.capture();
    return demoLimits.mutate(captured,"subjects",0,()->updateSubjectLocked(id,request));
  }
  private SubjectDto updateSubjectLocked(UUID id, SubjectRequest request) {
    UUID orgId = TenantContext.getRequired();
    Subject subject = subject(orgId, id);
    requireUniqueSubject(orgId, id, request.code(), request.name());
    applySubject(subject, request);
    Subject saved = subjectRepository.saveAndFlush(subject);
    audit(orgId, "SUBJECT_UPDATED", "subject", saved.getId(),
        Map.of("code", String.valueOf(saved.getCode()), "name", String.valueOf(saved.getName())));
    return toSubjectDto(orgId, saved);
  }

  public void deleteSubject(UUID id) {
    var captured=demoLimits.capture();
    demoLimits.fenced(captured,()->{deleteSubjectLocked(id);return null;});
  }
  private void deleteSubjectLocked(UUID id) {
    UUID orgId = TenantContext.getRequired();
    Subject subject = subject(orgId, id);
    // 迭代10 P2: 与 deleteTeacher/deleteClassroom/deleteClass 一致，删除前校验考试引用。此前缺此守卫，
    // 删除被考试引用的科目会直接撞 exam.subject_id 外键 → 被统一映射成误导的 400"导入字段超长/重复"，
    // 而应是贴切的 409。
    requireNotReferenced(orgId, "exam", "subject_id", id, "该科目已被考试引用");
    jdbcTemplate.update("delete from teacher_subject where org_id = ? and subject_id = ?", orgId, id);
    jdbcTemplate.update("delete from school_class_subject where org_id = ? and subject_id = ?", orgId, id);
    subjectRepository.delete(subject);
    audit(orgId, "SUBJECT_DELETED", "subject", id,
        Map.of("code", String.valueOf(subject.getCode()), "name", String.valueOf(subject.getName())));
  }

  private void applyTeacher(UUID orgId, Teacher teacher, TeacherRequest request) {
    teacher.setTeacherNo(required(request.teacherNo(), "teacherNo"));
    teacher.setName(required(request.name(), "name"));
    teacher.setGender(normalizeGender(request.gender()));
    teacher.setGrade(trimToNull(request.grade()));
    teacher.setPhone(trimToNull(request.phone()));
    teacher.setEmail(trimToNull(request.email()));
    teacher.setStatus(normalizeTeacherStatus(request.status()));
    // Teacher updates are exposed as PUT, so nullable scalar profile fields follow replacement
    // semantics: an explicit JSON null clears the persisted value. The previous null-as-no-op
    // behaviour made age/position impossible to remove after they had once been set.
    teacher.setAge(request.age());
    teacher.setPosition(trimToNull(request.position()));
    if (request.leaveWindows() != null) {
      teacher.setLeaveWindows(encodeWindows(request.leaveWindows(), "leaveWindows"));
    }
    if (request.functionalAccommodations() != null) {
      teacher.setFunctionalAccommodations(normalizeAccommodations(request.functionalAccommodations()));
    }
    if (request.dutyWindows() != null) {
      teacher.setDutyWindows(encodeWindows(request.dutyWindows(), "dutyWindows"));
    }
    // The teacher endpoint is PUT-based: the invigilation profile is replaced as a unit.
    // Missing/null optional values therefore reset to safe defaults instead of retaining stale
    // qualifications from a previous profile.
    teacher.setQualifications(normalizeQualifications(request.qualifications()));
    teacher.setQualificationExpiry(request.qualificationExpiry());
    teacher.setMaxDailySessions(normalizeMaxDailySessions(request.maxDailySessions()));
    teacher.setPreferredCampus(normalizePreferredCampus(request.preferredCampus()));
    teacher.setHomeroom(Boolean.TRUE.equals(request.homeroom()));
    // 迭代9 T4-P0-5: homeroomClassId 必须校验归属本 org，否则租户 A 可写入租户 B 的 class UUID
    // (跨租户引用注入)。与 applySchoolClass 的 ownedTeacherId/ownedClassroomId 同一防御范式。
    UUID previousHomeroomId = teacher.getHomeroomClassId();
    UUID nextHomeroomId = ownedSchoolClassId(orgId, request.homeroomClassId());
    teacher.setHomeroomClassId(nextHomeroomId);
    // 迭代11 P2: 若管理员通过 CRUD 显式变更/清空了已有的班主任绑定，同步作废导入侧的 homeroom_class_code
    // (V12 导入顺序无关回填的真源)。否则后续重新导入班级会按陈旧 code 把已被解绑的班主任重新绑回，
    // 重新引入"班主任监本班"。仅在"原本有绑定且现在变了"时作废——原本为 null(导入顺序待回填)不触发，
    // 从而不破坏导入顺序无关特性。
    if (previousHomeroomId != null && !previousHomeroomId.equals(nextHomeroomId)) {
      teacher.setHomeroomClassCode(null);
    }
  }

  private void applyClassroom(Classroom classroom, ClassroomRequest request) {
    classroom.setCode(required(request.code(), "code"));
    classroom.setName(required(request.name(), "name"));
    classroom.setBuilding(trimToNull(request.building()));
    classroom.setFloor(trimToNull(request.floor()));
    classroom.setCapacity(defaultInt(request.capacity()));
    classroom.setExamCapacity(defaultInt(request.examCapacity()));
    classroom.setRoomType(normalizeRoomType(request.roomType()));
    classroom.setStatus(normalizeClassroomStatus(request.status()));
    classroom.setFeatures(request.features());
  }

  private void applySchoolClass(UUID orgId, SchoolClass schoolClass, SchoolClassRequest request) {
    schoolClass.setCode(required(request.code(), "code"));
    schoolClass.setName(required(request.name(), "name"));
    schoolClass.setGrade(required(request.grade(), "grade"));
    schoolClass.setStage(trimToNull(request.stage()));
    schoolClass.setStudentCount(defaultInt(request.studentCount()));
    schoolClass.setHomeroomTeacherId(ownedTeacherId(orgId, request.homeroomTeacherId()));
    schoolClass.setDefaultClassroomId(ownedClassroomId(orgId, request.defaultClassroomId()));
    schoolClass.setStatus(normalizeClassStatus(request.status()));
  }

  private void applySubject(Subject subject, SubjectRequest request) {
    subject.setCode(trimToNull(request.code()));
    subject.setName(required(request.name(), "name"));
    subject.setCategory(normalizeSubjectCategory(request.category()));
    subject.setExamType(normalizeExamType(request.examType()));
    subject.setHours(request.hours() == null ? BigDecimal.ZERO : request.hours());
    subject.setColor(defaultString(request.color(), "slate"));
    subject.setStatus(normalizeSubjectStatus(request.status()));
  }

  private void replaceSubjects(String table, String ownerColumn, UUID orgId, UUID ownerId, List<UUID> subjectIds) {
    jdbcTemplate.update("delete from " + table + " where org_id = ? and " + ownerColumn + " = ?", orgId, ownerId);
    for (UUID subjectId : ownedSubjectIds(orgId, subjectIds)) {
      jdbcTemplate.update(
          "insert into " + table + " (" + ownerColumn + ", subject_id, org_id) values (?, ?, ?) on conflict do nothing",
          ownerId,
          subjectId,
          orgId
      );
    }
  }

  private List<UUID> ownedSubjectIds(UUID orgId, List<UUID> subjectIds) {
    if (subjectIds == null || subjectIds.isEmpty()) {
      return List.of();
    }
    List<UUID> owned = new ArrayList<>();
    for (UUID subjectId : subjectIds) {
      if (subjectId == null) {
        continue;
      }
      subjectRepository.findByOrgIdAndId(orgId, subjectId)
          .orElseThrow(() -> notFound("Subject not found: " + subjectId));
      owned.add(subjectId);
    }
    return owned;
  }

  private UUID ownedTeacherId(UUID orgId, UUID teacherId) {
    if (teacherId == null) {
      return null;
    }
    teacherRepository.findByOrgIdAndId(orgId, teacherId)
        .orElseThrow(() -> notFound("Teacher not found: " + teacherId));
    return teacherId;
  }

  private UUID ownedClassroomId(UUID orgId, UUID classroomId) {
    if (classroomId == null) {
      return null;
    }
    classroomRepository.findByOrgIdAndId(orgId, classroomId)
        .orElseThrow(() -> notFound("Classroom not found: " + classroomId));
    return classroomId;
  }

  private UUID ownedSchoolClassId(UUID orgId, UUID classId) {
    if (classId == null) {
      return null;
    }
    schoolClassRepository.findByOrgIdAndId(orgId, classId)
        .orElseThrow(() -> notFound("SchoolClass not found: " + classId));
    return classId;
  }

  /** 迭代9 F6 同款守卫 + 迭代12 P2：organization.settings.allowanceUnit 若为脏值(空串/非数字)，裸 ::numeric
   *  会抛 22P02→500。用数字模式守卫回退 120。抽成方法便于在列表里 hoist 出每教师循环(只查一次)。 */
  private double allowanceUnit(UUID orgId) {
    Double v = queryDouble(
        "select coalesce(case when trim(coalesce(settings->>'allowanceUnit','')) ~ '^[0-9]+([.][0-9]+)?$' "
            + "then trim(settings->>'allowanceUnit')::numeric else null end, 120) from organization where id = ?",
        orgId);
    return v == null ? 120.0 : v;
  }

  private TeacherDto toTeacherDto(UUID orgId, Teacher teacher) {
    // 单教师路径(getTeacher/create/update)：就地查一次 allowanceUnit。列表路径改用 3 参重载 hoist。
    return toTeacherDto(orgId, teacher, allowanceUnit(orgId));
  }

  private TeacherDto toTeacherDto(UUID orgId, Teacher teacher, double allowanceUnit) {
    List<String> subjects = jdbcTemplate.queryForList("""
        select s.name
        from subject s
        join teacher_subject ts on ts.subject_id = s.id
        where ts.org_id = ? and ts.teacher_id = ?
        order by s.name
        """, String.class, orgId, teacher.getId());
    String homeroomOf = teacher.getHomeroomClassId() == null
        ? null
        : queryString("select name from school_class where org_id = ? and id = ?", orgId, teacher.getHomeroomClassId());
    // 监考次数/课酬只统计"当前生效"的排程——每个任务最近一次 succeeded run。否则同一任务每重排一次就把
    // 该教师的监考次数与津贴再叠加一层(实测 3 次重排→3×)，因为旧 run 的装配行长期驻留且查询无 run 过滤。
    String activeRunFilter = " and schedule_run_id in ("
        + "select distinct on (sr.exam_task_id) sr.id from schedule_run sr "
        + "where sr.org_id = ? and sr.status = 'succeeded' "
        + "order by sr.exam_task_id, sr.finished_at desc nulls last, sr.created_at desc, sr.id desc)";
    Integer supCount = queryInteger(
        "select count(*) from schedule_assignment where org_id = ? and teacher_id = ?" + activeRunFilter,
        orgId, teacher.getId(), orgId);
    Double durationHours = queryDouble(
        "select coalesce(sum(extract(epoch from (end_time - start_time)) / 3600.0), 0) "
            + "from schedule_assignment where org_id = ? and teacher_id = ?" + activeRunFilter,
        orgId,
        teacher.getId(),
        orgId
    );
    double allowance = Math.round((durationHours == null ? 0.0 : durationHours) * allowanceUnit * 100.0) / 100.0;
    return new TeacherDto(
        teacher.getId(),
        teacher.getTeacherNo(),
        teacher.getTeacherNo(),
        teacher.getName(),
        teacher.getGender(),
        teacher.getGrade(),
        teacher.getPhone(),
        teacher.getEmail(),
        teacher.getStatus(),
        teacher.getAge(),
        teacher.getPosition(),
        decodeWindows(teacher.getLeaveWindows(), "leaveWindows"),
        List.copyOf(teacher.getFunctionalAccommodations()),
        decodeWindows(teacher.getDutyWindows(), "dutyWindows"),
        List.copyOf(teacher.getQualifications()),
        teacher.getQualificationExpiry(),
        teacher.getMaxDailySessions(),
        teacher.getPreferredCampus(),
        teacher.isHomeroom(),
        teacher.getHomeroomClassId(),
        homeroomOf,
        subjects,
        supCount == null ? 0 : supCount,
        allowance,
        gradeColor(teacher.getGrade())
    );
  }

  private ClassroomDto toClassroomDto(Classroom classroom) {
    return new ClassroomDto(
        classroom.getId(),
        classroom.getCode(),
        classroom.getCode(),
        classroom.getName(),
        classroom.getBuilding(),
        classroom.getFloor(),
        classroom.getCapacity(),
        classroom.getExamCapacity(),
        classroom.getRoomType(),
        roomUiType(classroom.getRoomType()),
        classroom.getStatus(),
        classroom.getFeatures(),
        // No time-window occupancy measurement exists here. Capacity is not a usage percentage.
        null
    );
  }

  private SchoolClassDto toSchoolClassDto(UUID orgId, SchoolClass schoolClass) {
    // 单班级路径(getClass/create/update)：走同一批量查询的单元素形态，与列表路径共用一份 SQL 口径，
    // 避免两套实现日后漂移；单条时批量查询退化为等价的三次单行查询，不会比旧实现多查。
    return toSchoolClassDto(schoolClass, schoolClassRefs(orgId, List.of(schoolClass)));
  }

  private SchoolClassDto toSchoolClassDto(SchoolClass schoolClass, SchoolClassRefs refs) {
    // null 归属 id 必须短路：refs 的空 map 是 Map.of()，其 get(null) 会抛 NPE。缺失/跨租户 id 不入 map
    // → 得到 null，与旧的 queryString 空结果返回 null 语义一致。
    String homeroom = schoolClass.getHomeroomTeacherId() == null
        ? null
        : refs.teacherNames().get(schoolClass.getHomeroomTeacherId());
    String defaultClassroom = schoolClass.getDefaultClassroomId() == null
        ? null
        : refs.classroomNames().get(schoolClass.getDefaultClassroomId());
    int subjectCount = refs.subjectCounts().getOrDefault(schoolClass.getId(), 0);
    return new SchoolClassDto(
        schoolClass.getId(),
        schoolClass.getCode(),
        schoolClass.getCode(),
        schoolClass.getName(),
        schoolClass.getGrade(),
        schoolClass.getStage(),
        schoolClass.getStudentCount(),
        schoolClass.getStudentCount(),
        schoolClass.getHomeroomTeacherId(),
        homeroom,
        schoolClass.getDefaultClassroomId(),
        defaultClassroom,
        schoolClass.getStatus(),
        subjectCount,
        gradeColor(schoolClass.getGrade())
    );
  }

  /** 批量查询回填 toSchoolClassDto 的三个派生字段；缺失的 id 不入 map(调用方按 null/0 兜底)。 */
  private record SchoolClassRefs(
      Map<UUID, String> teacherNames,
      Map<UUID, String> classroomNames,
      Map<UUID, Integer> subjectCounts
  ) {
  }

  /** 批量查询回填 toSubjectDto 的两个派生字段。 */
  private record SubjectUsage(Map<UUID, List<String>> grades, Map<UUID, Integer> examCounts) {
  }

  /** 批量查询的平铺行 (归属 id, 值)。用自有 record 而非 Map.entry —— 后者禁止 null value。 */
  private record KeyedValue<T>(UUID key, T value) {
  }

  private SchoolClassRefs schoolClassRefs(UUID orgId, List<SchoolClass> classes) {
    if (classes.isEmpty()) {
      return new SchoolClassRefs(Map.of(), Map.of(), Map.of());
    }
    // distinct：多个班级可共用同一班主任/默认考场，去重后再拼 in 列表。
    List<UUID> teacherIds = classes.stream()
        .map(SchoolClass::getHomeroomTeacherId).filter(Objects::nonNull).distinct().toList();
    List<UUID> classroomIds = classes.stream()
        .map(SchoolClass::getDefaultClassroomId).filter(Objects::nonNull).distinct().toList();
    List<UUID> classIds = classes.stream().map(SchoolClass::getId).toList();
    return new SchoolClassRefs(
        namesByIdIn(orgId, "teacher", teacherIds),
        namesByIdIn(orgId, "classroom", classroomIds),
        subjectCountsByClass(orgId, classIds)
    );
  }

  /** 按 org 过滤批量取名字。表名为代码内固定字面量、占位符按数量生成，无注入风险。 */
  private Map<UUID, String> namesByIdIn(UUID orgId, String table, List<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new LinkedHashMap<>();
    List<KeyedValue<String>> rows = jdbcTemplate.query(
        "select id, name from " + table + " where org_id = ? and id in (" + placeholders(ids.size()) + ")",
        (rs, rowNum) -> new KeyedValue<String>(rs.getObject("id", UUID.class), rs.getString("name")),
        batchArgs(orgId, ids));
    for (KeyedValue<String> row : rows) {
      names.put(row.key(), row.value());
    }
    return names;
  }

  private Map<UUID, Integer> subjectCountsByClass(UUID orgId, List<UUID> classIds) {
    Map<UUID, Integer> counts = new LinkedHashMap<>();
    List<KeyedValue<Integer>> rows = jdbcTemplate.query(
        "select class_id, count(*) as subject_count from school_class_subject "
            + "where org_id = ? and class_id in (" + placeholders(classIds.size()) + ") group by class_id",
        (rs, rowNum) -> new KeyedValue<Integer>(rs.getObject("class_id", UUID.class), rs.getInt("subject_count")),
        batchArgs(orgId, classIds));
    for (KeyedValue<Integer> row : rows) {
      counts.put(row.key(), row.value());
    }
    // group by 不产出零行分组 → 无关联的班级不入 map，调用方兜底 0，与旧的 count(*) 单查返回 0 一致。
    return counts;
  }

  /** 迭代12 P2(性能)：科目的 grades/examCount 批量化。V14 的 (org_id, subject_id) 复合索引支撑这里按
   *  subject_id 的反向批量查询。 */
  private SubjectUsage subjectUsage(UUID orgId, List<UUID> subjectIds) {
    if (subjectIds.isEmpty()) {
      return new SubjectUsage(Map.of(), Map.of());
    }
    String ids = placeholders(subjectIds.size());
    Object[] args = batchArgs(orgId, subjectIds);
    // distinct (subject_id, grade) + order by subject_id, grade 与旧的"每科目 distinct grade order by grade"
    // 逐科目等价：分组内仍是按 grade 升序的去重年级。school_class.grade 为 NOT NULL，不存在 null 排序差异。
    Map<UUID, List<String>> grades = new LinkedHashMap<>();
    List<KeyedValue<String>> gradeRows = jdbcTemplate.query("""
        select distinct scs.subject_id, sc.grade
        from school_class sc
        join school_class_subject scs on scs.class_id = sc.id
        where scs.org_id = ? and scs.subject_id in (%s)
        order by scs.subject_id, sc.grade
        """.formatted(ids),
        (rs, rowNum) -> new KeyedValue<String>(rs.getObject("subject_id", UUID.class), rs.getString("grade")),
        args);
    for (KeyedValue<String> row : gradeRows) {
      grades.computeIfAbsent(row.key(), key -> new ArrayList<>()).add(row.value());
    }
    Map<UUID, Integer> examCounts = new LinkedHashMap<>();
    List<KeyedValue<Integer>> examRows = jdbcTemplate.query("""
        select subject_id, count(*) as exam_count
        from exam
        where org_id = ? and subject_id in (%s)
        group by subject_id
        """.formatted(ids),
        (rs, rowNum) -> new KeyedValue<Integer>(rs.getObject("subject_id", UUID.class), rs.getInt("exam_count")),
        args);
    for (KeyedValue<Integer> row : examRows) {
      examCounts.put(row.key(), row.value());
    }
    return new SubjectUsage(grades, examCounts);
  }

  private String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
  }

  /** in 列表查询的参数：org_id 在前(多租户过滤),其后为各归属 id,顺序与 placeholders 生成的 ? 一一对应。 */
  private Object[] batchArgs(UUID orgId, List<UUID> ids) {
    Object[] args = new Object[ids.size() + 1];
    args[0] = orgId;
    for (int i = 0; i < ids.size(); i++) {
      args[i + 1] = ids.get(i);
    }
    return args;
  }

  private SubjectDto toSubjectDto(UUID orgId, Subject subject) {
    // 单科目路径(getSubject/create/update)：同 toSchoolClassDto，走批量查询的单元素形态复用同一口径。
    return toSubjectDto(subject, subjectUsage(orgId, List.of(subject.getId())));
  }

  private SubjectDto toSubjectDto(Subject subject, SubjectUsage usage) {
    List<String> grades = usage.grades().getOrDefault(subject.getId(), List.of());
    int examCount = usage.examCounts().getOrDefault(subject.getId(), 0);
    return new SubjectDto(
        subject.getId(),
        subject.getCode() == null ? subject.getId().toString() : subject.getCode(),
        subject.getCode(),
        subject.getName(),
        subject.getCategory(),
        subject.getExamType(),
        subject.getHours(),
        subject.getColor(),
        subject.getStatus(),
        grades.isEmpty() ? List.of("全年级") : grades,
        examCount
    );
  }

  private boolean matchesTeacher(TeacherDto teacher, String q, String grade, String subject, Boolean homeroom, String status) {
    return containsAny(q, teacher.teacherNo(), teacher.name(), teacher.phone(), teacher.email())
        && matchesValue(grade, teacher.grade())
        && matchesValue(status, teacher.status())
        && (subject == null || subject.isBlank() || teacher.subjects().contains(subject))
        && (homeroom == null || teacher.homeroom() == homeroom);
  }

  private boolean matchesClassroom(ClassroomDto classroom, String q, String status, String roomType) {
    return containsAny(q, classroom.code(), classroom.name(), classroom.building())
        && matchesValue(status, classroom.status())
        && matchesValue(roomType, classroom.roomType());
  }

  private boolean matchesSchoolClass(SchoolClassDto schoolClass, String q, String grade, String status) {
    return containsAny(q, schoolClass.code(), schoolClass.name(), schoolClass.homeroom())
        && matchesValue(grade, schoolClass.grade())
        && matchesValue(status, schoolClass.status());
  }

  private boolean matchesSubject(SubjectDto subject, String q, String category, String status) {
    return containsAny(q, subject.code(), subject.name())
        && matchesValue(category, subject.category())
        && matchesValue(status, subject.status());
  }

  private boolean containsAny(String q, String... values) {
    if (q == null || q.isBlank()) {
      return true;
    }
    String needle = q.trim().toLowerCase(Locale.ROOT);
    for (String value : values) {
      if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private boolean matchesValue(String expected, String actual) {
    return expected == null || expected.isBlank() || "all".equalsIgnoreCase(expected) || expected.equals(actual);
  }

  private Teacher teacher(UUID orgId, UUID id) {
    return teacherRepository.findByOrgIdAndId(orgId, id).orElseThrow(() -> notFound("Teacher not found"));
  }

  private Classroom classroom(UUID orgId, UUID id) {
    return classroomRepository.findByOrgIdAndId(orgId, id).orElseThrow(() -> notFound("Classroom not found"));
  }

  private SchoolClass schoolClass(UUID orgId, UUID id) {
    return schoolClassRepository.findByOrgIdAndId(orgId, id).orElseThrow(() -> notFound("Class not found"));
  }

  private Subject subject(UUID orgId, UUID id) {
    return subjectRepository.findByOrgIdAndId(orgId, id).orElseThrow(() -> notFound("Subject not found"));
  }

  private void requireUniqueTeacherNo(UUID orgId, UUID id, String teacherNo) {
    teacherRepository.findByOrgIdAndTeacherNo(orgId, required(teacherNo, "teacherNo"))
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(existing -> conflict("Teacher number already exists"));
  }

  private void requireUniqueClassroomCode(UUID orgId, UUID id, String code) {
    classroomRepository.findByOrgIdAndCode(orgId, required(code, "code"))
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(existing -> conflict("Classroom code already exists"));
  }

  private void requireUniqueClassCode(UUID orgId, UUID id, String code) {
    schoolClassRepository.findByOrgIdAndCode(orgId, required(code, "code"))
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(existing -> conflict("Class code already exists"));
  }

  private void requireUniqueSubject(UUID orgId, UUID id, String code, String name) {
    Optional.ofNullable(trimToNull(code))
        .flatMap(value -> subjectRepository.findByOrgIdAndCode(orgId, value))
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(existing -> conflict("Subject code already exists"));
    subjectRepository.findByOrgIdAndName(orgId, required(name, "name"))
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(existing -> conflict("Subject name already exists"));
  }

  private String queryString(String sql, Object... args) {
    try {
      return jdbcTemplate.queryForObject(sql, String.class, args);
    } catch (EmptyResultDataAccessException ignored) {
      return null;
    }
  }

  private Integer queryInteger(String sql, Object... args) {
    try {
      return jdbcTemplate.queryForObject(sql, Integer.class, args);
    } catch (EmptyResultDataAccessException ignored) {
      return 0;
    }
  }

  private Double queryDouble(String sql, Object... args) {
    try {
      return jdbcTemplate.queryForObject(sql, Double.class, args);
    } catch (EmptyResultDataAccessException ignored) {
      return 0.0;
    }
  }

  private String required(String value, String field) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return normalized;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private List<Map<String, Object>> encodeWindows(List<TeacherWindow> windows, String field) {
    List<Map<String, Object>> encoded = new ArrayList<>(windows.size());
    for (int index = 0; index < windows.size(); index++) {
      TeacherWindow window = windows.get(index);
      if (window == null || window.start() == null || window.end() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            field + "[" + index + "] requires start and end");
      }
      if (!window.start().isBefore(window.end())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            field + "[" + index + "] start must be before end");
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("start", window.start().toString());
      value.put("end", window.end().toString());
      String reason = trimToNull(window.reason());
      if (reason != null) {
        value.put("reason", reason);
      }
      encoded.add(value);
    }
    return encoded;
  }

  private List<TeacherWindow> decodeWindows(List<Map<String, Object>> windows, String field) {
    if (windows == null || windows.isEmpty()) {
      return List.of();
    }
    List<TeacherWindow> decoded = new ArrayList<>(windows.size());
    for (int index = 0; index < windows.size(); index++) {
      Map<String, Object> window = windows.get(index);
      try {
        LocalDateTime start = LocalDateTime.parse(String.valueOf(window.get("start")));
        LocalDateTime end = LocalDateTime.parse(String.valueOf(window.get("end")));
        if (!start.isBefore(end)) {
          throw new IllegalArgumentException("start must be before end");
        }
        decoded.add(new TeacherWindow(start, end, trimToNull((String) window.get("reason"))));
      } catch (RuntimeException exception) {
        throw new IllegalStateException("Invalid persisted " + field + "[" + index + "]", exception);
      }
    }
    return List.copyOf(decoded);
  }

  private List<String> normalizeAccommodations(List<String> accommodations) {
    List<String> normalized = new ArrayList<>(accommodations.size());
    for (int index = 0; index < accommodations.size(); index++) {
      String value = trimToNull(accommodations.get(index));
      if (value == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "functionalAccommodations[" + index + "] must not be blank");
      }
      normalized.add(value);
    }
    return normalized;
  }

  private List<String> normalizeQualifications(List<String> qualifications) {
    if (qualifications == null) {
      return DEFAULT_TEACHER_QUALIFICATIONS;
    }
    List<String> normalized = new ArrayList<>(qualifications.size());
    Set<String> seen = new java.util.LinkedHashSet<>();
    for (int index = 0; index < qualifications.size(); index++) {
      String qualification = qualifications.get(index);
      if (qualification == null || !ALLOWED_TEACHER_QUALIFICATIONS.contains(qualification)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "qualifications[" + index + "] must be one of INVIGILATOR, CHIEF");
      }
      if (!seen.add(qualification)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "qualifications must not contain duplicates");
      }
      normalized.add(qualification);
    }
    return List.copyOf(normalized);
  }

  private int normalizeMaxDailySessions(Integer maxDailySessions) {
    int normalized = maxDailySessions == null ? DEFAULT_MAX_DAILY_SESSIONS : maxDailySessions;
    if (normalized < 1 || normalized > 12) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "maxDailySessions must be between 1 and 12");
    }
    return normalized;
  }

  private String normalizePreferredCampus(String preferredCampus) {
    String normalized = trimToNull(preferredCampus);
    if (normalized != null && normalized.length() > 120) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "preferredCampus must not exceed 120 characters");
    }
    return normalized;
  }

  private int defaultInt(Integer value) {
    return value == null ? 0 : value;
  }

  private String defaultString(String value, String fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private String normalizeGender(String value) {
    String gender = defaultString(value, "unknown").toLowerCase(Locale.ROOT);
    return switch (gender) {
      case "male", "m", "男" -> "male";
      case "female", "f", "女" -> "female";
      default -> "unknown";
    };
  }

  private String normalizeTeacherStatus(String value) {
    String status = defaultString(value, "active").toLowerCase(Locale.ROOT);
    return switch (status) {
      case "disabled", "left" -> status;
      case "leave" -> "left";
      default -> "active";
    };
  }

  private String normalizeClassroomStatus(String value) {
    String status = defaultString(value, "active").toLowerCase(Locale.ROOT);
    return switch (status) {
      case "disabled", "maintenance" -> status;
      case "available" -> "active";
      default -> "active";
    };
  }

  private String normalizeClassStatus(String value) {
    String status = defaultString(value, "active").toLowerCase(Locale.ROOT);
    return switch (status) {
      case "disabled", "graduated" -> status;
      default -> "active";
    };
  }

  private String normalizeSubjectStatus(String value) {
    String status = defaultString(value, "active").toLowerCase(Locale.ROOT);
    return "disabled".equals(status) ? "disabled" : "active";
  }

  private String normalizeRoomType(String value) {
    String type = defaultString(value, "standard").toLowerCase(Locale.ROOT);
    return switch (type) {
      case "corridor", "lab", "meeting", "other" -> type;
      case "normal" -> "standard";
      default -> "standard";
    };
  }

  private String normalizeSubjectCategory(String value) {
    String category = defaultString(value, "main").toLowerCase(Locale.ROOT);
    return switch (category) {
      case "secondary", "activity" -> category;
      case "主科" -> "main";
      case "副科", "术科" -> "activity";
      default -> "main";
    };
  }

  private String normalizeExamType(String value) {
    String type = defaultString(value, "written").toLowerCase(Locale.ROOT);
    return switch (type) {
      case "oral", "practical", "other" -> type;
      case "口试" -> "oral";
      case "实操", "机考", "体测" -> "practical";
      default -> "written";
    };
  }

  private String roomUiType(String roomType) {
    return switch (roomType) {
      case "lab" -> "lab";
      case "meeting" -> "lecture";
      default -> "normal";
    };
  }

  private String gradeColor(String grade) {
    if (grade == null || grade.isBlank()) {
      return "slate";
    }
    String normalized = grade.toLowerCase(Locale.ROOT);
    if (normalized.contains("初一") || normalized.contains("g7") || normalized.contains("7")) {
      return "sky";
    }
    if (normalized.contains("初二") || normalized.contains("g8") || normalized.contains("8")) {
      return "indigo";
    }
    if (normalized.contains("初三") || normalized.contains("g9") || normalized.contains("9")) {
      return "violet";
    }
    if (normalized.contains("高一") || normalized.contains("10")) {
      return "emerald";
    }
    if (normalized.contains("高二") || normalized.contains("11")) {
      return "amber";
    }
    if (normalized.contains("高三") || normalized.contains("12")) {
      return "rose";
    }
    return "slate";
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /**
   * 删除前的引用校验：若主数据已被排考/考试引用，给出**贴切**的 409 文案（此前直接撞外键约束，被统一
   * 映射成"导入字段超长/重复"的误导文案）。表名/列名为代码内固定字面量，无注入风险。
   */
  private void requireNotReferenced(UUID orgId, String table, String column, UUID id, String reason) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE org_id = ? AND " + column + " = ?",
        Integer.class, orgId, id);
    if (count != null && count > 0) {
      conflict(reason + "（共 " + count + " 处引用），请先在排考结果中改派/调整后再删除。");
    }
  }

  /** 记一条主数据变更审计（此前主数据 CRUD/删除全无 actor/action 追踪 — 迭代8 补强）。payload 仅记
   * 业务标识(编号/名称)，绝不含密码等敏感明文。saveAndFlush 立即落库，避免任何刷新时机疑问。 */
  private void audit(UUID orgId, String action, String targetType, UUID targetId, Map<String, Object> payload) {
    AuditLog log = new AuditLog();
    log.setOrgId(orgId);
    log.setActorId(actorId());
    log.setAction(action);
    log.setTargetType(targetType);
    log.setTargetId(targetId);
    log.setSeverity("info");
    log.setPayload(payload);
    auditLogRepository.saveAndFlush(log);
  }

  private UUID actorId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal) {
      return principal.userId();
    }
    return null;
  }
}
