package com.adapstory.personalization.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Renders tutor text responses to DivKit JSON.
 * Generates L1-L5 interaction levels with button actions.
 * 
 * Рендерит текст ответов наставника в DivKit JSON.
 * Генерирует L1-L5 уровни взаимодействия с действиями кнопок.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WidgetLakeRenderer {
  private final ObjectMapper objectMapper;

  /**
   * Render AskUserQuestion widget (text input, options, or explanation).
   * Рендерить виджет AskUserQuestion.
   */
  public JsonNode renderAskUserQuestion(
      String questionText,
      List<String> options,
      String interactionLevel,
      String widgetId) {
    
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "container");
    root.put("orientation", "vertical");
    
    ArrayNode children = root.putArray("children");

    // Question text element
    ObjectNode textElement = objectMapper.createObjectNode();
    textElement.put("type", "text");
    textElement.put("text", questionText);
    textElement.put("font_size", 16);
    textElement.put("font_weight", "bold");
    children.add(textElement);

    // Interaction elements based on level
    switch (interactionLevel) {
      case "L1":
        addInputElement(children, "text");
        break;
      case "L2":
        if (options != null && !options.isEmpty()) {
          addSelectElement(children, options.subList(0, Math.min(2, options.size())));
        } else {
          addInputElement(children, "text");
        }
        break;
      case "L3":
        if (options != null && !options.isEmpty()) {
          addMultipleChoiceElement(children, options);
        } else {
          addInputElement(children, "text");
        }
        break;
      case "L4":
        if (options != null && !options.isEmpty()) {
          addMultipleChoiceElement(children, options);
        }
        addTextAreaElement(children, "Explain your choice:");
        break;
      case "L5":
        // Full skill launch
        addMultipleChoiceElement(children, options);
        addTextAreaElement(children, "Full explanation required:");
        break;
      default:
        addInputElement(children, "text");
    }

    // Submit button
    ObjectNode submitButton = objectMapper.createObjectNode();
    submitButton.put("type", "button");
    submitButton.put("text", "Submit Answer");
    ObjectNode action = submitButton.putObject("action");
    action.put("type", "smart_line:submit_answer");
    action.put("target", "submit");
    action.put("widget_id", widgetId);
    children.add(submitButton);

    log.debug("Rendered AskUserQuestion widget: level={}, options={}, widgetId={}", 
        interactionLevel, options != null ? options.size() : 0, widgetId);

    return root;
  }

  /**
   * Render ExplanationWidget.
   * Рендерить виджет ExplanationWidget.
   */
  public JsonNode renderExplanationWidget(String explanationText) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("type", "container");
    
    ArrayNode children = root.putArray("children");
    
    ObjectNode textElement = objectMapper.createObjectNode();
    textElement.put("type", "text");
    textElement.put("text", explanationText);
    textElement.put("font_size", 14);
    children.add(textElement);

    return root;
  }

  // ─────────────────────────────────────────────────────────
  // Helper methods for building DivKit elements
  // ─────────────────────────────────────────────────────────

  private void addInputElement(ArrayNode children, String inputType) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("type", "input");
    input.put("input_type", inputType);
    input.put("placeholder", "Your answer here...");
    children.add(input);
  }

  private void addSelectElement(ArrayNode children, List<String> options) {
    ObjectNode select = objectMapper.createObjectNode();
    select.put("type", "select");
    
    ArrayNode items = select.putArray("items");
    for (String option : options) {
      ObjectNode item = objectMapper.createObjectNode();
      item.put("text", option);
      item.put("value", option);
      items.add(item);
    }
    
    children.add(select);
  }

  private void addMultipleChoiceElement(ArrayNode children, List<String> options) {
    ObjectNode container = objectMapper.createObjectNode();
    container.put("type", "container");
    container.put("orientation", "vertical");
    
    ArrayNode choices = container.putArray("children");
    
    for (String option : options) {
      ObjectNode checkbox = objectMapper.createObjectNode();
      checkbox.put("type", "checkbox");
      checkbox.put("text", option);
      checkbox.put("value", option);
      choices.add(checkbox);
    }
    
    children.add(container);
  }

  private void addTextAreaElement(ArrayNode children, String placeholder) {
    ObjectNode textarea = objectMapper.createObjectNode();
    textarea.put("type", "textarea");
    textarea.put("input_type", "text");
    textarea.put("placeholder", placeholder);
    textarea.put("min_lines", 3);
    children.add(textarea);
  }
}
