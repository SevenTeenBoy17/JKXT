package com.jkxt.org.api;

import com.jkxt.org.api.OrgSettingsDtos.OrgSettingsDto;
import com.jkxt.org.api.OrgSettingsDtos.OrgSettingsRequest;
import com.jkxt.org.service.OrgSettingsService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机构业务设置（津贴单价等，租户自服务）。
 *
 * <p>权限与 /terms 同一手法：路径命中 SecurityConfig 既有兜底——GET 落
 * {@code hasAuthority("tenant:read")}、PUT 落 {@code hasAnyRole("ADMIN","SUPER_ADMIN")}，
 * 零 SecurityConfig 改动、零新增 permission。
 */
@Validated
@RestController
@RequestMapping("/api/v1/org/settings")
public class OrgSettingsController {

  private final OrgSettingsService orgSettingsService;

  public OrgSettingsController(OrgSettingsService orgSettingsService) {
    this.orgSettingsService = orgSettingsService;
  }

  @GetMapping
  public OrgSettingsDto settings() {
    return orgSettingsService.settings();
  }

  @PutMapping
  public OrgSettingsDto update(@Valid @RequestBody OrgSettingsRequest request) {
    return orgSettingsService.update(request);
  }
}
