package com.jkxt.masterdata.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class MasterDataDtos {

  private MasterDataDtos() {
  }

  public record TeacherRequest(
      @NotBlank String teacherNo,
      @NotBlank String name,
      String gender,
      String grade,
      String phone,
      String email,
      String status,
      @Positive Integer age,
      @Size(max = 80) String position,
      @Valid List<TeacherWindow> leaveWindows,
      List<@NotBlank String> functionalAccommodations,
      @Valid List<TeacherWindow> dutyWindows,
      @Size(max = 2) List<@NotBlank String> qualifications,
      LocalDate qualificationExpiry,
      @Min(1) @Max(12) Integer maxDailySessions,
      @Size(max = 120) String preferredCampus,
      @JsonProperty("isHomeroom") Boolean homeroom,
      UUID homeroomClassId,
      List<UUID> subjectIds
  ) {
  }

  public record TeacherDto(
      UUID uuid,
      String id,
      String teacherNo,
      String name,
      String gender,
      String grade,
      String phone,
      String email,
      String status,
      Integer age,
      String position,
      List<TeacherWindow> leaveWindows,
      List<String> functionalAccommodations,
      List<TeacherWindow> dutyWindows,
      List<String> qualifications,
      LocalDate qualificationExpiry,
      int maxDailySessions,
      String preferredCampus,
      @JsonProperty("isHomeroom") boolean homeroom,
      UUID homeroomClassId,
      String homeroomOf,
      List<String> subjects,
      int supCount,
      double allowance,
      String gradeColor
  ) {
  }

  /** Ordered teacher availability interval persisted as JSON and evaluated by the scheduler. */
  public record TeacherWindow(
      @NotNull LocalDateTime start,
      @NotNull LocalDateTime end,
      String reason
  ) {
  }

  public record ClassroomRequest(
      @NotBlank String code,
      @NotBlank String name,
      String building,
      String floor,
      @PositiveOrZero Integer capacity,
      @PositiveOrZero Integer examCapacity,
      String roomType,
      String status,
      List<String> features
  ) {
  }

  public record ClassroomDto(
      UUID uuid,
      String id,
      String code,
      String name,
      String building,
      String floor,
      int capacity,
      int examCapacity,
      String roomType,
      String type,
      String status,
      List<String> features,
      Integer usage
  ) {
  }

  public record SchoolClassRequest(
      @NotBlank String code,
      @NotBlank String name,
      @NotBlank String grade,
      String stage,
      @PositiveOrZero Integer studentCount,
      UUID homeroomTeacherId,
      UUID defaultClassroomId,
      String status,
      List<UUID> subjectIds
  ) {
  }

  public record SchoolClassDto(
      UUID uuid,
      String id,
      String code,
      String name,
      String grade,
      String stage,
      int studentCount,
      int students,
      UUID homeroomTeacherId,
      String homeroom,
      UUID defaultClassroomId,
      String defaultClassroom,
      String status,
      int subjects,
      String gradeColor
  ) {
  }

  public record SubjectRequest(
      String code,
      @NotBlank String name,
      String category,
      String examType,
      BigDecimal hours,
      String color,
      String status
  ) {
  }

  public record SubjectDto(
      UUID uuid,
      String id,
      String code,
      String name,
      String category,
      String examType,
      BigDecimal hours,
      String color,
      String status,
      List<String> grades,
      int examCount
  ) {
  }
}
