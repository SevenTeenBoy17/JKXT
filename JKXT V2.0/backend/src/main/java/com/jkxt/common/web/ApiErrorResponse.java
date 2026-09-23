package com.jkxt.common.web;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
    String code,
    String message,
    int status,
    Instant timestamp,
    String traceId,
    List<FieldError> errors
) {

  public static ApiErrorResponse of(String code, String message, int status) {
    return new ApiErrorResponse(code, message, status, Instant.now(), TraceId.current(), List.of());
  }

  public static ApiErrorResponse of(String code, String message, int status, List<FieldError> errors) {
    return new ApiErrorResponse(code, message, status, Instant.now(), TraceId.current(), errors == null ? List.of() : errors);
  }

  public record FieldError(
      String field,
      String reason
  ) {
  }
}
