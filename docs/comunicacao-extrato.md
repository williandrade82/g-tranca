# Extrato da comunicação entre jogadores (o que o código faz HOJE)

> **Natureza deste documento.** É um **extrato do código em 26/10/2026** (branch `feature/selecao-jogadores`, HEAD `51e00cc`), não uma
> especificação para implementar. Descreve o que **está implementado**, inclusive onde o comportamento **contradiz** o
> `definition.md`. Os trechos de código são cópias literais, com `arquivo:linhas`. Textos de interface são os do `strings.xml`.
> Somente leitura: nenhum código foi alterado ao escrevê-lo.

## Sumário

1. Visão geral e mapa de arquivos
2. Protocolo (mensagens, limites, anúncios, o que é oculto)
3. Passo a passo tela a tela
4. Catálogo de tratamento de erro
5. Trechos-chave de código
6. Análise do bug "Mesa não encontrada por perto" ao iniciar a mesa (APK 51e00cc)
7. Adendo: revisão crítica (gaps, riscos e plano de correções)

---

## 1. Visão geral e mapa de arquivos

### 1.1 Camadas

```
 TELAS (Compose)            ui/tables/TablesScreens.kt (Iniciar novo jogo)      ui/selection/PlayerSelectionScreen.kt (Seleção)
   |  estado via StateFlow   ui/multiplayer/RecruitedTableScreen.kt (recrutado)   ui/multiplayer/MpEndMessage.kt, MultiplayerOverlay.kt
   v                         ui/AppNavHost.kt (cola: NearbyPresence, RecruitedDestination, RejoinDestination, GetReady)
 VIEWMODELS / CONTROLE      ui/selection/PlayerSelectionViewModel.kt, ui/tables/TablesViewModels.kt
   |                        multiplayer/MultiplayerHub.kt (objeto de processo: transporte, Recruitment, mesa atual)
   v                        multiplayer/MultiplayerTable.kt (papel Hosting/Guesting, fim da mesa, sessão guardada)
                            multiplayer/MultiplayerHost.kt + MultiplayerDriver.kt (jogo do anfitrião e do convidado)
 SESSÕES (módulo :net)      Recruitment.kt (presença + recrutamento fora de mesa)
   |                        HostSession.kt (mesa do jogo, anfitrião)   GuestSession.kt (mesa do jogo, convidado)
   v                        TableLayout.kt (lugares, puro)   PresencePhotos.kt (fotos)
 CONFIABILIDADE             ReliableLink.kt (número de sequência, ack, reenvio)   Codec.kt (JSON + versão)   Protocol.kt (mensagens)
   |
   v
 TRANSPORTE                 Transport.kt (interface LocalTransport + MemoryTransport dos testes)
   |                        app/.../net/nearby/NearbyTransport.kt (busca, anúncio, conexões, orçamento de buscas)
   v                        GmsNearbyApi.kt (Nearby Connections, estratégia P2P_STAR), AdvertCodec.kt (anúncios <= 100 bytes)
 NEARBY / RÁDIO             Google Play Services: BLE/Bluetooth/Wi-Fi
```

Fluxo de uma mensagem: `Payload` (Protocol.kt) -> `ProtocolCodec.encode` (JSON, Codec.kt) -> `Envelope(v, seq, p, ack)` ->
`ReliableLink.sendLocked` (guarda até o ack, reenvia) -> `LocalTransport.send` -> `NearbyTransport.send` (`api.sendBytes`,
payload BYTES, teto `ConnectionsClient.MAX_BYTES_DATA_SIZE`) -> rádio. A recepção faz o caminho inverso: `onPayloadReceived`
-> `inbox` do transporte (`TransportEvent.Received`) -> o **único leitor** dos eventos (um por vez: `Recruitment.run`,
`HostSession` ou `GuestSession`) -> `ReliableLink.receiveLocked` (descarta duplicadas/atrasadas, entrega em ordem) -> `processLocked`.

### 1.2 Papéis e quem lê o transporte

O `NearbyTransport` tem **um só** fluxo de eventos (`events`, canal ilimitado). Por isso há **um leitor por vez**:

| Momento | Leitor dos eventos | Quem passa o bastão |
|---|---|---|
| Fora de mesa, Wi-Fi do jogo ligado | `Recruitment.run()` (dentro de `MultiplayerHub.runPresence`) | `Hub.stopRecruiting()` / `rec.shutdown()` |
| Anfitrião com a mesa do jogo aberta | `HostSession.open` (`collector`) | `HostSession.close()` |
| Convidado/recrutado com a mesa do jogo | `GuestSession.join` (`collector`) | `GuestSession.release()` |

### 1.3 Mapa de arquivos

| Arquivo | Responsabilidade |
|---|---|
| `net/.../Protocol.kt` (versão 15) | Todas as mensagens `@Serializable`, `Envelope`, `TableRules`, `SeatView`, motivos de recusa |
| `net/.../Codec.kt` | `ProtocolCodec` (JSON, lê `v` antes do conteúdo), `SequencedLink` |
| `net/.../ReliableLink.kt` | Sequência, ack, reenvio (1 s, 5 vezes), controle fora da sequência |
| `net/.../Transport.kt` | `LocalTransport`, `TableAdvert`, `PresenceAdvert`, `MemoryNetwork` (testes) |
| `net/.../nearby/AdvertCodec.kt` | Codec binário dos anúncios (mesa formato 2; presença 5; "saindo" 4), teto de 100 bytes |
| `net/.../TableLayout.kt` | Lugares da mesa (VAGO/Host/Robô/Chamado/Recrutado), operações puras |
| `net/.../Recruitment.kt` | Presença, listas de recrutadores/livres, chamar/aceitar/recusar, lugares sincronizados, batimento, início |
| `net/.../PresencePhotos.kt` | Fotos pela conexão de espera |
| `net/.../HostSession.kt` | Mesa do jogo: entrada dos recrutados (`TablePlan`), início, queda, pausa, sucessão, fim |
| `net/.../GuestSession.kt` | Convidado: `join`, reconexão, vistas, sucessor, fim |
| `app/.../net/nearby/NearbyTransport.kt` | Busca/anúncio/conexão sobre o Nearby, orçamento de buscas, modo ativo, rajada |
| `app/.../net/nearby/GmsNearbyApi.kt` | Chamadas reais ao Nearby (`P2P_STAR`, `SERVICE_ID`) |
| `app/.../net/nearby/MultiplayerAccess.kt`, `NearbyPermissions.kt` | Porta de permissões/Play Services/localização |
| `app/.../multiplayer/MultiplayerHub.kt` | `runPresence`, `newTable`, `closeRecruiting`, `joining` |
| `app/.../multiplayer/MultiplayerTable.kt` | Papel da mesa atual, `join/joinAsync/host/hostAsync/rejoin`, `MpEnd`, sessão guardada |
| `app/.../ui/AppNavHost.kt` | Navegação e a "cola" (presença, convite, mesa do recrutado, Prepare-se, Voltar à mesa) |

---

## 2. Protocolo

### 2.1 Versão e envelope


- Versão: `PROTOCOL_VERSION = 15` (`net/src/main/kotlin/com/gtranca/net/Protocol.kt:27`). Aparelhos de versões diferentes **não jogam juntos**.

- Envelope (`net/src/main/kotlin/com/gtranca/net/Protocol.kt:52`): `Envelope(v, seq, p, ack)`; `seq = 0` marca **controle** (fora da sequência: `AckOnly`, `ResendRequest`, `RecruitPing/Pong`); `ack` = última mensagem recebida em ordem.

- Codec (`net/src/main/kotlin/com/gtranca/net/Codec.kt:23`): JSON em UTF-8, discriminador `"t"`, `encodeDefaults = false`, `ignoreUnknownKeys = true`; `v` é lido do JSON cru **antes** de decodificar o conteúdo (mensagem de outra versão -> `Decoded.VersionMismatch`).

- Confiabilidade (`net/src/main/kotlin/com/gtranca/net/ReliableLink.kt:160`): ack em até 200 ms (`ACK_DELAY_MILLIS`), reenvio da não confirmada a cada 1 s (`RESEND_AFTER_MILLIS`), até 5 vezes (`MAX_RESENDS`); duplicadas/atrasadas descartadas; adiantadas esperam (até 256).

- Limite de tamanho: `NearbyTransport.send` exige `bytes.size <= api.maxBytes` (`ConnectionsClient.MAX_BYTES_DATA_SIZE`); acima disso lança `IllegalArgumentException("Mensagem acima do limite do Nearby")` (`app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:527`). Foto: `MAX_PHOTO_BASE64 = 20 480` caracteres (`net/src/main/kotlin/com/gtranca/net/Protocol.kt:332`).


### 2.2 Mensagens (todas `Payload`, `@SerialName` entre parênteses)

**Recrutamento (fora de mesa, conexão de espera):**

| Mensagem | Campos | Quem envia -> quem recebe | Para quê |
|---|---|---|---|
| `RecruitHello` (`recruitHello`) | `key, name, rejoin` | livre/recrutado -> recrutador | Abre a conexão de espera; `rejoin` = voltou depois de queda |
| `RecruitInvite` (`recruitInvite`) | `version, table: RecruitTable, you, timeoutMillis=25000` | recrutador -> chamado | Convite com aposta/tipo/regras/lugares; `you` = lugar dele |
| `RecruitReply` (`recruitReply`) | `key, accepted, reason?` | chamado -> recrutador | Aceitar/recusar (`reason`: recusou, saldo, sem resposta) |
| `RecruitSync` (`recruitSync`) | `version, table, you` | recrutador -> chamados e recrutados | Lugares completos e numerados (só aplica versão maior) |
| `RecruitLeave` (`recruitLeave`) | `reason?` | recrutado -> recrutador | "Sair da mesa" / saldo insuficiente |
| `RecruitEnd` (`recruitEnd`) | `reason: REMOVED / CLOSED / CANCELLED` | recrutador -> chamado/recrutado | Removido / mesa encerrada / convite cancelado |
| `RecruitStart` (`recruitStart`) | `tableId, prepareMillis=1500` | recrutador -> recrutados | Começar Partida: entrar na mesa `tableId` |
| `RecruitPing` / `RecruitPong` | (sem campos) | os dois | Batimento a cada 400 ms (controle, `seq = 0`) |
| `RecruitBye` (`recruitBye`) | `key` | quem sai -> conectados | Aviso explícito: tire-me das listas |
| `PresencePhoto` (`presencePhoto`) | `key, photo?` | os dois | Foto (Base64, <= 20 KB) uma vez por conexão |

`RecruitTable(hostName, rules: TableRules, layout: TableLayout)`; `TableLayout(mode, slots)` com `Slot`: `Vacant`, `Host`, `Robot(persona)`,
`Called(key, name)`, `Recruited(key, name)`.

**Mesa do jogo (conexão da mesa):**

| Mensagem | Campos | Sentido | Para quê |
|---|---|---|---|
| `Hello` (`hello`) | `name, token?, seat?, roundOverSeen, photo?, inviteKey?` | convidado -> anfitrião | Entrada: com `inviteKey` = recrutado; com `token` = reingresso; sem nada em jogo = pedido de entrada (§16.7) |
| `Welcome` (`welcome`) | `token, you, rules, roster, started, robots` | anfitrião -> convidado | Entrada aceita (o **token** só vai aqui, só ao dono) |
| `Rejected` (`rejected`) | `message, reason?` | anfitrião -> convidado | Recusa com texto pt-BR e motivo |
| `JoinWaiting` | - | anfitrião -> candidato | Pedido de entrada no meio do jogo aguarda aprovação |
| `RosterUpdate` | `roster` | anfitrião -> todos | Sala atualizada (nomes, assentos, presença; **sem token**) |
| `GameStarted` | `roster, robots` | anfitrião -> todos | O jogo começou; robôs por assento |
| `PlayerPhoto` | `playerId, photo` | anfitrião -> todos | Foto repassada uma vez por jogador |
| `Rename`, `Leave`, `Resign` | - | convidado -> anfitrião | Nome, sair, desistir (§16.9) |
| `ViewUpdate` | `view: SeatView` | anfitrião -> um convidado | **Vista só do assento dele** (§16.4) |
| `RoundStarted`, `Played`, `RoundOver`, `GameFinished`, `Payout`, `StakeDebit` | ver `Protocol.kt` | anfitrião -> convidados | Ritmo do jogo, fim de partida/jogo, moedas |
| `ProposeAction`, `ActionRejected` | `requestId, action` / `reason, ruleError?` | convidado <-> anfitrião | Jogada pretendida e recusa com motivo |
| `Ping` | - | convidado -> anfitrião | Teste de qualidade (5 s sem enviar nada) |
| `TableNotice`, `Paused`, `Resumed` | `kind, seat?, playerId?, persona?, refusal?` / `seat, reason, hostChoice?` | anfitrião -> todos | Avisos e pausa (§16.8) |
| `TableClosed` | `reason: HOST_LEFT / HOST_OFFLINE` | anfitrião -> convidados | "Mesa encerrada" |
| `SuccessorState`, `SuccessorAck`, `SuccessorDrop`, `BecomeHost` | estado completo (**só 1º e 2º sucessores**) | anfitrião <-> sucessor | Sucessão (§16.10) |
| `AckOnly`, `ResendRequest` | `from` | os dois | Controle da confiabilidade |

### 2.3 Anúncios (o que o Nearby leva no `endpointInfo`)

Limite `MAX_BYTES = 100` (`net/src/main/kotlin/com/gtranca/net/nearby/AdvertCodec.kt:34`); nomes são cortados por caractere (nunca no meio de um acento).

| Anúncio | Formato | Bytes (ordem) | Quem lê |
|---|---|---|---|
| **Mesa** | 2 | `[2][flags: emAndamento/listada][hostId varint][epoch varint][provisionalRank][tableId][hostName ≤ 32][n chaves ≤ 3][chaves][tableName]` | quem procura a mesa (`GuestSession.findTable`, `Recruitment.tablesLocked`) |
| **Presença** | 5 | `[5][flags: hosting=1, free=2][key ≤ 8][recruitedBy][n inviting ≤ 3][inviting...][name]` | listas de recrutadores/livres, convite pela reserva |
| **"Saindo"** | 4 | `[4][key]` | quem vê tira todas as entradas da chave na hora |

Mesa e presença **compartilham o mesmo anúncio** (o Nearby aceita um por vez): anunciar um **substitui** o outro (`app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:274`).

**Chaves e identidade.** A chave de presença (`newPresenceKey`, 6 caracteres) é gerada a cada vez que o Wi-Fi do jogo liga
(`MultiplayerHub.resetPresence`) e é a **identidade** nas listas e nas conexões de espera (nunca o endpoint do Nearby). O **token** de
cada jogador (128 bits, `randomToken`) identifica o assento na mesa do jogo; só vai no `Welcome` ao dono, ao anfitrião e aos sucessores.

### 2.4 O que é redigido ou oculto

- `TableLayout.redactedFor(key)` (`net/src/main/kotlin/com/gtranca/net/TableLayout.kt:86`): o convite e cada sincronização levam **só a chave do próprio destinatário**; as dos outros chegam vazias (só nome e estado).
- `PlayerInfo`/`RosterUpdate`: nunca o token. `SeatView` (vista do assento): só a mão do próprio assento, tamanhos das outras, lixo, mesas baixadas; nunca mãos alheias, monte, mortos nem a semente (§16.4).
- Exceção deliberada: `SuccessorState` (estado completo) vai só ao 1º e 2º sucessores.
- Risco aceito (`definition.md` §16.4): as chaves de presença dos chamados ainda não conectados vão no anúncio da presença do recrutador (`inviting`) e no da mesa (`invited`); outro aparelho com o app poderia usar uma delas.
- Logs: só no build debug, tag `GTrancaNearby`, sem nomes, fotos nem bytes; chaves abreviadas a 2 caracteres.


---

## 3. Passo a passo tela a tela

Convenção: **gatilho** -> **função** -> **mensagens** -> **estado** -> **o que cada lado vê** -> **tempos**.

### 3.1 Início ("Continuar mesa")

- **Gatilho:** botão "Continuar mesa" (`app/src/main/kotlin/com/gtranca/ui/home/HomeScreen.kt:205`), só quando há sessão guardada (`data/src/main/kotlin/com/gtranca/data/MultiplayerSession.kt:54`). -> `navigateFromHome(RejoinRoute)` (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:268`).
- **Tela `RejoinDestination`:** com o Wi-Fi do jogo desligado pergunta "Ligar o Wi-Fi do jogo?" (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:1039`) e usa o `MultiplayerAccessGate`; depois mostra "Procurando a mesa…".
- **Função:** `MultiplayerHub.newTable(...).rejoinAsync(sessão)` (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:1057`) -> `MultiplayerTable.rejoin` (`app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:389`): repete `GuestSession.join(tableId, token, seat)` até `REJOIN_TIMEOUT_MILLIS = 30 s` (`app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:897`).
- **Mensagens:** `Hello(name, token, seat, roundOverSeen, photo)` -> anfitrião responde `Welcome(started=true)` + vista; token desconhecido/ocupado -> `Rejected("A vaga foi preenchida.")`.
- **Estado:** `GuestStatus.Connecting -> Playing`; ao ver `Playing` navega a `MultiplayerGameRoute`.
- **Sessão guardada (`:data`):** só mesa, assento, token e nome; vale 12 h (data/.../MultiplayerSession.kt); é apagada em desistência sem volta, mesa encerrada, vaga preenchida ou sair da mesa.
- **Observação:** o "Continuar mesa" **só existe a partir do commit `2b6fcaa`**; antes a sessão não era persistida.


### 3.2 Iniciar novo jogo (tela 1): presença, lista de recrutadores, modo ativo, atualizar

- **Gatilho:** "Novo jogo" no Início -> rota `TablesRoute`. Com o Wi-Fi do jogo ligado e permissões ok, o `NearbyPresence` (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:530`) liga a presença.
- **Presença (`MultiplayerHub.runPresence`, `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerHub.kt:117`):** gera/reusa a chave, cria `PresencePhotos` e `Recruitment`, roda `rec.run()` e espera o evento `Start`. `Recruitment.run` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:230`): `advertiseLocked()` (anuncia presença) + coleta `transport.discoverPresence()` + coleta `transport.discover()` + lê os eventos do transporte.
- **Livre:** a tela chama `rec.setFree(true)` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:283`) quando está em *Iniciar novo jogo* ou no editor de regras. Isso anuncia `free = true` (flag 2 do anúncio de presença) e abre a **conexão de espera** com algum recrutador visível.
- **Lista "Jogadores recrutando":** `Recruitment.recruiters` = presenças com `hosting` (flag 1) e sem o próprio. Só informativa.
- **Modo ativo:** `advertiseLocked` chama `transport.setActiveMode(!closed && (freeFlag || recruiting()))`. No `NearbyTransport`, o modo ativo faz a busca recomeçar a cada **7,5 s** (folga 1 s); fora dele, a cada **15 s** (folga 3 s) (`app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:570`).
- **Botão atualizar:** `rec.refreshNow()` -> `transport.burst()` -> até **2** recomeços rápidos (2,5 s um do outro) dentro do orçamento; toque durante rajada é ignorado (`app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:134`).
- **Orçamento de buscas:** no máximo 4 inícios de busca por janela de 30 s (contando o inicial de `acquireDiscovery`), nunca em menos de 2,5 s (`app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:117`). Sem orçamento o laço registra `restart skipped budget=N/4 wait=ms` e espera.
- **Saída das listas:** `onLost` com folga (3 s; 1 s no modo ativo), varredura (2 recomeços seguidos sem rever; pula chaves "fixadas" e quem tem conexão aberta), aviso "saindo" (anúncio de 0,5 s + `RecruitBye`) (`app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:152`).
- **Fotos:** `PresencePhotos` troca `PresencePhoto` pela conexão de espera, uma vez por conexão.


### 3.3 Seleção de jogadores (recrutador)

| Passo | Gatilho (UI) | Função | Mensagens | Estado / o que cada lado vê |
|---|---|---|---|---|
| Abrir | "Selecionar jogadores" | `rec.openTable(rules, hostName)` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:299`) | (anúncio) presença com `hosting = true` | `layout` = só o recrutador no lugar 0, resto VAGO; os livres veem "Jogadores recrutando" |
| Chamar | tocar num lugar VAGO e no jogador livre | `rec.call(seat, presence)` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:348`) | `RecruitInvite(version, table, you, 25000)` pela conexão de espera (se ele já está em espera); senão chave em `inviting` (reserva) | Lugar `Called`; chamado vê o modal de convite com contagem de 25 s |
| Convite 25 s | (automático) | timer do recrutado (`inviteTimer`) e do recrutador (25 s + 2 s) | `RecruitReply(false, NO_RESPONSE)` | Sem resposta = recusa; lugar volta a VAGO; avisos nos dois |
| Recusar | "Recusar" no modal | `rec.refuse()` -> `refuseLocked` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:523`) | `RecruitReply(false, INVITE_REFUSED)` | Lugar VAGO no recrutador; toast "recusou o convite" |
| Saldo | (automático ao receber) | `canAffordNow(stake)` | `RecruitReply(false, INSUFFICIENT_BALANCE)` | Toast no chamado `recruit_balance_table`; no recrutador `recruit_balance` |
| Remover | tocar no lugar -> "Remover" | `rec.removeSeat(seat)` | Chamado: `RecruitEnd(CANCELLED)`; Recrutado: `RecruitEnd(REMOVED)` | Chamado: modal some sem aviso; Recrutado: "<anfitrião> tirou você da mesa" |
| Trocar/mover | arrastar avatar | `rec.move(from, to)` | `RecruitSync` (0,3 s depois) | Lugares atualizados em todos; convite pendente mostra a posição nova |
| Robôs | tocar robô; "Completar com robôs" | `placeRobot`, `fillWithRobots(pool, seed)` | `RecruitSync` | Robô esmaecido na lista |
| Modo | só na tela 1 (muda `TableLayout.mode`) | reabrir a mesa | - | Mudar de modo exige reabrir; lugares recomeçam VAGO |
| Voltar | botão Voltar / sair da tela | `MultiplayerHub.closeRecruiting()` -> `rec.closeHosting()` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:459`) | Chamado: `RecruitEnd(CANCELLED)`; Recrutado: `RecruitEnd(CLOSED)` | Recrutados veem "Mesa encerrada"; lugares sempre VAGO ao reabrir |
| Começar Partida | "Começar Partida" (habilitado com `canStart`) | `rec.start(tableId, pool, seed)` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:438`) | `RecruitStart(tableId, 1500)` a cada recrutado | Gera o `TablePlan`; ver 3.5 e 3.6 |

Sincronização: `changedLocked` incrementa a versão e agenda `syncAllLocked` (debounce **0,3 s**); convite, resposta e remoção não esperam.
Cada destinatário recebe a mesa **redigida** para ele (`tableFor(key)`).


### 3.4 Tela do recrutado e modal de convite

- **Modal de convite (`AppNavHost`, qualquer tela fora de jogo):** aparece quando `rec.status` é `Invited`. Mostra "%1$s está te recrutando", aposta, tipo, posição (Parceiro/Adversário), título da regra e a contagem (`Invited.remainingMillis(rec.nowMillis())`). Botões Detalhes da regra, Recusar, Aceitar.
- **Como chega:** o livre mantém a conexão de espera (`RecruitHello` + `PresencePhoto`); o recrutador responde `RecruitInvite` na hora do toque. O `Invited` guarda `deadlineMillis` no relógio local; o prazo **não renova** se o convite for reenviado depois de queda.
- **Aceitar:** `rec.accept()` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:506`) -> `RecruitReply(true)`; status `Joined`; o recrutador marca `Recruited`, versão nova, `RecruitSync` a todos. A UI navega para `RecruitedDestination` (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:929`): *Mesa do recrutado* só leitura (lugares, aposta, regras), botão **Sair da mesa** (`rec.leave()` -> `RecruitLeave`).
- **Aguardando:** o recrutado fica em `Joined` até receber `RecruitStart`. Se a conexão cair, `Joined` **permanece 8 s** (reingresso por `RecruitHello(rejoin=true)`); passado o prazo vira `Free` + evento `Closed` ("Mesa encerrada").
- **Convite com queda de conexão:** `Invited` fica **1,5 s** na tela à espera de a conexão voltar; o reenvio atualiza no lugar. Respostas explícitas (recusa, expiração, remoção, encerramento) fecham na hora.


### 3.5 Prepare-se! e início (recrutador)

1. `start()` (tela de Seleção, `AppNavHost`): cria `tableId = newTableId()`, `plan = rec.start(...)` (envia `RecruitStart` e espera as confirmações até 2 s), marca `started = true`.
2. `MultiplayerHub.stopRecruiting()` cancela o `Recruitment.run` do recrutador (libera o leitor dos eventos).
3. `MultiplayerHub.newTable(...)` cria a `MultiplayerTable` e **já a publica em `_table`** (ver `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerHub.kt:171`); em seguida `hostAsync(rules, hostName, tableId, plan)` -> `MultiplayerTable.host` -> `HostSession(..., plan).open(scope)`.
4. `HostSession.open` registra o leitor de eventos, anuncia a **mesa** (`advertiseLocked(force = true)`) e dispara `beginPlan(plan)`.
5. `beginPlan`: espera `prepareMillis` (1,5 s) e depois, em laço de 50 ms, espera até `waitMillis` (10 s no total) que **todos os recrutados** (`inviteKey != null && link == null`) conectem; quem faltar vira **robô sorteado** (aviso `TableNotice(REPLACED_BY_ROBOT, ..., OUT_OF_RANGE)`), então `started = true`, `GameStarted` a todos, `gameStart` preenchido.
6. A UI do recrutador mostra a camada "Prepare-se! O jogo vai começar!" (`GetReadyOverlay`) e abre o jogo depois de **no mínimo 1,5 s** (`GetReadyEffect`).

Mesa só com robôs (sem rede): `live?.closeHosting()`, atraso de `PREPARE_MILLIS` e `navController.startGame(...)`.


### 3.6 Entrada na mesa do jogo (recrutado) e sala de espera

1. O recrutado recebe `RecruitStart` -> `startedLocked` (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:679`) -> evento `RecruitEvent.Start(tableId, inviteKey, prepareMillis)`.
2. `MultiplayerHub.runPresence` sai do `rec.events.first { Start }`, marca `_joining = true` (a camada "Prepare-se!" aparece) e, no `finally`, chama `rec.shutdown()` (envia `RecruitBye`, fecha tudo, **anuncia presença** de novo e espera até 0,5 s) e cancela o `Recruitment.run`.
3. Em seguida: `newTable(...).joinAsync(tableId, name, inviteKey)` (`app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerHub.kt:159`) e, no `finally` do `runPresence`, `t.stopPresence()` (anúncio "saindo" por 0,5 s, depois para o anúncio).
4. `MultiplayerTable.join` cria `GuestSession` e chama `GuestSession.join` (`net/src/main/kotlin/com/gtranca/net/GuestSession.kt:222`): coleta eventos, **procura o anúncio da mesa** com `findTable()` (até `FIND_TABLE_MILLIS = 10 s`), `transport.connect(advert)` (até 20 s) e envia `Hello(name, token=null, inviteKey)`.
5. O anfitrião (`HostSession.entryHelloLocked`, `net/src/main/kotlin/com/gtranca/net/HostSession.kt:558`) casa a `inviteKey`, liga o `link`, marca `connected = true`, envia `Welcome(started = false)` + fotos e atualiza o anúncio.
6. Convidado: `Welcome` -> `GuestStatus.Lobby` (sala de espera: ainda não há tela própria; a camada "Prepare-se!" cobre). Ao chegar `GameStarted` -> `Playing` e a UI abre o jogo (`GetReadyEffect`).


### 3.7 O jogo: batimento, pausa, quedas, reconexão, sucessão (§16.5, §16.8, §16.10, §16.11)

- **Ritmo:** o anfitrião roda o `GameController`/motor; a cada jogada envia `Played` (público) e `ViewUpdate` (vista do assento). O convidado só envia `ProposeAction`.
- **Qualidade da conexão:** o convidado manda `Ping` (controle) se ficar 5 s sem enviar; `LinkQualityMeter` mede ida e volta (`net/src/main/kotlin/com/gtranca/net/GuestSession.kt:144`).
- **Queda do convidado:** `HostSession.disconnectedLocked` (jogo em andamento): `connected = false`, `TableNotice(DISCONNECTED)`, o assento fica reservado; `GuestSession.lostLocked` -> `Reconnecting` e `reconnect()` com espera de 1 s dobrando até 15 s, **sem limite enquanto a mesa existir** (`net/src/main/kotlin/com/gtranca/net/GuestSession.kt:378`); mesa ausente da busca por 60 s = `Closed`.
- **Pausa (§16.8):** na vez de quem caiu, `MultiplayerHost` cria a decisão do anfitrião (aguardar / colocar robô / abrir vaga); os demais recebem `Paused`; `MultiplayerOverlay` mostra "Aguardando <nome>" ou "Aguardando recrutamento".
- **Entrada no meio (§16.7):** `HostSession.openSeat` anuncia a mesa com `inProgress/listed`; um `Hello` sem token vira `JoinRequest`; `approveJoin` -> `Welcome(started = true)` + `StakeDebit`. A porta de entrada é a **chamada** do anfitrião a um jogador livre (decidido em 2026-10-27; `docs/recrutamento.md`, "Chamada no meio do jogo"); o pedido sem chamada continua só na rede.
- **Sucessão (§16.10):** os 2 primeiros convidados conectados recebem `SuccessorState` (estado completo); se a mesa some da busca por 3 s (o 2º: 6 s) assumem o anfitrião (`TakingOver`).
- **Wi-Fi do anfitrião desligado:** `HostSession.goOffline` envia `SuccessorDrop` + `TableClosed(HOST_OFFLINE)` e espera as confirmações (<= 6 s) antes de fechar; humanos remotos viram robôs.
- **Fim de partida/jogo (§16.11):** `RoundOver` só ao assento dono (resultado e mesa final); `GameFinished` e `Payout`; a mesa fica anunciada até todos receberem ou 5 min.
- **Desistência (§16.9):** `Resign`; assento VAGO; o anfitrião decide na vez dele; "Voltar à mesa" reingressa pelo token (`GuestSession.returnToTable`).


### 3.8 Fim de jogo e "Voltar à mesa"

- Fim: `GameFinished`/`Payout`; o convidado liquida moedas e estatística (`MultiplayerTable.settle`); a sessão guardada é apagada quando o jogo é entregue.
- "Voltar à mesa" (quem desistiu): `MultiplayerTable.returnToTable` -> `GuestSession.returnToTable` (`net/src/main/kotlin/com/gtranca/net/GuestSession.kt:255`): procura a mesa e reenvia `Hello` com o token; resposta chega em `status`/`end`.


---

## 4. Catálogo de tratamento de erro

| Situação | Onde é tratado | O que o usuário vê (texto exato) | O que o código faz depois |
|---|---|---|---|

| Sem Google Play Services | `app/src/main/kotlin/com/gtranca/net/nearby/MultiplayerAccess.kt:33`, gate em `app/src/main/kotlin/com/gtranca/net/nearby/MultiplayerAccess.kt:81` | "Este aparelho não tem o Google Play Services (ou está desatualizado), que o jogo em grupo precisa para achar outros celulares. Por enquanto o multijogador não funciona nele; o jogo contra os robôs continua normal." | Só o botão Fechar; o modo fica desligado (o jogo contra robôs segue). |

| Permissões negadas | `app/src/main/kotlin/com/gtranca/net/nearby/MultiplayerAccess.kt:75` | "Sem essa permissão não dá para achar outros celulares. Você pode tentar de novo ou liberar em Configurações > Apps > Tranca-Bot > Permissões. O jogo contra os robôs continua normal." | Botões Tentar de novo / Agora não; recusar deixa o Wi-Fi do jogo desligado. |

| Permissões ainda não pedidas | `app/src/main/kotlin/com/gtranca/net/nearby/MultiplayerAccess.kt:36` | "Para achar mesas e jogadores por perto, o Tranca-Bot usa o Bluetooth e o Wi-Fi do aparelho, e o Android também pede a localização para essa busca (nada é rastreado). Não usa internet e nada sai daqui além da partida, só para os celulares da mesa. O Android vai pedir a sua permissão a seguir." | Botão Continuar abre o pedido do Android. |

| Localização desligada (Android <= 12) | `app/src/main/kotlin/com/gtranca/net/nearby/MultiplayerAccess.kt:40`, `app/src/main/kotlin/com/gtranca/net/nearby/NearbyPermissions.kt:35` | "Neste Android a busca por aparelhos só funciona com a localização ligada. Ligue a localização e toque em Tentar de novo. Nada é rastreado: ela só libera a busca por perto." | Tentar de novo reverifica. |

| Wi-Fi/Bluetooth do aparelho desligados | sem verificação própria; surge como falha de `startDiscovery`/`startAdvertising` | Busca: toast "Não foi possível procurar mesas por perto (%1$s)." e o Wi-Fi do jogo é desligado (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:553`); anúncio: nada | Anúncio: `advertiseBytes` engole a exceção (`app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:285`) e tenta de novo no próximo anúncio; busca: `acquireDiscovery` relança. |

| `startAdvertising` falha com 8001 (já anunciando) | `app/src/main/kotlin/com/gtranca/net/nearby/GmsNearbyApi.kt:42` | nada | Para o anúncio e anuncia de novo. |

| `startDiscovery` falha no recomeço | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:391` | nada (log debug `restart failed ... (no sweep)`) | Não varre (ninguém é removido); tenta de novo em 1 s respeitando o orçamento. |

| Orçamento de buscas esgotado (4 por 30 s, mínimo 2,5 s) | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:372` | nada (log `restart skipped budget=N/4 wait=ms`) | Espera a janela liberar; recomeço pulado nunca conta como perda. |

| `requestConnection` falha | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:515` | depende do chamador (ver abaixo) | Lança `IllegalStateException`; log `connect failed`. |

| `requestConnection` sem resposta em 20 s | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:517` | depende do chamador | Remove o pendente, `api.disconnect`, `error("Falha ao conectar")`. |

| Mesa não encontrada ao entrar (recrutado) ou qualquer exceção de `join` | `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:287`, `net/src/main/kotlin/com/gtranca/net/GuestSession.kt:229` | modal "Mesa encerrada" com "Mesa não encontrada por perto." e botão "Voltar ao início" | `catch (e: Exception)` converte **qualquer** falha em `MpEnd.NotFound`; o botão chama `MultiplayerHub.leave()`. |

| Mesa não encontrada ao "Continuar mesa" | `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:295` | "Mesa não encontrada por perto. Confira se o anfitrião está com o jogo aberto e o Wi-Fi do jogo ligado, e tente de novo." | Tenta por 30 s; depois `NotFound`; a sessão guardada é mantida. |

| Conexão cai (jogo) | `net/src/main/kotlin/com/gtranca/net/GuestSession.kt:349` | faixa "Reconectando" (`mp_reconnecting`) e, na vez do assento, pausa | Reconexão com espera crescente até 15 s sem limite; mesa some da busca por 60 s = encerrada. |

| Conexão de espera cai (recrutamento) | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:911` | Convite: segue na tela 1,5 s; Recrutado: segue 8 s | Reconecta (0,3/1/2/4 s, depois 8 s); passado o prazo, `Free` + "Mesa encerrada". |

| Batimento (4 s sem nenhum byte) | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:132` | nada direto (log `heartbeat dead XX silent=N ms`) | Dá a conexão por caída, tira o jogador das listas, `lostLocked`. |

| Convite expirado (25 s) | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:788` | toast "Você não respondeu ao convite a tempo." (recrutado) / "%1$s não respondeu a tempo." (recrutador) | `RecruitReply(false, NO_RESPONSE)`; lugar VAGO. |

| Convite recusado | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:523` | toast "%1$s recusou o convite" | Lugar VAGO; o jogador pode ser chamado de novo. |

| Convite sem conexão (reserva pelo anúncio) | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:377` | o chamado só vê o modal quando conectar | Chamado sem conexão que some da busca por 10 s: lugar VAGO + aviso "fora do alcance". |

| Saldo insuficiente (convite) | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:773` | "Saldo insuficiente para esta mesa." | Recusa automática com `INSUFFICIENT_BALANCE`. |

| Recrutado não entra na mesa em 10 s | `net/src/main/kotlin/com/gtranca/net/HostSession.kt:384` | o anfitrião não vê aviso na tela (evento `lobbyNotices`); o recrutado vê o modal do item "Mesa não encontrada" | Vira robô sorteado (`REPLACED_BY_ROBOT`, motivo `OUT_OF_RANGE`) e a partida começa. |

| Versão de protocolo diferente | `net/src/main/kotlin/com/gtranca/net/Codec.kt:32`, `net/src/main/kotlin/com/gtranca/net/HostSession.kt:746`, `net/src/main/kotlin/com/gtranca/net/GuestSession.kt:415` | "O anfitrião está com outra versão do jogo (anfitrião: versão N; você: versão M). Atualizem todos os celulares para a mesma versão e criem uma mesa nova." | Conexão fechada; `Refused`. **Na presença/recrutamento** (conexões de espera) a mensagem de outra versão é ignorada em silêncio (`Decoded.Malformed -> false`). |

| Anúncio de formato desconhecido | `net/src/main/kotlin/com/gtranca/net/nearby/AdvertCodec.kt:72` | nada ("ninguém por perto") | `decode` devolve `null` e o anúncio é ignorado. |

| Mensagem inválida (JSON/estrutura) | `net/src/main/kotlin/com/gtranca/net/Codec.kt:30` | nada | `Decoded.Malformed` é ignorado (sem log). |

| Foto grande demais | `net/src/main/kotlin/com/gtranca/net/PresencePhotos.kt:41` | inicial no lugar da foto | Descartada. |

| Fim da mesa com o anfitrião fora (Wi-Fi/saída) | `net/src/main/kotlin/com/gtranca/net/GuestSession.kt:490` | "O anfitrião saiu e desfez a mesa." / "O anfitrião desligou o Wi-Fi do jogo: o jogo segue só no celular dele, com robôs no lugar dos outros jogadores." | `TableEnded`; sucessão desligada (`successorState = null`). |

| Token recusado (vaga preenchida) | `net/src/main/kotlin/com/gtranca/net/HostSession.kt:1069` | "A vaga foi preenchida." | A sessão guardada é apagada. |

| Mensagem acima do limite do Nearby | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:527` | nada | Exceção na chamada de envio. |


---

## 5. Trechos-chave de código

Cópias literais; o caminho e as linhas estão no título de cada bloco.

### 5.1 Recrutamento (`Recruitment.kt`)


**Recruitment.call - chamar** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:343-381`

```kotlin
    /**
     * Â§16.2 chama o jogador [presence] para o lugar [seat] (VAGO): o convite chega na hora, pela conexÃ£o de espera se ela
     * existe (senÃ£o pela reserva do anÃºncio). `false` se o lugar nÃ£o estÃ¡ VAGO, o jogador jÃ¡ estÃ¡ em outro lugar ou foi
     * recrutado por outro recrutador.
     */
    suspend fun call(seat: Int, presence: PresenceAdvert): Boolean = mutex.withLock {
        val l = layoutState ?: return@withLock false
        if (presence.key == myKey || remotes.size >= 3) return@withLock false
        if (presence.recruitedBy != null && presence.recruitedBy != myKey) return@withLock false
        val next = l.call(seat, presence.key, presence.name) ?: return@withLock false
        layoutState = next
        val r = Remote(presence.key, presence.name, clock())
        remotes[presence.key] = r
        // Â§16.2, Â§16.15 o chamado tem 25 s (+ folga): sem resposta, o lugar volta a VAGO e o jogador pode ser chamado de novo.
        scope.launch {
            delay(INVITE_TIMEOUT_MILLIS + INVITE_HOST_GRACE_MILLIS)
            mutex.withLock {
                if (remotes[presence.key] === r && layoutState?.slots?.any { it is Slot.Called && it.key == presence.key } == true) {
                    r.link?.sendLocked(RecruitEnd(RecruitEndReason.CANCELLED))
                    vacateLocked(presence.key, RecruitNotice(presence.key, r.name, refused = true, reason = RefusalReason.NO_RESPONSE), "no response")
                }
            }
        }
        val link = links.entries.firstOrNull { peerKey[it.key] == presence.key }?.value
        if (link != null) {
            r.link = link
            version++
            _layout.value = next
            link.sendLocked(RecruitInvite(version, tableFor(presence.key)!!, seat))
            log("rec call ${k(presence.key)} invite sent via link in ${clock() - r.calledAt} ms")
            debounceJob?.cancel()
            syncOthersLocked(except = presence.key)
            advertiseLocked()
        } else {
            log("rec call ${k(presence.key)} no link: advert fallback")
            changedLocked(immediate = true)
        }
        true
    }
```

**Recruitment.accept - aceitar** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:505-516`

```kotlin
    /** Â§16.2 aceita o convite: passa a ver a mesa do recrutador. Devolve se havia convite e conexÃ£o. */
    suspend fun accept(): Boolean = mutex.withLock {
        val invited = _status.value as? RecruitStatus.Invited ?: return@withLock false
        val link = hostLink ?: return@withLock false
        link.sendLocked(RecruitReply(myKey, true))
        answeredVersion[invited.hostKey] = maxOf(answeredVersion[invited.hostKey] ?: 0, invited.version)
        inviteLossJob?.cancel()
        log("rec accept sent")
        transition(RecruitStatus.Joined(invited.hostKey, invited.hostName, invited.table, invited.you, invited.version))
        advertiseLocked()
        true
    }
```

**Recruitment.refuseLocked - recusa (inclui automática)** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:523-531`

```kotlin
    private suspend fun refuseLocked(reason: RefusalReason) {
        val invited = _status.value as? RecruitStatus.Invited ?: return
        if (reason != RefusalReason.INVITE_REFUSED) _events.tryEmit(RecruitEvent.Refused(invited.hostName, reason))
        hostLink?.sendLocked(RecruitReply(myKey, false, reason))
        answeredVersion[invited.hostKey] = maxOf(answeredVersion[invited.hostKey] ?: 0, invited.version)
        inviteLossJob?.cancel()
        transition(RecruitStatus.Free, "refused:$reason")
        advertiseLocked()
    }
```

**Recruitment.start - Começar Partida** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:433-456`

```kotlin
    /**
     * Â§16.2 ComeÃ§ar Partida: exige [TableLayout.canStart]. Os recrutados recebem [RecruitStart] (mostrar "Prepare-se!" por
     * no mÃ­nimo [PREPARE_MILLIS] e entrar na mesa [tableId] com a prÃ³pria chave); a presenÃ§a volta a "livre". Devolve o
     * plano de lugares para a mesa do jogo, ou `null` se ainda nÃ£o dÃ¡.
     */
    suspend fun start(tableId: String, personaPool: List<String>, seed: Long): TablePlan? {
        val (plan, all) = mutex.withLock {
            val l = layoutState ?: return null
            val r = rules ?: return null
            if (!l.canStart) return null
            debounceJob?.cancel()
            remotes.values.forEach { it.link?.sendLocked(RecruitStart(tableId, PREPARE_MILLIS)) }
            log("rec start prepare=$PREPARE_MILLIS remotes=${remotes.size}")
            remotes.values.forEach { it.absentJob?.cancel(); it.graceJob?.cancel() }
            val plan = TablePlan(l, personaPool, seed)
            remotes.clear()
            layoutState = null
            _layout.value = null
            advertiseLocked()
            plan to links.values.toList()
        }
        closeAfterConfirm(all, START_CONFIRM_MILLIS)
        return plan
    }
```

**Recruitment.closeHosting - voltar à tela 1 / sair** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:458-477`

```kotlin
    /** Â§16.2 o recrutador voltou Ã  tela 1, saiu ou desligou o Wi-Fi: chamados tÃªm o convite cancelado; recrutados, "Mesa encerrada". */
    suspend fun closeHosting() {
        val all = mutex.withLock {
            if (layoutState == null && remotes.isEmpty() && links.isEmpty()) return
            debounceJob?.cancel()
            remotes.values.forEach { r ->
                val recruited = layoutState?.slots?.any { it is Slot.Recruited && it.key == r.key } == true
                r.link?.sendLocked(RecruitEnd(if (recruited) RecruitEndReason.CLOSED else RecruitEndReason.CANCELLED))
                r.absentJob?.cancel()
                r.graceJob?.cancel()
            }
            remotes.clear()
            layoutState = null
            _layout.value = null
            log("rec table closed")
            advertiseLocked()
            links.values.filter { it !== hostLink }
        }
        closeAfterConfirm(all, CLOSE_CONFIRM_MILLIS)
    }
```

**Recruitment.shutdown - larga tudo (o recrutado e o recrutador ao entrar na mesa)** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:479-501`

```kotlin
    /** Larga tudo (Wi-Fi do jogo desligado ou o aparelho entrou numa mesa/jogo): encerra o recrutamento nos dois papÃ©is. */
    suspend fun shutdown() {
        mutex.withLock {
            closed = true
            // Â§16.2 aviso explÃ­cito de saÃ­da: quem recebe me tira das listas na hora (as confirmaÃ§Ãµes esperam atÃ© 0,5 s).
            links.values.forEach { it.sendLocked(RecruitBye(myKey)) }
            advertiseLocked()
        }
        closeHosting()
        val rest = mutex.withLock {
            contacting?.cancel()
            rejoinJob?.cancel()
            val l = hostLink
            if (l != null && _status.value is RecruitStatus.Joined) l.sendLocked(RecruitLeave(null))
            val all = links.values.toList()
            hostLink = null
            outLink = null
            hostKey = null
            _status.value = RecruitStatus.Free
            all
        }
        if (rest.isNotEmpty()) closeAfterConfirm(rest, CLOSE_CONFIRM_MILLIS)
    }
```

**Recruitment.advertiseLocked - anúncio de presença** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:255-265`

```kotlin
    private suspend fun advertiseLocked() {
        transport.setActiveMode(!closed && (freeFlag || recruiting()))
        val recruitedBy = if (_status.value == RecruitStatus.Free) null else hostKey
        val inviting = remotes.values.filter { it.link == null }.map { it.key }.take(AdvertCodecLimits.MAX_INVITED)
        runCatching {
            transport.pinPresence(pinned())
            transport.advertisePresence(
                PresenceAdvert(myKey, myName, recruitedBy = recruitedBy, inviting = inviting, hosting = recruiting(), free = isFree()),
            )
        }
    }
```

**Recruitment.heartbeatTickLocked - batimento** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:131-153`

```kotlin
    /** Devolve se acabou (sem conexÃµes). ConexÃ£o muda por mais de [DEAD_AFTER_MILLIS] Ã© dada como caÃ­da. */
    private suspend fun heartbeatTickLocked(): Boolean {
        if (links.isEmpty()) {
            heartbeatJob = null
            return true
        }
        val now = clock()
        links.entries.toList().forEach { (ep, link) ->
            if (now - (lastHeard[ep] ?: now) > DEAD_AFTER_MILLIS) {
                log("heartbeat dead ${k(peerKey[ep] ?: "??")} silent=${now - (lastHeard[ep] ?: now)} ms")
                val key = peerKey[ep]
                links.remove(ep)
                lastHeard.remove(ep)
                link.close()
                key?.let { transport.forgetPresence(it) }
                lostLocked(ep, link)
                scope.launch { runCatching { transport.disconnect(ep) } }
            } else {
                link.controlLocked(RecruitPing)
            }
        }
        return false
    }
```

**Recruitment.startedLocked - o recrutado recebe o início** `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:679-684`

```kotlin
    private suspend fun startedLocked(tableId: String, prepareMillis: Long) {
        rejoinJob?.cancel()
        transition(RecruitStatus.Free)
        _events.tryEmit(RecruitEvent.Start(tableId, myKey, prepareMillis))
        hostLink?.let { dropLinkLocked(it) }
    }
```


### 5.2 Transporte Nearby (`NearbyTransport.kt`)


**Orçamento de buscas** `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:112-123`

```kotlin
    /**
     * §16.15 quanto falta para poder recomeçar a busca: espera a janela de 30 s liberar quando o orçamento acabou e
     * respeita o intervalo mínimo entre inícios. 0 = pode agora. O Android limita os inícios de varredura por app e, acima
     * do limite, a varredura volta vazia sem aviso.
     */
    private fun restartWaitLocked(): Long {
        val t = now()
        while (startTimes.isNotEmpty() && t - startTimes.first() >= restartWindowMillis) startTimes.removeFirst()
        val gap = startTimes.lastOrNull()?.let { minRestartGapMillis - (t - it) } ?: 0
        val budget = if (startTimes.size >= restartBudget) startTimes.first() + restartWindowMillis - t else 0
        return maxOf(gap, budget, 0)
    }
```

**Laço de recomeço da busca** `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:357-405`

```kotlin
    /**
     * §16.2, §16.15 recomeça a busca periodicamente e tira da lista as presenças que não reapareceram em
     * [SWEEPS_TO_REMOVE] recomeços seguidos. Falha ao religar não abandona: tenta de novo depois de [RESTART_RETRY_MILLIS].
     * Com conexão de presença pendente ou aberta (troca de fotos), o recomeço é adiado (no Nearby ele pode abortá-la).
     */
    private suspend fun refreshPresence() {
        waitRefresh(refreshInterval())
        while (true) {
            if (presenceConnectionBusy()) {
                delay(RESTART_RETRY_MILLIS)
                continue
            }
            // §16.15 orçamento de buscas: sem ele o laço espera; recomeço pulado nunca conta como perda de ninguém.
            val wait = control.withLock { restartWaitLocked() }
            if (wait > 0) {
                log("restart skipped budget=${startTimes.size}/$restartBudget wait=$wait ms")
                delay(wait)
                continue
            }
            val restartedAt = control.withLock {
                if (subscribers == 0) return@withLock null
                val at = now()
                if (discovering) runCatching { api.stopDiscovery() }
                recordStartLocked()
                try {
                    api.startDiscovery(discoveryCallback)
                    discovering = true
                    if (burstLeft > 0) burstLeft--
                    log("restart ok budget=${startTimes.size}/$restartBudget")
                    at
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    discovering = false
                    log("restart failed ${e.message?.take(40)} (no sweep)")
                    null
                }
            }
            if (restartedAt == null) {
                // Falha não conta como perda (sem varredura) e o próximo recomeço também respeita o orçamento.
                delay(RESTART_RETRY_MILLIS)
                continue
            }
            val grace = sweepGrace()
            delay(grace)
            sweep(restartedAt)
            waitRefresh((refreshInterval() - grace).coerceAtLeast(0))
        }
    }
```

**Anúncio (um por vez: presença OU mesa)** `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:274-291`

```kotlin
    private suspend fun advertiseBytes(bytes: ByteArray, presence: Boolean) {
        control.withLock {
            val current = advertised
            if (current != null && current.contentEquals(bytes)) return
            if (current != null) api.stopAdvertising()
            advertised = null
            try {
                api.startAdvertising(bytes, connectionCallback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Falha do rádio não fecha o app nem derruba a mesa: as conexões seguem e o próximo anúncio tenta de novo.
                return
            }
            advertised = bytes
            advertisingPresence = presence
        }
    }
```

**stopPresence - aviso "saindo" e parada** `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:296-320`

```kotlin
    /**
     * §16.2, §16.15 para a presença (a mesa, se for ela no ar, continua): antes, anuncia "saindo" com a mesma chave por
     * [leavingMillis], para os outros tirarem o jogador da lista na hora.
     */
    override suspend fun stopPresence() {
        val leaving = control.withLock {
            val current = advertised
            if (current == null || !advertisingPresence) return
            val presence = AdvertCodec.decodePresence(current) ?: return@withLock null
            if (presence.leaving) return@withLock null
            val bytes = AdvertCodec.encodePresenceLeaving(presence.key)
            api.stopAdvertising()
            advertised = null
            runCatching { api.startAdvertising(bytes, connectionCallback) }.onSuccess { advertised = bytes; log("advertise leaving") }
            bytes.takeIf { advertised === it }
        }
        if (leaving != null) delay(leavingMillis)
        control.withLock {
            // Outra coisa (a mesa, ou a presença de novo) pode ter tomado o lugar durante o aviso.
            if (leaving != null && advertised !== leaving) return
            if (advertised == null || !advertisingPresence) return
            api.stopAdvertising()
            advertised = null
        }
    }
```

**acquireDiscovery - liga a busca** `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:444-463`

```kotlin
    /** Liga a busca se preciso; devolve quando ela foi ligada (para o aquecimento). */
    private suspend fun acquireDiscovery(): Long = control.withLock {
        subscribers++
        lingerJob?.cancel()
        lingerJob = null
        if (!discovering) {
            found.value = emptyMap()
            foundPresence.value = emptyMap()
            try {
                api.startDiscovery(discoveryCallback)
                recordStartLocked()
            } catch (e: Exception) {
                subscribers-- // a busca não começou: quem pediu recebe o erro e não fica contado
                throw e
            }
            discovering = true
            discoveryStartedAt = now()
        }
        discoveryStartedAt
    }
```

**Conexão (timeout de 20 s)** `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:506-524`

```kotlin
    private suspend fun connectEndpoint(endpointId: String): EndpointId {
        val waiting = synchronized(lock) {
            if (endpointId in connected) return EndpointId(endpointId)
            pending.getOrPut(endpointId) { CompletableDeferred() }
        }
        try {
            api.requestConnection(LOCAL_INFO, endpointId, connectionCallback)
        } catch (e: Exception) {
            synchronized(lock) { pending.remove(endpointId) }
            throw IllegalStateException("Falha ao conectar", e)
        }
        val ok = withTimeoutOrNull(connectTimeoutMillis) { waiting.await() } ?: false
        if (!ok) {
            synchronized(lock) { pending.remove(endpointId) }
            api.disconnect(endpointId)
            error("Falha ao conectar")
        }
        return EndpointId(endpointId)
    }
```

**Varredura** `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:413-433`

```kotlin
    private fun sweep(restartedAt: Long) {
        val live = synchronized(lock) { connected.toSet() }
        val pinned = pinnedKeys
        val remove = synchronized(presenceSeen) {
            foundPresence.value.filter { it.value.key !in pinned }.keys.filter { endpoint ->
                val seen = presenceSeen[endpoint] ?: Long.MIN_VALUE
                if (seen >= restartedAt || endpoint in live) {
                    presenceMisses.remove(endpoint)
                    false
                } else {
                    val misses = (presenceMisses[endpoint] ?: 0) + 1
                    presenceMisses[endpoint] = misses
                    misses >= SWEEPS_TO_REMOVE
                }
            }.toSet().also { gone -> gone.forEach { presenceSeen.remove(it); presenceMisses.remove(it) } }
        }
        if (remove.isNotEmpty()) {
            log("remove x${remove.size}: sweep")
            foundPresence.update { it - remove }
        }
    }
```


### 5.3 Sessões da mesa do jogo


**GuestSession.join** `net/src/main/kotlin/com/gtranca/net/GuestSession.kt:218-231`

```kotlin
    /**
     * Conecta-se à mesa [tableId] e pede para entrar (§16.2), ou reingressa com [token] (§16.5, "Continuar"), mesmo que
     * a mesa não esteja na lista (ela fica anunciada o jogo todo, §16.7). Falha se a mesa não está na busca.
     */
    suspend fun join(scope: CoroutineScope, tableId: String, token: String? = null, seat: Seat? = null, inviteKey: String? = null) {
        this.scope = scope
        this.tableId = tableId
        this.token = token
        this.requestedSeat = seat
        this.inviteKey = inviteKey
        collector = scope.launch { transport.events.collect { handle(it) } }
        val advert = findTable() ?: error("Mesa não encontrada")
        connectAndHello(advert, seat)
    }
```

**GuestSession.findTable (10 s)** `net/src/main/kotlin/com/gtranca/net/GuestSession.kt:236-241`

```kotlin
    /**
     * A mesa [tableId] na busca (o anúncio preferido, §16.10), esperando até [FIND_TABLE_MILLIS]: no rádio real a busca
     * acha as mesas aos poucos, e a primeira lista pode trazer outra mesa e não esta.
     */
    private suspend fun findTable(): TableAdvert? =
        withTimeoutOrNull(FIND_TABLE_MILLIS) { transport.discover().mapNotNull { it.preferred(tableId) }.first() }
```

**GuestSession.connectAndHello** `net/src/main/kotlin/com/gtranca/net/GuestSession.kt:243-249`

```kotlin
    private suspend fun connectAndHello(advert: TableAdvert, seat: Seat?) {
        val endpoint = transport.connect(advert)
        mutex.withLock {
            link = ReliableLink(endpoint, transport, codec, scope, mutex, meter = meter)
            link!!.sendLocked(Hello(name, token, seat, roundOverSeen, photo, inviteKey.takeIf { token == null }))
        }
    }
```

**HostSession.open - publica a mesa e inicia o plano** `net/src/main/kotlin/com/gtranca/net/HostSession.kt:316-346`

```kotlin
    /** Publica a mesa e passa a atender os convidados em [scope]. */
    suspend fun open(scope: CoroutineScope) {
        this.scope = scope
        collector = scope.launch { transport.events.collect { handle(it) } }
        // §16.10 outro anfitrião da mesma mesa que prevaleça (só com o jogo em andamento).
        watcher = scope.launch {
            transport.discover().collect { list ->
                val mine = mutex.withLock { if (started && !closed) advertLocked() else null } ?: return@collect
                if (list.any { it.tableId == tableId && it.hostId != hostId && it.outranks(mine) }) supersede()
            }
        }
        mutex.withLock {
            if (started) refreshSuccessorsLocked()
            advertiseLocked(force = true)
        }
        // §16.2 "Prepare-se!": no mínimo prepareMillis; depois espera os recrutados entrarem (até waitMillis) e começa.
        plan?.let { scope.launch { beginPlan(it) } }
        // §16.10 provisório sem outro humano que possa confirmar: confirma sozinho depois do tempo de §16.15.
        if (provisionalRank > 0 && members.values.none { it.info.id != hostId && it.info.id != previousHostId && !it.info.resigned }) {
            scope.launch {
                delay(provisionalSoloMillis)
                mutex.withLock { commitLocked() }
            }
        } else if (provisionalRank > 0) {
            // §16.10 com outros humanos e ninguém reingressa: depois do tempo de §16.15, oferece "Assumir mesmo assim".
            scope.launch {
                delay(provisionalForceMillis)
                mutex.withLock { if (provisionalRank > 0 && !closed) _canForceCommit.value = true }
            }
        }
    }
```

**HostSession.beginPlan - início com robô para quem não entrou** `net/src/main/kotlin/com/gtranca/net/HostSession.kt:383-418`

```kotlin
    /** Começa o jogo do plano: espera o "Prepare-se!" e os recrutados; quem não entrou vira robô sorteado (§14.5, §16.2). */
    private suspend fun beginPlan(plan: TablePlan) {
        delay(plan.prepareMillis)
        val rest = (plan.waitMillis - plan.prepareMillis).coerceAtLeast(0)
        var waited = 0L
        while (waited < rest && mutex.withLock { members.values.any { it.inviteKey != null && it.link == null && it.info.id != hostId } }) {
            delay(ENTRY_POLL_MILLIS)
            waited += ENTRY_POLL_MILLIS
        }
        val seats = mutex.withLock {
            if (started || closed) return
            val random = kotlin.random.Random(plan.seed)
            val used = plan.layout.usedPersonas.toMutableSet()
            members.values.filter { it.inviteKey != null && it.link == null && it.info.id != hostId }.toList().forEach { m ->
                val free = plan.personaPool.filter { it !in used }
                val persona = (free.ifEmpty { plan.personaPool }).random(random)
                used += persona
                val seat = m.info.seat
                if (seat != null) robots = robots + (seat.index to persona)
                val notice = TableNotice(NoticeKind.REPLACED_BY_ROBOT, seat, m.info.id, persona, RefusalReason.OUT_OF_RANGE)
                _lobbyNotices.tryEmit(notice)
                m.inviteKey = null
                removeMemberLocked(m, disconnect = true)
            }
            members.values.forEach { it.inviteKey = null }
            started = true
            this.plan = null
            pending.values.toList().forEach { closePendingLocked(it) }
            refreshSuccessorsLocked()
            publishRosterLocked(broadcast = false)
            advertiseLocked()
            broadcastLocked(GameStarted(_roster.value, robots))
            guestSeats()
        }
        _gameStart.value = seats
    }
```

**HostSession.entryHelloLocked - entrada do recrutado** `net/src/main/kotlin/com/gtranca/net/HostSession.kt:557-570`

```kotlin
    /** §16.2 o recrutado se conectou com a chave de presença: ocupa o lugar planejado (sem novo convite). */
    private suspend fun entryHelloLocked(p: Pending, hello: Hello, key: String) {
        val member = members.values.firstOrNull { it.inviteKey == key && it.info.id != hostId }
            ?: return refuseLocked(p, refusalMessage(RefusalReason.OUT_OF_RANGE), RefusalReason.OUT_OF_RANGE)
        // Mesma chave em outra conexão: a nova assume.
        member.link?.let { old -> old.close(); transport.disconnect(old.endpoint) }
        pending.remove(p.link.endpoint)
        member.link = p.link
        updateLocked(member) { it.copy(connected = true) }
        publishRosterLocked(broadcast = true)
        p.link.sendLocked(Welcome(member.token, member.info.id, rules, _roster.value, started = false, robots = robots))
        sharePhotosLocked(member, p.link, hello.photo)
        advertiseLocked()
    }
```

**HostSession.advertLocked** `net/src/main/kotlin/com/gtranca/net/HostSession.kt:953-963`

```kotlin
    private fun advertLocked(): TableAdvert {
        val open = !finished && _openSeats.value.isNotEmpty()
        return TableAdvert(
            tableId, rules.tableName, members.getValue(hostId).info.name,
            inProgress = started && open,
            listed = started && open,
            hostId = hostId, epoch = epoch, provisionalRank = provisionalRank,
            // §16.2 recrutados que ainda não entraram: a chave serve de reserva se o início se perdeu.
            invited = members.values.filter { it.inviteKey != null && it.link == null }.mapNotNull { it.inviteKey },
        )
    }
```

**HostSession.advertiseLocked** `net/src/main/kotlin/com/gtranca/net/HostSession.kt:965-981`

```kotlin
    /**
     * Anúncio da mesa (§16.7): durante todo o jogo, para o reingresso pelo token; na lista ([TableAdvert.listed]) só antes
     * do início ou com vaga aberta ("jogo em andamento"). Depois do fim, só enquanto alguém que estava fora ainda não
     * recebeu o resultado (§16.11).
     */
    private suspend fun advertiseLocked(force: Boolean = false) {
        val outside = finished && !closed && finals.keys.any { members[it]?.link == null }
        _resultsPending.value = outside
        val want = !closed && (!finished || outside)
        if (want) {
            if (!advertising || force || started || plan != null) transport.advertise(advertLocked())
            advertising = true
        } else if (advertising) {
            transport.stopAdvertising()
            advertising = false
        }
    }
```


### 5.4 Cola do app


**MultiplayerHub.runPresence** `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerHub.kt:112-165`

```kotlin
    /**
     * §16.2 com o Wi-Fi do jogo ligado e fora de uma mesa: anuncia a presença ([name]), troca fotos e atende o
     * recrutamento imediato ([com.gtranca.net.Recruitment]); recrutado que recebe o início entra na mesa com a própria
     * chave. Roda até ser cancelado (Wi-Fi desligado, mesa aberta ou jogo); a presença para ao sair.
     */
    suspend fun runPresence(context: Context, data: GameData, name: String, photo: String?) {
        if (_table.value != null) return
        val t = transport(context)
        val key = presenceKey ?: com.gtranca.net.newPresenceKey().also { presenceKey = it }
        presenceName = name
        try {
            val start = kotlinx.coroutines.coroutineScope {
                var recRef: com.gtranca.net.Recruitment? = null
                // §16.2 as fotos dos jogadores próximos vão pela conexão de espera do recrutamento (nunca por uma avulsa).
                val photos = com.gtranca.net.PresencePhotos(t, key, photo, _presencePhotos, reserved = { recRef?.ownsEndpoint(it) == true }, log = debugLog)
                // O estado do recrutamento vive no escopo do hub (não no desta função), então não se perde se a tela recompor.
                val rec = com.gtranca.net.Recruitment(
                    t, scope, key, name, photos,
                    canAfford = { stake -> runCatching { com.gtranca.engine.Coins.canJoin(data.wallet.wallet.first().balance, stake) }.getOrDefault(true) },
                    log = debugLog,
                    clock = { System.nanoTime() / 1_000_000 },
                )
                recRef = rec
                val running = launch { runCatching { rec.run() } }
                recruiting = running
                _recruitment.value = rec
                try {
                    val events = launch {
                        rec.events.collect { if (it !is com.gtranca.net.RecruitEvent.Start) _recruitEvents.emit(it) }
                    }
                    val start = rec.events.first { it is com.gtranca.net.RecruitEvent.Start } as com.gtranca.net.RecruitEvent.Start
                    _joining.value = true
                    events.cancel()
                    start
                } finally {
                    _recruitment.value = null
                    withContext(NonCancellable) {
                        // Encerra as conexões de recrutamento (aviso de saída, até 0,5 s) e só então pára de ler os eventos,
                        // antes de a sessão da mesa começar a ler.
                        runCatching { rec.shutdown() }
                        running.cancel()
                        running.join()
                        recruiting = null
                    }
                }
            }
            // §16.2 o anfitrião tocou em Escolher posições: entra direto, com a própria chave (sem novo convite).
            newTable(context, data, photo).joinAsync(start.tableId, name, inviteKey = start.inviteKey)
            _joining.value = false
        } finally {
            _joining.value = false
            withContext(NonCancellable) { runCatching { t.stopPresence() } }
        }
    }
```

**MultiplayerHub.newTable** `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerHub.kt:167-177`

```kotlin
    /**
     * Uma mesa nova. A anterior, se houver, é deixada antes de a nova entrar ou publicar (um leitor por transporte); uma
     * entrega de resultado ainda aberta nela (§16.11) acaba aqui.
     */
    fun newTable(context: Context, data: GameData, photo: String? = null): MultiplayerTable {
        val leaving = _table.value?.let { old -> scope.launch { old.leave() } }
        return MultiplayerTable(transport(context), scope, DataMpCoins(data, scope), after = leaving, sessions = DataMpSessions(data)).also {
            it.myPhoto = photo
            _table.value = it
        }
    }
```

**MultiplayerTable.joinAsync - onde "Mesa não encontrada" nasce** `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:286-298`

```kotlin
    /** [join] sem esperar: a mesa não achada vira [MpEnd.NotFound]. */
    fun joinAsync(tableId: String, playerName: String, inviteKey: String? = null) {
        scope.launch {
            after?.join()
            try {
                join(tableId, playerName, inviteKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _end.value = MpEnd.NotFound
            }
        }
    }
```

**MultiplayerTable.hostAsync** `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:429-441`

```kotlin
    /** [host] sem esperar. */
    fun hostAsync(rules: TableRules, playerName: String, tableId: String, plan: com.gtranca.net.TablePlan) {
        scope.launch {
            after?.join()
            try {
                host(rules, playerName, tableId, plan)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _end.value = MpEnd.Closed
            }
        }
    }
```

**Modal "Mesa encerrada" (MpEndMessage)** `app/src/main/kotlin/com/gtranca/ui/multiplayer/MpEndMessage.kt:15-48`

```kotlin
fun MpEndMessage(reason: MpEnd, onClose: () -> Unit) {
    // §16.2 recusa do convite: o modal Recusa, com o motivo.
    if (reason is MpEnd.Refused && reason.reason != null) {
        GMessage(
            modifier = Modifier.testTag("mp-refusal"),
            title = stringResource(R.string.mp_refusal_title),
            text = stringResource(refusalText(reason.reason)),
            illustration = MessageIllustration.Info,
            tone = MessageTone.Warning,
            confirmText = stringResource(R.string.mp_ok_short),
            confirmTag = "mp-refusal-ok",
            onConfirm = onClose,
            onDismissRequest = onClose,
        )
        return
    }
    GMessage(
        modifier = Modifier.testTag("mp-end"),
        title = stringResource(R.string.mp_closed_title),
        text = when (reason) {
            is MpEnd.Refused -> reason.message
            MpEnd.Closed -> stringResource(R.string.mp_closed_text)
            MpEnd.NotFound -> stringResource(R.string.mp_not_found)
            is MpEnd.Ended -> stringResource(
                if (reason.reason == com.gtranca.net.CloseReason.HOST_LEFT) R.string.mp_closed_host_left else R.string.mp_closed_host_offline,
            )
        },
        illustration = MessageIllustration.Info,
        tone = MessageTone.Warning,
        confirmText = stringResource(R.string.back_to_home),
        onConfirm = onClose,
        onDismissRequest = onClose,
    )
}
```

**RecruitedDestination - tela do recrutado + camada Prepare-se + MpEndMessage** `app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:929-978`

```kotlin
private fun RecruitedDestination(data: GameData, navController: NavHostController) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rec by com.gtranca.multiplayer.MultiplayerHub.recruitment.collectAsStateWithLifecycle()
    // O StateFlow dá o valor atual já na 1ª composição (com um inicial "Free" a tela fecharia antes de ler o Joined).
    val statusFlow = rec?.status
    val status = if (statusFlow != null) statusFlow.collectAsStateWithLifecycle().value else com.gtranca.net.RecruitStatus.Free
    val table by com.gtranca.multiplayer.MultiplayerHub.table.collectAsStateWithLifecycle()
    val joining by com.gtranca.multiplayer.MultiplayerHub.joining.collectAsStateWithLifecycle()
    val profile = rememberProfile(data)
    val photos = rememberNearbyPhotos(data)
    val joined = status as? com.gtranca.net.RecruitStatus.Joined
    // Depois do início o recrutamento se encerra (Free): a tela continua desenhando a última mesa por baixo da camada.
    var lastJoined by remember { mutableStateOf<com.gtranca.net.RecruitStatus.Joined?>(null) }
    if (joined != null) lastJoined = joined
    val gettingReady = joining || table != null
    // Saiu, foi removido ou a mesa acabou: de volta à *Iniciar novo jogo* com as opções do próprio jogador.
    LaunchedEffect(joined == null, gettingReady) { if (joined == null && !gettingReady) navController.popBackStack() }
    val shown = joined ?: lastJoined
    if (shown != null) {
        com.gtranca.ui.multiplayer.RecruitedTableScreen(
            shown,
            self = (profile ?: PlayerProfile()).persona(PlayerProfile.DEFAULT_NAME, com.gtranca.ui.profile.ProfilePhoto.savedPath(context)),
            photos = photos,
            onLeave = {
                if (table != null) {
                    com.gtranca.multiplayer.MultiplayerHub.leave()
                } else {
                    scope.launch { runCatching { rec?.leave() } }
                }
            },
        )
    }
    if (gettingReady) {
        com.gtranca.ui.selection.GetReadyOverlay()
        val current = table
        if (current != null) {
            GetReadyEffect(current) {
                navController.navigate(MultiplayerGameRoute) {
                    popUpTo(HomeRoute) { inclusive = false }
                    launchSingleTop = true
                }
            }
            val end by current.end.collectAsStateWithLifecycle()
            end?.let { reason ->
                com.gtranca.ui.multiplayer.MpEndMessage(reason) { com.gtranca.multiplayer.MultiplayerHub.leave() }
            }
        }
    }
}
```

**Começar Partida na tela de Seleção (recrutador)** `app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:868-892`

```kotlin
    val start: () -> Unit = start@{
        if (!vm.canStart || starting) return@start
        starting = true
        scope.launch {
            val live = rec
            if (live != null && vm.hasRemotes) {
                // §16.2 em rede: os recrutados entram direto; a camada "Prepare-se!" cobre a tela até o jogo começar.
                val tableId = com.gtranca.net.nearby.newTableId()
                val plan = live.start(tableId, com.gtranca.game.Tables.personaPool, vm.seed)
                if (plan == null) {
                    starting = false
                    return@launch
                }
                started = true
                com.gtranca.multiplayer.MultiplayerHub.stopRecruiting()
                val photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.gtranca.multiplayer.SharedPhoto.encode(context, profile) }
                com.gtranca.multiplayer.MultiplayerHub.newTable(context, data, photo).hostAsync(rules, selfName, tableId, plan)
            } else {
                // §16.3 mesa só com robôs: o jogo começa no próprio aparelho, sem rede, depois de no mínimo 1,5 s de "Prepare-se!".
                started = true
                live?.closeHosting()
                kotlinx.coroutines.delay(com.gtranca.net.PREPARE_MILLIS)
                navController.startGame(config, vm.seed, com.gtranca.game.Tables.relativeRobots(config.mode, com.gtranca.engine.model.Seat(0), vm.layout.robots))
            }
        }
```


---

## 6. Análise do bug relatado (APK 51e00cc, 2 celulares)

**Sintoma.** Ao tocar "Começar Partida", o **recrutado** viu o modal **"Mesa encerrada"** com **"Mesa não encontrada por perto."** (botão "Voltar ao início") em cima da tela
"Mesa de Hector" (a *Mesa do recrutado*, aguardando o início). O **anfitrião** começou a partida com **um robô no lugar do convidado**.

### 6.1 O que o código prova

1. **De onde vem o texto.** O modal é o `MpEndMessage` com `MpEnd.NotFound` (`app/src/main/kotlin/com/gtranca/ui/multiplayer/MpEndMessage.kt:37`). Esse valor só é gravado em `_end` por:
   `MultiplayerTable.joinAsync` (`app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:295`: `catch (e: Exception)` -> `NotFound`), `rejoinAsync` e `rejoin`. No fluxo de início, é o `joinAsync` do recrutado.
   Logo, **`GuestSession.join` lançou uma exceção** - e `joinAsync` converte **qualquer** exceção em "Mesa não encontrada por perto" (inclusive falha de conexão).
2. **Por que o modal fica sobre "Mesa de Hector".** `RecruitedDestination` continua desenhando `lastJoined` enquanto `gettingReady = joining || table != null`
   (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:944`); o `MpEndMessage(end)` é desenhado por cima quando a `table` nova tem `end`.
3. **Por que o anfitrião põe um robô.** `HostSession.beginPlan` (`net/src/main/kotlin/com/gtranca/net/HostSession.kt:384`) só espera os recrutados por **10 s no total** (`ENTRY_WAIT_MILLIS`); sem o `Hello` com a `inviteKey`
   nesse prazo, o recrutado vira robô sorteado (`REPLACED_BY_ROBOT`). O recrutado, por sua vez, desiste em `FIND_TABLE_MILLIS = 10 s`. **Os dois relógios de 10 s se esgotam juntos**:
   é a mesma causa vista dos dois lados - o recrutado **nunca chegou a conectar** na mesa do jogo.
4. **O recrutado não reaproveita a conexão de recrutamento.** Depois do `RecruitStart`, `startedLocked` **derruba** a conexão de espera (`net/src/main/kotlin/com/gtranca/net/Recruitment.kt:683`) e `Recruitment.shutdown` fecha as demais.
   Para entrar, o recrutado precisa **achar de novo** o anfitrião, agora pelo anúncio da **mesa** (formato 2), via `discover()` + `connect(advert)`. Esse é o elo frágil.
5. **O anúncio da mesa pode ser sobrescrito/apagado pelo próprio anfitrião (candidato mais forte).** No anfitrião, ao começar:
   - `AppNavHost.start` chama `newTable(...)`, que **publica `_table` na hora**. Isso torna `presenceOn` falso em `NearbyPresence` (`app/src/main/kotlin/com/gtranca/ui/AppNavHost.kt:540`) e **cancela** o `runPresence` do anfitrião.
   - O `finally` do `runPresence` (`app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerHub.kt:151`) executa `rec.shutdown()` - que chama `advertiseLocked()` e, portanto, `transport.advertisePresence(...)` - e depois `t.stopPresence()`.
   - Como mesa e presença **dividem um único anúncio** (`advertiseBytes` troca um pelo outro, `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:274`), se a `HostSession.open` já tiver anunciado a mesa, o `shutdown` a **substitui por presença**
     e o `stopPresence` (aviso "saindo" por 0,5 s e parada) **desliga o anúncio**. A `HostSession` só volta a anunciar a mesa em eventos futuros (`entryHelloLocked`, quedas), que nunca ocorrem
     porque ninguém consegue achar a mesa. Resultado: o recrutado procura por 10 s e não acha.
   - Se o desligamento da presença terminar **antes** de `HostSession.open` anunciar a mesa, nada disso acontece. É uma **corrida** entre dois caminhos independentes (cancelamento do `runPresence` por recomposição x `hostAsync`).
6. **Fatores agravantes (prováveis, não decisivos):**
   - Depois do `Start`, o recrutado **não tem mais ninguém reiniciando a busca** (o `refresher` só vive enquanto há coletores de `discoverPresence`); `findTable` depende só do `onFound` de um endpoint **novo**
     (`net/src/main/kotlin/com/gtranca/net/GuestSession.kt:241`). Se a varredura do Android estiver estrangulada ou o anúncio da mesa nascer antes da busca estar efetivamente varrendo, o `onFound` pode nunca chegar.
   - O recrutado ainda **anuncia presença** (aviso "saindo" de 0,5 s) enquanto tenta conectar como convidado - uma combinação P2P_STAR (anunciante que também pede conexão) que o código nunca isolou em teste real.
   - O novo orçamento de buscas (4/30 s, mínimo 2,5 s) **não afeta** esse trecho: ele só governa `refreshPresence`; o `join` usa a busca já ligada (`acquireDiscovery` não consulta o orçamento, só o registra).
     O `found.value = emptyMap()` de `acquireDiscovery` só roda se a busca estiver desligada (`!discovering`); com o `linger` de 10 s ela costuma estar ligada, então não apaga a mesa já achada.

### 6.2 O que NÃO dá para provar sem aparelho

- A **ordem real** entre `HostSession.open` (anúncio da mesa) e o `finally` do `runPresence` do anfitrião (item 5): depende de recomposição e de latência do rádio.
- Se o Nearby entrega o `onFound` do anúncio da mesa a um aparelho que acabou de reiniciar/deixar de buscar, e quanto tempo isso leva (BLE, 3-15 s em muitos aparelhos).
- Se `requestConnection` ao anúncio da mesa falha enquanto o aparelho ainda anuncia presença (`STATUS_...`): o `catch` do `joinAsync` esconde o motivo.
- Que o orçamento de buscas seja inócuo no caso (o raciocínio acima vem da leitura do código).

**Como confirmar nos celulares (logs `GTrancaNearby`, build debug):** no anfitrião, a ordem de `advertise leaving` e do anúncio da mesa; no recrutado, `connect failed ...` (falha de conexão) versus nenhum
`found ...` de mesa (busca não achou). Sem essas linhas, a causa exata é indeterminável.


---

## 7. Adendo final - revisão crítica

Itens ordenados por prioridade (severidade x probabilidade de atingir jogadores reais). "Evidência" aponta o código atual.

| # | Severidade | Gap / risco | Evidência | Correção sugerida |
|---|---|---|---|---|
| 1 | **Alta** | O **início da partida depende de achar um anúncio novo**: o recrutado perde a conexão de recrutamento com o hub e precisa redescobrir a mesa; a mesa e a presença do anfitrião disputam **um único anúncio** e a presença pode apagar a mesa (item 5 do §6) | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:683`, `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerHub.kt:151`, `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:274` | Promover a conexão de espera à conexão da mesa (o recrutador abre a `HostSession` sobre as conexões existentes, sem redescobrir); no mínimo, tirar `advertise`/`stopPresence` do caminho do `rec.shutdown` no anfitrião e serializar "presença -> mesa" |
| 2 | **Alta** | `joinAsync` converte **qualquer** exceção em "Mesa não encontrada por perto" - o usuário e o log não distinguem busca, conexão e recusa | `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:287` | Tipar o erro (`NotFound`, `ConnectFailed`, `Timeout`) e logar `connect failed <status>`; mensagens distintas |
| 3 | **Alta** | Início **sem confirmação** dos recrutados: o anfitrião espera 10 s fixos e troca por robô **sem avisar o recrutado**, que continua vendo "Aguardando" | `net/src/main/kotlin/com/gtranca/net/HostSession.kt:386` | Handshake "Pronto" (o recrutado confirma que está na mesa) antes do Prepare-se; prazo maior (20-30 s); avisar o recrutado e dar volta ao Início com mensagem |
| 4 | **Alta** | Modelo `P2P_STAR` com **um hub por vez**: o livre mantém espera com **um** recrutador; dois recrutadores no mesmo ambiente disputam jogadores; com 3+ jogadores o hub carrega todas as conexões e as fotos | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:997` | Medir o limite de spokes por hub no hardware real; avaliar `P2P_CLUSTER` apenas para a presença; limitar fotos a 1 envio |
| 5 | **Alta** | **Latência de percepção** (7,5-16 s no modo ativo; 15-31 s no econômico) por causa do orçamento de buscas e do reinício como único meio de rever quem já estava no ar | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:570` | Usar as conexões abertas (batimento 400 ms, `RecruitBye`) como fonte primária; a busca só para quem entra; avaliar busca contínua sem reiniciar se o `onFound` repetir |
| 6 | **Alta** | **Estrangulamento de varredura do Android** (~5 inícios/30 s por app; acima, resultado vazio silencioso): o orçamento é teórico (4/30 s) e não foi validado em aparelho | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:581` | Medir em 3 versões de Android; reduzir o orçamento se o log mostrar listas vazias; contador visível no log |
| 7 | **Média** | **Tela bloqueada / app em segundo plano**: não há serviço em primeiro plano, `WakeLock` nem `keepScreenOn`; o Android pode suspender anúncio, busca e conexões | `app/src/main/AndroidManifest.xml` (sem `FOREGROUND_SERVICE`); nenhum `keepScreenOn` no app | `FLAG_KEEP_SCREEN_ON` nas telas de recrutamento e de espera; aviso ao usuário; serviço em primeiro plano só se os testes mostrarem necessidade |
| 8 | **Média** | **Bateria**: busca recomeçando + anúncio + batimento de 400 ms durante todo o tempo nas duas telas | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:1017`, `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:570` | Subir o batimento para 1 s; parar o modo ativo com a tela apagada; reduzir fotos |
| 9 | **Média** | **Wi-Fi/Bluetooth/localização desligados** não são verificados antes de ligar (só permissões/Play Services/localização no Android <= 12): a falha vira exceção do rádio | `app/src/main/kotlin/com/gtranca/net/nearby/NearbyTransport.kt:285` | Verificar `BluetoothAdapter.isEnabled`/Wi-Fi e orientar o usuário com texto próprio |
| 10 | **Média** | **Versões diferentes**: nas conexões de espera a mensagem de outra versão é **ignorada em silêncio**; anúncios de formato desconhecido somem da lista ("ninguém por perto") | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:710`, `net/src/main/kotlin/com/gtranca/net/nearby/AdvertCodec.kt:72` | Mostrar "jogador com outra versão do jogo" na lista (formato de anúncio com byte de versão visível) e na recusa do convite |
| 11 | **Média** | **Fotos**: até 20 KB por conexão, trocadas pela espera; só do recrutador visto; os outros recrutadores aparecem com inicial; foto pesada em BLE lento atrasa o `RecruitInvite` na mesma conexão | `net/src/main/kotlin/com/gtranca/net/PresencePhotos.kt:41` | Enviar foto depois do convite e em prioridade baixa; miniatura menor |
| 12 | ~~Média~~ **Decidido** | ~~Entrada no meio do jogo sem porta na UI~~ | `docs/recrutamento.md` ("Chamada no meio do jogo") e `definition.md` §16.7 | Opção A escolhida em 2026-10-27: o anfitrião chama um jogador livre; implementada nesta frente |
| 13 | **Média** | Chaves de presença no anúncio (`inviting`/`invited`) permitem que outro aparelho tome o lugar de um chamado | `net/src/main/kotlin/com/gtranca/net/Transport.kt:38` | Convite por conexão sempre (sem reserva por anúncio) ou segredo curto por convite |
| 14 | **Média** | **Reconexão de espera** (0,3/1/2/4/8 s) e do jogo (1 s -> 15 s) disputam rádio com a busca; sem prioridade, a reconexão pode falhar durante um recomeço | `net/src/main/kotlin/com/gtranca/net/Recruitment.kt:1009` | Adiar recomeço enquanto houver `Reconnecting`/espera pendente (já há para `pending`) |
| 15 | **Baixa** | Logs só no build debug; em campo, sem telemetria para diagnosticar quedas | `MultiplayerAccess.transport` | Buffer circular de eventos de rede exportável pelo usuário |
| 16 | **Baixa** | `Decoded.Malformed` descartado sem log | `net/src/main/kotlin/com/gtranca/net/Codec.kt:30` | Contador e log debug |
| 17 | **Baixa** | A sessão guardada vale 12 h e "Continuar mesa" tenta 30 s; mesa que mudou de anfitrião pode não ser achada | `app/src/main/kotlin/com/gtranca/multiplayer/MultiplayerTable.kt:897` | Atualizar a sessão na sucessão (nova chave) e mostrar o estado da tentativa |

### Plano de correções em etapas

1. **Etapa 1 - Entrada confiável na mesa (itens 1, 2, 3).** (a) Tirar a presença do caminho do anfitrião ao abrir a mesa; (b) manter a conexão de recrutamento como a da mesa ou fazer o recrutado
   reconectar com **tentativas e backoff por 30 s**, tipando os erros; (c) handshake "Pronto" e aviso ao recrutado quando virar robô. Teste em 2 celulares é obrigatório.
2. **Etapa 2 - Medir o rádio (itens 4, 5, 6, 8, 14).** Logs já existem; rodar roteiro de 2 e 3 celulares (Android 12, 13, 14) medindo `restart ok/skipped/failed`, `heartbeat dead`, tempo entre `call` e o convite visível, nº de spokes por hub.
3. **Etapa 3 - Ritmos baseados em medição (itens 5, 6, 8).** Ajustar orçamento, batimento e modo ativo; preferir mensagens nas conexões abertas para quem sai/cai; busca só para quem entra.
4. **Etapa 4 - Robustez de ambiente (itens 7, 9, 10).** `keepScreenOn`, verificações de Bluetooth/Wi-Fi, versão visível nos anúncios.
5. **Etapa 5 - Produto (itens 12, 13).** Decidir a porta do §16.7 e endurecer o convite (sem reserva por anúncio).
6. **Etapa 6 - Qualidade (itens 11, 15, 16, 17).** Fotos em segundo plano, telemetria exportável, sessão atualizada na sucessão.
