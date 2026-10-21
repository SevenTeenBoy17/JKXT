package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Test-only storage boundary, separate from test discovery and never used by production. */
class ExamPlanAuditStorageBoundary extends JdbcTemplate {
  record Row(UUID id, String action, String actor) {}
  private final Instant at;
  int exists = 1, eventQueries = 0;
  List<Row> rows = List.of();
  Object[] scopeArgs, eventArgs;
  String eventSql;

  ExamPlanAuditStorageBoundary(Instant at) { this.at = at; }

  @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
    assertThat(sql).contains("exam_plan_revision", "org_id=?", "plan_id=?", "id=?");
    scopeArgs = args.clone(); return type.cast(exists);
  }

  @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
    eventQueries++; eventArgs = args.clone(); eventSql = sql;
    List<T> result = new ArrayList<>();
    try { for (Row row : rows) {
      ResultSet rs = mock(ResultSet.class);
      when(rs.getObject("id", UUID.class)).thenReturn(row.id()); when(rs.getString("action")).thenReturn(row.action());
      when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(at)); when(rs.getString("actor_display_name")).thenReturn(row.actor());
      when(rs.getString("reason")).thenReturn("SECRET_REASON"); when(rs.getString("before_state")).thenReturn("SECRET_BEFORE");
      when(rs.getString("after_state")).thenReturn("SECRET_AFTER"); result.add(mapper.mapRow(rs, result.size()));
    } } catch (SQLException e) { throw new AssertionError(e); }
    return result;
  }

  @Override public int update(String sql, Object... args) { throw new AssertionError("Audit reads must not write: " + sql); }
}
