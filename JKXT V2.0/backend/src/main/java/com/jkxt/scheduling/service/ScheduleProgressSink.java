package com.jkxt.scheduling.service;

import com.jkxt.demo.DemoLease;
import com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent;
import com.jkxt.scheduling.service.ScheduleStreamAuthorization.Owner;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ScheduleProgressSink {
  private static final int MAX_RETAINED_RUNS=512,MAX_EVENTS=64,MAX_SUBSCRIPTIONS=128,MAX_PER_RUN=16;
  private final Object coordinationLock=new Object();
  private final LinkedHashMap<Owner,List<ProgressEvent>> buffers=new LinkedHashMap<>(64,0.75f,true);
  private final Set<Subscriber> subscriptions=new HashSet<>();
  private final ScheduleStreamAuthorization authorization;
  private final Executor sends;private final ScheduledExecutorService checks;
  private record Frame(ProgressEvent event) {}
  private static final class Subscriber {
    final SseEmitter emitter=new SseEmitter(120_000L);
    final ScheduleStreamAuthorization.Subscription identity;
    final ArrayDeque<Frame> queue=new ArrayDeque<>();
    boolean closed,draining;ScheduledFuture<?> check,lifetime;
    Subscriber(ScheduleStreamAuthorization.Subscription identity){this.identity=identity;}
  }
  public ScheduleProgressSink(ScheduleStreamAuthorization authorization,@Qualifier("scheduleStreamExecutor") Executor sends,
      @Qualifier("scheduleStreamChecks") ScheduledExecutorService checks) {this.authorization=authorization;this.sends=sends;this.checks=checks;}
  public void publish(ProgressEvent event){publish(event,authorization.formalOwner(event.runId()));}
  /** Only enqueue occurs under coordination; authorization and all network I/O happen outside it. */
  public void publish(ProgressEvent event,Owner owner) {
    if(!owner.runId().equals(event.runId()))throw new IllegalArgumentException("Progress ownership mismatch");
    List<Subscriber> wake=new ArrayList<>(),overflow=new ArrayList<>();
    synchronized(coordinationLock) {
      var buffer=buffers.computeIfAbsent(owner,ignored->new ArrayList<>());
      if(buffer.size()==MAX_EVENTS)buffer.removeFirst();buffer.add(event);
      while(buffers.size()>MAX_RETAINED_RUNS)buffers.remove(buffers.keySet().iterator().next());
      for(var subscriber:subscriptions)if(subscriber.identity.owner().equals(owner)) {
        if(subscriber.queue.size()==MAX_EVENTS)overflow.add(subscriber);
        else {subscriber.queue.add(new Frame(event));if(!subscriber.draining){subscriber.draining=true;wake.add(subscriber);}}
      }
    }
    overflow.forEach(this::close);wake.forEach(this::dispatch);
  }
  public SseEmitter stream(ScheduleStreamAuthorization.Subscription identity) {
    if(!authorization.allowed(identity))throw new IllegalStateException("Stream authorization expired");
    var subscriber=new Subscriber(identity);
    subscriber.emitter.onCompletion(()->close(subscriber));subscriber.emitter.onTimeout(()->close(subscriber));subscriber.emitter.onError(e->close(subscriber));
    synchronized(coordinationLock) {
      if(subscriptions.size()>=MAX_SUBSCRIPTIONS || subscriptions.stream().filter(s->s.identity.owner().equals(identity.owner())).count()>=MAX_PER_RUN)
        throw new com.jkxt.common.web.ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"SCHEDULE_STREAM_BUSY","进度连接已满，请稍后重试");
      // Replay is queued before registration, under the same lock as live enqueue.
      buffers.getOrDefault(identity.owner(),List.of()).forEach(e->subscriber.queue.add(new Frame(e)));
      if(subscriber.queue.isEmpty())subscriber.queue.add(new Frame(null));
      subscriptions.add(subscriber);subscriber.draining=true;
      try {
        subscriber.check=checks.scheduleWithFixedDelay(()->idle(subscriber),5,5,TimeUnit.SECONDS);
        subscriber.lifetime=checks.schedule(()->close(subscriber),120,TimeUnit.SECONDS);
      }catch(RejectedExecutionException stopped) {
        subscriber.closed=true;subscriptions.remove(subscriber);subscriber.queue.clear();
        if(subscriber.check!=null)subscriber.check.cancel(false);
        throw new com.jkxt.common.web.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"SCHEDULE_STREAM_UNAVAILABLE","进度连接暂不可用");
      }
    }
    dispatch(subscriber);return subscriber.emitter;
  }
  private void idle(Subscriber subscriber) {
    if(!authorization.allowed(subscriber.identity)){close(subscriber);return;}
    boolean wake=false,overflow=false;
    synchronized(coordinationLock) {
      if(subscriber.closed)return;
      if(subscriber.queue.size()==MAX_EVENTS)overflow=true;
      else {subscriber.queue.add(new Frame(null));if(!subscriber.draining){subscriber.draining=true;wake=true;}}
    }
    if(overflow)close(subscriber);else if(wake)dispatch(subscriber);
  }
  private void dispatch(Subscriber subscriber) {
    try{sends.execute(()->drain(subscriber));}catch(RejectedExecutionException full){close(subscriber);}
  }
  private void drain(Subscriber subscriber) {
    while(true) {
      Frame frame;
      synchronized(coordinationLock) {
        if(subscriber.closed)return;frame=subscriber.queue.poll();
        if(frame==null){subscriber.draining=false;return;}
      }
      if(!authorization.allowed(subscriber.identity)){close(subscriber);return;}
      synchronized(coordinationLock){if(subscriber.closed)return;}
      try {
        if(frame.event()==null)subscriber.emitter.send(SseEmitter.event().comment("heartbeat"));
        else subscriber.emitter.send(SseEmitter.event().name("progress").id(String.valueOf(frame.event().at().toEpochMilli())).data(frame.event()));
      }catch(IOException | RuntimeException failure){close(subscriber);return;}
    }
  }
  private void close(Subscriber subscriber) {
    synchronized(coordinationLock) {
      if(subscriber.closed)return;subscriber.closed=true;subscriptions.remove(subscriber);subscriber.queue.clear();
      if(subscriber.check!=null)subscriber.check.cancel(false);if(subscriber.lifetime!=null)subscriber.lifetime.cancel(false);
    }
    try{subscriber.emitter.complete();}catch(RuntimeException alreadyClosed){/* idempotent container completion */}
  }
  public void discardGeneration(DemoLease lease) {
    List<Subscriber> obsolete;
    synchronized(coordinationLock) {
      buffers.keySet().removeIf(owner->lease.equals(owner.lease()));
      obsolete=subscriptions.stream().filter(s->lease.equals(s.identity.owner().lease())).toList();
    }
    obsolete.forEach(this::close);
  }
  public int subscriptionCount(){synchronized(coordinationLock){return subscriptions.size();}}
  @jakarta.annotation.PreDestroy public void shutdown() {
    List<Subscriber> all;synchronized(coordinationLock){all=List.copyOf(subscriptions);}all.forEach(this::close);
  }
}
