package com.jkxt.demo;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.security.auth.SessionService;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.*;
import static com.jkxt.demo.DemoWorkspaceRepository.timestamp;

@Service
@EnableScheduling
public class DemoCleanupService {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DemoCleanupService.class);
  private enum Reason {RESOURCE_ATTESTATION,LIVE_GENERATION,OWNERSHIP_MISMATCH,PROTECTED_PLAN,UNFINISHED_JOBS,INBOUND_REFERENCE,UNSAFE_PATH,FILE_IO,STATE_UNAVAILABLE,UNEXPECTED}
  private static final class CleanupRefusal extends IllegalStateException {
    final Reason reason;
    CleanupRefusal(Reason reason){super(reason.name());this.reason=reason;}
  }
  public record CleanupSummary(int expired,int cleaned,int retryableFailures) {}
  private record Generation(UUID workspace,long generation,UUID org,UUID user,String state) {}
  private record Boundary(UUID workspace,long generation,long count) {}
  private final DemoWorkspaceRepository repository;private final DemoDeploymentGuard guard;private final DataSource source;
  private final DemoProperties properties;private final DeployProperties deploy;private final SessionService sessions;
  private final DemoSolveJobService solveJobs;
  private final com.jkxt.scheduling.service.ScheduleProgressSink progress;
  public DemoCleanupService(DemoWorkspaceRepository repository,DemoDeploymentGuard guard,DataSource source,
      DemoProperties properties,DeployProperties deploy,SessionService sessions,DemoSolveJobService solveJobs,com.jkxt.scheduling.service.ScheduleProgressSink progress) {
    this.repository=repository;this.guard=guard;this.source=source;this.properties=properties;this.deploy=deploy;this.sessions=sessions;
    this.solveJobs=solveJobs;this.progress=progress;
  }
  @Scheduled(initialDelay=60000,fixedDelay=60000) public void scheduledSweep() {
    try{sweep(repository.now());}catch(RuntimeException failure){LOG.warn("Demo cleanup deferred scope=sweep reason={}",reason(failure));}
  }
  public CleanupSummary sweep(Instant now) {
    if(!deploy.isDemo())return new CleanupSummary(0,0,0);
    int expired=repository.transaction(()->{
      int count=repository.jdbc.update("update demo_workspace set state='EXPIRED',cleanup_after=? where state<>'EXPIRED' and (expires_at<=? or last_activity_at<=?)",
          timestamp(now),timestamp(now),timestamp(now.minus(DemoWorkspaceRepository.IDLE)));
      repository.jdbc.update("update demo_generation g set cleanup_state='PENDING' from demo_workspace w where w.id=g.workspace_id and w.state='EXPIRED' and g.cleanup_state='LIVE'");return count;
    });
    try{verifyResources();}catch(RuntimeException unsafe){LOG.warn("Demo cleanup deferred scope=sweep reason={}",reason(unsafe));return new CleanupSummary(expired,0,1);}
    int cleaned=0,failed=0;
    // The read-only snapshot fixes membership, so continuous arrivals cannot consume this
    // sweep's budget. Business mutations use separate, freshly locked transactions below.
    try(var snapshot=source.getConnection()) {
      boolean originalAuto=snapshot.getAutoCommit(),originalReadOnly=snapshot.isReadOnly();int originalIsolation=snapshot.getTransactionIsolation();
      try {
      snapshot.setAutoCommit(false);snapshot.setReadOnly(true);snapshot.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      var scan=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(snapshot,true));
      var upper=scan.query("select workspace_id,generation,count(*) over() from demo_generation where cleanup_state in ('PENDING','RUNNING','FAILED') order by workspace_id desc,generation desc limit 1",
          rs->rs.next()?new Boundary(rs.getObject(1,UUID.class),rs.getLong(2),rs.getLong(3)):null);
      UUID cursor=new UUID(0,0);long cursorGeneration=0,remaining=upper==null?0:upper.count();
      while(remaining>0) {
      var pending=scan.query("select workspace_id,generation,org_id,user_id,cleanup_state from demo_generation where cleanup_state in ('PENDING','RUNNING','FAILED') and (workspace_id,generation)>(?,?) and (workspace_id,generation)<=(?,?) order by workspace_id,generation limit ?",
          (rs,n)->new Generation(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5)),
          cursor,cursorGeneration,upper.workspace(),upper.generation(),(int)Math.min(100,remaining));
      if(pending.isEmpty())break;
      for(var generation:pending) {
      try {
        boolean claimed=repository.transaction(()->{
          var w=repository.workspace(generation.workspace(),true);
          if(!eligible(w,generation))return false;
          return repository.jdbc.update("update demo_generation set cleanup_state='RUNNING' where workspace_id=? and generation=? and org_id=? and user_id=? and cleanup_state in ('PENDING','RUNNING','FAILED')",
              generation.workspace(),generation.generation(),generation.org(),generation.user())==1;
        });
        if(!claimed)continue;
        var captured=new DemoLease(generation.workspace(),generation.org(),generation.generation(),repository.workspace(generation.workspace(),false).expires());
        solveJobs.cancelGeneration(captured,"DEMO_EXPIRED");
        purgeBusiness(generation);
        progress.discardGeneration(captured);
        repository.transaction(()->{
          var w=repository.workspace(generation.workspace(),true);if(!eligible(w,generation))throw new CleanupRefusal(Reason.LIVE_GENERATION);
          verifyResources();purgeFiles(generation);
          repository.jdbc.update("update demo_generation set cleanup_state='DONE' where workspace_id=? and generation=? and org_id=? and user_id=? and cleanup_state='RUNNING'",
              generation.workspace(),generation.generation(),generation.org(),generation.user());return null;
        });cleaned++;
      }catch(RuntimeException failure){
        failed++;
        LOG.warn("Demo cleanup deferred scope=generation workspace={} generation={} reason={}",generation.workspace(),generation.generation(),reason(failure));
        repository.jdbc.update("update demo_generation set cleanup_state='FAILED' where workspace_id=? and generation=? and org_id=? and user_id=? and cleanup_state<>'DONE'",
            generation.workspace(),generation.generation(),generation.org(),generation.user());
      }
      }
      var last=pending.getLast();cursor=last.workspace();cursorGeneration=last.generation();remaining-=pending.size();
      }
      }finally{
        try{if(!snapshot.getAutoCommit())snapshot.rollback();}
        finally{
          try{snapshot.setTransactionIsolation(originalIsolation);}
          finally{try{snapshot.setReadOnly(originalReadOnly);}finally{snapshot.setAutoCommit(originalAuto);}}
        }
      }
    }catch(java.sql.SQLException failure){throw new CleanupRefusal(Reason.STATE_UNAVAILABLE);}
    return new CleanupSummary(expired,cleaned,failed);
  }
  private boolean eligible(DemoWorkspaceRepository.Workspace w,Generation g) {
    return w!=null && (w.state().equals("EXPIRED") || g.generation()<w.generation());
  }
  private void verifyResources() {
    try{guard.verify(source,source);}catch(RuntimeException unsafe){throw new CleanupRefusal(Reason.RESOURCE_ATTESTATION);}
  }
  /** Never log exception text/causes: they may contain storage paths, JDBC details or personal rows. */
  private static Reason reason(RuntimeException failure) {
    if(failure instanceof CleanupRefusal refusal)return refusal.reason;
    if(failure instanceof org.springframework.dao.DataAccessException)return Reason.STATE_UNAVAILABLE;
    return Reason.UNEXPECTED;
  }
  private void purgeBusiness(Generation g) {
    repository.transaction(()->{
      var w=repository.workspace(g.workspace(),true);if(!eligible(w,g))throw new CleanupRefusal(Reason.LIVE_GENERATION);
      var provenance=repository.jdbc.queryForMap("select * from demo_generation where workspace_id=? and generation=? for update",g.workspace(),g.generation());
      if(!g.org().equals(provenance.get("org_id")) || !g.user().equals(provenance.get("user_id")))throw new CleanupRefusal(Reason.OWNERSHIP_MISMATCH);
      verifyResources();
      // A bad path must prevent DB deletion too, not merely prevent a later file unlink.
      validatedFiles(g);
      boolean exists=Boolean.TRUE.equals(repository.jdbc.queryForObject("select exists(select 1 from organization where id=?)",Boolean.class,g.org()));
      if(exists) {
        boolean owner=Boolean.TRUE.equals(repository.jdbc.queryForObject("select exists(select 1 from app_user where id=? and org_id=? and role='DEMO')",Boolean.class,g.user(),g.org()));
        if(!owner)throw new CleanupRefusal(Reason.OWNERSHIP_MISMATCH);
        if(repository.jdbc.queryForObject("select count(*) from exam_plan where org_id=?",Integer.class,g.org())!=0)
          throw new CleanupRefusal(Reason.PROTECTED_PLAN);
        if(repository.jdbc.queryForObject("select count(*) from demo_solve_job where workspace_id=? and generation=? and org_id=? and released_at is null",Integer.class,g.workspace(),g.generation(),g.org())!=0)
          throw new CleanupRefusal(Reason.UNFINISHED_JOBS);
        if(repository.jdbc.queryForObject("select count(*) from schedule_run r where org_id=? and status in ('pending','queued','running') and not exists(select 1 from demo_solve_job j where j.run_id=r.id and j.org_id=r.org_id and j.workspace_id=? and j.generation=? and j.released_at is not null)",Integer.class,g.org(),g.workspace(),g.generation())!=0)
          throw new CleanupRefusal(Reason.UNFINISHED_JOBS);
        rejectInboundOwnership(g.org());
        // Reject weak legacy cross-org references before any root cascade or SET NULL side effect.
        if(repository.jdbc.queryForObject("select count(*) from notification_read r join notification n on n.id=r.notification_id where n.org_id=? and r.org_id<>?",Integer.class,g.org(),g.org())!=0)
          throw new CleanupRefusal(Reason.INBOUND_REFERENCE);
        repository.jdbc.update("delete from notification_read where org_id=?",g.org());
        repository.jdbc.update("delete from audit_log where org_id=?",g.org());
        repository.jdbc.update("delete from legacy_exam_task_plan_link where org_id=?",g.org());
        repository.jdbc.update("delete from exam_plan_substitution_case where org_id=?",g.org());
        repository.jdbc.update("delete from demo_schedule_export_snapshot where workspace_id=? and generation=? and org_id=?",g.workspace(),g.generation(),g.org());
      }
      var sessionIds=repository.jdbc.queryForList("select id::text from portal_session_registry where purpose='DEMO' and user_id=?",String.class,g.user());
      solveJobs.scrubReleasedGeneration(new DemoLease(g.workspace(),g.org(),g.generation(),w.expires()));
      for(String sessionId:sessionIds)sessions.revokeObservedDemo(sessionId,g.user(),g.generation());
      // Stable registry IDs now belong to the next user; a failed old after-commit unlink
      // can only be found in this immutable generation owner's observed Redis index.
      sessions.revokeOtherSessions(g.user(),null);
      if(exists)repository.jdbc.update("delete from organization where id=?",g.org());
      if(repository.jdbc.queryForObject("select count(*) from notification_read where org_id=?",Integer.class,g.org())!=0)throw new CleanupRefusal(Reason.OWNERSHIP_MISMATCH);
      return null;
    });
  }
  /** Database catalog supplies identifiers, never client JSON, filenames or inferred organization names. */
  private void rejectInboundOwnership(UUID org) {
    var refs=repository.jdbc.queryForList("""
        select child.relname child,parent.relname parent,
          string_agg(format('c.%I=p.%I',ca.attname,pa.attname),' AND ' order by k.i) predicate,
          exists(select 1 from pg_attribute a where a.attrelid=child.oid and a.attname='org_id' and not a.attisdropped) child_org,
          exists(select 1 from pg_attribute a where a.attrelid=parent.oid and a.attname='org_id' and not a.attisdropped) parent_org
        from pg_constraint fk join pg_class child on child.oid=fk.conrelid join pg_class parent on parent.oid=fk.confrelid
        join pg_namespace ns on ns.oid=child.relnamespace
        cross join lateral generate_subscripts(fk.conkey,1) k(i)
        join pg_attribute ca on ca.attrelid=child.oid and ca.attnum=fk.conkey[k.i]
        join pg_attribute pa on pa.attrelid=parent.oid and pa.attnum=fk.confkey[k.i]
        where fk.contype='f' and ns.nspname='public'
        group by fk.oid,child.oid,parent.oid
        """);
    for(var ref:refs) {
      String child=ref.get("child").toString(),parent=ref.get("parent").toString();
      // Ownership/operation tombstones persist and are never organization-cascade targets.
      if(parent.startsWith("demo_") || parent.startsWith("portal_session_"))continue;
      if(!Boolean.TRUE.equals(ref.get("parent_org")) && !parent.equals("organization"))continue;
      if(!child.matches("[a-z_]+") || !parent.matches("[a-z_]+"))throw new CleanupRefusal(Reason.OWNERSHIP_MISMATCH);
      String owned=parent.equals("organization")?"p.id=?":"p.org_id=?";
      String outside=Boolean.TRUE.equals(ref.get("child_org"))?"c.org_id IS DISTINCT FROM ?":"true";
      String query="select count(*) from \""+child+"\" c join \""+parent+"\" p on "+ref.get("predicate")+" where "+owned+" and "+outside;
      int count=Boolean.TRUE.equals(ref.get("child_org"))?repository.jdbc.queryForObject(query,Integer.class,org,org):repository.jdbc.queryForObject(query,Integer.class,org);
      if(count!=0)throw new CleanupRefusal(Reason.INBOUND_REFERENCE);
    }
  }
  private void purgeFiles(Generation generation) {
    try {
      for(Path path:validatedFiles(generation).stream().sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
    }catch(java.io.IOException failure){throw new CleanupRefusal(Reason.FILE_IO);}
  }
  private List<Path> validatedFiles(Generation generation) {
    try {
      Path root=Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
      if(!root.equals(root.toRealPath()))throw new CleanupRefusal(Reason.UNSAFE_PATH);
      Path workspace=root.resolve(generation.workspace().toString()),target=workspace.resolve(Long.toString(generation.generation()));
      if(!target.startsWith(root) || target.equals(root))throw new CleanupRefusal(Reason.UNSAFE_PATH);
      if(Files.exists(workspace,LinkOption.NOFOLLOW_LINKS) && (!workspace.equals(workspace.toRealPath()) || Files.isSymbolicLink(workspace)))throw new CleanupRefusal(Reason.UNSAFE_PATH);
      if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return List.of();
      if(!target.equals(target.toRealPath()) || Files.isSymbolicLink(target))throw new CleanupRefusal(Reason.UNSAFE_PATH);
      List<Path> owned;
      try(var stream=Files.walk(target)){owned=stream.toList();}
      // Validate the entire tree before the first unlink. storage_key is never interpreted as a path.
      for(Path path:owned)if(Files.isSymbolicLink(path) || !path.equals(path.toRealPath()) || !path.startsWith(target))throw new CleanupRefusal(Reason.UNSAFE_PATH);
      return owned;
    }catch(java.io.IOException failure){throw new CleanupRefusal(Reason.FILE_IO);}
  }
}
