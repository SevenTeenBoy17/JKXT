package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.scheduling.api.ScheduleEditDtos.ConflictCheckRequest;
import com.jkxt.scheduling.api.ScheduleEditDtos.SchedulePatchRequest;
import com.jkxt.scheduling.service.ScheduleEditService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** History must revalidate CURRENT master data and published reservations, not trust old snapshots. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LegacyRevisionEligibilityIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired ScheduleEditService edits;
  @Autowired EntityManager entityManager;
  private static final LocalDate DAY = LocalDate.of(2027, 1, 12);
  private UUID org, actor, task, firstTeacher, secondTeacher, room, run, assignment;

  @BeforeEach void before() {
    org=UUID.randomUUID(); actor=UUID.randomUUID(); task=UUID.randomUUID();
    firstTeacher=UUID.randomUUID(); secondTeacher=UUID.randomUUID(); room=UUID.randomUUID();
    run=UUID.randomUUID(); assignment=UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?,'Revision eligibility test',?)",org,"lr-"+org);
    jdbc.update("insert into app_user(id,org_id,username,display_name,password_hash,role,must_change_password) values (?,?,'tester','Synthetic tester','test-only-unused','ADMIN',false)",actor,org);
    for (UUID teacher : new UUID[]{firstTeacher,secondTeacher}) {
      jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender,qualifications,max_daily_sessions) values (?,?,?,'Synthetic qualified teacher','unknown','[\"INVIGILATOR\",\"CHIEF\"]'::jsonb,1)",teacher,org,teacher.toString().substring(0,24));
    }
    jdbc.update("insert into classroom(id,org_id,code,name,capacity,exam_capacity) values (?,?,'R1','Synthetic room',40,40)",room,org);
    jdbc.update("insert into exam_task(id,org_id,task_no,name,status) values (?,?,?,'Revision candidate','scheduled')",task,org,"T-"+task);
    UUID exam=exam(task,9);
    jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status,finished_at) values (?,?,?,'succeeded',now())",run,org,task);
    insertAssignment(assignment,run,exam,firstTeacher,9);
    TenantContext.set(org);
  }

  @AfterEach void after() { TenantContext.clear(); }

  @ParameterizedTest(name="{0} must reject current {1} and keep persisted assignment/cursor unchanged")
  @CsvSource({
      "UNDO,CHIEF,H2_CHIEF_QUALIFICATION", "REDO,CHIEF,H2_CHIEF_QUALIFICATION",
      "UNDO,INVIGILATOR,H2_INVIGILATOR_QUALIFICATION", "REDO,INVIGILATOR,H2_INVIGILATOR_QUALIFICATION",
      "UNDO,EXPIRY,H2_QUALIFICATION_EXPIRED", "REDO,EXPIRY,H2_QUALIFICATION_EXPIRED",
      "UNDO,DAILY,H2_MAX_DAILY_SESSIONS", "REDO,DAILY,H2_MAX_DAILY_SESSIONS",
      "UNDO,LEAVE,H2_TEACHER_LEAVE_WINDOW", "REDO,LEAVE,H2_TEACHER_LEAVE_WINDOW",
      "UNDO,PUBLISHED_DAILY,H2_MAX_DAILY_SESSIONS", "REDO,PUBLISHED_DAILY,H2_MAX_DAILY_SESSIONS",
      "UNDO,PUBLISHED_OVERLAP,H1_TEACHER_TIME_OVERLAP", "REDO,PUBLISHED_OVERLAP,H1_TEACHER_TIME_OVERLAP"
  })
  void historyCannotBypassChangedEligibility(String action,String condition,String code) {
    edits.patch(task,new SchedulePatchRequest(assignment,secondTeacher,0,"Synthetic history setup"));
    if ("REDO".equals(action)) edits.undo(task);
    entityManager.flush(); entityManager.clear();
    UUID destination="UNDO".equals(action)?firstTeacher:secondTeacher;
    changeCurrentEligibility(destination,condition);
    entityManager.clear();
    var before=jdbc.queryForMap("select teacher_id,source,conflict::text from schedule_assignment where id=?",assignment);
    var beforeRun=jdbc.queryForMap("select revision,revision_cursor,stats::text from schedule_run where id=?",run);
    var beforeRevision=jdbc.queryForMap("select status,before_state::text,after_state::text from schedule_revision where schedule_run_id=?",run);
    var conflict=edits.checkConflicts(task,new ConflictCheckRequest(assignment,destination));
    assertThat(conflict.allowed()).isFalse();
    assertThat(conflict.conflicts()).extracting(c->c.code()).contains(code);

    assertThatThrownBy(()->{if("UNDO".equals(action)) edits.undo(task);else edits.redo(task);})
        .isInstanceOfSatisfying(ResponseStatusException.class,ex->{
          assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(ex.getReason()).isEqualTo("SCHEDULE_CONFLICT");
        });
    entityManager.flush(); entityManager.clear();
    assertThat(jdbc.queryForMap("select teacher_id,source,conflict::text from schedule_assignment where id=?",assignment)).isEqualTo(before);
    assertThat(jdbc.queryForMap("select revision,revision_cursor,stats::text from schedule_run where id=?",run)).isEqualTo(beforeRun);
    assertThat(jdbc.queryForMap("select status,before_state::text,after_state::text from schedule_revision where schedule_run_id=?",run)).isEqualTo(beforeRevision);
    assertThat(jdbc.queryForObject("select revision_cursor from schedule_run where id=?",Integer.class,run))
        .isEqualTo("UNDO".equals(action)?1:0);
    assertThat(jdbc.queryForObject("select teacher_id from schedule_assignment where id=?",UUID.class,assignment))
        .isEqualTo("UNDO".equals(action)?secondTeacher:firstTeacher);
  }

  @Test void validHistoryStillRestoresBothAssignmentAndRevisionCursor() {
    edits.patch(task,new SchedulePatchRequest(assignment,secondTeacher,0,"Synthetic valid change"));
    assertThat(edits.undo(task).revisionCursor()).isZero();
    entityManager.flush(); entityManager.clear();
    assertThat(jdbc.queryForObject("select teacher_id from schedule_assignment where id=?",UUID.class,assignment)).isEqualTo(firstTeacher);
    assertThat(edits.redo(task).revisionCursor()).isEqualTo(1);
    entityManager.flush(); entityManager.clear();
    assertThat(jdbc.queryForObject("select teacher_id from schedule_assignment where id=?",UUID.class,assignment)).isEqualTo(secondTeacher);
    assertThat(jdbc.queryForObject("select status from schedule_revision where schedule_run_id=?",String.class,run)).isEqualTo("applied");
  }

  private void changeCurrentEligibility(UUID teacher,String condition) {
    switch(condition) {
      case "CHIEF" -> jdbc.update("update teacher set qualifications='[\"INVIGILATOR\"]'::jsonb where id=?",teacher);
      case "INVIGILATOR" -> jdbc.update("update teacher set qualifications='[]'::jsonb where id=?",teacher);
      case "EXPIRY" -> jdbc.update("update teacher set qualification_expiry=? where id=?",DAY.minusDays(1),teacher);
      case "DAILY" -> insertAssignment(UUID.randomUUID(),run,exam(task,15),teacher,15);
      case "LEAVE" -> jdbc.update("insert into exam_plan_leave_request(org_id,teacher_id,starts_at,ends_at,reason,status,requested_by,decided_by,decided_at) values (?,?,'2027-01-12T01:00:00Z','2027-01-12T02:00:00Z','Synthetic approved leave','APPROVED',?,?,now())",org,teacher,actor,actor);
      case "PUBLISHED_DAILY", "PUBLISHED_OVERLAP" -> {
        int hour="PUBLISHED_DAILY".equals(condition)?8:9;
        UUID published=UUID.randomUUID(),publishedRun=UUID.randomUUID();
        jdbc.update("insert into exam_task(id,org_id,task_no,name,status,published_at) values (?,?,?,'Synthetic published reservation','published',now())",published,org,"T-"+published);
        UUID exam=exam(published,hour);
        jdbc.update("insert into schedule_run(id,org_id,exam_task_id,status,finished_at) values (?,?,?,'succeeded',now())",publishedRun,org,published);
        insertAssignment(UUID.randomUUID(),publishedRun,exam,teacher,hour);
      }
      default -> throw new IllegalArgumentException("Unknown test condition");
    }
  }
  private UUID exam(UUID taskId,int hour) {
    UUID exam=UUID.randomUUID();
    jdbc.update("insert into exam(id,org_id,exam_task_id,subject_name,exam_date,start_time,end_time) values (?,?,?,'Synthetic exam',?,?,?)",exam,org,taskId,DAY,LocalTime.of(hour,0),LocalTime.of(hour+1,0));
    jdbc.update("insert into exam_room(org_id,exam_id,classroom_id) values (?,?,?)",org,exam,room);
    return exam;
  }
  private void insertAssignment(UUID id,UUID runId,UUID exam,UUID teacher,int hour) {
    jdbc.update("insert into schedule_assignment(id,org_id,schedule_run_id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time) values (?,?,?,?,?,?,'main',?,?,?)",id,org,runId,exam,room,teacher,DAY,LocalTime.of(hour,0),LocalTime.of(hour+1,0));
  }
}
