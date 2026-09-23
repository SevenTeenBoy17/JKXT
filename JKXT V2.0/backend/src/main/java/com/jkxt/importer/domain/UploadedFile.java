package com.jkxt.importer.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "uploaded_file")
public class UploadedFile extends TenantEntity {

  @Column(name = "exam_task_id")
  private UUID examTaskId;

  @Column(nullable = false)
  private String kind;

  @Column(name = "original_name", nullable = false)
  private String originalName;

  @Column(name = "storage_key", nullable = false)
  private String storageKey;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  private String sha256;

  public UploadedFile() {
  }

  public UUID getExamTaskId() {
    return examTaskId;
  }

  public void setExamTaskId(UUID examTaskId) {
    this.examTaskId = examTaskId;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getOriginalName() {
    return originalName;
  }

  public void setOriginalName(String originalName) {
    this.originalName = originalName;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public void setStorageKey(String storageKey) {
    this.storageKey = storageKey;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }
}
