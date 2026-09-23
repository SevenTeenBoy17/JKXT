package com.jkxt.org.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.UUID;

/** 学年学期（academic_term）的对外 DTO。 */
public final class AcademicTermDtos {

  private AcademicTermDtos() {
  }

  public record AcademicTermDto(
      UUID id,
      String yearLabel,
      String term,
      String termLabel,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      boolean current
  ) {
  }

  /**
   * 新建/编辑学期。{@code term} 只允许 S1/S2 —— 与 V2 迁移里
   * {@code CHECK (term IN ('S1','S2'))} 保持一致，避免请求穿到数据库才被约束拒绝。
   */
  public record AcademicTermRequest(
      @NotBlank String yearLabel,
      @NotBlank @Pattern(regexp = "S1|S2", message = "学期只能是 S1(第一学期) 或 S2(第二学期)") String term,
      String name,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      Boolean current
  ) {
  }
}
