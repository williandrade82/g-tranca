# Plano: 100 pontos para a distribuição certeira

Status (14/11/2026): **Fases 1–3 (motor, bots, UI local) e Fase 4 no multijogador local implementadas**; o online (Fase 4 em `online/contracts`/`:online-client`/`game-service`) continua pendente. Fluxo de sempre: `definition.md` primeiro, depois `docs/telas-app.md`, depois testes, código, `rules-auditor`, um commit por etapa, APK com hash.

## O que é a regra, em uma frase
Antes da distribuição, quem vai distribuir (jogador 2) e quem vai distribuir o morto (jogador 3) **tentam adivinhar**, numa barra deslizante, o tanto certo de cartas (44 ou 22 para a mão, conforme o modo; 22 para o morto). **Acertar não muda nada na distribuição real** — ela continua exatamente como é hoje (11 por mão, 11 por morto, nos estilos de animação já implementados). Acertar só rende **+100 pontos** de bônus para o lado. É um minijogo de sorte/pontaria encaixado antes da distribuição, não uma regra de distribuição nova.

## Decisões do usuário (13/11/2026)
| # | Decisão |
|---|---|
| 1 | Confirmado: a seleção na barra é só um palpite. A distribuição real de cartas (11/11) não muda em nada. |
| 2 | No modo 1×1 (2 assentos), "o anterior ao anterior" cai de volta em quem abriu o jogo — **mantém assim**: quem abre a partida também é quem distribui o morto (acumula os dois papéis). |
| 3 | Fatiamento: **tudo junto** — motor, bots, UI, multijogador local e online no mesmo desenvolvimento (não faseado em offline-primeiro / online-depois). As fases abaixo são só a ordem de implementação dentro desse desenvolvimento único, não etapas de entrega separadas para o usuário validar. |
| 4 | A regra é um **parâmetro configurável** em `RuleSet`/perfis de regras (§14.2/§14.3), podendo ser desligada numa mesa. |

## Papéis e ordem (confirma a leitura do pedido)
- Jogador 1 = quem abre a partida (já sorteado hoje, §4.1).
- Jogador 2 = o anterior a ele no sentido de jogo (§4.2, anti-horário) → **distribui a mão**.
- Jogador 3 = o anterior ao jogador 2 → **distribui o morto**.
- Em duplas (4 assentos): 1, 2 e 3 são três pessoas/duplas diferentes.
- Em individual (2 assentos): jogador 3 == jogador 1 (decisão #2 acima).

## Onde a regra entra no `definition.md`
- Nova subseção **§3.6 "Distribuição certeira"**, dentro de "3. Distribuição das Cartas", citando que não altera as contagens de §3.1/§3.2 — só decide um bônus.
- Nova linha em **§12.1 Pontos na mesa**: "Distribuição certeira: +100 para o lado de quem acertou a largura certa no palpite (mão ou morto), só se o parâmetro `dealGuessBonusEnabled` estiver ligado".
- Novo parâmetro em **§14.2**: `dealGuessBonusEnabled` (bool, padrão a decidir — provavelmente `true`, já que é a regra nova sendo adicionada) e taxas de acerto dos robôs em **§14.5** (Fácil 5%, Médio 15%, Difícil 25%).
- `docs/telas-app.md`: novo modal "Cartas para a distribuição" / "Cartas para o Morto" (glossário + comportamento), a reação de vitória/"não foi dessa vez" em tela cheia, e a reação reduzida (confete/tremor) na pílula dos adversários.

## Fase 1 — Motor (`:core-engine`), TDD com `rules-engine-dev`
1. Helper de assento: falta um `previousSeat`/`seatBefore` simétrico ao `nextSeat` existente (`Seats.kt:54`) para derivar jogador 2 e jogador 3 a partir de `firstSeat`.
2. Novo parâmetro `dealGuessBonusEnabled: Boolean` em `RuleSet`.
3. Novo sub-estado de round, algo como `DealGuessState` (fases: aguardando palpite do jogador 2 → aguardando palpite do jogador 3 → resolvido), guardando: assento de cada papel, o alvo (largura certa), o que cada um escolheu (`start`, `end`) e se acertou. Vive dentro de `RoundState`, antes de a primeira vez poder começar (hoje a distribuição já "acontece" de uma vez em `Deal.kt`; esse sub-estado é só o minijogo, não re-executa `deal`).
4. Novas `Action`s (ex.: `Action.GuessDealRange(seat, start, end)`) validadas por `legalActions` como qualquer outra jogada — só o assento certo, na fase certa, pode agir; `start`/`end` dentro dos limites mínimo/máximo calculados a partir do baralho restante (não hardcoded: `baralho total − já reservado pelo jogador 2`, respeitando os limites citados no pedido).
5. Pontuação: ao resolver cada palpite (acerto ou erro), registrar o evento (para a UI encenar a reação) e, se acerto, somar +100 ao `SideScore` do lado correspondente — entra em §12.1 como mais uma linha, igual às outras.
6. Reprodutibilidade: a correção do palpite humano é determinística (compara `end - start` com o alvo); os palpites dos **robôs** usam o RNG com semente injetada do jogo (mesmo princípio já usado nesta sessão para `DealStyle`), nunca `Random` global.
7. Testes citando `§3.6`/`§12.1`: acerto em duplas (44) e individual (22), acerto do morto (22), erro, parâmetro desligado (nenhum bônus, nenhuma fase de palpite), caso 1×1 (jogador 1 == jogador 3), limites min/max do morto.

## Fase 2 — Bots (`:ai`)
1. Quando é a vez de um bot "distribuir" (mão ou morto), ele escolhe `start`/`end` via o RNG do jogo: com probabilidade da sua `Difficulty` (5/15/25%) escolhe a largura certa (qualquer posição válida dentro do range, já que só a largura importa); caso contrário, uma largura errada.
2. Delay de decisão do bot nesse momento deve usar o mesmo padrão de "pensando" já usado nas jogadas normais, para a UI ter tempo de mostrar a barra "rodando" antes do bot "tocar" duas vezes.
3. Teste de taxa de acerto por amostragem de sementes (como os testes de bot já fazem), e um teste `:sim` headless opcional para checar que a tabela de pontuação com o bônus não quebra simulações longas.

## Fase 3 — UI (`:app`), com `android-ui-dev`
1. **Modal de seleção** ("Cartas para a distribuição" / "Cartas para o Morto"): imagem `docs/img/selecao_de_cartas.png` de fundo; a imagem `docs/img/indicador_e_barras.png` precisa ser **recortada** em 3 recursos: o indicador (seta+bolinha), a barra azul (fundo) e o segmento verde (faixa selecionada) — trabalho de preparação de asset antes de codificar a tela.
   - Primeiro toque: marca Início com o estágio atual do indicador (indicador continua correndo).
   - Segundo toque: para o indicador; resolve Início/Fim conforme a regra de "se o valor atual for menor/maior que o Início" do pedido; preenche os 3 campos (Início/Fim/Total).
   - Número de estágios da barra = cartas disponíveis para aquele papel (jogador 2: baralho inteiro; jogador 3: o que sobrou, já clampado aos limites min/max vindos do motor).
   - Modal só aparece na tela de quem tem a vez de escolher; nos outros aparelhos (multijogador/online) não aparece nada até o resultado.
2. **Monte escondido** durante essa fase (antes da distribuição em si) — ajuste pontual na mesa.
3. **Reação de vitória**: imagem `docs/img/100pontos.png` deslizando de baixo para cima, confete (reaproveita o padrão de `EndAnimation.kt`), texto condicional (jogador/parceiro, mão/morto), som `SoundEffect.CANASTA`, fecha só com toque.
4. **Reação "não foi dessa vez"**: imagem `docs/img/nao_foi_dessa_vez.png` deslizando de baixo para cima, piscar âmbar na borda da tela (efeito novo, checar se já existe algum "flash de borda" reaproveitável antes de criar um), texto condicional, fecha só com toque. Sem som de erro pedido explicitamente — confirmar se quer algum.
5. **Pílula dos outros jogadores** (multijogador/online): acerto → confete reduzido na pílula (reaproveita o padrão de `CharacterPortrait.kt`) + borda piscando dourado; erro → tremor curto. Isso é visível em qualquer mesa com mais de um humano (local ou online); contra só robôs, a reação cheia só aparece pro humano quando é ele quem palpita ou o parceiro dele.
6. Depois do resultado (vitória ou não), a animação de distribuição (já existente, estilos `GROUPS_OF_THREE`/`WAVES`) começa normalmente; o morto só é distribuído depois do palpite do jogador 3 ser resolvido, como já é hoje (mortos vêm depois das mãos).

## Fase 4 — Multijogador local e online (`:net`, `:online-client`, `net-comm-dev`/`online-backend-dev`)

**Multijogador local: CONCLUÍDA (14/11/2026, `net-comm-dev`).** O online (`online/contracts`, `:online-client`,
`game-service`) **continua pendente** — fora deste pedido, fica para quando o `online-backend-dev` cuidar dele.

O plano original (itens 1–3 abaixo, histórico) previa uma mensagem de protocolo nova para o palpite. Na prática não
foi preciso: `Action.GuessDealRange` já viajava como uma `Action` normal (`ProposeAction`/`legalActions`), e o estado
do palpite (`DealGuess`) já existia em `RoundState`/`PlayerView` desde a Fase 1. O bug real era bem mais simples e
mais grave do que "falta uma reação cosmética": **`SeatView` (o formato de rede, `:net`) não tinha o campo
`dealGuess`** — `SeatView.of`/`toPlayerView` nunca o tocavam, então todo convidado recebia `view.dealGuess = null`
sempre. Isso não só impedia a reação pequena na pílula dos outros assentos (item 1 original): travava **de vez em
quando, pra sempre**, o modal "Cartas para o Morto" no convidado que caísse no papel de `mortoDealerSeat` numa
partida depois da 1ª (`GameViewModel.dealGuessPrompt` retornava `null` na fase `DEAL_GUESS_MORTO` por depender de
`view.dealGuess?.hand`), travando o jogo sem saída (nenhuma ação em `legalActions` chegava à tela).

**O que foi feito:**
1. `:net`: `SeatView.dealGuess: DealGuess? = null` (campo aditivo); `SeatView.of`/`toPlayerView` passam a repassá-lo.
   `PROTOCOL_VERSION` subiu para **20** (convenção já usada para `skillBoosters`/protocolo 19: mesmo sendo compatível
   via `ignoreUnknownKeys`+default, builds diferentes não "quase" se falam — sem o bump o convidado desatualizado
   ficaria preso no mesmo bug, só que silenciosamente). Testes: `net/src/test/kotlin/com/gtranca/net/SeatViewTest.kt`
   (ida e volta pelo `ProtocolCodec` de verdade).
2. `:app`: `GameViewModel` já tinha toda a lógica certa (`dealGuessPrompt`, `handleDealGuessTransition`,
   `reactToGuess`); só faltava o campo do item 1 chegar. Testes: `app/src/test/kotlin/com/gtranca/multiplayer/
   DealGuessGuestTest.kt` (prova de ponta a ponta: o convidado como `mortoDealerSeat` numa partida depois da 1ª vê
   o modal e consegue agir — a vista passa pelo `SeatView`/`ProtocolCodec`/`toSnapshot` de verdade, não um
   `PlayerView` fabricado direto para a tela; um 2º teste, com o mesmo cenário mas sem `dealGuess`, prova que o
   `dealGuessPrompt` dependia mesmo dele).
   - **Tentativa revertida (mesmo dia):** uma versão intermediária desta correção tentou um portão no
     `SnapshotPacer` (`guessRevealWaitMillis`/`guessRevealDone`), espelhando o que já existe para as trocas de 3
     vermelho (`presentationDone`), para a vista seguinte a um palpite resolvido só aparecer depois de a tela
     reconhecer a reação. **Foi desfeita**: o `GameViewModel` observa `TableDriver.state`, um `StateFlow`
     **conflado** — na virada de partida, com o anfitrião em `PacingPolicy.FREE` (nunca espera ninguém), a tela pode
     nunca ver a vista exata em que um palpite "acabou de resolver" (só a seguinte, já adiantada); o reconhecimento
     correspondente nunca seria enviado e o portão travaria a mesa pelos 300 s do limite de segurança **a cada
     virada de partida** — pior que o problema que resolveria. Isso é seguro por outro motivo: a reação em tela
     cheia (`DealGuessRevealOverlay`) cobre a tela inteira e consome qualquer toque, então nada do que avança por
     trás dela fica visível ou clicável até o próprio toque a fechar (§16.6, princípio de fluidez). Ver KDoc de
     `TableDriver.guessRevealDone` (`:app`).
3. `docs/telas-app.md` 3.8.2 **não precisou mudar**: já descrevia o comportamento correto (modal só para quem
   palpita; pílula para os demais, em qualquer mesa) — só não funcionava ainda no multijogador local; agora funciona
   como o texto sempre disse.
4. `definition.md` **não precisou mudar**: §16.4 já cobre o caso em termos gerais ("mesma informação que um robô
   recebe", §14.5) — não é uma lista fechada de campos que precisasse citar `dealGuess` à parte.
5. **Checado e confirmado correto** (não era um gap, hipótese descartada após investigação): a pausa do anfitrião
   para assento VAGO/desconectado (§16.8, `MultiplayerHost.evaluatePause`) é agnóstica de fase — reconhece uma
   partida pendente em `Phase.DEAL_GUESS_HAND`/`DEAL_GUESS_MORTO` do mesmo jeito que em `PLAYING`. O teste
   `MultiplayerHostTest.§16_9 desistencia deixa o assento VAGO...` passa com a regra ligada (removido o
   `NO_DEAL_GUESS` que tinha antes); precisou de um ajuste no próprio teste (`runCurrent()` entre duas chamadas de
   `driveGuests`/`driveHost` sem ponto de suspensão no meio — nuance de agendamento do `StandardTestDispatcher` ao
   dirigir só um subconjunto dos convidados, não um bug do motor nem da mesa).

## Assets já recebidos (`docs/img/`)
`selecao_de_cartas.png`, `indicador_e_barras.png` (precisa recorte, ver Fase 3.1), `100pontos.png`, `nao_foi_dessa_vez.png` — conferidos, prontos para uso; nenhuma pendência de criação de arte, só de recorte/exportação dos 3 pedaços do indicador.

## Pontos em aberto para decidir durante a implementação (não bloqueiam o plano)
- Valor padrão de `dealGuessBonusEnabled` nos perfis de regras existentes (Clássica etc.) — provavelmente `true`, a confirmar quando chegar em §14.3.
- Efeito de borda âmbar piscando: criar do zero ou já existe algo reaproveitável na mesa (alertas, avisos)? Verificar antes de codificar.
- Som (se algum) para a reação "não foi dessa vez".
- Exergias de wording exatas das strings em pt-BR (ficam no `strings.xml` na hora da Fase 3).

## Ordem de commits sugerida
1. `definition.md` (§3.6, §12.1, §14.2/§14.5) + `docs/telas-app.md`.
2. `:core-engine`: parâmetro, assento, sub-estado, ações, pontuação — com testes.
3. `:ai`: palpite dos robôs por dificuldade — com testes.
4. `:app`: modal de seleção + monte escondido.
5. `:app`: reações de vitória/erro (tela cheia) + som.
6. `:app`: reação reduzida na pílula dos adversários.
7. `:net`/`:online-client`: sincronização entre aparelhos.
8. `rules-auditor` antes do PR final.
