package com.adapstory.personalization.api.controller;

import com.adapstory.personalization.api.dto.SmartLineRenderRequest;
import com.adapstory.personalization.api.dto.SmartLineRenderResponse;
import com.adapstory.personalization.application.service.SmartLineService;
import com.adapstory.personalization.domain.model.SmartLineSessionState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-контроллер Smart Line.
 * Реализует API-контракт из smart-line-bff-openapi.yaml.
 *
 * <p>Все эндпоинты требуют обязательные integration headers:
 * X-Request-Id, X-Correlation-Id, X-User-Id, X-Tenant-Id.
 */
@Slf4j
@RestController
@RequestMapping("/api/bc-16/personalization-runtime/v1/smart-line")
@Tag(name = "Smart Line", description = "Widget Lake and TutorOrchestrator endpoints")
@RequiredArgsConstructor
public class SmartLineController {
  private final SmartLineService smartLineService;

  /**
   * Render Smart Line widget(s) for student.
   */
  @PostMapping("/render")
  @Operation(
      summary = "Render Smart Line widget",
      description = "Generate and render personalized widget(s) using intent routing and agent orchestration"
  )
  public ResponseEntity<SmartLineRenderResponse> render(
      @RequestHeader("X-Tenant-Id") @NotNull UUID tenantId,
      @RequestHeader("X-User-Id") @NotNull UUID userId,
      @RequestHeader("X-Request-Id") @NotNull String requestId,
      @RequestHeader("X-Correlation-Id") @NotNull String correlationId,
      @Valid @RequestBody SmartLineRenderRequest request) {

    log.info("SmartLineController.render: tenant={}, user={}, session={}, requestId={}",
        tenantId, userId, request.sessionId(), requestId);

    SmartLineRenderResponse response = smartLineService.render(tenantId, request);
    return ResponseEntity.ok(response);
  }

  /**
   * Generic interaction endpoint (submit answer, navigate, etc.).
   * Canonical endpoint per architecture spec.
   */
  @PostMapping("/interact")
  @Operation(
      summary = "Process widget interaction",
      description = "Handle student interaction (submit answer, navigate, etc.), update state, evaluate"
  )
  public ResponseEntity<SmartLineService.InteractionResponse> interact(
      @RequestHeader("X-Tenant-Id") @NotNull UUID tenantId,
      @RequestHeader("X-User-Id") @NotNull UUID userId,
      @RequestHeader("X-Request-Id") @NotNull String requestId,
      @RequestHeader("X-Correlation-Id") @NotNull String correlationId,
      @Valid @RequestBody InteractionRequest request) {

    log.info("SmartLineController.interact: tenant={}, session={}, widget={}, action={}",
        tenantId, request.sessionId(), request.widgetId(), request.actionId());

    SmartLineService.InteractionResponse response = smartLineService.interact(
        tenantId, userId, request.sessionId(),
        request.widgetId(), request.actionId(),
        request.learnerAnswer()
    );

    return ResponseEntity.ok(response);
  }

  /**
   * Get session state.
   */
  @GetMapping("/session/{sessionId}")
  @Operation(summary = "Get session state", description = "Retrieve current Smart Line session")
  public ResponseEntity<SmartLineSessionState> getSession(
      @RequestHeader("X-Tenant-Id") @NotNull UUID tenantId,
      @RequestHeader("X-User-Id") @NotNull UUID userId,
      @PathVariable UUID sessionId) {

    SmartLineSessionState session = smartLineService.getSession(tenantId, userId, sessionId);
    if (session == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(session);
  }

  /**
   * Terminate session.
   */
  @DeleteMapping("/session/{sessionId}")
  @Operation(summary = "Terminate session", description = "End Smart Line session and clean up")
  public ResponseEntity<Void> terminateSession(
      @RequestHeader("X-Tenant-Id") @NotNull UUID tenantId,
      @RequestHeader("X-User-Id") @NotNull UUID userId,
      @PathVariable UUID sessionId) {

    smartLineService.terminateSession(tenantId, userId, sessionId);
    return ResponseEntity.noContent().build();
  }

  // ─────────────────────────── Request DTOs ───────────────────

  /**
   * Запрос на взаимодействие с виджетом.
   */
  public record InteractionRequest(
      @NotNull UUID sessionId,
      @NotNull String widgetId,
      @NotNull String actionId,
      String space,
      String learnerAnswer
  ) {}
}
