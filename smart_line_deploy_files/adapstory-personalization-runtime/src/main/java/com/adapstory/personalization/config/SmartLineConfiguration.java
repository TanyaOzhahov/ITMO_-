package com.adapstory.personalization.config;

import com.adapstory.starter.kafka.envelope.CloudEventEnvelopeConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Spring Boot конфигурация для Smart Line.
 * Feature flag: adapstory.smart-line.enabled=true.
 *
 * <p>НЕ переопределяет глобальный ObjectMapper.
 * Redis сериализация через стандартный StringRedisTemplate + ObjectMapper.
 */
@Configuration
@ConditionalOnProperty(name = "adapstory.smart-line.enabled", havingValue = "true", matchIfMissing = false)
@EnableKafka
public class SmartLineConfiguration {

  /**
   * CloudEventEnvelopeConverter для публикации событий в Kafka.
   * Создаётся только если ещё не зарегистрирован (из adapstory-starter-kafka).
   */
  @Bean
  @ConditionalOnMissingBean
  public CloudEventEnvelopeConverter cloudEventEnvelopeConverter(ObjectMapper objectMapper) {
    return new CloudEventEnvelopeConverter(objectMapper);
  }
}
