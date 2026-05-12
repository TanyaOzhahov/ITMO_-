package com.adapstory.personalization.application.service;

import com.adapstory.personalization.domain.model.WidgetDraft;
import com.adapstory.personalization.domain.port.outbound.LlmGatewayPort;
import com.adapstory.personalization.domain.port.outbound.VectorSearchPort;
import com.adapstory.personalization.domain.port.outbound.VectorSearchPort.ContextChunk;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Агент для ответов на организационные вопросы.
 * Java-порт OrganizationalAgent из ИИ_агент.ipynb.
 *
 * <p>Отвечает ТОЛЬКО из RAG-контекста курса, не придумывает.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrganizationalAgentAdapter {
  private final LlmGatewayPort llmGateway;
  private final VectorSearchPort vectorSearch;

  /**
   * Ответить на организационный вопрос из контекста курса.
   */
  public WidgetDraft respond(String question) {
    List<ContextChunk> chunks = vectorSearch.search(question, 5);

    if (chunks.isEmpty()) {
      log.info("No RAG context for organizational question: '{}'", question);
      return WidgetDraft.answerCard(
          "В материалах курса нет информации по этому вопросу. Уточните у преподавателя.");
    }

    String context = chunks.stream()
        .map(ContextChunk::text)
        .collect(Collectors.joining("\n\n"));

    String systemPrompt = """
        Студент курса задал организационный вопрос.
        Отвечай ТОЛЬКО на основе информации из материалов курса.
        Если информация есть — изложи её кратко и точно своими словами (2-4 предложения).
        Если информации нет — скажи: "В материалах курса нет информации по этому вопросу. \
        Уточните у преподавателя."
        Не придумывай ответы из общих знаний LLM.
        """;

    try {
      String answer = llmGateway.chatCompletion(
          systemPrompt,
          "Контекст курса:\n" + context + "\n\nВопрос: " + question,
          0.1, 400);
      return WidgetDraft.answerCard(answer);
    } catch (Exception e) {
      log.error("Organizational agent LLM call failed", e);
      return WidgetDraft.answerCard(
          "Не удалось обработать запрос. Попробуйте позже или обратитесь к преподавателю.");
    }
  }
}
