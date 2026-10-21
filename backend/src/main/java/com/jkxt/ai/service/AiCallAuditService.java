package com.jkxt.ai.service;

import com.jkxt.ai.domain.AiCallLog;
import com.jkxt.ai.repo.AiCallLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an AI-call audit record independently from the request transaction.
 *
 * <p>Acceptance-mode failures deliberately throw after the record is written. Keeping this write
 * in a separate transaction prevents the failure evidence from being rolled back with the API
 * request that reports the failure.</p>
 */
@Service
public class AiCallAuditService {

  private final AiCallLogRepository repository;

  public AiCallAuditService(AiCallLogRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void save(AiCallLog log) {
    repository.saveAndFlush(log);
  }
}
