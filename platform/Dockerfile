# AgentRelease Lab — platform image.
# Build with JDK 21 (Temurin), matching the pinned toolchain.
# Build context is the repository root (see docker-compose.yml).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY platform/pom.xml .
COPY platform/src ./src
RUN apt-get update -qq && apt-get install -y -qq maven \
 && mvn -B -DskipTests package \
 && cp target/agentrelease-lab-platform-*.jar /app.jar

FROM eclipse-temurin:21-jre
# curl for the Docker healthcheck.
RUN apt-get update -qq && apt-get install -y -qq curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
# Evaluation datasets (the platform serves them at /api/datasets).
COPY eval/datasets /app/eval/datasets
ENV EVAL_DATASETS_DIR=/app/eval/datasets
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=12 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
