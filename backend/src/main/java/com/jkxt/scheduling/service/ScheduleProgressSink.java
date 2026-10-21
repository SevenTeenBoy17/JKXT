package com.jkxt.scheduling.service;

import com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ScheduleProgressSink {

  // 迭代10 P2: 只保留最近 MAX_RETAINED_RUNS 个 run 的进度缓冲。此前 buffers 每个 run 一入永不删除
  // （remove() 只清 emitters，从不碰 buffers，也无任何调用方 remove buffers），长期运行的进程里随任务
  // 单调增长（每 run ~6 个 ProgressEvent）缓慢泄漏堆。访问序 LRU + removeEldestEntry 给一个硬上限，
  // 超出即淘汰最久未访问的 run 缓冲，彻底封住无界增长。
  private static final int MAX_RETAINED_RUNS = 512;

  private final Map<UUID, List<ProgressEvent>> buffers = Collections.synchronizedMap(
      new LinkedHashMap<UUID, List<ProgressEvent>>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, List<ProgressEvent>> eldest) {
          return size() > MAX_RETAINED_RUNS;
        }
      });
  private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

  // 迭代10 P2: 单把协调锁，让"缓冲追加 + 快照当前订阅者"(publish) 与"快照缓冲 + 注册订阅者"(stream) 互斥。
  // 于是任一进度事件要么已进了新订阅者的历史回放快照、要么被实时推给已注册的订阅者，绝不双发也不漏发
  // （此前 stream 先注册后回放，publish 若在其间触发会既实时推、又被回放快照带上 → 同一事件双发）。
  // 进度事件频率极低（每 run 数个），单锁竞争可忽略；两处的 emitter.send I/O 都在锁外执行，不会长占锁。
  private final Object coordinationLock = new Object();

  public void publish(ProgressEvent event) {
    List<SseEmitter> targets;
    synchronized (coordinationLock) {
      buffers.computeIfAbsent(event.runId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
      targets = new ArrayList<>(emitters.getOrDefault(event.runId(), List.of()));
    }
    for (SseEmitter emitter : targets) {
      sendOne(emitter, event);
    }
  }

  public List<ProgressEvent> events(UUID runId) {
    return List.copyOf(buffers.getOrDefault(runId, List.of()));
  }

  public SseEmitter stream(UUID runId) {
    SseEmitter emitter = new SseEmitter(120_000L);
    emitter.onCompletion(() -> remove(runId, emitter));
    emitter.onTimeout(() -> remove(runId, emitter));
    emitter.onError(error -> remove(runId, emitter));
    List<ProgressEvent> replay;
    synchronized (coordinationLock) {
      replay = events(runId);
      emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
    }
    for (ProgressEvent event : replay) {
      if (!sendOne(emitter, event)) {
        break;
      }
    }
    return emitter;
  }

  private boolean sendOne(SseEmitter emitter, ProgressEvent event) {
    try {
      emitter.send(SseEmitter.event()
          .name("progress")
          .id(String.valueOf(event.at().toEpochMilli()))
          .data(event));
      return true;
    } catch (IOException | IllegalStateException ex) {
      emitter.completeWithError(ex);
      return false;
    }
  }

  private void remove(UUID runId, SseEmitter emitter) {
    List<SseEmitter> list = emitters.get(runId);
    if (list == null) return;
    list.remove(emitter);
    if (list.isEmpty()) emitters.remove(runId);
  }
}
