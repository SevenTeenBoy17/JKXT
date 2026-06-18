package com.jkxt.masterdata.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
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
      @JsonProperty("isHomeroom") boolean homeroom,
      UUID homeroomClassId,
      String homeroomOf,
      List<String> subjects,
      int supCount,
      double allowance,
      String gradeColor
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
      int usage
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
