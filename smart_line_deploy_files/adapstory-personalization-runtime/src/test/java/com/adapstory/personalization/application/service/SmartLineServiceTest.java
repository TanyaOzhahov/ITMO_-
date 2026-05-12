package com.adapstory.personalization.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.adapstory.personalization.api.dto.SmartLineRenderRequest;
import com.adapstory.personalization.api.dto.SmartLineRenderResponse;
import com.adapstory.personalization.domain.model.SmartLineIntent;
import com.adapstory.personalization.domain.model.SmartLineSessionState;
import com.adapstory.personalization.domain.model.StudentState;
import com.adapstory.personalization.domain.model.TutoringStrategy;
import com.adapstory.personalization.domain.model.WidgetDraft;
import com.adapstory.personalization.domain.model.WidgetInstance;
import com.adapstory.personalization.infrastructure.cache.SmartLineSessionCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for SmartLineService.
 * Covers: session creation, intent routing, agent dispatch, interaction flow.
 */
@ExtendWith(MockitoExtension.class)
class SmartLineServiceTest {

  @Mock private SmartLineSessionCache sessionCache;
  @Mock private IntentRouter intentRouter;
  @Mock private TutorOrchestratorAdapter tutorAdapter;
  @Mock private EvaluatorAdapter evaluatorAdapter;
  @Mock private OrganizationalAgentAdapter organizationalAgent;
  @Mock private ProjectionBuilder projectionBuilder;
  @Mock private SmartLineEventPublisher eventPublisher;

  @InjectMocks private SmartLineService service;

  private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID LEARNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    when(sessionCache.get(eq(TENANT_ID), eq(LEARNER_ID), eq(SESSION_ID))).thenReturn(null);
  }

  // ─────────────────────────── RENDER ─────────────────────────

  @Test
  @DisplayName("should_create_new_session_when_no_existing_session")
  void should_create_new_session_when_no_existing_session() {
    // Arrange
    SmartLineRenderRequest request = new SmartLineRenderRequest(
        SESSION_ID, LEARNER_ID, "LEARNING", "Что такое юнит-экономика?", "unit-economics", null);

    when(intentRouter.route(anyString(), anyString()))
        .thenReturn(SmartLineIntent.LEARNING_UNDERSTANDING);

    WidgetDraft draft = WidgetDraft.askUserQuestion("question?", "socratic", "free_text", List.of());
    when(tutorAdapter.generateTutorDraft(any(StudentState.class)))
        .thenReturn(new TutorOrchestratorAdapter.TutorResult(TutoringStrategy.SOCRATIC, draft, 100));

    WidgetInstance widget = buildWidget("w-test01", "AskUserQuestion", "LEARNING", "L1");
    when(projectionBuilder.buildProjection(any(), any(), any(), any(), anyString(), anyString()))
        .thenReturn(widget);

    // Act
    SmartLineRenderResponse response = service.render(TENANT_ID, request);

    // Assert
    assertThat(response.sessionStatus()).isEqualTo("ACTIVE");
    assertThat(response.intent()).isEqualTo("LEARNING_UNDERSTANDING");
    assertThat(response.widgets()).hasSize(1);
    assertThat(response.widgets().get(0).widgetType()).isEqualTo("AskUserQuestion");

    verify(sessionCache).set(eq(TENANT_ID), eq(LEARNER_ID), eq(SESSION_ID), any());
    verify(eventPublisher).publishWidgetRendered(eq(TENANT_ID), any(), anyString());
  }

  @Test
  @DisplayName("should_route_organizational_intent_when_keywords_match")
  void should_route_organizational_intent_when_keywords_match() {
    // Arrange
    SmartLineRenderRequest request = new SmartLineRenderRequest(
        SESSION_ID, LEARNER_ID, "PROFILE", "Когда дедлайн по домашке?", null, null);

    when(intentRouter.route(anyString(), anyString()))
        .thenReturn(SmartLineIntent.ORGANIZATIONAL);

    WidgetDraft draft = WidgetDraft.answerCard("Дедлайн — 20 апреля.");
    when(organizationalAgent.respond(anyString())).thenReturn(draft);

    WidgetInstance widget = buildWidget("w-test02", "SmartLineAnswerCard", "PROFILE", "L1");
    when(projectionBuilder.buildProjection(any(), any(), any(), any(), anyString(), anyString()))
        .thenReturn(widget);

    // Act
    SmartLineRenderResponse response = service.render(TENANT_ID, request);

    // Assert
    assertThat(response.intent()).isEqualTo("ORGANIZATIONAL");
    verify(organizationalAgent).respond(anyString());
  }

  @Test
  @DisplayName("should_reuse_existing_session_when_cache_hit")
  void should_reuse_existing_session_when_cache_hit() {
    // Arrange
    SmartLineSessionState existingSession = SmartLineSessionState.builder()
        .sessionId(SESSION_ID)
        .tenantId(TENANT_ID)
        .learnerId(LEARNER_ID)
        .activeSpace("LEARNING")
        .createdAt(Instant.now().minusSeconds(60))
        .lastUpdatedAt(Instant.now().minusSeconds(30))
        .build();

    when(sessionCache.get(eq(TENANT_ID), eq(LEARNER_ID), eq(SESSION_ID)))
        .thenReturn(existingSession);

    SmartLineRenderRequest request = new SmartLineRenderRequest(
        SESSION_ID, LEARNER_ID, "LEARNING", "topic?", "topic", null);

    when(intentRouter.route(anyString(), anyString()))
        .thenReturn(SmartLineIntent.LEARNING_UNDERSTANDING);

    WidgetDraft draft = WidgetDraft.askUserQuestion("q?", "socratic", "free_text", List.of());
    when(tutorAdapter.generateTutorDraft(any()))
        .thenReturn(new TutorOrchestratorAdapter.TutorResult(TutoringStrategy.SOCRATIC, draft, 50));

    WidgetInstance widget = buildWidget("w-test03", "AskUserQuestion", "LEARNING", "L1");
    when(projectionBuilder.buildProjection(any(), any(), any(), any(), anyString(), anyString()))
        .thenReturn(widget);

    // Act
    SmartLineRenderResponse response = service.render(TENANT_ID, request);

    // Assert
    assertThat(response.sessionStatus()).isEqualTo("ACTIVE");
    verify(sessionCache).refreshTTL(eq(TENANT_ID), eq(LEARNER_ID), eq(SESSION_ID));
  }

  // ─────────────────────────── INTERACT ───────────────────────

  @Test
  @DisplayName("should_evaluate_correct_answer_and_update_mastery")
  void should_evaluate_correct_answer_and_update_mastery() {
    // Arrange
    StudentState learningState = StudentState.builder()
        .sessionId(SESSION_ID)
        .learnerId(LEARNER_ID)
        .tenantId(TENANT_ID)
        .topic("unit-economics")
        .currentInteractionLevel("L1")
        .build();

    SmartLineSessionState session = SmartLineSessionState.builder()
        .sessionId(SESSION_ID)
        .tenantId(TENANT_ID)
        .learnerId(LEARNER_ID)
        .learningState(learningState)
        .createdAt(Instant.now())
        .lastUpdatedAt(Instant.now())
        .build();

    when(sessionCache.get(eq(TENANT_ID), eq(LEARNER_ID), eq(SESSION_ID)))
        .thenReturn(session);

    when(evaluatorAdapter.evaluate(eq("unit-economics"), eq("Profit per customer")))
        .thenReturn(new EvaluatorAdapter.EvaluationResult(
            true, false, "", 0.85, "Good answer"));

    // Act
    SmartLineService.InteractionResponse response = service.interact(
        TENANT_ID, LEARNER_ID, SESSION_ID, "w-1", "smart_line:submit_answer", "Profit per customer");

    // Assert
    assertThat(response.correct()).isTrue();
    assertThat(response.score()).isEqualTo(0.85);
    assertThat(response.feedback()).isEqualTo("Good answer");

    verify(eventPublisher).publishStudentAnswered(
        eq(TENANT_ID), eq(SESSION_ID), eq("w-1"), eq(true), eq(0.85));
  }

  @Test
  @DisplayName("should_terminate_session_when_mastery_reached")
  void should_terminate_session_when_mastery_reached() {
    // Arrange
    StudentState learningState = StudentState.builder()
        .sessionId(SESSION_ID)
        .learnerId(LEARNER_ID)
        .tenantId(TENANT_ID)
        .topic("unit-economics")
        .masteryScore(0.65)
        .currentInteractionLevel("L1")
        .build();

    SmartLineSessionState session = SmartLineSessionState.builder()
        .sessionId(SESSION_ID)
        .tenantId(TENANT_ID)
        .learnerId(LEARNER_ID)
        .learningState(learningState)
        .createdAt(Instant.now())
        .lastUpdatedAt(Instant.now())
        .build();

    when(sessionCache.get(eq(TENANT_ID), eq(LEARNER_ID), eq(SESSION_ID)))
        .thenReturn(session);

    when(evaluatorAdapter.evaluate(anyString(), anyString()))
        .thenReturn(new EvaluatorAdapter.EvaluationResult(true, false, "", 0.90, "Perfect"));

    // Act
    SmartLineService.InteractionResponse response = service.interact(
        TENANT_ID, LEARNER_ID, SESSION_ID, "w-2", "smart_line:submit_answer", "correct answer");

    // Assert
    assertThat(response.sessionStatus()).startsWith("TERMINATED_");
    verify(eventPublisher).publishSessionTerminated(eq(TENANT_ID), eq(SESSION_ID), anyString());
    verify(eventPublisher).publishStudentMastered(eq(TENANT_ID), eq(SESSION_ID), any(Double.class));
  }

  @Test
  @DisplayName("should_track_misconceptions_on_incorrect_answer")
  void should_track_misconceptions_on_incorrect_answer() {
    // Arrange
    StudentState learningState = StudentState.builder()
        .sessionId(SESSION_ID)
        .learnerId(LEARNER_ID)
        .tenantId(TENANT_ID)
        .topic("unit-economics")
        .currentInteractionLevel("L1")
        .build();

    SmartLineSessionState session = SmartLineSessionState.builder()
        .sessionId(SESSION_ID)
        .tenantId(TENANT_ID)
        .learnerId(LEARNER_ID)
        .learningState(learningState)
        .createdAt(Instant.now())
        .lastUpdatedAt(Instant.now())
        .build();

    when(sessionCache.get(eq(TENANT_ID), eq(LEARNER_ID), eq(SESSION_ID)))
        .thenReturn(session);

    when(evaluatorAdapter.evaluate(anyString(), anyString()))
        .thenReturn(new EvaluatorAdapter.EvaluationResult(
            false, true, "Confuses revenue with profit", 0.3, "Partial understanding"));

    // Act
    service.interact(TENANT_ID, LEARNER_ID, SESSION_ID, "w-3", "smart_line:submit_answer", "wrong");

    // Assert
    assertThat(learningState.getMisconceptions())
        .contains("Confuses revenue with profit");
    assertThat(learningState.getConsecutiveWrong()).isEqualTo(1);
  }

  @Test
  @DisplayName("should_throw_when_session_not_found_on_interact")
  void should_throw_when_session_not_found_on_interact() {
    // Arrange — cache returns null (default setUp)

    // Act & Assert
    assertThatThrownBy(() ->
        service.interact(TENANT_ID, LEARNER_ID, SESSION_ID, "w-1", "submit", "answer"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Session not found");
  }

  @Test
  @DisplayName("should_select_L1_interaction_level_for_new_student")
  void should_select_L1_interaction_level_for_new_student() {
    // Arrange
    SmartLineRenderRequest request = new SmartLineRenderRequest(
        SESSION_ID, LEARNER_ID, "LEARNING", "topic?", "topic", null);

    when(intentRouter.route(anyString(), anyString()))
        .thenReturn(SmartLineIntent.LEARNING_UNDERSTANDING);

    WidgetDraft draft = WidgetDraft.askUserQuestion("q?", "socratic", "free_text", List.of());
    when(tutorAdapter.generateTutorDraft(any()))
        .thenReturn(new TutorOrchestratorAdapter.TutorResult(TutoringStrategy.SOCRATIC, draft, 50));

    WidgetInstance widget = buildWidget("w-test04", "AskUserQuestion", "LEARNING", "L1");
    when(projectionBuilder.buildProjection(any(), any(), any(), any(), anyString(), eq("L1")))
        .thenReturn(widget);

    // Act
    SmartLineRenderResponse response = service.render(TENANT_ID, request);

    // Assert
    assertThat(response.widgets().get(0).interactionLevel()).isEqualTo("L1");
  }

  // ─────────────────────────── Helpers ────────────────────────

  private WidgetInstance buildWidget(String widgetId, String type, String space, String level) {
    ObjectNode divData = objectMapper.createObjectNode();
    divData.put("type", "container");

    return WidgetInstance.builder()
        .id(UUID.randomUUID())
        .tenantId(TENANT_ID)
        .sessionId(SESSION_ID)
        .learnerId(LEARNER_ID)
        .widgetId(widgetId)
        .widgetType(type)
        .widgetName("Test Widget")
        .space(space)
        .state("PROJECTED")
        .interactionLevel(level)
        .divData(divData)
        .sourcePlugin("adapstory.tutoring.socratic-tutor")
        .rankScore(0.90)
        .ttlSeconds(300)
        .createdAt(Instant.now())
        .build();
  }
}
