package com.jkxt.org.api;

import com.jkxt.org.api.AcademicTermDtos.AcademicTermDto;
import com.jkxt.org.api.AcademicTermDtos.AcademicTermRequest;
import com.jkxt.org.service.AcademicTermService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学年学期管理（租户自服务）。
 *
 * <p>补齐此前完全缺失的 term 接口面：{@code academic_term} 有表、有实体、有仓储，却没有任何 Controller，
 * 导致顶栏「切换学期」只能提示去信息设置，而信息设置页无处可调 —— 死循环。
 *
 * <p>权限：走 {@code /api/v1/terms/**} 路径即可命中 SecurityConfig 既有规则 —— GET 落
 * {@code hasAuthority("tenant:read")}（四个角色都持有）、写操作落兜底的
 * {@code hasAnyRole("ADMIN","SUPER_ADMIN")}。**无需改动 SecurityConfig**，也不新增 permission，
 * 从而避免重演 V15 注释记载的「改 hasAuthority 后全锁死」事故。
 * 学期是租户主数据（每校独立、ADMIN 自服务），因此不放进 SUPER_ADMIN 专用的 /api/v1/admin/**。
 */
@Validated
@RestController
@RequestMapping("/api/v1/terms")
public class AcademicTermController {

  private final AcademicTermService academicTermService;

  public AcademicTermController(AcademicTermService academicTermService) {
    this.academicTermService = academicTermService;
  }

  @GetMapping
  public List<AcademicTermDto> list() {
    return academicTermService.list();
  }

  /** 当前学期；未设定时返回 204（新租户尚无学期是合法状态，不是错误）。 */
  @GetMapping("/current")
  public ResponseEntity<AcademicTermDto> current() {
    AcademicTermDto dto = academicTermService.current();
    return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AcademicTermDto create(@Valid @RequestBody AcademicTermRequest request) {
    return academicTermService.create(request);
  }

  @PutMapping("/{id}")
  public AcademicTermDto update(@PathVariable UUID id, @Valid @RequestBody AcademicTermRequest request) {
    return academicTermService.update(id, request);
  }

  /** 设为当前学期（同一 org 至多一条 current，由 V17 部分唯一索引兜底）。 */
  @PostMapping("/{id}/activate")
  public AcademicTermDto activate(@PathVariable UUID id) {
    return academicTermService.activate(id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    academicTermService.delete(id);
  }
}
