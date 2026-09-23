package com.jkxt.scheduling.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "schedule_revision")
public class ScheduleRevision extends TenantEntity {

  @Column(name = "exam_task_id", nullable = false)
  private UUID examTaskId;

  @Column(name = "schedule_run_id", nullable = false)
  private UUID scheduleRunId;

  @Column(name = "revision_no", nullable = false)
  private int revisionNo;

  @Column(nullable = false)
  private String action = "reassign";

  @Column(name = "assignment_id", nullable = false)
  private UUID assignmentId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "before_state", nullable = false)
  private Map<String, Object> beforeState = new LinkedHashMap<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "after_state", nullable = false)
  private Map<String, Object> afterState = new LinkedHashMap<>();

  @Column(nullable = false)
  private String status = "applied";

  @Column(name = "created_by")
  private UUID createdBy;

  public UUID getExamTaskId() {
    return examTaskId;
  }

  public void setExamTaskId(UUID examTaskId) {
    this.examTaskId = examTaskId;
  }

  public UUID getScheduleRunId() {
    return scheduleRunId;
  }

  public void setScheduleRunId(UUID scheduleRunId) {
    this.scheduleRunId = scheduleRunId;
  }

  public int getRevisionNo() {
    return revisionNo;
  }

  public void setRevisionNo(int revisionNo) {
    this.revisionNo = revisionNo;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public UUID getAssignmentId() {
    return assignmentId;
  }

  public void setAssignmentId(UUID assignmentId) {
    this.assignmentId = assignmentId;
  }

  public Map<String, Object> getBeforeState() {
    return beforeState;
  }

  public void setBeforeState(Map<String, Object> beforeState) {
    this.beforeState = beforeState == null ? new LinkedHashMap<>() : beforeState;
  }

  public Map<String, Object> getAfterState() {
    return afterState;
  }

  public void setAfterState(Map<String, Object> afterState) {
    this.afterState = afterState == null ? new LinkedHashMap<>() : afterState;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(UUID createdBy) {
    this.createdBy = createdBy;
  }
}
