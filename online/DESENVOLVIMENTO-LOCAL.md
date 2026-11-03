# Desenvolvimento local do jogo online (Docker Compose)

Decisao de 02/11/2026: o dia a dia de desenvolvimento do backend online passa a usar **Docker Compose**
em vez do Kubernetes local (Docker Desktop). Motivo: a maquina de desenvolvimento tem recursos limitados
e o cluster (control-plane kind, Traefik, NetworkPolicy, Helm) consome mais do que o necessario so para
iterar no codigo. O Kubernetes local continua existindo e vira o ambiente de **homologacao**, mais perto
da producao (ver `deploy/README.md`, que descreve o cluster — Helm, NetworkPolicy, Postgres com backup,
hardening dos pods). Nada no `deploy/` muda com isto.

## Subir tudo

```bash
cd online
cp .env.example .env   # uma vez; edite os segredos (gere com openssl rand, ver comentarios no arquivo)
docker compose up --build
```

Sobe Postgres + `lobby-service` + `game-service` + `economy-service` + `admin-api` + `admin-web`, cada um
numa porta do host (sem Traefik/ingress; sem NetworkPolicy — tudo na mesma rede do Compose):

| Servico | Porta no host | Endereco interno (entre servicos) |
|---|---|---|
| postgres | 5432 | `postgres:5432` |
| lobby-service | 8081 | `http://lobby-service:8080` |
| game-service | 8082 | `http://game-service:8080` |
| economy-service | 8083 | `http://economy-service:8080` |
| admin-api | 8084 | `http://admin-api:8080` (admin-web faz proxy pra ca) |
| admin-web | 8085 | *(nao chamado pelos outros)* |

```bash
curl http://localhost:8081/health   # lobby
curl http://localhost:8082/health   # game
curl http://localhost:8083/health   # economy
curl http://localhost:8084/health   # admin-api
curl http://localhost:8085         # admin-web (tela Entrar); http://localhost:8085/admin/login e o proxy pro admin-api
```

Console de gerenciamento: abra `http://localhost:8085` no navegador. Preencha `ADMIN_SEED_EMAIL` e
`ADMIN_SEED_PASSWORD` no `.env` **antes** do primeiro `docker compose up` (o admin-api so semeia o
primeiro administrador se a equipe estiver vazia); sem isso ninguem consegue entrar. O Painel mostra a
saude de lobby/game/economy-service porque o admin-api ja vem configurado, neste Compose, com
`LOBBY_SERVICE_HEALTH_URL`/`GAME_SERVICE_HEALTH_URL`/`ECONOMY_SERVICE_HEALTH_URL` apontando para o
`/health` de cada um pela rede interna.

Reconstruir so um servico depois de mudar o codigo: `docker compose up --build lobby-service` (ou
`admin-web`, `admin-api`...). O cache do Gradle (`--mount=type=cache,target=/root/.gradle` nos
Dockerfiles dos servicos Kotlin) e do npm (`--mount=type=cache,target=/root/.npm` no Dockerfile do
admin-web) continuam valendo — o `docker compose build` usa o BuildKit do Docker Desktop normalmente,
mesmo ganho de velocidade medido em `deploy/README.md` (build limpo ~9 min, build incremental de uma
linha ~1 min para os servicos Kotlin; o admin-web e bem mais rapido, so npm + Vite).

Apagar tudo, inclusive o volume do Postgres (perde os dados locais): `docker compose down -v`.

## O que fica de fora (por enquanto)

- **Ingress/roteamento por caminho**: no cluster, Traefik expoe um unico host com `/api/lobby`, `/economy`
  etc. no mesmo endereco. Aqui cada servico tem sua propria porta. Testar pelo **celular** apontando para
  este ambiente (em vez do cluster) exigiria decidir um proxy local ou reconfigurar o app por servico —
  nao fiz isso agora; decisao do usuario se/quando precisar.
- **NetworkPolicy, hardening dos pods, backup do Postgres**: continuam so no cluster (fazem sentido para
  homologacao/produção, nao para iterar localmente).
- **Chave do app (`APP_SIGNING_KEY`) e `GOOGLE_CLIENT_ID`**: opcionais no `.env`; sem eles os servicos
  sobem do mesmo jeito que no cluster sem o Secret (aviso no log, sem verificar).

## Quando usar o cluster Kubernetes local

Continua em `deploy/README.md`, sem mudanca. Use o cluster quando precisar validar algo que so existe
la: NetworkPolicy, Helm (`values.yaml`, probes, hardening), Postgres com backup/restauracao, ou testar
pelo celular via Traefik/ingress.
