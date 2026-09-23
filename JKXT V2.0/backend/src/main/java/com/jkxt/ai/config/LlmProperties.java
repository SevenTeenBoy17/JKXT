package com.jkxt.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jkxt.llm")
public class LlmProperties {

  // Workspace URLs and credentials must be injected together. There is no legacy gateway default.
  public static final String DEFAULT_BASE_URL = "";

  private boolean enabled = false;
  private boolean acceptanceMode = false;
  private boolean allowTestMocks = false;
  private String provider = "aliyun";
  private String baseUrl = DEFAULT_BASE_URL;
  private String fallbackBaseUrl = "";
  private String apiKey = "";
  private String model = "qwen-plus";
  private java.util.List<String> fallbackModels = java.util.List.of("qwen-flash");
  private int timeoutMs = 60000;
  private int maxRetries = 2;
  private int monthlyQuota = 1000;
  private int maxPromptChars = 40000;
  private int maxOutputTokens = 4096;
  private int maxResponseBytes = 1048576;

  public boolean isAllowTestMocks() { return allowTestMocks; }
  public java.util.List<String> getFallbackModels() { return fallbackModels; }
  public void setFallbackModels(java.util.List<String> value) { fallbackModels = value == null ? java.util.List.of() : java.util.List.copyOf(value); }
  public void setAllowTestMocks(boolean value) { allowTestMocks = value; }
  public int getMaxPromptChars() { return maxPromptChars; }
  public void setMaxPromptChars(int value) { maxPromptChars = value; }
  public int getMaxOutputTokens() { return maxOutputTokens; }
  public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
  public int getMaxResponseBytes() { return maxResponseBytes; }
  public void setMaxResponseBytes(int value) { maxResponseBytes = value; }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * When enabled, a real upstream response is mandatory: local canned data and automatic fallback
   * must be reported as failures instead of being presented as a successful acceptance result.
   */
  public boolean isAcceptanceMode() {
    return acceptanceMode;
  }

  public void setAcceptanceMode(boolean acceptanceMode) {
    this.acceptanceMode = acceptanceMode;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getFallbackBaseUrl() {
    return fallbackBaseUrl;
  }

  public void setFallbackBaseUrl(String fallbackBaseUrl) {
    this.fallbackBaseUrl = fallbackBaseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(int timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public int getMonthlyQuota() {
    return monthlyQuota;
  }

  public void setMonthlyQuota(int monthlyQuota) {
    this.monthlyQuota = monthlyQuota;
  }

  public boolean hasCredential() {
    return apiKey != null && !apiKey.isBlank();
  }
}
