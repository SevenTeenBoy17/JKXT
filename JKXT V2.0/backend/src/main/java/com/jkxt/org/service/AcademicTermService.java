package com.jkxt.org.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.org.api.AcademicTermDtos.AcademicTermDto;
import com.jkxt.org.api.AcademicTermDtos.AcademicTermRequest;
import com.jkxt.org.domain.AcademicTerm;
import com.jkxt.org.repo.AcademicTermRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学年学期管理。
 *
 * <p>此前 {@code academic_term} 只有实体与仓储、**没有任何 Controller/Service**：学期仅由
 * {@code AuthService.currentTermView()} 在 /auth/me 只读下发，前端顶栏点「切换学期」提示去信息设置，
 * 而信息设置页根本没有学期区块 —— 形成「提示指向不存在的功能」的死循环（用户实测报告）。
 * 本类补齐 org 级学期的读写能力。
 *
 * <p><b>重要语义边界</b>：当前后端**尚未按学期切分业务数据** —— {@code exam_task.term_id} 在运行时
 * 从不写入（实测 18/18 为 NULL），报表端点也不接受 term 参数。因此「切换当前学期」目前只改变
 * 档案标识与展示口径，**不会改动已生成的排班结果**。前端必须如实标注这一点，否则修好一个死循环
 * 又会造出「切了却什么都没变」的新误导。
 */
@Service
public class AcademicTermService {

  private final AcademicTermRepository termRepository;

  public AcademicTermService(AcademicTermRepository termRepository) {
    this.termRepository = termRepository;
  }

  /** 本 org 的全部学期，按开始日期倒序（最近的学期排在最前，符合运维查看习惯）。 */
  @Transactional(readOnly = true)
  public List<AcademicTermDto> list() {
    UUID orgId = TenantContext.getRequired();
    return termRepository.findByOrgId(orgId).stream()
        .sorted(Comparator.comparing(AcademicTerm::getStartDate).reversed())
        .map(AcademicTermService::toDto)
        .toList();
  }

  /** 当前学期；未设定时返回 {@code null}（新租户尚无学期是合法状态，不应报错）。 */
  @Transactional(readOnly = true)
  public AcademicTermDto current() {
    UUID orgId = TenantContext.getRequired();
    return termRepository.findByOrgIdAndCurrentTrue(orgId).stream()
        .findFirst()
        .map(AcademicTermService::toDto)
        .orElse(null);
  }

  @Transactional
  public AcademicTermDto create(AcademicTermRequest request) {
    UUID orgId = TenantContext.getRequired();
    validateDates(request);
    // (org_id, year_label, term) 是唯一键；提前给出可读错误，而不是让 DB 约束抛 500。
    boolean duplicated = termRepository.findByOrgId(orgId).stream()
        .anyMatch(t -> t.getYearLabel().equals(request.yearLabel().trim())
            && t.getTerm().equalsIgnoreCase(request.term()));
    if (duplicated) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "该学年学期已存在：" + request.yearLabel() + " " + termLabel(request.term()));
    }
    AcademicTerm term = new AcademicTerm();
    term.setOrgId(orgId);
    term.setYearLabel(request.yearLabel().trim());
    term.setTerm(request.term().toUpperCase());
    term.setName(request.name() == null || request.name().isBlank()
        ? request.yearLabel().trim() + " " + termLabel(request.term())
        : request.name().trim());
    term.setStartDate(request.startDate());
    term.setEndDate(request.endDate());
    term.setCurrent(false);
    AcademicTerm saved = termRepository.saveAndFlush(term);
    if (Boolean.TRUE.equals(request.current())) {
      return activate(saved.getId());
    }
    return toDto(saved);
  }

  @Transactional
  public AcademicTermDto update(UUID id, AcademicTermRequest request) {
    UUID orgId = TenantContext.getRequired();
    validateDates(request);
    AcademicTerm term = require(orgId, id);
    term.setYearLabel(request.yearLabel().trim());
    term.setTerm(request.term().toUpperCase());
    term.setName(request.name() == null || request.name().isBlank()
        ? request.yearLabel().trim() + " " + termLabel(request.term())
        : request.name().trim());
    term.setStartDate(request.startDate());
    term.setEndDate(request.endDate());
    return toDto(termRepository.saveAndFlush(term));
  }

  /**
   * 设为当前学期。
   *
   * <p>先把本 org 其它学期全部置 false 并 flush，再置目标为 true —— V17 建了部分唯一索引
   * {@code uq_academic_term_org_current ON academic_term(org_id) WHERE is_current}，
   * 若不先清空旧的 current，同一事务内会撞唯一约束。
   */
  @Transactional
  public AcademicTermDto activate(UUID id) {
    UUID orgId = TenantContext.getRequired();
    AcademicTerm target = require(orgId, id);
    List<AcademicTerm> others = termRepository.findByOrgIdAndCurrentTrue(orgId).stream()
        .filter(t -> !t.getId().equals(id))
        .toList();
    if (!others.isEmpty()) {
      others.forEach(t -> t.setCurrent(false));
      termRepository.saveAll(others);
      termRepository.flush();
    }
    if (!target.isCurrent()) {
      target.setCurrent(true);
      termRepository.saveAndFlush(target);
    }
    return toDto(target);
  }

  @Transactional
  public void delete(UUID id) {
    UUID orgId = TenantContext.getRequired();
    AcademicTerm term = require(orgId, id);
    if (term.isCurrent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "当前学期不可删除，请先把其它学期设为当前后再删除");
    }
    termRepository.delete(term);
  }

  /** 供其它模块取当前学期 id（如日后给 exam_task 打上 term_id）。 */
  @Transactional(readOnly = true)
  public Optional<UUID> currentTermId(UUID orgId) {
    return termRepository.findByOrgIdAndCurrentTrue(orgId).stream()
        .findFirst()
        .map(AcademicTerm::getId);
  }

  private AcademicTerm require(UUID orgId, UUID id) {
    return termRepository.findById(id)
        .filter(t -> orgId.equals(t.getOrgId()))   // 跨租户越权按"不存在"处理，不泄露存在性
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学期不存在"));
  }

  private void validateDates(AcademicTermRequest request) {
    if (!request.endDate().isAfter(request.startDate())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日期必须晚于开始日期");
    }
  }

  private static String termLabel(String term) {
    return "S1".equalsIgnoreCase(term) ? "第一学期" : "第二学期";
  }

  private static AcademicTermDto toDto(AcademicTerm t) {
    return new AcademicTermDto(t.getId(), t.getYearLabel(), t.getTerm(), termLabel(t.getTerm()),
        t.getName(), t.getStartDate(), t.getEndDate(), t.isCurrent());
  }
}
