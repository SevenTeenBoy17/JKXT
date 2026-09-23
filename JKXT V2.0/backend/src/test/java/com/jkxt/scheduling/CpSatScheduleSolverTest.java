package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.scheduling.solver.CpSatScheduleSolver;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleFixtureFactory;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CpSatScheduleSolverTest {

  @Test
  void omittedQualificationsMustNotImplicitlyAuthorizeInvigilation() {
    var original = fixtureFactory.feasible();
    var unqualified = original.teachers().stream().map(t -> new TeacherInput(t.id(), t.teacherNo(),
        t.name(), t.grade(), t.homeroom(), t.homeroomClassIds(), t.subjectIds())).toList();
    var result = solver.solve(new ScheduleFixture("unqualified", "资格缺失", original.seed(),
        unqualified, original.rooms(), original.examRooms()));
    assertThat(result.status()).isEqualTo("infeasible");
    assertThat(result.assignments()).isEmpty();
  }

  private final ScheduleFixtureFactory fixtureFactory = new ScheduleFixtureFactory();
  private final CpSatScheduleSolver solver = new CpSatScheduleSolver();

  @Test void capturedFixtureCollectionsCannotBeChangedAfterConstruction() {
    var original=fixtureFactory.feasible();var teachers=new ArrayList<>(original.teachers());var rooms=new ArrayList<>(original.rooms());var exams=new ArrayList<>(original.examRooms());
    var snapshot=new ScheduleFixture("immutable","不可变输入",42,teachers,rooms,exams);teachers.clear();rooms.clear();exams.clear();
    assertThat(snapshot.teachers()).isEqualTo(original.teachers());assertThat(snapshot.rooms()).isEqualTo(original.rooms());assertThat(snapshot.examRooms()).isEqualTo(original.examRooms());
    org.assertj.core.api.Assertions.assertThatThrownBy(()->snapshot.teachers().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test void explicitQualificationsAndExpiryAreHardEligibilityIncludingChiefRole() {
    var template=fixtureFactory.feasible();var exam=template.examRooms().getFirst();
    var single=new ScheduleFixture("qualification", "资格边界", 42,
        List.of(credential("chief",Set.of("INVIGILATOR","CHIEF"),exam.date()),
            credential("assistant",Set.of("INVIGILATOR"),exam.date()),
            credential("chief-only",Set.of("CHIEF"),null),
            credential("expired",Set.of("INVIGILATOR","CHIEF"),exam.date().minusDays(1))),
        template.rooms(),List.of(exam));
    var result=solver.solve(single,com.jkxt.scheduling.solver.SchedulePolicy.defaults());
    assertThat(result.status()).isEqualTo("succeeded");assertThat(result.assignments()).hasSize(2);
    assertThat(result.assignments()).filteredOn(a->a.role().equals("main")).singleElement()
        .satisfies(a->assertThat(a.teacherId()).isEqualTo(single.teachers().getFirst().id()));
    assertThat(result.assignments()).extracting(a->a.teacherId()).doesNotContain(single.teachers().get(2).id(),single.teachers().get(3).id());
    var noChief=new ScheduleFixture("no-chief","缺少主监",42,List.of(credential("i1",Set.of("INVIGILATOR"),null),credential("i2",Set.of("INVIGILATOR"),null)),template.rooms(),List.of(exam));
    assertThat(solver.solve(noChief).status()).isEqualTo("infeasible");
  }
  private TeacherInput credential(String name,Set<String> qualifications,LocalDate expiry) {
    return new TeacherInput(UUID.randomUUID(),name,name,"OTHER",false,Set.of(),Set.of(),List.of(),List.of(),qualifications,expiry);
  }

  @Test void cancellationBeforeNativeWrapperCreationKeepsDeliveringUntilActualReturn() throws Exception {
    var scheduler=new java.util.concurrent.ScheduledThreadPoolExecutor(1);scheduler.setRemoveOnCancelPolicy(true);
    var started=new java.util.concurrent.atomic.AtomicBoolean();var deliveredAfterStart=new java.util.concurrent.CountDownLatch(1);
    try(var control=new CpSatScheduleSolver.SolveControl(System.nanoTime(),java.time.Duration.ofSeconds(30).toNanos(),scheduler)) {
      control.requestStop("DEMO_RESET");
      try(var registration=control.register(()->{if(started.get())deliveredAfterStart.countDown();})) {
        assertThat(control.stopDeliveries()).isEqualTo(1);started.set(true);
        assertThat(deliveredAfterStart.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(control.stopDeliveries()).isGreaterThan(1);
      }
      long deliveries=control.stopDeliveries();control.requestStop("DEMO_SHUTDOWN");assertThat(control.stopDeliveries()).isEqualTo(deliveries);
      assertThat(control.stopReason()).isEqualTo("DEMO_RESET");
      org.assertj.core.api.Assertions.assertThatThrownBy(control::check).isInstanceOf(CpSatScheduleSolver.SolveStopped.class);
    }finally{scheduler.shutdownNow();}
  }

  @Test void queuedTimeConsumesTheSingleMonotonicBudgetAndPreventsNativeEntry() {
    var scheduler=new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    try(var control=new CpSatScheduleSolver.SolveControl(System.nanoTime()-java.time.Duration.ofSeconds(31).toNanos(),java.time.Duration.ofSeconds(30).toNanos(),scheduler)) {
      org.assertj.core.api.Assertions.assertThatThrownBy(()->solver.solve(fixtureFactory.feasible(),com.jkxt.scheduling.solver.SchedulePolicy.defaults(),control))
          .isInstanceOf(CpSatScheduleSolver.SolveStopped.class).hasMessage("DEMO_SOLVE_TIMEOUT");
      assertThat(control.remainingNanos()).isZero();assertThat(control.stopDeliveries()).isZero();
    }finally{scheduler.shutdownNow();}
  }

  @Test void ordinaryUnknownRetriesUseTheShrinkingBudgetAndCancellationBetweenAttemptsPreventsRetry() {
    for(boolean stopBetweenAttempts:List.of(false,true)) {
      var scheduler=new java.util.concurrent.ScheduledThreadPoolExecutor(1);
      try(var control=new CpSatScheduleSolver.SolveControl(System.nanoTime(),java.time.Duration.ofSeconds(30).toNanos(),scheduler)) {
        var budgets=new ArrayList<Double>();
        var probe=new CpSatScheduleSolver(){
          @Override protected com.google.ortools.sat.CpSolverStatus runNative(com.google.ortools.sat.CpSolver nativeSolver,com.google.ortools.sat.CpModel model) {
            budgets.add(nativeSolver.getParameters().getMaxTimeInSeconds());
            if(budgets.size()==1){if(stopBetweenAttempts)control.requestStop("DEMO_RESET");return com.google.ortools.sat.CpSolverStatus.UNKNOWN;}
            return nativeSolver.solve(model);
          }
        };
        if(stopBetweenAttempts) {
          org.assertj.core.api.Assertions.assertThatThrownBy(()->probe.solve(fixtureFactory.feasible(),com.jkxt.scheduling.solver.SchedulePolicy.defaults(),control))
              .isInstanceOf(CpSatScheduleSolver.SolveStopped.class).hasMessage("DEMO_RESET");assertThat(budgets).hasSize(1);
        }else {
          assertThat(probe.solve(fixtureFactory.feasible(),com.jkxt.scheduling.solver.SchedulePolicy.defaults(),control).status()).isEqualTo("succeeded");
          assertThat(budgets).hasSize(2);assertThat(budgets.get(1)).isLessThan(budgets.getFirst());assertThat(budgets.getFirst()).isLessThan(30.0);
        }
      }finally{scheduler.shutdownNow();}
    }
  }

  @Test
  void feasibleFixtureGeneratesAssignmentsAndFiveFairnessItems() {
    ScheduleFixture fixture = fixtureFactory.feasible();
    ScheduleSolverResult result = solver.solve(fixture);

    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.assignments()).hasSize(
        fixture.examRooms().stream().mapToInt(ScheduleFixture.ExamRoomInput::supervisorsPerRoom).sum()
    );
    assertThat(result.conflictSet()).isEmpty();
    assertThat(result.fairness()).hasSize(5);

    Set<String> teacherTime = new HashSet<>();
    result.assignments().forEach(a ->
        assertThat(teacherTime.add(a.teacherId() + "|" + a.date() + "|" + a.start() + "|" + a.end()))
            .as("H1 same teacher cannot be assigned twice in the same slot")
            .isTrue()
    );
    fixture.examRooms().forEach(room -> {
      long main = result.assignments().stream()
          .filter(a -> a.examId().equals(room.examId()) && a.roomId().equals(room.roomId()) && "main".equals(a.role()))
          .count();
      long total = result.assignments().stream()
          .filter(a -> a.examId().equals(room.examId()) && a.roomId().equals(room.roomId()))
          .count();
      assertThat(main).as("H2 one main proctor").isEqualTo(1);
      assertThat(total).as("H3 required proctor count").isEqualTo(room.supervisorsPerRoom());
    });
  }

  @Test
  void unmeasuredFairnessItemsAreUnavailableForSuccessfulAndInfeasibleRuns() {
    for (ScheduleFixture fixture : List.of(fixtureFactory.feasible(), fixtureFactory.teacherShortage())) {
      ScheduleSolverResult result = solver.solve(fixture);

      assertThat(result.fairness()).hasSize(5);
      assertThat(result.fairness()).filteredOn(item -> item.id().equals("corridorShare"))
          .singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo("—");
            assertThat(item.status()).isEqualTo("unavailable");
            assertThat(item.detail()).isEqualTo("当前未采集楼道岗位数据，不计入公平性评估");
          });
      assertThat(result.fairness()).filteredOn(item -> item.id().equals("notesApplied"))
          .singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo("—");
            assertThat(item.status()).isEqualTo("unavailable");
            assertThat(item.detail()).isEqualTo("当前未统计特殊备注执行情况，不计入评估");
          });
      assertThat(result.fairness()).filteredOn(item -> !item.status().equals("unavailable"))
          .extracting(item -> item.id()).containsExactly("avgLoad", "maxDiff", "homeroomShare");
    }
  }

  @Test
  void teacherShortageFixtureReturnsReadableConflictSet() {
    ScheduleSolverResult result = solver.solve(fixtureFactory.teacherShortage());

    assertThat(result.status()).isEqualTo("infeasible");
    assertThat(result.errorCode()).isEqualTo("SCHEDULE_INFEASIBLE");
    assertThat(result.assignments()).isEmpty();
    assertThat(result.conflictSet()).hasSize(1);
    assertThat(result.conflictSet().get(0).constraint()).contains("H2");
    assertThat(result.conflictSet().get(0).message()).contains("可用教师不足");
  }

  @Test
  void capacityConflictFixtureReturnsH7ConflictSet() {
    ScheduleSolverResult result = solver.solve(fixtureFactory.capacityConflict());

    assertThat(result.status()).isEqualTo("infeasible");
    assertThat(result.errorCode()).isEqualTo("SCHEDULE_INFEASIBLE");
    assertThat(result.conflictSet()).hasSize(1);
    assertThat(result.conflictSet().get(0).constraint()).isEqualTo("H7");
    assertThat(result.conflictSet().get(0).message()).contains("容量不足");
  }

  @Test
  void homeroomHeavyRosterNoLongerSpuriouslyInfeasible() {
    // 迭代10 P1 回归：全体教师均为班主任、总监考需求 > 2×班主任数 时，旧的"班主任≤2场"硬上限会把本可行的
    // 排班误判 INFEASIBLE(仅泛化的"无可行解"文案)。改为软惩罚后必须能出解，且允许个别班主任超过 2 场。
    UUID roomId = UUID.fromString("00000000-0000-0000-0000-0000000a0001");
    UUID g7HomeroomClass = UUID.fromString("00000000-0000-0000-0000-0000000a0007"); // 班主任本班(G7)
    UUID examClass = UUID.fromString("00000000-0000-0000-0000-0000000a0010");        // 考场班级(G10)，与本班无交集

    List<TeacherInput> teachers = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      teachers.add(new TeacherInput(
          UUID.fromString("00000000-0000-0000-0000-0000000b00%02d".formatted(i)),
          "HR-%03d".formatted(i), "班主任%03d".formatted(i),
          "G7",                 // 与考场年级 G10 不同 → 不触发同年级软惩罚
          true,                 // 均为班主任 → 命中(旧)硬上限
          Set.of(g7HomeroomClass), // 本班 G7，与考场班级(G10)无交集 → 全部可监考
          Set.of(), List.of(), List.of(), Set.of("INVIGILATOR", "CHIEF"), null)); // 无任教学科
    }
    List<RoomInput> rooms = List.of(new RoomInput(roomId, "HR-R", "回归考场", 60));
    List<ExamRoomInput> examRooms = new ArrayList<>();
    LocalDate base = LocalDate.of(2026, 6, 8);
    for (int i = 0; i < 5; i++) {   // 5 场 × 2 监考 = 10 场次；4 班主任 × 旧上限 2 = 8 < 10 → 旧必判无解
      examRooms.add(new ExamRoomInput(
          UUID.fromString("00000000-0000-0000-0000-0000000c00%02d".formatted(i + 1)),
          Set.of(examClass), roomId, "科目" + i, "G10",
          base.plusDays(i),           // 5 个不同日期 → 无时段重叠冲突
          LocalTime.of(9, 0), LocalTime.of(10, 30),
          40, 2, Set.of()));
    }
    ScheduleFixture fixture = new ScheduleFixture(
        "homeroom-heavy", "全班主任花名册回归", 20260610L, teachers, rooms, examRooms);

    ScheduleSolverResult result = solver.solve(fixture);

    assertThat(result.status()).as("班主任场次改软上限后应可行").isEqualTo("succeeded");
    assertThat(result.assignments()).hasSize(10);
    long maxPerTeacher = result.assignments().stream()
        .collect(Collectors.groupingBy(a -> a.teacherId(), Collectors.counting()))
        .values().stream().mapToLong(Long::longValue).max().orElse(0);
    assertThat(maxPerTeacher).as("软上限允许个别班主任超过 2 场以消除虚假无解").isGreaterThan(2);
  }

  @Test
  void benchmark200ExamsCompletesWithinPerformanceBudget() {
    ScheduleSolverResult result = solver.solve(fixtureFactory.benchmark200());

    System.out.printf(
        "P6 benchmark-200 status=%s solverStatus=%s solveMillis=%d assignments=%d%n",
        result.status(),
        result.solverStatus(),
        result.solveMillis(),
        result.assignments().size()
    );
    assertThat(result.status()).isEqualTo("succeeded");
    assertThat(result.assignments()).hasSize(400);
    assertThat(result.solveMillis()).isLessThan(45_000);
    assertThat(result.stats()).containsEntry("exams", 200);
  }
}
