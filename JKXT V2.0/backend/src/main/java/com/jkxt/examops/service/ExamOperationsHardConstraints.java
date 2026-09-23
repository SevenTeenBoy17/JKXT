package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Shared interpretation of school-local availability and room-feature hard constraints. */
@Component
public class ExamOperationsHardConstraints {

  static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> DUTY_ROLES = Set.of("INVIGILATOR", "CHIEF");
  private static final Set<String> DUTY_QUALIFICATIONS = Set.of("INVIGILATOR", "CHIEF");
  private static final Set<String> QUALIFICATION_CONTRACT_FIELDS = Set.of(
      "requiredQualifications", "requiredPositions", "requiresHomeroom");

  public boolean teacherAvailable(
      JsonNode leaveWindows,
      JsonNode dutyWindows,
      List<Window> hardLeaveWindows,
      Instant start,
      Instant end
  ) {
    if (start == null || end == null || !start.isBefore(end)) return false;
    List<Window> leaves = new ArrayList<>(windows(leaveWindows));
    if (hardLeaveWindows != null) leaves.addAll(hardLeaveWindows);
    if (leaves.stream().anyMatch(window -> overlaps(window.start(), window.end(), start, end))) return false;
    List<Window> duties = windows(dutyWindows);
    return duties.isEmpty() || duties.stream().anyMatch(window -> contains(window, start, end));
  }

  public List<Window> windows(JsonNode value) {
    if (value == null || !value.isArray()) return List.of();
    List<Window> result = new ArrayList<>();
    for (JsonNode item : value) {
      Instant start = parseInstant(item.path("start").asText(item.path("startsAt").asText(null)));
      Instant end = parseInstant(item.path("end").asText(item.path("endsAt").asText(null)));
      if (start != null && end != null && start.isBefore(end)) result.add(new Window(start, end));
    }
    return List.copyOf(result);
  }

  public Window expressionWindow(JsonNode expression) {
    if (expression == null || !expression.isObject() || !expression.path("approved").asBoolean(true)) return null;
    Instant start = parseInstant(expression.path("startsAt").asText(expression.path("start").asText(null)));
    Instant end = parseInstant(expression.path("endsAt").asText(expression.path("end").asText(null)));
    return start != null && end != null && start.isBefore(end) ? new Window(start, end) : null;
  }

  public boolean roomCompatible(JsonNode actualFeatures, JsonNode requiredFeatures) {
    return featureCodes(actualFeatures).containsAll(featureCodes(requiredFeatures));
  }

  public Set<String> missingFeatures(JsonNode actualFeatures, JsonNode requiredFeatures) {
    Set<String> missing = new LinkedHashSet<>(featureCodes(requiredFeatures));
    missing.removeAll(featureCodes(actualFeatures));
    return Set.copyOf(missing);
  }

  public boolean roomSupports(JsonNode actualFeatures, String requiredFeature) {
    return requiredFeature == null || requiredFeature.isBlank()
        || featureCodes(actualFeatures).contains(requiredFeature.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * A CHIEF duty always requires explicit CHIEF eligibility. Other roles honour the persisted
   * requirement contract when it declares qualifications, positions or homeroom status.
   */
  public boolean teacherEligible(String dutyRole, JsonNode requirement, TeacherEligibility teacher) {
    return teacherEligible(dutyRole, requirement, teacher, null);
  }

  public boolean teacherEligible(
      String dutyRole,
      JsonNode requirement,
      TeacherEligibility teacher,
      LocalDate dutyDate
  ) {
    String role = dutyRole == null ? "" : dutyRole.trim().toUpperCase(Locale.ROOT);
    if (!DUTY_ROLES.contains(role) || teacher == null
        || !validQualificationArray(teacher.qualifications())) return false;
    if (teacher.maxDailySessions() < 1) return false;
    if (dutyDate != null && teacher.qualificationExpiry() != null
        && teacher.qualificationExpiry().isBefore(dutyDate)) return false;

    boolean legacy = requirement == null || requirement.isNull() || requirement.isMissingNode();
    if (!legacy && !requirement.isObject()) return false;
    if (!legacy) {
      var fields = requirement.fieldNames();
      while (fields.hasNext()) {
        if (!QUALIFICATION_CONTRACT_FIELDS.contains(fields.next())) return false;
      }
      if (requirement.has("requiredQualifications")
          && !validQualificationArray(requirement.path("requiredQualifications"))) return false;
      if (requirement.has("requiredPositions")
          && !validNonBlankTextArray(requirement.path("requiredPositions"))) return false;
      if (requirement.has("requiresHomeroom")
          && !requirement.path("requiresHomeroom").isBoolean()) return false;
    }

    Set<String> required = new LinkedHashSet<>(strictTextCodes(
        legacy ? null : requirement.path("requiredQualifications")));
    required.add("INVIGILATOR");
    if ("CHIEF".equals(role)) required.add("CHIEF");
    if (!strictTextCodes(teacher.qualifications()).containsAll(required)) return false;

    Set<String> positions = strictTextCodes(legacy ? null : requirement.path("requiredPositions"));
    if (!positions.isEmpty()) {
      String actual = teacher.position() == null ? "" : teacher.position().trim().toUpperCase(Locale.ROOT);
      if (!positions.contains(actual)) return false;
    }
    return legacy || !requirement.path("requiresHomeroom").asBoolean(false) || teacher.homeroom();
  }

  public int campusPreferencePenalty(TeacherEligibility teacher, String roomCampus) {
    if (teacher == null || teacher.preferredCampus() == null || teacher.preferredCampus().isBlank()) return 1;
    if (roomCampus == null || roomCampus.isBlank()) return 1;
    String preferred = teacher.preferredCampus().trim().toUpperCase(Locale.ROOT);
    if (!preferred.startsWith("CAMPUS_")) preferred = "CAMPUS_" + preferred;
    return preferred.equals(roomCampus.trim().toUpperCase(Locale.ROOT)) ? 0 : 2;
  }

  public String campusCode(JsonNode roomFeatures) {
    return featureCodes(roomFeatures).stream()
        .filter(code -> code.startsWith("CAMPUS_"))
        .sorted()
        .findFirst()
        .orElse(null);
  }

  /** Persisted qualification contract used by the INVIGILATOR_ONLY planner. */
  public ObjectNode defaultDutyQualification(String dutyRole) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    var required = value.putArray("requiredQualifications").add("INVIGILATOR");
    if ("CHIEF".equalsIgnoreCase(dutyRole)) required.add("CHIEF");
    return value;
  }

  public Set<String> qualificationCodes(JsonNode value) {
    return featureCodes(value);
  }

  private boolean validQualificationArray(JsonNode value) {
    if (value == null || !value.isArray()) return false;
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode item : value) {
      if (!item.isTextual() || !item.asText().equals(item.asText().trim())
          || !DUTY_QUALIFICATIONS.contains(item.asText()) || !seen.add(item.asText())) return false;
    }
    return true;
  }

  private boolean validNonBlankTextArray(JsonNode value) {
    if (value == null || !value.isArray()) return false;
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode item : value) {
      if (!item.isTextual() || item.asText().isBlank()) return false;
      String canonical = item.asText().trim().toUpperCase(Locale.ROOT);
      if (!seen.add(canonical)) return false;
    }
    return true;
  }

  private Set<String> strictTextCodes(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return Set.of();
    Set<String> result = new LinkedHashSet<>();
    value.forEach(item -> result.add(item.asText().trim().toUpperCase(Locale.ROOT)));
    return Set.copyOf(result);
  }

  private Set<String> featureCodes(JsonNode value) {
    Set<String> result = new LinkedHashSet<>();
    if (value == null || value.isNull() || value.isMissingNode()) return Set.of();
    if (value.isArray()) {
      value.forEach(item -> {
        if (item.isTextual() && !item.asText().isBlank()) {
          result.add(item.asText().trim().toUpperCase(Locale.ROOT));
        } else if (item.isObject() && item.path("code").isTextual()) {
          result.add(item.path("code").asText().trim().toUpperCase(Locale.ROOT));
        }
      });
    } else if (value.isObject()) {
      value.fields().forEachRemaining(entry -> {
        if (entry.getValue().asBoolean(false)) result.add(entry.getKey().trim().toUpperCase(Locale.ROOT));
      });
    } else if (value.isTextual() && !value.asText().isBlank()) {
      result.add(value.asText().trim().toUpperCase(Locale.ROOT));
    }
    return Set.copyOf(result);
  }

  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (Exception ignored) {
      try {
        return Instant.parse(value);
      } catch (Exception ignoredAgain) {
        try {
          return LocalDateTime.parse(value).atZone(SCHOOL_ZONE).toInstant();
        } catch (Exception ignoredLocal) {
          return null;
        }
      }
    }
  }

  private boolean contains(Window window, Instant start, Instant end) {
    return !window.start().isAfter(start) && !window.end().isBefore(end);
  }

  private boolean overlaps(Instant firstStart, Instant firstEnd, Instant secondStart, Instant secondEnd) {
    return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
  }

  public record Window(Instant start, Instant end) {
  }

  public record TeacherEligibility(
      JsonNode qualifications,
      String position,
      boolean homeroom,
      LocalDate qualificationExpiry,
      int maxDailySessions,
      String preferredCampus
  ) {
    public TeacherEligibility(JsonNode qualifications, String position, boolean homeroom) {
      this(qualifications, position, homeroom, null, 4, null);
    }
  }
}
