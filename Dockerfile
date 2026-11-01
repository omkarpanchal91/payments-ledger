# ---- Build stage: compile the Spring Boot fat jar with Gradle ----
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

# Copy build scripts first so dependency resolution is cached between source changes.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- Runtime stage: small JRE image, non-root user ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ledger && adduser -S ledger -G ledger
WORKDIR /app
COPY --from=build /workspace/build/libs/ledger.jar /app/ledger.jar
USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/ledger.jar"]
