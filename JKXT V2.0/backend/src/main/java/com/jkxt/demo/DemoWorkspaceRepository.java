package com.jkxt.demo;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.security.auth.AuthException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DemoWorkspaceRepository {
  public static final Duration IDLE=Duration.ofMinutes(30),ABSOLUTE=Duration.ofHours(2);
  final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final DeployProperties deploy;
  public DemoWorkspaceRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectProvider<Clock> clocks,DeployProperties deploy) {
    this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.clock=clocks.getIfAvailable(Clock::systemUTC);this.deploy=deploy;
  }
  public record Workspace(UUID id,UUID visitor,UUID org,long generation,String state,Instant created,Instant expires,Instant activity) {
    public DemoLease lease() { return new DemoLease(id,org,generation,expires); }
    public Instant idleExpiresAt() { return activity.plus(IDLE).isBefore(expires)?activity.plus(IDLE):expires; }
  }
  public Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
  public void requireDemo() { if(!deploy.isDemo()) throw error(HttpStatus.FORBIDDEN,"DEMO_DISABLED","演示入口未启用"); }
  public <T>T transaction(Supplier<T> work) { return tx.execute(s->work.get()); }
  public void allocationLock() { jdbc.execute("select pg_advisory_xact_lock(1246443598)"); }
  public Workspace workspace(UUID id,boolean lock) {
    return jdbc.query("select * from demo_workspace where id=?"+(lock?" for update":""),
        rs->rs.next()?new Workspace(rs.getObject("id",UUID.class),rs.getObject("visitor_id",UUID.class),rs.getObject("org_id",UUID.class),
          rs.getLong("generation"),rs.getString("state"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("last_activity_at").toInstant()):null,id);
  }
  public Workspace visitorWorkspace(UUID visitor) {
    UUID id=jdbc.query("select id from demo_workspace where visitor_id=? order by created_at desc,id limit 1",
        rs->rs.next()?rs.getObject(1,UUID.class):null,visitor);
    return id==null?null:workspace(id,true);
  }
  public void alive(Workspace w) {
    if(w==null) throw error(HttpStatus.UNAUTHORIZED,"AUTH_UNAUTHENTICATED","演示工作区不可用");
    if(w.state().equals("EXPIRED") || !now().isBefore(w.idleExpiresAt()))
      throw error(HttpStatus.UNAUTHORIZED,"DEMO_EXPIRED","演示工作区已到期，请重新进入");
  }
  public void ready(Workspace w) {
    alive(w);
    if(!w.state().equals("READY")) throw error(HttpStatus.CONFLICT,"DEMO_RESETTING","演示工作区正在恢复，请重试同一操作");
  }
  public void matches(Workspace w,DemoLease lease) {
    alive(w);
    if(lease==null || w.generation()!=lease.generation() || !w.org().equals(lease.orgId()) || !w.expires().equals(lease.expiresAt()))
      throw error(HttpStatus.CONFLICT,"DEMO_STALE_GENERATION","演示数据版本已改变，请刷新");
  }
  public UUID user(UUID workspace,long generation) {
    return jdbc.queryForObject("select user_id from demo_generation where workspace_id=? and generation=?",UUID.class,workspace,generation);
  }
  public void touchActivity(DemoLease captured) {
    requireDemo();transaction(()->{
      var w=workspace(captured.workspaceId(),true);matches(w,captured);ready(w);
      jdbc.update("update demo_workspace set last_activity_at=greatest(last_activity_at,?) where id=?",timestamp(now()),w.id());
      return null;
    });
  }
  public static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
  public static AuthException error(HttpStatus status,String code,String message) { return new AuthException(status,code,message); }
}
