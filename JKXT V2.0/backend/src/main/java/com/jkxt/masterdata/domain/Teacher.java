package com.jkxt.masterdata.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

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

  @Column(name = "is_homeroom", nullable = false)
  private boolean homeroom;

  @Column(name = "homeroom_class_id")
  private UUID homeroomClassId;

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
}
