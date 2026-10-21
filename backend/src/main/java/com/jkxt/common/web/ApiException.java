package com.jkxt.common.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A business error carrying a stable machine-readable {@code code} alongside the HTTP status and a
 * human message. {@link RestExceptionHandler} surfaces all three in the uniform error envelope so
 * the frontend can branch on {@code code} (e.g. {@code SCHEDULE_INPUT_INCOMPLETE}) rather than
 * pattern-matching a localized message.
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final List<ApiErrorResponse.FieldError> errors;

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ApiException(
      HttpStatus status,
      String code,
      String message,
      List<ApiErrorResponse.FieldError> errors
  ) {
    super(message);
    this.status = status;
    this.code = code;
    this.errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public List<ApiErrorResponse.FieldError> getErrors() {
    return errors;
  }
}
