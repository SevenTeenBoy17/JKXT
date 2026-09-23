package com.jkxt.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jkxt.common.web.TraceId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P11ObservabilityIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void propagatesIncomingTraceIdOnSuccessfulRequests() throws Exception {
    mockMvc.perform(get("/api/v1/health").header(TraceId.HEADER, "p11-trace-001"))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceId.HEADER, "p11-trace-001"));
  }

  @Test
  void errorEnvelopeIncludesTraceIdAndStableCode() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/exam-tasks"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(TraceId.HEADER))
        .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
        .andExpect(jsonPath("$.traceId").exists())
        .andReturn();

    String headerTraceId = result.getResponse().getHeader(TraceId.HEADER);
    String body = result.getResponse().getContentAsString();
    assertThat(headerTraceId).isNotBlank();
    assertThat(body).contains("\"traceId\":\"" + headerTraceId + "\"");
  }
}
