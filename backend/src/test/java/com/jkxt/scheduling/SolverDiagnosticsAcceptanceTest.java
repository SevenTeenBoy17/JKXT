package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.scheduling.solver.CpSatScheduleSolver;
import com.jkxt.scheduling.solver.SchedulePolicy;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import com.jkxt.scheduling.solver.ScheduleSolverResult.ConflictItem;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * K12 测试诊断发现的**求解器可诊断性**缺陷回归。
 *
 * <p>背景：一次 96 行考试全部「人数 &gt; 考场容量」的排考返回 infeasible，但
 * ①conflictSet 被无条件截断为 1 条 —— 用户只看到「A101 容量不足」，按提示修好 A101 再排仍然无解，
 *   会误判成"修了没用/系统有 bug"；
 * ②文案说"排考模型无可行解"，让人以为 CP-SAT 已穷尽搜索证明无解，实际 {@code solverStatus=PRECHECK}、
 *   {@code solveMillis=0}，求解器根本没运行，真正该做的只是修数据。
 */
class SolverDiagnosticsAcceptanceTest {

  private final CpSatScheduleSolver solver = new CpSatScheduleSolver();

  @Test
  void batchCapacityFailuresAreAggregatedNotTruncatedToOne() {
    // 6 个考场全部容量不足（需求 50 > 容量 10）
    ScheduleSolverResult result = solver.solve(overCapacityFixture(6), SchedulePolicy.defaults());

    assertThat(result.status()).isEqualTo("infeasible");
    assertThat(result.solverStatus()).isEqualTo("PRECHECK");

    List<ConflictItem> conflicts = result.conflictSet();
    assertThat(conflicts).isNotEmpty();
    ConflictItem head = conflicts.get(0);
    // 缺陷点：此前恒为 1 条且不含任何"还有多少同类问题"的信息。
    assertThat(head.refs()).containsKey("sameKindCount");
    assertThat(((Number) head.refs().get("sameKindCount")).intValue()).isEqualTo(6);
    assertThat(head.message()).contains("另有").contains("同类问题");
  }

  @Test
  void singleConflictIsNotDecoratedWithSameKindCount() {
    // 只有 1 处问题时不应画蛇添足地加"另有 N 处"。
    ScheduleSolverResult result = solver.solve(overCapacityFixture(1), SchedulePolicy.defaults());

    ConflictItem head = result.conflictSet().get(0);
    assertThat(head.message()).doesNotContain("另有");
    assertThat(head.refs()).doesNotContainKey("sameKindCount");
  }

  @Test
  void precheckFailureIsNotDescribedAsSolverProvenInfeasible() {
    ScheduleSolverResult result = solver.solve(overCapacityFixture(3), SchedulePolicy.defaults());

    assertThat(result.solverStatus()).isEqualTo("PRECHECK");
    assertThat(result.solveMillis()).isLessThan(1000L);   // 求解器未运行
    // 缺陷点：此前与真正的 CP-SAT INFEASIBLE 共用"排考模型无可行解"，误导用户去调约束。
    assertThat(result.errorMessage()).contains("预检未通过").contains("未进入求解");
    assertThat(result.errorMessage()).doesNotContain("模型无可行解");
  }

  // ---------- fixture ----------

  /** n 个考场，每场需求 50 人但容量仅 10 人 —— 必然触发 H7 容量预检。 */
  private static ScheduleFixture overCapacityFixture(int n) {
    List<RoomInput> rooms = new java.util.ArrayList<>();
    List<ExamRoomInput> examRooms = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      UUID roomId = UUID.randomUUID();
      rooms.add(new RoomInput(roomId, "R%03d".formatted(i + 1), "考场%d".formatted(i + 1), 10));
      examRooms.add(new ExamRoomInput(UUID.randomUUID(), Set.of(), roomId, "语文", "三年级",
          LocalDate.of(2026, 6, 25), LocalTime.of(8, 30), LocalTime.of(10, 0), 50, 2, Set.of()));
    }
    List<TeacherInput> teachers = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      teachers.add(new TeacherInput(UUID.randomUUID(), "T%03d".formatted(i + 1), "教师%d".formatted(i + 1),
          "三年级", false, Set.of(), Set.of()).withEligibility(Set.of("INVIGILATOR", "CHIEF"), null, 4, List.of()));
    }
    return new ScheduleFixture("test-overcap", "容量不足用例", 42L, teachers, rooms, examRooms);
  }
}
