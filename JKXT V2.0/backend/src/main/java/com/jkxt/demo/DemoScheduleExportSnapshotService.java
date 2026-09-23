package com.jkxt.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.web.ApiException;
import com.jkxt.scheduling.domain.ScheduleRun;
import com.jkxt.scheduling.repo.ScheduleRunRepository;
import com.jkxt.tasks.api.ExamTaskDtos.AssignmentRow;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Full raw display rows, captured only inside the caller's workspace -> run write transaction. */
@Service
public class DemoScheduleExportSnapshotService {
  private final DemoWorkspaceRepository repository;
  private final WorkspaceFence fence;
  private final ScheduleRunRepository runs;
  private final EntityManager entities;
  private final ObjectMapper mapper;
  public DemoScheduleExportSnapshotService(DemoWorkspaceRepository repository,WorkspaceFence fence,
      ScheduleRunRepository runs,EntityManager entities,ObjectMapper mapper) {
    this.repository=repository;this.fence=fence;this.runs=runs;this.entities=entities;this.mapper=mapper;
  }
  public record Snapshot(UUID workspaceId,long generation,UUID orgId,UUID taskId,UUID runId,
      long exportRevision,int schemaVersion,Instant snapshotAt,List<AssignmentRow> rows,Map<String,Object> stats) {
    public String outputToken(){return "\""+runId+":"+exportRevision+"\"";}
    public Map<String,Object> displayStats(){
      var result=new LinkedHashMap<>(stats);result.put("exportRevision",exportRevision);result.put("outputToken",outputToken());
      result.put("snapshotAt",snapshotAt.toString());result.put("generation",generation);result.put("snapshotSchemaVersion",schemaVersion);
      return result;
    }
  }
  @Transactional(propagation=Propagation.MANDATORY)
  public Snapshot initial(DemoLease lease,UUID taskId,UUID runId) {
    fence.assertReadable(lease);
    runs.findOwnedIdForUpdate(lease.orgId(),taskId,runId).orElseThrow(DemoScheduleExportSnapshotService::unavailable);
    var run=runs.findByOrgIdAndId(lease.orgId(),runId).orElseThrow(DemoScheduleExportSnapshotService::unavailable);
    // persistResult flushed before capture; this is the solver transaction's completed result.
    return store(lease,run,0);
  }
  @Transactional(propagation=Propagation.MANDATORY)
  public Snapshot append(DemoLease lease,ScheduleRun lockedRun) {
    var prior=latest(lease,lockedRun.getExamTaskId(),lockedRun.getId());
    return store(lease,lockedRun,Math.addExact(prior.exportRevision(),1));
  }
  private Snapshot store(DemoLease lease,ScheduleRun run,long revision) {
    fence.assertReadable(lease);entities.flush();
    if(!lease.orgId().equals(run.getOrgId()))throw unavailable();
    var rows=repository.jdbc.query("""
        select a.*,e.subject_name,e.grade,c.code room_code,c.name room_name,t.teacher_no,t.name teacher_name
        from schedule_assignment a
        join exam e on e.id=a.exam_id and e.org_id=a.org_id and e.exam_task_id=?
        join teacher t on t.id=a.teacher_id and t.org_id=a.org_id
        join classroom c on c.id=a.classroom_id and c.org_id=a.org_id
        where a.org_id=? and a.schedule_run_id=?
        order by a.exam_date,a.start_time,c.code,a.role,a.id
        """,(rs,n)->new AssignmentRow(rs.getObject("id",UUID.class),rs.getObject("exam_id",UUID.class),
            rs.getObject("classroom_id",UUID.class),rs.getObject("teacher_id",UUID.class),rs.getString("subject_name"),rs.getString("grade"),
            rs.getDate("exam_date").toLocalDate(),rs.getObject("start_time",LocalTime.class),rs.getObject("end_time",LocalTime.class),
            rs.getString("room_code"),rs.getString("room_name"),rs.getString("teacher_no"),rs.getString("teacher_name"),rs.getString("role"),rs.getString("source")),
        run.getExamTaskId(),lease.orgId(),run.getId());
    int count=repository.jdbc.queryForObject("select count(*) from schedule_assignment where org_id=? and schedule_run_id=?",Integer.class,lease.orgId(),run.getId());
    if(rows.size()!=count || rows.isEmpty())throw unavailable();
    var stats=new LinkedHashMap<>(run.getStats());stats.put("revision",run.getRevision());stats.put("revisionCursor",run.getRevisionCursor());
    Instant at=repository.now();
    try {
      String payload=mapper.writeValueAsString(Map.of("rows",rows,"stats",stats));
      repository.jdbc.update("insert into demo_schedule_export_snapshot(workspace_id,generation,org_id,task_id,run_id,export_revision,schema_version,snapshot_at,payload) values(?,?,?,?,?,?,1,?,?::jsonb)",
          lease.workspaceId(),lease.generation(),lease.orgId(),run.getExamTaskId(),run.getId(),revision,java.sql.Timestamp.from(at),payload);
    }catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("Cannot encode output snapshot",failure);}
    return new Snapshot(lease.workspaceId(),lease.generation(),lease.orgId(),run.getExamTaskId(),run.getId(),revision,1,at,List.copyOf(rows),Collections.unmodifiableMap(stats));
  }
  public Snapshot latest(DemoLease lease,UUID task,UUID run) {return read(lease,task,run,null);}
  public Snapshot exact(DemoLease lease,UUID task,UUID run,long revision) {return read(lease,task,run,revision);}
  private Snapshot read(DemoLease lease,UUID task,UUID run,Long revision) {
    fence.assertReadable(lease);
    var args=new ArrayList<Object>(List.of(lease.workspaceId(),lease.generation(),lease.orgId(),task,run));
    if(revision!=null)args.add(revision);
    var found=repository.jdbc.query("select * from demo_schedule_export_snapshot where workspace_id=? and generation=? and org_id=? and task_id=? and run_id=?"+
        (revision==null?" order by export_revision desc limit 1":" and export_revision=?"),(rs,n)->{
          try {
            var payload=mapper.readTree(rs.getString("payload"));
            List<AssignmentRow> rows=mapper.convertValue(payload.path("rows"),new TypeReference<List<AssignmentRow>>(){});
            Map<String,Object> stats=mapper.convertValue(payload.path("stats"),new TypeReference<Map<String,Object>>(){});
            return new Snapshot(lease.workspaceId(),lease.generation(),lease.orgId(),task,run,rs.getLong("export_revision"),
                rs.getInt("schema_version"),rs.getTimestamp("snapshot_at").toInstant(),List.copyOf(rows),Collections.unmodifiableMap(stats));
          }catch(java.io.IOException failure){throw new IllegalStateException("Cannot decode output snapshot",failure);}
        },args.toArray());
    if(found.size()!=1)throw unavailable();fence.assertReadable(lease);return found.getFirst();
  }
  public void requireToken(DemoLease lease,UUID task,UUID run,String token) {
    if(token==null || token.isBlank())throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,"DEMO_OUTPUT_TOKEN_REQUIRED","请从当前排考结果获取输出版本后调整");
    if(!latest(lease,task,run).outputToken().equals(token))throw new ApiException(HttpStatus.PRECONDITION_FAILED,"DEMO_OUTPUT_TOKEN_STALE","排考结果已变化，请明确刷新后重新操作");
  }
  public static ApiException unavailable(){return new ApiException(HttpStatus.NOT_FOUND,"SCHEDULE_EXPORT_VERSION_UNAVAILABLE","指定排考输出版本不可用");}
}
