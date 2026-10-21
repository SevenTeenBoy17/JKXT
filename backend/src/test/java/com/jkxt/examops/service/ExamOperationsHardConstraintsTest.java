package com.jkxt.examops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExamOperationsHardConstraintsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ExamOperationsHardConstraints constraints = new ExamOperationsHardConstraints();

  @Test
  void interpretsSchoolLocalLeaveDutyAndExplicitLeaveWindows() throws Exception {
    Instant start = Instant.parse("2027-01-11T00:00:00Z");
    Instant end = Instant.parse("2027-01-11T02:00:00Z");
    assertThat(constraints.teacherAvailable(
        objectMapper.readTree("[]"),
        objectMapper.readTree("[{\"start\":\"2027-01-11T08:00:00\",\"end\":\"2027-01-11T10:00:00\"}]"),
        List.of(), start, end)).isTrue();
    assertThat(constraints.teacherAvailable(
        objectMapper.readTree("[{\"start\":\"2027-01-11T09:00:00\",\"end\":\"2027-01-11T11:00:00\"}]"),
        objectMapper.readTree("[]"), List.of(), start, end)).isFalse();
    assertThat(constraints.teacherAvailable(
        objectMapper.readTree("[]"),
        objectMapper.readTree("[{\"start\":\"2027-01-11T09:00:00\",\"end\":\"2027-01-11T11:00:00\"}]"),
        List.of(), start, end)).isFalse();
    assertThat(constraints.teacherAvailable(
        objectMapper.readTree("[]"), objectMapper.readTree("[]"),
        List.of(new ExamOperationsHardConstraints.Window(
            Instant.parse("2027-01-11T01:00:00Z"), Instant.parse("2027-01-11T03:00:00Z"))),
        start, end)).isFalse();
  }

  @Test
  void requiresEveryRoomFeature() throws Exception {
    assertThat(constraints.roomCompatible(
        objectMapper.readTree("[\"ACCESSIBLE\",\"PROJECTOR\"]"),
        objectMapper.readTree("[\"ACCESSIBLE\"]"))).isTrue();
    assertThat(constraints.roomCompatible(
        objectMapper.readTree("[\"PROJECTOR\"]"),
        objectMapper.readTree("[\"ACCESSIBLE\"]"))).isFalse();
    assertThat(constraints.missingFeatures(
        objectMapper.readTree("[\"PROJECTOR\"]"),
        objectMapper.readTree("[\"ACCESSIBLE\",\"PROJECTOR\"]")))
        .containsExactly("ACCESSIBLE");
  }

  @Test
  void chiefRoleAndPersistedQualificationAreEnforced() throws Exception {
    var chiefRequirement = objectMapper.readTree("{\"requiredQualifications\":[\"CHIEF\"]}");
    var qualified = new ExamOperationsHardConstraints.TeacherEligibility(
        objectMapper.readTree("[\"INVIGILATOR\",\"CHIEF\"]"), "Senior Teacher", true);
    var ordinary = new ExamOperationsHardConstraints.TeacherEligibility(
        objectMapper.readTree("[\"INVIGILATOR\"]"), "Teacher", false);
    var chiefOnly = new ExamOperationsHardConstraints.TeacherEligibility(
        objectMapper.readTree("[\"CHIEF\"]"), "Teacher", false);

    assertThat(constraints.teacherEligible("CHIEF", chiefRequirement, qualified)).isTrue();
    assertThat(constraints.teacherEligible("CHIEF", objectMapper.readTree("{}"), ordinary)).isFalse();
    assertThat(constraints.teacherEligible("CHIEF", chiefRequirement, chiefOnly)).isFalse();
    assertThat(constraints.teacherEligible("INVIGILATOR",
        objectMapper.readTree("{\"requiredQualifications\":[\"INVIGILATOR\"]}"), ordinary)).isTrue();
    assertThat(constraints.teacherEligible("INVIGILATOR",
        objectMapper.readTree("{\"requiredPositions\":[\"SENIOR TEACHER\"]}"), ordinary)).isFalse();
    assertThat(constraints.teacherEligible("INVIGILATOR",
        objectMapper.readTree("{\"requiredQualifications\":\"INVIGILATOR\"}"), ordinary)).isFalse();
    assertThat(constraints.defaultDutyQualification("CHIEF").toString())
        .isEqualTo("{\"requiredQualifications\":[\"INVIGILATOR\",\"CHIEF\"]}");
    assertThat(constraints.teacherEligible("CHIEF",
        objectMapper.readTree("{\"requiredQualificatons\":[\"CHIEF\"]}"), qualified)).isFalse();
    assertThat(constraints.teacherEligible("CHIEF",
        objectMapper.readTree("{\"requiredQualifications\":[\"PROCTOR\"]}"), qualified)).isFalse();
    assertThat(constraints.teacherEligible("CHIEF", chiefRequirement,
        new ExamOperationsHardConstraints.TeacherEligibility(
            objectMapper.readTree("[\"INVIGILATOR\",\"chief\"]"), "Teacher", false))).isFalse();
    assertThat(constraints.teacherEligible("LEAD", objectMapper.readTree("{}"), qualified)).isFalse();

    var expiringChief = new ExamOperationsHardConstraints.TeacherEligibility(
        objectMapper.readTree("[\"INVIGILATOR\",\"CHIEF\"]"), "Teacher", false,
        LocalDate.of(2028, 6, 2), 2, "north");
    assertThat(constraints.teacherEligible("CHIEF", chiefRequirement, expiringChief,
        LocalDate.of(2028, 6, 2))).isTrue();
    assertThat(constraints.teacherEligible("CHIEF", chiefRequirement, expiringChief,
        LocalDate.of(2028, 6, 3))).isFalse();
    assertThat(constraints.campusPreferencePenalty(expiringChief, "CAMPUS_NORTH")).isZero();
    assertThat(constraints.campusPreferencePenalty(expiringChief, "CAMPUS_SOUTH")).isEqualTo(2);
    assertThat(constraints.campusCode(objectMapper.readTree("[\"PROJECTOR\",\"CAMPUS_NORTH\"]")))
        .isEqualTo("CAMPUS_NORTH");
  }
}
