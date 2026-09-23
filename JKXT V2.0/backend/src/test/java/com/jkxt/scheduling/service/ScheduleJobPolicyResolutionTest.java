package com.jkxt.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.scheduling.solver.SchedulePolicy;
import com.jkxt.tasks.api.ExamTaskDtos.ReviewedUpload;
import com.jkxt.common.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * An explicitly confirmed policy DSL enables only its declared optional rules. This is the node the
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
        "{\"hard\":[{\"id\":\"r1\",\"type\":\"same_grade_avoid\",\"enabled\":false,\"severity\":\"hard\",\"params\":{}},{\"type\":\"teacher_avoid_class\",\"enabled\":true}],\"soft\":[]}"));

    assertThat(policy.avoidSameGrade()).isFalse();
    assertThat(policy.avoidHomeroomClass()).isTrue();
  }

  @Test
  void explicitlyDisabledHomeroomRuleTurnsOffOnlyThatConstraint() throws Exception {
    SchedulePolicy policy = ScheduleJobService.policyFromDsl(dsl(
        "{\"hard\":[{\"type\":\"teacher_avoid_class\",\"enabled\":false},{\"type\":\"same_grade_avoid\",\"enabled\":true,\"severity\":\"hard\"}],\"soft\":[]}"));

    assertThat(policy.avoidHomeroomClass()).isFalse();
    assertThat(policy.avoidSameGrade()).isTrue();
  }

  @Test
  void absentRulesAreOffAndExplicitlyEnabledRulesAreOn() throws Exception {
    assertThat(ScheduleJobService.policyFromDsl(dsl("{\"hard\":[],\"soft\":[]}")))
        .isEqualTo(new SchedulePolicy(false, SchedulePolicy.SameGrade.NONE, false));
    assertThat(ScheduleJobService.policyFromDsl(dsl(
            "{\"hard\":[{\"type\":\"same_grade_avoid\",\"enabled\":true}],\"soft\":[]}"))
        .avoidSameGrade()).isTrue();
  }

  /** An opted-in subject preference must not introduce either unrelated avoidance rule. */
  @Test
  void subjectAvoidOnlyDslDoesNotAddGradeOrHomeroomAvoidance() throws Exception {
    SchedulePolicy policy = ScheduleJobService.policyFromDsl(dsl(
        "{\"hard\":[],\"soft\":[{\"id\":\"s1\",\"type\":\"subject_avoid\",\"enabled\":true,\"severity\":\"soft\"}]}"));

    assertThat(policy.avoidSubjectClass()).isTrue();
    assertThat(policy.sameGrade()).isEqualTo(SchedulePolicy.SameGrade.NONE);
    assertThat(policy.avoidHomeroomClass()).isFalse();
  }

  @Test
  void optimizeSeverityNeverStrengthensSameGradeToHard() throws Exception {
    SchedulePolicy policy = ScheduleJobService.policyFromDsl(dsl(
        "{\"hard\":[],\"soft\":[{\"type\":\"same_grade_avoid\",\"enabled\":true,\"severity\":\"optimize\"}]}"));
    assertThat(policy.sameGrade()).isEqualTo(SchedulePolicy.SameGrade.SOFT);
  }

  @Test
  void quickScheduleWithoutExplicitPolicyMustNeverReadLatestUnconfirmedAiLog() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ScheduleJobService service = new ScheduleJobService(null, null, null, null, null, null,
        null, null, null, null, jdbc, mock(org.springframework.transaction.PlatformTransactionManager.class),
        Runnable::run, mapper, null, null, null, null, null, null);
    SchedulePolicy policy = ReflectionTestUtils.invokeMethod(service, "resolvePolicy",
        java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
    assertThat(policy).isEqualTo(SchedulePolicy.defaults());
    verifyNoInteractions(jdbc);
  }

  @Test
  void explicitWorkloadOnlyPolicyNeverAddsUnrequestedAvoidanceRules() throws Exception {
    SchedulePolicy policy = ScheduleJobService.policyFromDsl(dsl(
        "{\"hard\":[],\"soft\":[{\"id\":\"S1\",\"type\":\"workload_balance\",\"enabled\":true,\"severity\":\"soft\",\"params\":{}}]}"));
    assertThat(policy.avoidHomeroomClass()).isFalse();
    assertThat(policy.sameGrade()).isEqualTo(SchedulePolicy.SameGrade.NONE);
    assertThat(policy.avoidSubjectClass()).isFalse();
  }

  @Test
  void reviewedUploadsMatchExactIdentitiesRegardlessOfOrdering() {
    UUID org = UUID.randomUUID(), task = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(org), eq(task))).thenReturn(List.of(
        Map.of("id", first, "kind", "teacher", "sha256", "hash1"),
        Map.of("id", second, "kind", "exam", "sha256", "hash2")));
    ReflectionTestUtils.invokeMethod(reviewService(jdbc), "assertReviewedUploads", org, task,
        List.of(new ReviewedUpload(second, "exam", "hash2"), new ReviewedUpload(first, "teacher", "hash1")));
  }

  @Test
  void changedContentOrAdditionalUploadRequiresFreshReview() {
    UUID org = UUID.randomUUID(), task = UUID.randomUUID(), file = UUID.randomUUID();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(org), eq(task))).thenReturn(List.of(
        Map.of("id", file, "kind", "teacher", "sha256", "new-content")));
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(reviewService(jdbc), "assertReviewedUploads", org, task,
        List.of(new ReviewedUpload(file, "teacher", "old-content"))))
        .isInstanceOf(ApiException.class).hasMessageContaining("已发生变化");
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(reviewService(jdbc), "assertReviewedUploads", org, task, List.of()))
        .isInstanceOf(ApiException.class).hasMessageContaining("已发生变化");
  }

  @Test
  void missingOrDuplicateUploadReviewCannotBypassReviewCheck() {
    UUID org = UUID.randomUUID(), task = UUID.randomUUID();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ScheduleJobService service = reviewService(jdbc);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "assertReviewedUploads", org, task, (Object) null))
        .isInstanceOf(ApiException.class).hasMessageContaining("请先校验");
    ReviewedUpload duplicate = new ReviewedUpload(UUID.randomUUID(), "exam", "hash");
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "assertReviewedUploads", org, task, List.of(duplicate, duplicate)))
        .isInstanceOf(ApiException.class).hasMessageContaining("标识无效");
    verifyNoInteractions(jdbc);
  }

  private ScheduleJobService reviewService(JdbcTemplate jdbc) {
    return new ScheduleJobService(null, null, null, null, null, null,
        null, null, null, null, jdbc, mock(org.springframework.transaction.PlatformTransactionManager.class),
        Runnable::run, mapper, null, null, null, null, null, null);
  }
}
