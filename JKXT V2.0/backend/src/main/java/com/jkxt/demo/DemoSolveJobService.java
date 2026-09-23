package com.jkxt.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.web.ApiException;
import com.jkxt.scheduling.solver.*;
import com.jkxt.scheduling.solver.CpSatScheduleSolver.SolveControl;
import com.jkxt.scheduling.solver.CpSatScheduleSolver.SolveStopped;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** Durable single-workspace occupancy, with exact same-process worker acknowledgement. */
@Service
public class DemoSolveJobService {
  public static final long BUDGET_NANOS=Duration.ofSeconds(30).toNanos();
  public record Admission(UUID runId,UUID taskId,ScheduleFixture fixture,SchedulePolicy policy) {}
  public record OwnedJob(DemoLease lease,UUID reservationId,UUID runId,UUID taskId,UUID boot,
      long admittedTick,ScheduleFixture fixture,SchedulePolicy policy) {}
  private static final String EXACT="reservation_id=? and run_id=? and owner_boot_token=? and workspace_id=? and generation=? and org_id=? and task_id=?";
  private record ExitEvidence(OwnedJob job,Instant nativeExitedAt,String reason) {}
  private final DemoWorkspaceRepository repository;
  private final WorkspaceFence fence;
  private final DeployProperties deploy;
  private final ObjectMapper mapper;
  private final ScheduledExecutorService scheduler;
  private final UUID boot=UUID.randomUUID();
  private final ConcurrentMap<UUID,SolveControl> controls=new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID,OwnedJob> running=new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID,ExitEvidence> exited=new ConcurrentHashMap<>();
  private final ConcurrentMap<UUID,Thread> workers=new ConcurrentHashMap<>();
  private volatile boolean shuttingDown;
  public DemoSolveJobService(DemoWorkspaceRepository repository,WorkspaceFence fence,DeployProperties deploy,
      ObjectMapper mapper,@Qualifier("demoSolveControlScheduler") ScheduledExecutorService scheduler) {
    this.repository=repository;this.fence=fence;this.deploy=deploy;this.mapper=mapper;this.scheduler=scheduler;
  }
  public boolean demoDeployment(){return deploy.isDemo();}
  public OwnedJob admit(DemoLease lease,Supplier<Admission> prepare) {
    repository.requireDemo();
    if(lease==null)throw problem("DEMO_LEASE_REQUIRED","需要当前演示会话");
    if(TransactionSynchronizationManager.isActualTransactionActive())throw problem("DEMO_ADMISSION_TRANSACTION","演示排考需要独立提交边界");
    if(shuttingDown)throw problem("DEMO_SOLVE_UNAVAILABLE","演示求解正在停止，请稍后重试");
    return fence.commit(lease,()->{
      UUID occupant=repository.jdbc.query("select owner_boot_token from demo_solve_job where workspace_id=? and released_at is null",rs->rs.next()?rs.getObject(1,UUID.class):null,lease.workspaceId());
      if(occupant!=null) {
        if(!boot.equals(occupant))throw problem("DEMO_SOLVE_OWNER_UNKNOWN","旧进程的求解占用尚未确认退出；请由运维确认旧进程已终止后恢复");
        throw problem("DEMO_SOLVE_BUSY","该工作区已有求解任务；取消尚未确认退出时请等待");
      }
      long tick=System.nanoTime();Instant now=repository.now();
      Admission prepared=prepare.get();UUID reservation=UUID.randomUUID();
      repository.jdbc.update("""
          insert into demo_solve_job(reservation_id,workspace_id,generation,org_id,run_id,task_id,
          owner_boot_token,admitted_tick,admitted_at,deadline_at,lease_expires_at,fixture_json,policy_json,state)
          values(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,'QUEUED')
          """,reservation,lease.workspaceId(),lease.generation(),lease.orgId(),prepared.runId(),prepared.taskId(),boot,tick,
          DemoWorkspaceRepository.timestamp(now),DemoWorkspaceRepository.timestamp(now.plusSeconds(30)),DemoWorkspaceRepository.timestamp(lease.expiresAt()),
          json(prepared.fixture()),json(prepared.policy()));
      return new OwnedJob(lease,reservation,prepared.runId(),prepared.taskId(),boot,tick,prepared.fixture(),prepared.policy());
    });
  }
  private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException("Solve snapshot unavailable");}}
  private <T>T decode(String json,Class<T> type){try{return mapper.readValue(json,type);}catch(Exception failure){throw new IllegalStateException("Solve snapshot unreadable");}}
  private Object[] identity(OwnedJob job) {return new Object[]{job.reservationId(),job.runId(),job.boot(),job.lease().workspaceId(),job.lease().generation(),job.lease().orgId(),job.taskId()};}
  private Object[] values(OwnedJob job,Object... prefix){var args=new ArrayList<Object>(Arrays.asList(prefix));args.addAll(Arrays.asList(identity(job)));return args.toArray();}
  private boolean current(OwnedJob job) {
    try {fence.assertReadable(job.lease());return true;}
    catch(com.jkxt.security.auth.AuthException stale){return false;}
  }
  public long remainingNanos(OwnedJob job) {
    if(!boot.equals(job.boot()))throw problem("DEMO_SOLVE_OWNER_UNKNOWN","先确认旧演示进程已终止；不能依据启动标识释放占用");
    return Math.max(0,BUDGET_NANOS-Math.max(0,System.nanoTime()-job.admittedTick()));
  }
  public List<OwnedJob> outstanding() {
    if(!deploy.isDemo())return List.of();
    // Foreign-boot tombstones cannot starve this boot's bounded dispatcher.
    return outstandingWhere("owner_boot_token=? order by admitted_at limit 32",boot);
  }
  private List<OwnedJob> outstandingWhere(String condition,Object... args) {
    return repository.jdbc.query("select * from demo_solve_job where released_at is null and "+condition,(rs,n)->{
      var lease=new DemoLease(rs.getObject("workspace_id",UUID.class),rs.getObject("org_id",UUID.class),rs.getLong("generation"),rs.getTimestamp("lease_expires_at").toInstant());
      return new OwnedJob(lease,rs.getObject("reservation_id",UUID.class),rs.getObject("run_id",UUID.class),rs.getObject("task_id",UUID.class),
          rs.getObject("owner_boot_token",UUID.class),rs.getLong("admitted_tick"),decode(rs.getString("fixture_json"),ScheduleFixture.class),decode(rs.getString("policy_json"),SchedulePolicy.class));
    },args);
  }
  public boolean sameBoot(OwnedJob job){return boot.equals(job.boot());}
  public boolean streamAllowed(UUID reservation) {var control=controls.get(reservation);return control==null || control.stopReason()==null;}
  public String state(OwnedJob job) {
    return repository.jdbc.query("select state from demo_solve_job where "+EXACT,rs->rs.next()?rs.getString(1):null,identity(job));
  }
  public SolveControl claim(OwnedJob job) {
    if(!sameBoot(job))return null;
    if(shuttingDown || remainingNanos(job)==0 || !current(job)) {
      cancel(job,remainingNanos(job)==0?"DEMO_SOLVE_TIMEOUT":"DEMO_SOLVE_CANCELLED");return null;
    }
    return fence.commit(job.lease(),()->{
      int claimed=repository.jdbc.update("update demo_solve_job set state='RUNNING' where "+EXACT+" and state='QUEUED' and released_at is null",identity(job));
      if(claimed==0)return null;
      var control=new SolveControl(job.admittedTick(),BUDGET_NANOS,scheduler);
      controls.put(job.reservationId(),control);
      running.put(job.reservationId(),job);
      workers.put(job.reservationId(),Thread.currentThread());
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED){controls.remove(job.reservationId(),control);running.remove(job.reservationId(),job);workers.remove(job.reservationId());control.close();}}
      });
      return control;
    });
  }
  /** All business callbacks use this boundary; no ThreadLocal identity is consulted. */
  public <T>T write(OwnedJob job,Supplier<T> mutation,String terminalState) {
    var control=controls.get(job.reservationId());if(control==null)throw new SolveStopped("DEMO_SOLVE_CANCELLED");control.check();
    return fence.commit(job.lease(),()->{
      String state=repository.jdbc.query("select state from demo_solve_job where "+EXACT+" and released_at is null for update",rs->rs.next()?rs.getString(1):null,identity(job));
      if(!"RUNNING".equals(state))throw new SolveStopped("DEMO_SOLVE_CANCELLED");
      control.check();
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){control.check();}});
      T result=mutation.get();control.check();
      if(terminalState!=null)repository.jdbc.update("update demo_solve_job set state=? where "+EXACT,values(job,terminalState));
      return result;
    });
  }
  public boolean canDeliver(OwnedJob job,boolean terminal) {
    var control=controls.get(job.reservationId());
    if(control==null || control.stopReason()!=null || !current(job))return false;
    String state=state(job);return terminal?Set.of("SUCCEEDED","INFEASIBLE","FAILED").contains(state):"RUNNING".equals(state);
  }
  /** Stop first; DB transitions may wait on the workspace lock independently of native stop. */
  public void cancel(OwnedJob job,String reason) {
    signal(job,reason);
    repository.transaction(()->{
      repository.workspace(job.lease().workspaceId(),true);
      String state=repository.jdbc.query("select state from demo_solve_job where "+EXACT+" and released_at is null for update",rs->rs.next()?rs.getString(1):null,identity(job));
      if(state==null || !Set.of("QUEUED","RUNNING").contains(state))return null;
      // A foreign boot is never reaped merely from a mutable state or boot inequality.
      boolean prevented=state.equals("QUEUED") && sameBoot(job);
      repository.jdbc.update("update demo_solve_job set state=?,stop_requested_at=coalesce(stop_requested_at,?),terminal_reason=coalesce(terminal_reason,?),released_at=? where "+EXACT,
          values(job,prevented?"CANCELLED":"CANCEL_REQUESTED",DemoWorkspaceRepository.timestamp(repository.now()),reason,
              prevented?DemoWorkspaceRepository.timestamp(repository.now()):null));
      if(prevented)scrubReleased(job);
      if(current(job)) {
        boolean timeout=reason.equals("DEMO_SOLVE_TIMEOUT");
        repository.jdbc.update("update schedule_run set status=?,stage=?,progress=100,error_code=?,error_message=?,finished_at=? where id=? and org_id=?",
            timeout?"failed":"cancelled",timeout?"timeout":"cancelled",reason,timeout?"演示求解超过 30 秒预算，已停止接受结果":"演示求解已取消，等待工作线程退出",DemoWorkspaceRepository.timestamp(repository.now()),job.runId(),job.lease().orgId());
        repository.jdbc.update("update exam_task set status='failed' where id=? and org_id=?",job.taskId(),job.lease().orgId());
      }
      return null;
    });
    signal(job,reason);
  }
  private void signal(OwnedJob job,String reason){var owned=running.get(job.reservationId());var control=controls.get(job.reservationId());if(sameOwner(owned,job) && control!=null)control.requestStop(reason);}
  private boolean sameOwner(OwnedJob first,OwnedJob second){return first!=null && first.reservationId().equals(second.reservationId()) && first.runId().equals(second.runId()) && first.boot().equals(second.boot()) && first.lease().equals(second.lease());}
  public void cancelGeneration(DemoLease lease,String reason) {
    running.values().stream().filter(j->j.lease().equals(lease)).forEach(j->signal(j,reason));
    var owned=outstandingWhere("workspace_id=? and generation=? and org_id=?",lease.workspaceId(),lease.generation(),lease.orgId());
    owned.forEach(j->signal(j,reason));owned.forEach(j->cancel(j,reason));
  }
  public void rejected(OwnedJob job) {
    repository.transaction(()->{
      repository.workspace(job.lease().workspaceId(),true);
      int changed=repository.jdbc.update("update demo_solve_job set state='FAILED',terminal_reason='EXECUTOR_REJECTED',released_at=? where "+EXACT+" and state='QUEUED' and released_at is null",
          values(job,DemoWorkspaceRepository.timestamp(repository.now())));
      if(changed==1)scrubReleased(job);
      if(changed==1 && current(job)) {
        repository.jdbc.update("update schedule_run set status='failed',stage='failed',progress=100,error_code='SCHEDULE_JOB_FAILED',error_message='演示求解队列已满，请稍后重试',finished_at=? where id=? and org_id=?",
            DemoWorkspaceRepository.timestamp(repository.now()),job.runId(),job.lease().orgId());
        repository.jdbc.update("update exam_task set status='failed' where id=? and org_id=?",job.taskId(),job.lease().orgId());
      }
      return null;
    });
  }
  /** Native return is diagnostic only; the outer worker still owns all callbacks. */
  public void nativeExited(OwnedJob job) {
    if(!requireWorker(job))return;
    var control=controls.get(job.reservationId());
    if(control!=null && control.nativeReturned())lifecycle(job,"update demo_solve_job set native_exited_at=coalesce(native_exited_at,?) where "+EXACT+" and released_at is null");
  }
  public void workerExited(OwnedJob job) {workerExited(job,null);}
  public void workerExited(OwnedJob job,String terminalReason) {
    if(!requireWorker(job))return;
    var control=controls.get(job.reservationId());
    String reason=terminalReason!=null?terminalReason:control==null?null:control.stopReason();
    var evidence=new ExitEvidence(job,control!=null && control.nativeReturned()?repository.now():null,
        reason==null?"SCHEDULE_JOB_FAILED":reason);
    exited.put(job.reservationId(),evidence);
    try {acknowledgeExit(evidence);}
    finally {running.remove(job.reservationId(),job);workers.remove(job.reservationId());var retired=controls.remove(job.reservationId());if(retired!=null)retired.close();}
  }
  private boolean requireWorker(OwnedJob job) {
    var worker=workers.get(job.reservationId());
    if(worker==Thread.currentThread() && sameOwner(running.get(job.reservationId()),job))return true;
    if(worker==null && Boolean.TRUE.equals(repository.jdbc.query("select released_at is not null from demo_solve_job where "+EXACT,rs->rs.next()?rs.getBoolean(1):false,identity(job))))return false;
    throw new IllegalStateException("Only the claiming worker can acknowledge exit");
  }
  private void acknowledgeExit(ExitEvidence evidence) {
    OwnedJob job=evidence.job();
    if(!sameBoot(job))throw new IllegalStateException("Foreign worker acknowledgement refused");
    repository.transaction(()->{
      repository.workspace(job.lease().workspaceId(),true);
      var rows=repository.jdbc.queryForList("select state,released_at from demo_solve_job where "+EXACT+" for update",identity(job));
      if(rows.size()!=1)throw new IllegalStateException("Exact exited owner is unavailable");
      var row=rows.getFirst();if(row.get("released_at")!=null)return null;
      String state=row.get("state").toString();boolean incomplete=Set.of("QUEUED","RUNNING","CANCEL_REQUESTED").contains(state);
      if(incomplete && current(job)) {
        // Business repair and release share one transaction; the nested fence also checks before commit.
        String repaired=fence.commit(job.lease(),()->repairActiveBusiness(job,evidence.reason()));
        if(repaired!=null && !"CANCEL_REQUESTED".equals(state))state=repaired;
      }
      // A requested cancellation keeps its established sidecar outcome, even when timeout makes the run failed.
      if("CANCEL_REQUESTED".equals(state))state="CANCELLED";
      else if(Set.of("QUEUED","RUNNING").contains(state))state=failedReason(evidence.reason())?"FAILED":"CANCELLED";
      repository.jdbc.update("update demo_solve_job set state=?,terminal_reason=case when ? then coalesce(terminal_reason,?) else terminal_reason end,native_exited_at=coalesce(native_exited_at,?),released_at=? where "+EXACT+" and released_at is null",
          values(job,state,incomplete && !Set.of("SUCCEEDED","INFEASIBLE").contains(state),evidence.reason(),
              evidence.nativeExitedAt()==null?null:DemoWorkspaceRepository.timestamp(evidence.nativeExitedAt()),DemoWorkspaceRepository.timestamp(repository.now())));
      scrubReleased(job);return null;
    });
    // A thrown/uncertain commit retains the proof; retry observes an already released exact row safely.
    exited.remove(job.reservationId(),evidence);
  }
  private String repairActiveBusiness(OwnedJob job,String reason) {
    String status=repository.jdbc.query("select status from schedule_run where id=? and org_id=? and exam_task_id=? for update",
        rs->rs.next()?rs.getString(1):null,job.runId(),job.lease().orgId(),job.taskId());
    if(status==null)return null;
    if(Set.of("succeeded","infeasible","failed","cancelled").contains(status))return status.toUpperCase(Locale.ROOT);
    if(!Set.of("pending","queued","running").contains(status))throw new IllegalStateException("Unknown owned run recovery state");
    boolean failed=failedReason(reason);
    repository.jdbc.update("update schedule_run set status=?,stage=?,progress=100,error_code=?,error_message=?,finished_at=? where id=? and org_id=? and exam_task_id=? and status in ('pending','queued','running')",
        failed?"failed":"cancelled",failed?"failed":"cancelled",reason,"演示工作线程已退出，未提交有效结果，请重新排考",DemoWorkspaceRepository.timestamp(repository.now()),job.runId(),job.lease().orgId(),job.taskId());
    repository.jdbc.update("update exam_task set status='failed' where id=? and org_id=? and status='scheduling'",job.taskId(),job.lease().orgId());
    return failed?"FAILED":"CANCELLED";
  }
  private static boolean failedReason(String reason) {return reason.startsWith("SCHEDULE_") || reason.equals("DEMO_SOLVE_TIMEOUT") || reason.equals("DEMO_SOLVE_INPUT_CHANGED");}
  private void scrubReleased(OwnedJob job) {
    repository.jdbc.update("update demo_solve_job set fixture_json=null,policy_json=null,payload_scrubbed_at=? where "+EXACT+" and released_at is not null and fixture_json is not null",
        values(job,DemoWorkspaceRepository.timestamp(repository.now())));
  }
  /** Legacy released payloads are minimized under the same exact generation lock as business purge. */
  public void scrubReleasedGeneration(DemoLease lease) {
    repository.transaction(()->{
      repository.workspace(lease.workspaceId(),true);
      repository.jdbc.update("update demo_solve_job set fixture_json=null,policy_json=null,payload_scrubbed_at=? where workspace_id=? and generation=? and org_id=? and released_at is not null and fixture_json is not null",
          DemoWorkspaceRepository.timestamp(repository.now()),lease.workspaceId(),lease.generation(),lease.orgId());return null;
    });
  }
  public void retryExited(){for(var evidence:exited.values())acknowledgeExit(evidence);}
  private void lifecycle(OwnedJob job,String sql) {
    if(!sameBoot(job))throw new IllegalStateException("Foreign worker acknowledgement refused");
    repository.transaction(()->{repository.workspace(job.lease().workspaceId(),true);repository.jdbc.update(sql,values(job,DemoWorkspaceRepository.timestamp(repository.now())));return null;});
  }
  public String reconcileReason(OwnedJob job) {
    if(!sameBoot(job))return "DEMO_SOLVE_OWNER_UNKNOWN";
    var control=controls.get(job.reservationId());
    if(control!=null && control.stopReason()!=null)return control.stopReason();
    if(remainingNanos(job)==0)return "DEMO_SOLVE_TIMEOUT";
    return current(job)?null:"DEMO_SOLVE_CANCELLED";
  }
  @jakarta.annotation.PreDestroy public void shutdown() {
    shuttingDown=true;controls.values().forEach(c->c.requestStop("DEMO_SHUTDOWN"));
    for(var job:outstanding())if(sameBoot(job))cancel(job,"DEMO_SHUTDOWN");
  }
  private static ApiException problem(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
