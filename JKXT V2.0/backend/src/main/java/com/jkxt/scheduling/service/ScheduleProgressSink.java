package com.jkxt.scheduling.service;

import com.jkxt.scheduling.api.ScheduleDtos.ProgressEvent;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ScheduleProgressSink {

  private final Map<UUID, List<ProgressEvent>> buffers = new ConcurrentHashMap<>();
  private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public void publish(ProgressEvent event) {
    buffers.computeIfAbsent(event.runId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
    for (SseEmitter emitter : emitters.getOrDefault(event.runId(), List.of())) {
      try {
        emitter.send(SseEmitter.event()
            .name("progress")
            .id(String.valueOf(event.at().toEpochMilli()))
            .data(event));
      } catch (IOException | IllegalStateException ex) {
        emitter.completeWithError(ex);
      }
    }
  }

  public List<ProgressEvent> events(UUID runId) {
    return List.copyOf(buffers.getOrDefault(runId, List.of()));
  }

  public SseEmitter stream(UUID runId) {
    SseEmitter emitter = new SseEmitter(120_000L);
    emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> remove(runId, emitter));
    emitter.onTimeout(() -> remove(runId, emitter));
    emitter.onError(error -> remove(runId, emitter));
    for (ProgressEvent event : events(runId)) {
      try {
        emitter.send(SseEmitter.event()
            .name("progress")
            .id(String.valueOf(event.at().toEpochMilli()))
            .data(event));
      } catch (IOException | IllegalStateException ex) {
        emitter.completeWithError(ex);
        break;
      }
    }
    return emitter;
  }

  private void remove(UUID runId, SseEmitter emitter) {
    List<SseEmitter> list = emitters.get(runId);
    if (list == null) return;
    list.remove(emitter);
    if (list.isEmpty()) emitters.remove(runId);
  }
}
