package com.adapstory.personalization.application.service;

import com.adapter.integration.llm.gateway.LlmRequest;
import com.adapter.integration.llm.gateway.LlmResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign client for LLM Gateway (ChatGPT integration).
 * Routes to ChatGPT model via adapstory-plugin-gateway.
 * 
 * Feign client для LLM Gateway (интеграция ChatGPT).
 * Маршрутизирует на модель ChatGPT через adapstory-plugin-gateway.
 */
@FeignClient(
    name = "llm-gateway",
    url = "${adapstory.llm-gateway.url:http://localhost:8080}",
    path = "/api/llm-gateway/v1"
)
public interface LlmGatewayClient {

  /**
   * Call ChatGPT via LLM Gateway.
   * Вызвать ChatGPT через LLM Gateway.
   */
  @PostMapping("/chat")
  LlmResponse chat(@RequestBody LlmRequest request);
}
