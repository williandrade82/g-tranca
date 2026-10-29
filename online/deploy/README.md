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

**`POST /internal/sim`** - interno: exige o cabecalho `X-Internal-Secret` (ver "Segredo servico a servico") e **nao e exposto pelo ingress** (so `/health`, `/info` e `/ws` saem). Acesse por `kubectl port-forward`.
Corpo: `{"games":200,"seed":42,"mode":"individual|duplas","difficulty":"facil|medio|dificil","rulesJson":null,"targetScore":null}`. Limites: `games` 1..200, tempo maximo de 120 s (`timedOut`), uma simulacao por vez (429 se ocupado).

```bash
kubectl --context docker-desktop -n gtranca port-forward svc/game-service 8080:80 &
SECRET=$(kubectl --context docker-desktop -n gtranca get secret gtranca-internal -o jsonpath='{.data.INTERNAL_SECRET}' | base64 -d)
curl -H "X-Internal-Secret: $SECRET" -H 'Content-Type: application/json' -d '{"games":200,"seed":42,"mode":"duplas","difficulty":"facil"}' http://localhost:8080/internal/sim
```

Recursos do pod: pedido 500m CPU / 384Mi, limite 2 CPU / 768Mi (heap = 65% via `JAVA_OPTS`).

## Etapa 1.3b: humanos jogam por WebSocket

### Segredo servico a servico (uma vez)
Lobby e game-service falam por HTTP **interno** (nome do Service) e se autenticam com `X-Internal-Secret`. O valor vem do Secret `gtranca-internal` (nunca de values, codigo ou repositorio); sem o Secret os pods **nao sobem** (`secretKeyRef` obrigatorio) e, sem a variavel, `/internal/*` fica fechado (503).

```bash
kubectl --context docker-desktop create namespace gtranca    # se ainda nao existir
kubectl --context docker-desktop -n gtranca create secret generic gtranca-internal \
  --from-literal=INTERNAL_SECRET="$(openssl rand -hex 32)"
```
Trocar o segredo: apague e recrie o Secret e reinicie os dois deployments (`kubectl rollout restart deploy/game-service deploy/lobby-service`).

### Deploy (tag nova a cada build)
```bash
TAG=1.3b   # ou o hash do commit
docker build -f online/game-service/Dockerfile  -t gtranca/game-service:$TAG  .
docker build -f online/lobby-service/Dockerfile -t gtranca/lobby-service:$TAG .
cd online
helm upgrade --install game-service  deploy/helm/game-service  -n gtranca --kube-context docker-desktop --set ingress.enabled=true --set image.tag=$TAG
helm upgrade --install lobby-service deploy/helm/lobby-service -n gtranca --kube-context docker-desktop --set ingress.enabled=true --set image.tag=$TAG
```
Ingress: o game-service expoe **so** `/health`, `/info` e `/ws`; o lobby expoe `/m` e `/api/lobby`. **`/internal/*` (criar partida, introspeccao de convidado, `/internal/sim`) nao sai pelo ingress** em nenhum dos dois. Em `game-service` os ajustes por variavel: `BOT_DELAY_MS` (1200), `ROUND_PAUSE_MS` (6000), `MAX_MATCHES`, `WS_MESSAGES_PER_SECOND`/`WS_BURST`, `WS_PING_MS`/`WS_TIMEOUT_MS`, `IDLE_TTL_MS`, `LOBBY_URL`.

### Fluxo
1. Anfitriao chama `POST /api/lobby/tables/{id}/start`. O lobby valida, marca a mesa "iniciando" (join/sair/encerrar recusados, a mesa segue ABERTA), chama `POST http://game-service/internal/matches` (segredo) com `{tableId, rules, seats:[human(guestId,name)|robot(profile,level)]}` e so entao passa a EM_JOGO. Falhou: a mesa volta a ficar livre e o erro e `PARTIDA_INDISPONIVEL` (503) ou `REQUISICAO_INVALIDA` (regras recusadas pelo motor).
2. Cada humano abre `ws://<servidor>/ws/match/<id>` com `X-Protocol-Version: 1` e `Authorization: Bearer <token de convidado>` **no handshake HTTP** (o token nunca vai na URL). O game-service valida a versao (426 "Atualize o aplicativo" / 503 "Servidor em manutenção"), o token no lobby (`POST /internal/guests/resolve`, 401 se invalido) e o assento (404 sem partida, 403 se nao e da mesa) **antes** do upgrade.

### Protocolo (resumo; JSON, `type` discrimina; tipos em `online/contracts/.../Game.kt`)
Cliente -> servidor (so isto):
- `{"type":"play","seq":N,"stateVersion":V,"action":{...Action do motor...}}`  (`seq` cresce por jogador e continua entre conexoes; `stateVersion` = `view.version` da ultima vista)
- `{"type":"resign","seq":N}`

Servidor -> cliente:
- `welcome {protocolVersion, tableId, you, table(TableRules), ruleSet, seats[SeatInfo], lastSeq}` (primeira mensagem; `lastSeq` = ultimo seq aceito)
- `update {event?: PublicEvent, view: OnlineSeatView}` a CADA jogada processada e a cada mudanca de partida, para todos os humanos, cada um com a vista do SEU assento (`hand` so a propria; dos outros so `handSizes`; `legal` so quando e a vez dele; `turnEvents` (as acoes da vez mais recente de cada assento), `totals`, `history` quando muda, `stage` PLAYING/ROUND_OVER/GAME_OVER, `winner`)
- `seats {seats, seat, change: CONNECTED|DISCONNECTED|RESIGNED}`
- `rejected {seq, reason: NOT_PLAYING|NOT_YOUR_TURN|STALE_STATE|ILLEGAL, message, refusals}` (nada muda; consome o seq)
- `replaced`, `resigned`, `error {code, message}` (fatais; a conexao e fechada: 4001 substituida, 1008 mensagem invalida/limite de taxa, 1009 grande demais)
`seq` duplicado ou menor que o ultimo e descartado em silencio. Nunca vao ao cliente: `GameState`/`Match`, mao dos outros, monte, mortos, semente.

### Limites
Mensagem do cliente ate 16 KiB (frame maior fecha a conexao), 10 mensagens/s por conexao (rajada 20), ping a cada 15 s e timeout de 45 s, **1 conexao por assento** (a nova assume e a antiga recebe `replaced` e e fechada), 60 handshakes/min por IP, `MAX_MATCHES` partidas por pod. Sessao sem atividade por 2 h e descartada. Queda de conexao **nao e abandono** (a vez espera; relogio e punicao sao a 1.3c e a fase 2); `resign` coloca um robo no assento e registra o evento.

### Prova no cluster (cliente de linha de comando)
```bash
cd online && ./gradlew.bat :probe:run --args="192.168.1.5 200 8"     # servidor, pontuacao-alvo, queda do Beto apos N acoes
```
Cria 2 convidados, mesa dupla com 2 robos, inicia, conecta os dois por WebSocket, joga ate o fim pelas `legal` das vistas, envia uma jogada ilegal de proposito (recusada), derruba a conexao do Beto e reconecta pelo mesmo assento.

Saida real da prova (imagem `1.3b`, `BOT_DELAY_MS=200`, alvo 200): handshake com versao 0 -> `426 {"code":"VERSAO_INCOMPATIVEL","message":"Atualize o aplicativo"}`; sem token -> `401`; `GET /internal/sim` pelo ingress -> `404`; Ana e Beto conectados nos assentos 0 e 1; jogada ilegal de Ana -> `RECUSADA ... ILLEGAL (MUST_DRAW_FIRST) recusas=1`; Beto derrubado (ultimo seq 8) e, 3 s depois, `Welcome: assento 1, lastSeq=8` com a vista atual; `FIM DO JOGO` com o mesmo placar nas duas vistas. Para ver o jogo com o ritmo normal dos robos, suba o chart sem `--set env.BOT_DELAY_MS=200` (padrao 1200 ms).
