package com.jkxt.demo.api;

import com.jkxt.demo.*;
import com.jkxt.demo.api.DemoDtos.*;
import com.jkxt.security.auth.AuthRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoOverviewController {
  private final DemoWorkspaceRepository repository;private final JdbcTemplate jdbc;private final HttpServletRequest request;
  public DemoOverviewController(DemoWorkspaceRepository repository,JdbcTemplate jdbc,HttpServletRequest request){this.repository=repository;this.jdbc=jdbc;this.request=request;}
  @GetMapping("/overview") public DemoOverview overview() {
    repository.requireDemo();
    if(!(request.getAttribute(AuthRequestAttributes.DEMO_LEASE) instanceof DemoLease lease))throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_OPERATION_FORBIDDEN","需要演示会话");
    return repository.transaction(()->{
      var w=repository.workspace(lease.workspaceId(),true);repository.matches(w,lease);repository.ready(w);
      var tasks=jdbc.query("select id,name,status,updated_at from exam_task where org_id=? order by updated_at desc,id limit 5",
          (rs,n)->new DemoTaskSummary(rs.getObject("id",java.util.UUID.class),rs.getString("name"),rs.getString("status"),rs.getTimestamp("updated_at").toInstant()),lease.orgId());
      return new DemoOverview(count("teacher",lease),count("classroom",lease),count("exam",lease),tasks);
    });
  }
  private long count(String table,DemoLease lease){return jdbc.queryForObject("select count(*) from "+table+" where org_id=?",Long.class,lease.orgId());}
}
