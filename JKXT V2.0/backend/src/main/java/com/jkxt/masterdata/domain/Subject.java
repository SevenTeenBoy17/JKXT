package com.jkxt.masterdata.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "subject")
public class Subject extends TenantEntity {

  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String category = "main";

  @Column(name = "exam_type", nullable = false)
  private String examType = "written";

  private BigDecimal hours;

  private String color;

  @Column(nullable = false)
  private String status = "active";

  public Subject() {
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

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getExamType() {
    return examType;
  }

  public void setExamType(String examType) {
    this.examType = examType;
  }

  public BigDecimal getHours() {
    return hours;
  }

  public void setHours(BigDecimal hours) {
    this.hours = hours;
  }

  public String getColor() {
    return color;
  }

  public void setColor(String color) {
    this.color = color;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
