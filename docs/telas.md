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
| **Botão de Wi-Fi** | Botão com o símbolo de Wi-Fi, perto do título de "Mesas disponíveis"; ligado, pesquisa mesas abertas por perto (§16) e as acrescenta à lista; desligado, nada de rede é usado | Mesas disponíveis |
| **Banner "Multi player"** | Banner animado com "Multi" na 1ª linha e "player" na 2ª, no lugar da imagem do robô, no card de mesa multijogador | Mesas disponíveis |
| **Card de dupla** | Cartão "Dupla 1" / "Dupla 2" com dois nomes: o jogador já conectado ou "Vago" | card de mesa multijogador |
| **Marca "jogo em andamento"** | Etiqueta na mesa multijogador cujo jogo já começou e que aceita alguém no assento da vez | card de mesa multijogador |
| **Mesa redonda de posições** | Mesa em círculo com os 4 (ou 2) assentos, marcadores de seleção, nome sob o assento e os botões de rotacionar à esquerda e à direita | Escolha sua posição |
| **Marcador do jogador** | O assento onde o jogador está, com aro azul brilhante e o nome em campo editável; sempre na posição de baixo da tela | Escolha sua posição |
| **Pílula de humano remoto** | Pílula do assento de outro humano: medalhão com a **foto** dele (se tiver e se a opção *Ver fotos nas mesas compartilhadas* do Perfil estiver ligada) ou com a inicial, nome, mão em cartas viradas e última jogada; a vez dele é indicada como a dos robôs | mesa de jogo |

---

## 2. Mapa de navegação

```
(abertura sem nome no perfil) Boas-vindas ──► Perfil ──(com nome)──► Início
Início ──► Mesas disponíveis ──► Regras da mesa ──► JOGO
  │             └──► Criar mesa ──► (Personalizar regra) ──► Regras da mesa (mesa nova) ──► JOGO
  ├──► Continuar ─────────────────────────────────────────────────────────────────► JOGO
  ├──► Perfil ──► Personalizar regra
  └──► Estatísticas

Mesas disponíveis ──(Wi-Fi do jogo ligado no Perfil)──► Escolha sua posição ──► Aguardando início do jogo ──► JOGO (multijogador)
Mesas disponíveis ──► Criar mesa (tipo Multijogador) ──► Regras da mesa ──► Sala da mesa (anfitrião, na posição 1; publica) ──► Iniciar jogo ──► JOGO (multijogador)
Início ──► Continuar (sessão multijogador) ──► reingresso na mesa ──► JOGO (multijogador)

JOGO: mesa ─► anúncio da partida ─► pontos da partida ─► (próxima partida | anúncio do jogo ─► fim de jogo) ─► Início
```

Voltar do jogo leva ao Início (as telas de mesa saem da pilha). Rotas em `ui/AppNavHost.kt`.

---

## 3. Telas

### 3.0 Boas-vindas (`WelcomeScreen`)
**Quando:** ao abrir o app (ou voltar ao Início) **sem nome definido no perfil** (primeira abertura ou nome apagado). Fica no lugar do Início.
**Conteúdo:** logotipo, "Boas-vindas ao TRANCA-BOT!", texto convidando a escolher o nome no Perfil e um **único botão**, **Ir para o Perfil**. Ao voltar do Perfil com nome salvo, aparece o Início; sem nome, as boas-vindas de novo.
**Tags:** `welcome-screen`, `welcome-profile`.

### 3.1 Início (`HomeScreen`)
**Objetivo:** ponto de entrada; continuar o jogo salvo ou começar outro. Conteúdo **centralizado na vertical** (rola se a tela for baixa).
**De cima para baixo:**
1. **Imagem dos quatro robôs** (`home_all_bots`, WebP reduzido), bordas de baixo esmaecidas.
2. **Logotipo** e a descrição ("Um jogo de tranca robotizado pra jogar entre família").
3. **Saldo** ("Seu saldo: N moedas") e, quando disponível, o botão **Resgatar +200 moedas** (§14.6).
4. **Continuar** (só com jogo salvo): botão dourado com modo, dificuldade, partida e placar.
5. **Ver mesas - Iniciar** (abre *Mesas disponíveis*): dourado sem jogo salvo; secundário com jogo salvo.
6. **Barra fixa** embaixo: Perfil · Estatísticas; versão discreta logo abaixo.
7. Botão de **som** no canto superior direito, sempre à vista.

**Variações:** "Ver mesas - Iniciar" com jogo salvo abre o modal *Começar um novo jogo?* (o jogo salvo conta como derrota por desistência). Com uma **sessão multijogador** guardada (§16.12), **Continuar** a mostra ao lado do jogo salvo contra robôs (cada um no seu botão): tocar nela reingressa na mesa como jogador comum, mesmo para quem era anfitrião.
**Regras:** §14 (jogo salvo), §14.6 (moedas), §13.1 (desistência).
**Tags:** `home-bots`, `new-game`, `continue`, `home-balance`, `claim-daily`, `profile`, `stats`, `home-version`.

### 3.2 Mesas disponíveis (`TablesScreen`)
**Objetivo:** escolher uma mesa aberta pelos robôs ou criar a sua (§14.4).
**Estrutura:** título, subtítulo, saldo e a lista de **cards de mesa**; embaixo, fixos, **Voltar** e **Criar mesa**.
**Card de mesa (individual):** à esquerda o retrato de apresentação com o **bordão** abaixo; no centro "Mesa de <nome>", estrelas e característica, nome das regras, aposta, "Individual · Dificuldade · até N pontos"; à direita o ícone de **lista com lupa** e "Ver regras".
**Card de mesa (duplas):** o mesmo cabeçalho sem retrato e, abaixo, três retratos lado a lado (adv. esquerdo, parceiro, adv. direito), cada um com bordão, nome e rótulo da posição.
**Variações:** mesa acima do saldo mostra "saldo insuficiente" em vermelho (dá para ver as regras, não para entrar).
**Multijogador (§16):** a pesquisa por perto segue a configuração **Wi-Fi do jogo** do Perfil (3.6), guardada no aparelho e desligada por padrão. **Ligada**, esta tela já pesquisa mesas abertas nas proximidades ao abrir, sem tocar em nada, acrescentando-as à lista atual; as mesas aparecem e somem conforme são encontradas. Desligada, nada de rede é usado e a lista é a de sempre. Ao lado do título fica o **botão de Wi-Fi**, que abre refletindo essa configuração (aceso = pesquisando, apagado/riscado = desligado); tocar nele alterna e **grava a mesma configuração do Perfil** (uma só fonte). Ao ligar, passa pelo modal *Procurar mesas por perto*; recusar deixa desligado no botão e no Perfil. Se a configuração está ligada mas as permissões foram retiradas (ou a localização exigida está desligada), ao abrir esta tela aparece o modal *Procurar mesas por perto* de novo; **Agora não** desliga a configuração. Se o Nearby recusar a pesquisa, um aviso explica e a configuração é desligada. **Criar mesa** continua sendo a mesma tela (3.4), que ganha o tipo **Multijogador**.
**Card de mesa multijogador:** "<nome da mesa>" (definido pelo anfitrião), estrelas de dificuldade, nome das regras, aposta de cada um em moedas, "Ver regras" (como nas mesas de duplas) e, no lugar da imagem do robô, o **banner "Multi player"**. Abaixo, os dois **cards de dupla** ("Dupla 1" e "Dupla 2") com dois nomes cada: o jogador conectado ou "Vago". Mesa de 2 posições (§16.3) mostra os dois jogadores em vez dos cards de dupla. Com o jogo já iniciado, a mesa leva a **marca "jogo em andamento"** e só aparece na lista quando há assento aberto (§16.7, §16.8); quem tem token volta pelo "Continuar" mesmo sem a mesa na lista (§16.5). Tocar na linha abre **Escolha sua posição** (3.13).
**Card de mesa multijogador, primeira versão (etapa 6):** o anúncio da mesa (até 100 bytes, `AdvertCodec`) só traz o **nome da mesa**, o **nome do anfitrião** e a marca **"jogo em andamento"**; o card mostra isso e o banner "Multi player". Dificuldade, regras, aposta, "Ver regras" e os cards de dupla aparecem **depois de entrar**, em *Escolha sua posição* (3.13), que recebe as regras da mesa; saldo menor que a aposta é avisado lá (o jogador só pode sair). *Pendente com produto:* ampliar o anúncio (dificuldade, aposta, tipo de regra e ocupação dos assentos) para o card completo acima.
**Regras:** §14.4, §14.5, §14.6.
**Tags:** `tables-screen`, `table-<id>`, `table-<id>-stake`, `table-<id>-roster`, `tables-create`, `tables-back`, `tables-wifi`, `table-<id>-multi-banner`, `table-<id>-team-1`, `table-<id>-team-2`, `table-<id>-in-progress`.

### 3.3 Regras da mesa (`TableRulesScreen`)
**Objetivo:** ver quem joga e as regras da mesa; iniciar o jogo. Serve às mesas dos robôs e à mesa recém-criada.
**De cima para baixo:**
1. Título "Regras da mesa".
2. **Faixa de nível** centralizada ("Nível dos robôs:" ★ nome do nível). À direita, o **ícone de reciclagem** (sortear outros jogadores) — **só em mesa criada pelo jogador contra robôs** (nunca na mesa multijogador).
3. **Painel dos robôs** (não aparece na mesa multijogador: os robôs só são apresentados quando o jogo começa)
   - *Individual:* um bloco do robô (retrato, "Mesa de <nome>", bordão, característica, descrição) e "Modo · Dificuldade".
   - *Duplas:* **parceiro** sozinho, centralizado e ~40% maior; título **Adversários**; os dois adversários lado a lado **na posição do jogo** (esquerdo à esquerda), sem rótulos "Adv.".
4. **Painel da aposta** e saldo; aviso vermelho se não houver saldo.
5. **Resumo das regras** (§14.2), destacando o que difere da Clássica.
6. Barra fixa: **Voltar** e **Iniciar jogo** (desabilitado sem saldo). Em **mesa multijogador** (criada em 3.4), o botão é **Publicar mesa** e leva à *sala da mesa* (3.13, no modo do anfitrião) e o **nome da mesa** aparece no título. Os robôs **não** são mostrados antes do início (nem aqui nem na sala): o anfitrião os sorteia sem exibir e, no início do jogo, cada assento vago recebe o seu robô, o mesmo (personagem, nome e assento) em todos os aparelhos (§16.2).

**Regras:** §14.2, §14.4, §14.5, §14.6.
**Tags:** `table-rules-screen`, `table-rules-level`, `table-rules-reroll`, `table-rules-roster`, `table-rules-partner`, `table-rules-opponents`, `table-rules-left`, `table-rules-right`, `table-rules-portrait`, `table-rules-stake`, `rules-summary`, `table-rules-start`.

### 3.4 Criar mesa (`CreateTableScreen`)
**Objetivo:** montar uma mesa (§14.4).
**Seções:** **Tipo** (Contra robôs / **Multijogador**), Modo (Individual/Duplas; **oculto no multijogador**, onde a mesa é sempre de 4 posições, §16.2), **Nome da mesa** (campo de texto; **só no multijogador**), Dificuldade, **Aposta (moedas)** com saldo, **Regras** (Clássica, Tranca Paulista, Personalizada), **Pontuação-alvo** (campo numérico, padrão 3000, com mensagens de erro). Barra fixa: **Voltar** e **Criar mesa** (habilitado quando válido e com saldo).
**Navegação:** "Personalizada" sempre abre *Personalizar regra*; ao criar, abre *Regras da mesa* com os robôs sorteados. No **multijogador**, os mesmos campos (dificuldade, aposta, regras, pontuação-alvo) valem para a mesa, mais o **nome da mesa** (§16.2); ao criar, abre *Regras da mesa* (3.3) sem os robôs e dali o anfitrião segue para *Escolha sua posição* (3.13), onde a mesa é **publicada**. As permissões do Nearby são pedidas ao escolher o tipo Multijogador (modal *Procurar mesas por perto*).
**Tags:** `mode-individual`, `mode-duplas`, `rules-custom`, `create-table`, `create-back`; no multijogador: `create-type-multi`, `create-table-name`.

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
6b. Chave **Ver fotos nas mesas compartilhadas** (grava na hora; ligada por padrão): na mesa multijogador, mostra as fotos dos outros jogadores; desligada, eles aparecem com a inicial. A própria foto vai à mesa de qualquer forma (§16.2). Tag `profile-shared-photos`.
6c. Botão **Rever o tour guiado**: zera o registro dos tours; o de cada tela (3.16) volta a aparecer na próxima vez que ela abrir. Tag `profile-tour-reset`.
6a. Painel **Wi-Fi do jogo** (chave; grava na hora): liga o multijogador por perto (§16). Ao ligar, passa pelo modal *Procurar mesas por perto* (explicação e permissões); recusar deixa a chave desligada. Tag `profile-wifi`.
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
**Cabeçalho:** "Partida N · alvo X", **placar (pílula)**, o ícone do **tour guiado** (3.16; à vista, ao lado do som), o botão de **som** e o ícone de **engrenagem** (Opções), que abre um menu com: **Velocidade das animações** (normal/lenta), **Símbolos das jogadas** (legenda), **Desistir** (bandeira) e, no anfitrião multijogador, as ações do menu do anfitrião (3.14). Tags: `table-options` (engrenagem) e, no menu, `speed-toggle`, `action-legend`, `action-resign`.
**Área do meio (rola):** **pílulas dos outros assentos** (em duplas: adv. esquerdo, parceiro, adv. direito; em telas estreitas, o parceiro numa linha e os adversários na de baixo) → jogos do outro lado → **mesa** (monte, mortos, lixo com ícone de ver todo o lixo) → jogos do seu lado, com 3 vermelhos.
**Fixos embaixo:** **barra de ações** e **mão**.
**Pílula do assento:** medalhão com o robô — **com as cartas na mão por padrão** e a pose de **pensador** só na vez dele —, nome sem posição (o parceiro leva a legenda "Parceiro"), mão em cartas viradas, contador e símbolos da última jogada. Tocar abre o modal de detalhe.
**Multijogador (§16):** os outros assentos humanos usam a **pílula de humano remoto**; o **indicador de vez** (destaque e texto "Vez de <nome>") vale para humanos remotos e robôs. Cada aparelho anima na própria velocidade (§16.6). Se quem tem a vez caiu, a mesa mostra *Aguardando <nome>* (modais em 4). O jogo só entrega a vez ao jogador depois de a animação do seu aparelho terminar. A **bandeira** (desistir) leva ao modal de desistência de grupo.
**Faixas e encenações:** troca de 3 vermelho de outro assento (faixa breve); cartas em voo; selo e pulso de canastra.
**Regras:** §3 a §10 (jogada), §3.5 e §6.5 (3 vermelhos), §5 (lixo), §7 (canastras).
**Tags:** `table-screen`, `table-scroll`, `score`, `seat-<n>`, `seat-hand-<n>`, `stock`, `mortos`, `discard-pile`, `discard-empty` (lixo vazio: lugar de uma carta com ícone de lixeira), `show-discard-pile` (contador sempre visível, mostra 0 com o lixo vazio), `action-bar`, `turn-status`, `hand`, `hand-card-<carta>`, `action-legend`, `action-resign`.

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

### 3.13 Escolha sua posição (`SeatPickerScreen`)
**Objetivo:** escolher o assento na mesa (§16.2) e esperar o início. Imagem de referência: `docs/telas/escolha-posicao-multiplayer.png`.
**De cima para baixo:** botão de **voltar/sair** no canto (sempre disponível), título "Escolha sua posição" e subtítulo ("Você sempre joga na posição de baixo. Escolha onde deseja ficar na mesa."); a **mesa redonda de posições** com os assentos (feltro verde, aro de madeira); à esquerda e à direita os botões **rotacionar à esquerda** e **rotacionar à direita**; embaixo, o botão dourado **Confirmar posição**.
**Mesa redonda:** cada assento mostra uma pílula com o **nome** do jogador ou **"VAGO"** e um marcador de seleção. O **marcador do jogador** (aro azul brilhante) fica sempre na posição de baixo, com o nome em **campo editável**, iniciado com o nome do perfil. Cada assento com jogador mostra o **avatar** dele em tamanho de assento (~40 dp, acima do nome): a **foto** do perfil (§16.2; a do próprio jogador vem do perfil local; as dos outros só se *Ver fotos nas mesas compartilhadas* estiver ligada) ou o medalhão com a inicial. Ao entrar, o jogador já **ocupa o primeiro assento livre** (é o inicial); rotacionar gira as posições na tela e o marcador cai sempre num **assento vago** (nunca troca de lugar com outro jogador; sem assento vago, a rotação fica indisponível). Nome em branco vira "Jogador N".
**Anfitrião (a mesma tela, sem escolha de posição):** o anfitrião **não escolhe posição**: fica sempre na **posição 1**, no marcador de baixo, e não há rotação nem **Confirmar posição**; o nome é editável. Antes do início, o botão **Excluir duas posições** (mesa de 2) aparece enquanto há 0 ou 1 convidado conectado e fica indisponível com 2 ou mais (§16.3); com a mesa reduzida vira **Restaurar 4 posições**. A mudança é enviada a todos e o convidado, se houver, passa para o assento em frente. *Ainda não implementado:* a sessão de rede (etapa 4) não tem a exclusão de posições; o botão fica para depois.
**Regras da mesa na sala:** abaixo da mesa redonda, um painel com nome da mesa, dificuldade (estrelas), aposta e o resumo das regras (o mesmo de 3.3), para o convidado ver o que aceita ao entrar (§16.2). Saldo menor que a aposta: aviso vermelho e **Confirmar posição** desabilitado.
**Estados:** *Escolher* → ao tocar **Confirmar posição**, o botão vira **Aguardando início do jogo** (desabilitado) e a tela continua recebendo as atualizações (entradas, saídas, nomes, exclusão de posições). O **anfitrião** vê **Iniciar jogo** (quando quiser; assentos vagos ficam com robôs) no lugar de Confirmar posição. Com algum assento **VAGO**, ou com convidado que ainda não confirmou a posição, **Iniciar jogo** abre o modal *Iniciar com robôs?* antes de começar. Quem não confirmou a posição até o início é desconectado, e o anfitrião vê um **aviso** com os nomes.
**Regras:** §16.2, §16.3.
**Origem:** vem de *Regras da mesa* (anfitrião, que **publica** a mesa ao tocar em **Publicar mesa**) ou de *Mesas disponíveis* (convidado).
**Tags:** `seat-picker`, `seat-picker-table`, `seat-slot-<n>`, `seat-picker-name`, `seat-picker-rotate-left`, `seat-picker-rotate-right`, `seat-picker-confirm`, `seat-picker-waiting`, `seat-picker-start`, `seat-picker-exclude`, `seat-picker-leave`.

### 3.14 Mesa multijogador durante o jogo
**Anfitrião** (cabeçalho da mesa de jogo, 3.8; dentro do menu de **engrenagem**): ícone de **mesa** com o menu do anfitrião: *Passar o cargo* e *Liberar um assento de robô para entrada* (§16.7).
**Telas de resultado (3.9 a 3.11) em grupo:** cada aparelho as percorre no seu tempo, como no jogo contra robôs (§16.11). O **Continuar** do anfitrião distribui a próxima partida; o do convidado só avança as telas dele (a próxima partida, já recebida, aparece depois). Ninguém espera ninguém.
**Desistir (convidado):** mesma bandeira e confirmação do jogo contra robôs (§13.1); a desistência vale a qualquer momento e o jogador vai para o fim de jogo como derrota por desistência. Na mesa de 4 posições, a **pílula do assento** dele passa a mostrar **"VAGO"** para todos e o jogo segue até a vez desse assento (§16.9); no 1×1 o outro vence na hora.
**Fim de jogo (3.11):** mostra o resultado em moedas de cada celular (prêmio dividido por assento vencedor; a parte de robô ou de assento VAGO se perde, §16.13); a derrota por desistência é a de quem desistiu. Quem estava fora no fim do jogo e volta pelo token vai direto a esta tela, com o resultado e o prêmio (§16.11). **Anfitrião:** "Voltar ao início" não derruba a mesa: enquanto alguém ainda não recebeu o resultado, a mesa segue aberta em segundo plano e fecha sozinha ao fim da entrega (§16.11, §16.15).
**Voltar à mesa (convidado que desistiu):** no fim de jogo por desistência, o botão **Voltar à mesa** reingressa pelo token enquanto o assento não foi substituído nem ocupado (§16.9); se não der, a mensagem do anfitrião aparece.
**Sair** durante o jogo é a mesma desistência (mesma confirmação).
**Tags:** `host-menu`, `host-pass-role`, `host-open-seat`.

### 3.15 Pausa da mesa multijogador (queda, assento VAGO e recrutamento)
**Objetivo:** mostrar a pausa quando chega a vez de um assento sem jogador (queda de conexão, §16.8, ou desistência, assento VAGO, §16.9) e as ações do anfitrião. Ainda **não implementada** (etapa 6); o estado vem da sessão (etapa 4).
**Pílula do assento:** "VAGO" (desistência ou vaga aberta) ou o nome com o indicador **desconectado** (queda, ainda reservado ao jogador).
**Anfitrião (modal de pausa):** "<nome> não está na mesa" e três botões: **Aguardar** (o modal fica em "Aguardando <nome>" e fecha quando ele reingressa), **Colocar um robô** (efeito imediato) e **Abrir vaga** (o modal passa a "Aguardando recrutamento", ainda com **Colocar um robô**). Um **pedido de entrada** ("<nome> quer entrar no lugar de <assento>") com **Aceitar** e **Recusar**; ele pode chegar ainda antes de o anfitrião escolher uma das três ações e aparece junto delas (Aceitar = abrir a vaga e aprovar, §16.7). Se o dono volta antes, o pedido some e o candidato vê "A vaga foi preenchida.".
**Convidados:** faixa "Jogo pausado: aguardando <nome>" / "aguardando o anfitrião" / "aguardando recrutamento", atualizada a cada ação do anfitrião; some quando o jogo segue.
**Avisos:** "<nome> saiu da mesa" (queda), "<nome> desistiu", "<nome> foi substituído por <personagem>" (sempre um personagem que não estava na mesa), "<nome> entrou na mesa", "<nome> é o novo anfitrião" (§16.10; no anfitrião provisório, a mesa só segue quando outro jogador reingressa nele), "<nome> voltou a ser convidado" (o provisório que achou o anfitrião original) e, só ao anfitrião, "Sem sucessor: uma queda sua encerra a mesa".
**Regras:** §16.7, §16.8, §16.9, §16.10.
**Tags:** `mp-pause`, `mp-pause-wait`, `mp-pause-robot`, `mp-pause-recruit`, `mp-join-request`, `mp-join-accept`, `mp-join-refuse`, `mp-paused-banner`.

---

### 3.16 Tour guiado
**Objetivo:** apresentar as telas na primeira vez do jogador. Cada tela com tour mostra o seu **uma única vez** (registro por tela, guardado no aparelho); o ícone do tour (letra "i" num círculo, tag `tour-button`) no alto da tela o mostra de novo a qualquer momento, e **Rever o tour guiado** no Perfil (3.6) zera o registro de todas.
**Formato:** a tela escurece e um **recorte** destaca o elemento real do passo; um **balão** com texto curto (até ~2 linhas), o **indicador de passo** ("2/6") e os botões **Voltar**, **Próximo** (no último, **Concluir**) e **Pular**. O tour **nunca joga nem executa nada**: tocar fora do balão não faz nada; só os botões do balão funcionam. O leitor de tela lê o texto do passo. Tags: `tour-overlay`, `tour-text`, `tour-step`, `tour-next`, `tour-back`, `tour-skip`.
**Mesas disponíveis (3.2)** — aparece ao abrir a tela pela primeira vez; ícone ao lado do título (junto do Wi-Fi):
1. Saldo: "Seu saldo de moedas. Cada mesa tem uma aposta; vencendo, você ganha as moedas da mesa."
2. Primeira mesa: "Cada mesa mostra os robôs, o nível (estrelas), as regras, a aposta e a pontuação-alvo. Toque nela para ver as regras e começar."
3. Botão de Wi-Fi: "Wi-Fi do jogo: ligado, aparecem também as mesas de outros celulares por perto para jogar junto."
4. Criar mesa: "Crie a sua mesa: modo, nível dos robôs, regras, aposta e pontuação-alvo."
5. Ícone do tour: "Toque aqui para rever este tour quando quiser."
**Mesa de jogo (3.8)** — aparece **só na primeira vez em que é a vez do jogador**, depois das animações iniciais (distribuição e trocas de 3 vermelho) e com a vez já liberada; vale contra robôs e no multijogador:
1. Mão: "Sua mão. Toque nas cartas para selecionar; as destacadas podem ser jogadas agora."
2. Monte: "O monte: no começo da vez, compre uma carta daqui."
3. Lixo: "O lixo fica todo à vista; o número mostra quantas cartas há. Toque nele para pegá-lo quando valer."
4. Mortos: "Os mortos: quem acabar primeiro com as cartas da mão pega um."
5. Outros jogadores: "Os outros jogadores: cartas na mão e a última jogada. Toque numa pílula para ver o detalhe."
6. Placar: "O placar: os pontos de cada lado no jogo."
7. Barra de ações: "Aqui ficam as ações que valem agora: comprar, baixar, acrescentar e descartar."
8. Som e Opções: "Som e Opções (engrenagem): velocidade, símbolos das jogadas e desistir."
9. Ícone do tour: "Toque aqui para rever este tour quando quiser."
**Multijogador:** o tour é só da tela deste aparelho e não pausa o jogo; a vez continua aberta enquanto ele está na tela (não há limite de tempo para jogar, §16).

## 4. Modais

Todos levam o **cabeçalho de marca**.

| Modal | Tipo | Quando abre | Conteúdo |
|---|---|---|---|
| Começar um novo jogo? | mensagem (perigo) | Início, "Novo jogo" com jogo salvo | explica que o jogo salvo vira desistência |
| Voltar ao início? | mensagem (aviso) | voltar durante o jogo | confirma sair |
| Desistir do jogo? | mensagem (perigo) | bandeira (menu de engrenagem) / anúncio | §13.1; mostra as **apostas em jogo** (aposta de cada jogador, total em jogo, quanto o jogador perde e que deixa de concorrer ao prêmio) e os **pontos** da mesa (placar de cada lado, com os nomes de quem está nele); cancelar volta ao estado exato |
| **Iniciar com robôs?** | mensagem (aviso) | anfitrião, **Iniciar jogo** com assento VAGO ou convidado sem confirmar (3.13) | lista os assentos vagos que ficam com robôs e, se houver, quem ainda não confirmou a posição (será desconectado e o assento fica com robô, §16.2); **Iniciar jogo** / **Cancelar** |
| Recusar a compra? | mensagem | monte acabou sem morto | encerra a partida sem batida |
| Zerar estatísticas? | mensagem | Estatísticas | confirma zerar |
| Símbolos das jogadas | diálogo | **?** na mesa | legenda de comprar, pegar, baixar, acrescentar, descartar, recusar |
| **Detalhe do jogador** | diálogo | tocar na pílula | imagem de apresentação do robô, bordão, nome e a última jogada (com cartas e 3 vermelhos baixados) |
| Lixo completo | diálogo | ícone na linha do lixo | todas as cartas do lixo |
| **Procurar mesas por perto** | mensagem | ligar o Wi-Fi do jogo no Perfil ou no botão de Wi-Fi de Mesas disponíveis, abrir Mesas disponíveis com ele ligado e sem permissão, ou criar mesa multijogador | explica, em linguagem simples, as permissões pedidas (Bluetooth, dispositivos próximos ou localização, conforme o Android) e que **nada sai do aparelho e não usa internet**; **Permitir** / **Agora não** (o modo fica desligado) |
| **Versão do jogo diferente** | mensagem (aviso) | entrar em mesa de outra versão | "Atualize o app nos dois celulares"; recusa a entrada |
| **Pedido de entrada** | mensagem | anfitrião, quando alguém pede para entrar no meio do jogo (§16.7) | nome de quem pede e o assento (robô ou o da vez); **Aceitar** / **Recusar** |
| **Jogo pausado** | mensagem | convidados, quando o jogo pausa por queda ou recrutamento (§16.8) | quem caiu e o que o anfitrião está fazendo; fecha sozinho quando o jogo segue |
| **Aguardando <nome>** | diálogo (anfitrião) | chega a vez de quem caiu | três ações: **Aguardar o reingresso**, **Substituir por um robô** e **Recrutar outro jogador** (desativa o que não vale; com o recrutamento ativo vira "Aguardando recrutamento", ainda com **Substituir por robô**) |
| **Passar o cargo** | diálogo (anfitrião) | menu do anfitrião ou ao desistir | lista os humanos disponíveis pela ordem de entrada (nunca robôs); **Passar** / **Cancelar** |
| **Você agora é o anfitrião** | mensagem | sucessor, ao assumir (§16.10) | avisa que a mesa passou para ele |
| **Aguardando os outros jogadores** | mensagem (anfitrião provisório) | sucessor que assumiu e ainda não foi confirmado (§16.10) | explica que a mesa espera alguém reingressar; depois de 30 s (§16.15) mostra o botão **Assumir mesmo assim**, que confirma o cargo e retoma o jogo (*provisório, revisar com produto*) |
| **Desistir da mesa?** | mensagem (perigo) | bandeira (menu de engrenagem) ou Voltar do Android, em mesa multijogador | explica que um robô assume o lugar, que a aposta se perde e que o jogo segue para os demais; no 1×1 entre humanos, que o outro vence; mostra as mesmas **apostas em jogo** e **pontos** de *Desistir do jogo?* (só o que este aparelho já sabe) |
| **Mesa encerrada** | mensagem | sem humano para assumir ou o anfitrião fechou (quem volta depois que a entrega do resultado acabou, §16.11, também vê esta mensagem; quem está nas telas de resultado não é interrompido) | volta ao início |
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
| 3.13 Escolha sua posição (**referência de design**, ainda não implementada) | `escolha-posicao-multiplayer.png` |

**Sem captura (a fazer depois)** — dependem de situações que o emulador não força por toques:
- 3.9 Anúncio da partida / do jogo, 3.10 Pontos da partida, 3.12 Revisão da mesa final e a variação de **vitória** do Fim de jogo: exigem terminar uma partida jogando. Desistir leva direto ao Fim de jogo (derrota), sem passar pelo anúncio. Caminho sugerido: adaptar [EndScreensTest.kt](../app/src/androidTest/kotlin/com/gtranca/EndScreensTest.kt), que monta essas telas com dados fixos.
- Modais *Onde baixar?*, *Como usar o descarte?* e *Recusar a compra?*: dependem da mão sorteada (ou do monte acabar sem morto).
- Faixa de troca de 3 vermelho de outro assento e pulso/selo de canastra (animações curtas).
- Variações: mesa com "saldo insuficiente", Perfil com foto, Estatísticas com partidas jogadas.

## 7. O que ainda não está aqui

- Textos exatos de cada mensagem de erro (ver `strings.xml`).
- Telas do multijogador: primeira versão na etapa 6 do `docs/plano-multijogador-local.md` (capturas depois do teste nos celulares). Fora dela: card completo da mesa (anúncio maior), **Excluir duas posições** (§16.3) e **Continuar** da sessão multijogador (§16.12, falta o espaço da sessão no `:data`).
- Conferência visual no celular das mudanças de 18–19/10 (ver o status em `docs/plano-nomes-aos-bots.md`).
