package com.jkxt.examops.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class DutyAssignmentSelfServiceDtos {

  private DutyAssignmentSelfServiceDtos() {
  }

  public record RejectDutyRequest(
      @NotBlank(message = "拒绝原因不能为空")
      @Size(max = 500, message = "拒绝原因不能超过 500 个字符")
      String reason
  ) {
  }

  public record DutyActionResponse(
      UUID assignmentId,
      UUID revisionId,
      String status,
      String reason,
      Instant respondedAt,
      Instant checkedInAt,
      UUID substitutionCaseId,
      boolean replayed
  ) {
  }
}
