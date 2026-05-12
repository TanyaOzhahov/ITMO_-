package com.adapstory.personalization.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.adapstory.personalization.api.dto.SmartLineRenderResponse;
import com.adapstory.personalization.api.dto.SmartLineRenderResponse.WidgetProjectionDto;
import com.adapstory.personalization.application.service.SmartLineService;
import com.adapstory.personalization.domain.model.SmartLineSessionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Integration tests for SmartLineController REST endpoints.
 * Validates HTTP contract, integration headers, and response shapes.
 */
@WebMvcTest(SmartLineController.class)
class SmartLineControllerIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockBean private SmartLineService smartLineService;

  private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  @DisplayName("should_render_widget_when_valid_request")
  void should_render_widget_when_valid_request() throws Exception {
    // Arrange
    SmartLineRenderResponse serviceResponse = new SmartLineRenderResponse(
        SESSION_ID.toString(),
        "LEARNING",
        "LEARNING_UNDERSTANDING",
        Instant.now(),
        "ACTIVE",
        List.of(new WidgetProjectionDto(
            "w-test01", "AskUserQuestion", "Practice Question",
            "LEARNING", "L1", "adapstory.tutoring.socratic-tutor",
            0.90, 300, List.of("smart_line:submit_answer"), null, null))
    );

    when(smartLineService.render(eq(TENANT_ID), any())).thenReturn(serviceResponse);

    String requestBody = """
        {
          "sessionId": "%s",
          "learnerId": "%s",
          "space": "LEARNING",
          "prompt": "What is unit economics?",
          "topic": "unit-economics"
        }
        """.formatted(SESSION_ID, USER_ID);

    // Act & Assert
    mockMvc.perform(post("/api/bc-16/personalization-runtime/v1/smart-line/render")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT_ID.toString())
            .header("X-User-Id", USER_ID.toString())
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Correlation-Id", UUID.randomUUID().toString())
            .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.intent").value("LEARNING_UNDERSTANDING"))
        .andExpect(jsonPath("$.widgets[0].widgetType").value("AskUserQuestion"));
  }

  @Test
  @DisplayName("should_process_interaction_when_valid_request")
  void should_process_interaction_when_valid_request() throws Exception {
    // Arrange
    SmartLineService.InteractionResponse interactionResponse =
        new SmartLineService.InteractionResponse(true, 0.85, "Good!", "L2", "ACTIVE");

    when(smartLineService.interact(eq(TENANT_ID), eq(USER_ID), eq(SESSION_ID),
        eq("w-1"), eq("smart_line:submit_answer"), eq("Profit per customer")))
        .thenReturn(interactionResponse);

    String requestBody = """
        {
          "sessionId": "%s",
          "widgetId": "w-1",
          "actionId": "smart_line:submit_answer",
          "learnerAnswer": "Profit per customer"
        }
        """.formatted(SESSION_ID);

    // Act & Assert
    mockMvc.perform(post("/api/bc-16/personalization-runtime/v1/smart-line/interact")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", TENANT_ID.toString())
            .header("X-User-Id", USER_ID.toString())
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Correlation-Id", UUID.randomUUID().toString())
            .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correct").value(true))
        .andExpect(jsonPath("$.score").value(0.85));
  }

  @Test
  @DisplayName("should_return_404_when_session_not_found")
  void should_return_404_when_session_not_found() throws Exception {
    // Arrange
    when(smartLineService.getSession(eq(TENANT_ID), eq(USER_ID), eq(SESSION_ID)))
        .thenReturn(null);

    // Act & Assert
    mockMvc.perform(get("/api/bc-16/personalization-runtime/v1/smart-line/session/" + SESSION_ID)
            .header("X-Tenant-Id", TENANT_ID.toString())
            .header("X-User-Id", USER_ID.toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("should_return_session_when_found")
  void should_return_session_when_found() throws Exception {
    // Arrange
    SmartLineSessionState session = SmartLineSessionState.builder()
        .sessionId(SESSION_ID)
        .tenantId(TENANT_ID)
        .learnerId(USER_ID)
        .activeSpace("LEARNING")
        .createdAt(Instant.now())
        .lastUpdatedAt(Instant.now())
        .build();

    when(smartLineService.getSession(eq(TENANT_ID), eq(USER_ID), eq(SESSION_ID)))
        .thenReturn(session);

    // Act & Assert
    mockMvc.perform(get("/api/bc-16/personalization-runtime/v1/smart-line/session/" + SESSION_ID)
            .header("X-Tenant-Id", TENANT_ID.toString())
            .header("X-User-Id", USER_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeSpace").value("LEARNING"));
  }

  @Test
  @DisplayName("should_terminate_session_and_return_204")
  void should_terminate_session_and_return_204() throws Exception {
    // Act & Assert
    mockMvc.perform(delete("/api/bc-16/personalization-runtime/v1/smart-line/session/" + SESSION_ID)
            .header("X-Tenant-Id", TENANT_ID.toString())
            .header("X-User-Id", USER_ID.toString()))
        .andExpect(status().isNoContent());
  }
}
