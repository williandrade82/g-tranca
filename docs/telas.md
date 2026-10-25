# Telas do Tranca-Bot

Especificação das telas e dos modais do app, para pedir mudanças sem ambiguidade. Foi montada em 2026-10-19 a partir do código, do `definition.md` e dos planos em `docs/`.

**Como usar**
- **Regras do jogo** ficam no [definition.md](../definition.md) (fonte da verdade). Aqui só se aponta para a seção (`§`).
- **Telas e componentes** ficam neste arquivo. Mudou uma tela? Atualize aqui **antes** do código, como se faz com as regras.
- **Valores exatos** (cores, dp, textos) não são copiados para cá: estão em `ui/theme/` e em `res/values/strings.xml`. Aqui vai o que a tela mostra e para quê.
- Planos `docs/plano-*.md` são históricos de trabalhos pontuais; este arquivo é o retrato atual.

---

## 0. Saldo no topo (todas as telas)
Em todas as telas, um indicador pequeno no **centro do alto** mostra o **saco de moedas** e o **saldo** do jogador (tag `coin-balance`; leitor de tela: "Seu saldo: N moedas"), atualizado na hora (aposta debitada, prêmio recebido, ganho diário). Fica numa faixa própria acima do conteúdo, para não sobrepor títulos e botões; na **mesa de jogo** ele não ganha faixa: vira um ícone da linha dos ícones do cabeçalho (3.8), no tamanho da engrenagem, com o número embaixo. A linha "Seu saldo: N moedas" do Início (junto do resgate diário) **continua**; Mesas disponíveis não tem linha de saldo nem saldo junto da aposta (só o do topo).

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
| **Pílula de escolha dos participantes** | Primeiro elemento de "Mesas disponíveis": linhas com medalhão (foto ou inicial; robô em círculo) e nome — o jogador (sempre primeiro e marcado), os jogadores próximos (ou "Recrutando") e os 4 robôs; até 3 marcados além do jogador; até 4 linhas, rola se houver mais | Mesas disponíveis |
| **Card de mesa** | Cartão de uma mesa de outro anfitrião em formação (ou com assento aberto) | Mesas disponíveis |
| **Bordão** | Frase decorativa em itálico dourado de cada robô | vários |
| **Medalhão** | Aro dourado com o avatar dentro: robô (em círculo) ou foto/inicial do jogador | pílula, Perfil |
| **Barra de ações** | Status da vez + botões (Baixar, Comprar, Descartar...) fixos acima da mão | mesa de jogo |
| **Mão** | As cartas do jogador, em grade, com o seletor de ordem (Valor/Naipe e "separar 3 pretos e coringas") | mesa de jogo |
| **Mesa** | O feltro verde com o monte, os mortos e o lixo | mesa de jogo |
| **Verso da carta** | Cor do baralho do jogador + coroa no centro | monte, mãos viradas |
| **Fundo dos perdedores** | Degradê vermelho escuro (embaixo) → transparente (em cima), que esmaece e reaparece em ciclo, atrás dos robôs que perderam | fim de jogo |
| **Chip de escolha** | Cápsula selecionável (selecionada: azul royal com borda dourada) | Mesas disponíveis, regras |
| **Botão de Wi-Fi** | Botão com o símbolo de Wi-Fi (a mesma configuração **Wi-Fi do jogo** do Perfil); ligado, anuncia a presença do jogador e procura jogadores e mesas por perto (§16); desligado, nada de rede é usado | Mesas disponíveis, mesa de jogo |
| **Banner "Multi player"** | Banner animado com "Multi" na 1ª linha e "player" na 2ª, no card de mesa de outro anfitrião | Mesas disponíveis |
| **Marca "jogo em andamento"** | Etiqueta na mesa multijogador cujo jogo já começou e que aceita alguém no assento da vez | card de mesa multijogador |
| **Mesa redonda de posições** | Mesa em círculo com os 4 (ou 2) assentos, todos ocupados (humanos ou robôs), a marca de **confirmado** de cada um, nome sob o assento e os botões de rotacionar à esquerda e à direita | Escolha sua posição |
| **Marcador do jogador** | O assento onde o jogador está, com aro azul brilhante e o nome em campo editável; sempre na posição de baixo da tela | Escolha sua posição |
| **Pílula de humano remoto** | Pílula do assento de outro humano: medalhão com a **foto** dele (se tiver e se a opção *Ver fotos nas mesas compartilhadas* do Perfil estiver ligada) ou com a inicial, nome, mão em cartas viradas e última jogada; a vez dele é indicada como a dos robôs | mesa de jogo |

---

## 2. Mapa de navegação

```
(abertura sem nome no perfil) Boas-vindas ──► Perfil ──(com nome)──► Início
Início ──► Mesas disponíveis (montagem; ⇄ Personalizar regra) ──Escolher posições──► Escolha sua posição ──(todos confirmados + 2 s)──► JOGO
  ├──► Continuar ─────────────────────────────────────────────────────────────────► JOGO
  ├──► Perfil ──► Personalizar regra
  └──► Estatísticas

(qualquer tela, Wi-Fi do jogo ligado) modal Convite ──(aceito ou 10 s)──► Escolha sua posição ──► JOGO (multijogador)
Mesas disponíveis ──card de outro anfitrião──► pedido de entrada ──(aceito)──► Escolha sua posição ──► JOGO (multijogador)
Início ──► Continuar (sessão multijogador) ──► reingresso na mesa ──► JOGO (multijogador)

JOGO: mesa ─(fim da partida: banner e animação de 2 s)─► mesa final (banner minimizado + Seguir) ─► pontos da partida ─► (próxima partida | fim de jogo) ─► Início
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
**Objetivo:** montar a mesa (§14.4) — escolher quem joga e as regras — ou entrar na mesa de outro anfitrião por perto (§16.2). Absorve a antiga *Criar mesa*; *Regras da mesa* e *Criar mesa* deixaram de existir.
**De cima para baixo (rola):**
1. Título **"Iniciar novo jogo"** (a tela continua chamada *Mesas disponíveis* nestes documentos), o ícone do tour e o **botão de Wi-Fi** (configuração **Wi-Fi do jogo**, a mesma do Perfil: tocar alterna e grava; ao ligar, passa pelo modal *Procurar mesas por perto*; recusar deixa desligado). Sem linha de saldo (só o do topo, seção 0).
2. **Pílula de escolha dos participantes:** uma **grade de avatares**, até **6 por linha** (o jogador conta), um ao lado do outro; cada avatar é a imagem **em círculo** (foto do perfil; inicial só para quem não tem foto ou enquanto a foto do jogador próximo não chegou; robô no círculo) com o **nome abaixo** (uma linha, "…" se não couber). **Marcado:** aro dourado grosso e um **✓ dourado** no canto; **desmarcado:** aro fino e a imagem um pouco apagada. A ordem:
   - **o jogador**, sempre o primeiro e sempre com aparência de **marcado** (não desmarca);
   - com o Wi-Fi ligado, cada **jogador próximo** encontrado, com a **foto** do perfil (pedida uma vez ao achá-lo; inicial se não tiver foto ou com "Ver fotos nas mesas compartilhadas" desligada). A lista acompanha a busca: quem desliga o Wi-Fi do jogo some na hora (aviso de "saindo"), quem some sem avisar sai em até cerca de 20 s (§16.15) e quem continua não pisca; sem nenhum, o avatar **"Recrutando"** (círculo girando, não selecionável), logo depois do jogador. Com o Wi-Fi desligado, essa parte não aparece;
   - os **4 robôs** (Bolt, Domino, Turbo, Zen).
   Tocar numa linha marca ou desmarca; no máximo **3** marcados além do jogador (o 4º toque não marca). Marcar um jogador próximo **envia o convite na hora** (§16.2); enquanto ele não responde, o avatar mostra "aguardando"; recusou, ele volta desmarcado. Desmarcar cancela o convite (ou tira da mesa quem já aceitou). Jogador próximo recrutado por **outro** anfitrião aparece como **"Recrutado"** e não pode ser marcado. A pílula mostra até **4 linhas** de avatares e rola a partir da 5ª.
3. **Mesas de outros anfitriões** (só com o Wi-Fi ligado): card com o **banner "Multi player"**, "Mesa de <anfitrião>" e a marca "jogo em andamento" quando for o caso. Aparecem enquanto a mesa está na escolha de posições com algum robô (ou com assento aberto no meio do jogo, §16.7). Tocar envia o **pedido de entrada**; aceito pelo anfitrião, abre *Escolha sua posição* (3.13). As regras, a aposta e o nível aparecem lá; saldo menor que a aposta é avisado lá (só pode sair).
4. **Opções da mesa** (as da antiga *Criar mesa*, sempre preenchidas com a última configuração gravada):
   - **Modo** (Individual / Duplas): com **3 ou 4** participantes marcados (contando o jogador), fica em **Duplas** e Individual desabilitado; com **1 ou 2**, os dois habilitados.
   - **Dificuldade dos Robôs**.
   - **Aposta (moedas)**, sem o saldo ao lado; apostas acima do saldo desabilitadas.
   - **Regras** (Clássica, Tranca Paulista, Personalizada — "Personalizada" sempre abre *Personalizar regra*).
   - **Pontuação-alvo** (campo numérico, padrão 3000, com mensagens de erro).
5. Barra fixa: **Voltar** e **Escolher posições** (habilitado quando válido e com saldo).

**Escolher posições:** grava a configuração e abre *Escolha sua posição* (3.13) como anfitrião. Os jogadores próximos que **aceitaram** vão direto à escolha de posições; quem ainda não respondeu tem o convite cancelado e vira robô sorteado, como as posições que sobram.
**Recrutado (aceitou o convite de outro anfitrião):** a tela mostra a pílula e as opções **do anfitrião**, sincronizadas ao vivo e **só para leitura**; "Escolher posições" vira **"Aguardando..."** (desabilitado) e "Voltar" vira **"Sair da mesa"** (volta à própria configuração e fica disponível). Avisos: "<anfitrião> tirou você da mesa", "Saldo insuficiente" (a aposta passou do saldo; sai sozinho) e *Mesa encerrada* (o anfitrião saiu ou desligou o Wi-Fi). Quem estava em *Personalizar regra* ao aceitar volta à tela anterior sem salvar.
**Lembra a última mesa:** grava no aparelho modo, nível dos robôs, regras, aposta, pontuação-alvo e os robôs marcados (jogadores próximos não são lembrados). Ao carregar, o que ficou inválido é ajustado sem aviso: aposta acima do saldo vira a maior aposta que o saldo cobre; "Personalizada" sem regras salvas vira Clássica; pontuação-alvo inválida volta ao padrão; Individual com mais de 2 marcados vira Duplas.
**Wi-Fi ligado sem permissão:** ao abrir, aparece o modal *Procurar mesas por perto*; **Agora não** desliga a configuração. Se o Nearby recusar a busca, um aviso explica e a configuração é desligada.
**Regras:** §14.4, §14.5, §14.6, §16.2.
**Tags:** `tables-screen`, `tables-wifi`, `participants`, `participant-self`, `participant-near-<id>`, `participant-recruiting`, `participant-bot-<perfil>`, `table-<id>`, `table-<id>-multi-banner`, `table-<id>-in-progress`, `mode-individual`, `mode-duplas`, `rules-custom`, `choose-seats`, `tables-back`.

### 3.5 Personalizar regra (`RulesEditorScreen`)
**Objetivo:** editar o perfil de regras personalizado do jogador (§14.2, §14.4). Hoje se chega por **Mesas disponíveis** ("Personalizada") e pelo **Perfil** ("Minhas regras").
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
6c. Chave **Tour guiado** (mesmo estilo das chaves de Wi-Fi e fotos; grava na hora; ligada por padrão): ligada, cada tela (3.16) mostra o seu tour sozinha na primeira vez; desligada, o tour nunca aparece sozinho (o ícone "i" das telas continua funcionando). **Ligar** a chave zera o registro dos tours vistos, para eles aparecerem de novo. Tag `profile-tour`.
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
**Cabeçalho:** "Partida N · alvo X", **placar (pílula)** — tocar nesse canto (texto e placar) abre o modal **Informações do jogo** (4) —, o ícone do **tour guiado** (3.16; à vista, ao lado do som), o botão de **som** e o ícone de **engrenagem** (Opções), que abre um menu com: **Velocidade das animações** (normal/lenta), **Símbolos das jogadas** (legenda), **Desistir** (bandeira) e, com o Wi-Fi do jogo ligado, a seção **Multijogador** (3.14). **Linha dos ícones** (em cima): "Partida N · alvo X" à esquerda e, à direita, todos no tamanho da engrenagem (48 dp, imagem de 24 dp): **total em jogo** (maço de notas, com a soma das apostas de todos os assentos embaixo, §14.6/§16.13; só com aposta; tag `table-pot`), **saldo** (saco de moedas com o número embaixo), **Wi-Fi** (3.14, tag `table-wifi`), tour, som e engrenagem. O **placar** fica na linha de baixo; tocar nele ou em "Partida N · alvo X" abre as Informações do jogo. Tags: `table-options` (engrenagem) e, no menu, `speed-toggle`, `action-legend`, `action-resign`.
**Área do meio (rola):** **pílulas dos outros assentos** (em duplas: adv. esquerdo, parceiro, adv. direito; em telas estreitas, o parceiro numa linha e os adversários na de baixo) → jogos do outro lado → **mesa** (monte, mortos, lixo com ícone de ver todo o lixo) → jogos do seu lado, com 3 vermelhos.
**Fixos embaixo:** **barra de ações** e **mão**.
**Minha última jogada:** na linha "Sua mão (N)", ao lado do nome, um botão (tag `own-last-play`, 48 dp) abre o **modal de detalhe** com a **sua** última vez, no mesmo formato do dos outros jogadores (sem jogada ainda na partida: "Você ainda não jogou nesta partida."). Vale contra robôs e no multijogador.
**Ordem do detalhe da vez (pílula e modal):** a vez aparece na **ordem real, a mesma das animações**: troca de 3 vermelho do início da vez, compra ou lixo (ao pegar o lixo, primeiro o jogo com o topo e as cartas da mão, depois o resto que foi à mão), troca do 3 vermelho que veio na compra, baixas e acréscimos na ordem em que foram feitos, e o descarte por último.
**Sinal da conexão (multijogador, só na tela dos convidados):** a pílula do **anfitrião** mostra um ícone de sinal com **1 a 3 barras** (tag `host-signal`): a qualidade da conexão deste aparelho com o anfitrião (§16.15). No anfitrião não aparece.
**Esconder cartas (só na mesa multijogador):** na linha "Sua mão (N)", à esquerda do botão de ordenação, o botão de **olho** (tag `hand-hide`; olho riscado quando escondida). Ligado, as cartas da mão aparecem **viradas** (verso na cor do baralho) e não podem ser selecionadas; a contagem continua. O estado é só deste aparelho (não vai pela rede) e vale até o fim do jogo; **não se desliga sozinho** na vez do jogador: Comprar e Pegar o lixo continuam funcionando, e tocar numa carta virada mostra o aviso "Mostre suas cartas para jogar". Contra robôs o botão não aparece.
**Som da vez:** quando a vez passa a ser do jogador deste aparelho (no momento em que ela é liberada, depois das animações), toca um som curto e suave (dois tons leves). Respeita o botão de som; não toca na revisão da mesa final nem enquanto a vez continua com o mesmo jogador.
**Espaço vertical:** a mesa prioriza o feltro: cabeçalho, pílulas, margens do feltro, títulos das seções de jogos, linha "Sua mão" e barra de ações são compactos, e as cartas dos jogos na mesa e do lixo são um pouco maiores (34 × 50 dp). **Telas pequenas e fonte grande:** as pílulas e o feltro rolam juntos; o feltro ocupa pelo menos o espaço livre e **cresce com o conteúdo** (nunca corta jogos nem o lixo: o que não cabe fica abaixo, na rolagem). Na barra de ações, o texto da vez fica numa linha (a fonte diminui até caber) e os botões dividem a largura numa linha só, com alvo de 48 dp; **Limpar** vira um ícone "✕" (descrição "Limpar"). Na pílula do parceiro, o rótulo "Parceiro" diminui até caber em vez de ser cortado. Títulos de destaque (logotipo, títulos de tela e de resultado) diminuem a fonte até caber, sem quebrar palavra.
**Pílula do assento:** medalhão com o robô — **com as cartas na mão por padrão** e a pose de **pensador** só na vez dele —, nome sem posição (o parceiro leva a legenda "Parceiro"), mão em cartas viradas, contador e símbolos da última jogada. Tocar abre o modal de detalhe.
**Multijogador (§16):** os outros assentos humanos usam a **pílula de humano remoto**; o **indicador de vez** (destaque e texto "Vez de <nome>") vale para humanos remotos e robôs. Cada aparelho anima na própria velocidade (§16.6). Se quem tem a vez caiu, a mesa mostra *Aguardando <nome>* (modais em 4). O jogo só entrega a vez ao jogador depois de a animação do seu aparelho terminar. A **bandeira** (desistir) leva ao modal de desistência de grupo.
**Faixas e encenações:** troca de 3 vermelho de outro assento (faixa breve); cartas em voo; selo e pulso de canastra.
**Regras:** §3 a §10 (jogada), §3.5 e §6.5 (3 vermelhos), §5 (lixo), §7 (canastras).
**Tags:** `table-screen`, `table-scroll`, `score`, `seat-<n>`, `seat-hand-<n>`, `stock`, `mortos`, `discard-pile`, `discard-empty` (lixo vazio: lugar de uma carta com ícone de lixeira), `show-discard-pile` (contador sempre visível, mostra 0 com o lixo vazio), `action-bar`, `turn-status`, `hand`, `hand-card-<carta>`, `action-legend`, `action-resign`.

### 3.9 Fim da partida sobre a mesa (animação e banner)
**Objetivo:** marcar o fim da partida (§11) sem sair da mesa. Substitui o antigo anúncio da partida/do jogo (`AnnouncementScreen`, removido).
**Conteúdo:** camada transparente sobre a mesa, que fica ao fundo um pouco escurecida por **2 s** (um toque encerra antes); nada cobre a tela de forma opaca. Sempre aparece, grande e centralizado, o **banner de fim de partida**, uma **arte pronta** (`banner_bateu`, `banner_perdeu`, `banner_ninguem_bateu`, em `res/drawable-nodpi/`):
- **Seu lado bateu** (§11.1): arte **"Bateu!"**; em duplas, uma legenda pequena embaixo diz **"Você bateu!"** ou **"Sua dupla bateu!"**; confete caindo pela tela toda; som de vitória.
- **O adversário bateu:** arte **"Perdeu!"** (em duplas, a legenda **"Sua dupla perdeu"**); a **borda da tela** pulsando em vermelho, suave; som de derrota.
- **Fim sem batida** (§11.2, monte esgotado ou recusa da compra): arte **"Ninguém Bateu"**, só o escurecimento (sem confete nem borda vermelha).
- O leitor de tela lê a frase completa ("Você bateu!", "Sua dupla perdeu", "Fim das cartas do monte"...).
- Ao terminar, o banner é **minimizado** com animação (encolhe e desliza) até a barra de baixo, junto do botão **Seguir** (3.12), e **fica ali** enquanto a mesa final estiver aberta, sem cobrir a mão nem os jogos. Com as animações do Android desligadas: sem movimento (só a mensagem e o escurecimento; o banner troca direto para a forma minimizada). Os sons respeitam o botão de som.
- **Desistência** (§13.1) vai ao fim de jogo como antes. **Multijogador:** cada aparelho mostra no seu tempo (§16.11).
**Tags:** `go-out-overlay`, `go-out-message`, `go-out-banner` (grande), `go-out-banner-mini` (minimizado).

### 3.10 Pontos da partida (`RoundSummaryScreen`)
**Topo:** abaixo do título, a **mesma arte do banner de fim de partida** (3.9: Bateu! / Perdeu! / Ninguém Bateu, com a legenda em duplas), em tamanho menor, no lugar da faixa de texto antiga. **Objetivo:** detalhar a pontuação da partida (§12) por lado: cartas na mesa e na mão (3 vermelhos, canastras, batida, morto, cartas por valor), subtotais, total da partida e acumulado, com o rosto vencedor/perdedor/pensador por lado. **Coluna do item:** nas linhas de cartas, no lugar da descrição, **cartas de exemplo** pequenas em leque, de naipes variados e sem explicação (3 vermelhos: 3♥ 3♦; 3 pretos: 3♠ 3♣; canastra limpa: 9♥ 10♥ J♥ Q♥; suja: 9♣ 2♦ J♣ Q♣; 4 a 10: 4♣ 7♥ 10♠; figuras e ás: J♦ K♠ A♥; coringas: 2♠ 2♥); o leitor de tela lê a descrição. Batida, morto não pego, subtotais e totais continuam em texto. **Títulos das colunas** (Valor e, para cada lado, Qtd e Pontos) no topo da tabela e **repetidos** no topo de cada seção (Cartas na mesa e Cartas na mão). **Tour** (3.16): ícone ao lado do título; abre sozinho na primeira vez e explica, de forma didática, cada coluna e cada linha (as de coringa e canastra suja só quando a mesa tem coringa), rolando a tabela até a linha do passo. **Coluna Valor:** logo depois do item, o valor de cada carta, canastra, batida ou morto pelas regras da mesa (§12), com sinal; vazia nos subtotais e totais. Sem a observação do 3 vermelho abaixo da linha. **Colunas:** item | valor | e, para cada lado, **Qtd** e **Pontos** (a quantidade de cartas numa coluna própria, sem parênteses; linhas sem quantidade — batida, morto não pego, subtotais e totais — deixam a de quantidade vazia). No título de cada lado, o avatar e, abaixo, o **ícone de 3 cartas** (na cor do baralho do perfil para o seu lado, cinza-claro para o adversário) junto do nome do lado ("Nós"/"Eles" ou o nome no individual). Números com largura fixa; cabe em 360 dp com fonte 1,3×. Tags: `count-<item>-<lado>`. Aviso de empate quando for o caso. Botão **Próxima partida** ou **Continuar** (fim do jogo: vai direto ao *Fim de jogo*, 3.11). Sem desistir (decisão do usuário).
**Tags:** `round-summary`, `next-round`, `show-final`, `tie-notice`.

### 3.11 Fim de jogo / resultado final (`GameOverScreen`)
**Objetivo:** fechar o jogo (§13).
**De cima para baixo:** título, **arte do resultado** (`banner_venceu` na vitória, caindo com um balanço; `banner_perdeu` na derrota, parada; o leitor de tela lê "Vitória" / "Derrota"), os jogadores (vencedores no tamanho normal; **perdedores com metade do tamanho**), o **saco de moedas** com o resultado em moedas (§14.6) — na vitória **"+N"** (o que ganhou), na derrota **"−N"** (a aposta perdida) em vermelho; sem aposta, o texto "Jogo sem aposta" sem o saco —, a **tabela** partida a partida com o total, e **Voltar ao início**. Sem coroa, sem a linha dos nomes dos vencedores, sem "Sua dupla venceu!" e sem a frase de moedas (o leitor de tela lê "Você ganhou N moedas" / "Você perdeu N moedas").
**Tags:** `game-over`, `outcome-ribbon`, `game-over-cast`, `coins-result`, `game-over-table`, `back-home`.

### 3.12 Revisão da mesa final
Depois da batida (ou do fim sem batida, §11.2) o jogador continua na mesa de jogo vendo a **mesa final**, "só para olhar": a barra de ações mostra o **banner de fim de partida minimizado** (3.9) e o botão **Seguir**, que vai direto a *Pontos da partida* (3.10). Desistir continua pela engrenagem. No multijogador, o Seguir do anfitrião segue para os pontos e, de lá, a próxima partida, como antes (§16.11). Tag `final-table-continue`.

### 3.13 Escolha sua posição (`SeatPickerScreen`)
**Objetivo:** cada jogador escolhe o assento (§16.2) e o jogo começa quando todos confirmam. Imagem de referência: `docs/telas/escolha-posicao-multiplayer.png`.
**De cima para baixo:** botão de **voltar/sair** no canto (sempre disponível), título "Escolha sua posição" e subtítulo ("Você sempre joga na posição de baixo. Escolha onde deseja ficar na mesa."); a **mesa redonda de posições** (feltro verde, aro de madeira); à esquerda e à direita os botões **rotacionar à esquerda** e **rotacionar à direita**; embaixo, o botão dourado **Confirmar posição**.
**Mesa redonda:** **sem "VAGO"**: todo assento tem um humano ou um robô (retrato do robô; foto ou inicial do humano, §16.2, as dos outros só com *Ver fotos nas mesas compartilhadas* ligada). Cada assento mostra o nome e a marca de **confirmado** (✓) quando for o caso. O **marcador do jogador** (aro azul brilhante) fica sempre embaixo, com o nome em **campo editável** (iniciado com o nome do perfil; em branco vira "Jogador N").
**Rotação (todos, inclusive o anfitrião):** girar **troca de lugar** com o próximo ocupante naquela direção que **ainda não confirmou** (humano ou robô), pulando quem já confirmou; sem ninguém para trocar, o botão fica indisponível. Quando outro jogador troca com você, **a sua tela também muda** (você passa a ver a mesa a partir do novo lugar).
**Confirmar posição:** trava o lugar (o botão vira **Aguardando os outros jogadores**, desabilitado, e as rotações somem); ninguém mais troca com você. Quando **todos os humanos** confirmam, os **robôs confirmam sozinhos** (as marcas ✓ aparecem neles), e **2 s depois** o jogo começa em todos os aparelhos.
**Mesa só com robôs:** a mesma tela, sem rede; ao confirmar, os robôs confirmam e o jogo começa em 2 s.
**Anfitrião:** recebe o modal **Pedido de entrada** (4) de quem tocou na mesa dele em *Mesas disponíveis*; aceito, o novo jogador entra no lugar do primeiro robô e todos veem. Convidado que **recusa**, não responde ao alcance ou **sai** vira robô sorteado, com um aviso "<nome> saiu; <robô> assume o lugar". **Sair** do anfitrião desfaz a mesa (os convidados veem *Mesa encerrada*).
**Regras da mesa na sala:** abaixo da mesa redonda, um painel com "Mesa de <anfitrião>", dificuldade dos robôs (estrelas), aposta e o resumo das regras (§14.2), para o convidado ver o que aceita. Saldo menor que a aposta: aviso vermelho e **Confirmar posição** desabilitado.
**Regras:** §16.2, §16.3.
**Origem:** *Mesas disponíveis* (anfitrião, por **Escolher posições**; ou quem teve o pedido de entrada aceito) ou o modal **Convite** (convidado).
**Tags:** `seat-picker`, `seat-picker-table`, `seat-slot-<n>`, `seat-slot-<n>-confirmed`, `seat-picker-name`, `seat-picker-rotate-left`, `seat-picker-rotate-right`, `seat-picker-confirm`, `seat-picker-waiting`, `seat-picker-leave`.

### 3.14 Mesa multijogador durante o jogo
**Botão de Wi-Fi (cabeçalho da mesa de jogo, 3.8):** na linha do saco de moedas. Reflete o **Wi-Fi do jogo**.
- **Jogo só com robôs, ligar:** o jogo não muda; o jogador vira **anfitrião** (§16.7) e passa a poder liberar assentos de robô para entrada.
- **Desligar, anfitrião com humanos:** abre o modal **Desconectar a mesa?**; confirmado, todos os humanos remotos viram robôs e o jogo segue no aparelho. **Anfitrião só com robôs:** desliga sem pergunta e o jogo continua normalmente.
- **Desligar, convidado:** abre **Desistir da mesa?** (§16.9); cancelar mantém o Wi-Fi ligado e o jogo.
**Menu de engrenagem, seção Multijogador** (só com o Wi-Fi ligado):
- **Anfitrião:** *Trocar jogador ou robô* (escolhe um assento: humano → robô, ou robô → vaga aberta para entrada, §16.7, §16.8) e *Entregar a mesa* (o antigo *Repassar a mesa*, §16.10).
- **Convidado:** uma linha informativa "Anfitrião: <nome>" (sem ação).
**Telas de resultado (3.9 a 3.11) em grupo:** cada aparelho as percorre no seu tempo, como no jogo contra robôs (§16.11). O **Continuar** do anfitrião distribui a próxima partida; o do convidado só avança as telas dele (a próxima partida, já recebida, aparece depois). Ninguém espera ninguém.
**Desistir (convidado):** mesma bandeira e confirmação do jogo contra robôs (§13.1); a desistência vale a qualquer momento e o jogador vai para o fim de jogo como derrota por desistência. Na mesa de 4 posições, a **pílula do assento** dele passa a mostrar **"VAGO"** para todos e o jogo segue até a vez desse assento (§16.9); no 1×1 o outro vence na hora.
**Fim de jogo (3.11):** mostra o resultado em moedas de cada celular (prêmio dividido por assento vencedor; a parte de robô ou de assento VAGO se perde, §16.13); a derrota por desistência é a de quem desistiu. Quem estava fora no fim do jogo e volta pelo token vai direto a esta tela, com o resultado e o prêmio (§16.11). **Anfitrião:** "Voltar ao início" não derruba a mesa: enquanto alguém ainda não recebeu o resultado, a mesa segue aberta em segundo plano e fecha sozinha ao fim da entrega (§16.11, §16.15).
**Voltar à mesa (convidado que desistiu):** no fim de jogo por desistência, o botão **Voltar à mesa** reingressa pelo token enquanto o assento não foi substituído nem ocupado (§16.9); se não der, a mensagem do anfitrião aparece.
**Sair** durante o jogo é a mesma desistência (mesma confirmação).
**Tags:** `table-wifi`, `mp-menu`, `mp-swap-seat`, `mp-hand-over`, `mp-host-name`.

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
**Objetivo:** apresentar as telas na primeira vez do jogador. Cada tela com tour mostra o seu **uma única vez** (registro por tela, guardado no aparelho); o ícone do tour (letra "i" num círculo, tag `tour-button`) no alto da tela o mostra de novo a qualquer momento, e a chave **Tour guiado** do Perfil (3.6) liga ou desliga a abertura automática (ligar zera o registro de todas).
**Formato:** a tela escurece e um **recorte** destaca o elemento real do passo; por cima do véu, um **balão** de fundo marfim com texto azul-marinho (contraste AA, ≥ 4,5:1) e borda dourada, com texto curto (até ~2 linhas), o **indicador de passo** ("2/6") e os botões **Voltar**, **Próximo** (no último, **Concluir**) e **Pular**. O tour **nunca joga nem executa nada**: tocar fora do balão não faz nada; só os botões do balão funcionam. O leitor de tela lê o texto do passo. Tags: `tour-overlay`, `tour-text`, `tour-step`, `tour-next`, `tour-back`, `tour-skip`.
**Mesas disponíveis (3.2)** — aparece ao abrir a tela pela primeira vez; ícone ao lado do título (junto do Wi-Fi):
(passos na ordem da tela, de cima para baixo e, na mesma linha, da esquerda para a direita)
1. Ícone do tour: "Toque aqui para rever este tour quando quiser."
2. Botão de Wi-Fi: "Wi-Fi do jogo: ligado, aparecem os jogadores e as mesas de outros celulares por perto para jogar junto."
3. Pílula de participantes: "Escolha até 3 companheiros de mesa: jogadores por perto ou robôs. Você sempre joga."
4. Opções: "Modo, nível dos robôs, aposta, regras e pontuação-alvo. A última escolha fica guardada."
5. Escolher posições: "Pronto? Os jogadores escolhidos recebem o convite e todos escolhem o lugar na mesa."
**Mesa de jogo (3.8)** — aparece **só na primeira vez em que é a vez do jogador**, depois das animações iniciais (distribuição e trocas de 3 vermelho) e com a vez já liberada; vale contra robôs e no multijogador:
(passos na ordem da tela, de cima para baixo e, na mesma linha, da esquerda para a direita)
1. Placar: "O placar: os pontos de cada lado no jogo."
2. Total em jogo (maço de notas, `table-pot`, só com aposta): "Total em jogo: a soma das apostas de todos na mesa; quem vence leva."
3. Saldo (`coin-balance`): "Seu saldo de moedas."
4. Ícone do tour: "Toque aqui para rever este tour quando quiser."
5. Som e Opções: "Som e Opções (engrenagem): velocidade, símbolos das jogadas e desistir."
6. Outros jogadores: "Os outros jogadores: cartas na mão e a última jogada. Toque numa pílula para ver o detalhe."
7. Monte: "O monte: no começo da vez, compre uma carta daqui."
8. Mortos: "Os mortos: quem acabar primeiro com as cartas da mão pega um."
9. Lixo: "O lixo fica todo à vista; o número mostra quantas cartas há. Toque nele para pegá-lo quando valer."
10. Mão: "Sua mão. Toque nas cartas para selecionar; as destacadas podem ser jogadas agora."
11. Barra de ações: "Aqui ficam as ações que valem agora: comprar, baixar, acrescentar e descartar."
**Multijogador:** o tour é só da tela deste aparelho e não pausa o jogo; a vez continua aberta enquanto ele está na tela (não há limite de tempo para jogar, §16).

## 4. Modais

Todos levam o **cabeçalho de marca**.

| Modal | Tipo | Quando abre | Conteúdo |
|---|---|---|---|
| Começar um novo jogo? | mensagem (perigo) | Início, "Novo jogo" com jogo salvo | explica que o jogo salvo vira desistência |
| Voltar ao início? | mensagem (aviso) | voltar durante o jogo | confirma sair |
| Desistir do jogo? | mensagem (perigo) | bandeira (menu de engrenagem) | §13.1; mostra as **apostas em jogo** (aposta de cada jogador, total em jogo, quanto o jogador perde e que deixa de concorrer ao prêmio) e os **pontos** da mesa (placar de cada lado, com os nomes de quem está nele); cancelar volta ao estado exato |
| **Convite** | mensagem | jogador próximo marcado na montagem de outro anfitrião (§16.2), em qualquer tela fora de um jogo | "Mesa de <anfitrião>"; **Aceitar** / **Recusar**, **sem prazo** e sem aceite automático; some se o anfitrião desmarcar. Saldo menor que a aposta: recusa automática com aviso. Tags `invite`, `invite-accept`, `invite-refuse` |
| **Desconectar a mesa?** | mensagem (aviso) | anfitrião desliga o botão de Wi-Fi na mesa de jogo com humanos (3.14) | "Os jogadores por perto serão substituídos por robôs e o jogo segue só neste celular."; **Desconectar** / **Cancelar** (no convidado, o botão abre *Desistir da mesa?*) |
| **Recusa** | mensagem (aviso) | convite ou pedido de entrada recusado (§16.2) | quem recusou e o **motivo** (recusou o convite, saldo insuficiente, fora do alcance, o anfitrião recusou, mesa cheia); **OK**. No anfitrião, aviso breve na escolha de posições |
| Recusar a compra? | mensagem | monte acabou sem morto | encerra a partida sem batida |
| Zerar estatísticas? | mensagem | Estatísticas | confirma zerar |
| **Informações do jogo** | diálogo | tocar em "Partida N · alvo X" ou no placar, na mesa de jogo | **Jogo:** regras (nome do perfil e o resumo, o mesmo de 3.13), modo, nível dos robôs, pontuação-alvo, aposta de cada jogador e quanto recebe cada vencedor (§14.6, §16.13); **Lados:** quem joga em cada lado (avatar, nome, "robô" ou "humano"; no multijogador, quem é o anfitrião); **Partida atual:** número e quem começou; **Partidas:** tabela partida a partida (número, total de cada lado e quem bateu ou "sem batida") e o acumulado. Só informação pública (no multijogador, o histórico recebido). Botão **Fechar**. Tags `match-info`, `match-info-rounds`, `match-info-close` |
| Símbolos das jogadas | diálogo | **?** na mesa | legenda de comprar, pegar, baixar, acrescentar, descartar, recusar |
| **Detalhe do jogador** | diálogo | tocar na pílula | imagem de apresentação do robô, bordão, nome e a última jogada (com cartas e 3 vermelhos baixados) |
| Lixo completo | diálogo | ícone na linha do lixo | todas as cartas do lixo |
| **Procurar mesas por perto** | mensagem | ligar o Wi-Fi do jogo no Perfil ou no botão de Wi-Fi de Mesas disponíveis, abrir Mesas disponíveis com ele ligado e sem permissão, ou criar mesa multijogador | explica, em linguagem simples, as permissões pedidas (Bluetooth, dispositivos próximos ou localização, conforme o Android) e que **nada sai do aparelho e não usa internet**; **Permitir** / **Agora não** (o modo fica desligado) |
| **Versão do jogo diferente** | mensagem (aviso) | entrar em mesa de outra versão | "O anfitrião está com outra versão do jogo (anfitrião: versão N; você: versão M). Atualizem todos os celulares para a mesma versão e criem uma mesa nova."; recusa a entrada |
| **Pedido de entrada** | mensagem | anfitrião, quando alguém pede para entrar na escolha de posições (§16.2, no lugar de um robô) ou no meio do jogo (§16.7) | nome e foto de quem pede e o assento (robô ou o da vez); **Aceitar** / **Recusar** |
| **Jogo pausado** | mensagem | convidados, quando o jogo pausa por queda ou recrutamento (§16.8) | quem caiu e o que o anfitrião está fazendo; fecha sozinho quando o jogo segue |
| **Aguardando <nome>** | diálogo (anfitrião) | chega a vez de quem caiu | três ações: **Aguardar o reingresso**, **Substituir por um robô** e **Recrutar outro jogador** (desativa o que não vale; com o recrutamento ativo vira "Aguardando recrutamento", ainda com **Substituir por robô**) |
| **Entregar a mesa** | diálogo (anfitrião) | seção Multijogador da engrenagem ou ao desistir | lista os humanos disponíveis pela ordem de entrada (nunca robôs); **Passar** / **Cancelar** |
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
| Regras da mesa e Criar mesa (telas removidas; capturas antigas mantidas como histórico) | `03-regras-da-mesa.png`, `04-criar-mesa.png` e variações |
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
- 3.9 Animação da batida, 3.10 Pontos da partida, 3.12 Revisão da mesa final e a variação de **vitória** do Fim de jogo: exigem terminar uma partida jogando. Desistir leva direto ao Fim de jogo (derrota), sem passar pela mesa final. Caminho sugerido: adaptar [EndScreensTest.kt](../app/src/androidTest/kotlin/com/gtranca/EndScreensTest.kt), que monta essas telas com dados fixos.
- Modais *Onde baixar?*, *Como usar o descarte?* e *Recusar a compra?*: dependem da mão sorteada (ou do monte acabar sem morto).
- Faixa de troca de 3 vermelho de outro assento e pulso/selo de canastra (animações curtas).
- Variações: mesa com "saldo insuficiente", Perfil com foto, Estatísticas com partidas jogadas.

## 7. O que ainda não está aqui

- Textos exatos de cada mensagem de erro (ver `strings.xml`).
- Telas do multijogador: primeira versão na etapa 6 do `docs/plano-multijogador-local.md` (capturas depois do teste nos celulares). Fora dela: card completo da mesa (anúncio maior) e **Continuar** da sessão multijogador (§16.12, falta o espaço da sessão no `:data`).
- Conferência visual no celular das mudanças de 18–19/10 (ver o status em `docs/plano-nomes-aos-bots.md`).
