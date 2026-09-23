package com.jkxt.demo.api;

import com.jkxt.demo.*;
import com.jkxt.demo.api.DemoDtos.*;
import com.jkxt.security.auth.AuthRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoSimulationController {
  private static final String LABEL="模拟发布，未通知真实教师";
  private final DemoWorkspaceRepository repository;private final JdbcTemplate jdbc;private final HttpServletRequest request;
  private final WorkspaceFence fence;
  public DemoSimulationController(DemoWorkspaceRepository repository,JdbcTemplate jdbc,HttpServletRequest request,WorkspaceFence fence){this.repository=repository;this.jdbc=jdbc;this.request=request;this.fence=fence;}
  @PostMapping("/tasks/{taskId}/simulate-publish")
  public SimulationResult publish(@PathVariable UUID taskId,@Valid @RequestBody SimulationRequest body){return simulatePublish(taskId,body.operationId().toString());}
  public SimulationResult simulatePublish(UUID taskId,String operationId) {
    repository.requireDemo();UUID operation=DemoWorkspaceService.operationId(operationId);
    if(!(request.getAttribute(AuthRequestAttributes.DEMO_LEASE) instanceof DemoLease lease))throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","需要演示会话");
    return fence.commit(lease,()->{
      // Validate live ownership BEFORE reading an idempotent result, including after reset/cleanup.
      if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from exam_task where id=? and org_id=?)",Boolean.class,taskId,lease.orgId())))
        throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","任务不属于当前演示工作区");
      String hash=DemoWorkspaceService.hash("SIMULATE_PUBLISH:"+lease.workspaceId()+":"+lease.generation()+":"+lease.orgId()+":"+taskId);
      var prior=jdbc.query("select * from demo_simulation where workspace_id=? and operation_id=?",rs->{
        if(!rs.next())return null;
        if(!hash.equals(rs.getString("request_hash")) || rs.getLong("generation")!=lease.generation() || !taskId.equals(rs.getObject("task_id",UUID.class)))
          throw DemoWorkspaceRepository.error(HttpStatus.CONFLICT,"DEMO_IDEMPOTENCY_CONFLICT","模拟操作与原请求不匹配");
        return new SimulationResult(taskId,operation.toString(),rs.getString("label"),rs.getTimestamp("created_at").toInstant());
      },lease.workspaceId(),operation);
      if(prior!=null)return prior;
      // Successful immutable records consume lifetime-workspace capacity across resets. The
      // existing workspace fence serializes this count and insert; rollback consumes nothing.
      if(jdbc.queryForObject("select count(*) from demo_simulation where workspace_id=?",Long.class,lease.workspaceId())>=60)
        throw DemoWorkspaceRepository.error(HttpStatus.TOO_MANY_REQUESTS,"DEMO_SIMULATION_CAPACITY_EXCEEDED","演示工作区模拟发布记录已达60条上限，重置不会恢复额度");
      var now=repository.now();
      jdbc.update("insert into demo_simulation(workspace_id,generation,org_id,task_id,operation_id,request_hash,label,created_at) values(?,?,?,?,?,?,?,?)",
          lease.workspaceId(),lease.generation(),lease.orgId(),taskId,operation,hash,LABEL,DemoWorkspaceRepository.timestamp(now));
      return new SimulationResult(taskId,operation.toString(),LABEL,now);
    });
  }
}
