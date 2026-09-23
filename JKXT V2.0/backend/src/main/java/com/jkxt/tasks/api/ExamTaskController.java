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

  @org.springframework.web.bind.annotation.DeleteMapping("/exam-tasks/{taskId}")
  @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID taskId) {
    examTaskService.delete(taskId);
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
  public JobResponse startScheduleJob(@PathVariable UUID taskId,
      @RequestBody(required = false) ExamTaskDtos.StartScheduleJobRequest request) {
    return examTaskService.startScheduleJob(taskId, request == null ? null : request.policyId(),
        request == null ? null : request.reviewedUploads());
  }

  @GetMapping("/schedule-jobs/{jobId}")
  public JobResponse job(@PathVariable UUID jobId) {
    return examTaskService.job(jobId);
  }

  @GetMapping(value = "/schedule-jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter jobEvents(@PathVariable UUID jobId,jakarta.servlet.http.HttpServletRequest request) {
    return scheduleJobService.stream(jobId,
        (com.jkxt.security.auth.SessionEnvelope)request.getAttribute(com.jkxt.security.auth.AuthRequestAttributes.ENVELOPE),
        com.jkxt.common.tenant.TenantContext.getRequired(),
        (com.jkxt.demo.DemoLease)request.getAttribute(com.jkxt.security.auth.AuthRequestAttributes.DEMO_LEASE));
  }

  @GetMapping("/exam-tasks/{taskId}/assignments")
  public AssignmentsResponse assignments(
      @PathVariable UUID taskId,
      @RequestParam(required = false) UUID runId
  ) {
    return examTaskService.assignments(taskId, runId);
  }

  @GetMapping("/exam-tasks/{taskId}/export")
  public ResponseEntity<byte[]> export(
      @PathVariable UUID taskId,
      @RequestParam(defaultValue = "xlsx") String format,
      @RequestParam(required=false) UUID runId,
      @RequestParam(required=false) Long revision
  ) {
    // 白名单必须在这里（controller）做：此前是 service 层 throw，但下面 `pdf?"pdf":"xlsx"` 会先把任何非
    // pdf 值(csv/json/拼写错误)强写成 xlsx，使 service 校验成死代码、非法格式被静默当 xlsx 返回。
    String fmt = format == null || format.isBlank() ? "xlsx" : format.trim().toLowerCase(java.util.Locale.ROOT);
    if (!"xlsx".equals(fmt) && !"pdf".equals(fmt)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "UNSUPPORTED_EXPORT_FORMAT: 仅支持 xlsx 或 pdf");
    }
    boolean pdf = "pdf".equals(fmt);
    byte[] body = examTaskService.export(taskId, pdf ? "pdf" : "xlsx",runId,revision);
    return ResponseEntity.ok()
        .contentType(pdf ? MediaType.APPLICATION_PDF : XLSX)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(pdf ? "jkxt-assignments.pdf" : "jkxt-assignments.xlsx")
            .build()
            .toString())
        .body(body);
  }
}
