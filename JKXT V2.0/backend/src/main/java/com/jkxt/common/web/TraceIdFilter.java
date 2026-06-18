package com.jkxt.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TRACE_BYTES = 16;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String traceId = normalize(request.getHeader(TraceId.HEADER));
    MDC.put(TraceId.MDC_KEY, traceId);
    response.setHeader(TraceId.HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TraceId.MDC_KEY);
    }
  }

  private String normalize(String incoming) {
    if (incoming == null || incoming.isBlank()) {
      return generate();
    }
    String trimmed = incoming.trim();
    if (trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9._:-]+")) {
      return generate();
    }
    return trimmed;
  }

  private String generate() {
    byte[] bytes = new byte[TRACE_BYTES];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
