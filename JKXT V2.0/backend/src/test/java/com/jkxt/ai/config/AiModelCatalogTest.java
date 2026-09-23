package com.jkxt.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AiModelCatalogTest {
  @Test
  void catalogueContainsEightyUniqueExactUserModelCodes() {
    assertThat(AiModelCatalog.models()).hasSize(80);
    assertThat(AiModelCatalog.models().stream().map(AiModelCatalog.Model::id))
        .doesNotHaveDuplicates()
        .contains("qwen3.7-plus", "qwen3.7-max-2026-06-08", "MiniMax-M2.5", "MiniMax-M2.1",
            "deepseek-v4-pro-0813", "tongyi-intent-detect-v3", "qwen3.5-397b-a17b");
  }

  @Test
  void specializedModelFamiliesCannotGenerateOperationalPolicies() {
    for (String id : java.util.List.of("gui-plus", "qwen-mt-flash", "qwen-vl-ocr-latest", "qvq-max",
        "qwen3-vl-32b-thinking", "qwen-math-turbo", "qwen-plus-character", "tongyi-intent-detect-v3")) {
      assertThat(AiModelCatalog.find(id).policyCompatible()).as(id).isFalse();
    }
    assertThat(AiModelCatalog.find("qwen-plus").policyCompatible()).isTrue();
    assertThat(AiModelCatalog.policyModelIds()).containsExactly("qwen-plus", "qwen-flash");
    assertThat(AiModelCatalog.find("untrusted-model")).isNull();
  }
}
