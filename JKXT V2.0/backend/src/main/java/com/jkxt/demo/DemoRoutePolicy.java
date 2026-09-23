package com.jkxt.demo;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Explicit opt-in contract. New MVC mappings never widen DEMO access automatically. */
@Component
public final class DemoRoutePolicy {
  public static final List<String> PERMISSIONS=List.of("tenant:read","masterdata:manage","exam:manage","schedule:manage");
  public static final Set<String> IMPORT_KINDS=Set.of("teachers","classes","classrooms","subjects","exams");
  private static final String ID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  private record Rule(Set<String> methods,Pattern path) {}
  private static Rule rule(String methods,String path){return new Rule(Set.of(methods.split(",")),Pattern.compile("/api/v1"+path));}
  private static final List<Rule> RULES=List.of(
      rule("GET,POST","/(teachers|classes|classrooms|subjects)"),
      rule("GET,PUT,DELETE","/(teachers|classes|classrooms|subjects)/"+ID),
      rule("GET","/import/templates/(teachers|classes|classrooms|subjects|exams)"),
      rule("GET","/import/(teachers|classes|classrooms|subjects|exams)/template"),
      rule("POST","/import/validate"),
      rule("POST","/import/(teachers|classes|classrooms|subjects|exams)(/validate|/commit)?"),
      rule("GET,POST","/exam-tasks"),rule("GET,DELETE","/exam-tasks/"+ID),
      rule("POST","/exam-tasks/"+ID+"/(uploads|imports/validate|schedule-jobs)"),
      rule("GET","/exam-tasks/"+ID+"/(assignments|export)"),
      rule("GET","/schedule-jobs/"+ID+"(/events)?"),
      rule("POST","/tasks/"+ID+"/schedule/(check-conflicts|undo|redo)"),
      rule("PATCH","/tasks/"+ID+"/schedule"),
      rule("POST","/demo/workspace/reset"),rule("POST","/demo/tasks/"+ID+"/simulate-publish"),
      rule("GET","/demo/overview"),rule("GET","/auth/me"),rule("POST","/auth/logout"));
  public boolean allows(String method,String normalizedPath) {
    return method!=null && safePath(normalizedPath) && RULES.stream().anyMatch(r->r.methods().contains(method)&&r.path().matcher(normalizedPath).matches());
  }
  public static boolean safePath(String path) {
    return path!=null && !path.contains("%") && !path.contains(";") && !path.contains("\\")
        && !path.contains("//") && !path.contains(".") && !path.contains("?") && !path.contains("#");
  }
  public boolean foregroundEligible(String method,String path) {
    return allows(method,path) && !path.startsWith("/api/v1/auth/") && !path.startsWith("/api/v1/schedule-jobs/")
        && !path.equals("/api/v1/demo/workspace/reset");
  }
}
