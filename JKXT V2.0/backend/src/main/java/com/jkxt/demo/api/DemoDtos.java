package com.jkxt.demo.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jkxt.security.api.AuthDtos.*;
import com.jkxt.security.auth.PortalPurpose;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;

public final class DemoDtos {
  private DemoDtos() {}
  public enum Intent {START,RESTORE,RECOVER_INITIAL,REISSUE}
  public record SessionRequest(UUID operationId,@NotNull Intent intent) {
    @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException("Unknown demo field");}
  }
  public record ResetRequest(@NotNull UUID operationId,boolean confirmed) {
    @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException("Unknown reset field");}
  }
  public record SimulationRequest(@NotNull UUID operationId) {
    @JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException("Unknown simulation field");}
  }
  public record DemoOverview(long teacherCount,long roomCount,long examCount,List<DemoTaskSummary> recentTasks) {}
  public record DemoTaskSummary(UUID taskId,String title,String status,Instant updatedAt) {}
  public record SimulationResult(UUID taskId,String operationId,String label,Instant createdAt) {}
  public record WorkspaceResponse(UserView user,OrgView org,TermView term,List<String> permissions,boolean onboarded,
      PortalPurpose audience,String sessionContext,String csrfToken,Instant expiresAt,DemoView demo,UUID workspaceId,String state,Instant idleExpiresAt) {}
}
