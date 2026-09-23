package com.jkxt.common.config;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jkxt.deploy")
public class DeployProperties {

  public static final UUID DEFAULT_PRIVATE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private String mode = "dev";
  private String fixedOrgId = DEFAULT_PRIVATE_ORG_ID.toString();
  private boolean externalSmsEnabled = false;

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public String getFixedOrgId() {
    return fixedOrgId;
  }

  public void setFixedOrgId(String fixedOrgId) {
    this.fixedOrgId = fixedOrgId;
  }

  public boolean isExternalSmsEnabled() {
    return externalSmsEnabled;
  }

  public void setExternalSmsEnabled(boolean externalSmsEnabled) {
    this.externalSmsEnabled = externalSmsEnabled;
  }

  /**
   * 本地开发部署。仅用于放行**开发脚手架**（如 P6 求解器 fixture 端点），
   * 不得用于放宽任何面向真实数据的业务权限。
   */
  public boolean isDev() {
    return "dev".equals(normalizedMode());
  }

  public boolean isPrivate() {
    return "private".equals(normalizedMode());
  }

  public boolean isSaas() {
    return "saas".equals(normalizedMode());
  }

  public boolean isDemo() {
    return "demo".equals(normalizedMode());
  }

  public UUID fixedOrgUuid() {
    if (fixedOrgId == null || fixedOrgId.isBlank()) {
      return DEFAULT_PRIVATE_ORG_ID;
    }
    return UUID.fromString(fixedOrgId.trim());
  }

  private String normalizedMode() {
    return mode == null ? "dev" : mode.trim().toLowerCase(Locale.ROOT);
  }
}
