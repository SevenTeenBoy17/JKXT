package com.jkxt.org.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 租户自服务的机构业务设置（目前仅津贴单价，后续机构级参数在此扩展）。 */
public final class OrgSettingsDtos {

  private OrgSettingsDtos() {
  }

  public record OrgSettingsDto(BigDecimal allowanceUnit) {
  }

  public record OrgSettingsRequest(
      // 元/小时。上限 9999 挡手滑（如把「1200 元/月」误填成单价）；0 与负数无业务意义。
      @NotNull
      @DecimalMin(value = "0", inclusive = false, message = "津贴单价必须大于 0")
      @DecimalMax(value = "9999", message = "津贴单价超出合理范围（元/小时）")
      BigDecimal allowanceUnit
  ) {
  }
}
