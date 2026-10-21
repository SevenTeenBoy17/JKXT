package com.jkxt.masterdata.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "teacher")
public class Teacher extends TenantEntity {

  @Column(name = "teacher_no", nullable = false)
  private String teacherNo;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String gender;

  private String grade;

  private String phone;

  private String email;

  @Column(nullable = false)
  private String status = "active";

  private Integer age;

  private String position;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "leave_windows", columnDefinition = "jsonb", nullable = false)
  private List<Map<String, Object>> leaveWindows = new ArrayList<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "functional_accommodations", columnDefinition = "jsonb", nullable = false)
  private List<String> functionalAccommodations = new ArrayList<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "duty_windows", columnDefinition = "jsonb", nullable = false)
  private List<Map<String, Object>> dutyWindows = new ArrayList<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "qualifications", columnDefinition = "jsonb", nullable = false)
  private List<String> qualifications = new ArrayList<>(List.of("INVIGILATOR"));

  @Column(name = "qualification_expiry")
  private LocalDate qualificationExpiry;

  @Column(name = "max_daily_sessions", nullable = false)
  private int maxDailySessions = 4;

  @Column(name = "preferred_campus")
  private String preferredCampus;

  @Column(name = "is_homeroom", nullable = false)
  private boolean homeroom;

  @Column(name = "homeroom_class_id")
  private UUID homeroomClassId;

  // 教师文件"所带班级"列声明的原始班级编码（即使班级尚未导入也持久化），用于导入顺序无关地回填
  // homeroom_class_id（见 ImportCommitService.commitClasses 回填逻辑）。
  @Column(name = "homeroom_class_code")
  private String homeroomClassCode;

  public Teacher() {
  }

  public String getTeacherNo() {
    return teacherNo;
  }

  public void setTeacherNo(String teacherNo) {
    this.teacherNo = teacherNo;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getGender() {
    return gender;
  }

  public void setGender(String gender) {
    this.gender = gender;
  }

  public String getGrade() {
    return grade;
  }

  public void setGrade(String grade) {
    this.grade = grade;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Integer getAge() {
    return age;
  }

  public void setAge(Integer age) {
    this.age = age;
  }

  public String getPosition() {
    return position;
  }

  public void setPosition(String position) {
    this.position = position;
  }

  public List<Map<String, Object>> getLeaveWindows() {
    return leaveWindows;
  }

  public void setLeaveWindows(List<Map<String, Object>> leaveWindows) {
    this.leaveWindows = copyWindows(leaveWindows);
  }

  public List<String> getFunctionalAccommodations() {
    return functionalAccommodations;
  }

  public void setFunctionalAccommodations(List<String> functionalAccommodations) {
    this.functionalAccommodations = functionalAccommodations == null
        ? new ArrayList<>()
        : new ArrayList<>(functionalAccommodations);
  }

  public List<Map<String, Object>> getDutyWindows() {
    return dutyWindows;
  }

  public void setDutyWindows(List<Map<String, Object>> dutyWindows) {
    this.dutyWindows = copyWindows(dutyWindows);
  }

  public List<String> getQualifications() {
    return qualifications;
  }

  public void setQualifications(List<String> qualifications) {
    this.qualifications = qualifications == null
        ? new ArrayList<>(List.of("INVIGILATOR"))
        : new ArrayList<>(qualifications);
  }

  public LocalDate getQualificationExpiry() {
    return qualificationExpiry;
  }

  public void setQualificationExpiry(LocalDate qualificationExpiry) {
    this.qualificationExpiry = qualificationExpiry;
  }

  public int getMaxDailySessions() {
    return maxDailySessions;
  }

  public void setMaxDailySessions(int maxDailySessions) {
    this.maxDailySessions = maxDailySessions;
  }

  public String getPreferredCampus() {
    return preferredCampus;
  }

  public void setPreferredCampus(String preferredCampus) {
    this.preferredCampus = preferredCampus;
  }

  public boolean isHomeroom() {
    return homeroom;
  }

  public void setHomeroom(boolean homeroom) {
    this.homeroom = homeroom;
  }

  public UUID getHomeroomClassId() {
    return homeroomClassId;
  }

  public void setHomeroomClassId(UUID homeroomClassId) {
    this.homeroomClassId = homeroomClassId;
  }

  public String getHomeroomClassCode() {
    return homeroomClassCode;
  }

  public void setHomeroomClassCode(String homeroomClassCode) {
    this.homeroomClassCode = homeroomClassCode;
  }

  private List<Map<String, Object>> copyWindows(List<Map<String, Object>> windows) {
    if (windows == null) {
      return new ArrayList<>();
    }
    List<Map<String, Object>> copied = new ArrayList<>(windows.size());
    for (Map<String, Object> window : windows) {
      copied.add(window == null ? new LinkedHashMap<>() : new LinkedHashMap<>(window));
    }
    return copied;
  }
}
