# Telas do Tranca-Bot

Especificação das telas e dos modais do app, para pedir mudanças sem ambiguidade. Foi montada em 2026-10-19 a partir do código, do `definition.md` e dos planos em `docs/`.

**Como usar**
- **Regras do jogo** ficam no [definition.md](../definition.md) (fonte da verdade). Aqui só se aponta para a seção (`§`).
- **Telas e componentes** ficam neste arquivo. Mudou uma tela? Atualize aqui **antes** do código, como se faz com as regras.
- **Valores exatos** (cores, dp, textos) não são copiados para cá: estão em `ui/theme/` e em `res/values/strings.xml`. Aqui vai o que a tela mostra e para quê.
- Planos `docs/plano-*.md` são históricos de trabalhos pontuais; este arquivo é o retrato atual.

---

## 1. Glossário de componentes

Use estes nomes nos pedidos.

| Nome | O que é | Onde |
|---|---|---|
| **Logotipo** | "TRANCA-BOT" em Cinzel dourado com brilho periódico, mais a descrição abaixo | Início |
| **Coroa** | Símbolo dourado do app (`CrownMark`); no ícone, no verso da carta, na vitória e no cabeçalho de marca | vários |
| **Cabeçalho de marca** | Coroa pequena + "TRANCA-BOT" + filete dourado, no topo de todo modal | modais |
| **Modal** | Janela sobre a tela: `GDialog` (diálogo de conteúdo) ou `GMessage` (confirmação/aviso com medalha ilustrada). Ambos levam o cabeçalho de marca | ver §4 |
| **Pílula do assento** | Cartão de cada outro jogador na mesa de jogo: medalhão com o robô, nome, mão em cartas viradas, contador e última jogada. Tocar nela abre o **modal de detalhe do jogador** | mesa de jogo |
| **Modal de detalhe do jogador** | Mostra o robô (imagem de apresentação), bordão, nome e a última jogada daquele assento | mesa de jogo |
| **Placar (pílula)** | Cápsula com os totais acumulados ("Nós 200 × 300 Eles") | cabeçalho da mesa |
| **Card de mesa** | Cartão de uma mesa aberta na lista de "Mesas disponíveis" | Mesas disponíveis |
| **Faixa de nível** | "Nível dos robôs:" + estrelas + nome do nível; à direita, o ícone de sortear (só em mesa criada pelo jogador) | Regras da mesa |
| **Bloco do robô** | Retrato de apresentação, nome, bordão, característica e descrição do estilo | Regras da mesa |
| **Bordão** | Frase decorativa em itálico dourado de cada robô | vários |
| **Medalhão** | Aro dourado com o avatar dentro: robô (em círculo) ou foto/inicial do jogador | pílula, Perfil |
| **Barra de ações** | Status da vez + botões (Baixar, Comprar, Descartar...) fixos acima da mão | mesa de jogo |
| **Mão** | As cartas do jogador, em grade, com o seletor de ordem (Valor/Naipe e "separar 3 pretos e coringas") | mesa de jogo |
| **Mesa** | O feltro verde com o monte, os mortos e o lixo | mesa de jogo |
| **Verso da carta** | Cor do baralho do jogador + coroa no centro | monte, mãos viradas |
| **Fundo dos perdedores** | Degradê vermelho escuro (embaixo) → transparente (em cima), que esmaece e reaparece em ciclo, atrás dos robôs que perderam | anúncio, fim de jogo |
| **Chip de escolha** | Cápsula selecionável (selecionada: azul royal com borda dourada) | Criar mesa, regras |

---

## 2. Mapa de navegação

```
Início ──► Mesas disponíveis ──► Regras da mesa ──► JOGO
  │             └──► Criar mesa ──► (Personalizar regra) ──► Regras da mesa (mesa nova) ──► JOGO
  ├──► Continuar ─────────────────────────────────────────────────────────────────► JOGO
  ├──► Perfil ──► Personalizar regra
  └──► Estatísticas

JOGO: mesa ─► anúncio da partida ─► pontos da partida ─► (próxima partida | anúncio do jogo ─► fim de jogo) ─► Início
```

Voltar do jogo leva ao Início (as telas de mesa saem da pilha). Rotas em `ui/AppNavHost.kt`.

---

## 3. Telas

### 3.1 Início (`HomeScreen`)
**Objetivo:** ponto de entrada; continuar o jogo salvo ou começar outro. Conteúdo **centralizado na vertical** (rola se a tela for baixa).
**De cima para baixo:**
1. **Imagem dos quatro robôs** (`home_all_bots`, WebP reduzido), bordas de baixo esmaecidas.
2. **Logotipo** e a descrição ("Um jogo de tranca robotizado pra jogar entre família").
3. **Saldo** ("Seu saldo: N moedas") e, quando disponível, o botão **Resgatar +200 moedas** (§14.6).
4. **Continuar** (só com jogo salvo): botão dourado com modo, dificuldade, partida e placar.
5. **Novo jogo**: dourado sem jogo salvo; secundário com jogo salvo.
6. **Barra fixa** embaixo: Perfil · Estatísticas; versão discreta logo abaixo.
7. Botão de **som** no canto superior direito, sempre à vista.

**Variações:** "Novo jogo" com jogo salvo abre o modal *Começar um novo jogo?* (o jogo salvo conta como derrota por desistência).
**Regras:** §14 (jogo salvo), §14.6 (moedas), §13.1 (desistência).
**Tags:** `home-bots`, `new-game`, `continue`, `home-balance`, `claim-daily`, `profile`, `stats`, `home-version`.

### 3.2 Mesas disponíveis (`TablesScreen`)
**Objetivo:** escolher uma mesa aberta pelos robôs ou criar a sua (§14.4).
**Estrutura:** título, subtítulo, saldo e a lista de **cards de mesa**; embaixo, fixos, **Voltar** e **Criar mesa**.
**Card de mesa (individual):** à esquerda o retrato de apresentação com o **bordão** abaixo; no centro "Mesa de <nome>", estrelas e característica, nome das regras, aposta, "Individual · Dificuldade · até N pontos"; à direita o ícone de **lista com lupa** e "Ver regras".
**Card de mesa (duplas):** o mesmo cabeçalho sem retrato e, abaixo, três retratos lado a lado (adv. esquerdo, parceiro, adv. direito), cada um com bordão, nome e rótulo da posição.
**Variações:** mesa acima do saldo mostra "saldo insuficiente" em vermelho (dá para ver as regras, não para entrar).
**Regras:** §14.4, §14.5, §14.6.
**Tags:** `tables-screen`, `table-<id>`, `table-<id>-stake`, `table-<id>-roster`, `tables-create`, `tables-back`.

### 3.3 Regras da mesa (`TableRulesScreen`)
**Objetivo:** ver quem joga e as regras da mesa; iniciar o jogo. Serve às mesas dos robôs e à mesa recém-criada.
**De cima para baixo:**
1. Título "Regras da mesa".
2. **Faixa de nível** centralizada ("Nível dos robôs:" ★ nome do nível). À direita, o **ícone de reciclagem** (sortear outros jogadores) — **só em mesa criada pelo jogador**.
3. **Painel dos robôs**
   - *Individual:* um bloco do robô (retrato, "Mesa de <nome>", bordão, característica, descrição) e "Modo · Dificuldade".
   - *Duplas:* **parceiro** sozinho, centralizado e ~40% maior; título **Adversários**; os dois adversários lado a lado **na posição do jogo** (esquerdo à esquerda), sem rótulos "Adv.".
4. **Painel da aposta** e saldo; aviso vermelho se não houver saldo.
5. **Resumo das regras** (§14.2), destacando o que difere da Clássica.
6. Barra fixa: **Voltar** e **Iniciar jogo** (desabilitado sem saldo).

**Regras:** §14.2, §14.4, §14.5, §14.6.
**Tags:** `table-rules-screen`, `table-rules-level`, `table-rules-reroll`, `table-rules-roster`, `table-rules-partner`, `table-rules-opponents`, `table-rules-left`, `table-rules-right`, `table-rules-portrait`, `table-rules-stake`, `rules-summary`, `table-rules-start`.

### 3.4 Criar mesa (`CreateTableScreen`)
**Objetivo:** montar uma mesa (§14.4).
**Seções:** Modo (Individual/Duplas), Dificuldade, **Aposta (moedas)** com saldo, **Regras** (Clássica, Tranca Paulista, Personalizada), **Pontuação-alvo** (campo numérico, padrão 3000, com mensagens de erro). Barra fixa: **Voltar** e **Criar mesa** (habilitado quando válido e com saldo).
**Navegação:** "Personalizada" sempre abre *Personalizar regra*; ao criar, abre *Regras da mesa* com os robôs sorteados.
**Tags:** `mode-individual`, `mode-duplas`, `rules-custom`, `create-table`, `create-back`.

### 3.5 Personalizar regra (`RulesEditorScreen`)
**Objetivo:** editar o perfil de regras personalizado do jogador (§14.2, §14.4). Hoje se chega por **Criar mesa** ("Personalizada") e pelo **Perfil** ("Minhas regras").
**Conteúdo:** dica, "Começar a partir de" (perfil de origem), grupos (qualquer / só A, K, Q, J / sem grupos), coringas por jogo, "morto vira monte" (chave), pontos da canastra (limpa/suja), bater exige canastra limpa (chave), pontos do 3 preto. Barra fixa: **Voltar** e **Salvar regras**.
**Tags:** `groups-*`, `wilds-*`, `switch-*`, `points-*`, `rules-save`, `rules-back`.

### 3.6 Perfil (`ProfileScreen`)
**Objetivo:** identidade e preferências do jogador (§14.1).
**De cima para baixo:**
1. **Prévia do avatar** grande (foto ou medalhão com inicial).
2. Botões de foto (escolher/trocar/remover da galeria, sem permissão).
3. **Nome** (até 16 caracteres; vazio = "Você").
4. **Cor do medalhão** (paleta).
5. **Cor do baralho**: três versos de carta (azul, verde, vermelho); a escolhida leva aro dourado.
6. Painel **Sons do jogo** (chave; toca um som ao ligar).
7. Painel **Velocidade das animações** (normal ou 30% mais lenta; grava na hora).
8. Botão **Minhas regras**.
9. **Salvar** (grava nome, foto, cores e baralho) e **Voltar**.

**Regras:** §14.1.
**Tags:** `profile-screen`, `profile-preview`, `profile-name`, `medal-color-<n>`, `deck-color-<azul|verde|vermelho>`, `profile-sound`, `profile-speed-*`, `profile-rules`, `profile-save`.

### 3.7 Estatísticas (`StatsScreen`)
**Objetivo:** histórico de jogos e moedas.
**Conteúdo:** painel de **moedas** (saldo, ganho/perda total); um painel por modo (individual, duplas) com anel de vitórias, **gráfico de barras** por dificuldade (jogos × vitórias) e tabela com os mesmos números; nota sobre derrotas; **Zerar estatísticas** (abre confirmação) e **Voltar**.
**Tags:** `stats-screen`, `stats-coins`, `stats-individual`, `stats-duplas`, `stats-chart-*`, `stats-reset`.

### 3.8 Mesa de jogo (`TableScreen`, dentro de `GameScreen`)
**Objetivo:** jogar. Desenhada só a partir da `PlayerView`; controles habilitados por `legalActions`.
**Cabeçalho:** "Partida N · alvo X", **placar (pílula)**, som, velocidade, **?** (legenda dos símbolos) e **bandeira** (desistir).
**Área do meio (rola):** **pílulas dos outros assentos** (em duplas: adv. esquerdo, parceiro, adv. direito; em telas estreitas, o parceiro numa linha e os adversários na de baixo) → jogos do outro lado → **mesa** (monte, mortos, lixo com ícone de ver todo o lixo) → jogos do seu lado, com 3 vermelhos.
**Fixos embaixo:** **barra de ações** e **mão**.
**Pílula do assento:** medalhão com o robô — **com as cartas na mão por padrão** e a pose de **pensador** só na vez dele —, nome sem posição (o parceiro leva a legenda "Parceiro"), mão em cartas viradas, contador e símbolos da última jogada. Tocar abre o modal de detalhe.
**Faixas e encenações:** troca de 3 vermelho de outro assento (faixa breve); cartas em voo; selo e pulso de canastra.
**Regras:** §3 a §10 (jogada), §3.5 e §6.5 (3 vermelhos), §5 (lixo), §7 (canastras).
**Tags:** `table-screen`, `table-scroll`, `score`, `seat-<n>`, `seat-hand-<n>`, `stock`, `mortos`, `discard-pile`, `show-discard-pile`, `action-bar`, `turn-status`, `hand`, `hand-card-<carta>`, `action-legend`, `action-resign`.

### 3.9 Anúncio da partida / do jogo (`AnnouncementScreen`)
**Objetivo:** dar o resultado antes dos números (§11, §13).
**Conteúdo:** painel com moldura dourada; **coroa** pulsando quando o resultado é vitória; os robôs na pose de vencedor (confete) e os perdedores na de perdedor sobre o **fundo dos perdedores**; título do resultado, legenda e botão (**Ver pontos** / **Ver resultado**); botão **Ver a mesa** (revisão) e **Desistir** no topo.
**Tags:** `announcement`, `announcement-crown`, `announcement-cast`, `announcement-title`.

### 3.10 Pontos da partida (`RoundSummaryScreen`)
**Objetivo:** detalhar a pontuação da partida (§12) por lado: cartas na mesa e na mão (3 vermelhos, canastras, batida, morto, cartas por valor), subtotais, total da partida e acumulado, com o rosto vencedor/perdedor/pensador por lado. Aviso de empate quando for o caso. Botão **Próxima partida** ou **Continuar** (fim do jogo). Sem desistir (decisão do usuário).
**Tags:** `round-summary`, `next-round`, `show-final`, `tie-notice`.

### 3.11 Fim de jogo / resultado final (`GameOverScreen`)
**Objetivo:** fechar o jogo (§13).
**De cima para baixo:** título, **faixa** Vitória/Derrota, **coroa** (só na vitória), os robôs (vencedores e perdedores), nomes de quem venceu, resultado em **moedas** (§14.6), **tabela** partida a partida com o total, e **Voltar ao início**.
**Tags:** `game-over`, `outcome-ribbon`, `game-over-crown`, `game-over-cast`, `winner-names`, `coins-result`, `game-over-table`, `back-home`.

### 3.12 Revisão da mesa final
A mesa de jogo "só para olhar", aberta a partir do anúncio; a barra de ações dá lugar a **Continuar**, que volta ao anúncio. Tag `review-continue`.

---

## 4. Modais

Todos levam o **cabeçalho de marca**.

| Modal | Tipo | Quando abre | Conteúdo |
|---|---|---|---|
| Começar um novo jogo? | mensagem (perigo) | Início, "Novo jogo" com jogo salvo | explica que o jogo salvo vira desistência |
| Voltar ao início? | mensagem (aviso) | voltar durante o jogo | confirma sair |
| Desistir do jogo? | mensagem (perigo) | bandeira / anúncio | §13.1; cancelar volta ao estado exato |
| Recusar a compra? | mensagem | monte acabou sem morto | encerra a partida sem vencedor |
| Zerar estatísticas? | mensagem | Estatísticas | confirma zerar |
| Símbolos das jogadas | diálogo | **?** na mesa | legenda de comprar, pegar, baixar, acrescentar, descartar, recusar |
| **Detalhe do jogador** | diálogo | tocar na pílula | imagem de apresentação do robô, bordão, nome e a última jogada (com cartas e 3 vermelhos baixados) |
| Lixo completo | diálogo | ícone na linha do lixo | todas as cartas do lixo |
| Onde baixar? | diálogo | "Baixar" com mais de uma possibilidade | cada opção com cartas, destino e pontos |
| Como usar o descarte? | diálogo | pegar o lixo com mais de um plano | cada plano; dica "ou selecione as cartas da mão antes de tocar no lixo" |

---

## 5. Identidade visual (resumo)

- **Paleta:** azul-marinho/royal do boné e da jaqueta do robô, azul-pele como realce, **ouro** das coroas, creme; **feltro verde só na mesa**. Fonte única: `ui/theme/Color.kt` (`GColors`).
- **Tipografia:** Cinzel nos títulos e no logotipo; Poppins no resto.
- **Símbolo:** a **coroa**.
- **Robôs:** Bolt (Jovem), Domino (Sóbrio), Turbo (Atleta), Zen (Alternativo); mesmo nome em qualquer nível; 4 poses (apresentação, vencedor, perdedor, pensador) e uma imagem "com as cartas na mão". Detalhes em `definition.md` §14.5 e `docs/personagens/`.
- **Cor do baralho** do jogador (azul, verde ou vermelho): vale em todo verso de carta do app.
- **Acessibilidade:** alvos de toque de 48 dp, textos de 12 sp no mínimo, descrições de conteúdo nos ícones, animações paradas quando o Android as desliga.

---

## 6. Capturas de tela

Tiradas em 2026-10-19 no emulador Pixel 4 (API 33, 1080×2280), versão 0.85, dados zerados (saldo inicial 1000 moedas). Ficam em [docs/telas/](telas/).

| Tela / modal | Arquivo(s) |
|---|---|
| 3.1 Início (sem jogo salvo / com jogo salvo) | `01-inicio.png`, `01b-inicio-com-jogo-salvo.png` |
| 3.2 Mesas disponíveis (individual / duplas, rolada) | `02-mesas-disponiveis.png`, `02b-mesas-disponiveis-duplas.png` |
| 3.3 Regras da mesa (mesa de duplas dos robôs; mesa criada, com o ícone de sortear) | `03-regras-da-mesa.png`, `03b-regras-da-mesa-rolado.png`, `03c-regras-da-mesa-mesa-criada.png`, `03d-regras-da-mesa-mesa-criada-rolado.png` |
| 3.4 Criar mesa | `04-criar-mesa.png`, `04b-criar-mesa-rolado.png` |
| 3.5 Personalizar regra | `05-personalizar-regra.png`, `05b-personalizar-regra-rolado.png` |
| 3.6 Perfil | `07-perfil.png`, `07b-perfil-rolado.png` |
| 3.7 Estatísticas | `08-estatisticas.png`, `08b-estatisticas-rolado.png` |
| 3.8 Mesa de jogo (início, com lixo e jogadas, com jogos baixados, carta selecionada, individual) | `09-mesa-de-jogo.png`, `09b-mesa-com-lixo-e-jogadas.png`, `09c-mesa-com-jogos-baixados.png`, `09d-barra-de-acoes-carta-selecionada.png`, `09e-mesa-de-jogo-individual.png` |
| Modal Detalhe do jogador | `10-modal-detalhe-jogador.png` |
| Modal Símbolos das jogadas | `11-modal-simbolos.png` |
| Modal Zerar estatísticas? | `12-modal-zerar-estatisticas.png` |
| Modal Desistir do jogo? | `13-modal-desistir.png` |
| Modal Voltar ao início? | `14-modal-voltar-ao-inicio.png` |
| Modal Começar um novo jogo? | `15-modal-novo-jogo.png` |
| Modal Lixo completo | `18-modal-lixo-completo.png` |
| 3.11 Fim de jogo (derrota por desistência) | `24-fim-de-jogo-derrota.png` |

**Sem captura (a fazer depois)** — dependem de situações que o emulador não força por toques:
- 3.9 Anúncio da partida / do jogo, 3.10 Pontos da partida, 3.12 Revisão da mesa final e a variação de **vitória** do Fim de jogo: exigem terminar uma partida jogando. Desistir leva direto ao Fim de jogo (derrota), sem passar pelo anúncio. Caminho sugerido: adaptar [EndScreensTest.kt](../app/src/androidTest/kotlin/com/gtranca/EndScreensTest.kt), que monta essas telas com dados fixos.
- Modais *Onde baixar?*, *Como usar o descarte?* e *Recusar a compra?*: dependem da mão sorteada (ou do monte acabar sem morto).
- Faixa de troca de 3 vermelho de outro assento e pulso/selo de canastra (animações curtas).
- Variações: mesa com "saldo insuficiente", Perfil com foto, Estatísticas com partidas jogadas.

## 7. O que ainda não está aqui

- Textos exatos de cada mensagem de erro (ver `strings.xml`).
- Conferência visual no celular das mudanças de 18–19/10 (ver o status em `docs/plano-nomes-aos-bots.md`).
