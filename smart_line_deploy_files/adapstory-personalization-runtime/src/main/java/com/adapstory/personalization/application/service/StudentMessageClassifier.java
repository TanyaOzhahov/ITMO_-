package com.adapstory.personalization.application.service;

import com.adapstory.personalization.domain.model.StudentMessageIntent;
import com.adapstory.personalization.domain.port.outbound.LlmGatewayPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Классификатор намерений сообщения студента в обучающем диалоге.
 * Java-порт IntentClassifier из ИИ_агент.ipynb.
 *
 * <p>Определяет: answer, chat, question, off_topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentMessageClassifier {
  private final LlmGatewayPort llmGateway;

  /**
   * Классифицировать сообщение студента в контексте обучающего диалога.
   */
  public StudentMessageIntent classify(String studentMessage, String tutorLastMessage, String topic) {
    String systemPrompt = """
        You classify student messages in a tutor-student dialog.
        Respond with ONLY one word: answer, chat, question, or off_topic.
        """;

    String userPrompt = """
        Topic: %s
        Tutor's last message: %s
        Student's message: %s

        Intent categories:
        - answer: student attempts to answer the tutor's question (even partially or incorrectly)
        - chat: conversational message (thanks, ok, understood, etc.)
        - question: student asks their own question about the course topic
        - off_topic: message unrelated to the learning topic
        """.formatted(topic, tutorLastMessage, studentMessage);

    try {
      String response = llmGateway.chatCompletion(systemPrompt, userPrompt, 0.0, 10);
      String cleaned = response.strip().toLowerCase().replace("\"", "");
      return switch (cleaned) {
        case "answer" -> StudentMessageIntent.ANSWER;
        case "chat" -> StudentMessageIntent.CHAT;
        case "question" -> StudentMessageIntent.QUESTION;
        case "off_topic" -> StudentMessageIntent.OFF_TOPIC;
        default -> {
          log.warn("Unknown student message intent: '{}', defaulting to ANSWER", cleaned);
          yield StudentMessageIntent.ANSWER;
        }
      };
    } catch (Exception e) {
      log.warn("Student message classification failed: {}", e.getMessage());
      return StudentMessageIntent.ANSWER;
    }
  }
}
