FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon
COPY . .
RUN ./gradlew :app-engine:bootJar :app-admin:bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home-dir /app enginerules
WORKDIR /app
COPY --from=builder /workspace/apps/engine/build/libs/engine.jar /app/engine.jar
COPY --from=builder /workspace/apps/admin/build/libs/admin.jar /app/admin.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/engine.jar"]
