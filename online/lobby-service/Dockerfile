# Contexto de build: a RAIZ do repositorio (precisa do :core-engine e do :ai, usados so leitura)
#   docker build -f online/lobby-service/Dockerfile -t gtranca/lobby-service:dev .
# O .dockerignore ao lado (lobby-service/Dockerfile.dockerignore) limita o contexto ao necessario.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY core-engine ./core-engine
COPY ai ./ai
COPY online ./online
WORKDIR /src/online
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon :lobby-service:installDist -x test

FROM eclipse-temurin:17-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home gtranca
COPY --from=build /src/online/lobby-service/build/install/lobby-service /opt/lobby-service
USER 10001
ENV PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:${PORT}/health || exit 1
ENTRYPOINT ["/opt/lobby-service/bin/lobby-service"]
