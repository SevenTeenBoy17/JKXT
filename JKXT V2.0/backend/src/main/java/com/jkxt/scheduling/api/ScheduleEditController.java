package com.jkxt.scheduling.api;

import com.jkxt.scheduling.api.ScheduleEditDtos.ConflictCheckRequest;
import com.jkxt.scheduling.api.ScheduleEditDtos.ConflictCheckResponse;
import com.jkxt.scheduling.api.ScheduleEditDtos.PublishResponse;
import com.jkxt.scheduling.api.ScheduleEditDtos.RevisionResponse;
import com.jkxt.scheduling.api.ScheduleEditDtos.SchedulePatchRequest;
import com.jkxt.scheduling.api.ScheduleEditDtos.SchedulePatchResponse;
import com.jkxt.scheduling.service.ScheduleEditService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class ScheduleEditController {

  private final ScheduleEditService scheduleEditService;

  public ScheduleEditController(ScheduleEditService scheduleEditService) {
    this.scheduleEditService = scheduleEditService;
  }

  @PostMapping("/{taskId}/schedule/check-conflicts")
  public ConflictCheckResponse checkConflicts(
      @PathVariable UUID taskId,
      @Valid @RequestBody ConflictCheckRequest request
  ) {
    return scheduleEditService.checkConflicts(taskId, request);
  }

  @PatchMapping("/{taskId}/schedule")
  public SchedulePatchResponse patch(
      @PathVariable UUID taskId,
      @Valid @RequestBody SchedulePatchRequest request,
      @org.springframework.web.bind.annotation.RequestHeader(value="If-Match",required=false) String ifMatch
  ) {
    return scheduleEditService.patch(taskId, request,ifMatch);
  }

  @PostMapping("/{taskId}/schedule/undo")
  public RevisionResponse undo(@PathVariable UUID taskId,@org.springframework.web.bind.annotation.RequestHeader(value="If-Match",required=false) String ifMatch) {
    return scheduleEditService.undo(taskId,ifMatch);
  }

  @PostMapping("/{taskId}/schedule/redo")
  public RevisionResponse redo(@PathVariable UUID taskId,@org.springframework.web.bind.annotation.RequestHeader(value="If-Match",required=false) String ifMatch) {
    return scheduleEditService.redo(taskId,ifMatch);
  }

  @PostMapping("/{taskId}/publish")
  public PublishResponse publish(@PathVariable UUID taskId) {
    return scheduleEditService.publish(taskId);
  }
}
