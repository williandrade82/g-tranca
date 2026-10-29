# Recrutamento e seleção de jogadores: máquina de estados e sincronização (§14.4, §16.2, §16.15)

Fonte da verdade das regras: `definition.md` §14.4, §14.6, §16.2, §16.3 e §16.15. Este documento descreve **como** as duas
telas (*Iniciar novo jogo* e *Seleção de jogadores*) e a tela do recrutado ficam sincronizadas entre aparelhos. Telas:
`docs/telas.md` (3.2, 3.3, 3.13). Código: `net/.../TableLayout.kt` (lugares), `Recruitment.kt` (conexões),
`PresencePhotos.kt`, `HostSession.kt` (entrada na mesa e início) e `app/.../NearbyTransport.kt`.

## Princípios (herdados, continuam valendo)

1. **O anúncio de presença só descobre; a conexão decide.** O anúncio leva o essencial e muda pouco: chave, nome, os
   **estados** (`livre` e `recrutando`, abaixo), `inviting` (reserva: chaves chamadas que ainda não têm conexão) e
   `recruitedBy` (recrutador que já chamou o jogador, para os outros mostrarem "Recrutado"). Todo o resto (convite,
   cancelamento, aceite, recusa, lugares, saída, remoção, início) vai por **mensagens na conexão de espera** do jogador com
   o recrutador (`ReliableLink`: ordem, confirmação e reenvio).
2. **Nada se decide pela ausência momentânea na busca** (anunciante que para e reanuncia, busca reiniciada): a presença só
   sai da lista como diz "Lista de presença".
3. **O estado sobrevive** à recomposição da tela e ao reinício da presença: o `Recruitment` vive enquanto o Wi-Fi do jogo
   está ligado (chave de presença).
4. **Lugares versionados:** o recrutador envia sempre o estado **completo** (config + lugares) com um número de versão
   crescente; o chamado/recrutado aplica só versão maior que a aplicada. O convite, a resposta e a remoção **não esperam** o
   debounce de 0,3 s; os arrastos (mudanças de lugar) esperam.
5. **Só o recrutador decide quem está em cada lugar.** O recrutado só aceita, recusa ou sai.

## Estados do aparelho (presença)

| Estado | Quando | Aparece para os outros |
|---|---|---|
| **fora** | qualquer tela que não é a 1 nem a 2 (Início, Perfil...), Wi-Fi ligado | só a presença básica; **não** está nas listas e não pode ser chamado |
| **livre** | tela 1 (*Iniciar novo jogo*) com o Wi-Fi ligado, sem estar chamado | na lista da tela 2 dos recrutadores |
| **recrutando** | tela 2 (*Seleção de jogadores*) com o Wi-Fi ligado | na lista da tela 1 dos livres (só informativo) |
| **chamado / recrutado** | convite pendente / aceito | "Recrutado" (não selecionável) para os outros recrutadores; deixa de estar `livre` |
| **jogo** | num jogo | não anuncia |

Quem está `livre` abre a **conexão de espera** com um recrutador (um por vez: `P2P_STAR`, spoke de um só centro); recrutador
não recruta recrutador (nenhum abre conexão a outro). Dois aparelhos `recrutando` ignoram-se.

## Modelo dos lugares (recrutador)

`TableLayout` (puro, sem rede): lista de lugares `0..n-1` (`n` = 2 no individual, 4 em duplas).

- Lugar **0**: o recrutador (`HOST`), **fixo**, embaixo. Lugar **2** (duplas): o parceiro; lugares 1 e 3: adversários;
  individual: lugar 1 é o adversário. O rótulo **Parceiro/Adversário** de um lugar é calculado em relação ao lugar 0.
- Cada lugar é `VACANT`, `HOST`, `ROBOT(persona)`, `CALLED(chave, nome)` ou `RECRUITED(chave, nome)`.
- Operações: **chamar** (lugar `VACANT` + jogador livre → `CALLED`), **pôr robô** (lugar `VACANT` + persona não usada),
  **remover** (lugar ≠ 0), **mover/trocar** (de ≠ 0 para ≠ 0; ocupado → troca; `VACANT` → move), **completar com robôs**
  (sorteia, com a semente do recrutador, personas ainda não usadas nos `VACANT`), **aceitar** (`CALLED → RECRUITED`),
  **recusar/sair/perder** (→ `VACANT`).
- `canStart` = todos os lugares preenchidos **e** nenhum `CALLED`.

## Recrutador (por jogador chamado)

`Chamado` -> `Recrutado` -> fora (lugar volta a `VACANT`).

| Evento | Efeito |
|---|---|
| tela 2 aberta (`openTable`) | anuncia `recrutando`; os livres se ligam (espera), no máximo 6 |
| chamar (lugar + jogador livre) | lugar `CALLED`. **Se o jogador já está em espera:** `RecruitInvite` na hora pela conexão (sem mexer no anúncio). Senão: reserva, a chave vai em `inviting` e o convite sai quando ele conectar |
| `RecruitReply(aceito)` | `RECRUITED`; sai de `inviting`; envia a **todos** os chamados/recrutados o estado completo com versão nova (sem debounce) |
| `RecruitReply(recusado)` / saldo insuficiente | lugar `VACANT`; aviso ao recrutador com o motivo; o jogador volta a aparecer livre |
| remover `CALLED` | `RecruitEnd(CANCELLED)` (o modal some, sem aviso); lugar `VACANT` |
| remover `RECRUITED` | `RecruitEnd(REMOVED)` ("tirou você da mesa"); lugar `VACANT` |
| mover/trocar | estado novo (versão nova) a todos os chamados/recrutados, depois de 0,3 s; o convite pendente mostra a posição nova |
| `RecruitLeave` ("Sair da mesa") | lugar `VACANT`; aviso |
| conexão cai (`RECRUITED`) | continua `RECRUITED` **sem conexão** por 8 s à espera do reingresso; passado o prazo: `VACANT` + aviso "fora do alcance" |
| `CALLED` sem conexão e ausente da lista por 10 s seguidos | `VACANT` + aviso "fora do alcance" |
| Começar Partida | exige `canStart`; `RecruitStart(tableId, prepareMillis = 1500)` a cada `RECRUITED` conectado (espera as confirmações até 2 s); **1.** `Recruitment.plan` valida e monta o plano (sem enviar nada); **2.** o recrutador larga a leitura dos eventos e abre a mesa do jogo (`HostSession` com o plano), que **anuncia a mesa**; **3.** só então `Recruitment.sendStart` envia `RecruitStart(tableId, prepareMillis = 1500)` a cada `RECRUITED` conectado (espera as confirmações até 2 s) e a presença deixa de anunciar por cima da mesa; a sessão espera `Ready` de cada recrutado, no mínimo 1,5 s e no máximo 30 s; quem não confirmou vira robô sorteado (aviso) e, se ainda tiver conexão, recebe `Rejected` com o motivo |
| voltar à tela 1 / sair / Wi-Fi desligado | `CALLED`: `RecruitEnd(CANCELLED)`; `RECRUITED`: `RecruitEnd(CLOSED)` ("Mesa encerrada"); espera as confirmações até 0,5 s; lugares limpos (sempre `VACANT` ao abrir de novo) |

## Chamado / Recrutado (jogador livre)

`Livre` -> (vê um recrutador `recrutando`, ou a própria chave em `inviting`) **em espera** (interno) -> (recebe
`RecruitInvite`) `Chamado` -> (Aceitar) `Recrutado` -> fim.

- **Em espera:** conecta ao recrutador, envia `RecruitHello` e a foto; a conexão fica aberta. Falha: nova tentativa depois de
  0,3, 1, 2, 4 s (e a cada 8 s) enquanto o recrutador continuar recrutando ou me listar. O recrutador que parou de recrutar
  (e não me chama) solta a espera. Outro recrutador que me liste em `inviting` toma o lugar da espera.
- **Chamado:** o modal *Convite* mostra recrutador, aposta, tipo, posição, título da regra; **25 s para responder** (relógio local de quem recebe, a partir da chegada; `Invited.deadlineMillis` e `remainingMillis()` para a contagem). Sem resposta: `RecruitReply(recusado, NO_RESPONSE)`, volta a `Livre`, o modal fecha e o recrutador devolve o lugar a VAGO; o recrutador também espera 25 s + 2 s e, se nada chegou, devolve o lugar sozinho. Um convite reenviado depois de uma queda leva o tempo que ainda resta. Cada
  `RecruitSync` atualiza o modal (posição). Saldo menor que a aposta: recusa automática com motivo. Aceitar/Recusar vão pela
  conexão. `RecruitEnd(CANCELLED)` ou queda da conexão desfazem o convite.
- **Recrutado:** a tela do recrutado (só leitura) lê de `Recruited.table`: config + lugares, sem lista de jogadores
  próximos; botão **Sair da mesa** (`RecruitLeave`). `RecruitEnd(REMOVED | CLOSED)`, `RecruitStart` ou a saída própria
  encerram. **Queda** sem aviso: continua `Recrutado` por até 8 s tentando reconectar (`RecruitHello` de reingresso; o
  recrutador reenvia o estado inteiro); passado o prazo: livre + aviso "Mesa encerrada".
- **Início perdido:** se `RecruitStart` não chegou (queda no toque), a mesa do jogo aparece na busca com a chave do
  recrutado em `invited`: ele trata como início e entra.
- **Prepare-se:** ao receber `RecruitStart` (e o recrutador ao tocar), a tela mostra a imagem por **no mínimo** `prepareMillis`
  e a mantém até o jogo começar (`GameStarted`).

## Terceiro (outro recrutador)

Vê o jogador com `recruitedBy` de outra chave como "Recrutado" (não selecionável). `recruitedBy` só existe enquanto o
jogador está `Chamado` ou `Recrutado`; recusa/saída/remoção o limpam.

## Entrada na mesa e início do jogo

**Lista e anúncio (§16.2, §16.15):** `closeHosting` manda `RecruitBye` às conexões de espera que não são chamadas nem recrutadas, e o outro lado tira o recrutador da lista na hora. `NearbyTransport` trata `EndpointUnknownException` (8011) esquecendo só aquele endpoint e disparando uma busca extra (dentro do orçamento). `stopPresence(announceLeaving = false)` é o que o recrutado usa antes de procurar a mesa; o pedido de conexão espera 0,25 s depois da última mudança de anúncio.

**Rádio (§16.15):** `GmsNearbyApi` anuncia e conecta com `NON_DISRUPTIVE` e `disruptiveUpgrade = false` (sem Wi-Fi Direct/hotspot; `lowPower` desligado por causa dos anúncios de até 100 bytes). `NearbyTransport` dá prazo a cada chamada ao Nearby (5 s anúncio e aceite, 8 s busca, 20 s pedido de conexão; "já conectado" 8003 vale como sucesso) (`api timeout <nome>` no log; `api <nome> <ms> ms` com a duração), separa a trava do anúncio da trava da busca e deixa o aviso de "saindo" e as desconexões fora do caminho do início (`Recruitment.shutdown` não espera confirmações depois do início).

`HostSession` recebe o **plano de lugares** (`TablePlan`): o layout (humano = Recrutado com a chave de presença; robô com a persona),
as personas possíveis e a semente. Cada recrutado entra com `GuestSession.join(tableId, inviteKey)` e já fica no lugar
planejado (`Welcome` com `started = false`) e responde `Ready` (**protocolo versão 16**; o anfitrião conta o recrutado como presente só com ele). Enquanto algum recrutado não confirmou, o anfitrião reafirma o anúncio da mesa a cada 2 s. Entre `plan` e `sendStart` o `shutdown` da presença só se marca como fechado (nada de `RecruitBye` nem de fechar conexões); `sendStart` manda o início, o `RecruitBye` só aos que não são recrutados, espera até 2 s e fecha. O recrutado ignora o `RecruitBye` do próprio recrutador enquanto `Joined`; com a conexão caída mantém a busca viva (`holdSearch`) durante os 8 s e faz uma última olhada nos anúncios de mesa antes de virar `Free`. Quem procura a mesa (`GuestSession.join`) repete a busca (rajada de recomeços dentro do orçamento de §16.15) e a conexão por até 30 s, com espera crescente; a falha final é tipada (`NOT_FOUND`, `CONNECT_FAILED`, `TIMEOUT`) e cada tipo tem o seu texto no modal *Mesa encerrada*. Ao abrir (`HostSession.open`), a sessão espera `prepareMillis`
(1,5 s) **e** todos os humanos do plano com `Ready` recebido, até `waitMillis` (30 s); quem faltou vira robô sorteado (aviso em
`lobbyNotices`); envia `GameStarted` (com os robôs de cada lugar) e preenche `gameStart`. Os robôs são exatamente os da tela 2 (nada é sorteado de novo,
exceto os de quem não entrou). Nada de semente ou token alheio nas mensagens (testado varrendo os bytes).

## Chamada no meio do jogo (§16.7)

Quem hospeda uma mesa **em andamento** anuncia a mesa (não a presença), então o convite não pode ir por uma conexão de
espera: ele segue o caminho do "início perdido", pelo **anúncio da mesa**.

1. **Anfitrião** (`HostSession.callPlayer(seat, key, name)`): o assento precisa estar aberto (`openSeat`); a chave de presença
   do chamado entra em `TableAdvert.invited` (com `inProgress`). Uma chamada por assento, no máximo 3 no total. Quem chama
   enxerga os livres por `LocalTransport.discoverPresence()` (só com o modal *Chamar jogador* aberto, em modo ativo), filtrando
   `free`, sem `hosting`, sem `recruitedBy` e sem chamada pendente.
2. **Livre** (`Recruitment.tablesLocked`): se está livre (tela 1) e a própria chave aparece em `invited` de um anúncio
   `inProgress`, emite `RecruitEvent.Called(tableId, myKey)` (uma vez por instância). O hub encerra a presença (sem
   "Prepare-se!") e abre a mesa com `joinAsync(tableId, name, inviteKey)`.
3. **Conexão:** `Hello(inviteKey)` numa mesa em andamento cuja chave está nas chamadas recebe `CallOffer(hostName, rules, seat,
   debit)`; sem chamada, continua a recusa "o lugar passou para um robô". `GuestStatus.Called` abre o modal *Convite*; o saldo
   menor que o `debit` recusa sozinho. `CallReply(accepted, reason)` volta ao anfitrião.
4. **Aceite:** vira um `JoinRequest` já marcado `called` e o anfitrião o aprova na hora pelo mesmo caminho de `approveJoin`
   (troca o jogador, ajusta o razão de apostas, manda `StakeDebit` e `Welcome(started = true)`).
5. **Fim da chamada** sem aceite: recusa, 27 s sem resposta, conexão perdida, cancelamento do anfitrião, ou o assento deixou de
   ser ocupável (dono voltou, robô posto): a chamada sai de `invited`, o convidado vê o motivo e o anfitrião recebe o aviso.

## Lista de presença (NearbyTransport)

| Situação | Regra |
|---|---|
| mesma chave achada em outro endpoint | o endpoint antigo é descartado (a chave é a identidade; vale o mais recente) |
| `onLost` | a presença só sai se não reaparecer em 3 s (1 s no modo ativo; anunciante que para e reanuncia não pisca) |
| aviso "saindo" | sai na hora (todas as entradas da chave) |
| busca reiniciada a cada 15 s (modo econômico) | quem não reaparece 3 s depois, em 2 verificações seguidas, sai (~31 s) |
| **orçamento de buscas** | o Android limita os inícios de varredura (~5 por 30 s por app; acima, volta vazia em silêncio): no máximo **4 recomeços por janela móvel de 30 s** (contando o inicial) e nunca em menos de 2,5 s; sem orçamento o laço espera a janela liberar (`restart skipped budget=N/4 wait=ms`). Recomeço pulado ou que falhou **não** conta como perda de ninguém |
| **modo ativo** (telas *Iniciar novo jogo* e *Seleção de jogadores* abertas, Wi-Fi ligado) | busca reiniciada a cada **7,5 s**, folga de 1 s; sai quem não reaparece em 2 recomeços seguidos (queda silenciosa sem conexão: até ~16 s); fora dessas telas volta o modo econômico (15 s) |
| **rajada** (`refreshNow()`, botão atualizar ou puxar para atualizar) | até 2 recomeços rápidos (2,5 s um do outro, folga 0,5 s) dentro do orçamento e volta aos 7,5 s; novo pedido durante a rajada é ignorado; só vale no modo ativo |
| conexão aberta (espera/recrutamento) | mensagem de teste a cada 400 ms; qualquer byte recebido conta como vida; sem **nenhum** byte por 4 s a conexão é dada como caída e o jogador sai das listas (~4 s). O aviso explícito é o caminho rápido (~1 s). Convite (`Invited`) que perde a conexão continua na tela por 1,5 s: se a conexão volta e o convite é reenviado, o status é atualizado no lugar, sem piscar |
| aviso explícito de saída (`RecruitBye`) | pela conexão aberta e pelo anúncio "saindo" (0,5 s); quem recebe tira o jogador da lista na hora (~1 s no rádio real) |
| chave **fixada** (chamada/recrutada/recrutador de quem aceitou) | a varredura não a remove; só `onLost` após a folga, "saindo" ou a queda da conexão |
| pedido de conexão em andamento | a busca não reinicia até ele terminar (as conexões já abertas não são afetadas) |

Cada remoção registra o motivo no log de depuração.

## Fotos

As fotos vão **pela conexão de espera** (`PresencePhoto` logo depois do `RecruitHello`, nos dois sentidos, uma vez por
conexão), nunca por uma conexão avulsa (a avulsa e a de recrutamento usam o mesmo endpoint, e desconectar uma derrubava a
outra). Jogador sem conexão de espera (fora da cota, ou sem recrutador) aparece com a inicial. A foto dos **outros**
recrutadores na lista da tela 1 sai como inicial (no `P2P_STAR` o livre mantém espera com um só recrutador).

## Tempos (§16.15)

| O quê | Tempo |
|---|---|
| nova tentativa de conexão de espera | 0,3, 1, 2, 4 s (depois 8 s) |
| `onLost` da presença até sair da lista | 3 s (modo ativo: 1 s) |
| convite sem resposta vale como recusa | 25 s (+ 2 s de folga no recrutador) |
| mensagem de teste / queda da conexão aberta | a cada 400 ms / após 4 s sem nenhum byte |
| convite que perdeu a conexão continua na tela | 1,5 s |
| aviso de "saindo" (anúncio) e espera das confirmações | 0,5 s |
| lugares enviados (debounce; convite, resposta e remoção não esperam) | 0,3 s |
| reingresso do recrutado que perdeu a conexão | 8 s |
| chamado sem conexão que sumiu da busca | 10 s |
| "Prepare-se!" | no mínimo 1,5 s; até o jogo começar |
| espera pelos recrutados entrarem na mesa após Começar | até 10 s |
| confirmações no Começar Partida | até 2 s |
| confirmações ao voltar / sair / Wi-Fi desligado | até 0,5 s |

## Decisões provisórias (registradas; reabrir com o produto se não servirem)

1. **Voltar da tela 2 à tela 1 encerra o recrutamento** (chamados cancelados, recrutados recebem "Mesa encerrada"); os
   lugares nunca persistem.
2. **O recrutado se vê embaixo** na mesa só de leitura (a mesa é girada para o lugar dele); o recrutador também.
3. **O Wi-Fi fica também na tela 1** (botão no título), além da tela 2 e do Perfil, para quem só quer ficar disponível.
4. **Mudança de modo só na tela 1**; a tela 2 não troca o modo nem altera as opções (por isso a configuração só é enviada
   uma vez por convite/estado, e as mudanças que passam pelo debounce são as de lugares).
5. **Aposta:** mesmo índice ao trocar de nível; acima do saldo vira o maior valor coberto (§14.6).
6. **Recusa/saída/queda deixam o lugar VAGO** (sem robô sorteado sozinho); o sorteio só acontece em *Completar com robôs* e,
   no início, para quem não confirmou `Ready` na mesa em 30 s.

## Em aberto (perguntar ao produto)

1. ~~§16.7 perdeu a porta de entrada~~ **Decidido (2026-10-27, opção A):** o anfitrião **chama** um jogador livre para o
   assento aberto; ver "Chamada no meio do jogo" abaixo. O pedido de entrada sem chamada continua só na rede.
2. Trocar de duplas para individual com gente já nos lugares: hoje só é possível na tela 1 (sem lugares ocupados).

## Diagnóstico (build debug, tag `GTrancaNearby`)

Sem nomes, fotos ou bytes; chaves abreviadas (2 caracteres). Linhas úteis: `rec state A->B` (recrutado),
`rec call/accepted/refused/removed/left <motivo>`, `rec seats v=N send/apply`, `rec invite sent via link in N ms`,
`rec start prepare=N`, `presence found/lost/dedupe/leaving/sweep/remove <motivo>`, `restart ok/failed`.
