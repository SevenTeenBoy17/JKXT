package com.jkxt.scheduling.api;

import com.jkxt.scheduling.api.ScheduleDtos.FixtureInfo;
import com.jkxt.scheduling.api.ScheduleDtos.RunSnapshot;
import com.jkxt.scheduling.api.ScheduleDtos.RunStartResponse;
import com.jkxt.scheduling.service.ScheduleJobService;
import com.jkxt.scheduling.solver.ScheduleFixtureFactory;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/scheduling")
public class SchedulingController {

  private final ScheduleJobService scheduleJobService;
  private final ScheduleFixtureFactory fixtureFactory;

  public SchedulingController(ScheduleJobService scheduleJobService, ScheduleFixtureFactory fixtureFactory) {
    this.scheduleJobService = scheduleJobService;
    this.fixtureFactory = fixtureFactory;
  }

  @GetMapping("/fixtures")
  public List<FixtureInfo> fixtures() {
    return fixtureFactory.fixtureIds().stream()
        .map(id -> new FixtureInfo(id, fixtureFactory.fixture(id).name()))
        .toList();
  }

  @PostMapping("/fixtures/{fixtureId}/solve")
  public ScheduleSolverResult solveFixture(@PathVariable String fixtureId) {
    return scheduleJobService.solveFixture(fixtureId);
  }

  @PostMapping("/fixtures/{fixtureId}/runs")
  public RunStartResponse startFixtureRun(@PathVariable String fixtureId) {
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
