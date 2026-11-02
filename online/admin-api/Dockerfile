# syntax=docker/dockerfile:1
# Contexto de build: a RAIZ do repositorio (precisa do :core-engine e do :ai, usados so leitura pelo settings.gradle.kts)
#   docker build -f online/admin-api/Dockerfile -t gtranca/admin-api:dev .
# O .dockerignore ao lado (admin-api/Dockerfile.dockerignore) limita o contexto ao necessario.
# Cache mount em /root/.gradle (BuildKit): guarda dependencias e o build cache local do Gradle
# entre builds (precisa de BuildKit ativo; no Docker Desktop recente e o padrao).
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY core-engine ./core-engine
COPY ai ./ai
COPY online ./online
WORKDIR /src/online
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :admin-api:installDist -x test

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home gtranca
COPY --from=build /src/online/admin-api/build/install/admin-api /opt/admin-api
USER 10001
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:${PORT}/health || exit 1
ENTRYPOINT ["/opt/admin-api/bin/admin-api"]
