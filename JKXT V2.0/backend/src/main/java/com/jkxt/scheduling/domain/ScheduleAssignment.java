package com.jkxt.scheduling.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "schedule_assignment")
public class ScheduleAssignment extends TenantEntity {

  @Column(name = "schedule_run_id", nullable = false)
  private UUID scheduleRunId;

  @Column(name = "exam_id", nullable = false)
  private UUID examId;

  @Column(name = "classroom_id", nullable = false)
  private UUID classroomId;

  @Column(name = "teacher_id", nullable = false)
  private UUID teacherId;

  @Column(nullable = false)
  private String role;

  @Column(name = "exam_date", nullable = false)
  private LocalDate examDate;

  @Column(name = "start_time", nullable = false)
  private LocalTime startTime;

  @Column(name = "end_time", nullable = false)
  private LocalTime endTime;

  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> conflict = new LinkedHashMap<>();

  @Column(nullable = false)
  private String source = "solver";

  public ScheduleAssignment() {
  }

  public UUID getScheduleRunId() {
    return scheduleRunId;
  }

  public void setScheduleRunId(UUID scheduleRunId) {
    this.scheduleRunId = scheduleRunId;
  }

  public UUID getExamId() {
    return examId;
  }

  public void setExamId(UUID examId) {
    this.examId = examId;
  }

  public UUID getClassroomId() {
    return classroomId;
  }

  public void setClassroomId(UUID classroomId) {
    this.classroomId = classroomId;
  }

  public UUID getTeacherId() {
    return teacherId;
  }

  public void setTeacherId(UUID teacherId) {
    this.teacherId = teacherId;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public LocalDate getExamDate() {
    return examDate;
  }

  public void setExamDate(LocalDate examDate) {
    this.examDate = examDate;
  }

  public LocalTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalTime startTime) {
    this.startTime = startTime;
  }

  public LocalTime getEndTime() {
    return endTime;
  }

  public void setEndTime(LocalTime endTime) {
    this.endTime = endTime;
  }

  public Map<String, Object> getConflict() {
    return conflict;
  }

  public void setConflict(Map<String, Object> conflict) {
    this.conflict = conflict == null ? new LinkedHashMap<>() : conflict;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
