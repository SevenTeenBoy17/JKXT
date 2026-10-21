package com.jkxt.tasks.repo;

import com.jkxt.tasks.domain.ExamTask;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExamTaskRepository extends JpaRepository<ExamTask, UUID> {

  Optional<ExamTask> findByOrgIdAndTaskNo(UUID orgId, String taskNo);

  Optional<ExamTask> findByOrgIdAndId(UUID orgId, UUID id);

  List<ExamTask> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

  /**
   * 迭代12 P2: 发布(publish) 需按任务串行化——并发发布会各自读到"未发布"状态、双双通过幂等判断,导致给每位
   * 监考重复下发通知(邮件/短信/站内信各一份)。发布事务内先对任务行取悲观写锁,第二个并发发布阻塞至第一个
   * 提交后再读到 published 状态、走幂等分支返回,不再重复下发。
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from ExamTask t where t.orgId = :orgId and t.id = :id")
  Optional<ExamTask> findByOrgIdAndIdForUpdate(@Param("orgId") UUID orgId, @Param("id") UUID id);
}
