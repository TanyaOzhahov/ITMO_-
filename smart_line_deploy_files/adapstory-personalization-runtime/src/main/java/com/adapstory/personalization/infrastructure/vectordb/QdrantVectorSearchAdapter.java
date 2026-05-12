package com.adapstory.personalization.infrastructure.vectordb;

import com.adapstory.personalization.domain.port.outbound.LlmGatewayPort;
import com.adapstory.personalization.domain.port.outbound.VectorSearchPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Адаптер для Qdrant vector search.
 * Реализует domain port VectorSearchPort.
 * Аналог retrieve_context() из ИИ_агент.ipynb.
 */
@Slf4j
@Component
public class QdrantVectorSearchAdapter implements VectorSearchPort {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final LlmGatewayPort llmGateway;
  private final String qdrantUrl;
  private final String collectionName;

  public QdrantVectorSearchAdapter(
      ObjectMapper objectMapper,
      LlmGatewayPort llmGateway,
      @Value("${adapstory.qdrant.url:https://qdrant.dev.adapstory.com}") String qdrantUrl,
      @Value("${adapstory.qdrant.collection:presentations_industrix_openai}") String collectionName) {
    this.objectMapper = objectMapper;
    this.llmGateway = llmGateway;
    this.qdrantUrl = qdrantUrl;
    this.collectionName = collectionName;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @Override
  public List<ContextChunk> search(String query, int topK) {
    try {
      // 1. Get embedding via LLM Gateway
      List<Float> vector = llmGateway.embed(query);

      // 2. Search Qdrant
      ObjectNode body = objectMapper.createObjectNode();
      ArrayNode vectorNode = body.putArray("vector");
      for (Float v : vector) {
        vectorNode.add(v);
      }
      body.put("limit", topK);
      body.put("with_payload", true);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(qdrantUrl + "/collections/" + collectionName + "/points/search"))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(15))
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.error("Qdrant search failed: status={}, body={}", response.statusCode(), response.body());
        return List.of();
      }

      // 3. Parse results
      JsonNode root = objectMapper.readTree(response.body());
      JsonNode results = root.path("result");

      List<ContextChunk> chunks = new ArrayList<>();
      for (JsonNode result : results) {
        JsonNode payload = result.path("payload");
        String text = payload.path("text").asText("").trim();
        if (text.isEmpty()) continue;

        chunks.add(new ContextChunk(
            text,
            payload.has("page") ? payload.path("page").asInt() : null,
            payload.path("source").asText(null),
            result.path("score").asDouble(0.0)
        ));
      }

      log.debug("Qdrant search: query='{}', results={}", truncate(query), chunks.size());
      return chunks;

    } catch (Exception e) {
      log.error("Vector search failed for query: '{}'", truncate(query), e);
      return List.of();
    }
  }

  private String truncate(String text) {
    return text.length() > 60 ? text.substring(0, 60) + "..." : text;
  }
}
