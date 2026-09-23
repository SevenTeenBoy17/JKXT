package com.jkxt.platform.api;

import com.jkxt.platform.service.AdminPlatformService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')") // platform-operator only; tenant ADMIN/JIAOWU must be denied
public class AdminPlatformController {

  private final AdminPlatformService adminPlatformService;

  public AdminPlatformController(AdminPlatformService adminPlatformService) {
    this.adminPlatformService = adminPlatformService;
  }

  @GetMapping("/tenants")
  public List<Map<String, Object>> tenants() {
    return adminPlatformService.tenants();
  }

  @PostMapping("/tenants")
  public Map<String, Object> createTenant(@RequestBody(required = false) Map<String, Object> request) {
    return adminPlatformService.createTenant(request == null ? Map.of() : request);
  }

  @PutMapping("/tenants/{tenantId}")
  public Map<String, Object> updateTenant(@PathVariable UUID tenantId, @RequestBody Map<String, Object> request) {
    return adminPlatformService.updateTenant(tenantId, request);
  }

  @PostMapping("/tenants/{tenantId}/suspend")
  public Map<String, Object> suspendTenant(@PathVariable UUID tenantId) {
    return adminPlatformService.setTenantStatus(tenantId, "suspended");
  }

  @PostMapping("/tenants/{tenantId}/restore")
  public Map<String, Object> restoreTenant(@PathVariable UUID tenantId) {
    return adminPlatformService.setTenantStatus(tenantId, "active");
  }

  @GetMapping("/accounts")
  public List<Map<String, Object>> accounts() {
    return adminPlatformService.accounts();
  }

  @PostMapping("/accounts")
  public Map<String, Object> createAccount(@RequestBody(required = false) Map<String, Object> request) {
    return adminPlatformService.createAccount(request == null ? Map.of() : request);
  }

  @PostMapping("/accounts/{accountId}/reset-password")
  public Map<String, Object> resetPassword(@PathVariable UUID accountId) {
    return adminPlatformService.accountAction(accountId, "reset-password");
  }

  @PostMapping("/accounts/{accountId}/force-logout")
  public Map<String, Object> forceLogout(@PathVariable UUID accountId) {
    return adminPlatformService.accountAction(accountId, "force-logout");
  }

  @PostMapping("/accounts/{accountId}/disable")
  public Map<String, Object> disableAccount(@PathVariable UUID accountId) {
    return adminPlatformService.accountAction(accountId, "disable");
  }

  @PostMapping("/accounts/{accountId}/delete")
  public Map<String, Object> deleteAccount(@PathVariable UUID accountId) {
    return adminPlatformService.accountAction(accountId, "delete");
  }

  @GetMapping("/system-health")
  public Map<String, Object> systemHealth() {
    return adminPlatformService.systemHealth();
  }

  @GetMapping("/audit-logs")
  public List<Map<String, Object>> auditLogs(
      @org.springframework.web.bind.annotation.RequestParam(required = false) Integer hours) {
    return adminPlatformService.auditLogs(hours);
  }

  @GetMapping("/security/overview")
  public Map<String, Object> securityOverview() {
    return adminPlatformService.securityOverview();
  }

  @GetMapping("/security/threats")
  public List<Map<String, Object>> securityThreats() {
    return adminPlatformService.securityThreats();
  }

  @PostMapping("/security/ip-ban")
  public Map<String, Object> banIp(@RequestBody Map<String, Object> request) {
    return adminPlatformService.addIpRule("ban", request);
  }

  @PostMapping("/security/ip-allowlist")
  public Map<String, Object> allowIp(@RequestBody Map<String, Object> request) {
    return adminPlatformService.addIpRule("allowlist", request);
  }

  @GetMapping("/backups")
  public List<Map<String, Object>> backups() {
    return adminPlatformService.backups();
  }

  @PostMapping("/backups/run")
  public Map<String, Object> runBackup() {
    return adminPlatformService.runBackup();
  }

  @GetMapping("/announcements")
  public List<Map<String, Object>> announcements() {
    return adminPlatformService.announcements();
  }

  @PostMapping("/announcements")
  public Map<String, Object> createAnnouncement(@RequestBody(required = false) Map<String, Object> request) {
    return adminPlatformService.createAnnouncement(request == null ? Map.of() : request);
  }

  @PutMapping("/announcements/{announcementId}")
  public Map<String, Object> updateAnnouncement(
      @PathVariable UUID announcementId,
      @RequestBody Map<String, Object> request
  ) {
    return adminPlatformService.updateAnnouncement(announcementId, request);
  }

  @PostMapping("/announcements/{announcementId}/publish")
  public Map<String, Object> publishAnnouncement(@PathVariable UUID announcementId) {
    return adminPlatformService.announcementAction(announcementId, "publish");
  }

  @PostMapping("/announcements/{announcementId}/push")
  public Map<String, Object> pushAnnouncement(@PathVariable UUID announcementId) {
    return adminPlatformService.announcementAction(announcementId, "push");
  }

  @PostMapping("/announcements/{announcementId}/retract")
  public Map<String, Object> retractAnnouncement(@PathVariable UUID announcementId) {
    return adminPlatformService.announcementAction(announcementId, "retract");
  }

  @GetMapping("/announcements/{announcementId}/read-stats")
  public Map<String, Object> announcementReadStats(@PathVariable UUID announcementId) {
    return adminPlatformService.announcementReadStats(announcementId);
  }

  @GetMapping("/platform-settings")
  public Map<String, Object> platformSettings() {
    return adminPlatformService.platformSettings();
  }

  @PutMapping("/platform-settings")
  public Map<String, Object> savePlatformSettings(@RequestBody Map<String, Object> request) {
    return adminPlatformService.savePlatformSettings(request);
  }
}
