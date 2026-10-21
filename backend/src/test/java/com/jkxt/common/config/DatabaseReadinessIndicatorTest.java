package com.jkxt.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class DatabaseReadinessIndicatorTest {
  @Test void successfulProbeRestoresConnectionDeadlineAndClosesResources() throws Exception {
    DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
    Statement statement=mock(Statement.class); ResultSet result=mock(ResultSet.class);
    when(source.getConnection()).thenReturn(connection); when(connection.getNetworkTimeout()).thenReturn(17000);
    when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery("SELECT 1")).thenReturn(result);
    when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
    assertThat(new DatabaseReadinessIndicator(source,2000).health().getStatus()).isEqualTo(Status.UP);
    var order=inOrder(connection,statement,result);
    order.verify(connection).getNetworkTimeout();
    order.verify(connection).setNetworkTimeout(any(),eq(2000));
    order.verify(connection).createStatement(); order.verify(statement).setQueryTimeout(2);
    order.verify(statement).executeQuery("SELECT 1"); order.verify(result).next(); order.verify(result).getInt(1);
    order.verify(result).close(); order.verify(statement).close(); order.verify(connection).isClosed();
    order.verify(connection).setNetworkTimeout(any(),eq(17000)); order.verify(connection).close();
  }

  @Test void queryFailureIsDownAndNeverLeaksJdbcErrorOrLeavesShortBusinessDeadline() throws Exception {
    DataSource source=mock(DataSource.class); Connection connection=mock(Connection.class);
    Statement statement=mock(Statement.class);
    when(source.getConnection()).thenReturn(connection); when(connection.getNetworkTimeout()).thenReturn(0);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.executeQuery("SELECT 1")).thenThrow(new SQLException("jdbc:secret-database password=must-not-leak"));
    var health=new DatabaseReadinessIndicator(source,2000).health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.toString()).doesNotContain("secret-database","must-not-leak");
    verify(connection).setNetworkTimeout(any(),eq(0)); verify(statement).close(); verify(connection).close();
  }

  @Test void poolAcquisitionFailureFailsClosed() throws Exception {
    DataSource source=mock(DataSource.class); when(source.getConnection()).thenThrow(new SQLException("offline"));
    assertThat(new DatabaseReadinessIndicator(source,2000).health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test void invalidOrUnboundedTimeoutIsRejected() {
    DataSource source=mock(DataSource.class);
    for(int timeout:new int[]{-1,0,99,10001}) {
      assertThatThrownBy(()->new DatabaseReadinessIndicator(source,timeout)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
