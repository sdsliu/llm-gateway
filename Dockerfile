# syntax=docker/dockerfile:1
# ---- Стадия 1: сборка на полном JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Сначала только wrapper и pom — слой с зависимостями кэшируется, пока pom.xml не меняется.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/llm-gateway-*.jar /app.jar

# ---- Стадия 2: рантайм только с JRE ----
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 gateway
USER gateway
WORKDIR /app
COPY --from=build /app.jar app.jar
EXPOSE 4000
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:4000/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
