package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class DemoRoutePolicyTest {
  static final String ID="11111111-1111-4111-8111-111111111111";
  /** This independent table is the approved public contract, never derived from production rules. */
  static Map<String,Set<String>> approved() {
    Map<String,Set<String>> routes=new LinkedHashMap<>();
    for(String name:List.of("teachers","classes","classrooms","subjects")) {
      routes.put("/api/v1/"+name,Set.of("GET","POST"));
      routes.put("/api/v1/"+name+"/"+ID,Set.of("GET","PUT","DELETE"));
    }
    for(String kind:List.of("teachers","classes","classrooms","subjects","exams")) {
      routes.put("/api/v1/import/templates/"+kind,Set.of("GET"));
      routes.put("/api/v1/import/"+kind+"/template",Set.of("GET"));
      routes.put("/api/v1/import/"+kind,Set.of("POST"));
      routes.put("/api/v1/import/"+kind+"/validate",Set.of("POST"));
      routes.put("/api/v1/import/"+kind+"/commit",Set.of("POST"));
    }
    routes.put("/api/v1/import/validate",Set.of("POST"));
    routes.put("/api/v1/exam-tasks",Set.of("GET","POST"));
    routes.put("/api/v1/exam-tasks/"+ID,Set.of("GET","DELETE"));
    for(String suffix:List.of("uploads","imports/validate","schedule-jobs"))routes.put("/api/v1/exam-tasks/"+ID+"/"+suffix,Set.of("POST"));
    for(String suffix:List.of("assignments","export"))routes.put("/api/v1/exam-tasks/"+ID+"/"+suffix,Set.of("GET"));
    routes.put("/api/v1/schedule-jobs/"+ID,Set.of("GET"));
    routes.put("/api/v1/schedule-jobs/"+ID+"/events",Set.of("GET"));
    for(String suffix:List.of("check-conflicts","undo","redo"))routes.put("/api/v1/tasks/"+ID+"/schedule/"+suffix,Set.of("POST"));
    routes.put("/api/v1/tasks/"+ID+"/schedule",Set.of("PATCH"));
    routes.put("/api/v1/demo/workspace/reset",Set.of("POST"));
    routes.put("/api/v1/demo/tasks/"+ID+"/simulate-publish",Set.of("POST"));
    routes.put("/api/v1/demo/overview",Set.of("GET"));
    routes.put("/api/v1/auth/me",Set.of("GET"));
    routes.put("/api/v1/auth/logout",Set.of("POST"));
    return routes;
  }
  static Stream<Arguments> routes(){return approved().entrySet().stream().flatMap(e->Stream.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE").map(m->Arguments.of(m,e.getKey(),e.getValue().contains(m))));}
  @ParameterizedTest @MethodSource("routes") void exactMethodsOnly(String method,String path,boolean expected){assertThat(new DemoRoutePolicy().allows(method,path)).isEqualTo(expected);}
  @ParameterizedTest @ValueSource(strings={"/api/v1/tasks/123/publish","/api/v1/nl-policy/parse","/api/v1/admin/accounts","/api/v1/future-route","/api/v1/auth/me/password","/api/v1/demo/visitor","/api/v1/demo/sessions","/api/v1/import/users","/api/v1/teachers/123","/api/v1/teachers/../subjects","/api/v1/teachers%2F","/api/v1/teachers;foo=1","/api/v1//teachers","/api/v1/teachers/","/api/v1/teachers?x=y","/api/v1/%74eachers","/api/v1/teachers%252f","/api/v1/teachers\\x"})
  void unknownMalformedAndEncodedPathsAreDenied(String path){for(String method:List.of("GET","POST","PUT","DELETE"))assertThat(new DemoRoutePolicy().allows(method,path)).as(method+" "+path).isFalse();}
  @Test void permissionAndActivityContractsAreNarrow(){
    var p=new DemoRoutePolicy();
    assertThat(DemoRoutePolicy.PERMISSIONS).containsExactly("tenant:read","masterdata:manage","exam:manage","schedule:manage");
    for(String path:List.of("/api/v1/auth/me","/api/v1/auth/logout","/api/v1/demo/workspace/reset","/api/v1/demo/visitor","/api/v1/demo/sessions","/api/v1/schedule-jobs/"+ID,"/api/v1/schedule-jobs/"+ID+"/events"))
      for(String method:List.of("GET","POST"))assertThat(p.foregroundEligible(method,path)).isFalse();
    assertThat(p.foregroundEligible("GET","/api/v1/teachers")).isTrue();
    assertThat(p.foregroundEligible("POST","/api/v1/tasks/"+ID+"/publish")).isFalse();
  }
}
