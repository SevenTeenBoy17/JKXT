package com.jkxt.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SessionService.class);
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final CurrentAccountAccess accounts;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final TransactionTemplate cleanupTransactions;
  private final boolean secure;
  private final String sameSite;
  private final Duration ttl;
  private java.time.Clock demoClock=java.time.Clock.systemUTC();
  @org.springframework.beans.factory.annotation.Autowired
  public void demoClock(org.springframework.beans.factory.ObjectProvider<java.time.Clock> clocks) {demoClock=clocks.getIfAvailable(java.time.Clock::systemUTC);}

  public SessionService(StringRedisTemplate redis,ObjectMapper mapper,CurrentAccountAccess accounts,
      JdbcTemplate jdbc,PlatformTransactionManager manager,
      @Value("${jkxt.auth.cookie-secure:false}") boolean secure,
      @Value("${jkxt.auth.cookie-same-site:Lax}") String sameSite,
      @Value("${jkxt.auth.session-ttl-seconds:604800}") long seconds) {
    if(seconds<=0) throw new IllegalArgumentException("Session TTL must be positive");
    this.redis=redis; this.mapper=mapper; this.accounts=accounts;
    this.jdbc=Objects.requireNonNull(jdbc); this.transactions=new TransactionTemplate(Objects.requireNonNull(manager));
    this.cleanupTransactions=new TransactionTemplate(manager);
    this.cleanupTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.secure=secure; this.sameSite=sameSite; this.ttl=Duration.ofSeconds(seconds);
  }
  public String cookieName(PortalPurpose purpose) {
    return (secure?"__Host-":"")+"JKXT_"+purpose.name()+"_SESSION";
  }
  /** Transitional SCHOOL-only consumer remains until the Task 9 fixture migration. */
  @Deprecated public CreatedSession create(SessionPrincipal principal) {
    var current=accounts.load(principal.userId(),"");
    return create(current.principal(),PortalPurpose.SCHOOL,current.authVersion());
  }
  public CreatedSession create(SessionPrincipal principal,PortalPurpose purpose,long authVersion) {
    return replace(principal,purpose,authVersion,null);
  }
  /** Join the caller's DB transaction. Redis preparation is never sufficient authorization. */
  public CreatedSession replace(SessionPrincipal principal,PortalPurpose purpose,long authVersion,String previousId) {
    if(purpose==PortalPurpose.DEMO || !PortalSessionPolicy.allows(purpose,principal.role()) || authVersion<0)
      throw new AuthException(HttpStatus.FORBIDDEN,"AUTH_PORTAL_MISMATCH","Account cannot use this portal");
    return transactions.execute(status -> {
      UUID lineage=validId(previousId)?jdbc.query("select lineage_id from portal_session_registry where id=? and purpose=? and expires_at>now()",
          rs->rs.next()?rs.getObject(1,UUID.class):null,UUID.fromString(previousId),purpose.name()):null;
      Instant expires=Instant.now().plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
      if(lineage==null) {
        lineage=UUID.randomUUID();
        jdbc.update("insert into portal_session_lineage(id,purpose,expires_at) values(?,?,?)",lineage,purpose.name(),java.sql.Timestamp.from(expires));
      }
      // Lock exactly one lineage after the login's account lock. No path locks an account here.
      UUID previous=jdbc.queryForObject("select current_session_id from portal_session_lineage where id=? and purpose=? for update",
          UUID.class,lineage,purpose.name());
      Registry old=previous==null?null:registry(previous,purpose);
      String id=UUID.randomUUID().toString();
      var stored=new SessionPrincipal(id,principal.userId(),principal.orgId(),principal.username(),principal.displayName(),
          principal.role(),List.copyOf(principal.permissions()));
      var envelope=new SessionEnvelope(stored,purpose,authVersion,token(),token(),expires,null,null);
      String json;
      try { json=mapper.writeValueAsString(new StoredSession(1,envelope)); }
      catch(Exception failure) { throw new IllegalStateException("Unable to serialize session",failure); }
      jdbc.update("insert into portal_session_registry(id,purpose,lineage_id,user_id,auth_version,expires_at) values(?,?,?,?,?,?)",
          UUID.fromString(id),purpose.name(),lineage,principal.userId(),authVersion,java.sql.Timestamp.from(expires));
      jdbc.update("update portal_session_lineage set current_session_id=?,expires_at=greatest(expires_at,?) where id=?",
          UUID.fromString(id),java.sql.Timestamp.from(expires),lineage);
      // Register before SET, including the network-uncertain case where Redis writes then throws.
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int completion) {
          try {
            if(completion==STATUS_COMMITTED) {
              if(old!=null) deletePayload(old.id(),old.purpose(),old.userId());
            } else deletePayload(id,purpose,principal.userId());
          } catch(RuntimeException unavailable) {
            // DB committed-selector checks remain authoritative even if physical cleanup fails.
            LOG.warn("Portal session payload cleanup unavailable; committed registry still enforces authorization");
          }
          if(completion==STATUS_COMMITTED) try { cleanupExpiredAliases(); }
          catch(RuntimeException unavailable) { LOG.warn("Expired portal session cleanup unavailable"); }
        }
      });
      redis.execute(new DefaultRedisScript<>("""
          redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2])
          redis.call('SADD',KEYS[2],KEYS[1])
          redis.call('EXPIRE',KEYS[2],ARGV[2])
          return 1
          """,Long.class),List.of(key(id,purpose),userKey(principal.userId())),json,Long.toString(ttl.toSeconds()));
      return new CreatedSession(stored,sessionCookie(id,purpose),envelope);
    });
  }
  public Optional<SessionEnvelope> find(String id,PortalPurpose purpose) {
    if(!validId(id) || purpose==null) return Optional.empty();
    if(purpose==PortalPurpose.DEMO)return findDemo(id);
    String json=redis.opsForValue().get(key(id,purpose));
    if(json==null) return Optional.empty();
    SessionEnvelope envelope;
    try {
      var stored=mapper.readValue(json,StoredSession.class); envelope=stored.envelope();
      if(stored.schemaVersion()!=1 || envelope==null || envelope.audience()!=purpose || envelope.principal()==null
          || !id.equals(envelope.principal().sessionId()) || envelope.authVersion()<0 || envelope.context()==null
          || envelope.csrfToken()==null || envelope.expiresAt()==null || !envelope.expiresAt().isAfter(Instant.now()))
        return Optional.empty();
    } catch(Exception malformed) { return Optional.empty(); }
    // Outside deserialization catch: a DB outage is an identifiable failure, never authentication.
    boolean committed=Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from portal_session_registry r join portal_session_lineage l
          on l.id=r.lineage_id and l.purpose=r.purpose
          where r.id=? and r.purpose=? and l.current_session_id=r.id and r.user_id=?
          and r.auth_version=? and r.expires_at=? and r.expires_at>now())
        """,Boolean.class,UUID.fromString(id),purpose.name(),envelope.principal().userId(),envelope.authVersion(),
        java.sql.Timestamp.from(envelope.expiresAt())));
    return committed?Optional.of(envelope):Optional.empty();
  }
  public void revoke(String id,PortalPurpose purpose) {
    if(!validId(id)) return;
    transactions.executeWithoutResult(status -> {
      var record=registry(UUID.fromString(id),purpose);
      if(record!=null) {
        if(purpose==PortalPurpose.DEMO) {
          var generation=demoGeneration(record.userId());
          if(generation!=null)revokeObservedDemo(id,record.userId(),generation);
          return;
        }
        jdbc.update("update portal_session_lineage set current_session_id=null where current_session_id=? and purpose=?",UUID.fromString(id),purpose.name());
        deletePayload(id,purpose,record.userId());
      } else redis.delete(key(id,purpose));
    });
  }
  public void revoke(String id) { for(var p:PortalPurpose.values()) revoke(id,p); }
  public void revokeOtherSessions(UUID userId,String keepId) {
    if(userId==null) return;
    // Snapshot first. A concurrently added independent browser is not erased/rebuilt out of its index.
    Set<String> keys=redis.opsForSet().members(userKey(userId));
    if(keys==null) return;
    RuntimeException failure=null;
    for(String key:keys) {
      if(keepId!=null && key.endsWith(":"+keepId)) continue;
      for(var p:PortalPurpose.values()) {
        String prefix="jkxt:session:"+p.name().toLowerCase(Locale.ROOT)+":";
        if(key.startsWith(prefix)) {
          String id=key.substring(prefix.length());
          if(p==PortalPurpose.DEMO && id.startsWith("g:")) {
            String[] parts=id.split(":",3);
            if(parts.length==3 && parts[1].matches("[1-9][0-9]*") && validId(parts[2]))
              revokeObservedDemo(parts[2],userId,Long.parseLong(parts[1]));
            break;
          }
          if(validId(id)) {
            try {
              revoke(id,p);
              redis.opsForSet().remove(userKey(userId),key);
            } catch(RuntimeException unavailable) {
              // Includes transaction begin/commit uncertainty. Never change a selector in fallback.
              try { deletePayload(id,p,userId); }
              catch(RuntimeException cleanupFailure) { if(cleanupFailure!=unavailable) unavailable.addSuppressed(cleanupFailure); }
              if(failure==null) failure=unavailable; else if(failure!=unavailable) failure.addSuppressed(unavailable);
            }
          }
          break;
        }
      }
    }
    if(failure!=null) throw failure;
  }
  private Registry registry(UUID id,PortalPurpose purpose) {
    return jdbc.query("select user_id from portal_session_registry where id=? and purpose=?",
        rs->rs.next()?new Registry(id.toString(),purpose,rs.getObject(1,UUID.class)):null,id,purpose.name());
  }
  private void deletePayload(String id,PortalPurpose purpose,UUID user) {
    if(purpose==PortalPurpose.DEMO) {
      Long generation=demoGeneration(user);
      if(generation!=null)deleteDemoPayload(id,user,generation);
      return;
    }
    redis.execute(new DefaultRedisScript<>("redis.call('DEL',KEYS[1]); redis.call('SREM',KEYS[2],KEYS[1]); return 1",Long.class),
        List.of(key(id,purpose),userKey(user)));
  }
  private void cleanupExpiredAliases() {
    // After issuance commit, in a separate transaction: no account locks, bounded ordered lineage locks.
    var expired=cleanupTransactions.execute(status -> {
      var result=new ArrayList<Registry>();
      var lineages=jdbc.queryForList("""
          select l.id from portal_session_lineage l where l.expires_at<=now()
          or exists(select 1 from portal_session_registry r where r.lineage_id=l.id and r.expires_at<=now())
          order by l.id limit 64 for update skip locked
          """,UUID.class);
      for(var lineage:lineages) {
        if(result.size()>=128) break;
        var records=jdbc.query("""
            select id,purpose,user_id from portal_session_registry where lineage_id=? and expires_at<=now()
            order by id limit ?
            """,(rs,n)->new Registry(rs.getString(1),PortalPurpose.valueOf(rs.getString(2)),rs.getObject(3,UUID.class)),lineage,128-result.size());
        for(var record:records) {
          jdbc.update("update portal_session_lineage set current_session_id=null where id=? and current_session_id=?",lineage,UUID.fromString(record.id()));
          jdbc.update("delete from portal_session_registry where id=?",UUID.fromString(record.id()));
        }
        result.addAll(records);
        jdbc.update("delete from portal_session_lineage where id=? and expires_at<=now() and not exists(select 1 from portal_session_registry where lineage_id=?)",lineage,lineage);
      }
      return result;
    });
    // Exact captured IDs only, after successful metadata cleanup commit; never rebuild a user index.
    RuntimeException failure=null;
    for(var record:expired) try { deletePayload(record.id(),record.purpose(),record.userId()); }
    catch(RuntimeException unavailable) { if(failure==null) failure=unavailable; else if(failure!=unavailable) failure.addSuppressed(unavailable); }
    if(failure!=null) throw failure;
  }
  public ResponseCookie sessionCookie(String id,PortalPurpose purpose) { return cookie(id,purpose,ttl); }
  public ResponseCookie demoCookie(SessionEnvelope envelope,Instant now) {
    return cookie(envelope.principal().sessionId(),PortalPurpose.DEMO,Duration.between(now,envelope.expiresAt()));
  }
  /** Caller holds the workspace row. Each generation has its own prepared payload. */
  public SessionEnvelope prepareDemo(UUID workspace,long generation,UUID user,Instant expires,String stableId) {
    if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Demo registration requires a transaction");
    var current=accounts.load(user,stableId==null?UUID.randomUUID().toString():stableId);
    if(!"DEMO".equals(current.principal().role()))throw new AuthException(HttpStatus.FORBIDDEN,"AUTH_PORTAL_MISMATCH","Demo ownership required");
    String id=current.principal().sessionId();Registry old=registry(UUID.fromString(id),PortalPurpose.DEMO);
    if(stableId==null) {
      UUID lineage=UUID.randomUUID();
      jdbc.update("insert into portal_session_lineage(id,purpose,current_session_id,expires_at) values(?,'DEMO',?,?)",lineage,UUID.fromString(id),java.sql.Timestamp.from(expires));
      jdbc.update("insert into portal_session_registry(id,purpose,lineage_id,user_id,auth_version,expires_at) values(?,'DEMO',?,?,?,?)",
          UUID.fromString(id),lineage,user,current.authVersion(),java.sql.Timestamp.from(expires));
    }else {
      UUID selected=jdbc.query("select l.current_session_id from portal_session_lineage l join portal_session_registry r on r.lineage_id=l.id where r.id=? and r.purpose='DEMO' for update of l",
          rs->rs.next()?rs.getObject(1,UUID.class):null,UUID.fromString(id));
      if(!UUID.fromString(id).equals(selected))throw new AuthException(HttpStatus.UNAUTHORIZED,"AUTH_UNAUTHENTICATED","Demo session was revoked");
      jdbc.update("update portal_session_registry set user_id=?,auth_version=?,expires_at=? where id=? and purpose='DEMO'",
          user,current.authVersion(),java.sql.Timestamp.from(expires),UUID.fromString(id));
    }
    var envelope=new SessionEnvelope(current.principal(),PortalPurpose.DEMO,current.authVersion(),token(),token(),expires,workspace,generation);
    String payload;
    try{payload=mapper.writeValueAsString(new StoredSession(1,envelope));}catch(Exception e){throw new IllegalStateException("Demo session serialization failed");}
    Long oldGeneration=old==null?null:demoGeneration(old.userId());
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
      @Override public void afterCompletion(int completion) {
        try{
          if(completion!=STATUS_COMMITTED)deleteDemoPayload(id,user,generation);
          else if(old!=null && oldGeneration!=null && !old.userId().equals(user))deleteDemoPayload(id,old.userId(),oldGeneration);
        }catch(RuntimeException unavailable){LOG.warn("Demo generation payload cleanup pending; committed registry remains authoritative");}
      }
    });
    // Physical TTL is a storage ceiling; the authoritative idle/absolute deadlines are DB+server clock.
    redis.execute(new DefaultRedisScript<>("redis.call('SET',KEYS[1],ARGV[1],'EX',7200); redis.call('SADD',KEYS[2],KEYS[1]); redis.call('EXPIRE',KEYS[2],7200); return 1",Long.class),
        List.of(demoKey(id,generation),userKey(user)),payload);
    return envelope;
  }
  private Optional<SessionEnvelope> findDemo(String id) {
    var binding=jdbc.query("""
        select r.user_id,r.auth_version,r.expires_at,g.workspace_id,g.generation,w.state,
          least(w.expires_at,w.last_activity_at+interval '30 minutes') from portal_session_registry r
        join portal_session_lineage l on l.id=r.lineage_id and l.purpose=r.purpose and l.current_session_id=r.id
        join demo_generation g on g.user_id=r.user_id join demo_workspace w on w.id=g.workspace_id where r.id=? and r.purpose='DEMO'
        """,rs->rs.next()?new DemoBinding(rs.getObject(1,UUID.class),rs.getLong(2),rs.getTimestamp(3).toInstant(),rs.getObject(4,UUID.class),rs.getLong(5),rs.getString(6),rs.getTimestamp(7).toInstant()):null,UUID.fromString(id));
    if(binding==null)return Optional.empty();
    if(binding.state().equals("EXPIRED") || !demoClock.instant().isBefore(binding.deadline()))
      throw new AuthException(HttpStatus.UNAUTHORIZED,"DEMO_EXPIRED","演示工作区已到期，请重新进入");
    String json=redis.opsForValue().get(demoKey(id,binding.generation()));if(json==null)return Optional.empty();
    try {
      var payload=mapper.readValue(json,StoredSession.class);var e=payload.envelope();
      if(payload.schemaVersion()!=1 || e==null || e.audience()!=PortalPurpose.DEMO || e.principal()==null
          || !id.equals(e.principal().sessionId()) || !binding.user().equals(e.principal().userId()) || e.authVersion()!=binding.version()
          || !binding.expires().equals(e.expiresAt()) || !binding.workspace().equals(e.workspaceId()) || !Objects.equals(binding.generation(),e.generation())
          || e.context()==null || e.csrfToken()==null)return Optional.empty();
      return Optional.of(e);
    }catch(Exception malformed){return Optional.empty();}
  }
  /** Delayed old-generation revocation cannot revoke the stable ID's new owner. */
  public void revokeObservedDemo(String id,UUID observedUser,long generation) {
    transactions.executeWithoutResult(status->{
      jdbc.query("select w.id from demo_workspace w join demo_generation g on g.workspace_id=w.id where g.user_id=? and g.generation=? for update of w",
          rs->{while(rs.next()){/* serialize with generation publication */}return null;},observedUser,generation);
      jdbc.update("""
          update portal_session_lineage l set current_session_id=null from portal_session_registry r
          join demo_generation g on g.user_id=r.user_id where l.id=r.lineage_id and l.purpose='DEMO'
          and l.current_session_id=r.id and r.id=? and r.user_id=? and g.generation=?
          """,UUID.fromString(id),observedUser,generation);
      deleteDemoPayload(id,observedUser,generation);
    });
  }
  public void revokeDemo(SessionEnvelope captured) {
    transactions.executeWithoutResult(status->{
      var generation=jdbc.query("select generation from demo_workspace where id=? for update",rs->rs.next()?rs.getLong(1):null,captured.workspaceId());
      if(!Objects.equals(generation,captured.generation()))throw new AuthException(HttpStatus.CONFLICT,"DEMO_STALE_GENERATION","Demo generation changed");
      var current=find(captured.principal().sessionId(),PortalPurpose.DEMO).orElseThrow(()->new AuthException(HttpStatus.UNAUTHORIZED,"AUTH_UNAUTHENTICATED","Session unavailable"));
      if(!Objects.equals(captured.context(),current.context()))throw new AuthException(HttpStatus.CONFLICT,"SESSION_CONTEXT_CHANGED","Session context changed");
      revokeObservedDemo(captured.principal().sessionId(),captured.principal().userId(),captured.generation());
    });
  }
  private Long demoGeneration(UUID user) {return jdbc.query("select generation from demo_generation where user_id=?",rs->rs.next()?rs.getLong(1):null,user);}
  private void deleteDemoPayload(String id,UUID user,long generation) {
    redis.execute(new DefaultRedisScript<>("redis.call('DEL',KEYS[1]); redis.call('SREM',KEYS[2],KEYS[1]); return 1",Long.class),List.of(demoKey(id,generation),userKey(user)));
  }
  private static String demoKey(String id,long generation) {return "jkxt:session:demo:g:"+generation+":"+id;}
  private record DemoBinding(UUID user,long version,Instant expires,UUID workspace,long generation,String state,Instant deadline) {}
  public ResponseCookie clearCookie(PortalPurpose purpose) { return cookie("",purpose,Duration.ZERO); }
  @Deprecated public ResponseCookie clearCookie() { return clearCookie(PortalPurpose.SCHOOL); }
  @Deprecated public String cookieName() { return cookieName(PortalPurpose.SCHOOL); }
  private ResponseCookie cookie(String id,PortalPurpose purpose,Duration age) {
    return ResponseCookie.from(cookieName(purpose),id).httpOnly(true).secure(secure).sameSite(sameSite).path("/").maxAge(age).build();
  }
  private static boolean validId(String id) { return id!=null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
  private static String token() { return UUID.randomUUID().toString()+UUID.randomUUID().toString(); }
  private static String key(String id,PortalPurpose purpose) { return "jkxt:session:"+purpose.name().toLowerCase(Locale.ROOT)+":"+id; }
  private static String userKey(UUID id) { return "jkxt:user-sessions:v2:"+id; }
  public record CreatedSession(SessionPrincipal principal,ResponseCookie cookie,SessionEnvelope envelope) {}
  private record StoredSession(int schemaVersion,SessionEnvelope envelope) {}
  private record Registry(String id,PortalPurpose purpose,UUID userId) {}
}
