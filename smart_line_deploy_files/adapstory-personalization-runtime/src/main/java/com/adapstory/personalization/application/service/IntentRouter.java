package com.adapstory.personalization.application.service;

import com.adapstory.personalization.domain.model.SmartLineIntent;
import com.adapstory.personalization.domain.port.outbound.LlmGatewayPort;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Intent Router — rules-first маршрутизация запросов Smart Line.
 * Аналог QuestionTypeClassifier из ИИ_агент.ipynb, расширенный до 6 интентов.
 *
 * <p>Порядок:
 * 1. Keyword rules (детерминированно, без LLM)
 * 2. LLM-fallback classification (если правила не сработали)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {
  private final LlmGatewayPort llmGateway;

  private static final Set<String> ORGANIZATIONAL_KEYWORDS = Set.of(
      "домашка", "задание", "дедлайн", "обратная связь", "преподаватель",
      "расписание", "оценки", "платформа", "проверка", "сдача", "срок",
      "вебинар", "чат", "ментор", "эксперт", "формат сдачи",
      "homework", "deadline", "schedule", "grade", "feedback", "submission"
  );

  private static final Set<String> NAVIGATION_KEYWORDS = Set.of(
      "найти", "каталог", "курс", "подписка", "купить", "рекомендации",
      "подобрать", "search", "catalog", "browse", "recommend", "find course"
  );

  private static final Set<String> PROGRESS_KEYWORDS = Set.of(
      "прогресс", "статистика", "достижения", "рейтинг", "аналитика",
      "сколько прошёл", "мои результаты",
      "progress", "analytics", "achievements", "stats", "my results"
  );

  private static final Set<String> ACCOUNT_KEYWORDS = Set.of(
      "профиль", "настройки", "пароль", "сертификат", "аккаунт", "подписка",
      "profile", "settings", "password", "certificate", "account"
  );

  /**
   * Определить интент запроса студента.
   *
   * @param prompt  текст запроса от студента
   * @param space   активный Space (контекст)
   * @return        канонический интент
   */
  public SmartLineIntent route(String prompt, String space) {
    if (prompt == null || prompt.isBlank()) {
      return SmartLineIntent.UNSUPPORTED;
    }

    String lower = prompt.toLowerCase().trim();

    // 1. Keyword rules
    SmartLineIntent keywordResult = classifyByKeywords(lower);
    if (keywordResult != null) {
      log.info("Intent resolved by keywords: intent={}, prompt='{}'", keywordResult, truncate(prompt));
      return keywordResult;
    }

    // 2. Space-based heuristic
    SmartLineIntent spaceHint = hintFromSpace(space);

    // 3. LLM fallback
    try {
      SmartLineIntent llmResult = classifyByLlm(prompt);
      log.info("Intent resolved by LLM: intent={}, prompt='{}'", llmResult, truncate(prompt));
      return llmResult;
    } catch (Exception e) {
      log.warn("LLM intent classification failed, falling back to space hint: {}", e.getMessage());
      return spaceHint != null ? spaceHint : SmartLineIntent.LEARNING_UNDERSTANDING;
    }
  }

  private SmartLineIntent classifyByKeywords(String lower) {
    if (containsAny(lower, ORGANIZATIONAL_KEYWORDS)) return SmartLineIntent.ORGANIZATIONAL;
    if (containsAny(lower, NAVIGATION_KEYWORDS)) return SmartLineIntent.NAVIGATION_DISCOVERY;
    if (containsAny(lower, PROGRESS_KEYWORDS)) return SmartLineIntent.PROGRESS_REFLECTION;
    if (containsAny(lower, ACCOUNT_KEYWORDS)) return SmartLineIntent.ACCOUNT_PROFILE;
    return null;
  }

  private SmartLineIntent hintFromSpace(String space) {
    if (space == null) return null;
    return switch (space.toUpperCase()) {
      case "LEARNING" -> SmartLineIntent.LEARNING_UNDERSTANDING;
      case "ANALYTICS" -> SmartLineIntent.PROGRESS_REFLECTION;
      case "MARKETPLACE" -> SmartLineIntent.NAVIGATION_DISCOVERY;
      case "PROFILE" -> SmartLineIntent.ACCOUNT_PROFILE;
      case "ONBOARDING" -> SmartLineIntent.NAVIGATION_DISCOVERY;
      default -> null;
    };
  }

  private SmartLineIntent classifyByLlm(String prompt) {
    String systemPrompt = """
        You are an intent classifier for a student learning platform.
        Classify the student's message into exactly one category.

        Categories:
        - ORGANIZATIONAL: questions about course process (homework, deadlines, feedback, schedule, grading)
        - LEARNING_UNDERSTANDING: questions about course content (concepts, terms, explanations)
        - NAVIGATION_DISCOVERY: looking for courses, catalog browsing, recommendations
        - PROGRESS_REFLECTION: progress, achievements, analytics, study reflection
        - ACCOUNT_PROFILE: profile settings, certificates, account management
        - UNSUPPORTED: completely unrelated to the platform

        Respond with ONLY the category name, nothing else.
        """;

    String response = llmGateway.chatCompletion(systemPrompt, prompt, 0.0, 30);
    String cleaned = response.trim().toUpperCase().replace(" ", "_");

    try {
      return SmartLineIntent.valueOf(cleaned);
    } catch (IllegalArgumentException e) {
      log.warn("LLM returned unknown intent: '{}', defaulting to LEARNING_UNDERSTANDING", cleaned);
      return SmartLineIntent.LEARNING_UNDERSTANDING;
    }
  }

  private boolean containsAny(String text, Set<String> keywords) {
    for (String keyword : keywords) {
      if (text.contains(keyword)) return true;
    }
    return false;
  }

  private String truncate(String text) {
    return text.length() > 80 ? text.substring(0, 80) + "..." : text;
  }
}
