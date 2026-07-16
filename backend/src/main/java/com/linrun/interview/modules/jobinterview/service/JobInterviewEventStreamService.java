package com.linrun.interview.modules.jobinterview.service;

import com.linrun.interview.modules.jobinterview.config.JobInterviewProperties;
import com.linrun.interview.modules.jobinterview.dto.JobInterviewContracts.EventView;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 持久化事件轮询流；事件 ID 支持 Last-Event-ID 断线续传。 */
@Service
@RequiredArgsConstructor
public class JobInterviewEventStreamService {

  private final JobInterviewSessionPersistenceService sessionPersistence;
  private final JobInterviewViewAssembler viewAssembler;
  private final JobInterviewProperties properties;

  public List<EventView> replay(
      Long userId,
      String sessionId,
      long afterEventId
  ) {
    sessionPersistence.requireOwned(userId, sessionId);
    return sessionPersistence.listEvents(
            userId, sessionId, afterEventId, properties.getReconnectEventLimit())
        .stream().map(viewAssembler::event).toList();
  }

  public Flux<ServerSentEvent<EventView>> stream(
      Long userId,
      String sessionId,
      long afterEventId
  ) {
    sessionPersistence.requireOwned(userId, sessionId);
    AtomicLong cursor = new AtomicLong(Math.max(0L, afterEventId));
    return Flux.interval(Duration.ZERO, Duration.ofSeconds(1))
        .concatMap(tick -> Mono.fromCallable(() -> replay(
                userId, sessionId, cursor.get()))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(events -> {
              if (events.isEmpty()) {
                if (tick > 0 && tick % 15 == 0) {
                  return Flux.just(ServerSentEvent.<EventView>builder()
                      .comment("keepalive")
                      .build());
                }
                return Flux.empty();
              }
              return Flux.fromIterable(events).map(event -> {
                cursor.accumulateAndGet(event.eventId(), Math::max);
                return ServerSentEvent.<EventView>builder(event)
                    .id(String.valueOf(event.eventId()))
                    .event(event.eventType())
                    .build();
              });
            }));
  }
}
