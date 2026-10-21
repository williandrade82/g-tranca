# Plano: multijogador local (até 4 pessoas, cada uma no seu celular, sem internet)

Status: **aprovado em 2026-10-20** (recomendações N1 a N11 aceitas; mesa reduzida decidida pelo usuário). **Etapas 1, 2 e 3 feitas** (N12 e N13 confirmadas). Origem: `docs/plano-teamplay.md`.
Fluxo de sempre: `definition.md` e `docs/telas.md` primeiro, depois testes, código e `rules-auditor`. O jogo individual e o fluxo atual contra
robôs **não mudam**; o multijogador é uma opção nova na tela de mesas disponíveis.

## Objetivo
Uma mesa **criada sempre com 4 assentos** (duplas) em que 1 a 4 pessoas jogam, cada uma no seu celular, e os assentos vagos têm robôs. **Exceção (mesa reduzida):** com apenas um jogador conectado, o anfitrião pode **excluir duas posições**, e a mesa passa a ser de 2 assentos (individual); ver "Mesa reduzida". Um celular é o
**anfitrião** (autoridade: roda o `GameController` e o `:core-engine`); os demais são **convidados**. Sem internet: Nearby Connections
(`P2P_STAR`).

## Restrições (do pedido; não reabrir)
- Convidado só envia a `Action` pretendida e eventos de sala; o anfitrião valida com `legalActions`, aplica com `apply()` e transmite.
- A cada celular só vai o que aquele assento vê: **nunca** mão dos outros, monte, mortos, semente ou `Match`/`GameState` completo.
  **Única exceção:** o 1º e o 2º sucessores (seção "Sucessão").
- Cada aparelho anima por conta própria, na velocidade do seu jogador; **não há sincronização de animação**.
- Transporte atrás de interface, com implementação em memória (testes JVM) e a real (Nearby). Mensagens `@Serializable` com versão de
  protocolo; versões diferentes são recusadas com mensagem em pt-BR.
- Regras da mesa (perfil, pontuação-alvo, aposta) trafegam **uma vez**, antes do início.
- Sem limite de tempo por jogada; moedas locais e não verificáveis (risco aceito); nada do `Match` em disco em convidados, anfitrião ou sucessores.
- Aparelhos sem Google Play Services ficam fora por ora.

## O que o código de hoje impõe (achados da leitura)
1. **Dependência `:net → :core-engine` não basta.** `PublicEvent` (a "última jogada de cada jogador") está em `:ai`; `GameController`,
   `GameConfig`, `Persona`, `TurnRedThrees` e `SeatPlayer` estão em `:app`. `:net` não enxerga nenhum deles, e o teste "partida completa
   igual à local" precisa do `GameController`. Ver decisão **N1**.
2. **`GameController` é de um humano só:** `HumanPlayer` único, `viewerSeat` único, `humanRequest` único, `GameSnapshot` de um espectador,
   e o laço espera sinais de animação do aparelho (`presentationSignal`, `dealAnimationSignal`, `awaitingNextRound`, `botDelayMillis`).
3. **`GameViewModel` assume `humanSeat = Seat(0)`** (linha ~251); `SeatRole.of(mode, seat, viewer)` já aceita qualquer visualizador, mas a
   interface precisa ser conferida para convidados nos assentos 1 a 3.
4. **`PlayerView` é `@Serializable`, mas carrega `rules`** (o perfil inteiro) a cada vista; no multijogador a mensagem por jogada **não
   leva** `rules` (vão uma vez). `PublicEvent` **não** é `@Serializable` (precisa de serialização; ver N1).
5. **Informação oculta a varrer:** `GameSnapshot.history` (`RoundRecord`/`SideScore`/`HandPenalty`) e `RoundResult` vão a todos; conferir na
   etapa 3 que não trazem listas de cartas de mãos alheias (se trouxerem, o DTO as reduz a pontos). `PlayerView.hand` é só a do dono.
6. **Bots e relógio:** o Difícil tem teto de 800 ms (`HardBotConfig.DEFAULT`), então o resultado dele depende da máquina. Os testes de "mesma
   semente, mesmo resultado" usam bots **determinísticos** (Fácil/Médio, ou o Difícil `withoutTimeLimit()`), como o `:sim` já faz.
7. **Resultado de jogo com vários humanos:** hoje `resign()` encerra o jogo e dá a vitória ao outro lado; no grupo ele vira "robô assume e o
   jogo segue" (reescrita de §13.1 e §14.6, sem alterar o individual).

## Arquitetura proposta
### Módulos
- **`:net`** (Kotlin/JVM puro, depende só de `:core-engine`): interface `LocalTransport`, `MemoryTransport` (anfitrião e convidados no mesmo
  processo, relógio virtual), mensagens do protocolo, DTOs de vista e a **sessão** (sala, tokens, números de sequência, reconexão,
  ordem de sucessão). Não conhece `GameController`.
- **Orquestração do anfitrião** (liga `GameController` ao transporte): onde mora depende de N1.
- **Android real** (Nearby, permissões, descoberta): em `:app`, em pacote próprio (`com.gtranca.net.nearby`), atrás da interface. Um módulo
  `:net-android` só se justificar depois (hoje não: é uma classe, e o `:app` já depende de Play Services quando precisar).
- Regra de dependência mantida: `app → net, data, ai, core-engine`; `net → core-engine`; o `core-engine` não depende de nada.

### Transporte
```
interface LocalTransport {
    val events: Flow<TransportEvent>                    // Conectado(endpoint), Desconectado(endpoint), Recebido(endpoint, bytes)
    suspend fun advertise(tableInfo: TableAdvert)       // anfitrião publica a mesa
    suspend fun discover(): Flow<TableAdvert>           // convidado procura mesas
    suspend fun connect(table: TableId): Result
    suspend fun send(endpoint: EndpointId, bytes: ByteArray)
    suspend fun disconnect(endpoint: EndpointId)
}
```
O transporte só entrega **bytes** e eventos de conexão; identidade é o **token**, nunca o endpoint. `MemoryTransport` permite simular perda,
duplicação, reordenação e queda de um endpoint.

### Protocolo (rascunho)
Envelope: `{ protocolVersion, tableId, seq, ack, payload }`. `payload` (sealed, `@Serializable`):
- **Sala:** `Hello(name, token?)`, `Welcome(token, tableRules, roster)`, `Reject(reason)`, `SeatChosen(seat)`, `RenameSeat`, `RosterUpdate(roster)`,
  `Leave`, `Resign`, `StartGame`.
- **Jogo:** `ViewUpdate` (a vista do assento daquele celular + eventos novos desde o último), `ActionProposed(action, requestId, seq)`,
  `ActionRejected(reason)`, `Explain/ExplainReply` (motivo da recusa de uma jogada; ida e volta ao anfitrião), `NextRound`.
- **Controle do anfitrião:** `Paused(reason)`, `Resumed`, `SeatReplacedByBot`, `Recruiting`, `HostChanged(newHost)`.
- **Só sucessores:** `SuccessorState(match, roundEvents, botRandomCalls, personas, tokens, betLedger, seq)` e `SuccessorAck(seq)`.
- Número de sequência por remetente; o receptor descarta duplicadas e atrasadas e pede reenvio das que faltam; jogada fora da vez ou
  ilegal volta como `ActionRejected(motivo)` sem alterar o estado.
- **Versão:** `Hello` leva `protocolVersion`; diferente → `Reject("Versão do jogo diferente: atualize o app nos dois celulares")`.

### Identidade e tokens
Cada jogador recebe um **token** aleatório ao entrar. **Todos** conhecem `(id, nome, ordem de entrada)` de todos; o **token não é parte da
identificação pública**: ele só é conhecido pelo dono, pelo anfitrião e pelos sucessores (se fosse transmitido a todos, qualquer um
sequestraria um assento). Reingresso: `Hello(token)` no mesmo assento, enquanto não substituído nem ocupado.

### O que cada celular recebe (DTO por assento)
`SeatView` = `PlayerView` **sem `rules`** + `turnEvents` por assento + `turnRedThrees` + placar, totais, histórico reduzido, vez, `thinkingSeat`,
situação dos mortos (só `MortoStatus` e tamanho). Montado pelo anfitrião com `viewFor(seat)` (a mesma função dos robôs). Teste obrigatório:
**varrer o JSON serializado** de todas as mensagens do convidado em busca de qualquer carta que não seja da sua mão, da mesa, do lixo ou
dos 3 vermelhos, da semente e de qualquer campo de `Match`.

### Refatoração do `GameController` (risco 1; etapa 2, antes do transporte)
Mantém o jogo contra robôs **idêntico** (mesma semente, mesmo resultado, testes atuais verdes):
- **Humanos por assento:** `Map<Seat, HumanPlayer>`; `humanRequest` e `requestCounter` por assento; `explain(seat, action, requestId)`.
- **Fluxo de eventos por jogada:** o controlador emite `GameEvent` (jogada aplicada com `PublicEvent`, vez mudou, partida pontuada, jogo
  terminou, pausa, substituição). O `state` (StateFlow) de hoje continua para o jogo local, derivado do mesmo fluxo. Uma instância de
  `SeatViewBuilder` gera o snapshot **por assento**.
- **Ritmo:** em modo rede, o controlador **não espera** `presentationSignal`, `dealAnimationSignal` nem `botDelayMillis` de um aparelho
  só: ele só pára quando precisa de decisão humana. Cada UI (a do anfitrião inclusive) consome o fluxo e **segura o pedido ao seu humano**
  até terminar as próprias animações. No jogo local (1 humano), o comportamento atual de pausas fica como está (parâmetro de ritmo
  `PacingPolicy`: `LocalAnimated` x `Free`).
- **Desistência em grupo:** `replaceWithBot(seat, persona)` troca o `SeatPlayer` do assento por um `BotSeatPlayer`, com `Random(botSeed(gameSeed,
  assento))` **retomado** da posição `CountingRandom` do assento e memória reconstruída de `roundEvents` (o mesmo caminho do jogo
  retomado). O `resign()` do individual não muda.
- **Salvamento:** `onSave = null` no anfitrião, nos sucessores e nos convidados em multijogador (nada em disco).

### Sucessão (anfitrião)
- O 1º e o 2º sucessores (ordem de entrada e **disponibilidade**, ou seja, quem está conectado e respondendo) recebem `SuccessorState` e o
  mantêm **só em memória**: nunca disco, log ou tela; descartado ao fim do jogo ou ao deixar de ser sucessor.
- **O estado do sucessor** segue o formato de `RestoredGame` (`Match` + `roundEvents` + `botRandomCalls`) **mais** personas, tabela de
  tokens, razão das apostas (retidas, desistentes, quem recebe prêmio) e o `seq`. Sem `roundEvents` e a posição do RNG, os robôs do
  sucessor divergiriam.
- **Confirmação:** o anfitrião só dá a jogada como confirmada (e só a transmite aos demais) depois do `SuccessorAck` do 1º sucessor
  (estratégia pedida). **Tempo limite** (ex.: 3 s virtuais): sem ack, o 1º sucessor é marcado indisponível e o 2º é promovido a 1º; sem
  nenhum, o anfitrião segue sem sucessor e avisa que uma queda abrupta encerrará a mesa (N5).
- **Assumir:** o sucessor cria um `GameController` a partir do `RestoredGame`, passa a anunciar a mesa e avisa a todos (`HostChanged`); os
  convidados **reconectam pelo identificador da mesa + token** (no `P2P_STAR` a perda do anfitrião desfaz a estrela). O antigo anfitrião,
  se voltar, reingressa como jogador comum. O `MemoryTransport` simula essa topologia.
- **Canal seguro não resolve o risco** (análise do pedido está certa): o dado precisa ser decifrado no destino para ser usado; o Nearby já
  cifra o caminho. Salvaguardas acima. **Ideia futura (fora do escopo):** o sucessor recebe o estado **cifrado**, com a chave só liberada
  na passagem do cargo (ex.: partilhada entre anfitrião e os convidados restantes, com limiar) — registrado, como o risco das moedas.

### Moedas e apostas de grupo
Isoladas em um componente próprio (`GroupBetLedger`, no `:core-engine`, junto de `Coins`): apostas retidas por assento, herança ao entrar
no lugar de quem saiu, perda ao desistir (fica com o robô), repasse do prêmio ao parceiro humano ou ao dono da mesa, caso "todos os demais
desistiram". Puro e testado com propriedades (soma do que se recebe = soma das apostas), para endurecer depois. O saldo de cada celular é
local; o resultado em moedas é calculado pelo anfitrião e enviado a cada um.

### Permissões (Nearby Connections; conferir contra a documentação atual na etapa 5, via `context7`)
`minSdk` é 26, então há três faixas (lista a confirmar na documentação):
- **API 26–30:** `ACCESS_FINE_LOCATION` (e as de Wi-Fi/Bluetooth normais, sem pedir).
- **API 31–32:** `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (com `neverForLocation` quando couber).
- **API 33+:** as de Bluetooth acima mais `NEARBY_WIFI_DEVICES` (com `neverForLocation`); localização deixa de ser necessária.
Pedidas **só** ao ligar o botão de Wi-Fi ou ao criar mesa, com uma explicação antes ("para achar mesas por perto; nada sai do aparelho e não
usa internet"). Recusa = o modo fica desligado e o app segue offline normal.

### Mesa reduzida (4 → 2 assentos; decidido em 2026-10-20)
- A criação é **sempre de 4 posições**. Enquanto houver **0 ou 1 convidado conectado** (além do anfitrião), o anfitrião pode **excluir duas posições**; a mesa vira **individual** (2 assentos, um **em frente** ao outro, `GameMode.INDIVIDUAL`). Com **2 ou mais convidados conectados**, a exclusão é **bloqueada**.
- **Anfitrião + 1 convidado:** é um 1×1 entre dois humanos. Ao excluir as posições, o convidado vai **sempre para o assento do adversário** (em frente ao anfitrião), **seja qual for o assento que escolheu**. A mudança aparece **na tela do anfitrião e na do convidado** (a posição dele é atualizada e redistribuída como qualquer alteração de posição). **Anfitrião sozinho:** o adversário é um robô.
- **Restaurar as 4 posições:** o anfitrião pode voltar atrás antes do início; os assentos extras voltam vagos e o convidado fica onde está, podendo reposicionar-se (para restaurar com 2 convidados já não há o que restaurar: a exclusão estava bloqueada).
- Depois que **um 2º convidado** conecta, a exclusão deixa de ser possível; se a mesa já estava reduzida, ele **não consegue entrar** (mesa cheia, 2 de 2).
- Vale só **antes do início**. A mesa continua anunciada com os dados atualizados (a lista mostra os 2 jogadores e "Vago", sem "Dupla 1/Dupla 2").
- Efeitos a tratar: o círculo de "Escolha sua posição" com 2 assentos, 1 personagem (não 3), aposta e prêmio (§14.6 individual), sucessão (com 2 humanos o único sucessor é o outro; sem outro humano não há sucessor, ver N5), e a regra do individual para "desistência" do 1×1 (o outro humano vence; sem robô para assumir, N11).

## Decisões abertas (com a minha recomendação)
| # | Pergunta | Recomendação |
|---|---|---|
| N1 | **Onde mora a orquestração do anfitrião e os tipos compartilhados**, dado que `:net → :core-engine` apenas? (a) `:net` só com protocolo, transporte e sala, com DTOs próprios; a orquestração fica no `:app` e os testes de partida completa em `app/src/test` (que já roda na JVM); (b) extrair a camada de jogo (`GameController`, `GameConfig`, `SeatPlayer`...) para um novo módulo JVM `:game` (`→ ai, core-engine`), e `:net` + `:game` se juntam no `:app`; (c) mover `PublicEvent` para o `:core-engine`. | **(a) + (c)**: `PublicEvent` sobe para o `:core-engine` (é só dados públicos; `:ai` passa a importá-lo de lá), `:net` fica leve e a orquestração e os testes de partida completa ficam no `:app`. Evita um módulo novo grande e mexe pouco na tabela de CLAUDE.md (só ganha a linha de `:net`). |
| N2 | **Próxima partida** (`continueToNextRound` bloqueia hoje): quem avança? | **Anfitrião avança**, depois de todos os humanos conectados tocarem "Continuar" **ou** de um tempo opcional que o anfitrião pode forçar; robôs e desconectados não seguram. **Substituída pelo usuário (2026-10-21):** cada aparelho conduz as telas de resultado sozinho; o anfitrião avança quando ele mesmo avança, sem esperar ninguém (§16.11). |
| N3 | **"Dono da mesa"** para o prêmio sem humano que receba: o criador original ou o anfitrião atual após uma sucessão? | O **anfitrião atual** (quem sustenta a mesa); o cargo passa com a sucessão. |
| N4 | **Anfitrião desiste sem passar o cargo**: passa sozinho ao 1º sucessor, ou o jogo acaba para todos? | **Passa sozinho** ao 1º sucessor humano disponível (nunca a robô); se não houver humano, o jogo termina e a mesa fecha. |
| N5 | **Sem sucessor disponível** (ex.: o anfitrião está só com 1 convidado que cai, ou ninguém responde ao ack): o jogo continua sem proteção contra queda abrupta, ou pausa até haver sucessor? | **Continua**, com aviso ao anfitrião ("sem sucessor: uma queda encerra a mesa"). |
| N6 | **Personagem do robô que substitui** um humano (queda ou desistência) e quem o escolhe. | O **anfitrião** escolheu os personagens dos assentos vagos ao criar a mesa; ao substituir, usa o primeiro personagem ainda **não usado na mesa**, no nível da mesa; nada é sorteado de novo. |
| N7 | **Modo da mesa multijogador.** | **Resolvida pelo usuário:** criada sempre com 4 posições; o anfitrião pode excluir duas se houver só um jogador conectado (ver "Mesa reduzida"). |
| N10 | Mesa reduzida: "apenas um jogador conectado". | **Resolvida pelo usuário** (ver "Mesa reduzida"): 0 ou 1 convidado permite excluir; 2 ou mais bloqueia; o convidado vai ao assento do adversário. |
| N11 | **Desistência num 1×1 entre dois humanos:** o hoje (§13.1 individual) dá a vitória ao outro lado; no grupo o robô assume. Qual vale aqui? | O **§13.1 do individual**: o outro humano vence e leva a aposta (não entra robô, pois não restaria jogo). Uma queda de conexão ainda pode ser **substituída por robô** pelo anfitrião ou aguardar. |
| N12 | **Aposta de quem cai e é substituído por robô** (ação 2 de §16.8, sem desistir): perde a aposta como na desistência? | **Sim**: fica retida no assento (robô); se o jogador reingressar **antes** da substituição, nada se perde. |
| N13 | **Entrar no lugar de um robô no meio do jogo** (§16.7): quem entra debita a aposta? | **Sim**, debita a aposta da mesa (saldo ≥ aposta) e a aposta virtual do robô some; no lugar de quem saiu, herda a retida. | **Confirmada (2026-10-21).**
| N14 | **Desistência na mesa de 4 (2026-10-21):** o assento fica VAGO e o anfitrião decide na vez dele (aguardar, robô ou abrir vaga). | **Decidida pelo usuário** (§16.9). A confirmar: (a) "aguardar" depois da desistência (§16.8 desativava); (b) aposta de quem desistiu e reingressa; (c) "mais de um convidado": aplicado a toda mesa de 4 posições (o 1×1 é só a mesa reduzida); (d) assento do anfitrião que desiste na mesa de 4 (hoje: passa o cargo e o assento fica VAGO, como o de um convidado). |
| N8 | **"Continuar" e o jogo salvo local:** a sessão multijogador (mesa, assento, token, nome) convive com o slot do jogo salvo contra robôs? | **Slot separado**: um jogo salvo contra robôs e uma sessão multijogador podem coexistir; "Continuar" no início mostra o que existir, e o multijogador tenta reingressar pelo token. |
| N9 | **Perfil de pontos da mesa vs. estatísticas:** o resultado de uma mesa multijogador conta nas estatísticas e no resultado em moedas de cada celular como hoje? | **Sim**, com o modo marcado como multijogador, sem mudar as contas atuais. |

## Etapas (um commit por etapa; pare para revisão ao fim de cada uma)
1. **`definition.md` (§16 novo, §13.1, §14.6, §5.6, §14.4) e `docs/telas.md` (3.4 com o tipo Multijogador, 3.3, 3.13 e 3.14, modais, glossário). — FEITA** (itens "a confirmar" marcados em §16.7, §16.8 e §16.13): nova seção de multijogador (§14.7 ou §16), reescrita de §13.1 e §14.6 para o jogo em grupo (individual
   intacto), a exceção da informação oculta para os sucessores (regra de §5.6/§14), e as telas e modais novos (botão Wi-Fi, linha da mesa
   multijogador, criar mesa, escolha de posição, sala de espera, queda/recrutamento, vez remota). Nenhum código. *(android-ui-dev para telas,
   rules-engine-dev para o texto das regras)*
2. **Refatorar o `GameController` — FEITA** (humanos por assento, fluxo de eventos, snapshot por assento, `PacingPolicy`, `replaceWithBot`), mais
   `PublicEvent` para o `:core-engine` se N1 for (a)+(c). Testes de regressão: mesma semente, mesmo resultado; testes atuais verdes.
3. **`:net`: protocolo + sessão + `MemoryTransport`** e a orquestração do anfitrião (`app/…/multiplayer/MultiplayerHost.kt`) — FEITA (reenvio de perdidas fica na etapa 4); testes JVM: partida completa com humanos simulados e
   robôs igual à local; varredura de vazamento no JSON; jogada ilegal/fora da vez/duplicada/de assento alheio recusada; versões diferentes
   recusadas; mensagens perdidas/reordenadas. *(net-comm-dev)*
4. **Reconexão e papéis:** token, queda na vez, substituição por robô, recrutamento, entrada no meio do jogo com aprovação, desistência com
   repasse da aposta (`GroupBetLedger`), passagem de anfitrião e sucessão com ack (tudo na JVM, relógio virtual). *(net-comm-dev)*
5. **Transporte real no Android:** Nearby `P2P_STAR`, permissões nas três faixas de API, descoberta e anúncio; roteiro de teste em 2 a 4 celulares reais. *(net-comm-dev)*
6. **Telas** (conforme `docs/telas.md` atualizado), indicador de vez remoto, ViewModels para convidados nos assentos 1 a 3. *(android-ui-dev)*
7. **Loja:** `docs/loja/ficha-da-loja.md` e política de privacidade com as novas permissões ("nada sai do aparelho; sem internet").
   Revisão final do `rules-auditor` quanto a vazamento (só os sucessores recebem estado oculto; token não trafega no roster).

Ao fim de cada etapa: `.\gradlew.bat test` e `:app:assembleDebug`. Da etapa 5 em diante, o roteiro do que testar em celulares reais.

## Riscos
- **Nearby no `P2P_STAR`:** a perda do anfitrião desfaz a estrela; o plano de sucessão depende de re-anúncio e reconexão por token. Validar cedo (etapa 5)
  com 3 celulares.
- **Latência da confirmação do sucessor:** cada jogada espera o ack; mitigado pelo tempo limite e pelo tamanho pequeno da mensagem (`seq` incremental, só o que mudou).
- **Moedas adulteráveis:** aceito; `GroupBetLedger` isolado para endurecer depois.
- **Estado dos sucessores** é a única exceção de informação oculta; endurecimento futuro registrado acima.
- **Tamanho da refatoração do `GameController`:** por isso a etapa 2 vem sozinha, com regressão contra a versão atual.
