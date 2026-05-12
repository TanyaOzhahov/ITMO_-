package com.adapstory.personalization.domain.port.outbound;

import java.util.List;

/**
 * Порт для вызова LLM Gateway (ChatGPT и др.).
 * Реализация — в infrastructure-слое через HTTP/Feign.
 */
public interface LlmGatewayPort {

  /**
   * Выполнить chat completion с заданным system prompt и user message.
   *
   * @param systemPrompt  системный промпт (роль, контекст)
   * @param userMessage   сообщение пользователя
   * @param temperature   температура генерации
   * @param maxTokens     максимальное количество токенов ответа
   * @return              сгенерированный текст ответа
   */
  String chatCompletion(String systemPrompt, String userMessage,
                        double temperature, int maxTokens);

  /**
   * Получить embedding для текста (для RAG-поиска).
   */
  List<Float> embed(String text);
}
