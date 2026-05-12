package com.adapstory.personalization.application.service;

import com.adapstory.personalization.domain.model.StudentState;
import com.adapstory.personalization.domain.model.TutoringStrategy;
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
 * Адаптер TutorOrchestrator — Java-порт логики из ИИ_агент.ipynb.
 *
 * <p>Реализует полный цикл: PlannerAgent → TutorAgent → WidgetDraft.
 * Все 5 стратегий: SOCRATIC, HINT, EXPLAIN, VERIFY, MASTERED.
 * RAG через VectorSearchPort (Qdrant).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TutorOrchestratorAdapter {
  private final LlmGatewayPort llmGateway;
  private final VectorSearchPort vectorSearch;

  private static final int RAG_TOP_K = 10;
  private static final double CHAT_TEMPERATURE = 0.3;
  private static final int MAX_TOKENS = 700;

  // ─────────────────────────── PlannerAgent ───────────────────

  /**
   * Выбрать стратегию обучения по состоянию студента.
   * Точный порт PlannerAgent.plan() из ИИ_агент.ipynb.
   */
  public TutoringStrategy selectStrategy(StudentState state) {
    // mastered
    if (state.getMasteryScore() >= 0.80) {
      return TutoringStrategy.MASTERED;
    }
    // verify after explain
    if (state.isExplanationUsed() && "EXPLAIN".equals(state.getLastStrategy())) {
      return TutoringStrategy.VERIFY;
    }
    // socratic — student hasn't tried or score < 0.5, attempts <= 1
    if (state.getAttempts() <= 1 && state.getMasteryScore() < 0.5) {
      return TutoringStrategy.SOCRATIC;
    }
    // hint — score < 0.6, attempts <= 3, or has misconceptions
    if (state.getMasteryScore() < 0.6 && state.getAttempts() <= 3) {
      return TutoringStrategy.HINT;
    }
    if (!state.getMisconceptions().isEmpty() && state.getAttempts() <= 3) {
      return TutoringStrategy.HINT;
    }
    // explain — attempts >= 3 or consecutive_wrong >= 2
    if (state.getAttempts() >= 3 || state.getConsecutiveWrong() >= 2) {
      return TutoringStrategy.EXPLAIN;
    }
    return TutoringStrategy.SOCRATIC;
  }

  // ─────────────────────────── TutorAgent ─────────────────────

  /**
   * Сгенерировать WidgetDraft для обучающего взаимодействия.
   * Выполняет RAG-поиск + LLM-генерацию по стратегии.
   */
  public TutorResult generateTutorDraft(StudentState state) {
    TutoringStrategy strategy = selectStrategy(state);

    if (strategy == TutoringStrategy.MASTERED) {
      log.info("Student mastered topic: topic={}, mastery={}",
          state.getTopic(), state.getMasteryScore());
      return new TutorResult(strategy, WidgetDraft.answerCard(
          "Отлично! Тема усвоена. Переходите к следующему заданию."), 0);
    }

    // RAG: retrieve context
    List<ContextChunk> chunks = vectorSearch.search(state.getTopic(), RAG_TOP_K);
    String contextText = buildContextText(chunks);

    if (chunks.isEmpty()) {
      log.warn("No RAG context found for topic: {}", state.getTopic());
      return new TutorResult(strategy, WidgetDraft.answerCard(
          "В материалах курса нет информации по данному вопросу."), 0);
    }

    // Build strategy-specific prompt
    String systemPrompt = buildSystemPrompt(strategy, state);
    String userPrompt = buildUserPrompt(strategy, state, contextText);

    // LLM call
    String tutorText;
    try {
      tutorText = llmGateway.chatCompletion(systemPrompt, userPrompt, CHAT_TEMPERATURE, MAX_TOKENS);
    } catch (Exception e) {
      log.error("LLM call failed for tutor generation: strategy={}", strategy, e);
      tutorText = "Попробуйте ещё раз или попросите подсказку.";
    }

    // Build WidgetDraft based on strategy
    WidgetDraft draft = buildDraft(strategy, tutorText);

    // Update state
    state.setLastStrategy(strategy.name());
    if (strategy == TutoringStrategy.EXPLAIN) {
      state.setExplanationUsed(true);
    }

    log.info("Tutor draft generated: strategy={}, topic={}", strategy, state.getTopic());
    return new TutorResult(strategy, draft, MAX_TOKENS);
  }

  // ─────────────────────────── Prompts ────────────────────────

  private String buildSystemPrompt(TutoringStrategy strategy, StudentState state) {
    String misconceptionsText = state.getMisconceptions().isEmpty()
        ? "нет"
        : String.join("; ", state.getMisconceptions());

    return """
        Ты ИИ-тьютор курса. Используй ТОЛЬКО информацию из контекста.
        Если информации нет — скажи: «В материалах курса нет информации по этому вопросу».

        Текущее состояние студента:
        - attempts: %d
        - consecutive_wrong: %d
        - mastery_score: %.2f
        - explanation_used: %s

        Заблуждения студента: %s
        """.formatted(
        state.getAttempts(), state.getConsecutiveWrong(),
        state.getMasteryScore(), state.isExplanationUsed(),
        misconceptionsText);
  }

  private String buildUserPrompt(TutoringStrategy strategy, StudentState state, String context) {
    String historyText = state.getDialogHistory().isEmpty()
        ? "История диалога пуста."
        : String.join("\n", state.getDialogHistory().subList(
            Math.max(0, state.getDialogHistory().size() - 10),
            state.getDialogHistory().size()));

    String instruction = switch (strategy) {
      case SOCRATIC -> """
          Задай ОДИН наводящий вопрос, который помогает студенту самому прийти к ответу.
          Не давай ответ и не перечисляй факты. Максимум — 2 предложения.""";
      case HINT -> """
          Дай ОДНУ небольшую подсказку, указывающую направление мысли.
          Не раскрывай полный ответ. Упомяни заблуждение студента, если оно есть.
          Максимум — 3 предложения.""";
      case EXPLAIN -> """
          Объясни тему развёрнуто и структурированно.
          Используй ТОЛЬКО контекст курса.
          В конце укажи источник: [Источник: SOURCE, стр. PAGE].
          Исправь заблуждения студента, если они есть.""";
      case VERIFY -> """
          Ты только что объяснил тему. Теперь задай студенту ОДИН короткий проверочный вопрос,
          чтобы убедиться, что он понял объяснение. Вопрос должен быть конкретным.""";
      case MASTERED -> "Тема усвоена.";
    };

    return """
        КОНТЕКСТ КУРСА:
        %s

        ТЕМА СТУДЕНТА: %s

        ИСТОРИЯ ДИАЛОГА:
        %s

        СТРАТЕГИЯ: %s
        ИНСТРУКЦИЯ:
        %s
        """.formatted(context, state.getTopic(), historyText, strategy.name(), instruction);
  }

  // ─────────────────────────── Draft builder ──────────────────

  private WidgetDraft buildDraft(TutoringStrategy strategy, String tutorText) {
    return switch (strategy) {
      case SOCRATIC -> WidgetDraft.askUserQuestion(
          tutorText, "socratic", "free_text", List.of());
      case HINT -> WidgetDraft.askUserQuestion(
          tutorText, "hint", "free_text", List.of());
      case VERIFY -> WidgetDraft.askUserQuestion(
          tutorText, "verify", "free_text", List.of());
      case EXPLAIN -> WidgetDraft.explanation(tutorText, "course-material");
      case MASTERED -> WidgetDraft.answerCard(tutorText);
    };
  }

  // ─────────────────────────── RAG helpers ────────────────────

  private String buildContextText(List<ContextChunk> chunks) {
    return chunks.stream()
        .map(c -> "SOURCE: %s\nPAGE: %s\n\nTEXT:\n%s".formatted(
            c.source(), c.page(), c.text()))
        .collect(Collectors.joining("\n\n---\n\n"));
  }

  // ─────────────────────────── Result DTO ─────────────────────

  public record TutorResult(
      TutoringStrategy strategy,
      WidgetDraft draft,
      int tokensUsed
  ) {}
}
