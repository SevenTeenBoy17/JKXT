package com.jkxt.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** No resource or environment identity is inferred or provisioned by the application. */
@Component
@ConfigurationProperties(prefix = "jkxt.demo")
public class DemoProperties {
  private String environmentId = "";
  private String databaseName = "";
  private String databaseUser = "";
  private String storageRoot = "";
  private String publicOrigin = "";
  private String schoolOrigin = "";
  public String getEnvironmentId() { return environmentId; }
  public void setEnvironmentId(String value) { environmentId = value; }
  public String getDatabaseName() { return databaseName; }
  public void setDatabaseName(String value) { databaseName = value; }
  public String getDatabaseUser() { return databaseUser; }
  public void setDatabaseUser(String value) { databaseUser = value; }
  public String getStorageRoot() { return storageRoot; }
  public void setStorageRoot(String value) { storageRoot = value; }
  public String getPublicOrigin() { return publicOrigin; }
  public void setPublicOrigin(String value) { publicOrigin = value; }
  public String getSchoolOrigin() { return schoolOrigin; }
  public void setSchoolOrigin(String value) { schoolOrigin = value; }
}
