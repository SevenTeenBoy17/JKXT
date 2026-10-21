package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.scheduling.solver.CpSatScheduleSolver;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.*;
import com.jkxt.scheduling.solver.SchedulePolicy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacySolverEligibilityTest {
  private static final LocalDate DAY = LocalDate.of(2027, 1, 12);
  private static final UUID ROOM = UUID.fromString("00000000-0000-0000-0000-000000009900");
  private static final Set<String> CHIEF = Set.of("INVIGILATOR", "CHIEF");
  private final CpSatScheduleSolver solver = new CpSatScheduleSolver();

  @Test void missingChiefCannotFillTheMandatoryMainRole() {
    var result = solve(List.of(teacher(1, Set.of("INVIGILATOR"), null, 4)), List.of(exam(8, 9, 1)));
    assertThat(result.status()).isEqualTo("infeasible");
    assertThat(result.assignments()).isEmpty();
  }

  @Test void chiefWithoutInvigilatorQualificationIsNotEligible() {
    assertThat(solve(List.of(teacher(1, Set.of("CHIEF"), null, 4)), List.of(exam(8, 9, 1))).status())
        .isEqualTo("infeasible");
  }

  @Test void onlyQualifiedTeacherCanTakeMainWhileAssistantRemainsEligible() {
    TeacherInput assistant = teacher(1, Set.of("INVIGILATOR"), null, 4);
    TeacherInput chief = teacher(2, CHIEF, null, 4);
    var result = solve(List.of(assistant, chief), List.of(exam(8, 9, 2)));
    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.assignments()).filteredOn(a -> a.role().equals("main"))
        .extracting(a -> a.teacherId()).containsExactly(chief.id());
  }

  @Test void qualificationExpiryIsInclusiveOnSchoolExamDate() {
    assertThat(solve(List.of(teacher(1, CHIEF, DAY.minusDays(1), 4)), List.of(exam(8, 9, 1))).status())
        .isEqualTo("infeasible");
    assertThat(solve(List.of(teacher(1, CHIEF, DAY, 4)), List.of(exam(8, 9, 1))).status())
        .isEqualTo("succeeded");
  }

  @Test void individualDailyLimitIsHardAndZeroDoesNotMeanUnlimited() {
    assertThat(solve(List.of(teacher(1, CHIEF, null, 1)), List.of(exam(8, 9, 1), exam(15, 16, 1))).status())
        .isEqualTo("infeasible");
    assertThat(solve(List.of(teacher(1, CHIEF, null, 0)), List.of(exam(8, 9, 1))).status())
        .isEqualTo("infeasible");
  }

  @Test void searchFindsEarlyBAndLateAInsteadOfGreedyFalseShortage() {
    TeacherInput a = teacher(1, CHIEF, null, 1);
    TeacherInput b = new TeacherInput(new UUID(0, 2), "B", "教师B", "G12", false, Set.of(), Set.of(),
        List.of(), List.of(window(8, 10)), CHIEF, null, 1, List.of());
    var result = solve(List.of(a, b), List.of(exam(8, 9, 1), exam(15, 16, 1)));
    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.assignments()).filteredOn(x -> x.start().equals(LocalTime.of(8, 0)))
        .extracting(x -> x.teacherId()).containsExactly(b.id());
    assertThat(result.assignments()).filteredOn(x -> x.start().equals(LocalTime.of(15, 0)))
        .extracting(x -> x.teacherId()).containsExactly(a.id());
  }

  @Test void publishedNonOverlappingDutyConsumesDailyCapacity() {
    TeacherInput teacher = teacher(1, CHIEF, null, 1).withEligibility(CHIEF, null, 1, List.of(window(8, 9)));
    assertThat(solve(List.of(teacher), List.of(exam(15, 16, 1))).status()).isEqualTo("infeasible");
  }

  @Test void publishedOverlapIsHardButTouchingBoundariesAreAllowed() {
    TeacherInput teacher = teacher(1, CHIEF, null, 4).withEligibility(CHIEF, null, 4, List.of(window(8, 9)));
    assertThat(solve(List.of(teacher), List.of(exam(8, 10, 1))).status()).isEqualTo("infeasible");
    assertThat(solve(List.of(teacher), List.of(exam(9, 10, 1))).status()).isEqualTo("succeeded");
  }

  private TeacherInput teacher(int n, Set<String> roles, LocalDate expiry, int limit) {
    return new TeacherInput(new UUID(0, n), "A" + n, "教师" + n, "G12", false, Set.of(), Set.of(),
        List.of(), List.of(), roles, expiry, limit, List.of());
  }
  private TeacherWindow window(int start, int end) { return new TeacherWindow(DAY.atTime(start, 0), DAY.atTime(end, 0)); }
  private ExamRoomInput exam(int start, int end, int count) {
    return new ExamRoomInput(new UUID(1, start), Set.of(), ROOM, "语文", "G10", DAY,
        LocalTime.of(start, 0), LocalTime.of(end, 0), 30, count);
  }
  private com.jkxt.scheduling.solver.ScheduleSolverResult solve(List<TeacherInput> teachers, List<ExamRoomInput> exams) {
    return solver.solve(new ScheduleFixture("constraints", "约束回归", 17, teachers,
        List.of(new RoomInput(ROOM, "R1", "考场一", 40)), exams), SchedulePolicy.defaults());
  }
}
