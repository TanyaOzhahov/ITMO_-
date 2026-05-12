package com.adapstory.personalization.application.service;

import com.adapstory.personalization.domain.port.outbound.LlmGatewayPort;
import com.adapstory.personalization.domain.port.outbound.VectorSearchPort;
import com.adapstory.personalization.domain.port.outbound.VectorSearchPort.ContextChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Адаптер EvaluatorAgent — Java-порт из ИИ_агент.ipynb.
 * Оценивает ответ студента через LLM (не hardcoded stub).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluatorAdapter {
  private final LlmGatewayPort llmGateway;
  private final VectorSearchPort vectorSearch;
  private final ObjectMapper objectMapper;

  /**
   * Оценить ответ студента через LLM + RAG-контекст.
   */
  public EvaluationResult evaluate(String topic, String studentAnswer) {
    // Retrieve context for evaluation
    List<ContextChunk> chunks = vectorSearch.search(topic, 5);
    String context = chunks.stream()
        .map(ContextChunk::text)
        .collect(Collectors.joining("\n\n"));

    String systemPrompt = """
        You evaluate a student's answer against course materials.
        Respond with ONLY a valid JSON object, no markdown, no explanation.
        """;

    String userPrompt = """
        Question/Topic: %s
        Student's answer: %s
        Course context: %s

        Evaluate and return JSON:
        {
          "correct": true/false,
          "partial": true/false,
          "misconception": "description of misconception or empty string",
          "score": 0.0-1.0,
          "feedback": "brief feedback for the tutor (not for the student)"
        }
        """.formatted(topic, studentAnswer, context);

    try {
      String response = llmGateway.chatCompletion(systemPrompt, userPrompt, 0.0, 300);
      String cleaned = response.strip()
          .replaceAll("^```json\\s*", "")
          .replaceAll("^```\\s*", "")
          .replaceAll("\\s*```$", "");

      JsonNode json = objectMapper.readTree(cleaned);

      return new EvaluationResult(
          json.path("correct").asBoolean(false),
          json.path("partial").asBoolean(false),
          json.path("misconception").asText(""),
          json.path("score").asDouble(0.0),
          json.path("feedback").asText("")
      );
    } catch (Exception e) {
      log.error("LLM evaluation failed for topic={}", topic, e);
      return new EvaluationResult(false, false, "", 0.0,
          "Evaluation failed — LLM unavailable");
    }
  }

  public record EvaluationResult(
      boolean correct,
      boolean partial,
      String misconception,
      double score,
      String feedback
  ) {}
}
