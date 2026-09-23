package com.jkxt.org.api;

import com.jkxt.org.api.AppearanceDtos.AppearanceResponse;
import com.jkxt.org.api.AppearanceDtos.UpdateAppearanceRequest;
import com.jkxt.org.service.AppearanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${jkxt.api-base:/api/v1}/org/settings/appearance")
public class AppearanceController {

  private final AppearanceService service;

  public AppearanceController(AppearanceService service) {
    this.service = service;
  }

  @GetMapping
  public AppearanceResponse get() {
    return service.get();
  }

  @PutMapping
  public AppearanceResponse update(@Valid @RequestBody UpdateAppearanceRequest request) {
    return service.update(request);
  }
}
