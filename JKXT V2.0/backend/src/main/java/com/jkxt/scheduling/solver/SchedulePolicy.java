package com.jkxt.scheduling.solver;

/**
 * Resolved scheduling policy fed into the solver (Epic 4). Today it toggles the two hard avoidance
 * constraints the solver actually implements; the AI policy DSL ({@code same_grade_avoid} /
 * {@code teacher_avoid_class} rules) resolves into these flags, so an operator who relaxes a rule
 * via natural language genuinely changes the solve. Defaults keep both constraints ON, matching the
 * pre-Epic-4 behaviour.
 */
public record SchedulePolicy(boolean avoidHomeroomClass, boolean avoidSameGrade) {

  public static SchedulePolicy allOn() {
    return new SchedulePolicy(true, true);
  }
}
