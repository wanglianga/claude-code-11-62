# syntax=docker/dockerfile:1

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------- runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 ledger && chown -R ledger /app
COPY --from=build /build/target/bonded-ledger-1.0.0.jar app.jar
USER ledger
EXPOSE 8080
ENV SERVER_PORT=8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD ["sh", "-c", "curl -fsS http://127.0.0.1:${SERVER_PORT}/actuator/health >/dev/null || exit 1"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
