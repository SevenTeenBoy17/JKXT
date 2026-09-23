package com.jkxt.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.auth.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static com.jkxt.demo.DemoWorkspaceRepository.*;

@Service
public class DemoWorkspaceService {
  public record WorkspaceGrant(SessionEnvelope envelope,DemoLease lease,String state) {}
  private record Operation(UUID workspace,long expected,UUID id,String kind,String hash,String state,String result) {}
  private final DemoWorkspaceRepository repository;private final DemoSeedService seed;private final SessionService sessions;private final ObjectMapper mapper;
  private final DemoQuotaService quotas;
  private final DemoSolveJobService solveJobs;
  public DemoWorkspaceService(DemoWorkspaceRepository repository,DemoSeedService seed,SessionService sessions,ObjectMapper mapper,DemoQuotaService quotas,DemoSolveJobService solveJobs) {
    this.repository=repository;this.seed=seed;this.sessions=sessions;this.mapper=mapper;
    this.quotas=quotas;
    this.solveJobs=solveJobs;
  }
  public WorkspaceGrant start(UUID visitorId,String operationId,String requestHash) {
    return start(visitorId,operationId,requestHash,()->{});
  }
  public WorkspaceGrant start(UUID visitorId,String operationId,String requestHash,Runnable proof) {
    return start(visitorId,operationId,requestHash,proof,"internal");
  }
  public WorkspaceGrant start(UUID visitorId,String operationId,String requestHash,Runnable proof,String resolvedIp) {
    repository.requireDemo();UUID operation=operationId(operationId);
    if(!hash("START").equals(requestHash))throw error(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","启动请求摘要不匹配");
    UUID workspace=repository.transaction(()->{
      repository.allocationLock();proof.run();beforeCommit(proof);
      Operation existing=operation(operation);if(existing!=null)checkOwner(existing,visitorId);
      var w=repository.visitorWorkspace(visitorId);
      if(w!=null){repository.alive(w);if(existing!=null)check(existing,"CREATE",requestHash);return w.id();}
      if(existing!=null)throw forbidden();
      Instant now=repository.now();
      int count=repository.jdbc.queryForObject("select count(*) from demo_workspace where state<>'EXPIRED' and expires_at>? and last_activity_at>?",Integer.class,timestamp(now),timestamp(now.minus(IDLE)));
      if(count>=20)throw error(HttpStatus.TOO_MANY_REQUESTS,"DEMO_QUOTA_EXCEEDED","演示工作区已满，请稍后重试");
      // Only a real new allocation attempts this debit. A later DB rollback cannot refund Redis.
      quotas.consumeAttempt("start",resolvedIp);
      UUID id=UUID.randomUUID(),org=UUID.randomUUID(),user=seed.allocateIdentity(org);
      repository.jdbc.update("insert into demo_workspace(id,visitor_id,org_id,generation,state,created_at,expires_at,last_activity_at) values(?,?,?,1,'RESETTING',?,?,?)",id,visitorId,org,timestamp(now),timestamp(now.plus(ABSOLUTE)),timestamp(now));
      repository.jdbc.update("insert into demo_generation(workspace_id,generation,org_id,user_id,cleanup_state) values(?,1,?,?,'LIVE')",id,org,user);
      insertOperation(id,1,operation,"CREATE",requestHash);return id;
    });
    return finish(workspace,operation,false,proof);
  }
  public WorkspaceGrant reset(DemoLease lease,String operationId) {
    repository.requireDemo();UUID operation=operationId(operationId);
    DemoLease cancelled=repository.transaction(()->{
      // One lock order for allocation, reset and reissue; seed calculation never holds this lock.
      repository.allocationLock();var w=repository.workspace(lease.workspaceId(),true);repository.alive(w);
      Operation existing=operation(operation);
      if(existing!=null){
        if(!existing.workspace().equals(w.id()))throw forbidden();check(existing,"RESET",hash("RESET:"+w.id()+":"+existing.expected()));
        if(existing.state().equals("SUCCEEDED"))return null;
        UUID sourceOrg=repository.jdbc.queryForObject("select org_id from demo_generation where workspace_id=? and generation=?",UUID.class,w.id(),existing.expected());
        return new DemoLease(w.id(),sourceOrg,existing.expected(),w.expires());
      }
      repository.matches(w,lease);repository.ready(w);
      repository.jdbc.update("update demo_workspace set state='RESETTING' where id=? and generation=? and state='READY'",w.id(),w.generation());
      UUID org=UUID.randomUUID(),user=seed.allocateIdentity(org);
      repository.jdbc.update("insert into demo_generation(workspace_id,generation,org_id,user_id,cleanup_state) values(?,?,?,?, 'LIVE')",w.id(),w.generation()+1,org,user);
      insertOperation(w.id(),w.generation(),operation,"RESET",hash("RESET:"+w.id()+":"+w.generation()));return lease;
    });
    if(cancelled!=null)solveJobs.cancelGeneration(cancelled,"DEMO_RESET");
    return finish(lease.workspaceId(),operation,true,()->{});
  }
  private WorkspaceGrant finish(UUID workspace,UUID operation,boolean reset,Runnable proof) {
    try {
      return repository.transaction(()->{
        // The long seed transaction serializes only this workspace. Revocation was committed earlier.
        var w=repository.workspace(workspace,true);repository.alive(w);proof.run();
        beforeCommit(()->{repository.alive(repository.workspace(workspace,false));proof.run();});
        Operation op=operation(operation);
        if(op==null){repository.ready(w);return current(w);}
        if(op.state().equals("SUCCEEDED"))return result(w,op);
        if(!Set.of("RESETTING","FAILED").contains(w.state()))throw error(HttpStatus.CONFLICT,"DEMO_STALE_GENERATION","操作版本已改变");
        long target=reset?op.expected()+1:op.expected();
        if(w.generation()!=op.expected())throw error(HttpStatus.CONFLICT,"DEMO_STALE_GENERATION","操作版本已改变");
        UUID org=repository.jdbc.queryForObject("select org_id from demo_generation where workspace_id=? and generation=?",UUID.class,workspace,target);
        UUID user=seed.initialize(org,120);repository.alive(w);
        String stable=reset?current(w).envelope().principal().sessionId():null;
        var envelope=sessions.prepareDemo(workspace,target,user,w.expires(),stable);
        repository.jdbc.update("update demo_workspace set generation=?,org_id=?,state='READY' where id=? and generation=? and state in ('RESETTING','FAILED')",target,org,workspace,op.expected());
        if(reset)repository.jdbc.update("update demo_generation set cleanup_state='PENDING' where workspace_id=? and generation=?",workspace,op.expected());
        storeResult(op,envelope);repository.alive(w);
        return new WorkspaceGrant(envelope,new DemoLease(workspace,org,target,w.expires()),"READY");
      });
    }catch(AuthException failure){throw failure;}
    catch(RuntimeException failure){
      repository.transaction(()->{
        var w=repository.workspace(workspace,true);var op=operation(operation);
        if(w!=null && op!=null && !w.state().equals("EXPIRED") && !op.state().equals("SUCCEEDED")) {
          repository.jdbc.update("update demo_workspace set state='FAILED',cleanup_after=? where id=?",timestamp(repository.now().plus(java.time.Duration.ofMinutes(15))),workspace);
          repository.jdbc.update("update demo_operation set state='FAILED' where workspace_id=? and operation_id=?",workspace,operation);
        }return null;
      });
      throw error(HttpStatus.SERVICE_UNAVAILABLE,"DEMO_INITIALIZATION_FAILED","演示数据准备失败，请使用同一操作重试");
    }
  }
  public WorkspaceGrant restore(UUID visitor,String sessionId) {
    return restore(visitor,sessionId,()->{});
  }
  public WorkspaceGrant restore(UUID visitor,String sessionId,Runnable proof) {
    repository.requireDemo();return repository.transaction(()->{
      var w=repository.visitorWorkspace(visitor);repository.ready(w);proof.run();var grant=current(w);
      if(!Objects.equals(sessionId,grant.envelope().principal().sessionId()))throw error(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","需要当前演示凭据或显式恢复首次响应");
      return grant;
    });
  }
  /** Cookie emission is a recorded server attempt, never proof of browser delivery. */
  public WorkspaceGrant initialDelivery(UUID visitor,String operationId,boolean explicitRecovery,String presentedId) {
    return initialDelivery(visitor,operationId,explicitRecovery,presentedId,()->{});
  }
  public WorkspaceGrant initialDelivery(UUID visitor,String operationId,boolean explicitRecovery,String presentedId,Runnable proof) {
    return repository.transaction(()->{
      // A pre-lock snapshot may locate the workspace only; cookieIssued can change while waiting.
      var location=operation(operationId(operationId));if(location==null)throw forbidden();
      var w=repository.workspace(location.workspace(),true);
      var op=operation(location.id());if(op==null || !op.workspace().equals(w.id()))throw forbidden();
      checkOwner(op,visitor);check(op,"CREATE",hash("START"));repository.ready(w);
      if(!op.state().equals("SUCCEEDED"))throw error(HttpStatus.CONFLICT,"DEMO_RESETTING","演示工作区正在恢复，请重试同一操作");
      proof.run();beforeCommit(()->{var latest=repository.workspace(w.id(),false);repository.matches(latest,w.lease());repository.ready(latest);proof.run();});
      var grant=result(w,op);
      var result=json(op.result());boolean issued=result.path("cookieIssued").asBoolean();
      if(Objects.equals(presentedId,grant.envelope().principal().sessionId()))return grant;
      if(issued && !explicitRecovery)throw error(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","首次响应可能丢失，请显式恢复");
      repository.jdbc.update("update demo_operation set result_json=jsonb_set(result_json,'{cookieIssued}','true') where workspace_id=? and operation_id=?",w.id(),op.id());return grant;
    });
  }
  public WorkspaceGrant reissue(UUID visitor,String operationId) {
    return reissue(visitor,operationId,()->{});
  }
  public WorkspaceGrant reissue(UUID visitor,String operationId,Runnable proof) {
    repository.requireDemo();UUID opId=operationId(operationId);
    return repository.transaction(()->{
      repository.allocationLock();var w=repository.visitorWorkspace(visitor);repository.ready(w);proof.run();
      beforeCommit(()->{repository.alive(repository.workspace(w.id(),false));proof.run();});Operation op=operation(opId);
      if(op!=null){checkOwner(op,visitor);check(op,"CREATE",reissueHash(visitor,op.workspace(),op.expected()));return result(w,op);}
      UUID currentId=repository.jdbc.query("select l.current_session_id from portal_session_registry r join portal_session_lineage l on l.id=r.lineage_id join demo_generation g on g.user_id=r.user_id where g.workspace_id=? and g.generation=? and l.current_session_id=r.id",
          rs->rs.next()?rs.getObject(1,UUID.class):null,w.id(),w.generation());
      if(currentId!=null)throw error(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","已有可用会话，请恢复当前会话");
      boolean recognized=Boolean.TRUE.equals(repository.jdbc.queryForObject("select exists(select 1 from portal_session_registry r join demo_generation g on g.user_id=r.user_id where g.workspace_id=? and g.generation=?)",Boolean.class,w.id(),w.generation()));
      if(!recognized)throw forbidden();
      insertOperation(w.id(),w.generation(),opId,"CREATE",reissueHash(visitor,w.id(),w.generation()));
      var envelope=sessions.prepareDemo(w.id(),w.generation(),repository.user(w.id(),w.generation()),w.expires(),null);
      storeResult(operation(opId),envelope);return new WorkspaceGrant(envelope,w.lease(),"READY");
    });
  }
  private WorkspaceGrant current(Workspace w) {
    String id=repository.jdbc.query("select r.id from portal_session_registry r join portal_session_lineage l on l.id=r.lineage_id and l.current_session_id=r.id join demo_generation g on g.user_id=r.user_id where g.workspace_id=? and g.generation=? and r.purpose='DEMO'",
        rs->rs.next()?rs.getString(1):null,w.id(),w.generation());
    var envelope=sessions.find(id,PortalPurpose.DEMO).orElseThrow(()->error(HttpStatus.UNAUTHORIZED,"AUTH_UNAUTHENTICATED","演示会话已撤销，请显式重新签发"));
    return new WorkspaceGrant(envelope,w.lease(),w.state());
  }
  private void beforeCommit(Runnable validation) {
    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
      @Override public void beforeCommit(boolean readOnly){validation.run();}
    });
  }
  private WorkspaceGrant result(Workspace w,Operation op) {
    repository.ready(w);var stored=json(op.result());var current=current(w);
    if(stored.path("generation").asLong()!=w.generation() || !stored.path("sessionId").asText().equals(current.envelope().principal().sessionId()))
      throw error(HttpStatus.CONFLICT,"DEMO_STALE_GENERATION","操作结果已失效，不能恢复旧凭据");
    return current;
  }
  private void storeResult(Operation op,SessionEnvelope e) {
    repository.jdbc.update("update demo_operation set state='SUCCEEDED',result_json=?::jsonb where workspace_id=? and operation_id=?",
        "{\"sessionId\":\""+e.principal().sessionId()+"\",\"generation\":"+e.generation()+",\"cookieIssued\":false}",op.workspace(),op.id());
  }
  private void insertOperation(UUID w,long generation,UUID op,String kind,String hash){repository.jdbc.update("insert into demo_operation(workspace_id,expected_generation,operation_id,kind,request_hash,state) values(?,?,?,?,?,'RUNNING')",w,generation,op,kind,hash);}
  private Operation operation(UUID op) {return repository.jdbc.query("select * from demo_operation where operation_id=?",rs->rs.next()?new Operation(rs.getObject("workspace_id",UUID.class),rs.getLong("expected_generation"),rs.getObject("operation_id",UUID.class),rs.getString("kind"),rs.getString("request_hash"),rs.getString("state"),rs.getString("result_json")):null,op);}
  private void checkOwner(Operation op,UUID visitor){if(!repository.workspace(op.workspace(),false).visitor().equals(visitor))throw forbidden();}
  private void check(Operation op,String kind,String hash){if(!op.kind().equals(kind)||!op.hash().equals(hash))throw error(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","幂等操作与原请求不匹配");}
  private com.fasterxml.jackson.databind.JsonNode json(String value){try{return mapper.readTree(value);}catch(Exception e){throw error(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_STATE_UNAVAILABLE","操作记录不可用");}}
  private static AuthException forbidden(){return error(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","此操作不属于当前访客");}
  private static String reissueHash(UUID visitor,UUID workspace,long generation){return hash("REISSUE:"+visitor+":"+workspace+":"+generation);}
  public static UUID operationId(String value){try{return UUID.fromString(value);}catch(RuntimeException e){throw error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","operationId 必须是 UUID");}}
  public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
