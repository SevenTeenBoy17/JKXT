package com.jkxt.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned user-supplied catalogue, not an assertion of upstream availability or entitlement.
 * Specialized models are deliberately not eligible to generate operational scheduling policies.
 */
public final class AiModelCatalog {
  private static final Map<String, Model> MODELS = load();

  private AiModelCatalog() {}

  public record Model(String id, String capability, boolean policyCompatible, boolean jsonMode, String thinking) {}
  private record Document(int version, String source, List<Model> models) {}

  public static List<Model> models() {
    return List.copyOf(MODELS.values());
  }

  public static Model find(String id) {
    return MODELS.get(id);
  }

  public static List<String> policyModelIds() {
    return models().stream().filter(Model::policyCompatible).map(Model::id).toList();
  }

  private static Map<String, Model> load() {
    try (var stream = AiModelCatalog.class.getResourceAsStream("/ai-models.json")) {
      if (stream == null) throw new IllegalStateException("AI model catalogue is missing");
      Document document = new ObjectMapper().readValue(stream, Document.class);
      Map<String, Model> result = new LinkedHashMap<>();
      for (Model model : document.models()) {
        if (model.id() == null || model.id().isBlank() || result.putIfAbsent(model.id(), model) != null) {
          throw new IllegalStateException("AI model catalogue contains an invalid or duplicate ID");
        }
      }
      return java.util.Collections.unmodifiableMap(result);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load AI model catalogue", exception);
    }
  }
}
