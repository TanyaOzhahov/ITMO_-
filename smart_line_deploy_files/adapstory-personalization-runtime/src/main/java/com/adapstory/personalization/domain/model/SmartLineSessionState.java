package com.adapstory.personalization.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Состояние сессии Smart Line верхнего уровня.
 * Хранит active space, историю интентов, context scope.
 * learning_state подключается только при LEARNING_UNDERSTANDING интенте.
 *
 * <p>Redis key: widget-lake:tenant:{tenant_id}:learner:{learner_id}:session:{session_id}
 * <p>TTL: 1800 seconds (sliding window).
 */
@Getter
@Setter
@Builder
public class SmartLineSessionState {
  private UUID sessionId;
  private UUID tenantId;
  private UUID learnerId;

  @Builder.Default private String activeSpace = "LEARNING";
  private String lastIntent;

  @Builder.Default private List<String> intentHistory = new ArrayList<>();

  private ContextScope contextScope;
  private int lastWidgetVersion;

  private Instant createdAt;
  private Instant lastUpdatedAt;
  @Builder.Default private int ttlSeconds = 1800;

  // Learning state — attached only when intent = LEARNING_UNDERSTANDING
  private StudentState learningState;

  /**
   * Зафиксировать новый интент.
   */
  public void recordIntent(String intent) {
    this.lastIntent = intent;
    this.intentHistory.add(intent);
    this.lastUpdatedAt = Instant.now();
  }

  /**
   * Сменить активный Space.
   */
  public void switchSpace(String newSpace) {
    this.activeSpace = newSpace;
    this.lastUpdatedAt = Instant.now();
  }

  /**
   * Инкрементировать версию виджетов.
   */
  public int incrementWidgetVersion() {
    return ++this.lastWidgetVersion;
  }

  /**
   * Контекстная позиция студента в курсе.
   */
  public record ContextScope(
      UUID courseId,
      UUID lessonId,
      UUID pageId
  ) {}
}
