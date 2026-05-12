package com.adapstory.personalization.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Smart Line render request DTO.
 * Запрос на рендеринг Smart Line виджета.
 */
public record SmartLineRenderRequest(
    @NotNull UUID sessionId,
    @NotNull UUID learnerId,
    @NotNull String space,
    String prompt,
    String topic,
    @Valid ContextScope contextScope
) {

  /**
   * Контекст текущей позиции студента в курсе.
   */
  public record ContextScope(
      UUID courseId,
      UUID lessonId,
      UUID pageId
  ) {}
}
