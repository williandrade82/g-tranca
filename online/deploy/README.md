# Deploy do jogo online (cluster local)

Sempre use o contexto do Docker Desktop de forma explicita (`--context docker-desktop` / `--kube-context docker-desktop`).
Ative antes: Docker Desktop, Settings, Kubernetes, Enable. Confira: `kubectl --context docker-desktop get nodes` (no Ready).

Rode a partir da pasta `online/` (menos o `docker build`, que parte da raiz do repositorio, ver abaixo).

```bash
# 1. Imagem local (o Docker Desktop K8s enxerga as imagens locais; sem registro externo)
# O contexto agora e a RAIZ DO REPOSITORIO (o game-service usa :core-engine e :ai do app, somente leitura).
# Use uma TAG NOVA a cada build (ex.: o hash do commit): o no do Docker Desktop K8s nao atualiza uma tag que ja conhecia (:dev).
cd .. && TAG=$(git rev-parse --short HEAD) && docker build -f online/game-service/Dockerfile -t gtranca/game-service:$TAG . && cd online

# 2. Ingress (Traefik) - uma vez
helm repo add traefik https://traefik.github.io/charts && helm repo update
helm install traefik traefik/traefik -n traefik --create-namespace --kube-context docker-desktop -f deploy/infra/traefik-values.yaml

# 3. game-service
helm upgrade --install game-service deploy/helm/game-service -n gtranca --create-namespace \
  --kube-context docker-desktop --set ingress.enabled=true --set image.tag=$TAG

# 4. Conferir
curl http://192.168.1.5/health        # via ingress
kubectl --context docker-desktop -n gtranca port-forward svc/game-service 8080:80   # alternativa: http://localhost:8080/health

# 5. Infra (so quando for usada; senha do Postgres em deploy/infra/postgres.yaml deve ser trocada)
kubectl --context docker-desktop apply -f deploy/infra/postgres.yaml
helm repo add nats https://nats-io.github.io/k8s/helm/charts/
helm install nats nats/nats -n gtranca --kube-context docker-desktop -f deploy/infra/nats-values.yaml
```

Desenvolvimento local sem cluster: `.\gradlew.bat :game-service:run` e `http://localhost:8080/health`.

`:core-engine` e `:ai` entram no build de `online/` por caminho (`include` + `projectDir = ../core-engine` no `online/settings.gradle.kts`): nenhum arquivo do app muda. Em contêiner o layout e `/src/{core-engine,ai,online}`; cada Dockerfile tem um `Dockerfile.dockerignore` que limita o contexto.

## lobby-service (mesas e convites)

**Persistencia: SOMENTE EM MEMORIA nesta fase.** Reiniciar o pod apaga convidados e mesas (a interface `TableStore` permite trocar por Postgres depois). Por isso `replicaCount: 1`; nao escale.
Sem economy-service: a aposta da mesa NAO tem o saldo verificado ao entrar (`SALDO_INSUFICIENTE` esta declarado mas nunca e devolvido).

```bash
# (a partir da raiz do repositorio)  docker build -f online/lobby-service/Dockerfile -t gtranca/lobby-service:$TAG .
helm upgrade --install lobby-service deploy/helm/lobby-service -n gtranca --kube-context docker-desktop --set ingress.enabled=true --set image.tag=$TAG
```

Ingress por prefixo no Traefik: `/m` e `/api/lobby` vao para o lobby-service; `/health`, `/info` e o resto seguem no game-service. (Os Dockerfiles copiam todos os modulos porque o `settings.gradle.kts` inclui todos.)

Variaveis: `PORT`, `TABLE_OPEN_TTL_MINUTES` (30), `RATE_LIMIT_PER_MINUTE` (120 por IP), `TRUST_FORWARDED_FOR` (`true` atras do Traefik: usa o ultimo IP de `X-Forwarded-For` no limite de taxa).

Exemplo (toda chamada de `/api/lobby` leva `X-Protocol-Version: 1`; depois do convidado, `Authorization: Bearer <token>`):

```bash
H='X-Protocol-Version: 1'
curl -H "$H" -d '{"name":"Ana"}' http://192.168.1.5/api/lobby/guests
curl -H "$H" -H "Authorization: Bearer $TOKEN" -d '{"rules":{"mode":"INDIVIDUAL","rulesJson":"{}"}}' http://192.168.1.5/api/lobby/tables
curl http://192.168.1.5/m/<ID>      # pagina do convite
```

## game-service: partida so com robos (etapa 1.3a)

`MatchRunner` joga um jogo completo (todas as partidas ate a pontuacao-alvo, §11-§13) com robos do `:ai` em todos os assentos: `legalActions` -> robo decide com o `PlayerView` -> `apply`, igual ao `:sim`. Mesma semente = mesmo jogo; as sementes por jogo/assento sao as do `:sim`, entao os resultados sao identicos (200 jogos, semente 42, facil: 107x93 individual e 97x103 duplas nos dois). Sem boosters, aposta nem economia. Dificil roda sem teto de tempo (reproduzivel) e e lento.

**`POST /internal/sim` - SO PARA VALIDACAO LOCAL, SEM AUTENTICACAO; sera removido/fechado antes de qualquer exposicao.**
Corpo: `{"games":200,"seed":42,"mode":"individual|duplas","difficulty":"facil|medio|dificil","rulesJson":null,"targetScore":null}`. Limites: `games` 1..200, tempo maximo de 120 s (`timedOut`), uma simulacao por vez (429 se ocupado).

```bash
curl -H 'Content-Type: application/json' -d '{"games":200,"seed":42,"mode":"duplas","difficulty":"facil"}' http://192.168.1.5/internal/sim
```

Recursos do pod: pedido 500m CPU / 384Mi, limite 2 CPU / 768Mi (heap = 65% via `JAVA_OPTS`).
