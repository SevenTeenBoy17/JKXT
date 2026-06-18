package com.jkxt.security.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "role")
public class Role extends TenantEntity {

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(name = "system_role", nullable = false)
  private boolean systemRole;

  public Role() {
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public boolean isSystemRole() {
    return systemRole;
  }

  public void setSystemRole(boolean systemRole) {
    this.systemRole = systemRole;
  }
}
