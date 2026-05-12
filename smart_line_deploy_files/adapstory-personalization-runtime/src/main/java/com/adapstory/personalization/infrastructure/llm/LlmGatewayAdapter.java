package com.adapstory.personalization.infrastructure.llm;

import com.adapstory.personalization.domain.port.outbound.LlmGatewayPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Адаптер LLM Gateway — вызывает ChatGPT через HTTP.
 * Реализует domain port LlmGatewayPort.
 */
@Slf4j
@Component
public class LlmGatewayAdapter implements LlmGatewayPort {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String gatewayUrl;
  private final String apiKey;

  public LlmGatewayAdapter(
      ObjectMapper objectMapper,
      @Value("${adapstory.llm-gateway.url:https://api.openai.com/v1}") String gatewayUrl,
      @Value("${adapstory.llm-gateway.api-key:}") String apiKey) {
    this.objectMapper = objectMapper;
    this.gatewayUrl = gatewayUrl;
    this.apiKey = apiKey;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @Override
  public String chatCompletion(String systemPrompt, String userMessage,
                                double temperature, int maxTokens) {
    try {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("model", "gpt-4o-mini");
      body.put("temperature", temperature);
      body.put("max_tokens", maxTokens);

      ArrayNode messages = body.putArray("messages");

      ObjectNode sysMsg = objectMapper.createObjectNode();
      sysMsg.put("role", "system");
      sysMsg.put("content", systemPrompt);
      messages.add(sysMsg);

      ObjectNode userMsg = objectMapper.createObjectNode();
      userMsg.put("role", "user");
      userMsg.put("content", userMessage);
      messages.add(userMsg);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(gatewayUrl + "/chat/completions"))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .timeout(Duration.ofSeconds(30))
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.error("LLM Gateway returned {}: {}", response.statusCode(), response.body());
        throw new RuntimeException("LLM Gateway error: " + response.statusCode());
      }

      JsonNode responseJson = objectMapper.readTree(response.body());
      return responseJson.path("choices").path(0).path("message").path("content").asText();

    } catch (Exception e) {
      log.error("LLM Gateway call failed", e);
      throw new RuntimeException("LLM Gateway call failed", e);
    }
  }

  @Override
  public List<Float> embed(String text) {
    try {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("model", "text-embedding-3-large");
      body.put("input", text);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(gatewayUrl + "/embeddings"))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .timeout(Duration.ofSeconds(15))
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      JsonNode responseJson = objectMapper.readTree(response.body());
      JsonNode embedding = responseJson.path("data").path(0).path("embedding");

      List<Float> result = new java.util.ArrayList<>();
      embedding.forEach(node -> result.add((float) node.asDouble()));
      return result;

    } catch (Exception e) {
      log.error("Embedding call failed", e);
      throw new RuntimeException("Embedding call failed", e);
    }
  }
}
