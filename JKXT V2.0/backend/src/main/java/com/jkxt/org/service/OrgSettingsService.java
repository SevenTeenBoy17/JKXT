package com.jkxt.org.service;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.org.api.OrgSettingsDtos.OrgSettingsDto;
import com.jkxt.org.api.OrgSettingsDtos.OrgSettingsRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 机构业务设置（租户自服务）。
 *
 * <p>补齐「津贴单价永久不可改」的只读缺口：{@code organization.settings.allowanceUnit} 决定
 * 全部津贴金额（教师列表、历史统计、监考次数统计、导出报表的钱数全部由 单价×时长 现算），
 * onboarding 也承诺「场次×时长×单价」，但此前全库**没有任何接口能改它**——建租户后单价就焊死在
 * 默认 120 元/小时。与学期缺口（AcademicTermService 注释）同构：有数据、有消费方、无管理入口。
 *
 * <p>语义边界（前端必须如实展示）：金额是**现算口径**，改单价即时影响所有统计与报表——
 * 包括历史统计页面显示的金额；已导出到本地的文件不受影响。
 */
@Service
public class OrgSettingsService {

  static final BigDecimal DEFAULT_ALLOWANCE_UNIT = new BigDecimal("120");

  private final JdbcTemplate jdbcTemplate;

  public OrgSettingsService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional(readOnly = true)
  public OrgSettingsDto settings() {
    UUID orgId = TenantContext.getRequired();
    // 数字模式守卫与 MasterDataService.allowanceUnit()/ReportingService 完全同款：
    // settings 里的脏值（空串/非数字）不许把读路径炸成 22P02→500，回退默认 120。
    BigDecimal unit = jdbcTemplate.queryForObject(
        "select coalesce(case when trim(coalesce(settings->>'allowanceUnit','')) ~ '^[0-9]+([.][0-9]+)?$' "
            + "then trim(settings->>'allowanceUnit')::numeric else null end, ?) from organization where id = ?",
        BigDecimal.class, DEFAULT_ALLOWANCE_UNIT, orgId);
    return new OrgSettingsDto(unit == null ? DEFAULT_ALLOWANCE_UNIT : unit);
  }

  @Transactional
  public OrgSettingsDto update(OrgSettingsRequest request) {
    UUID orgId = TenantContext.getRequired();
    // jsonb_set 只动 allowanceUnit 一个键，organization.settings 里的其它键（abbr/color/students…）原样保留。
    // 存为 jsonb 数字而非字符串——读侧的数字模式守卫两种形态都认，但数字形态更规范。
    jdbcTemplate.update(
        "update organization set settings = jsonb_set(coalesce(settings, '{}'::jsonb), "
            + "'{allowanceUnit}', to_jsonb(?::numeric)), updated_at = now() where id = ?",
        request.allowanceUnit(), orgId);
    return settings();
  }
}
