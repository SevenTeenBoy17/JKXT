package com.jkxt.demo;

import static org.assertj.core.api.Assertions.*;
import com.jkxt.security.TestDatabaseIsolationGuard;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class DemoSchemaIntegrationTest {
  static String url, user, password, schema;
  static Map<String,String> baseline;

  @BeforeAll static void upgrade() throws Exception {
    verifyIsolation();
    schema = "demo_schema_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
        .target("30").load().migrate();
    try (Connection c = connection()) { baseline = snapshot(c); }
    Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("31").load().migrate();
  }

  static void verifyIsolation() {
    url = System.getenv("JKXT_TEST_DATASOURCE_URL");
    user = System.getenv("JKXT_TEST_DATASOURCE_USERNAME");
    password = System.getenv("JKXT_TEST_DATASOURCE_PASSWORD");
    try (var context = new GenericApplicationContext()) {
      Map<String,Object> values = new HashMap<>();
      values.put("spring.datasource.url", url == null ? "" : url);
      values.put("spring.datasource.username", user == null ? "" : user);
      values.put("spring.datasource.password", password == null ? "" : password);
      values.put("spring.data.redis.host", Objects.toString(System.getenv("JKXT_TEST_REDIS_HOST"), ""));
      values.put("spring.data.redis.port", Objects.toString(System.getenv("JKXT_TEST_REDIS_PORT"), ""));
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated", values));
      new TestDatabaseIsolationGuard().initialize(context);
    }
  }

  static Connection connection() throws SQLException {
    Connection c = DriverManager.getConnection(url,user,password);
    c.createStatement().execute("SET search_path TO " + schema + ", public");
    return c;
  }
  static Map<String,String> snapshot(Connection c) throws SQLException {
    Map<String,String> result = new TreeMap<>();
    for (String table : List.of("organization", "role", "user_role", "app_user")) {
      String expression = table.equals("app_user") ? "to_jsonb(t)-'auth_version'" : "to_jsonb(t)";
      try (var r = c.createStatement().executeQuery("select coalesce(jsonb_agg(v order by v::text)::text,'[]') from (select " + expression + " v from " + table + " t) q")) {
        r.next(); result.put(table,r.getString(1));
      }
    }
    return result;
  }
  static void sql(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) { for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]); p.execute(); }
  }
  static long number(Connection c,String query) throws SQLException {
    try(var r=c.createStatement().executeQuery(query)){r.next();return r.getLong(1);}
  }
  static UUID org(Connection c) throws SQLException {
    UUID id=UUID.randomUUID(); sql(c,"insert into organization(id,name,slug) values (?, 'Synthetic', ?)",id,id.toString()); return id;
  }
  static UUID account(Connection c,UUID org,String role) throws SQLException {
    UUID id=UUID.randomUUID(); sql(c,"insert into app_user(id,org_id,username,display_name,password_hash,role) values (?,?,?,'Synthetic','unused',?)",id,org,id.toString(),role);return id;
  }
  static UUID workspace(Connection c,UUID visitor) throws SQLException {
    UUID w=UUID.randomUUID(),o=org(c);
    sql(c,"insert into demo_workspace(id,visitor_id,org_id,generation,state,created_at,expires_at,last_activity_at) values (?,?,?,1,'READY',now(),now()+interval '2 hours',now())",w,visitor,o);
    UUID u=account(c,o,"DEMO");
    sql(c,"insert into demo_generation(workspace_id,generation,org_id,user_id,cleanup_state) values (?,1,?,?,'LIVE')",w,o,u);
    return w;
  }

  @Test void upgradePreservesAllExistingSchoolsUsersAndPermissions() throws Exception {
    try(var c=connection()) {
      assertThat(number(c,"select max(version::int) from flyway_schema_history where success")).isEqualTo(31);
      assertThat(snapshot(c)).isEqualTo(baseline);
    }
  }
  @Test void securityChangesIncrementVersionButLoginBookkeepingDoesNot() throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false); UUID u=account(c,org(c),"ADMIN");
      for(String assignment:List.of("password_hash='changed'","role='VIEWER'","org_id='"+org(c)+"'","status='disabled'","deleted_at=now()"))
        sql(c,"update app_user set "+assignment+" where id=?",u);
      assertThat(number(c,"select auth_version from app_user where id='"+u+"'")).isEqualTo(5);
      sql(c,"update app_user set login_count=login_count+1,last_login_at=now(),auth_version=999 where id=?",u);
      assertThat(number(c,"select auth_version from app_user where id='"+u+"'")).isEqualTo(5);c.rollback();
    }
  }
  @Test void originalRolesRemainValidAndUnownedDemoAccountIsRejected() throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false); UUID o=org(c);
      for(String role:List.of("SUPER_ADMIN","ADMIN","VIEWER","TEACHER_QUERY")) account(c,o,role);
      c.rollback(); c.setAutoCommit(true);
      assertThatThrownBy(()->account(c,UUID.fromString("00000000-0000-0000-0000-000000000001"),"DEMO")).isInstanceOf(SQLException.class);
    }
  }
  @Test void visitorCannotHaveTwoLiveWorkspacesAndCurrentGenerationIsRequired() throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false); UUID visitor=UUID.randomUUID();workspace(c,visitor);
      assertThatThrownBy(()->workspace(c,visitor)).isInstanceOf(SQLException.class);c.rollback();
      UUID w=workspace(c,UUID.randomUUID());sql(c,"update demo_workspace set generation=2 where id=?",w);
      assertThatThrownBy(c::commit).isInstanceOf(SQLException.class);c.rollback();
    }
  }
  @Test void generationOwnershipCannotChangeAndCleanupRetainsHistoricalIdentity() throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false);UUID w=workspace(c,UUID.randomUUID());
      sql(c,"set constraints all immediate");
      Savepoint sp=c.setSavepoint();
      assertThatThrownBy(()->sql(c,"update demo_generation set org_id=? where workspace_id=?",UUID.randomUUID(),w)).isInstanceOf(SQLException.class);c.rollback(sp);
      sql(c,"update demo_workspace set state='EXPIRED' where id=?",w);
      sql(c,"delete from organization where id=(select org_id from demo_workspace where id=?)",w);
      sql(c,"update demo_generation set cleanup_state='DONE' where workspace_id=?",w);
      assertThat(number(c,"select count(*) from demo_generation where workspace_id='"+w+"' and org_id is not null and user_id is not null")).isEqualTo(1);
      c.rollback();
    }
  }
  @Test void operationIdentityAndRequestDigestAreImmutable() throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false);UUID w=workspace(c,UUID.randomUUID()),op=UUID.randomUUID();
      sql(c,"insert into demo_operation(workspace_id,expected_generation,operation_id,kind,request_hash,state) values (?,1,?,'RESET',?,'RUNNING')",w,op,"a".repeat(64));
      Savepoint sp=c.setSavepoint();
      assertThatThrownBy(()->sql(c,"update demo_operation set request_hash=? where workspace_id=?", "b".repeat(64),w)).isInstanceOf(SQLException.class);c.rollback(sp);
      assertThatThrownBy(()->sql(c,"insert into demo_operation(workspace_id,expected_generation,operation_id,kind,request_hash,state) values (?,1,?,'RESET',?,'RUNNING')",w,op,"b".repeat(64))).isInstanceOf(SQLException.class);c.rollback();
    }
  }

  @Test void deferredCreateAndResetSucceedAndExpiredWorkspaceNeverRevives() throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false);UUID visitor=UUID.randomUUID(),w=workspace(c,visitor);
      sql(c,"set constraints all immediate");sql(c,"set constraints all deferred");
      UUID nextOrg=org(c),nextUser=account(c,nextOrg,"DEMO");
      sql(c,"update demo_workspace set state='RESETTING',generation=2,org_id=? where id=?",nextOrg,w);
      sql(c,"insert into demo_generation(workspace_id,generation,org_id,user_id,cleanup_state) values (?,2,?,?,'LIVE')",w,nextOrg,nextUser);
      sql(c,"update demo_workspace set state='READY' where id=?",w);
      sql(c,"set constraints all immediate");
      assertThat(number(c,"select generation from demo_workspace where id='"+w+"'")).isEqualTo(2);
      sql(c,"update demo_workspace set state='EXPIRED' where id=?",w);
      Savepoint sp=c.setSavepoint();
      assertThatThrownBy(()->sql(c,"update demo_workspace set state='READY' where id=?",w)).isInstanceOf(SQLException.class);c.rollback(sp);
      sql(c,"set constraints all deferred");workspace(c,visitor);sql(c,"set constraints all immediate");
      c.rollback();
    }
  }

  @Test void demoCannotBecomeAdministratorOrHaveOwnershipTombstoneDeleted() throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false);UUID w=workspace(c,UUID.randomUUID());sql(c,"set constraints all immediate");
      Savepoint sp=c.setSavepoint();
      assertThatThrownBy(()->sql(c,"update app_user set role='ADMIN' where id=(select user_id from demo_generation where workspace_id=?)",w)).isInstanceOf(SQLException.class);c.rollback(sp);
      assertThatThrownBy(()->sql(c,"delete from demo_generation where workspace_id=?",w)).isInstanceOf(SQLException.class);c.rollback();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
  void demoPrimaryKeyCannotEscapeOwnershipWithRoleOrOrgChange(boolean changeRole) throws Exception {
    try(var c=connection()) {
      c.setAutoCommit(false);UUID w=workspace(c,UUID.randomUUID());sql(c,"set constraints all immediate");
      String otherChange=changeRole ? "role='ADMIN'" : "org_id='"+org(c)+"'";
      assertThatThrownBy(()->sql(c,"update app_user set id=?,"+otherChange+" where id=(select user_id from demo_generation where workspace_id=?)",UUID.randomUUID(),w))
          .isInstanceOf(SQLException.class);
      c.rollback();
    }
  }
}
