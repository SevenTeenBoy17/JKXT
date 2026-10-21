package com.jkxt.examops.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JointInputDtos {

  private JointInputDtos() {
  }

  public record BulkRequest<T>(@NotNull List<@Valid T> items) {
  }

  public record BulkResponse(
      UUID revisionId,
      String resource,
      int acceptedCount,
      long revisionCursor,
      boolean replayed
  ) {
  }

  public record ResourceSnapshot<T>(
      UUID revisionId,
      String resource,
      long revisionCursor,
      List<T> items
  ) {
  }

  public record DemandInput(
      String demandCode,
      String name,
      UUID subjectId,
      Integer durationMinutes,
      Integer candidateCount,
      Integer invigilatorCount,
      Integer minRooms,
      Integer maxRooms,
      Boolean splitAllowed,
      List<String> requiredRoomFeatures,
      Map<String, Object> settings
  ) {
  }

  public record DemandView(
      UUID id,
      String demandCode,
      String name,
      UUID subjectId,
      int durationMinutes,
      int candidateCount,
      int invigilatorCount,
      int minRooms,
      int maxRooms,
      boolean splitAllowed,
      List<String> requiredRoomFeatures,
      Map<String, Object> settings
  ) {
  }

  public record DemandClassInput(
      String demandCode,
      UUID classId,
      Integer expectedCandidates,
      Map<String, Object> settings
  ) {
  }

  public record DemandClassView(
      UUID id,
      String demandCode,
      UUID classId,
      int expectedCandidates,
      Map<String, Object> settings
  ) {
  }

  public record DemandWindowInput(
      String demandCode,
      String windowCode,
      Instant startsAt,
      Instant endsAt,
      Integer preferenceWeight,
      Boolean locked,
      Map<String, Object> settings
  ) {
  }

  public record DemandWindowView(
      UUID id,
      String demandCode,
      String windowCode,
      Instant startsAt,
      Instant endsAt,
      int preferenceWeight,
      boolean locked,
      Map<String, Object> settings
  ) {
  }

  public record CandidateInput(
      String candidateCode,
      String name,
      UUID classId,
      Map<String, Object> attributes
  ) {
  }

  public record CandidateView(
      UUID id,
      String candidateCode,
      String name,
      UUID classId,
      Map<String, Object> attributes
  ) {
  }

  public record EnrollmentInput(
      String candidateCode,
      String demandCode,
      String status,
      Map<String, Object> settings
  ) {
  }

  public record EnrollmentView(
      UUID id,
      String candidateCode,
      String demandCode,
      String status,
      Map<String, Object> settings
  ) {
  }

  public record CandidateWindowInput(
      String candidateCode,
      String windowType,
      Instant startsAt,
      Instant endsAt,
      String reason,
      Map<String, Object> settings
  ) {
  }

  public record CandidateWindowView(
      UUID id,
      String candidateCode,
      String windowType,
      Instant startsAt,
      Instant endsAt,
      String reason,
      Map<String, Object> settings
  ) {
  }

  public record SpecialRequirementInput(
      String candidateCode,
      String demandCode,
      String requirementCode,
      String requirementType,
      List<String> requiredFeatures,
      Integer extraMinutes,
      Boolean separateRoom,
      Map<String, Object> details
  ) {
  }

  public record SpecialRequirementView(
      UUID id,
      String candidateCode,
      String demandCode,
      String requirementCode,
      String requirementType,
      List<String> requiredFeatures,
      int extraMinutes,
      boolean separateRoom,
      Map<String, Object> details
  ) {
  }

  public record RoomPoolInput(
      UUID classroomId,
      String campusCode,
      Integer examCapacity,
      List<String> features,
      String status,
      Map<String, Object> settings
  ) {
  }

  public record RoomPoolView(
      UUID id,
      UUID classroomId,
      String campusCode,
      int examCapacity,
      List<String> features,
      String status,
      Map<String, Object> settings
  ) {
  }

  public record RoomWindowInput(
      UUID classroomId,
      String windowType,
      Instant startsAt,
      Instant endsAt,
      String reason,
      Map<String, Object> settings
  ) {
  }

  public record RoomWindowView(
      UUID id,
      UUID classroomId,
      String windowType,
      Instant startsAt,
      Instant endsAt,
      String reason,
      Map<String, Object> settings
  ) {
  }

  public record SeatInput(
      UUID classroomId,
      String seatCode,
      Integer rowNo,
      Integer columnNo,
      List<String> features,
      String status
  ) {
  }

  public record SeatView(
      UUID id,
      UUID classroomId,
      String seatCode,
      Integer rowNo,
      Integer columnNo,
      List<String> features,
      String status
  ) {
  }

  public record TeacherPoolInput(
      UUID teacherId,
      List<String> eligibleRoles,
      Integer maxDailySessions,
      Integer minRestMinutes,
      String preferredCampus,
      String status,
      Map<String, Object> preferences
  ) {
  }

  public record TeacherPoolView(
      UUID id,
      UUID teacherId,
      List<String> eligibleRoles,
      int maxDailySessions,
      int minRestMinutes,
      String preferredCampus,
      String status,
      Map<String, Object> preferences
  ) {
  }

  public record AvoidanceInput(
      UUID teacherId,
      String targetType,
      String demandCode,
      UUID classId,
      String candidateCode,
      String severity,
      String reason,
      Map<String, Object> details
  ) {
  }

  public record AvoidanceView(
      UUID id,
      UUID teacherId,
      String targetType,
      String demandCode,
      UUID classId,
      String candidateCode,
      String severity,
      String reason,
      Map<String, Object> details
  ) {
  }

  public record CampusTravelInput(
      String fromCampus,
      String toCampus,
      Integer travelMinutes,
      Integer bufferMinutes,
      Map<String, Object> settings
  ) {
  }

  public record CampusTravelView(
      UUID id,
      String fromCampus,
      String toCampus,
      int travelMinutes,
      int bufferMinutes,
      Map<String, Object> settings
  ) {
  }
}
