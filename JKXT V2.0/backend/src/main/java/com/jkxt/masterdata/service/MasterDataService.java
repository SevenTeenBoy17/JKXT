package com.jkxt.masterdata.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.masterdata.api.MasterDataDtos.ClassroomDto;
import com.jkxt.masterdata.api.MasterDataDtos.ClassroomRequest;
import com.jkxt.masterdata.api.MasterDataDtos.SchoolClassDto;
import com.jkxt.masterdata.api.MasterDataDtos.SchoolClassRequest;
import com.jkxt.masterdata.api.MasterDataDtos.SubjectDto;
import com.jkxt.masterdata.api.MasterDataDtos.SubjectRequest;
import com.jkxt.masterdata.api.MasterDataDtos.TeacherDto;
import com.jkxt.masterdata.api.MasterDataDtos.TeacherRequest;
import com.jkxt.masterdata.domain.Classroom;
import com.jkxt.masterdata.domain.SchoolClass;
import com.jkxt.masterdata.domain.Subject;
import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.SchoolClassRepository;
import com.jkxt.masterdata.repo.SubjectRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class MasterDataService {

  private final TeacherRepository teacherRepository;
  private final ClassroomRepository classroomRepository;
  private final SchoolClassRepository schoolClassRepository;
  private final SubjectRepository subjectRepository;
  private final JdbcTemplate jdbcTemplate;

  public MasterDataService(
      TeacherRepository teacherRepository,
      ClassroomRepository classroomRepository,
      SchoolClassRepository schoolClassRepository,
      SubjectRepository subjectRepository,
      JdbcTemplate jdbcTemplate
  ) {
    this.teacherRepository = teacherRepository;
    this.classroomRepository = classroomRepository;
    this.schoolClassRepository = schoolClassRepository;
    this.subjectRepository = subjectRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional(readOnly = true)
  public List<TeacherDto> listTeachers(String q, String grade, String subject, Boolean homeroom, String status) {
    UUID orgId = TenantContext.getRequired();
    return teacherRepository.findByOrgIdOrderByTeacherNoAsc(orgId).stream()
        .map(teacher -> toTeacherDto(orgId, teacher))
        .filter(dto -> matchesTeacher(dto, q, grade, subject, homeroom, status))
        .toList();
  }

  @Transactional(readOnly = true)
  public TeacherDto getTeacher(UUID id) {
    UUID orgId = TenantContext.getRequired();
    return toTeacherDto(orgId, teacher(orgId, id));
  }

  public TeacherDto createTeacher(TeacherRequest request) {
    UUID orgId = TenantContext.getRequired();
    requireUniqueTeacherNo(orgId, null, request.teacherNo());
    Teacher teacher = new Teacher();
    teacher.setOrgId(orgId);
    applyTeacher(teacher, request);
    Teacher saved = teacherRepository.saveAndFlush(teacher);
    replaceSubjects("teacher_subject", "teacher_id", orgId, saved.getId(), request.subjectIds());
    return toTeacherDto(orgId, saved);
  }

  public TeacherDto updateTeacher(UUID id, TeacherRequest request) {
    UUID orgId = TenantContext.getRequired();
    Teacher teacher = teacher(orgId, id);
    requireUniqueTeacherNo(orgId, id, request.teacherNo());
    applyTeacher(teacher, request);
    Teacher saved = teacherRepository.saveAndFlush(teacher);
    if (request.subjectIds() != null) {
      replaceSubjects("teacher_subject", "teacher_id", orgId, saved.getId(), request.subjectIds());
    }
    return toTeacherDto(orgId, saved);
  }

  public void deleteTeacher(UUID id) {
    UUID orgId = TenantContext.getRequired();
    Teacher teacher = teacher(orgId, id);
    jdbcTemplate.update(
        "update school_class set homeroom_teacher_id = null where org_id = ? and homeroom_teacher_id = ?",
        orgId,
        id
    );
    jdbcTemplate.update("delete from teacher_subject where org_id = ? and teacher_id = ?", orgId, id);
    teacherRepository.delete(teacher);
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
    UUID orgId = TenantContext.getRequired();
    requireUniqueClassroomCode(orgId, null, request.code());
    Classroom classroom = new Classroom();
    classroom.setOrgId(orgId);
    applyClassroom(classroom, request);
    return toClassroomDto(classroomRepository.saveAndFlush(classroom));
  }

  public ClassroomDto updateClassroom(UUID id, ClassroomRequest request) {
    UUID orgId = TenantContext.getRequired();
    Classroom classroom = classroom(orgId, id);
    requireUniqueClassroomCode(orgId, id, request.code());
    applyClassroom(classroom, request);
    return toClassroomDto(classroomRepository.saveAndFlush(classroom));
  }

  public void deleteClassroom(UUID id) {
    UUID orgId = TenantContext.getRequired();
    Classroom classroom = classroom(orgId, id);
    jdbcTemplate.update(
        "update school_class set default_classroom_id = null where org_id = ? and default_classroom_id = ?",
        orgId,
        id
    );
    classroomRepository.delete(classroom);
  }

  @Transactional(readOnly = true)
  public List<SchoolClassDto> listClasses(String q, String grade, String status) {
    UUID orgId = TenantContext.getRequired();
    return schoolClassRepository.findByOrgIdOrderByGradeAscCodeAsc(orgId).stream()
        .map(schoolClass -> toSchoolClassDto(orgId, schoolClass))
        .filter(dto -> matchesSchoolClass(dto, q, grade, status))
        .toList();
  }

  @Transactional(readOnly = true)
  public SchoolClassDto getClass(UUID id) {
    UUID orgId = TenantContext.getRequired();
    return toSchoolClassDto(orgId, schoolClass(orgId, id));
  }

  public SchoolClassDto createClass(SchoolClassRequest request) {
    UUID orgId = TenantContext.getRequired();
    requireUniqueClassCode(orgId, null, request.code());
    SchoolClass schoolClass = new SchoolClass();
    schoolClass.setOrgId(orgId);
    applySchoolClass(orgId, schoolClass, request);
    SchoolClass saved = schoolClassRepository.saveAndFlush(schoolClass);
    replaceSubjects("school_class_subject", "class_id", orgId, saved.getId(), request.subjectIds());
    return toSchoolClassDto(orgId, saved);
  }

  public SchoolClassDto updateClass(UUID id, SchoolClassRequest request) {
    UUID orgId = TenantContext.getRequired();
    SchoolClass schoolClass = schoolClass(orgId, id);
    requireUniqueClassCode(orgId, id, request.code());
    applySchoolClass(orgId, schoolClass, request);
    SchoolClass saved = schoolClassRepository.saveAndFlush(schoolClass);
    if (request.subjectIds() != null) {
      replaceSubjects("school_class_subject", "class_id", orgId, saved.getId(), request.subjectIds());
    }
    return toSchoolClassDto(orgId, saved);
  }

  public void deleteClass(UUID id) {
    UUID orgId = TenantContext.getRequired();
    SchoolClass schoolClass = schoolClass(orgId, id);
    jdbcTemplate.update(
        "update teacher set homeroom_class_id = null, is_homeroom = false where org_id = ? and homeroom_class_id = ?",
        orgId,
        id
    );
    jdbcTemplate.update("delete from school_class_subject where org_id = ? and class_id = ?", orgId, id);
    schoolClassRepository.delete(schoolClass);
  }

  @Transactional(readOnly = true)
  public List<SubjectDto> listSubjects(String q, String category, String status) {
    UUID orgId = TenantContext.getRequired();
    return subjectRepository.findByOrgIdOrderByCodeAscNameAsc(orgId).stream()
        .map(subject -> toSubjectDto(orgId, subject))
        .filter(dto -> matchesSubject(dto, q, category, status))
        .toList();
  }

  @Transactional(readOnly = true)
  public SubjectDto getSubject(UUID id) {
    UUID orgId = TenantContext.getRequired();
    return toSubjectDto(orgId, subject(orgId, id));
  }

  public SubjectDto createSubject(SubjectRequest request) {
    UUID orgId = TenantContext.getRequired();
    requireUniqueSubject(orgId, null, request.code(), request.name());
    Subject subject = new Subject();
    subject.setOrgId(orgId);
    applySubject(subject, request);
    return toSubjectDto(orgId, subjectRepository.saveAndFlush(subject));
  }

  public SubjectDto updateSubject(UUID id, SubjectRequest request) {
    UUID orgId = TenantContext.getRequired();
    Subject subject = subject(orgId, id);
    requireUniqueSubject(orgId, id, request.code(), request.name());
    applySubject(subject, request);
    return toSubjectDto(orgId, subjectRepository.saveAndFlush(subject));
  }

  public void deleteSubject(UUID id) {
    UUID orgId = TenantContext.getRequired();
    Subject subject = subject(orgId, id);
    jdbcTemplate.update("delete from teacher_subject where org_id = ? and subject_id = ?", orgId, id);
    jdbcTemplate.update("delete from school_class_subject where org_id = ? and subject_id = ?", orgId, id);
    subjectRepository.delete(subject);
  }

  private void applyTeacher(Teacher teacher, TeacherRequest request) {
    teacher.setTeacherNo(required(request.teacherNo(), "teacherNo"));
    teacher.setName(required(request.name(), "name"));
    teacher.setGender(normalizeGender(request.gender()));
    teacher.setGrade(trimToNull(request.grade()));
    teacher.setPhone(trimToNull(request.phone()));
    teacher.setEmail(trimToNull(request.email()));
    teacher.setStatus(normalizeTeacherStatus(request.status()));
    teacher.setHomeroom(Boolean.TRUE.equals(request.homeroom()));
    teacher.setHomeroomClassId(request.homeroomClassId());
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

  private TeacherDto toTeacherDto(UUID orgId, Teacher teacher) {
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
    Integer supCount = queryInteger("select count(*) from schedule_assignment where org_id = ? and teacher_id = ?", orgId, teacher.getId());
    Double durationHours = queryDouble(
        "select coalesce(sum(extract(epoch from (end_time - start_time)) / 3600.0), 0) from schedule_assignment where org_id = ? and teacher_id = ?",
        orgId,
        teacher.getId()
    );
    Double allowanceUnit = queryDouble("select coalesce((settings->>'allowanceUnit')::numeric, 120) from organization where id = ?", orgId);
    double allowance = Math.round((durationHours == null ? 0.0 : durationHours) * (allowanceUnit == null ? 120.0 : allowanceUnit) * 100.0) / 100.0;
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
        classroom.getExamCapacity() > 0 ? Math.min(92, Math.max(18, classroom.getExamCapacity() + 18)) : 0
    );
  }

  private SchoolClassDto toSchoolClassDto(UUID orgId, SchoolClass schoolClass) {
    String homeroom = schoolClass.getHomeroomTeacherId() == null
        ? null
        : queryString("select name from teacher where org_id = ? and id = ?", orgId, schoolClass.getHomeroomTeacherId());
    String defaultClassroom = schoolClass.getDefaultClassroomId() == null
        ? null
        : queryString("select name from classroom where org_id = ? and id = ?", orgId, schoolClass.getDefaultClassroomId());
    Integer subjectCount = queryInteger(
        "select count(*) from school_class_subject where org_id = ? and class_id = ?",
        orgId,
        schoolClass.getId()
    );
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
        subjectCount == null ? 0 : subjectCount,
        gradeColor(schoolClass.getGrade())
    );
  }

  private SubjectDto toSubjectDto(UUID orgId, Subject subject) {
    List<String> grades = jdbcTemplate.queryForList("""
        select distinct sc.grade
        from school_class sc
        join school_class_subject scs on scs.class_id = sc.id
        where scs.org_id = ? and scs.subject_id = ?
        order by sc.grade
        """, String.class, orgId, subject.getId());
    Integer examCount = queryInteger("select count(*) from exam where org_id = ? and subject_id = ?", orgId, subject.getId());
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
        examCount == null ? 0 : examCount
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
}
