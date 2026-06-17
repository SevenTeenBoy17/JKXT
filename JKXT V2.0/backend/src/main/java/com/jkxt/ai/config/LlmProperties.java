package com.jkxt.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jkxt.llm")
public class LlmProperties {

  public static final String DEFAULT_BASE_URL = "https://api.llm-token.cn/v1";

  private boolean enabled = false;
  private String provider = "mock";
  private String baseUrl = DEFAULT_BASE_URL;
  private String apiKey = "";
  private String model = "deepseek-v4-pro";
  private int timeoutMs = 12000;
  private int maxRetries = 1;
  private int monthlyQuota = 1000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
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
