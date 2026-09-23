package com.jkxt.ai.service;

import com.jkxt.ai.api.AiDtos.PolicyDsl;
import com.jkxt.ai.api.AiDtos.RuleDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PolicyDslValidator {

  private static final Map<String, String> SELECTED_RULE_TYPES = Map.of(
      "homeroom_avoid", "teacher_avoid_class", "same_grade_avoid", "same_grade_avoid",
      "subject_off", "subject_avoid", "subject_avoid", "subject_avoid",
      "workload_balance", "workload_balance", "gender_mix", "gender_mix", "cross_grade", "cross_grade_prefer");

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
  private static final Set<String> ALLOWED_HARD_RULE_TYPES = Set.of(
      "teacher_avoid_class",
      "same_grade_avoid"
  );

  public List<String> validate(PolicyDsl dsl) {
    List<String> errors = new ArrayList<>();
    if (dsl == null) {
      errors.add("DSL root is missing.");
      return errors;
    }
    if (!"1.0".equals(dsl.version())) {
      errors.add("version must be 1.0.");
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
    if (dsl.explanations() == null || dsl.explanations().isEmpty()
        || dsl.explanations().stream().anyMatch(value -> value == null || value.isBlank())) {
      errors.add("explanations must contain at least one non-blank item.");
    }
    if (dsl.repairSuggestions() == null) {
      errors.add("repairSuggestions must be an array.");
    }
    if (dsl.needsReview() == null) {
      errors.add("needsReview is required.");
    }
    Set<String> ruleIds = new HashSet<>();
    validateRules("hard", dsl.hard(), ruleIds, errors);
    validateRules("soft", dsl.soft(), ruleIds, errors);
    return errors;
  }

  /** Selected controls are authoritative; model output must not silently invent or omit rules. */
  public List<String> validateSelection(PolicyDsl dsl, List<String> policyIds) {
    List<String> errors = new ArrayList<>();
    Set<String> selected = new HashSet<>();
    for (String id : policyIds == null ? List.<String>of() : policyIds) {
      String type = id == null ? null : SELECTED_RULE_TYPES.get(id);
      if (type == null) errors.add("Unsupported selected policy id: " + id);
      else selected.add(type);
    }
    if (dsl == null || dsl.hard() == null || dsl.soft() == null) return List.of("Policy selection requires valid rule arrays.");
    Set<String> enabled = new HashSet<>(), seen = new HashSet<>();
    for (List<RuleDto> group : List.of(dsl.hard(), dsl.soft())) {
      for (RuleDto rule : group) {
        if (rule == null || rule.type() == null || "manual_note".equals(rule.type())) continue;
        if (!seen.add(rule.type())) errors.add("Duplicate policy rule type: " + rule.type());
        if (Boolean.TRUE.equals(rule.enabled())) {
          enabled.add(rule.type());
          if (!selected.contains(rule.type())) errors.add("Unselected policy rule is enabled: " + rule.type());
        }
      }
    }
    for (String type : selected) {
      if (!enabled.contains(type)) errors.add("Selected policy rule is missing or disabled: " + type);
    }
    return errors;
  }

  private void validateRules(String group, List<RuleDto> rules, Set<String> ruleIds, List<String> errors) {
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
      } else if (!ruleIds.add(rule.id())) {
        errors.add(prefix + ".id is duplicated: " + rule.id());
      }
      if (!ALLOWED_RULE_TYPES.contains(rule.type())) {
        errors.add(prefix + ".type is unsupported: " + rule.type());
      } else if ("hard".equals(group) && !ALLOWED_HARD_RULE_TYPES.contains(rule.type())) {
        errors.add(prefix + ".type cannot be enforced as a hard DSL rule: " + rule.type());
      } else if ("soft".equals(group) && "teacher_avoid_class".equals(rule.type())) {
        errors.add(prefix + ".type requires hard severity: teacher_avoid_class");
      }
      if (rule.enabled() == null) {
        errors.add(prefix + ".enabled is required.");
      }
      if (!ALLOWED_SEVERITY.contains(rule.severity())) {
        errors.add(prefix + ".severity is unsupported: " + rule.severity());
      } else if ("hard".equals(group) && !"hard".equals(rule.severity())) {
        errors.add(prefix + ".severity must be hard.");
      } else if ("soft".equals(group) && "hard".equals(rule.severity())) {
        errors.add(prefix + ".severity must be soft or optimize.");
      }
      if (rule.params() == null) {
        errors.add(prefix + ".params must be an object.");
      }
    }
  }
}
