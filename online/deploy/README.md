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
helm upgrade --install traefik traefik/traefik --version 42.1.3 -n traefik --create-namespace --kube-context docker-desktop -f deploy/infra/traefik-values.yaml

# 3. game-service
helm upgrade --install game-service deploy/helm/game-service -n gtranca --create-namespace \
  --kube-context docker-desktop --set ingress.enabled=true --set image.tag=$TAG

# 4. Conferir
curl http://192.168.1.5/health        # via ingress
kubectl --context docker-desktop -n gtranca port-forward svc/game-service 8080:80   # alternativa: http://localhost:8080/health

# 5. Infra: Postgres, backup e rede -> ver "Postgres, backup e rede" abaixo
helm repo add nats https://nats-io.github.io/k8s/helm/charts/
helm install nats nats/nats -n gtranca --kube-context docker-desktop -f deploy/infra/nats-values.yaml
```

Desenvolvimento local sem cluster: `.\gradlew.bat :game-service:run` e `http://localhost:8080/health`.

`:core-engine` e `:ai` entram no build de `online/` por caminho (`include` + `projectDir = ../core-engine` no `online/settings.gradle.kts`): nenhum arquivo do app muda. Em contêiner o layout e `/src/{core-engine,ai,online}`; cada Dockerfile tem um `Dockerfile.dockerignore` que limita o contexto.

## lobby-service (mesas e convites)

**Persistencia (etapa 2.4): Postgres, schema `lobby`** (convidados com hash do token, fotos e mesas; historico Flyway proprio `lobby.flyway_lobby_history`). O Flyway do lobby cria o schema ao iniciar com o usuario `POSTGRES_USER` (dono do banco). Usa o mesmo Secret `postgres-credentials` do economy (`DB_URL`=`jdbc:postgresql://postgres:5432/$(POSTGRES_DB)`, `DB_USER`/`DB_PASSWORD`) e o mesmo initContainer `wait-for-postgres`. O rotulo `app: lobby-service` libera a NetworkPolicy do Postgres. Conferido: apagar o pod do lobby mantem os convidados no banco e um novo probe roda. Continua com `replicaCount: 1` (sessoes/presenca ainda em memoria).
startupProbe do lobby e do economy: ate 5 min (3 s x 100), porque o servico retenta o banco e migra antes de abrir o HTTP.
Ao aplicar valores novos do chart nao use `--reuse-values` (ignora defaults novos, ex. `waitForPostgres`); use `--reset-then-reuse-values` ou repita os `--set`.

```bash
# (a partir da raiz do repositorio)  docker build -f online/lobby-service/Dockerfile -t gtranca/lobby-service:$TAG .
helm upgrade --install lobby-service deploy/helm/lobby-service -n gtranca --kube-context docker-desktop --set ingress.enabled=true --set image.tag=$TAG
```

Ingress por prefixo no Traefik: `/m` e `/api/lobby` vao para o lobby-service; `/health`, `/info` e o resto seguem no game-service. (Os Dockerfiles copiam todos os modulos porque o `settings.gradle.kts` inclui todos.)

Variaveis: `PORT`, `TABLE_OPEN_TTL_MINUTES` (30), `RATE_LIMIT_PER_MINUTE` (120 por IP; no cluster local 2000, porque o Docker Desktop entrega todos os clientes com o mesmo IP interno), `GUEST_CREATION_PER_MINUTE` (10 por IP; local 200), `TRUST_FORWARDED_FOR` (`true` atras do Traefik: usa o ultimo IP de `X-Forwarded-For` no limite de taxa).

Exemplo (toda chamada de `/api/lobby` leva `X-Protocol-Version: 2`; depois do convidado, `Authorization: Bearer <token>`):

```bash
H='X-Protocol-Version: 2'
curl -H "$H" -d '{"name":"Ana"}' http://192.168.1.5/api/lobby/guests
curl -H "$H" -H "Authorization: Bearer $TOKEN" -d '{"rules":{"mode":"INDIVIDUAL","rulesJson":"{}"}}' http://192.168.1.5/api/lobby/tables
curl http://192.168.1.5/m/<ID>      # pagina do convite
```

### Login Google (etapa 2.6, §18.1/§18.5)
`POST /api/lobby/accounts/google` verifica o ID token (RS256, emissor e `aud` do Google, JWKS por `kid`) e adota a carteira
do convidado do aparelho uma vez (`walletFromGuest`). Variaveis em `values.yaml` (configuracao normal, **nao** Secret:
`GOOGLE_CLIENT_ID` e o client_id OAuth tipo "Web" do projeto Google Cloud do app; ele vai no proprio ID token como `aud`
e e publico por natureza):
- `GOOGLE_CLIENT_ID` - sem ele a rota responde `LOGIN_INDISPONIVEL` (503) e o login fica desligado (aviso no log).
- `GOOGLE_JWKS_URL` (opcional) - padrao `https://www.googleapis.com/oauth2/v3/certs`.

**Egress para o Google (pendente de decisao do usuario).** O namespace nega toda saida para a internet por padrao
(`infra/networkpolicies.yaml`). Sem uma liberacao, o lobby nunca alcanca o JWKS do Google e toda tentativa de login
responde `LOGIN_INDISPONIVEL` (503, "Não foi possível falar com o Google agora") mesmo com `GOOGLE_CLIENT_ID` configurado
e o verificador ativo. A liberacao fica **fora** do `networkpolicies.yaml` padrao, em `infra/networkpolicy-lobby-egress-google.yaml`
(so o lobby-service, so porta 443, nega as faixas privadas RFC1918). Aplique quando decidir permitir essa saida:
```bash
kubectl --context docker-desktop apply -f deploy/infra/networkpolicy-lobby-egress-google.yaml
```
O economy-service tambem ganhou uma rota nova nesta etapa (`POST /internal/economy/accounts/{accountId}/adopt`,
migracao `V3__conta_google.sql`): implante lobby-service **e** economy-service juntos, na mesma tag.

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
2. Cada humano abre `ws://<servidor>/ws/match/<id>` com `X-Protocol-Version: 2` e `Authorization: Bearer <token de convidado>` **no handshake HTTP** (o token nunca vai na URL). O game-service valida a versao (426 "Atualize o aplicativo" / 503 "Servidor em manutenção"), o token no lobby (`POST /internal/guests/resolve`, 401 se invalido) e o assento (404 sem partida, 403 se nao e da mesa) **antes** do upgrade.

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
Mensagem do cliente ate 16 KiB (frame maior fecha a conexao), 10 mensagens/s por conexao (rajada 20), ping a cada 15 s e timeout de 45 s, **1 conexao por assento** (a nova assume e a antiga recebe `replaced` e e fechada), 60 handshakes/min por IP, `MAX_MATCHES` partidas por pod. Sessao sem atividade por 2 h e descartada. Queda de conexao **nao e abandono** no tempo indeterminado (a mesa espera); relogio, abandono e encerramento: ver "Etapa 1.3c". Sessao so e descartada por inatividade quando **todos** os humanos ficam desconectados por `IDLE_TTL_MS` (2 h).

### Prova no cluster (cliente de linha de comando)
```bash
cd online && ./gradlew.bat :probe:run --args="192.168.1.5 200 8"     # servidor, pontuacao-alvo, queda do Beto apos N acoes
```
Cria 2 convidados, mesa dupla com 2 robos, inicia, conecta os dois por WebSocket, joga ate o fim pelas `legal` das vistas, envia uma jogada ilegal de proposito (recusada), derruba a conexao do Beto e reconecta pelo mesmo assento.

Saida real da prova (imagem `c0068c4`, ritmo padrao `BOT_DELAY_MS=1200`, alvo 200; HEAD da etapa): handshake com versao 0 -> `426 {"code":"VERSAO_INCOMPATIVEL","message":"Atualize o aplicativo"}`; sem token -> `401`; `GET /internal/sim` pelo ingress -> `404`; Ana e Beto conectados nos assentos 0 e 1; jogada ilegal de Ana -> `RECUSADA ... ILLEGAL (MUST_DRAW_FIRST) recusas=1`; Beto derrubado (ultimo seq 8) e, 3 s depois, `Welcome: assento 1, lastSeq=8` com a vista atual; `FIM DO JOGO` com o mesmo placar nas duas vistas.

## Etapa 1.3c: relogio de jogada, abandono e mesa encerrada (protocolo 2)

**O protocolo subiu para 2** (mensagem nova `closed`, `turn` e `endReason` na vista, `reason` em `seats`): app e servidor de versoes diferentes recebem 426 "Atualize o aplicativo" / 503 "Servidor em manutenção". Implante lobby e game-service **juntos**.

### Relogio de jogada (§18.4)
- Com `UM_MINUTO` ou `CINCO_MINUTOS` a **vez** do humano conta (comprar, baixar e descartar sao uma contagem so; reinicia quando o assento da vez muda ou comeca partida nova). Esgotar = **abandono** (nunca se joga por ele). `INDETERMINADO`: a mesa espera.
- **Queda de conexao nao para o relogio** (com tempo definido). Sem tempo, a mesa espera o jogador voltar.
- Toda vista (`update`) traz `turn: {seat, limitMs, leftMs}` para **todos** os assentos durante a vez de um humano (`null` sem tempo, na vez de robo, entre partidas e no fim). O cliente conta `leftMs` a partir do recebimento (sem comparar relogios).
- Os prazos disparam antes de qualquer comando: uma jogada que chega depois do fim da vez ja encontra o abandono feito. Nos testes o relogio e injetado (nenhum sleep).

### Abandono (desistir ou tempo)
- Um **robo assume** o assento (nivel `TableRules.robotLevel`; `seats` traz `change: RESIGNED` e `reason: DESISTIU|TEMPO`); o jogador recebe `resigned` e a conexao fecha. **Sem volta** (`ASSENTO_ABANDONADO`).
- **Excecao 1x1 entre dois humanos** (`INDIVIDUAL` com os dois assentos humanos): sem robo; o outro vence na hora (`view.winner`, `view.endReason = ABANDONO`, `stage = GAME_OVER`); a partida em andamento nao e pontuada (§13.1).
- Cada abandono vira um `AbandonEvent` em memoria (motivo, instante, segundos desde o inicio do jogo, punicao e aposta da mesa, `withinGrace` = antes de 60 s, `betRefunded`), entregue em `MatchEnd.abandonments` para a economia da fase 2. **Nao mexe em moedas.** O log traz so assento, motivo, segundos e punicao (nunca guestId, nome ou token).

### Encerrar a mesa em jogo (§18.4)
- `DELETE /api/lobby/tables/{id}` (anfitriao): se a mesa esta EM_JOGO, o lobby chama `POST /internal/matches/{id}/close` `{hostGuestId}` no game-service (segredo; fora do ingress). O game-service registra o abandono do anfitriao (`ANFITRIAO_ENCERROU`; a punicao da mesa vale para ele; os demais ficam com `othersRefunded`), **nenhum robo assume**, manda `closed` a todos e fecha as conexoes (1000). Game-service inalcancavel: `503 PARTIDA_INDISPONIVEL` e a mesa segue EM_JOGO (o lobby nunca diz "encerrada" com o jogo rodando); 404 do game-service (partida ja sumiu) conta como encerrada.
- O game-service avisa o lobby (`POST /internal/tables/{id}/ended` `{reason}`, segredo, fora do ingress) quando o jogo **termina** (`PONTUACAO`), **abandono** no 1x1, **encerrado** ou **inatividade**: a mesa EM_JOGO vira ENCERRADA sem esperar o TTL de 6 h. O aviso sai do ator da partida, com 5 tentativas (0,5 s, 2 s, 5 s, 15 s); idempotente.

### Inatividade
Sem relogio de jogada nao se descarta jogo com humano conectado. A sessao so e descartada quando **todos** os humanos ficam desconectados por `IDLE_TTL_MS` (padrao 2 h), registrada como `INATIVIDADE` e avisada ao lobby. Voltar reinicia a contagem.

### Prova no cluster
```bash
cd online
./gradlew.bat :probe:run --args="192.168.1.5 200 8 jogo"       # jogo normal; no fim o lobby mostra a mesa ENCERRADA
./gradlew.bat :probe:run --args="192.168.1.5 200 8 desistir"   # 1x1 entre humanos: Beto desiste, Ana vence na hora
./gradlew.bat :probe:run --args="192.168.1.5 200 8 encerrar"   # anfitriao encerra: TableClosed nos dois, lobby ENCERRADA
./gradlew.bat :probe:run --args="192.168.1.5 200 8 tempo"      # duplas com 1 MIN: 60 s depois um robo assume (leva ~1 min)
```

Saida real da prova (imagens `59d92bb`, protocolo 2, tempo real de 1 minuto; no Windows com espacos no caminho use `:probe:installDist` e `java -cp "lib/*" com.gtranca.online.probe.MainKt <args>`):
- `desistir`: Beto envia `Resign`; Ana recebe `seats ... RESIGNED (DESISTIU)` com tipos `[HUMANO, HUMANO*]` (nenhum robo) e a vista `stage=GAME_OVER, winner=0, endReason=ABANDONO`; lobby: `ENCERRADA`.
- `encerrar`: Beto (nao anfitriao) -> 403; Ana encerra -> 200; os dois recebem `TableClosed` e a conexao fecha com `1000 Mesa encerrada`; lobby `ENCERRADA`; reconectar -> `404 MESA_INEXISTENTE`.
- `tempo`: vista com `turn=TurnClock(seat=0, limitMs=60000, leftMs=59997)`; 60 s depois `seats ... RESIGNED (TEMPO)`, tipos `[ROBO*, HUMANO, ROBO, ROBO]`, a Ana recebe `YouResigned` e a conexao fecha; a mesa segue EM_JOGO; o anfitriao encerra e o lobby fica `ENCERRADA`.
- `jogo` (normal, com queda e volta do Beto): `FIM DO JOGO` com o mesmo placar nas duas vistas e, 2 s depois, lobby `estado=ENCERRADA` (aviso do game-service).

## Chave do aplicativo (§18.2 "Só o aplicativo oficial")
Cada pedido do app (lobby e handshake do WebSocket) leva `X-App-Time` e `X-App-Signature` (HMAC-SHA256 com a chave do app de `método
caminho
horário
token`, horário com ±5 min). A chave fica **fora do repositório**: no cluster, no Secret `gtranca-app-key`; no app, em `local.properties` (`online.appKey=...`). Sem o Secret os serviços sobem sem verificar (aviso no log).

```bash
KEY=$(openssl rand -base64 32 | tr -d '=+/' | cut -c1-40)
kubectl --context docker-desktop -n gtranca create secret generic gtranca-app-key --from-literal=APP_SIGNING_KEY="$KEY"
kubectl --context docker-desktop -n gtranca rollout restart deploy/lobby-service deploy/game-service
# probe com a mesma chave:
APP_SIGNING_KEY="$KEY" ./gradlew.bat :probe:run --args="192.168.1.5"
```

## Postgres, backup e rede

### Layout de `online/deploy/`
```
helm/<servico>/            um chart por servico (lobby-service, game-service)
infra/postgres.yaml        Service + StatefulSet do Postgres (comum local/nuvem)
infra/postgres-backup.yaml CronJob de pg_dump diario
infra/networkpolicies.yaml negar por padrao + liberacoes
infra/local/               SO cluster local: StorageClass gtranca-local, PVs hostPath, PVCs
infra/cloud/               nuvem: os mesmos PVCs com a StorageClass do provedor (nao testado)
infra/traefik-values.yaml, infra/nats-values.yaml
```

### Secrets necessarios (o usuario cria; nunca no repositorio)
`gtranca-internal` (INTERNAL_SECRET), `gtranca-app-key` (APP_SIGNING_KEY, opcional) - comandos acima - e:
```bash
kubectl --context docker-desktop -n gtranca create secret generic postgres-credentials \
  --from-literal=POSTGRES_USER=gtranca --from-literal=POSTGRES_DB=gtranca \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -hex 24)"
```

### Onde ficam os dados (local)
O Kubernetes do Docker Desktop roda no **modo kind**: o no (`desktop-control-plane`) e um conteiner **sem acesso as pastas do Windows** (conferido: nao existe `/run/desktop/mnt/host` no no, e o Docker Desktop nao deixa adicionar montagens a ele). Por isso:
- **Dados vivos**: `/var/lib/gtranca/postgres` do no (PV `gtranca-postgres-data`, hostPath, `Retain`). Fica num volume do Docker: sobrevive a apagar pod/StatefulSet/PVC e a reiniciar o Docker Desktop; **some com "Reset Kubernetes cluster"** ou desinstalar o Docker Desktop.
- **Dumps**: `/var/lib/gtranca/backups` do no (PV `gtranca-postgres-backups`), copiados para **`C:\Users\willi\gtranca-data\backups`** (fora do Git e fora do OneDrive). E o dump (`pg_dump -Fc`), nao a pasta de dados, que serve para levar o banco para a nuvem: a pasta crua so funciona na mesma versao maior do Postgres e nao entra num Postgres gerenciado.
- Alternativa (decisao do usuario): trocar o Kubernetes do Docker Desktop para o modo kubeadm expoe `C:\` no no em `/run/desktop/mnt/host/c/...`, mas recria o cluster (Traefik, servicos e Secrets precisam ser refeitos) e montagens do Windows costumam brigar com o dono 999/permissao 0700 que o Postgres exige.

### Subir (primeira vez ou apos reset do cluster)
```bash
# pastas no no, com dono 999 (hostPath ignora fsGroup)
MSYS_NO_PATHCONV=1 docker exec desktop-control-plane sh -c 'mkdir -p /var/lib/gtranca/postgres /var/lib/gtranca/backups && chown 999:999 /var/lib/gtranca/postgres /var/lib/gtranca/backups && chmod 700 /var/lib/gtranca/postgres'
kubectl --context docker-desktop apply -f deploy/infra/local/postgres-storage.yaml -f deploy/infra/postgres.yaml -f deploy/infra/postgres-backup.yaml -f deploy/infra/networkpolicies.yaml
kubectl --context docker-desktop -n gtranca rollout status sts/postgres
```
Na nuvem: troque `infra/local/postgres-storage.yaml` por `infra/cloud/postgres-storage.yaml` (com a StorageClass do provedor); o resto e igual.

**Recriar o PVC apos apaga-lo** (o PV `Retain` fica `Released` e nao religa sozinho):
```bash
kubectl --context docker-desktop patch pv gtranca-postgres-data --type merge -p '{"spec":{"claimRef":{"uid":null,"resourceVersion":null}}}'
kubectl --context docker-desktop apply -f deploy/infra/local/postgres-storage.yaml
```

### Backup e restauracao (testado em 31/10/2026)
CronJob `postgres-backup`: 03:30 UTC, `pg_dump -Fc` em `/backups/<db>-<data>.dump`, apaga dumps com mais de 7 dias (`BACKUP_KEEP_DAYS`).
```bash
K="kubectl --context docker-desktop -n gtranca"
# disparar agora
$K create job --from=cronjob/postgres-backup backup-manual-$(date +%s)
# copiar os dumps para o Windows (manual; pode virar tarefa do Agendador de Tarefas do Windows)
MSYS_NO_PATHCONV=1 docker cp desktop-control-plane:/var/lib/gtranca/backups/. "C:/Users/willi/gtranca-data/backups"
# restaurar um dump do Windows (substitui os objetos existentes)
F="C:/Users/willi/gtranca-data/backups/gtranca-AAAAMMDD-HHMMSS.dump"
$K exec -i postgres-0 -- sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < "$F"
```
Teste feito: tabela `restore_test` com uma linha -> job manual -> copia para o Windows -> `drop table` -> `pg_restore` do arquivo no Windows -> a linha voltou -> tabela apagada. Persistencia: linha sobreviveu a apagar o pod e a apagar StatefulSet + PVC e recriar.

### Endurecimento
Todos os pods: `runAsNonRoot` com uid fixo (servicos 10001, Postgres/backup 999), `seccompProfile: RuntimeDefault`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `readOnlyRootFilesystem` (emptyDir em `/tmp`; no Postgres tambem `/var/run/postgresql`), `automountServiceAccountToken: false`, requests e limite de memoria, startupProbe. Imagens com tag fixa em `values.yaml` (`image.tag`, hash do commit; Postgres `17.11`): troque com `--set image.tag=$TAG` a cada build.

### NetworkPolicy (`infra/networkpolicies.yaml`)
Nega tudo no namespace `gtranca` e libera: DNS (kube-system, 53 UDP/TCP) para todos; Traefik (namespace `traefik`) -> lobby e game (8080); lobby <-> game (8080); Postgres (5432) so a partir de pods `app: economy-service`, `app: lobby-service` (2.4) e `app: postgres-backup`; Traefik e game-service -> economy-service (8080). Etapa 2.3: tambem lobby -> economy e economy -> lobby (8080). Nenhum servico sai para a internet.
**O kindnet do Docker Desktop APLICA as politicas** (conferido: um pod qualquer nao alcanca o lobby nem o Postgres; ingress, lobby<->game, backup->Postgres e a prova ponta a ponta seguem funcionando). Servico novo no namespace precisa da sua liberacao aqui, senao fica mudo.

## economy-service (saldo, ganho diario, compras; etapa 2.2)
Chart `helm/economy-service`. Usa os Secrets ja existentes (o usuario cria; nada no repositorio):
- `postgres-credentials` (chaves `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`): viram `DB_USER`/`DB_PASSWORD`; `DB_URL` = `jdbc:postgresql://postgres:5432/$(POSTGRES_DB)` (expansao do Kubernetes).
- `gtranca-app-key` (chave `APP_SIGNING_KEY`): obrigatoria; sem ela o pod nao sobe.
- `gtranca-internal` (chave `INTERNAL_SECRET`, etapa 2.3): obrigatoria; o mesmo Secret do lobby e do game. Aplique o chart com esta referencia ANTES (ou junto) da imagem 2.3, senao o pod entra em CrashLoop.

O Flyway cria as tabelas ao iniciar (`conta`, `carteira`, `inventario`, `lancamento`, `aposta`), com o usuario `POSTGRES_USER` (dono do banco).

```powershell
# (raiz do repositorio)  docker build -f online/economy-service/Dockerfile -t gtranca/economy-service:$TAG .
helm --kube-context docker-desktop -n gtranca upgrade --install economy-service online/deploy/helm/economy-service --set ingress.enabled=true --set image.tag=$TAG
kubectl --context docker-desktop -n gtranca rollout status deploy/economy-service
```

Ingress: so `/economy` (assinado, §18.2; sem assinatura -> 401). `/internal/economy` nunca sai pelo ingress; `/health` e `/info` no ingress sao do game-service, entao confira a saude do economy por `kubectl port-forward deploy/economy-service 18080:8080` e `curl localhost:18080/health`.
Rede: Traefik, lobby e game-service (2.3, `http://economy-service/...`, Service na porta 80) -> economy (8080); economy -> lobby (`LOBBY_URL=http://lobby-service`) e -> Postgres (5432). Lobby e game recebem `ECONOMY_URL=http://economy-service` (porta 80 do Service, nao 8080).

Inicio apos reinicio do Docker Desktop: o Hikari falha rapido se o Postgres (ou o DNS) ainda nao voltou e a JVM sai (eram 8 reinicios). O initContainer `wait-for-postgres` (imagem `postgres:17.11`, so `pg_isready`, nao-root, raiz somente leitura) segura o pod ate o Postgres aceitar conexoes.
Rollout `maxUnavailable: 0` (sobe o novo antes de derrubar o antigo; o estado esta no Postgres).

Conferido em 31/10/2026: rollout ok, `/health` UP, `GET /economy/probe-k8s-01` assinado pelo ingress -> 200 (conta criada com saldo inicial), sem assinatura -> 401, `/internal/economy` pelo ingress -> 404, tabelas criadas.

## Seletor de boosters online (protocolo 2 -> 4; etapa S17.3/S18.3)

`ONLINE_PROTOCOL_VERSION` subiu de 3 para 4 (fase do seletor de boosters na vista, novo endpoint interno do economy
`/internal/economy/matches/{matchId}/seats/{seat}/boosters`, `EconomyStore.debitBoosters`). Isso muda o que o
**lobby-service** aceita em `X-Protocol-Version` mesmo sem o codigo-fonte dele ter mudado nesta etapa: a constante vem
do `:online-contracts`, compilada no jar. **Implante os tres servicos juntos** (lobby-service, game-service,
economy-service) na mesma tag, senao o app novo (protocolo 4) recebe `426 VERSAO_INCOMPATIVEL` do lobby antigo em
toda chamada `/api/lobby` (nao so nos boosters). Ordem: economy-service antes ou junto do game-service (a rota nova de
debito e chamada pelo game-service; sem ela o debito falha com 404 nas 3 tentativas e o jogo segue sem boosters, sem
perder moeda). Nenhuma variavel de ambiente nova obrigatoria (`BOOSTER_SELECT_MS`, opcional, padrao 30000). Nenhuma
NetworkPolicy nova (egress game-service -> economy-service ja existia).

Implantado em 02/11/2026 na tag `49d7282` (os tres `values.yaml` atualizados para esta tag como novo padrao):
rollout ok nos tres, 0 restarts, logs sem erro, sem migracao Flyway nova. Confirmado fim a fim pelo
`probe` (ver "Prova no cluster" acima): handshake com `X-Protocol-Version: 3` -> `426`; com `4` passa a checagem de
versao (cai no filtro de assinatura do app a seguir, esperado sem `APP_SIGNING_KEY` no cliente); jogo completo com
seletor de boosters nos dois assentos (`BoosterPrompt` -> confirmacao vazia -> `BoostersConfirmed`/`BOOSTERS_CHOSEN`),
queda e reconexao do Beto, placar coerente nas duas vistas, mesa `ENCERRADA` no lobby ao final.
