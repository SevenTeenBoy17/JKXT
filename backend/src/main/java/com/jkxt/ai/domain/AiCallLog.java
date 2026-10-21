package com.jkxt.ai.domain;

import com.jkxt.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_call_log")
public class AiCallLog extends BaseEntity {

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  @Column(name = "exam_task_id")
  private UUID examTaskId;

  @Column(name = "schedule_run_id")
  private UUID scheduleRunId;

  @Column(nullable = false)
  private String provider;

  private String model;

  @Column(nullable = false)
  private String purpose;

  @Column(name = "prompt_version")
  private String promptVersion;

  @Column(name = "input_hash")
  private String inputHash;

  @Column(nullable = false)
  private String status = "pending";

  @Column(name = "latency_ms")
  private Integer latencyMs;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "token_usage", nullable = false)
  private Map<String, Object> tokenUsage = new LinkedHashMap<>();

  @Column(name = "cost_cents")
  private BigDecimal costCents;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_meta", nullable = false)
  private Map<String, Object> requestMeta = new LinkedHashMap<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_meta", nullable = false)
  private Map<String, Object> responseMeta = new LinkedHashMap<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "masked_input", nullable = false)
  private Map<String, Object> maskedInput = new LinkedHashMap<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "masked_output", nullable = false)
  private Map<String, Object> maskedOutput = new LinkedHashMap<>();

  @Column(name = "error_message")
  private String errorMessage;

  public AiCallLog() {
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

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

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getPurpose() {
    return purpose;
  }

  public void setPurpose(String purpose) {
    this.purpose = purpose;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getPromptVersion() {
    return promptVersion;
  }

  public void setPromptVersion(String promptVersion) {
    this.promptVersion = promptVersion;
  }

  public String getInputHash() {
    return inputHash;
  }

  public void setInputHash(String inputHash) {
    this.inputHash = inputHash;
  }

  public Integer getLatencyMs() {
    return latencyMs;
  }

  public void setLatencyMs(Integer latencyMs) {
    this.latencyMs = latencyMs;
  }

  public Map<String, Object> getTokenUsage() {
    return tokenUsage;
  }

  public void setTokenUsage(Map<String, Object> tokenUsage) {
    this.tokenUsage = tokenUsage == null ? new LinkedHashMap<>() : tokenUsage;
  }

  public BigDecimal getCostCents() {
    return costCents;
  }

  public void setCostCents(BigDecimal costCents) {
    this.costCents = costCents;
  }

  public Map<String, Object> getRequestMeta() {
    return requestMeta;
  }

  public void setRequestMeta(Map<String, Object> requestMeta) {
    this.requestMeta = requestMeta == null ? new LinkedHashMap<>() : requestMeta;
  }

  public Map<String, Object> getResponseMeta() {
    return responseMeta;
  }

  public void setResponseMeta(Map<String, Object> responseMeta) {
    this.responseMeta = responseMeta == null ? new LinkedHashMap<>() : responseMeta;
  }

  public Map<String, Object> getMaskedInput() {
    return maskedInput;
  }

  public void setMaskedInput(Map<String, Object> maskedInput) {
    this.maskedInput = maskedInput == null ? new LinkedHashMap<>() : maskedInput;
  }

  public Map<String, Object> getMaskedOutput() {
    return maskedOutput;
  }

  public void setMaskedOutput(Map<String, Object> maskedOutput) {
    this.maskedOutput = maskedOutput == null ? new LinkedHashMap<>() : maskedOutput;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
