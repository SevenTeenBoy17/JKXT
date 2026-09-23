package com.jkxt.demo;

import com.jkxt.security.auth.AuthException;
import java.time.Duration;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DemoQuotaService {
  private record Rate(long maximum,Duration window) {}
  private static final Map<String,Rate> RATES=Map.of(
      "bootstrap",new Rate(60,Duration.ofMinutes(1)),"start",new Rate(5,Duration.ofMinutes(10)),
      "reset",new Rate(2,Duration.ofMinutes(1)),"upload",new Rate(10,Duration.ofMinutes(1)),"solve",new Rate(3,Duration.ofMinutes(1)));
  private static final Map<String,String> TABLES=Map.of("teachers","teacher","classrooms","classroom","exams","exam","tasks","exam_task","classes","school_class","subjects","subject");
  private static final Map<String,Long> CAPACITY=Map.of("teachers",150L,"classrooms",40L,"exams",100L,"tasks",10L,"classes",200L,"subjects",50L);
  private static final DefaultRedisScript<Long> ATTEMPT=new DefaultRedisScript<>("""
      local now=tonumber(ARGV[1]); local window=tonumber(ARGV[2]); local maximum=tonumber(ARGV[3]);
      redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-window)
      if redis.call('ZCARD',KEYS[1])>=maximum then
        local first=redis.call('ZRANGE',KEYS[1],0,0,'WITHSCORES')
        return math.max(1,tonumber(first[2])+window-now)
      end
      redis.call('ZADD',KEYS[1],now,ARGV[4]); redis.call('PEXPIRE',KEYS[1],window)
      return 0
      """,Long.class);
  private final StringRedisTemplate redis;
  private final DemoWorkspaceRepository repository;
  private final jakarta.persistence.EntityManager persistence;
  public DemoQuotaService(StringRedisTemplate redis,DemoWorkspaceRepository repository,jakarta.persistence.EntityManagerFactory factory){
    this.redis=redis;this.repository=repository;
    this.persistence=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory);
  }
  public static final class RateExceeded extends AuthException {
    private final long retryAfter;
    RateExceeded(long millis){super(HttpStatus.TOO_MANY_REQUESTS,"DEMO_QUOTA_EXCEEDED","演示操作过于频繁，请稍后重试");retryAfter=Math.max(1,(millis+999)/1000);}
    public long retryAfter(){return retryAfter;}
  }
  /** Attempts are irreversible even if the following business transaction fails. */
  public void consumeAttempt(String operation,String subject) {
    repository.requireDemo();var rate=RATES.get(operation);
    if(rate==null || subject==null || subject.isBlank())throw unavailable();
    Long retry;
    try {
      retry=redis.execute(ATTEMPT,List.of(rateKey(operation,subject)),Long.toString(repository.now().toEpochMilli()),
          Long.toString(rate.window().toMillis()),Long.toString(rate.maximum()),UUID.randomUUID().toString());
    } catch(RuntimeException failure){throw unavailable();}
    if(retry==null || retry<0)throw unavailable();
    if(retry>0)throw new RateExceeded(retry);
  }
  static String rateKey(String operation,String subject){return "jkxt:demo:quota:"+operation+":"+DemoWorkspaceService.hash(subject);}
  private static AuthException unavailable(){return DemoWorkspaceRepository.error(HttpStatus.SERVICE_UNAVAILABLE,"DEMO_QUOTA_UNAVAILABLE","演示配额暂不可用");}

  /**
   * Finite data capacity is protected by the caller's existing workspace transaction, not Redis.
   * The caller supplies validated, deduplicated NEW rows and retains this transaction until commit.
   * Task 6 must integrate all mutators with the same workspace lock before this is an end-to-end cap.
   */
  public Reservation reserve(DemoLease lease,String operation,long amount) {
    repository.requireDemo();
    if(!CAPACITY.containsKey(operation))throw DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_CAPACITY_UNSUPPORTED","此演示容量操作尚未接入");
    if(amount<0)throw DemoWorkspaceRepository.error(HttpStatus.BAD_REQUEST,"DEMO_CAPACITY_INVALID","新增数量不能为负数");
    if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw DemoWorkspaceRepository.error(HttpStatus.SERVICE_UNAVAILABLE,"DEMO_CAPACITY_TRANSACTION_REQUIRED","演示容量需要工作区写入事务");
    var w=repository.workspace(lease.workspaceId(),true);repository.matches(w,lease);repository.ready(w);
    long maximum=CAPACITY.get(operation),current=count(operation,lease.orgId());
    if(amount>maximum-current)throw capacityExceeded();
    var reservation=new Reservation(lease,operation);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
      @Override public void beforeCommit(boolean readOnly) {
        if(!reservation.committed)throw DemoWorkspaceRepository.error(HttpStatus.CONFLICT,"DEMO_RESERVATION_ABORTED","演示写入未提交");
        var latest=repository.workspace(lease.workspaceId(),true);repository.matches(latest,lease);repository.ready(latest);
        // JDBC counts cannot see deferred JPA inserts until flush. This is still the SAME locked
        // transaction, never an early commit. Check the lease again after flush, independently of
        // the caller's WorkspaceFence synchronization order.
        persistence.flush();
        if(count(operation,lease.orgId())>maximum)throw capacityExceeded();
        latest=repository.workspace(lease.workspaceId(),true);repository.matches(latest,lease);repository.ready(latest);
      }
      @Override public void afterCompletion(int status){reservation.finished=true;}
    });
    return reservation;
  }
  private long count(String operation,UUID org){return repository.jdbc.queryForObject("select count(*) from "+TABLES.get(operation)+" where org_id=?",Long.class,org);}
  private static AuthException capacityExceeded(){return DemoWorkspaceRepository.error(HttpStatus.TOO_MANY_REQUESTS,"DEMO_CAPACITY_EXCEEDED","演示工作区数据容量已满");}
  public final class Reservation implements AutoCloseable {
    private final DemoLease lease;private final String operation;
    private boolean committed,closed,finished;
    private Reservation(DemoLease lease,String operation){this.lease=lease;this.operation=operation;}
    public DemoLease lease(){return lease;}
    public String operation(){return operation;}
    public void commit(){if(closed || finished)throw new IllegalStateException("Reservation already closed");committed=true;}
    /** An uncommitted close makes the enclosing transaction fail; its DB lock releases on rollback. */
    @Override public void close(){closed=true;}
  }
}
