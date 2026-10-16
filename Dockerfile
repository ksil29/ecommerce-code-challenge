FROM node:24-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS backend
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src/main src/main
COPY --from=frontend /frontend/dist src/main/resources/static
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
RUN rm -f /usr/bin/pebble && groupadd --system --gid 10001 app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=backend /workspace/build/libs/app.jar app.jar
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", "--sun-misc-unsafe-memory-access=allow", "-jar", "/app/app.jar"]
