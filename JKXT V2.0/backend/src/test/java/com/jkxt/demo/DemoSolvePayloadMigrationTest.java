package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import com.jkxt.security.TestDatabaseIsolationGuard;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Retained, isolated schemas: never changes the public schema or rewrites migration history. */
class DemoSolvePayloadMigrationTest {
  static String url,user,password;
  static Integer observedV35;
  @BeforeAll static void guard() {
    url=System.getenv("JKXT_TEST_DATASOURCE_URL");user=System.getenv("JKXT_TEST_DATASOURCE_USERNAME");password=System.getenv("JKXT_TEST_DATASOURCE_PASSWORD");
    try(var context=new GenericApplicationContext()) {
      Map<String,Object> values=new HashMap<>();
      values.put("spring.datasource.url",Objects.toString(url,""));values.put("spring.datasource.username",Objects.toString(user,""));values.put("spring.datasource.password",Objects.toString(password,""));
      values.put("spring.data.redis.host",Objects.toString(System.getenv("JKXT_TEST_REDIS_HOST"),""));values.put("spring.data.redis.port",Objects.toString(System.getenv("JKXT_TEST_REDIS_PORT"),""));
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",values));
      new TestDatabaseIsolationGuard().initialize(context);
    }
    assertThat(url).endsWith("/jkxt_test_entry_entry20260920v2");
  }
  static String schema(String kind){return "task6b_fix1_"+kind+"_"+UUID.randomUUID().toString().replace("-","");}
  static Flyway migrate(String schema,String target) {
    assertThat(schema).matches("task6b_fix1_(upgrade|fresh)_[a-f0-9]{32}");
    var flyway=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target(target).load();
    flyway.migrate();flyway.validate();return flyway;
  }
  static Connection connection(String schema) throws SQLException {
    assertThat(schema).matches("task6b_fix1_(upgrade|fresh)_[a-f0-9]{32}");
    var c=DriverManager.getConnection(url,user,password);
    try(var statement=c.createStatement()){statement.execute("SET search_path TO "+schema+", public");}
    assertThat(scalar(c,"select current_schema()" )).isEqualTo(schema);return c;
  }
  static void sql(Connection c,String statement,Object... args) throws SQLException {
    try(var p=c.prepareStatement(statement)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.execute();}
  }
  static String scalar(Connection c,String statement) throws SQLException {
    try(var s=c.createStatement();var rs=s.executeQuery(statement)){assertThat(rs.next()).isTrue();return rs.getString(1);}
  }
  static Map<String,String> row(Connection c,UUID id) throws SQLException {
    try(var p=c.prepareStatement("select * from demo_solve_job where reservation_id=?")) {
      p.setObject(1,id);try(var rs=p.executeQuery()){assertThat(rs.next()).isTrue();var result=new TreeMap<String,String>();
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++)result.put(rs.getMetaData().getColumnName(i),rs.getString(i));return result;}
    }
  }
  static UUID seed(Connection c,boolean released) throws SQLException {
    c.setAutoCommit(false);UUID org=UUID.randomUUID(),workspace=UUID.randomUUID(),account=UUID.randomUUID(),owner=UUID.randomUUID();
    sql(c,"insert into organization(id,name,slug) values(?,'Synthetic migration fixture',?)",org,org.toString());
    sql(c,"insert into demo_workspace(id,visitor_id,org_id,generation,state,created_at,expires_at,last_activity_at) values(?,?,?,1,'READY',now(),now()+interval '2 hours',now())",workspace,UUID.randomUUID(),org);
    sql(c,"insert into app_user(id,org_id,username,display_name,password_hash,role) values(?,?,?,'Synthetic migration fixture','unused','DEMO')",account,org,account.toString());
    sql(c,"insert into demo_generation(workspace_id,generation,org_id,user_id,cleanup_state) values(?,1,?,?,'LIVE')",workspace,org,account);
    sql(c,"""
        insert into demo_solve_job(reservation_id,workspace_id,generation,org_id,run_id,task_id,owner_boot_token,
        admitted_tick,admitted_at,deadline_at,lease_expires_at,fixture_json,policy_json,state,native_exited_at,released_at,terminal_reason)
        values(?,?,1,?,?,?,?,17,now(),now()+interval '30 seconds',now()+interval '2 hours',?::jsonb,?::jsonb,?,
        case when ? then now() else null end,case when ? then now() else null end,?)
        """,owner,workspace,org,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
        "{\"teacher\":\"fixture-"+owner+"\",\"window\":\"2035-01-02\"}","{\"policy\":\"policy-"+owner+"\"}",released?"SUCCEEDED":"QUEUED",released,released,released?"LEGACY_SUCCESS":null);
    c.commit();c.setAutoCommit(true);return owner;
  }
  static String publicFingerprint(Connection c) throws SQLException {
    return scalar(c,"select md5(coalesce((select jsonb_agg(to_jsonb(j) order by reservation_id)::text from public.demo_solve_job j),'[]') || (select jsonb_agg(to_jsonb(h) order by installed_rank)::text from public.flyway_schema_history h))");
  }
  static void history(Connection c,String schema) throws SQLException {
    assertThat(scalar(c,"select max(version::int) from flyway_schema_history where success")).isEqualTo("35");
    assertThat(scalar(c,"select count(*) from flyway_schema_history where version is not null and success")).isEqualTo("35");
    assertThat(scalar(c,"select count(*) from flyway_schema_history where not success")).isEqualTo("0");
    assertThat(scalar(c,"select checksum from flyway_schema_history where version='34'")).isEqualTo("-1201506175");
    int checksum=Integer.parseInt(scalar(c,"select checksum from flyway_schema_history where version='35'"));
    if(observedV35!=null)assertThat(checksum).isEqualTo(observedV35);observedV35=checksum;
    assertThat(scalar(c,"select count(*) from pg_trigger where tgrelid='demo_solve_job'::regclass and tgname='demo_solve_immutable' and not tgisinternal")).isEqualTo("1");
    assertThat(scalar(c,"select count(*) from pg_constraint where conrelid='demo_solve_job'::regclass and conname='demo_solve_payload_lifecycle' and convalidated")).isEqualTo("1");
    System.out.printf("TASK6B_FIX1_MIGRATION schema=%s version=35 v34Checksum=-1201506175 v35Checksum=%d validated=true%n",schema,checksum);
  }
  static void refuses(Connection c,String query,Object... args) {
    assertThatThrownBy(()->sql(c,query,args)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
  }
  static void activeGuards(Connection c,UUID id) throws SQLException {
    var before=row(c,id);
    refuses(c,"update demo_solve_job set fixture_json='{}'::jsonb where reservation_id=?",id);
    refuses(c,"update demo_solve_job set policy_json='{}'::jsonb where reservation_id=?",id);
    refuses(c,"update demo_solve_job set fixture_json=null,policy_json=null,payload_scrubbed_at=now() where reservation_id=?",id);
    refuses(c,"update demo_solve_job set run_id=? where reservation_id=?",UUID.randomUUID(),id);
    refuses(c,"delete from demo_solve_job where reservation_id=?",id);
    assertThat(row(c,id)).isEqualTo(before);
  }
  static void releasedGuards(Connection c,UUID id) throws SQLException {
    var before=row(c,id);assertThat(before.get("fixture_json")).isNull();assertThat(before.get("policy_json")).isNull();
    assertThat(before.get("released_at")).isNotNull();assertThat(before.get("payload_scrubbed_at")).isNotNull();
    refuses(c,"update demo_solve_job set fixture_json='{}'::jsonb,policy_json='{}'::jsonb,payload_scrubbed_at=null where reservation_id=?",id);
    refuses(c,"update demo_solve_job set state='FAILED' where reservation_id=?",id);
    refuses(c,"update demo_solve_job set payload_scrubbed_at=payload_scrubbed_at+interval '1 second' where reservation_id=?",id);
    refuses(c,"update demo_solve_job set owner_boot_token=? where reservation_id=?",UUID.randomUUID(),id);
    refuses(c,"delete from demo_solve_job where reservation_id=?",id);assertThat(row(c,id)).isEqualTo(before);
  }
  @Test void populatedV34UpgradePreservesActiveInputAndMinimizesReleasedLegacyOnly() throws Exception {
    String schema=schema("upgrade");migrate(schema,"34");UUID active,released;Map<String,String> activeBefore,releasedBefore;String publicBefore;
    try(var c=connection(schema)) {
      assertThat(scalar(c,"select max(version::int) from flyway_schema_history where success")).isEqualTo("34");
      publicBefore=publicFingerprint(c);active=seed(c,false);released=seed(c,true);activeBefore=row(c,active);releasedBefore=row(c,released);
      assertThat(activeBefore.get("fixture_json")).contains("fixture-"+active);assertThat(activeBefore.get("policy_json")).contains("policy-"+active);
    }
    migrate(schema,"35");
    try(var c=connection(schema)) {
      history(c,schema);var activeAfter=row(c,active);assertThat(activeAfter.remove("payload_scrubbed_at")).isNull();assertThat(activeAfter).isEqualTo(activeBefore);
      var releasedAfter=row(c,released);
      for(var entry:releasedBefore.entrySet())if(!Set.of("fixture_json","policy_json").contains(entry.getKey()))assertThat(releasedAfter.get(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
      activeGuards(c,active);releasedGuards(c,released);assertThat(publicFingerprint(c)).isEqualTo(publicBefore);
    }
  }
  @Test void freshV35SchemaHasSameHistoryAndOneWayPayloadGuards() throws Exception {
    String schema=schema("fresh");migrate(schema,"35");
    try(var c=connection(schema)) {
      String publicBefore=publicFingerprint(c);history(c,schema);UUID active=seed(c,false);activeGuards(c,active);
      sql(c,"update demo_solve_job set state='SUCCEEDED',native_exited_at=now(),released_at=now(),fixture_json=null,policy_json=null,payload_scrubbed_at=now() where reservation_id=?",active);
      releasedGuards(c,active);assertThat(publicFingerprint(c)).isEqualTo(publicBefore);
    }
  }
}
