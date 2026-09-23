package com.jkxt.demo;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

/** Generated educational samples only; no real tenant rows or names are read. */
@Service
public class DemoSeedService {
  private final JdbcTemplate jdbc;private final DemoWorkspaceRepository repository;
  public DemoSeedService(JdbcTemplate jdbc,DemoWorkspaceRepository repository){this.jdbc=jdbc;this.repository=repository;}
  UUID allocateIdentity(UUID org) {
    UUID user=UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug,settings) values(?,'云杉演示学校',?,'{\"synthetic\":true}')",org,"demo-"+org);
    jdbc.update("insert into app_user(id,org_id,username,display_name,password_hash,role,onboarded,must_change_password) values(?,?,?,'演示访客',?,'DEMO',true,false)",
        user,org,"demo-"+user,"!NO-CREDENTIAL-LOGIN-"+UUID.randomUUID());return user;
  }
  @Transactional public UUID initialize(UUID orgId,int teacherCount) {
    repository.requireDemo();
    if(teacherCount!=120)throw DemoWorkspaceRepository.error(HttpStatus.TOO_MANY_REQUESTS,"DEMO_QUOTA_EXCEEDED","演示初始化需使用120名合成教师");
    UUID user=jdbc.queryForObject("select g.user_id from demo_generation g join demo_workspace w on w.id=g.workspace_id where g.org_id=? and w.state in ('RESETTING','FAILED')",UUID.class,orgId);
    if(Boolean.TRUE.equals(jdbc.queryForObject("select settings @> '{\"demoSeedVersion\":1}'::jsonb from organization where id=?",Boolean.class,orgId)))return user;
    UUID term=id(orgId,"term"),task=id(orgId,"task");
    LocalDate date=LocalDate.ofInstant(repository.now(),java.time.ZoneOffset.UTC).plusDays(7);
    jdbc.update("insert into academic_term(id,org_id,year_label,term,name,start_date,end_date,is_current) values(?,?,?,'S1','演示学期',?,?,true)",term,orgId,Integer.toString(date.getYear()),date.minusMonths(1),date.plusMonths(5));
    String[] subjects={"语文","数学","英语","物理","化学","生物","历史","地理","政治"};
    for(int i=0;i<subjects.length;i++)jdbc.update("insert into subject(id,org_id,code,name) values(?,?,?,?)",id(orgId,"subject"+i),orgId,"S"+i,subjects[i]);
    for(int i=0;i<12;i++) {
      UUID room=id(orgId,"room"+i),schoolClass=id(orgId,"class"+i);
      jdbc.update("insert into classroom(id,org_id,code,name,building,floor,capacity,exam_capacity) values(?,?,?,?,?,?,45,30)",room,orgId,"R"+(i+1),"明理楼"+(i+1)+"考场","明理楼",Integer.toString(i/4+1));
      jdbc.update("insert into school_class(id,org_id,code,name,grade,student_count,default_classroom_id) values(?,?,?,?,'高一',40,?)",schoolClass,orgId,"C"+(i+1),"高一"+(i+1)+"班",room);
    }
    String[] family={"林","陈","周","沈","许","苏","顾","陆","叶","程","郑","杨"};
    String[] names={"知远","明悦","书宁","清和","思雨","景行","予安","若溪","文轩","晓晴"};
    for(int i=0;i<teacherCount;i++) {
      UUID teacher=id(orgId,"teacher"+i);boolean homeroom=i<12;UUID schoolClass=homeroom?id(orgId,"class"+i):null;
      String leave=i%10==0?"[{\"start\":\""+date+"T08:00:00\",\"end\":\""+date+"T10:00:00\",\"reason\":\"演示教研安排\"}]":"[]";
      jdbc.update("insert into teacher(id,org_id,teacher_no,name,gender,grade,is_homeroom,homeroom_class_id,qualifications,leave_windows,tags) values(?,?,?,?,?,'高一',?,?,?::jsonb,?::jsonb,'[\"合成演示资料\"]')",
          teacher,orgId,String.format("T%03d",i+1),family[i%12]+names[i/12],i%2==0?"female":"male",homeroom,schoolClass,i%3==0?"[\"INVIGILATOR\",\"CHIEF\"]":"[\"INVIGILATOR\"]",leave);
      jdbc.update("insert into teacher_subject(teacher_id,subject_id,org_id) values(?,?,?)",teacher,id(orgId,"subject"+(i%subjects.length)),orgId);
      if(homeroom)jdbc.update("update school_class set homeroom_teacher_id=? where id=? and org_id=?",teacher,schoolClass,orgId);
    }
    jdbc.update("insert into exam_task(id,org_id,task_no,name,term_id,owner_id,starts_on,ends_on,source,status) values(?,?,'DEMO-001','高一期中模拟考试',?,?,?,?, 'quick','ready')",task,orgId,term,user,date,date.plusDays(5));
    for(int i=0;i<6;i++) {
      UUID exam=id(orgId,"exam"+i);
      jdbc.update("insert into exam(id,org_id,exam_task_id,subject_id,subject_name,exam_date,start_time,end_time,grade,duration_minutes,room_count) values(?,?,?,?,?,?,'09:00','11:00','高一',120,12)",exam,orgId,task,id(orgId,"subject"+i),subjects[i],date.plusDays(i));
      for(int r=0;r<12;r++) {
        jdbc.update("insert into exam_room(exam_id,classroom_id,org_id) values(?,?,?)",exam,id(orgId,"room"+r),orgId);
        jdbc.update("insert into exam_class(exam_id,class_id,org_id) values(?,?,?)",exam,id(orgId,"class"+r),orgId);
      }
    }
    jdbc.update("update organization set settings=settings || '{\"demoSeedVersion\":1}'::jsonb where id=?",orgId);return user;
  }
  static UUID id(UUID org,String label){return UUID.nameUUIDFromBytes((org+":"+label).getBytes(StandardCharsets.UTF_8));}
}
