package com.jkxt.demo;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class JdbcWorkspaceFence implements WorkspaceFence {
  private final DemoWorkspaceRepository repository;
  public JdbcWorkspaceFence(DemoWorkspaceRepository repository) { this.repository=repository; }
  public void assertReadable(DemoLease lease) {
    repository.requireDemo();var w=repository.workspace(lease.workspaceId(),false);repository.matches(w,lease);repository.ready(w);
  }
  public <T>T commit(DemoLease lease,Supplier<T> mutation) {
    repository.requireDemo();
    return repository.transaction(()->{
      var locked=repository.workspace(lease.workspaceId(),true);repository.matches(locked,lease);repository.ready(locked);
      // Also covers a caller-owned outer transaction which commits after this method returns.
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void beforeCommit(boolean readOnly) { repository.matches(repository.workspace(lease.workspaceId(),false),lease);repository.ready(repository.workspace(lease.workspaceId(),false)); }
      });
      T result=mutation.get();repository.alive(locked);return result;
    });
  }
}
