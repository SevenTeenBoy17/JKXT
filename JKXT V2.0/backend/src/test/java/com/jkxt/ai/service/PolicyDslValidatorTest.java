package com.jkxt.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.ai.api.AiDtos.PolicyDsl;
import com.jkxt.ai.api.AiDtos.RuleDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyDslValidatorTest {

  private final PolicyDslValidator validator = new PolicyDslValidator();

  @Test
  void acceptsCompleteDslWithEmptyRuleArrays() {
    PolicyDsl dsl = new PolicyDsl(
        "1.0",
        "OR_TOOLS",
        List.of(),
        List.of(),
        List.of("最终分配由 OR-Tools 生成。"),
        List.of(),
        0.8,
        true
    );

    assertThat(validator.validate(dsl)).isEmpty();
  }

  @Test
  void rejectsIncompleteRootWrongGroupSeverityAndDuplicateIds() {
    RuleDto hardWithSoftSeverity = new RuleDto(
        "DUPLICATE",
        "teacher_avoid_class",
        true,
        "soft",
        Map.of()
    );
    RuleDto softWithHardSeverity = new RuleDto(
        "DUPLICATE",
        "workload_balance",
        true,
        "hard",
        Map.of()
    );
    PolicyDsl dsl = new PolicyDsl(
        "2.0",
        "OR_TOOLS",
        List.of(hardWithSoftSeverity),
        List.of(softWithHardSeverity),
        List.of(),
        null,
        0.5,
        null
    );

    assertThat(validator.validate(dsl))
        .contains(
            "version must be 1.0.",
            "explanations must contain at least one non-blank item.",
            "repairSuggestions must be an array.",
            "needsReview is required.",
            "hard[0].severity must be hard.",
            "soft[0].id is duplicated: DUPLICATE",
            "soft[0].severity must be soft or optimize."
        );
  }

  @Test
  void rejectsRuleThatTheSolverCannotEnforceAsHard() {
    PolicyDsl dsl = new PolicyDsl(
        "1.0",
        "OR_TOOLS",
        List.of(new RuleDto("H_SUBJECT", "subject_avoid", true, "hard", Map.of())),
        List.of(),
        List.of("说明"),
        List.of(),
        0.9,
        true
    );

    assertThat(validator.validate(dsl))
        .contains("hard[0].type cannot be enforced as a hard DSL rule: subject_avoid");
  }

  @Test
  void homeroomCannotBeDeclaredSoftThenEnforcedHard() {
    PolicyDsl dsl = selectionDsl(List.of(new RuleDto("H1", "teacher_avoid_class", true, "soft", Map.of())));
    assertThat(validator.validate(dsl)).contains("soft[0].type requires hard severity: teacher_avoid_class");
  }

  @Test
  void selectionRequiresExactEnabledTypesAndRejectsDuplicateOrMissingRules() {
    RuleDto balance = new RuleDto("B1", "workload_balance", true, "soft", Map.of());
    assertThat(validator.validateSelection(selectionDsl(List.of(balance)), List.of("workload_balance"))).isEmpty();
    assertThat(validator.validateSelection(selectionDsl(List.of(balance)), List.of()))
        .contains("Unselected policy rule is enabled: workload_balance");
    assertThat(validator.validateSelection(selectionDsl(List.of()), List.of("homeroom_avoid")))
        .contains("Selected policy rule is missing or disabled: teacher_avoid_class");
    assertThat(validator.validateSelection(selectionDsl(List.of(balance, new RuleDto("B2", "workload_balance", false, "soft", Map.of()))), List.of("workload_balance")))
        .contains("Duplicate policy rule type: workload_balance");
  }

  private PolicyDsl selectionDsl(List<RuleDto> soft) {
    return new PolicyDsl("1.0", "OR_TOOLS", List.of(), soft, List.of("按已确认项目执行"), List.of(), 0.9, false);
  }
}
