package com.adapstory.personalization.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Widget instance during Smart Line session.
 * Immutable after creation (DivKit JSON cannot be changed).
 * Audit trail in bc19_widget_instance_audit table.
 * 
 * Экземпляр виджета во время сессии Smart Line.
 * Неизменяемый после создания (DivKit JSON не может быть изменён).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetInstance {
  private UUID id;
  private UUID tenantId;
  private UUID sessionId;
  private UUID learnerId;

  // Widget identity
  private String widgetId; // ask-user-question-72e8c
  private String widgetType; // AskUserQuestion, ExplanationWidget, etc.
  private String widgetName;

  // Context
  private String space; // LEARNING, ANALYTICS, etc.
  private String state; // CREATED, RENDERED, INTERACTED, ARCHIVED
  private String interactionLevel; // L1-L5

  // DivKit JSON (immutable)
  private JsonNode divData;
  private String divDataHash; // SHA256 for deduplication

  // Metadata
  private String sourcePlugin;
  private Double rankScore; // 0.00-1.00
  private Integer ttlSeconds;
  private JsonNode payload; // plugin-specific data

  // Timestamps
  private Instant createdAt;
  private Instant expiredAt;
  private Instant updatedAt;

  /**
   * Transition state to RENDERED.
   * Переход в состояние RENDERED.
   */
  public void markRendered() {
    this.state = "RENDERED";
    this.updatedAt = Instant.now();
  }

  /**
   * Transition state to INTERACTED.
   * Переход в состояние INTERACTED.
   */
  public void markInteracted() {
    this.state = "INTERACTED";
    this.updatedAt = Instant.now();
  }

  /**
   * Check if widget has expired (TTL).
   * Проверить, истёк ли TTL виджета.
   */
  public boolean isExpired() {
    if (expiredAt == null) {
      long createdEpoch = createdAt.getEpochSecond();
      long currentEpoch = Instant.now().getEpochSecond();
      return (currentEpoch - createdEpoch) > ttlSeconds;
    }
    return Instant.now().isAfter(expiredAt);
  }
}
