package com.adapstory.personalization.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Smart Line render response DTO.
 * Ответ с отрендеренными виджетами Widget Lake.
 */
public record SmartLineRenderResponse(
    String sessionId,
    String space,
    String intent,
    Instant generatedAt,
    String sessionStatus,
    List<WidgetProjectionDto> widgets
) {

  /**
   * Widget projection DTO (matches WidgetLakeItem TypeScript interface).
   * Проекция виджета для фронтенда.
   */
  public record WidgetProjectionDto(
      String widgetId,
      String widgetType,
      String name,
      String space,
      String interactionLevel,
      String sourcePlugin,
      double rankScore,
      int ttlSeconds,
      List<String> allowedActions,
      JsonNode payload,
      JsonNode divData
  ) {}
}
