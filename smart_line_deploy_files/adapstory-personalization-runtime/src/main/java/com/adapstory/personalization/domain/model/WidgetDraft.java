package com.adapstory.personalization.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Typed draft, генерируемый LLM или rule-логикой.
 * НЕ является финальным DivKit JSON — передаётся в ProjectionBuilder.
 *
 * <p>Архитектура: "LLM Outputs Drafts, Not Executable UI".
 */
public record WidgetDraft(
    String widgetType,
    String pedagogyMode,
    String question,
    String inputMode,
    String followUpPolicy,
    String explanationText,
    List<String> options,
    Map<String, Object> metadata
) {

  public static WidgetDraft askUserQuestion(
      String question, String pedagogyMode, String inputMode, List<String> options) {
    return new WidgetDraft(
        "AskUserQuestion", pedagogyMode, question, inputMode, "evaluate_then_regenerate",
        null, options, Map.of());
  }

  public static WidgetDraft explanation(String text, String source) {
    return new WidgetDraft(
        "ExplanationWidget", null, null, null, null,
        text, List.of(), Map.of("source", source));
  }

  public static WidgetDraft answerCard(String text) {
    return new WidgetDraft(
        "SmartLineAnswerCard", null, null, null, null,
        text, List.of(), Map.of());
  }
}
