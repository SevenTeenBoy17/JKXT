package com.jkxt.ai.client;

import java.math.BigDecimal;
import java.util.Map;

public interface LlmClient {

  ChatResponse chat(ChatRequest request);

  record ChatRequest(
      String operation,
      String provider,
      String model,
      String systemPrompt,
      String userPrompt,
      Map<String, Object> maskedInput
  ) {
  }

  record ChatResponse(
      String provider,
      String model,
      String content,
      int latencyMs,
      Map<String, Object> tokenUsage,
      BigDecimal costCents,
      String status,
      boolean fallback,
      String errorMessage
  ) {
  }
}
