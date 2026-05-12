package com.adapstory.personalization.application.service;

import com.adapstory.domain.DomainEvent;
import com.adapstory.personalization.domain.model.WidgetInstance;
import com.adapstory.starter.kafka.envelope.CloudEventEnvelopeConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Издатель событий Smart Line (формат CloudEvents 1.0).
 *
 * <p>Использует {@link CloudEventEnvelopeConverter} для формирования CE envelope.
 * CE type включает суффикс версии (.v1) — требование регламента.
 * correlation-id и request-id пробрасываются из MDC (требование регламента).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartLineEventPublisher {
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final CloudEventEnvelopeConverter cloudEventConverter;
  private final ObjectMapper objectMapper;

  private static final String SOURCE = "urn:adapstory:bc16:personalization-runtime";

  private static final String TOPIC_WIDGET_RENDERED = "smart-line.widget.rendered";
  private static final String TOPIC_STUDENT_ANSWERED = "smart-line.student.answered";
  private static final String TOPIC_STUDENT_MASTERED = "smart-line.student.mastered";
  private static final String TOPIC_SESSION_TERMINATED = "smart-line.session.terminated";

  /**
   * Опубликовать событие WidgetRendered.
   */
  public void publishWidgetRendered(UUID tenantId, WidgetInstance widget, String strategy) {
    Map<String, Object> data = Map.of(
        "widget_id", widget.getWidgetId(),
        "widget_type", widget.getWidgetType(),
        "space", widget.getSpace(),
        "interaction_level", widget.getInteractionLevel(),
        "source_plugin", widget.getSourcePlugin(),
        "strategy", strategy
    );

    publish(TOPIC_WIDGET_RENDERED,
        "com.adapstory.smart-line.widget.rendered.v1",
        "widget/" + widget.getWidgetId(),
        tenantId, data);
  }

  /**
   * Опубликовать событие StudentAnswered.
   */
  public void publishStudentAnswered(
      UUID tenantId, UUID sessionId, String widgetId, boolean isCorrect, double score) {

    Map<String, Object> data = Map.of(
        "session_id", sessionId.toString(),
        "widget_id", widgetId,
        "is_correct", isCorrect,
        "score", score
    );

    publish(TOPIC_STUDENT_ANSWERED,
        "com.adapstory.smart-line.student.answered.v1",
        "widget/" + widgetId,
        tenantId, data);
  }

  /**
   * Опубликовать событие StudentMastered.
   */
  public void publishStudentMastered(UUID tenantId, UUID sessionId, double masteryScore) {
    Map<String, Object> data = Map.of(
        "session_id", sessionId.toString(),
        "mastery_score", masteryScore
    );

    publish(TOPIC_STUDENT_MASTERED,
        "com.adapstory.smart-line.student.mastered.v1",
        "session/" + sessionId,
        tenantId, data);
  }

  /**
   * Опубликовать событие SessionTerminated.
   */
  public void publishSessionTerminated(UUID tenantId, UUID sessionId, String reason) {
    Map<String, Object> data = Map.of(
        "session_id", sessionId.toString(),
        "termination_reason", reason
    );

    publish(TOPIC_SESSION_TERMINATED,
        "com.adapstory.smart-line.session.terminated.v1",
        "session/" + sessionId,
        tenantId, data);
  }

  // ─────────────────────────── Internal ───────────────────────

  private void publish(String topic, String ceType, String subject,
                       UUID tenantId, Map<String, Object> data) {
    try {
      // Create a lightweight DomainEvent for CloudEventEnvelopeConverter
      SmartLineDomainEvent event = new SmartLineDomainEvent(ceType, subject);
      String dataJson = objectMapper.writeValueAsString(data);

      String envelope = cloudEventConverter.toCloudEventJson(
          event, dataJson, SOURCE, tenantId.toString());

      Message<String> message = MessageBuilder
          .withPayload(envelope)
          .setHeader(KafkaHeaders.TOPIC, topic)
          .setHeader(KafkaHeaders.KEY, tenantId.toString())
          .setHeader("tenant-id", tenantId.toString())
          .build();

      kafkaTemplate.send(message);
      log.debug("Published CloudEvent: topic={}, type={}", topic, ceType);
    } catch (Exception e) {
      log.error("Failed to publish CloudEvent: topic={}, type={}", topic, ceType, e);
    }
  }

  /**
   * Lightweight DomainEvent adapter for CloudEventEnvelopeConverter.
   */
  private static class SmartLineDomainEvent implements DomainEvent {
    private final UUID eventId = UUID.randomUUID();
    private final Instant occurredAt = Instant.now();
    private final String cloudEventType;
    private final String aggregateId;

    SmartLineDomainEvent(String cloudEventType, String aggregateId) {
      this.cloudEventType = cloudEventType;
      this.aggregateId = aggregateId;
    }

    @Override public UUID getEventId() { return eventId; }
    @Override public Instant getOccurredAt() { return occurredAt; }
    @Override public String cloudEventType() { return cloudEventType; }
    @Override public String getAggregateId() { return aggregateId; }
  }
}
