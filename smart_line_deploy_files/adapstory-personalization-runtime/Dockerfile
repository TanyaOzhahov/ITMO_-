# ──────────────────────────────────────────────────────────────────────────────
# Multi-stage Dockerfile for Smart Line Widget Lake (BC-16)
#
# STAGE 1: Builder - Maven with dependency caching
# STAGE 2: Runtime - Eclipse Temurin JRE 21 (slim), non-root user
# ──────────────────────────────────────────────────────────────────────────────

# STAGE 1: Builder
FROM --platform=linux/amd64 maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy pom files (for dependency caching)
COPY pom.xml .
COPY src/ ./src/

# Build JAR (skip tests for speed)
RUN mvn clean package -DskipTests -q -Dmaven.javadoc.skip=true

# STAGE 2: Runtime
FROM --platform=linux/amd64 eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="adapstory-personalization-runtime"
LABEL org.opencontainers.image.description="Smart Line Widget Lake - TutorOrchestrator with ChatGPT"
LABEL org.opencontainers.image.version="0.1.0"
LABEL org.opencontainers.image.vendor="Adapstory"

RUN apk update && apk upgrade --no-cache && rm -rf /var/cache/apk/*

RUN addgroup -g 1000 -S appuser && adduser -u 1000 -S appuser -G appuser

WORKDIR /app

# Copy JAR from builder
COPY --from=builder --chown=appuser:appuser /build/target/*.jar app.jar

RUN chown -R appuser:appuser /app

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xmx512m -Xms256m"
ENV SPRING_PROFILES_ACTIVE="smart-line"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
