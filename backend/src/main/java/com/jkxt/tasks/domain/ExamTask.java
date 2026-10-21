package com.jkxt.tasks.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "exam_task")
public class ExamTask extends TenantEntity {

  @Column(name = "task_no", nullable = false)
  private String taskNo;

  @Column(nullable = false)
  private String name;

  @Column(name = "term_id")
  private UUID termId;

  @Column(nullable = false)
  private String status = "draft";

  @Column(nullable = false)
  private String source = "manual";

  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(name = "starts_on")
  private LocalDate startsOn;

  @Column(name = "ends_on")
  private LocalDate endsOn;

  @Column(name = "published_at")
  private Instant publishedAt;

  public ExamTask() {
  }

  public String getTaskNo() {
    return taskNo;
  }

  public void setTaskNo(String taskNo) {
    this.taskNo = taskNo;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public UUID getTermId() {
    return termId;
  }

  public void setTermId(UUID termId) {
    this.termId = termId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(UUID ownerId) {
    this.ownerId = ownerId;
  }

  public LocalDate getStartsOn() {
    return startsOn;
  }

  public void setStartsOn(LocalDate startsOn) {
    this.startsOn = startsOn;
  }

  public LocalDate getEndsOn() {
    return endsOn;
  }

  public void setEndsOn(LocalDate endsOn) {
    this.endsOn = endsOn;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }
}
