package com.jkxt.common.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.org.repo.OrganizationRepository;
import com.jkxt.security.auth.SessionService;
import com.jkxt.security.repo.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({HealthController.class, VersionController.class})
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class HealthAndVersionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SessionService sessionService;

  @MockitoBean
  private DeployProperties deployProperties;

  @MockitoBean
  private OrganizationRepository organizationRepository;

  @MockitoBean
  private UserAccountRepository userAccountRepository;

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
