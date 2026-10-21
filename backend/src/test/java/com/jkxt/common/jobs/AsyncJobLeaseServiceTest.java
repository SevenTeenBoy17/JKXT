package com.jkxt.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jkxt.common.jobs.AsyncJobLeaseService.Kind;
import com.jkxt.common.jobs.AsyncJobLeaseService.Lease;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AsyncJobLeaseServiceTest {
  @Test
  void unavailableDatabaseCannotAuthorizeOutputOrReturnSuccessfulOwnership() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("isolated connection failure"));
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("isolated connection failure"));
    AsyncJobLeaseService service = new AsyncJobLeaseService(jdbc, manager, 1000, 1000);
    Lease lease = new Lease(UUID.randomUUID(), Kind.PLANNING, UUID.randomUUID(), UUID.randomUUID(), 1);
    AtomicBoolean wrote = new AtomicBoolean();
    assertThatThrownBy(() -> service.finish(lease, () -> wrote.set(true)))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(wrote).isFalse();
    assertThatThrownBy(() -> service.valid(lease)).isInstanceOf(DataAccessResourceFailureException.class);
  }
}
