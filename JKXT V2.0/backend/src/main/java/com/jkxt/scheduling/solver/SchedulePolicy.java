package com.jkxt.scheduling.solver;

/**
 * Resolved scheduling policy fed into the solver (Epic 4). It toggles the avoidance constraints the
 * solver implements; the AI policy DSL ({@code same_grade_avoid} / {@code teacher_avoid_class} rules)
 * resolves into it, so an operator who relaxes a rule via natural language genuinely changes the solve.
 *
 * <p>班主任避监本班 ({@code avoidHomeroomClass}) is a genuine hard rule. 同年级回避 is a graded
 * preference ({@link SameGrade}): HARD excludes same-grade teachers outright (can make a single-grade
 * exam infeasible), SOFT only penalises them in the objective (recommended — never causes infeasibility),
 * NONE ignores it. The one-click default uses SOFT so the schedule "尽量回避同年级" without ever失败.
 */
public record SchedulePolicy(boolean avoidHomeroomClass, SameGrade sameGrade, boolean avoidSubjectClass) {

  /** Backward-compatible 2-arg view (任课老师不监本班 默认关闭). */
  public SchedulePolicy(boolean avoidHomeroomClass, SameGrade sameGrade) {
    this(avoidHomeroomClass, sameGrade, false);
  }

  /** 同年级回避的强度档位。 */
  public enum SameGrade {
    /** 不回避同年级。 */
    NONE,
    /** 软偏好：尽量回避（目标函数惩罚），但允许必要时违反，绝不致无解。 */
    SOFT,
    /** 硬约束：同年级教师一律不监本年级（可能使单年级考试无解）。 */
    HARD
  }

  /** Backward-compatible boolean view: true when same-grade avoidance is active at all (SOFT or HARD). */
  public boolean avoidSameGrade() {
    return sameGrade != SameGrade.NONE;
  }

  /** 全部回避按硬约束执行（保留给演示 fixture 与回归测试的最严格语义）。任课老师避本班为 DSL 显式 opt-in，
   * 不纳入此默认预设，以保持 policyFromDsl(空 DSL) == allOn() 的解析契约。 */
  public static SchedulePolicy allOn() {
    return new SchedulePolicy(true, SameGrade.HARD);
  }

  /**
   * Realistic K-12 default for the one-click flow: 班主任避监本班 stays ON (hard); 同年级回避 runs as a
   * SOFT preference — it never makes the common single-grade case infeasible, yet在多年级场景下会尽量
   * 把同年级监考让给跨年级教师。
   */
  public static SchedulePolicy defaults() {
    return new SchedulePolicy(true, SameGrade.SOFT);
  }
}
