package com.jkxt.examops.api;

import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.JointInputDtos.AvoidanceInput;
import com.jkxt.examops.api.JointInputDtos.AvoidanceView;
import com.jkxt.examops.api.JointInputDtos.BulkRequest;
import com.jkxt.examops.api.JointInputDtos.BulkResponse;
import com.jkxt.examops.api.JointInputDtos.CampusTravelInput;
import com.jkxt.examops.api.JointInputDtos.CampusTravelView;
import com.jkxt.examops.api.JointInputDtos.CandidateInput;
import com.jkxt.examops.api.JointInputDtos.CandidateView;
import com.jkxt.examops.api.JointInputDtos.CandidateWindowInput;
import com.jkxt.examops.api.JointInputDtos.CandidateWindowView;
import com.jkxt.examops.api.JointInputDtos.DemandClassInput;
import com.jkxt.examops.api.JointInputDtos.DemandClassView;
import com.jkxt.examops.api.JointInputDtos.DemandInput;
import com.jkxt.examops.api.JointInputDtos.DemandView;
import com.jkxt.examops.api.JointInputDtos.DemandWindowInput;
import com.jkxt.examops.api.JointInputDtos.DemandWindowView;
import com.jkxt.examops.api.JointInputDtos.EnrollmentInput;
import com.jkxt.examops.api.JointInputDtos.EnrollmentView;
import com.jkxt.examops.api.JointInputDtos.ResourceSnapshot;
import com.jkxt.examops.api.JointInputDtos.RoomPoolInput;
import com.jkxt.examops.api.JointInputDtos.RoomPoolView;
import com.jkxt.examops.api.JointInputDtos.RoomWindowInput;
import com.jkxt.examops.api.JointInputDtos.RoomWindowView;
import com.jkxt.examops.api.JointInputDtos.SeatInput;
import com.jkxt.examops.api.JointInputDtos.SeatView;
import com.jkxt.examops.api.JointInputDtos.SpecialRequirementInput;
import com.jkxt.examops.api.JointInputDtos.SpecialRequirementView;
import com.jkxt.examops.api.JointInputDtos.TeacherPoolInput;
import com.jkxt.examops.api.JointInputDtos.TeacherPoolView;
import com.jkxt.examops.service.JointInputReadService;
import com.jkxt.examops.service.JointInputWriteService;
import com.jkxt.security.auth.AuthException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/plan-revisions/{revisionId}/inputs")
@PreAuthorize("hasAuthority('schedule:manage')")
public class JointInputController {

  private final JointInputReadService readService;
  private final JointInputWriteService writeService;

  public JointInputController(JointInputReadService readService, JointInputWriteService writeService) {
    this.readService = readService;
    this.writeService = writeService;
  }

  @GetMapping("/demands")
  public ResourceSnapshot<DemandView> demands(@PathVariable UUID revisionId) {
    return readService.demands(revisionId);
  }

  @PostMapping("/demands")
  public BulkResponse demands(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<DemandInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "demands", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/demand-classes")
  public ResourceSnapshot<DemandClassView> demandClasses(@PathVariable UUID revisionId) {
    return readService.demandClasses(revisionId);
  }

  @PostMapping("/demand-classes")
  public BulkResponse demandClasses(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<DemandClassInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "demand-classes", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/demand-windows")
  public ResourceSnapshot<DemandWindowView> demandWindows(@PathVariable UUID revisionId) {
    return readService.demandWindows(revisionId);
  }

  @PostMapping("/demand-windows")
  public BulkResponse demandWindows(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<DemandWindowInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "demand-windows", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/candidates")
  public ResourceSnapshot<CandidateView> candidates(@PathVariable UUID revisionId) {
    return readService.candidates(revisionId);
  }

  @PostMapping("/candidates")
  public BulkResponse candidates(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<CandidateInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "candidates", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/enrollments")
  public ResourceSnapshot<EnrollmentView> enrollments(@PathVariable UUID revisionId) {
    return readService.enrollments(revisionId);
  }

  @PostMapping("/enrollments")
  public BulkResponse enrollments(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<EnrollmentInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "enrollments", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/candidate-windows")
  public ResourceSnapshot<CandidateWindowView> candidateWindows(@PathVariable UUID revisionId) {
    return readService.candidateWindows(revisionId);
  }

  @PostMapping("/candidate-windows")
  public BulkResponse candidateWindows(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<CandidateWindowInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "candidate-windows", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/special-requirements")
  public ResourceSnapshot<SpecialRequirementView> specialRequirements(@PathVariable UUID revisionId) {
    return readService.specialRequirements(revisionId);
  }

  @PostMapping("/special-requirements")
  public BulkResponse specialRequirements(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<SpecialRequirementInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "special-requirements", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/room-pool")
  public ResourceSnapshot<RoomPoolView> roomPool(@PathVariable UUID revisionId) {
    return readService.roomPool(revisionId);
  }

  @PostMapping("/room-pool")
  public BulkResponse roomPool(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<RoomPoolInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "room-pool", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/room-windows")
  public ResourceSnapshot<RoomWindowView> roomWindows(@PathVariable UUID revisionId) {
    return readService.roomWindows(revisionId);
  }

  @PostMapping("/room-windows")
  public BulkResponse roomWindows(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<RoomWindowInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "room-windows", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/seat-topology")
  public ResourceSnapshot<SeatView> seats(@PathVariable UUID revisionId) {
    return readService.seats(revisionId);
  }

  @PostMapping("/seat-topology")
  public BulkResponse seats(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<SeatInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "seat-topology", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/teacher-pool")
  public ResourceSnapshot<TeacherPoolView> teacherPool(@PathVariable UUID revisionId) {
    return readService.teacherPool(revisionId);
  }

  @PostMapping("/teacher-pool")
  public BulkResponse teacherPool(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<TeacherPoolInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "teacher-pool", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/avoidances")
  public ResourceSnapshot<AvoidanceView> avoidances(@PathVariable UUID revisionId) {
    return readService.avoidances(revisionId);
  }

  @PostMapping("/avoidances")
  public BulkResponse avoidances(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<AvoidanceInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "avoidances", body, ifMatch, idempotencyKey, request);
  }

  @GetMapping("/campus-travel")
  public ResourceSnapshot<CampusTravelView> campusTravel(@PathVariable UUID revisionId) {
    return readService.campusTravel(revisionId);
  }

  @PostMapping("/campus-travel")
  public BulkResponse campusTravel(
      @PathVariable UUID revisionId, @Valid @RequestBody BulkRequest<CampusTravelInput> body,
      @RequestHeader("If-Match") String ifMatch,
      @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request
  ) {
    return write(revisionId, "campus-travel", body, ifMatch, idempotencyKey, request);
  }

  private BulkResponse write(
      UUID revisionId, String resource, BulkRequest<?> body, String ifMatch,
      String idempotencyKey, HttpServletRequest request
  ) {
    return writeService.replace(revisionId, resource, body.items(), idempotencyKey,
        cursor(ifMatch), principal(request).userId());
  }

  private long cursor(String value) {
    if (value == null || value.isBlank()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IF_MATCH_REQUIRED", "If-Match is required");
    }
    String normalized = value.trim();
    if (normalized.startsWith("W/")) normalized = normalized.substring(2).trim();
    if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    try {
      long cursor = Long.parseLong(normalized);
      if (cursor < 0) throw new NumberFormatException("negative cursor");
      return cursor;
    } catch (NumberFormatException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "IF_MATCH_INVALID",
          "If-Match must contain a non-negative numeric revision cursor");
    }
  }

  private SessionPrincipal principal(HttpServletRequest request) {
    Object value = request.getAttribute(AuthRequestAttributes.PRINCIPAL);
    if (value instanceof SessionPrincipal principal) return principal;
    throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHENTICATED", "Authentication is required");
  }
}
