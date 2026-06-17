package com.jkxt.common.web;

import java.util.Optional;
import org.slf4j.MDC;

public final class TraceId {

  public static final String MDC_KEY = "traceId";
  public static final String HEADER = "X-Trace-Id";

  private TraceId() {
  }

  public static String current() {
    return Optional.ofNullable(MDC.get(MDC_KEY)).orElse("");
  }
}
