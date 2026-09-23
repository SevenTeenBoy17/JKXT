package com.jkxt.scheduling.repo;

import com.jkxt.scheduling.domain.ScheduleRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, UUID> {

  List<ScheduleRun> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  List<ScheduleRun> findByOrgIdAndExamTaskIdOrderByCreatedAtDescIdDesc(UUID orgId, UUID examTaskId);

  List<ScheduleRun> findByOrgIdAndExamTaskIdAndStatusOrderByFinishedAtDescCreatedAtDescIdDesc(
      UUID orgId,
      UUID examTaskId,
      String status
  );

  Optional<ScheduleRun> findByOrgIdAndId(UUID orgId, UUID id);

  // Lock the owned row without hydrating/checking an already-managed stale @Version entity.
  @org.springframework.data.jpa.repository.Query(value="select id from schedule_run where org_id=:orgId and exam_task_id=:taskId and id=:runId for update",nativeQuery=true)
  Optional<UUID> findOwnedIdForUpdate(
      @org.springframework.data.repository.query.Param("orgId") UUID orgId,
      @org.springframework.data.repository.query.Param("taskId") UUID taskId,
      @org.springframework.data.repository.query.Param("runId") UUID runId);

  /**
   * 迭代10 P1: 任务"当前生效"的排班 run = 最新 succeeded 的 run；仅当从未成功过时才回退到最新
   * (失败/无解) run 以如实反映状态。读取路径(ExamTaskService.latestRun)与编辑/发布路径
   * (ScheduleEditService.context)必须共用此唯一口径 —— 否则一次失败/无解的重排(更晚 createdAt、
   * 零 assignment)会被编辑/发布路径当成"最新 run"，把用户正在查看的已成功排班永久锁死为不可编辑
   * (404 does not belong to latest run)且不可发布(400 requires assignments)。
   */
  default Optional<ScheduleRun> findActiveRun(UUID orgId, UUID examTaskId) {
    // A run is created by the database clock but completed by the JVM clock. If either clock is
    // corrected backwards while a rapid sequence of runs is executing, created_at alone can put
    // an older successful result ahead of the run that actually completed last. Prefer the latest
    // successful completion and keep deterministic tie-breakers. Callers that already have a
    // runId can bypass active-run selection entirely through the exact-run assignments endpoint.
    List<ScheduleRun> succeeded =
        findByOrgIdAndExamTaskIdAndStatusOrderByFinishedAtDescCreatedAtDescIdDesc(
            orgId, examTaskId, "succeeded");
    if (!succeeded.isEmpty()) {
      return Optional.of(succeeded.getFirst());
    }
    return findByOrgIdAndExamTaskIdOrderByCreatedAtDescIdDesc(orgId, examTaskId).stream().findFirst();
  }
}
