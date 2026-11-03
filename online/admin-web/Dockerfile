# syntax=docker/dockerfile:1
# Contexto de build: esta PROPRIA pasta (online/admin-web), nao a raiz do repositorio: o admin-web e um
# projeto npm independente, sem dependencia nenhuma do monorepo Kotlin (:core-engine, :ai), diferente dos
# outros Dockerfiles de online/ (lobby/game/economy/admin-api).
#   docker build -t gtranca/admin-web:dev online/admin-web
# Cache mount em /root/.npm (BuildKit): guarda o cache de dependencias entre builds.
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

# Imagem "unprivileged" oficial: ja roda sem root e escuta numa porta > 1024 (8080), sem precisar
# ajustar permissoes/usuario na mao como nos Dockerfiles baseados em eclipse-temurin.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
# 127.0.0.1, nao "localhost": o busybox wget desta imagem prefere IPv6 (::1) pra "localhost" e o nginx
# so escuta IPv4 (visto na pratica: HEALTHCHECK ficava unhealthy com "Connection refused" mesmo com o
# nginx respondendo normalmente por fora, em 127.0.0.1 e 0.0.0.0).
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ || exit 1
