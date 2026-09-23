package com.jkxt.masterdata.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "school_class")
public class SchoolClass extends TenantEntity {

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String grade;

  private String stage;

  @Column(name = "student_count", nullable = false)
  private int studentCount;

  @Column(name = "homeroom_teacher_id")
  private UUID homeroomTeacherId;

  @Column(name = "default_classroom_id")
  private UUID defaultClassroomId;

  @Column(nullable = false)
  private String status = "active";

  public SchoolClass() {
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getGrade() {
    return grade;
  }

  public void setGrade(String grade) {
    this.grade = grade;
  }

  public String getStage() {
    return stage;
  }

  public void setStage(String stage) {
    this.stage = stage;
  }

  public int getStudentCount() {
    return studentCount;
  }

  public void setStudentCount(int studentCount) {
    this.studentCount = studentCount;
  }

  public UUID getHomeroomTeacherId() {
    return homeroomTeacherId;
  }

  public void setHomeroomTeacherId(UUID homeroomTeacherId) {
    this.homeroomTeacherId = homeroomTeacherId;
  }

  public UUID getDefaultClassroomId() {
    return defaultClassroomId;
  }

  public void setDefaultClassroomId(UUID defaultClassroomId) {
    this.defaultClassroomId = defaultClassroomId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
