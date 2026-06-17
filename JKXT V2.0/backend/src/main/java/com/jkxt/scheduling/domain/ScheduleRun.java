package com.jkxt.scheduling.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "schedule_run")
public class ScheduleRun extends TenantEntity {

  @Column(name = "exam_task_id", nullable = false)
  private UUID examTaskId;

  @Column(nullable = false)
  private String status = "pending";

  private String stage;

  @Column(nullable = false)
  private int progress;

  @Column(nullable = false)
  private String solver = "ortools-cpsat";

  private Long seed;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "error_message")
  private String errorMessage;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private Map<String, Object> stats = new LinkedHashMap<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private List<Map<String, Object>> logs = new ArrayList<>();

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(nullable = false)
  private int revision;

  @Column(name = "revision_cursor", nullable = false)
  private int revisionCursor;

  public ScheduleRun() {
  }

  public UUID getExamTaskId() {
    return examTaskId;
  }

  public void setExamTaskId(UUID examTaskId) {
    this.examTaskId = examTaskId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getStage() {
    return stage;
  }

  public void setStage(String stage) {
    this.stage = stage;
  }

  public int getProgress() {
    return progress;
  }

  public void setProgress(int progress) {
    this.progress = progress;
  }

  public String getSolver() {
    return solver;
  }

  public void setSolver(String solver) {
    this.solver = solver;
  }

  public Long getSeed() {
    return seed;
  }

  public void setSeed(Long seed) {
    this.seed = seed;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Map<String, Object> getStats() {
    return stats;
  }

  public void setStats(Map<String, Object> stats) {
    this.stats = stats == null ? new LinkedHashMap<>() : stats;
  }

  public List<Map<String, Object>> getLogs() {
    return logs;
  }

  public void setLogs(List<Map<String, Object>> logs) {
    this.logs = logs == null ? new ArrayList<>() : logs;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public int getRevision() {
    return revision;
  }

  public void setRevision(int revision) {
    this.revision = revision;
  }

  public int getRevisionCursor() {
    return revisionCursor;
  }

  public void setRevisionCursor(int revisionCursor) {
    this.revisionCursor = revisionCursor;
  }
}
