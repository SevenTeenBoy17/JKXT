package com.jkxt.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PiiMaskerTest {
  private final PiiMasker masker = new PiiMasker();

  @Test
  void masksWholeDottedWorkspaceKeyWithoutRetainingSuffix() {
    String token = "sk-ws-ABC.synthetic_segment.signature-example-1234";
    String masked = masker.maskText("API key=" + token + "，请勿记录。");
    assertThat(masked).isEqualTo("API key=sk-***，请勿记录。");
    assertThat(masked.contains(token)).isFalse();
  }

  @Test
  void masksBearerAndLegacyKeysAndDoesNotEatSurroundingChinese() {
    assertThat(masker.maskText("Bearer synthetic.token_signature_123456"))
        .isEqualTo("Bearer ***");
    assertThat(masker.maskText("密钥sk-abcdefghijklmnop，请保密"))
        .isEqualTo("密钥sk-***，请保密");
  }
}
