package com.adapstory.personalization.application.service;

import com.adapstory.personalization.domain.model.WidgetDraft;
import com.adapstory.personalization.domain.model.WidgetInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Projection Builder — конвертирует typed WidgetDraft в финальный DivKit JSON.
 *
 * <p>Архитектура: "LLM Outputs Drafts, Not Executable UI".
 * Draft → Policy validation → Projection (DivKit JSON) → WidgetInstance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionBuilder {
  private final ObjectMapper objectMapper;

  /**
   * Конвертировать WidgetDraft в WidgetInstance с финальным DivKit JSON.
   */
  public WidgetInstance buildProjection(
      WidgetDraft draft,
      UUID tenantId, UUID sessionId, UUID learnerId,
      String space, String interactionLevel) {

    String widgetId = "w-" + UUID.randomUUID().toString().substring(0, 8);

    JsonNode divData = switch (draft.widgetType()) {
      case "AskUserQuestion" -> buildAskUserQuestion(draft, widgetId, interactionLevel);
      case "ExplanationWidget" -> buildExplanation(draft);
      case "SmartLineAnswerCard" -> buildAnswerCard(draft);
      default -> {
        log.warn("Unknown widget type: {}", draft.widgetType());
        yield buildAnswerCard(draft);
      }
    };

    List<String> allowedActions = resolveAllowedActions(draft.widgetType());
    double rankScore = calculateRankScore(draft, interactionLevel);

    return WidgetInstance.builder()
        .id(UUID.randomUUID())
        .tenantId(tenantId)
        .sessionId(sessionId)
        .learnerId(learnerId)
        .widgetId(widgetId)
        .widgetType(draft.widgetType())
        .widgetName(resolveWidgetName(draft))
        .space(space)
        .state("PROJECTED")
        .interactionLevel(interactionLevel)
        .divData(divData)
        .sourcePlugin("adapstory.tutoring.socratic-tutor")
        .rankScore(rankScore)
        .ttlSeconds(300)
        .createdAt(Instant.now())
        .build();
  }

  // ─────────────────────────── DivKit builders ────────────────

  private JsonNode buildAskUserQuestion(WidgetDraft draft, String widgetId, String level) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "container");
    root.put("orientation", "vertical");

    ArrayNode children = root.putArray("children");

    // Question text
    ObjectNode questionText = objectMapper.createObjectNode();
    questionText.put("type", "text");
    questionText.put("text", draft.question());
    questionText.put("font_size", 16);
    questionText.put("font_weight", "bold");
    children.add(questionText);

    // Input elements by interaction level
    switch (level) {
      case "L1", "L2" -> addFreeTextInput(children);
      case "L3" -> {
        if (draft.options() != null && !draft.options().isEmpty()) {
          addMultipleChoice(children, draft.options());
        } else {
          addFreeTextInput(children);
        }
      }
      case "L4" -> {
        if (draft.options() != null && !draft.options().isEmpty()) {
          addMultipleChoice(children, draft.options());
        }
        addTextArea(children, "Объясните свой выбор:");
      }
      case "L5" -> {
        addFreeTextInput(children);
        addTextArea(children, "Полное объяснение:");
      }
      default -> addFreeTextInput(children);
    }

    // Submit button with custom_action
    ObjectNode button = objectMapper.createObjectNode();
    button.put("type", "button");
    button.put("text", "Ответить");

    ArrayNode actions = button.putArray("actions");
    ObjectNode action = objectMapper.createObjectNode();
    action.put("type", "custom_action");
    action.put("action_id", "smart_line:submit_answer");
    ObjectNode params = action.putObject("params");
    params.put("widgetId", widgetId);
    actions.add(action);

    children.add(button);

    return root;
  }

  private JsonNode buildExplanation(WidgetDraft draft) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "container");
    root.put("orientation", "vertical");

    ArrayNode children = root.putArray("children");

    ObjectNode text = objectMapper.createObjectNode();
    text.put("type", "text");
    text.put("text", draft.explanationText());
    text.put("font_size", 14);
    text.put("line_height", 22);
    children.add(text);

    return root;
  }

  private JsonNode buildAnswerCard(WidgetDraft draft) {
    String text = draft.explanationText() != null ? draft.explanationText() : draft.question();
    if (text == null) text = "";

    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "container");
    root.put("orientation", "vertical");

    ArrayNode children = root.putArray("children");

    ObjectNode textNode = objectMapper.createObjectNode();
    textNode.put("type", "text");
    textNode.put("text", text);
    textNode.put("font_size", 14);
    children.add(textNode);

    return root;
  }

  // ─────────────────────────── DivKit element helpers ─────────

  private void addFreeTextInput(ArrayNode children) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("type", "input");
    input.put("id", "learner_answer");
    input.put("hint_text", "Введите ваш ответ...");
    children.add(input);
  }

  private void addMultipleChoice(ArrayNode children, List<String> options) {
    ObjectNode container = objectMapper.createObjectNode();
    container.put("type", "container");
    container.put("orientation", "vertical");
    ArrayNode items = container.putArray("children");

    for (String option : options) {
      ObjectNode checkbox = objectMapper.createObjectNode();
      checkbox.put("type", "checkbox");
      checkbox.put("text", option);
      checkbox.put("value", option);
      items.add(checkbox);
    }
    children.add(container);
  }

  private void addTextArea(ArrayNode children, String placeholder) {
    ObjectNode textarea = objectMapper.createObjectNode();
    textarea.put("type", "textarea");
    textarea.put("hint_text", placeholder);
    textarea.put("min_lines", 3);
    children.add(textarea);
  }

  // ─────────────────────────── Helpers ────────────────────────

  private List<String> resolveAllowedActions(String widgetType) {
    return switch (widgetType) {
      case "AskUserQuestion" -> List.of("smart_line:submit_answer");
      case "ExplanationWidget" -> List.of();
      case "SmartLineAnswerCard" -> List.of();
      default -> List.of();
    };
  }

  private double calculateRankScore(WidgetDraft draft, String level) {
    double base = switch (draft.widgetType()) {
      case "AskUserQuestion" -> 0.90;
      case "ExplanationWidget" -> 0.85;
      case "SmartLineAnswerCard" -> 0.80;
      default -> 0.70;
    };
    // Higher interaction levels get slight boost
    double levelBoost = switch (level) {
      case "L3" -> 0.02;
      case "L4" -> 0.04;
      case "L5" -> 0.06;
      default -> 0.0;
    };
    return Math.min(1.0, base + levelBoost);
  }

  private String resolveWidgetName(WidgetDraft draft) {
    return switch (draft.widgetType()) {
      case "AskUserQuestion" -> "Practice Question";
      case "ExplanationWidget" -> "Explanation";
      case "SmartLineAnswerCard" -> "Answer";
      default -> "Widget";
    };
  }
}
