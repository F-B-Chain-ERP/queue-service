# syntax=docker/dockerfile:1
# Multi-stage Dockerfile for Queue Service

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY core-model/ core-model/
COPY backend-service/mvnw backend-service/mvnw
COPY backend-service/.mvn backend-service/.mvn
COPY queue-service/ queue-service/

RUN chmod +x backend-service/mvnw

WORKDIR /workspace/core-model
RUN ../backend-service/mvnw clean install -DskipTests

WORKDIR /workspace/queue-service
RUN ../backend-service/mvnw clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S erp && adduser -S erp -G erp
USER erp:erp

COPY --from=builder /workspace/queue-service/target/queue-service-*.jar app.jar

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENV SPRING_PROFILES_ACTIVE="dev"

EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
