package com.jkxt.importer.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "import_batch")
public class ImportBatch extends TenantEntity {

  @Column(name = "exam_task_id")
  private UUID examTaskId;

  @Column(name = "uploaded_file_id")
  private UUID uploadedFileId;

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String status = "uploaded";

  @Column(name = "row_count", nullable = false)
  private int rowCount;

  @Column(name = "valid_count", nullable = false)
  private int validCount;

  @Column(name = "error_count", nullable = false)
  private int errorCount;

  public ImportBatch() {
  }

  public UUID getExamTaskId() {
    return examTaskId;
  }

  public void setExamTaskId(UUID examTaskId) {
    this.examTaskId = examTaskId;
  }

  public UUID getUploadedFileId() {
    return uploadedFileId;
  }

  public void setUploadedFileId(UUID uploadedFileId) {
    this.uploadedFileId = uploadedFileId;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String kind) {
    this.kind = kind;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getRowCount() {
    return rowCount;
  }

  public void setRowCount(int rowCount) {
    this.rowCount = rowCount;
  }

  public int getValidCount() {
    return validCount;
  }

  public void setValidCount(int validCount) {
    this.validCount = validCount;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public void setErrorCount(int errorCount) {
    this.errorCount = errorCount;
  }
}
