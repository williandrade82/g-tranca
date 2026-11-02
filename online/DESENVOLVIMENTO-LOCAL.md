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

Sobe Postgres + `lobby-service` + `game-service` + `economy-service` + `admin-api`, cada um numa porta do
host (sem Traefik/ingress; sem NetworkPolicy — tudo na mesma rede do Compose):

| Servico | Porta no host | Endereco interno (entre servicos) |
|---|---|---|
| postgres | 5432 | `postgres:5432` |
| lobby-service | 8081 | `http://lobby-service:8080` |
| game-service | 8082 | `http://game-service:8080` |
| economy-service | 8083 | `http://economy-service:8080` |
| admin-api | 8084 | *(nao chamado pelos outros)* |

```bash
curl http://localhost:8081/health   # lobby
curl http://localhost:8082/health   # game
curl http://localhost:8083/health   # economy
curl http://localhost:8084/health   # admin-api
```

Reconstruir so um servico depois de mudar o codigo: `docker compose up --build lobby-service`.
O cache do Gradle (`--mount=type=cache,target=/root/.gradle` nos Dockerfiles) continua valendo — o
`docker compose build` usa o BuildKit do Docker Desktop normalmente, mesmo ganho de velocidade medido
em `deploy/README.md` (build limpo ~9 min, build incremental de uma linha ~1 min).

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
