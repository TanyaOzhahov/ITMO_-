package com.adapstory.personalization.application.service;

import com.adapstory.personalization.api.dto.SmartLineRenderRequest;
import com.adapstory.personalization.api.dto.SmartLineRenderResponse;
import com.adapstory.personalization.api.dto.SmartLineRenderResponse.WidgetProjectionDto;
import com.adapstory.personalization.domain.model.SmartLineIntent;
import com.adapstory.personalization.domain.model.SmartLineSessionState;
import com.adapstory.personalization.domain.model.StudentState;
import com.adapstory.personalization.domain.model.TutoringStrategy;
import com.adapstory.personalization.domain.model.WidgetDraft;
import com.adapstory.personalization.domain.model.WidgetInstance;
import com.adapstory.personalization.infrastructure.cache.SmartLineSessionCache;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Главный сервис Smart Line — оркестрирует полный render-flow:
 *
 * <ol>
 *   <li>Load/create SmartLineSessionState (Redis)</li>
 *   <li>Intent Routing (rules-first + LLM fallback)</li>
 *   <li>Dispatch to agent by intent (Tutor, Organizational, etc.)</li>
 *   <li>WidgetDraft generation (typed draft, NOT final DivKit)</li>
 *   <li>ProjectionBuilder → WidgetInstance (final DivKit JSON)</li>
 *   <li>Cache update + Kafka event publish</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartLineService {
  private final SmartLineSessionCache sessionCache;
  private final IntentRouter intentRouter;
  private final TutorOrchestratorAdapter tutorAdapter;
  private final EvaluatorAdapter evaluatorAdapter;
  private final OrganizationalAgentAdapter organizationalAgent;
  private final ProjectionBuilder projectionBuilder;
  private final SmartLineEventPublisher eventPublisher;

  // ═══════════════════════════════════════════════════════════
  // RENDER
  // ═══════════════════════════════════════════════════════════

  /**
   * Главный render — точка входа для BFF.
   */
  public SmartLineRenderResponse render(UUID tenantId, SmartLineRenderRequest request) {
    UUID sessionId = request.sessionId();
    UUID learnerId = request.learnerId();
    String space = request.space();

    log.info("Smart Line render: tenant={}, session={}, space={}", tenantId, sessionId, space);

    // 1. Load or create session state
    SmartLineSessionState session = loadOrCreateSession(tenantId, learnerId, sessionId, request);

    // 2. Refresh sliding TTL
    sessionCache.refreshTTL(tenantId, learnerId, sessionId);

    // 3. Intent routing
    SmartLineIntent intent = intentRouter.route(request.prompt(), space);
    session.recordIntent(intent.name());

    log.info("Intent routed: intent={}, prompt='{}'", intent,
        truncate(request.prompt()));

    // 4. Dispatch to agent → WidgetDraft
    WidgetDraft draft = dispatchToAgent(intent, request, session);

    // 5. Select interaction level
    String interactionLevel = selectInteractionLevel(session, intent);

    // 6. ProjectionBuilder → WidgetInstance (final DivKit JSON)
    WidgetInstance widget = projectionBuilder.buildProjection(
        draft, tenantId, sessionId, learnerId, space, interactionLevel);

    // 7. Update session + cache
    session.incrementWidgetVersion();
    sessionCache.set(tenantId, learnerId, sessionId, session);

    // 8. Kafka event (async)
    String strategy = session.getLearningState() != null
        ? session.getLearningState().getLastStrategy()
        : intent.name();
    eventPublisher.publishWidgetRendered(tenantId, widget, strategy);

    // 9. Build response
    return new SmartLineRenderResponse(
        sessionId.toString(),
        space,
        intent.name(),
        Instant.now(),
        resolveSessionStatus(session),
        List.of(toDto(widget))
    );
  }

  // ═══════════════════════════════════════════════════════════
  // INTERACT (submit answer + generic interactions)
  // ═══════════════════════════════════════════════════════════

  /**
   * Обработать взаимодействие студента (submit answer, etc.).
   */
  public InteractionResponse interact(
      UUID tenantId, UUID learnerId, UUID sessionId,
      String widgetId, String actionId, String learnerAnswer) {

    log.info("Smart Line interact: session={}, widget={}, action={}",
        sessionId, widgetId, actionId);

    SmartLineSessionState session = sessionCache.get(tenantId, learnerId, sessionId);
    if (session == null) {
      throw new IllegalStateException("Session not found: " + sessionId);
    }

    // Evaluate answer via LLM
    StudentState learningState = session.getLearningState();
    if (learningState == null) {
      throw new IllegalStateException("No learning state for session: " + sessionId);
    }

    EvaluatorAdapter.EvaluationResult evaluation =
        evaluatorAdapter.evaluate(learningState.getTopic(), learnerAnswer);

    // Update learning state
    learningState.recordAttempt(evaluation.correct());
    learningState.setMasteryScore(
        Math.min(1.0, learningState.getMasteryScore() + calculateDelta(learningState, evaluation)));

    if (!evaluation.misconception().isBlank()) {
      learningState.addMisconception(evaluation.misconception());
    }

    learningState.addToHistory("Студент", learnerAnswer);

    // Kafka event
    eventPublisher.publishStudentAnswered(
        tenantId, sessionId, widgetId, evaluation.correct(), evaluation.score());

    // Check termination
    String sessionStatus = "ACTIVE";
    if (learningState.shouldTerminate()) {
      String reason = learningState.getTerminationReason();
      sessionStatus = "TERMINATED_" + reason.toUpperCase();
      eventPublisher.publishSessionTerminated(tenantId, sessionId, reason);
      if ("mastered".equals(reason)) {
        eventPublisher.publishStudentMastered(
            tenantId, sessionId, learningState.getMasteryScore());
      }
    }

    // Save updated session
    sessionCache.set(tenantId, learnerId, sessionId, session);

    log.info("Interaction processed: correct={}, score={}, mastery={}, status={}",
        evaluation.correct(), evaluation.score(), learningState.getMasteryScore(), sessionStatus);

    return new InteractionResponse(
        evaluation.correct(),
        evaluation.score(),
        evaluation.feedback(),
        selectInteractionLevel(session, SmartLineIntent.LEARNING_UNDERSTANDING),
        sessionStatus
    );
  }

  // ═══════════════════════════════════════════════════════════
  // GET SESSION
  // ═══════════════════════════════════════════════════════════

  /**
   * Получить текущее состояние сессии.
   */
  public SmartLineSessionState getSession(UUID tenantId, UUID learnerId, UUID sessionId) {
    return sessionCache.get(tenantId, learnerId, sessionId);
  }

  /**
   * Завершить сессию.
   */
  public void terminateSession(UUID tenantId, UUID learnerId, UUID sessionId) {
    sessionCache.delete(tenantId, learnerId, sessionId);
    eventPublisher.publishSessionTerminated(tenantId, sessionId, "user_terminated");
    log.info("Session terminated by user: session={}", sessionId);
  }

  // ═══════════════════════════════════════════════════════════
  // INTERNAL
  // ═══════════════════════════════════════════════════════════

  private SmartLineSessionState loadOrCreateSession(
      UUID tenantId, UUID learnerId, UUID sessionId, SmartLineRenderRequest request) {

    SmartLineSessionState session = sessionCache.get(tenantId, learnerId, sessionId);
    if (session != null) {
      log.debug("Loaded existing session: id={}", sessionId);
      return session;
    }

    SmartLineSessionState.ContextScope scope = null;
    if (request.contextScope() != null) {
      scope = new SmartLineSessionState.ContextScope(
          request.contextScope().courseId(),
          request.contextScope().lessonId(),
          request.contextScope().pageId()
      );
    }

    session = SmartLineSessionState.builder()
        .sessionId(sessionId)
        .tenantId(tenantId)
        .learnerId(learnerId)
        .activeSpace(request.space())
        .contextScope(scope)
        .createdAt(Instant.now())
        .lastUpdatedAt(Instant.now())
        .build();

    log.debug("Created new session: id={}", sessionId);
    return session;
  }

  private WidgetDraft dispatchToAgent(
      SmartLineIntent intent, SmartLineRenderRequest request, SmartLineSessionState session) {

    return switch (intent) {
      case LEARNING_UNDERSTANDING -> {
        // Initialize or get learning state
        StudentState learningState = session.getLearningState();
        if (learningState == null) {
          learningState = StudentState.builder()
              .sessionId(session.getSessionId())
              .learnerId(session.getLearnerId())
              .tenantId(session.getTenantId())
              .topic(request.topic() != null ? request.topic() : request.prompt())
              .space(request.space())
              .sessionStartedAt(Instant.now())
              .lastUpdated(Instant.now())
              .build();
          session.setLearningState(learningState);
        }

        TutorOrchestratorAdapter.TutorResult result = tutorAdapter.generateTutorDraft(learningState);
        yield result.draft();
      }

      case ORGANIZATIONAL -> organizationalAgent.respond(request.prompt());

      case NAVIGATION_DISCOVERY ->
          WidgetDraft.answerCard("Используйте каталог для поиска курсов и материалов.");

      case PROGRESS_REFLECTION ->
          WidgetDraft.answerCard("Ваш прогресс доступен в разделе Аналитика.");

      case ACCOUNT_PROFILE ->
          WidgetDraft.answerCard("Настройки профиля доступны в разделе Профиль.");

      case UNSUPPORTED ->
          WidgetDraft.answerCard("Не удалось определить ваш запрос. Попробуйте переформулировать.");
    };
  }

  private String selectInteractionLevel(SmartLineSessionState session, SmartLineIntent intent) {
    if (intent != SmartLineIntent.LEARNING_UNDERSTANDING || session.getLearningState() == null) {
      return "L1";
    }
    StudentState ls = session.getLearningState();
    if (ls.getMasteryScore() >= 0.80) return "L5";
    if (ls.getMasteryScore() >= 0.60 && ls.getAttempts() >= 3) return "L4";
    if (ls.getMasteryScore() >= 0.40 && ls.getAttempts() >= 2) return "L3";
    if (ls.getMasteryScore() >= 0.20) return "L2";
    return "L1";
  }

  private double calculateDelta(StudentState state, EvaluatorAdapter.EvaluationResult eval) {
    if (!eval.correct()) return 0.0;
    String level = state.getCurrentInteractionLevel();
    if (level == null) level = "L1";
    return switch (level) {
      case "L1" -> 0.35;
      case "L2" -> 0.25;
      case "L3" -> 0.20;
      case "L4" -> 0.15;
      case "L5" -> 0.05;
      default -> 0.10;
    };
  }

  private String resolveSessionStatus(SmartLineSessionState session) {
    if (session.getLearningState() != null && session.getLearningState().shouldTerminate()) {
      return "TERMINATED_" + session.getLearningState().getTerminationReason().toUpperCase();
    }
    return "ACTIVE";
  }

  private WidgetProjectionDto toDto(WidgetInstance w) {
    return new WidgetProjectionDto(
        w.getWidgetId(), w.getWidgetType(), w.getWidgetName(),
        w.getSpace(), w.getInteractionLevel(), w.getSourcePlugin(),
        w.getRankScore(), w.getTtlSeconds(),
        List.of("smart_line:submit_answer"),
        null, w.getDivData()
    );
  }

  private String truncate(String text) {
    if (text == null) return "";
    return text.length() > 80 ? text.substring(0, 80) + "..." : text;
  }

  // ─────────────────────────── Response DTOs ──────────────────

  public record InteractionResponse(
      boolean correct,
      double score,
      String feedback,
      String nextInteractionLevel,
      String sessionStatus
  ) {}
}
