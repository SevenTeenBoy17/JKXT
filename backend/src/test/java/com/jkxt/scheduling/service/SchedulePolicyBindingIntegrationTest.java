package com.jkxt.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.jkxt.ai.domain.AiCallLog;
import com.jkxt.ai.repo.AiCallLogRepository;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.importer.domain.UploadedFile;
import com.jkxt.importer.repo.UploadedFileRepository;
import com.jkxt.scheduling.solver.SchedulePolicy;
import com.jkxt.tasks.api.ExamTaskDtos.ReviewedUpload;
import com.jkxt.tasks.domain.ExamTask;
import com.jkxt.tasks.repo.ExamTaskRepository;
import com.jkxt.tasks.service.ExamTaskService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/** Database-bound evidence: explicit immutable audit ID, never the most recent unconfirmed parse. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchedulePolicyBindingIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  @Autowired private ScheduleJobService jobs;
  @Autowired private ExamTaskService taskService;
  @Autowired private ExamTaskRepository tasks;
  @Autowired private AiCallLogRepository audit;
  @Autowired private EntityManager entityManager;
  @Autowired private UploadedFileRepository files;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test
  void newerParseAndFailedParseCannotReplaceExplicitlyConfirmedPolicy() {
    ExamTask task = task("draft");
    AiCallLog confirmed = policy(task, "succeeded", false);
    AiCallLog newer = policy(task, "succeeded", true);
    policy(task, "failed", true);

    SchedulePolicy selected = ReflectionTestUtils.invokeMethod(jobs, "resolvePolicy", ORG, task.getId(), confirmed.getId());
    SchedulePolicy latest = ReflectionTestUtils.invokeMethod(jobs, "resolvePolicy", ORG, task.getId(), newer.getId());
    SchedulePolicy legacy = ReflectionTestUtils.invokeMethod(jobs, "resolvePolicy", ORG, task.getId());
    assertThat(selected.sameGrade()).isEqualTo(SchedulePolicy.SameGrade.NONE);
    assertThat(latest.sameGrade()).isEqualTo(SchedulePolicy.SameGrade.HARD);
    assertThat(legacy).isEqualTo(SchedulePolicy.defaults());
  }

  @Test
  void foreignTaskTenantAndUnsuccessfulAuditIdsAreRejected() {
    ExamTask task = task("draft"), other = task("draft");
    AiCallLog confirmed = policy(task, "succeeded", false);
    AiCallLog failed = policy(task, "failed", false);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(jobs, "resolvePolicy", ORG, other.getId(), confirmed.getId()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(jobs, "resolvePolicy", UUID.randomUUID(), task.getId(), confirmed.getId()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(jobs, "resolvePolicy", ORG, task.getId(), failed.getId()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void publishedValidationAndRestartReturnConflictWithoutMutatingPublication() {
    TenantContext.set(ORG);
    ExamTask task = task("published");
    Instant published = Instant.parse("2026-09-01T00:00:00Z");
    task.setPublishedAt(published);
    tasks.saveAndFlush(task);
    UUID id = task.getId();
    assertThatThrownBy(() -> taskService.validate(id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> taskService.upload(id, "teacher", null, true)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> jobs.startTaskRun(id)).isInstanceOf(ApiException.class);
    entityManager.clear();
    ExamTask unchanged = tasks.findByOrgIdAndId(ORG, id).orElseThrow();
    assertThat(unchanged.getStatus()).isEqualTo("published");
    assertThat(unchanged.getPublishedAt()).isEqualTo(published);
  }

  @Test
  void uploadAfterReviewIsRejectedBeforeRunCreationAndTaskMutation() {
    TenantContext.set(ORG);
    ExamTask task = task("draft");
    AiCallLog confirmed = policy(task, "succeeded", false);
    UploadedFile first = upload(task, "teacher", "first");
    List<ReviewedUpload> reviewed = List.of(new ReviewedUpload(first.getId(), first.getKind(), first.getSha256()));
    ReflectionTestUtils.invokeMethod(jobs, "assertReviewedUploads", ORG, task.getId(), reviewed);
    upload(task, "exam", "second");
    assertThatThrownBy(() -> jobs.startTaskRun(task.getId(), confirmed.getId(), reviewed))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("AI_REVIEW_STALE"));
    entityManager.clear();
    assertThat(tasks.findByOrgIdAndId(ORG, task.getId()).orElseThrow().getStatus()).isEqualTo("draft");
  }

  @Test
  void previouslySucceededButSemanticallyWrongPolicyIsRejectedWithoutRewritingAudit() {
    ExamTask task = task("draft");
    AiCallLog old = policy(task, "succeeded", true);
    old.setMaskedInput(Map.of("policyIds", List.of("workload_balance")));
    audit.saveAndFlush(old);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(jobs, "resolvePolicy", ORG, task.getId(), old.getId()))
        .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("AI_POLICY_INVALID"));
    assertThat(audit.findById(old.getId()).orElseThrow().getStatus()).isEqualTo("succeeded");
  }

  private UploadedFile upload(ExamTask task, String kind, String hash) {
    UploadedFile file = new UploadedFile();
    file.setOrgId(ORG); file.setExamTaskId(task.getId()); file.setKind(kind);
    file.setOriginalName("synthetic.xlsx"); file.setStorageKey("test/policy-binding/" + UUID.randomUUID());
    file.setSizeBytes(1); file.setSha256(hash);
    return files.saveAndFlush(file);
  }

  private ExamTask task(String status) {
    ExamTask task = new ExamTask();
    task.setOrgId(ORG); task.setTaskNo("PB-" + UUID.randomUUID());
    task.setName("Synthetic policy binding test"); task.setSource("quick"); task.setStatus(status);
    return tasks.saveAndFlush(task);
  }

  private AiCallLog policy(ExamTask task, String status, boolean avoidSameGrade) {
    AiCallLog call = new AiCallLog();
    call.setOrgId(ORG); call.setExamTaskId(task.getId()); call.setProvider("aliyun"); call.setModel("qwen-plus");
    call.setPurpose("nl2dsl"); call.setStatus(status); call.setRequestMeta(Map.of("fallback", false));
    call.setMaskedInput(Map.of("policyIds", avoidSameGrade ? List.of("same_grade_avoid") : List.of()));
    call.setMaskedOutput(Map.of("dsl", Map.of("version", "1.0", "solverAuthority", "OR_TOOLS",
        "hard", List.of(Map.of("id", "G1", "type", "same_grade_avoid", "enabled", avoidSameGrade, "severity", "hard", "params", Map.of())),
        "soft", List.of(), "explanations", List.of("Synthetic confirmed selection"), "repairSuggestions", List.of(), "confidence", 0.9, "needsReview", false)));
    return audit.saveAndFlush(call);
  }
}
