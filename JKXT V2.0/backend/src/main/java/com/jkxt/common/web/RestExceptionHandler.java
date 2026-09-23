package com.jkxt.common.web;

import com.jkxt.security.auth.AuthException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class RestExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

  @ExceptionHandler(com.jkxt.demo.DemoImportLimits.ImportBusyException.class)
  ResponseEntity<ApiErrorResponse> handleDemoImportBusy(com.jkxt.demo.DemoImportLimits.ImportBusyException exception) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER,"5")
        .body(ApiErrorResponse.of(exception.getCode(),exception.getMessage(),503));
  }

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

  /**
   * Method-security denials happen after the servlet security filter chain and therefore do not
   * reach its access-denied handler. Keep their public contract identical to filter-level RBAC
   * denials instead of allowing them to fall through to the generic 500 handler.
   */
  @ExceptionHandler(AuthorizationDeniedException.class)
  ResponseEntity<ApiErrorResponse> handleAuthorizationDenied(AuthorizationDeniedException exception) {
    return ResponseEntity
        .status(HttpStatus.FORBIDDEN)
        .body(ApiErrorResponse.of(
            "AUTH_FORBIDDEN",
            "Permission is required",
            HttpStatus.FORBIDDEN.value()
        ));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrorResponse> handleApi(ApiException exception) {
    return ResponseEntity
        .status(exception.getStatus())
        .body(ApiErrorResponse.of(
            exception.getCode(),
            exception.getMessage(),
            exception.getStatus().value(),
            exception.getErrors()
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

  @ExceptionHandler(OptimisticLockingFailureException.class)
  ResponseEntity<ApiErrorResponse> handleOptimisticLock(OptimisticLockingFailureException exception) {
    return ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of(
            "RESOURCE_CONFLICT",
            "资源已被并发修改，请重新加载后重试",
            HttpStatus.CONFLICT.value()
        ));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException exception) {
    return ResponseEntity
        .badRequest()
        .body(ApiErrorResponse.of(
            "MALFORMED_REQUEST",
            "请求体格式不正确",
            HttpStatus.BAD_REQUEST.value()
        ));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
    String[] supported = exception.getSupportedMethods();
    if (supported != null && supported.length > 0) {
      builder.header(HttpHeaders.ALLOW, String.join(", ", supported));
    }
    return builder.body(ApiErrorResponse.of(
        "METHOD_NOT_ALLOWED",
        "请求方法不被允许",
        HttpStatus.METHOD_NOT_ALLOWED.value()
    ));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
    return ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ApiErrorResponse.of(
            "NOT_FOUND",
            "资源不存在",
            HttpStatus.NOT_FOUND.value()
        ));
  }

  // A wrong/unsupported request Content-Type is a client error (was falling through to 500).
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
    return ResponseEntity
        .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(ApiErrorResponse.of(
            "UNSUPPORTED_MEDIA_TYPE",
            "请求的内容类型不被支持",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()
        ));
  }

  // A malformed path/query argument (e.g. non-UUID path variable, non-boolean query param) is a
  // client error -> 400, distinct from the 404 returned for a syntactically valid but missing id.
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
    return ResponseEntity
        .badRequest()
        .body(ApiErrorResponse.of(
            "BAD_REQUEST",
            "参数 " + exception.getName() + " 取值无效",
            HttpStatus.BAD_REQUEST.value()
        ));
  }

  // A bad/empty multipart upload is a client error, like a malformed JSON body.
  @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
  ResponseEntity<ApiErrorResponse> handleMultipart(Exception exception) {
    return ResponseEntity
        .badRequest()
        .body(ApiErrorResponse.of(
            "MALFORMED_REQUEST",
            "上传请求格式不正确（需 multipart/form-data 表单）",
            HttpStatus.BAD_REQUEST.value()
        ));
  }

  // Bad argument values raised by the domain (e.g. unknown fixture id) are client errors -> 400.
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
    return ResponseEntity
        .badRequest()
        .body(ApiErrorResponse.of(
            "BAD_REQUEST",
            exception.getMessage() == null ? "请求参数无效" : exception.getMessage(),
            HttpStatus.BAD_REQUEST.value()
        ));
  }

  // A lost write under DB contention (deadlock / pessimistic-lock / transient failure) is a
  // retryable conflict, not a server fault — surface it as 409 like the optimistic-lock case.
  @ExceptionHandler({PessimisticLockingFailureException.class, TransientDataAccessException.class})
  ResponseEntity<ApiErrorResponse> handleWriteConflict(Exception exception) {
    return ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(ApiErrorResponse.of(
            "RESOURCE_CONFLICT",
            "资源写入冲突，请重新加载后重试",
            HttpStatus.CONFLICT.value()
        ));
  }

  // User-supplied data that violates a DB constraint (a too-long cell, a duplicate natural key,
  // a check/not-null violation) is a CLIENT error, not a server fault. The importer validation
  // gate catches most of these up front, but anything it misses (e.g. an over-length 姓名/考试科目
  // overflowing its varchar) previously fell through to a generic 500. Surface it as a clean 400
  // so the operator can fix the offending cell instead of seeing "Internal server error".
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
    return ResponseEntity
        .badRequest()
        .body(ApiErrorResponse.of(
            "DATA_CONSTRAINT_VIOLATION",
            "导入数据违反字段约束（如字段超长或与现有数据重复），请检查后重试",
            HttpStatus.BAD_REQUEST.value()
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
