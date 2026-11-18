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
| edge (proxy) | 8080 | *(porta que o app usa: `<IP do PC>:8080` no campo "Servidor online")* |
| lobby-service | 8081 (só localhost) | `http://lobby-service:8080` |
| game-service | 8082 (só localhost) | `http://game-service:8080` |
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

**`ADMIN_INTERNAL_SECRET` (Sec18.7, Painel e tela Contas): preencha no `.env` com um valor PROPRIO,
diferente do `INTERNAL_SECRET`.** Sem ele (ou se for igual ao `INTERNAL_SECRET`), o lobby-service e o
economy-service desligam as rotas `/internal/admin/...` (aviso no log deles) e o admin-api fica sem numeros
no Painel ("indisponivel" em tudo) e com a tela Contas respondendo 503 — sem nenhum aviso na tela, so no
log. Gere com `openssl rand -hex 32`, como os outros segredos deste arquivo.

Reconstruir so um servico depois de mudar o codigo: `docker compose up --build lobby-service` (ou
`admin-web`, `admin-api`...). O cache do Gradle (`--mount=type=cache,target=/root/.gradle` nos
Dockerfiles dos servicos Kotlin) e do npm (`--mount=type=cache,target=/root/.npm` no Dockerfile do
admin-web) continuam valendo — o `docker compose build` usa o BuildKit do Docker Desktop normalmente,
mesmo ganho de velocidade medido em `deploy/README.md` (build limpo ~9 min, build incremental de uma
linha ~1 min para os servicos Kotlin; o admin-web e bem mais rapido, so npm + Vite).

Apagar os containers (mantem os dados): `docker compose down`. **O Postgres usa bind mount** (pasta
`${GTRANCA_STORAGE:-C:/Projetos/g-tranca-storage}/postgres-data`, nao um volume nomeado do Docker) —
`docker compose down -v` **nao apaga esses dados** (confirmado: `docker volume ls` nao lista nada deste
projeto). Para zerar o banco de verdade: `docker compose down` e depois apagar essa pasta a mao.

**Equipe do console ja tem gente e o seu `ADMIN_SEED_EMAIL`/`ADMIN_SEED_PASSWORD` nao fizeram efeito?**
O log do admin-api avisa "ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD ignorados: a equipe ja tem gente" quando
`admin.membro` nao esta vazia (por exemplo, depois de alguem testar com uma conta propria). Duas saidas,
voce escolhe (as duas apagam algo, por isso nenhum script faz isso sozinho):
- **Preserva tudo:** entre com a conta que ja existe, cadastre a sua pelo `admin-api` (o admin-web ainda
  nao tem tela de Equipe) e remova a antiga:
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:8085/admin/login -H "Content-Type: application/json" \
    -d '{"email":"<email-que-ja-existe>","password":"<senha-que-ja-existe>"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)
  curl -s -X POST http://localhost:8085/admin/team -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"email":"<seu-email>","password":"<sua-senha>","role":"ADMINISTRADOR","reason":"assumindo a equipe"}'
  # depois, com o id devolvido pelo cadastro da conta antiga (GET /admin/team lista):
  curl -s -X DELETE http://localhost:8085/admin/team/<id-da-conta-antiga> -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d '{"reason":"conta de teste, nao precisa mais"}'
  ```
- **Volta ao seed do zero (so a equipe do console; lobby/economy continuam intactos, e a auditoria
  dessas acoes some com o schema):**
  ```bash
  docker compose up -d postgres
  docker compose exec postgres psql -U gtranca -d gtranca -c "DROP SCHEMA admin CASCADE"
  # preencha ADMIN_SEED_EMAIL/ADMIN_SEED_PASSWORD no .env agora, se ainda nao preencheu
  docker compose up -d admin-api
  ```

## Rodar sem Docker (servico a servico, decisao de 17/11/2026)

Para iterar no codigo mais rapido que o `docker compose up --build`, rode cada servico Kotlin direto na
JVM (plugin `application` do Gradle, ja configurado nos quatro: `lobby-service`, `game-service`,
`economy-service`, `admin-api`) contra o seu Postgres local, sem build de imagem nenhum. Decisao do
usuario: durante o desenvolvimento, so construir a imagem e implantar (Compose ou cluster) quando ele
pedir explicitamente — ver `.claude/agents/online-backend-dev.md` e `admin-console-dev.md`.

Pre-requisito: um Postgres seu rodando em `localhost:5432` (fora do Docker), com um banco e um usuario.
O exemplo abaixo usa `gtranca`/`gtranca`/`mude-isto` — **troque pelos valores do seu Postgres real**. Cada
servico migra o proprio esquema sozinho ao subir (Flyway), no mesmo banco.

Abra um terminal PowerShell por servico (suba `economy-service` e `lobby-service` primeiro; os outros
dependem deles para `GAME_SERVICE_URL`/`ECONOMY_URL`/`LOBBY_URL`):

```powershell
# economy-service (porta 8083)
$env:PORT = "8083"
$env:DB_URL = "jdbc:postgresql://localhost:5432/gtranca"
$env:DB_USER = "gtranca"
$env:DB_PASSWORD = "mude-isto"
$env:INTERNAL_SECRET = "dev-local-internal-secret"
$env:ADMIN_INTERNAL_SECRET = "dev-local-admin-secret"
$env:LOBBY_URL = "http://localhost:8081"
.\gradlew.bat :economy-service:run
```

```powershell
# lobby-service (porta 8081)
$env:PORT = "8081"
$env:DB_URL = "jdbc:postgresql://localhost:5432/gtranca"
$env:DB_USER = "gtranca"
$env:DB_PASSWORD = "mude-isto"
$env:INTERNAL_SECRET = "dev-local-internal-secret"
$env:ADMIN_INTERNAL_SECRET = "dev-local-admin-secret"
$env:GAME_SERVICE_URL = "http://localhost:8082"
$env:ECONOMY_URL = "http://localhost:8083"
.\gradlew.bat :lobby-service:run
```

```powershell
# game-service (porta 8082, sem banco - so memoria)
$env:PORT = "8082"
.\gradlew.bat :game-service:run
```

```powershell
# admin-api (porta 8084)
$env:PORT = "8084"
$env:DB_URL = "jdbc:postgresql://localhost:5432/gtranca"
$env:DB_USER = "gtranca"
$env:DB_PASSWORD = "mude-isto"
$env:ADMIN_INTERNAL_SECRET = "dev-local-admin-secret"
$env:LOBBY_SERVICE_URL = "http://localhost:8081"
$env:ECONOMY_SERVICE_URL = "http://localhost:8083"
$env:LOBBY_SERVICE_HEALTH_URL = "http://localhost:8081/health"
$env:GAME_SERVICE_HEALTH_URL = "http://localhost:8082/health"
$env:ECONOMY_SERVICE_HEALTH_URL = "http://localhost:8083/health"
$env:ADMIN_SEED_EMAIL = "admin@local.invalid"
$env:ADMIN_SEED_PASSWORD = "mude-isto-tambem"
.\gradlew.bat :admin-api:run
```

```powershell
# admin-web (porta 5173 por padrao do Vite)
cd admin-web
npm run dev
```

Observacoes:
- `INTERNAL_SECRET` e `ADMIN_INTERNAL_SECRET` precisam ser **iguais entre os servicos que rodam juntos**
  nesta sessao local; podem ser qualquer valor de teste, diferente do `.env` do Compose/cluster.
- `APP_SIGNING_KEY` e `GOOGLE_CLIENT_ID` ficam de fora (opcionais): sem eles os servicos sobem do mesmo
  jeito, sem verificar assinatura do app nem oferecer login Google — igual ao Compose.
- `gradlew.bat :servico:run` recompila so o que mudou (bem mais rapido que `docker compose build`); o
  processo fica no terminal (Ctrl+C para parar). Sem orquestracao automatica: se um servico cair, suba-o
  de novo a mao.
- Isto substitui o Compose **so para iterar no codigo**. Testar pelo celular, validar Helm/NetworkPolicy,
  ou qualquer coisa que dependa do proxy `edge`/ingress continua precisando do Compose ou do cluster (ver
  secoes abaixo e `deploy/README.md`).

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
