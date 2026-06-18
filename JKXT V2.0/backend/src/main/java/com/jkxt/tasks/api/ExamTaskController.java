package com.jkxt.tasks.api;

import com.jkxt.tasks.api.ExamTaskDtos.AssignmentsResponse;
import com.jkxt.tasks.api.ExamTaskDtos.CreateExamTaskRequest;
import com.jkxt.tasks.api.ExamTaskDtos.ExamTaskResponse;
import com.jkxt.tasks.api.ExamTaskDtos.JobResponse;
import com.jkxt.tasks.api.ExamTaskDtos.UploadResponse;
import com.jkxt.tasks.api.ExamTaskDtos.ValidationResponse;
import com.jkxt.tasks.service.ExamTaskService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class ExamTaskController {

  private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final ExamTaskService examTaskService;
  private final com.jkxt.scheduling.service.ScheduleJobService scheduleJobService;

  public ExamTaskController(
      ExamTaskService examTaskService,
      com.jkxt.scheduling.service.ScheduleJobService scheduleJobService
  ) {
    this.examTaskService = examTaskService;
    this.scheduleJobService = scheduleJobService;
  }

  @PostMapping("/exam-tasks")
  public ExamTaskResponse create(@RequestBody(required = false) CreateExamTaskRequest request) {
    return examTaskService.create(request);
  }

  @GetMapping("/exam-tasks")
  public List<ExamTaskResponse> list() {
    return examTaskService.list();
  }

  @GetMapping("/exam-tasks/{taskId}")
  public ExamTaskResponse detail(@PathVariable UUID taskId) {
    return examTaskService.detail(taskId);
  }

  @PostMapping(value = "/exam-tasks/{taskId}/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public UploadResponse upload(
      @PathVariable UUID taskId,
      @RequestParam String kind,
      @RequestParam(required = false) MultipartFile file,
      @RequestParam(defaultValue = "false") boolean sample
  ) {
    return examTaskService.upload(taskId, kind, file, sample);
  }

  @PostMapping("/exam-tasks/{taskId}/imports/validate")
  public ValidationResponse validate(@PathVariable UUID taskId) {
    return examTaskService.validate(taskId);
  }

  @PostMapping("/exam-tasks/{taskId}/schedule-jobs")
  public JobResponse startScheduleJob(@PathVariable UUID taskId) {
    return examTaskService.startScheduleJob(taskId);
  }

  @GetMapping("/schedule-jobs/{jobId}")
  public JobResponse job(@PathVariable UUID jobId) {
    return examTaskService.job(jobId);
  }

  @GetMapping(value = "/schedule-jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter jobEvents(@PathVariable UUID jobId) {
    return scheduleJobService.stream(jobId);
  }

  @GetMapping("/exam-tasks/{taskId}/assignments")
  public AssignmentsResponse assignments(@PathVariable UUID taskId) {
    return examTaskService.assignments(taskId);
  }

  @GetMapping("/exam-tasks/{taskId}/export")
  public ResponseEntity<byte[]> export(
      @PathVariable UUID taskId,
      @RequestParam(defaultValue = "xlsx") String format
  ) {
    boolean pdf = "pdf".equalsIgnoreCase(format);
    byte[] body = examTaskService.export(taskId, pdf ? "pdf" : "xlsx");
    return ResponseEntity.ok()
        .contentType(pdf ? MediaType.APPLICATION_PDF : XLSX)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(pdf ? "jkxt-assignments.pdf" : "jkxt-assignments.xlsx")
            .build()
            .toString())
        .body(body);
  }
}
