package com.jkxt.ai.api;

import com.jkxt.ai.api.AiDtos.NlPolicyParseRequest;
import com.jkxt.ai.api.AiDtos.NlPolicyParseResponse;
import com.jkxt.ai.api.AiDtos.NlPolicyRefineRequest;
import com.jkxt.ai.api.AiDtos.NlPolicyRefineResponse;
import com.jkxt.ai.api.AiDtos.ProviderStatusResponse;
import com.jkxt.ai.service.AiPolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AiPolicyController {

  private final AiPolicyService aiPolicyService;

  public AiPolicyController(AiPolicyService aiPolicyService) {
    this.aiPolicyService = aiPolicyService;
  }

  @PostMapping("/nl-policy/parse")
  public NlPolicyParseResponse parse(@Valid @RequestBody NlPolicyParseRequest request) {
    return aiPolicyService.parse(request);
  }

  @PostMapping("/nl-policy/refine")
  public NlPolicyRefineResponse refine(@Valid @RequestBody NlPolicyRefineRequest request) {
    return aiPolicyService.refine(request);
  }

  @GetMapping("/ai/providers")
  public ProviderStatusResponse providers() {
    return aiPolicyService.providers();
  }

  @GetMapping("/ai/models")
  public java.util.List<AiDtos.ModelStatus> models() {
    return aiPolicyService.providers().catalog();
  }
}
