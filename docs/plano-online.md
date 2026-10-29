# Plano: jogar online (pela internet)

Status: **proposta para decisão** (28/10/2026); nada implementado. Fluxo de sempre: `definition.md` e `docs/telas.md` primeiro, depois testes, código, `rules-auditor`, um commit por etapa, APK com hash. Este plano parte do que já existe (multijogador local, §16) e da visão de 15/10 ("online futuro: login, servidor Ktor reusando `:core-engine`, salas privadas, amigos/presença, carteira online, radar público").

> **"Ficar online" aqui = jogar com outras pessoas pela internet.** Publicar o app na Play Store é outra frente (lista de pendências em `docs/loja/` e na memória do projeto) e entra só como fase final deste plano.

## Objetivo
Qualquer pessoa joga uma mesa de Tranca com amigos e família **sem estar na mesma sala**: cria uma sala, manda o código, e os celulares jogam pela internet, com as mesmas regras, perfis de regra, boosters e apostas de hoje.

## Fora de escopo (por ora)
- Compra de moedas com dinheiro: **nunca** (já decidido, §14.6).
- iOS e web. Chat livre de texto (só as reações do b3, que não têm moderação a fazer).
- Torneios e ranking mundial.

## O que já temos e vamos reaproveitar
| Peça | Estado | Uso no online |
|---|---|---|
| `:core-engine` (JVM puro) | motor, regras, validação | roda **igual no servidor**; `legalActions` continua a única fonte de jogadas |
| `PlayerView` por assento | visão sem informação oculta | é o que o servidor manda a cada celular |
| `:net` (protocolo v19, `ProtocolCodec`, `HostSession`/`GuestSession`, `ReliableLink`) | multijogador local | o protocolo e a lógica de sessão viram a base; muda o transporte |
| `LocalTransport` | interface; hoje Nearby e memória | nova implementação por WebSocket (ver "Transporte") |
| Recrutamento, sucessão de anfitrião, reconexão, pausa (§16.2–16.10) | feito | reaproveitar as regras; a sucessão deixa de ser necessária com servidor autoritativo |
| `:sim` | bot × bot | teste de carga e de regressão do servidor |

## Decisão central: quem é a autoridade?
| Opção | Como | Prós | Contras |
|---|---|---|---|
| **A. Servidor dedicado autoritativo (recomendada)** | um servidor Kotlin (Ktor) roda o motor; celulares só enviam `Action` e recebem `PlayerView` | segurança de verdade (ninguém vê cartas alheias nem adultera saldo); queda do anfitrião deixa de existir; mesa continua mesmo sem o criador | tem custo mensal e manutenção |
| B. Celular anfitrião + relay na internet | mantém o modelo atual, servidor só repassa bytes | quase sem lógica no servidor; mais barato | anfitrião continua vendo todas as cartas (hoje já é assim); saldo e inventário seguem adulteráveis; queda do anfitrião ainda dói |

**Recomendação: A.** O motor já é Kotlin/JVM puro, então o servidor é "o `HostSession` rodando na nuvem". O esforço extra paga a **carteira e o inventário protegidos**, que o online exige (a loja e as apostas viram dinheiro de verdade no jogo).

## Arquitetura proposta
### Módulos novos
- `:server` (Kotlin/JVM, Ktor + WebSocket): salas, partidas, contas, carteira, inventário, loja. Depende de `:core-engine`, `:ai` (robôs nas mesas) e `:net` (protocolo).
- `:net` ganha `WebSocketTransport` (cliente, no app) e o lado servidor do mesmo protocolo. O resto do `:net` não muda de natureza.
- `:app` ganha tela de **Entrar/Conta**, **Salas** e **Amigos**; o jogo em si usa a mesma mesa multijogador de hoje (3.14).

### Transporte
`LocalTransport` hoje mistura "descobrir mesas por perto" com "enviar bytes". Separar em: `Transport` (só conectar, enviar, receber, cair) e `Discovery` (Nearby hoje; lista de salas do servidor amanhã). O jogo não deve saber qual dos dois está por baixo. É a **primeira refatoração** (etapa 1) e tem testes de regressão do multijogador local.

### Identidade
- **Convidado anônimo** primeiro (id aleatório guardado no aparelho): permite testar o online sem login.
- **Login com Google** (via Credential Manager / Firebase Auth) na fase de contas. Foto opcional da conta, como já previsto.
- O servidor emite um **token de sessão** curto; reconexão usa o token (como o de §16.5).

### Dados no servidor
Banco simples (Postgres gerenciado ou SQLite no começo): `conta`, `carteira` (saldo, último ganho diário, resultado total), `inventário` (quantidades de boosters), `compras` (idempotentes, como o `ShopRepository`), `amizades`, `partidas` (resultado e aposta, para auditoria).
- **Migração das moedas locais:** a carteira local é adulterável e "aceita"; no online ela **não vale** (começa com o saldo inicial) ou migra uma vez com teto. *Decisão de produto, ver abaixo.*
- A **loja** (§17.10) passa a debitar e creditar **no servidor** numa só transação; isso resolve de vez o problema de dois arquivos.

### Regras novas do online (entram no `definition.md` como §18, antes do código)
Sala (código curto, privada), assentos e recrutamento (a mecânica de §16.2 vale, trocando "jogador por perto" por "amigo/código"); tempo de jogada (ex.: 45 s, depois um robô joga a vez e o jogador pode voltar); abandono (robô assume, aposta perdida, §16.9); reconexão (token, §16.5); boosters no assento (§17.4, já pensado para isso); aposta com saldo do servidor; limites (n.º de salas por conta, taxa de mensagens).

### Segurança
- Servidor **nunca** manda `GameState` completo, só `PlayerView` (a regra que já vale para bots e telas).
- Toda `Action` é validada por `legalActions` no servidor; cliente que mandar jogada ilegal é ignorado e contado.
- TLS (wss), tokens com expiração, limite de taxa, tamanho máximo de mensagem, validação do protocolo versionado (versão antiga recebe aviso para atualizar, como "Versão do jogo diferente").
- Nomes e fotos de perfil compartilhados com desconhecidos: moderação mínima (lista de palavras, denúncia) **antes** do radar público.

## Fases (cada uma termina com algo jogável e um critério de saída)
| Fase | Entrega | Critério de saída |
|---|---|---|
| **0. Decisões e docs** | respostas às perguntas abaixo; `definition.md` §18; `docs/telas.md` (Conta, Salas, Amigos); plano de custos | usuário aprova os documentos |
| **1. Servidor mínimo** | `:server` com salas privadas por código, convidado anônimo, mesa 1×1 e duplas, robôs, reconexão; `WebSocketTransport`; separar `Transport`/`Discovery` | 2 e depois 4 celulares jogam um jogo inteiro por **dados móveis**, sem moedas; `:sim` joga 1000 partidas contra o servidor sem estado ilegal |
| **2. Contas e economia no servidor** | login Google, carteira e inventário no servidor, ganho diário, apostas, loja e presente diário server-side, migração (ou não) do saldo local | compra e aposta funcionam mesmo se o app for reinstalado; teste de que o saldo nunca fica negativo sob concorrência |
| **3. Amigos e presença** | lista de amigos por código/convite, "online agora", convidar para a sala, notificação push (FCM) | convidar um amigo que está com o app fechado e ele entrar na mesa |
| **4. Mesas públicas (radar)** | lista de salas abertas com regras e aposta visíveis, entrar sem convite, denúncia e bloqueio | 50 mesas simultâneas sem degradar; moderação básica ativa |
| **5. Endurecimento e publicação** | observabilidade (logs, métricas, alertas), backup do banco, LGPD (exportar/apagar conta), política de privacidade e "Segurança dos dados" da Play, keystore, ícone, capturas | beta fechado na Play com 20+ pessoas por 2 semanas |

Cada fase tem seu branch/PR, APK com hash e testes: motor e servidor em JVM (rápidos), protocolo com `MemoryTransport` e com um servidor de teste, e um **teste de caos** (queda de rede, mensagens duplicadas e fora de ordem).

## Custos e hospedagem (estimativa, a confirmar)
Mesa de Tranca é leve (poucas mensagens por minuto). Um servidor pequeno (1 vCPU, 512 MB–1 GB) aguenta centenas de mesas. Opções: VPS barata ou Cloud Run/Fly.io + Postgres gerenciado; ordem de grandeza de **US$ 10–30 por mês** no começo. Firebase Auth e FCM são gratuitos nesse volume. Domínio e certificado: poucos reais por ano.

## Riscos
1. **Escopo de conta/economia** é maior que o jogo online em si (migração, antifraude, LGPD). Mitigação: fase 1 sem contas.
2. **Latência e queda de rede em celular**: a mesa já tem pausa e reconexão (§16.8); precisa do tempo de jogada (robô joga a vez) para não travar a mesa.
3. **Divergência de versões** entre app e servidor: protocolo versionado e aviso de atualização; servidor aceita a versão atual e a anterior por um tempo.
4. **Moderação** de nomes/fotos em mesas públicas: só abrir o radar (fase 4) com denúncia e bloqueio prontos.
5. **Custo e operação** recaem sobre uma pessoa: automatizar deploy e alertas desde a fase 1.
6. **Trapaça por saldo local** deixa de existir online, mas **enquanto houver as duas carteiras** (local e online) a interface deve deixar claro qual está em uso.

## Decisões que preciso de você (com a minha recomendação)
1. **Autoridade:** servidor dedicado (A) ou anfitrião + relay (B)? *Recomendo A.*
2. **Quem pode jogar online primeiro:** só amigos por código (fases 1–3) e radar público depois (fase 4)? *Recomendo sim.*
3. **Login:** convidado anônimo + Google, ou também e-mail/senha? *Recomendo só Google (menos manutenção e menos dados sensíveis).*
4. **Moedas e boosters locais:** começam do zero no online, migram uma vez (com teto) ou ficam separados (modo "offline" e modo "online" com carteiras diferentes)? *Recomendo carteiras separadas: o offline continua como é, sem risco de fraude no online.*
5. **Tempo de jogada** e punição por abandono: 45 s e robô assume a vez? Há perda de moedas além da aposta? *Recomendo 45 s, robô joga a vez, sem punição extra.*
6. **Hospedagem e orçamento mensal** aceitável, e quem opera (você mesmo?). *Recomendo começar em um serviço gerenciado (Cloud Run ou Fly.io) para não administrar máquina.*
7. **Faixa etária e moderação:** o app é "para jogar em família"; mesas públicas abrem a porta para desconhecidos. Aceita adiar o radar público? *Recomendo adiar e decidir depois do beta com amigos.*

## Próximo passo sugerido
Você responde as 7 perguntas (pode ser só "ok" para as recomendadas). Em seguida eu escrevo o `definition.md` §18 e o esboço das telas em `docs/telas.md` (fase 0) e abro a fase 1 em um branch próprio, começando pela separação `Transport`/`Discovery`.
