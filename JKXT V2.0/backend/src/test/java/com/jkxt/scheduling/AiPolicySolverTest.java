package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.scheduling.solver.CpSatScheduleSolver;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import com.jkxt.scheduling.solver.SchedulePolicy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Epic 4 — the AI policy DSL actually drives the solver (not cosmetic). A fixture whose only
 * teachers share the exam's grade is infeasible while same-grade avoidance is ON, and becomes
 * feasible the moment the policy disables that rule. The DSL ({@code same_grade_avoid} enabled=false)
 * resolves into {@code SchedulePolicy(avoidSameGrade=false)} which is what this proves.
 */
class AiPolicySolverTest {

  private final CpSatScheduleSolver solver = new CpSatScheduleSolver();

  private ScheduleFixture sameGradeOnlyFixture() {
    UUID room = UUID.randomUUID();
    List<TeacherInput> teachers = List.of(
        new TeacherInput(UUID.randomUUID(), "T1", "师一", "高一", false, Set.of(), Set.of(), List.of(), List.of(), Set.of("INVIGILATOR", "CHIEF"), null),
        new TeacherInput(UUID.randomUUID(), "T2", "师二", "高一", false, Set.of(), Set.of(), List.of(), List.of(), Set.of("INVIGILATOR", "CHIEF"), null),
        new TeacherInput(UUID.randomUUID(), "T3", "师三", "高一", false, Set.of(), Set.of(), List.of(), List.of(), Set.of("INVIGILATOR", "CHIEF"), null)
    );
    List<RoomInput> rooms = List.of(new RoomInput(room, "R1", "考场一", 40));
    List<ExamRoomInput> examRooms = List.of(new ExamRoomInput(
        UUID.randomUUID(), Set.of(), room, "数学", "高一",
        LocalDate.of(2026, 6, 9), LocalTime.of(9, 0), LocalTime.of(10, 30), 30, 2));
    return new ScheduleFixture("ai-policy-test", "同年级回避开关验证", 42L, teachers, rooms, examRooms);
  }

  @Test
  void sameGradeAvoidanceOnMakesAllSameGradeTeachersIneligible() {
    ScheduleFixture fixture = sameGradeOnlyFixture();

    ScheduleSolverResult strict = solver.solve(fixture, SchedulePolicy.allOn());
    assertThat(strict.status()).isEqualTo("infeasible");
    assertThat(strict.assignments()).isEmpty();

    // The default solve() overload keeps both constraints on (backward compatible).
    assertThat(solver.solve(fixture).status()).isEqualTo("infeasible");
  }

  @Test
  void disablingSameGradeAvoidanceViaPolicyMakesItFeasible() {
    ScheduleFixture fixture = sameGradeOnlyFixture();

    ScheduleSolverResult relaxed = solver.solve(fixture, new SchedulePolicy(true, SchedulePolicy.SameGrade.NONE));

    assertThat(relaxed.status()).isEqualTo("succeeded");
    assertThat(relaxed.assignments()).hasSize(2); // 1 exam-room × 2 supervisors
  }

  @Test
  void subjectAvoidSoftPrefersTeacherNotTeachingTheClassSubject() {
    // 任课老师不监本班(subject_avoid)软偏好：教师任教的学科 ∋ 本场考试科目(scope=sameSubject) → 加惩罚。
    // 两名教师除"是否任教本场考试科目"外完全等价(同年级/同载荷/非班主任)，故求解器必选不任教该科者。
    UUID room = UUID.randomUUID();
    UUID subject = UUID.randomUUID();
    UUID classId = UUID.randomUUID();
    TeacherInput subjectTeacher = new TeacherInput(
        UUID.randomUUID(), "ST", "任课师", "高二", false, Set.of(), Set.of(subject), List.of(), List.of(), Set.of("INVIGILATOR", "CHIEF"), null);
    TeacherInput otherTeacher = new TeacherInput(
        UUID.randomUUID(), "OT", "他科师", "高二", false, Set.of(), Set.of(), List.of(), List.of(), Set.of("INVIGILATOR", "CHIEF"), null);
    List<RoomInput> rooms = List.of(new RoomInput(room, "R1", "考场一", 40));
    ExamRoomInput examRoom = new ExamRoomInput(
        UUID.randomUUID(), Set.of(classId), room, "数学", "高二",
        LocalDate.of(2026, 6, 9), LocalTime.of(9, 0), LocalTime.of(10, 30), 30, 1, Set.of(subject));
    ScheduleFixture fixture = new ScheduleFixture(
        "subject-avoid-test", "任课老师不监本班验证", 7L,
        List.of(subjectTeacher, otherTeacher), rooms, List.of(examRoom));

    ScheduleSolverResult result = solver.solve(
        fixture, new SchedulePolicy(false, SchedulePolicy.SameGrade.NONE, true));

    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.assignments()).hasSize(1);
    assertThat(result.assignments().get(0).teacherId()).isEqualTo(otherTeacher.id());
  }
}
