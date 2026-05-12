package com.adapstory.personalization.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Модель состояния студента во время обучающей сессии Smart Line.
 * Кэшируется в Redis с TTL 1800s (скользящее окно).
 *
 * <p>Маппит все поля из TutorOrchestrator (ИИ_агент.ipynb):
 * topic, attempts, masteryScore, misconceptions, history,
 * lastStrategy, explanationUsed, consecutiveWrong, consecutiveChat.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
public class StudentState {
  private UUID sessionId;
  private UUID learnerId;
  private UUID tenantId;

  // Topic & context
  private String topic;
  private String space;

  // Performance metrics (from ИИ_агент.ipynb StudentState)
  @Builder.Default private int attempts = 0;
  @Builder.Default private int consecutiveWrong = 0;
  @Builder.Default private int consecutiveChat = 0;
  @Builder.Default private double masteryScore = 0.0;

  // Strategy tracking
  private String lastStrategy;
  @Builder.Default private boolean explanationUsed = false;

  // Learning insights
  @Builder.Default private List<String> misconceptions = new ArrayList<>();
  @Builder.Default private List<String> dialogHistory = new ArrayList<>();
  @Builder.Default private List<InteractionRecord> interactionRecords = new ArrayList<>();

  // Session metadata
  private Instant lastUpdated;
  private Instant sessionStartedAt;

  // State transitions
  private String currentInteractionLevel;

  // Manual all-args constructor (Lombok @AllArgsConstructor запрещён регламентом)
  @Builder
  public StudentState(
      UUID sessionId, UUID learnerId, UUID tenantId,
      String topic, String space,
      int attempts, int consecutiveWrong, int consecutiveChat, double masteryScore,
      String lastStrategy, boolean explanationUsed,
      List<String> misconceptions, List<String> dialogHistory,
      List<InteractionRecord> interactionRecords,
      Instant lastUpdated, Instant sessionStartedAt,
      String currentInteractionLevel) {
    this.sessionId = sessionId;
    this.learnerId = learnerId;
    this.tenantId = tenantId;
    this.topic = topic;
    this.space = space;
    this.attempts = attempts;
    this.consecutiveWrong = consecutiveWrong;
    this.consecutiveChat = consecutiveChat;
    this.masteryScore = masteryScore;
    this.lastStrategy = lastStrategy;
    this.explanationUsed = explanationUsed;
    this.misconceptions = misconceptions != null ? misconceptions : new ArrayList<>();
    this.dialogHistory = dialogHistory != null ? dialogHistory : new ArrayList<>();
    this.interactionRecords = interactionRecords != null ? interactionRecords : new ArrayList<>();
    this.lastUpdated = lastUpdated;
    this.sessionStartedAt = sessionStartedAt;
    this.currentInteractionLevel = currentInteractionLevel;
  }

  /**
   * Зафиксировать попытку ответа студента.
   */
  public void recordAttempt(boolean isCorrect) {
    this.attempts++;
    if (isCorrect) {
      this.consecutiveWrong = 0;
    } else {
      this.consecutiveWrong++;
    }
    this.consecutiveChat = 0;
    this.lastUpdated = Instant.now();
  }

  /**
   * Зафиксировать «болтовню» — студент уклонился от ответа.
   */
  public void recordChat() {
    this.consecutiveChat++;
    this.lastUpdated = Instant.now();
  }

  /**
   * Добавить заблуждение, если его ещё нет в списке.
   */
  public void addMisconception(String misconception) {
    if (misconception != null && !misconception.isBlank()
        && !this.misconceptions.contains(misconception)) {
      this.misconceptions.add(misconception);
    }
  }

  /**
   * Добавить реплику в историю диалога.
   */
  public void addToHistory(String role, String text) {
    this.dialogHistory.add(role + ": " + text);
  }

  /**
   * Проверить, должна ли сессия быть завершена.
   */
  public boolean shouldTerminate() {
    return masteryScore >= 0.80
        || consecutiveWrong >= 3
        || attempts >= 6;
  }

  /**
   * Определить причину завершения.
   */
  public String getTerminationReason() {
    if (masteryScore >= 0.80) return "mastered";
    if (consecutiveWrong >= 3) return "max_consecutive_wrong";
    if (attempts >= 6) return "max_attempts";
    return null;
  }

  /**
   * Проверить, уклоняется ли студент от ответов (>=2 chat подряд).
   */
  public boolean isEvadingAnswers() {
    return consecutiveChat >= 2;
  }

  /**
   * Запись единичного взаимодействия.
   */
  public record InteractionRecord(
      String interactionLevel,
      boolean correct,
      double score,
      String strategy,
      Instant timestamp
  ) {}
}
