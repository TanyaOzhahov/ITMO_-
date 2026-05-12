package com.adapstory.personalization.infrastructure.cache;

import com.adapstory.personalization.domain.model.SmartLineSessionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-кэш для SmartLineSessionState.
 *
 * <p>Key: widget-lake:tenant:{tenant_id}:learner:{learner_id}:session:{session_id}
 * <p>TTL: 1800 seconds (sliding window).
 * <p>Tenant-scoped keys обеспечивают изоляцию данных между арендаторами.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartLineSessionCache {
  private final RedisTemplate<String, String> redisTemplate;
  private final ObjectMapper objectMapper;

  private static final Duration TTL = Duration.ofSeconds(1800);

  /**
   * Получить сессию из Redis.
   */
  public SmartLineSessionState get(UUID tenantId, UUID learnerId, UUID sessionId) {
    String key = buildKey(tenantId, learnerId, sessionId);
    try {
      String json = redisTemplate.opsForValue().get(key);
      if (json == null) return null;
      return objectMapper.readValue(json, SmartLineSessionState.class);
    } catch (Exception e) {
      log.error("Failed to read session from Redis: key={}", key, e);
      return null;
    }
  }

  /**
   * Сохранить сессию в Redis с TTL 1800s.
   */
  public void set(UUID tenantId, UUID learnerId, UUID sessionId, SmartLineSessionState state) {
    String key = buildKey(tenantId, learnerId, sessionId);
    try {
      String json = objectMapper.writeValueAsString(state);
      redisTemplate.opsForValue().set(key, json, TTL);
      log.debug("Cached SmartLineSessionState: key={}", key);
    } catch (Exception e) {
      log.error("Failed to write session to Redis: key={}", key, e);
    }
  }

  /**
   * Удалить сессию из Redis.
   */
  public void delete(UUID tenantId, UUID learnerId, UUID sessionId) {
    String key = buildKey(tenantId, learnerId, sessionId);
    redisTemplate.delete(key);
    log.debug("Deleted SmartLineSessionState: key={}", key);
  }

  /**
   * Продлить TTL (sliding window) при каждом обращении.
   */
  public void refreshTTL(UUID tenantId, UUID learnerId, UUID sessionId) {
    String key = buildKey(tenantId, learnerId, sessionId);
    redisTemplate.expire(key, TTL);
  }

  /**
   * Tenant-scoped Redis key per architecture spec.
   */
  private String buildKey(UUID tenantId, UUID learnerId, UUID sessionId) {
    return "widget-lake:tenant:%s:learner:%s:session:%s".formatted(tenantId, learnerId, sessionId);
  }
}
