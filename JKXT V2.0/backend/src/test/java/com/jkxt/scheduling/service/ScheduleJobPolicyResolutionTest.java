package com.jkxt.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.scheduling.solver.SchedulePolicy;
import org.junit.jupiter.api.Test;

/**
 * Epic 4 — a parsed policy DSL resolves into solver flags: a hard rule is turned off only when the
 * DSL explicitly disables it (enabled=false); otherwise it stays on. This is the exact node the
 * AI parse persists to {@code ai_call_log.masked_output.dsl} and that {@code resolvePolicy} reads.
 */
class ScheduleJobPolicyResolutionTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode dsl(String json) throws Exception {
    return mapper.readTree(json);
  }

  @Test
  void explicitlyDisabledSameGradeRuleTurnsOffOnlyThatConstraint() throws Exception {
    SchedulePolicy policy = ScheduleJobService.policyFromDsl(dsl(
        "{\"hard\":[{\"id\":\"r1\",\"type\":\"same_grade_avoid\",\"enabled\":false,\"severity\":\"hard\",\"params\":{}}],\"soft\":[]}"));

    assertThat(policy.avoidSameGrade()).isFalse();
    assertThat(policy.avoidHomeroomClass()).isTrue();
  }

  @Test
  void explicitlyDisabledHomeroomRuleTurnsOffOnlyThatConstraint() throws Exception {
    SchedulePolicy policy = ScheduleJobService.policyFromDsl(dsl(
        "{\"hard\":[{\"type\":\"teacher_avoid_class\",\"enabled\":false}],\"soft\":[]}"));

    assertThat(policy.avoidHomeroomClass()).isFalse();
    assertThat(policy.avoidSameGrade()).isTrue();
  }

  @Test
  void absentOrEnabledRulesKeepAllConstraintsOn() throws Exception {
    assertThat(ScheduleJobService.policyFromDsl(dsl("{\"hard\":[],\"soft\":[]}")))
        .isEqualTo(SchedulePolicy.allOn());
    assertThat(ScheduleJobService.policyFromDsl(dsl(
            "{\"hard\":[{\"type\":\"same_grade_avoid\",\"enabled\":true}],\"soft\":[]}"))
        .avoidSameGrade()).isTrue();
  }
}
