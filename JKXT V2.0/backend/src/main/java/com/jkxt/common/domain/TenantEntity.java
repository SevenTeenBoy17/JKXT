package com.jkxt.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;

@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {

  @Column(name = "org_id", nullable = false)
  private UUID orgId;

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }
}
