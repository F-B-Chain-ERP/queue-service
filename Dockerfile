# syntax=docker/dockerfile:1
# Multi-stage Dockerfile for Queue Service

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy core-model and queue-service source
COPY core-model/ core-model/
COPY queue-service/ queue-service/

# Grant execution rights to Maven wrapper
RUN chmod +x queue-service/mvnw

# 1. Build & install shared core-model library
WORKDIR /workspace/core-model
RUN ../queue-service/mvnw clean install -DskipTests

# 2. Package queue-service application
WORKDIR /workspace/queue-service
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root system user & group
RUN addgroup -S erp && adduser -S erp -G erp
USER erp:erp

# Copy packaged jar from builder
COPY --from=builder /workspace/queue-service/target/queue-service-*.jar app.jar

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENV SPRING_PROFILES_ACTIVE="dev"

EXPOSE 8090

HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8090/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
