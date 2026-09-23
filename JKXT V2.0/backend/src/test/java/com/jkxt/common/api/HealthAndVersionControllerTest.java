package com.jkxt.common.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.org.repo.OrganizationRepository;
import com.jkxt.security.auth.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({HealthController.class, VersionController.class})
@AutoConfigureMockMvc(addFilters = false)
class HealthAndVersionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private SessionService sessionService;

  @MockBean
  private DeployProperties deployProperties;

  @MockBean
  private OrganizationRepository organizationRepository;

  @Test
  void healthEndpointReturnsP1UpPayload() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.service").value("jkxt-backend"))
        .andExpect(jsonPath("$.stage").value("P1"))
        .andExpect(jsonPath("$.version").value("0.1.0-p1"));
  }

  @Test
  void versionEndpointReturnsRuntimeMetadata() throws Exception {
    mockMvc.perform(get("/api/v1/version"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("jkxt-backend"))
        .andExpect(jsonPath("$.version").value("0.1.0-p1"))
        .andExpect(jsonPath("$.stage").value("P1"))
        .andExpect(jsonPath("$.apiBase").value("/api/v1"))
        .andExpect(jsonPath("$.javaFeature").value(greaterThanOrEqualTo(21)));
  }
}
