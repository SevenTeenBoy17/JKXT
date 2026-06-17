package com.jkxt.common.web;

import com.jkxt.security.auth.AuthException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class RestExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

  @ExceptionHandler(AuthException.class)
  ResponseEntity<ApiErrorResponse> handleAuth(AuthException exception) {
    return ResponseEntity
        .status(exception.getStatus())
        .body(ApiErrorResponse.of(
            exception.getCode(),
            exception.getMessage(),
            exception.getStatus().value()
        ));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrorResponse> handleApi(ApiException exception) {
    return ResponseEntity
        .status(exception.getStatus())
        .body(ApiErrorResponse.of(
            exception.getCode(),
            exception.getMessage(),
            exception.getStatus().value()
        ));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
    List<ApiErrorResponse.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
        .map(this::fieldError)
        .toList();
    return ResponseEntity
        .badRequest()
        .body(ApiErrorResponse.of(
            "VALIDATION_FAILED",
            "Request validation failed",
            HttpStatus.BAD_REQUEST.value(),
            errors
        ));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiErrorResponse> handleMissingRequestParameter(MissingServletRequestParameterException exception) {
    return ResponseEntity
        .badRequest()
        .body(ApiErrorResponse.of(
            "BAD_REQUEST",
            "Missing required request parameter: " + exception.getParameterName(),
            HttpStatus.BAD_REQUEST.value()
        ));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
    int status = exception.getStatusCode().value();
    String code = switch (status) {
      case 400 -> "BAD_REQUEST";
      case 403 -> "FORBIDDEN";
      case 404 -> "NOT_FOUND";
      case 409 -> "CONFLICT";
      default -> "REQUEST_FAILED";
    };
    return ResponseEntity
        .status(exception.getStatusCode())
        .body(ApiErrorResponse.of(
            code,
            exception.getReason() == null ? exception.getMessage() : exception.getReason(),
            status
        ));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
    log.error("Unhandled API exception", exception);
    return ResponseEntity
        .status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiErrorResponse.of(
            "INTERNAL_ERROR",
            "Internal server error",
            HttpStatus.INTERNAL_SERVER_ERROR.value()
        ));
  }

  private ApiErrorResponse.FieldError fieldError(FieldError error) {
    return new ApiErrorResponse.FieldError(error.getField(), error.getDefaultMessage());
  }
}
