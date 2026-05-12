package com.adapstory.personalization.infrastructure.cache;

import com.adapstory.personalization.domain.model.StudentState;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis cache for StudentState (1800s TTL, sliding window).
 * Following smart-line-redis-schema.md key naming convention.
 * 
 * Кэш Redis для StudentState (1800s TTL, скользящее окно).
 * Следует соглашениям об именовании из smart-line-redis-schema.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentStateCache {
  private final RedisTemplate<String, StudentState> redisTemplate;

  private static final String KEY_PREFIX = "widget-lake:session:";
  private static final Duration TTL = Duration.ofSeconds(1800);

  /**
   * Get StudentState from Redis.
   * Получить StudentState из Redis.
   */
  public StudentState get(UUID sessionId) {
    String key = buildKey(sessionId);
    StudentState state = redisTemplate.opsForValue().get(key);
    log.debug("Retrieved StudentState from cache: session={}, present={}", sessionId, state != null);
    return state;
  }

  /**
   * Set StudentState in Redis with 1800s TTL.
   * Установить StudentState в Redis с TTL 1800s.
   */
  public void set(UUID sessionId, StudentState state) {
    String key = buildKey(sessionId);
    redisTemplate.opsForValue().set(key, state, TTL);
    log.debug("Cached StudentState: session={}", sessionId);
  }

  /**
   * Delete StudentState from Redis.
   * Удалить StudentState из Redis.
   */
  public void delete(UUID sessionId) {
    String key = buildKey(sessionId);
    Boolean deleted = redisTemplate.delete(key);
    log.debug("Deleted StudentState from cache: session={}, deleted={}", sessionId, deleted);
  }

  /**
   * Check if StudentState exists in cache.
   * Проверить, существует ли StudentState в кэше.
   */
  public boolean exists(UUID sessionId) {
    String key = buildKey(sessionId);
    Boolean exists = redisTemplate.hasKey(key);
    return exists != null && exists;
  }

  /**
   * Extend TTL for sliding window (call on every access).
   * Продлить TTL для скользящего окна (вызывать при каждом доступе).
   */
  public void refreshTTL(UUID sessionId) {
    String key = buildKey(sessionId);
    redisTemplate.expire(key, TTL);
    log.debug("Refreshed TTL for StudentState: session={}", sessionId);
  }

  private String buildKey(UUID sessionId) {
    return KEY_PREFIX + sessionId;
  }
}
