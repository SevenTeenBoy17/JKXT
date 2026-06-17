package com.jkxt.notify.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification")
public class Notification extends TenantEntity {

  @Column(name = "exam_task_id")
  private UUID examTaskId;

  @Column(nullable = false)
  private String type;

  @Column(nullable = false)
  private String title;

  private String body;

  @Column(nullable = false)
  private String status = "queued";

  @Column(name = "target_url")
  private String targetUrl;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private Map<String, Object> payload = new LinkedHashMap<>();

  @Column(name = "published_at")
  private Instant publishedAt;

  public UUID getExamTaskId() {
    return examTaskId;
  }

  public void setExamTaskId(UUID examTaskId) {
    this.examTaskId = examTaskId;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getTargetUrl() {
    return targetUrl;
  }

  public void setTargetUrl(String targetUrl) {
    this.targetUrl = targetUrl;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public void setPayload(Map<String, Object> payload) {
    this.payload = payload == null ? new LinkedHashMap<>() : payload;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(Instant publishedAt) {
    this.publishedAt = publishedAt;
  }
}
