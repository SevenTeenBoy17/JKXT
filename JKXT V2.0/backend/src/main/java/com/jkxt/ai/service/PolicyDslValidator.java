package com.jkxt.ai.service;

import com.jkxt.ai.api.AiDtos.PolicyDsl;
import com.jkxt.ai.api.AiDtos.RuleDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PolicyDslValidator {

  private static final Set<String> ALLOWED_RULE_TYPES = Set.of(
      "teacher_avoid_class",
      "same_grade_avoid",
      "gender_mix",
      "subject_avoid",
      "workload_balance",
      "cross_grade_prefer",
      "availability_avoid",
      "capacity_guard",
      "rest_gap",
      "manual_note"
  );
  private static final Set<String> ALLOWED_SEVERITY = Set.of("hard", "soft", "optimize");

  public List<String> validate(PolicyDsl dsl) {
    List<String> errors = new ArrayList<>();
    if (dsl == null) {
      errors.add("DSL root is missing.");
      return errors;
    }
    if (dsl.version() == null || dsl.version().isBlank()) {
      errors.add("version is required.");
    }
    if (!"OR_TOOLS".equals(dsl.solverAuthority())) {
      errors.add("solverAuthority must be OR_TOOLS.");
    }
    if (dsl.hard() == null) {
      errors.add("hard rules must be an array.");
    }
    if (dsl.soft() == null) {
      errors.add("soft rules must be an array.");
    }
    if (dsl.confidence() == null || dsl.confidence() < 0 || dsl.confidence() > 1) {
      errors.add("confidence must be between 0 and 1.");
    }
    validateRules("hard", dsl.hard(), errors);
    validateRules("soft", dsl.soft(), errors);
    return errors;
  }

  private void validateRules(String group, List<RuleDto> rules, List<String> errors) {
    if (rules == null) {
      return;
    }
    if (rules.size() > 50) {
      errors.add(group + " rule count exceeds 50.");
    }
    for (int i = 0; i < rules.size(); i++) {
      RuleDto rule = rules.get(i);
      String prefix = group + "[" + i + "]";
      if (rule == null) {
        errors.add(prefix + " is null.");
        continue;
      }
      if (rule.id() == null || rule.id().isBlank()) {
        errors.add(prefix + ".id is required.");
      }
      if (!ALLOWED_RULE_TYPES.contains(rule.type())) {
        errors.add(prefix + ".type is unsupported: " + rule.type());
      }
      if (rule.enabled() == null) {
        errors.add(prefix + ".enabled is required.");
      }
      if (!ALLOWED_SEVERITY.contains(rule.severity())) {
        errors.add(prefix + ".severity is unsupported: " + rule.severity());
      }
      if (rule.params() == null) {
        errors.add(prefix + ".params must be an object.");
      }
    }
  }
}
