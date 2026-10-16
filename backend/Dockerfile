FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY backend/gradlew backend/gradlew
COPY backend/gradle backend/gradle
COPY backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.lockfile backend/
COPY backend/src backend/src
COPY scripts/seed.sql scripts/seed.sql
RUN cd backend && sh gradlew --no-daemon --console=plain bootJar

FROM eclipse-temurin:21-jre-jammy
ARG SOURCE_REVISION=unversioned
ARG BUILD_ID=local
LABEL org.opencontainers.image.revision=$SOURCE_REVISION io.infrastructure.build-id=$BUILD_ID
RUN apt-get update && apt-get install -y --no-install-recommends curl && apt-get clean
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/monitoring-service.jar /app/service.jar
USER 10001:10001
EXPOSE 8081 8086
ENTRYPOINT ["java","-jar","/app/service.jar"]
