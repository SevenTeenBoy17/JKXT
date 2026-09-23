package com.jkxt.scheduling.api;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.scheduling.api.ScheduleDtos.FixtureInfo;
import com.jkxt.scheduling.api.ScheduleDtos.RunSnapshot;
import com.jkxt.scheduling.api.ScheduleDtos.RunStartResponse;
import com.jkxt.scheduling.service.ScheduleJobService;
import com.jkxt.scheduling.solver.ScheduleFixtureFactory;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/scheduling")
public class SchedulingController {

  private final ScheduleJobService scheduleJobService;
  private final ScheduleFixtureFactory fixtureFactory;
  private final DeployProperties deployProperties;

  public SchedulingController(ScheduleJobService scheduleJobService, ScheduleFixtureFactory fixtureFactory,
      DeployProperties deployProperties) {
    this.scheduleJobService = scheduleJobService;
    this.fixtureFactory = fixtureFactory;
    this.deployProperties = deployProperties;
  }

  /**
   * fixture 端点是 P6 阶段留下的求解器开发脚手架，只应存在于本地开发部署。
   *
   * <p>此前它们对**任何持有 {@code schedule:manage} 的教务管理员**开放（SecurityConfig 的
   * {@code POST /api/v1/scheduling/**} 规则），而
   * {@link ScheduleJobService#startFixtureRun} 会经 {@code upsertFixtureMasterData} 按
   * teacherNo/roomCode **覆盖写租户的真实教师与教室档案**——把教师性别抹成 unknown、
   * 把教室 building 改写成 "P6 Fixture"、features 改写成 ["solver-fixture"]、并按 fixture 值
   * 覆盖容量。该破坏在本项目已实际发生过（基线教室 C101–C104 被覆盖后需人工清理）。
   *
   * <p>前端从不调用这些端点（全仓零引用），因此收紧不影响任何既有功能。
   * 返回 404 而非 403：非开发部署下这些端点在语义上就不该存在，不必对外确认其存在性。
   */
  private void requireDevScaffolding() {
    if (!deployProperties.isDev()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
  }

  @GetMapping("/fixtures")
  public List<FixtureInfo> fixtures() {
    requireDevScaffolding();
    return fixtureFactory.fixtureIds().stream()
        .map(id -> new FixtureInfo(id, fixtureFactory.fixture(id).name()))
        .toList();
  }

  @PostMapping("/fixtures/{fixtureId}/solve")
  public ScheduleSolverResult solveFixture(@PathVariable String fixtureId) {
    requireDevScaffolding();
    return scheduleJobService.solveFixture(fixtureId);
  }

  @PostMapping("/fixtures/{fixtureId}/runs")
  public RunStartResponse startFixtureRun(@PathVariable String fixtureId) {
    requireDevScaffolding();
    return scheduleJobService.startFixtureRun(fixtureId);
  }

  @GetMapping("/runs/{runId}")
  public RunSnapshot run(@PathVariable UUID runId) {
    return scheduleJobService.snapshot(runId);
  }

  @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable UUID runId) {
    return scheduleJobService.stream(runId);
  }
}
