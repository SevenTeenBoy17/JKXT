package com.jkxt.examops.api;

import com.jkxt.examops.api.OperationsDtos.ApprovalRequestView;
import com.jkxt.examops.api.OperationsDtos.EmergencyChangeView;
import com.jkxt.examops.api.OperationsDtos.ReadinessResponse;
import com.jkxt.examops.service.OperationsQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class OperationsController {

  private final OperationsQueryService service;

  public OperationsController(OperationsQueryService service) {
    this.service = service;
  }

  @GetMapping("/operations/readiness")
  public ReadinessResponse readiness() {
    return service.readiness();
  }

  @GetMapping("/approval-requests")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<ApprovalRequestView> approvals() {
    return service.approvals();
  }

  @GetMapping("/emergency-changes")
  @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
  public List<EmergencyChangeView> emergencies() {
    return service.emergencies();
  }
}
