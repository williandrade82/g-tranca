# Telas do Tranca-Bot

Especificação das telas e dos modais do app, para pedir mudanças sem ambiguidade. Foi montada em 2026-10-19 a partir do código, do `definition.md` e dos planos em `docs/`.

**Como usar**
- **Regras do jogo** ficam no [definition.md](../definition.md) (fonte da verdade). Aqui só se aponta para a seção (`§`).
- **Telas e componentes** ficam neste arquivo. Mudou uma tela? Atualize aqui **antes** do código, como se faz com as regras.
- **Valores exatos** (cores, dp, textos) não são copiados para cá: estão em `ui/theme/` e em `res/values/strings.xml`. Aqui vai o que a tela mostra e para quê.
- Planos `docs/plano-*.md` são históricos de trabalhos pontuais; este arquivo é o retrato atual.

---

## 0. Saldo no topo (todas as telas)
Em todas as telas, um indicador pequeno no **centro do alto** mostra o **saco de moedas** e o **saldo** do jogador (tag `coin-balance`; leitor de tela: "Seu saldo: N moedas"), atualizado na hora (aposta debitada, prêmio recebido, ganho diário). Fica numa faixa própria acima do conteúdo, para não sobrepor títulos e botões; na **mesa de jogo** ele não ganha faixa: vira um ícone da linha dos ícones do cabeçalho (3.8), no tamanho da engrenagem, com o número embaixo. A linha "Seu saldo: N moedas" do Início (junto do resgate diário) **continua**; Iniciar novo jogo não tem linha de saldo nem saldo junto da aposta (só o do topo).

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
| **Modal de jogada não permitida** | `GDialog` que explica por que o motor recusou a jogada do jogador: título curto, **o que aconteceu**, **a regra desta mesa** (cita o nome da regra da mesa, ex.: "Tranca Paulista") e **o que fazer**; três botões no lugar do antigo "Entendi", as opções de **Ações impedidas**: **Continuar avisando** (só fecha o modal; destaque), **Avisar discretamente** e **Não avisar mais** (fecham e gravam a preferência). Aparece só quando a preferência *Ações impedidas* do Perfil é **Explicar sempre** | mesa de jogo (ver §4) |
| **Aviso discreto** | Faixa pequena (`GSnack`) na altura do último toque do jogador na mesa, logo acima do dedo, fixa enquanto aparece (nunca sob a barra de navegação do Android), com uma frase simples do problema (ex.: "Compre uma carta antes de jogar"); some sozinha. **Tocar na faixa (mensagem ou ícone) abre o modal de jogada não permitida**, o mesmo da opção *Explicar sempre* (a faixa "Mostre suas cartas para jogar" não abre nada). Usada quando *Ações impedidas* é **Aviso discreto** | mesa de jogo (ver §4) |
| **Placar (pílula)** | Cápsula com os totais acumulados ("Nós 200 × 300 Eles") | cabeçalho da mesa |
| **Armário** | Imagem do armário de madeira (`booster-stock-v1.png`) com 9 nichos (3 × 3); cada **nicho** guarda um item do inventário | Inventário |
| **Nicho de booster** | Espaço do armário (ou do seletor) com o **ícone** do booster, o **nome** embaixo à esquerda e a **quantidade** embaixo à direita; **esmaecido** com quantidade 0; **marcado** (aro dourado) quando escolhido para o próximo jogo | Inventário, Seletor de boosters |
| **Ponto verde / círculo vermelho** | Marcas do b1 (ponto verde, canto **superior direito** da carta) e do b2 (círculo vermelho, mesmo tamanho, canto **inferior esquerdo**; a carta **pulsa** se fecharia uma canastra do adversário) | mão da mesa |
| **Botão de reações** | Ícone (`reacts-icon-v1.png`) entre "Sua mão" e o botão da última jogada, só com o b3 na partida; mostra os usos restantes; abre o **balão de reações** | mesa de jogo |
| **Balão de reações** | Balão de fala discreto acima do botão com as 5 reações (imagem, nome); fecha ao escolher uma ou ao tocar fora; o texto "N restantes" mostra os usos da partida | mesa de jogo |
| **Bordão** | Frase decorativa em itálico dourado de cada robô | vários |
| **Medalhão** | Aro dourado com o avatar dentro: robô (em círculo) ou foto/inicial do jogador | pílula, Perfil |
| **Barra de ações** | Status da vez + botões (Baixar, Comprar, Descartar...) fixos acima da mão | mesa de jogo |
| **Mão** | As cartas do jogador, em grade, com o seletor de ordem (Valor/Naipe e "separar 3 pretos e coringas") | mesa de jogo |
| **Mesa** | O feltro verde com o monte, os mortos e o lixo | mesa de jogo |
| **Verso da carta** | Cor do baralho do jogador + coroa no centro | monte, mãos viradas |
| **Fundo dos perdedores** | Degradê vermelho escuro (embaixo) → transparente (em cima), que esmaece e reaparece em ciclo, atrás dos robôs que perderam | fim de jogo |
| **Chip de escolha** | Cápsula selecionável (selecionada: azul royal com borda dourada) | Iniciar novo jogo, regras |
| **Botão de Wi-Fi** | Botão com o símbolo de Wi-Fi (a mesma configuração **Wi-Fi do jogo** do Perfil); ligado, anuncia a presença do jogador e procura jogadores por perto (§16); desligado, nada de rede é usado. Ligar/desligar **nunca trava** a tela onde está | Iniciar novo jogo, Seleção de jogadores (bloco Multiplayer), mesa de jogo |
| **Pílula de humano remoto** | Pílula do assento de outro humano: medalhão com a **foto** dele (se tiver e se a opção *Ver fotos nas mesas compartilhadas* do Perfil estiver ligada) ou com a inicial, nome, mão em cartas viradas e última jogada; a vez dele é indicada como a dos robôs | mesa de jogo |
| **Quadro de opções** | Cartão com Modo, Dificuldade dos robôs, Aposta, Regras e Pontuação-alvo | Iniciar novo jogo |
| **Valor de aposta** | Chip com o **ícone do saco de dinheiro** e o valor (três por nível de dificuldade, §14.6); desabilitado acima do saldo | Iniciar novo jogo |
| **Botão "Ver detalhes da regra"** | Botão discreto (texto/ícone pequeno) **dentro do quadro de regras**; abre o modal *Detalhes da regra* | Iniciar novo jogo, Convite |
| **Avatar de jogador próximo** | Foto do perfil em círculo (inicial se não tiver foto, se a opção *Ver fotos nas mesas compartilhadas* estiver desligada ou antes de a foto chegar) com o **nome abaixo** (uma linha, "…" se não couber), no mesmo formato da antiga grade de participantes | listas das telas 3.2 e 3.3 |
| **Lista de recrutadores** | Fileira de avatares dos outros jogadores próximos que estão **recrutando** (sem o próprio); só informativa, tocar não faz nada | Iniciar novo jogo |
| **Mesa de lugares** | Mesa (feltro verde, aro de madeira) com 2 lugares (individual: um em cima, um embaixo) ou 4 (duplas, em cruz); o jogador sempre embaixo; cada lugar é **VAGO** (círculo tracejado com "VAGO"), **robô**, **Chamado**, **Recrutado** ou o próprio jogador; o nome sob o avatar | Seleção de jogadores, Mesa do recrutado |
| **Lugar VAGO** | Lugar sem ninguém: círculo tracejado com "VAGO"; tocar nele o seleciona (aro dourado) para receber um robô ou um jogador | Seleção de jogadores |
| **Lista de robôs** | Fileira horizontal dos 4 robôs (Bolt, Domino, Turbo, Zen): **foto de apresentação em círculo** e o nome embaixo; o robô já sentado fica **esmaecido** na lista | Seleção de jogadores |
| **Bloco Multiplayer** | Texto "Multiplayer" à esquerda e o **sinal de Wi-Fi** à direita (reflete e altera o Wi-Fi do jogo do Perfil), seguido da lista de jogadores próximos livres | Seleção de jogadores |
| **Selo "Chamado"** | Imagem (`seal_chamado`) sobre o avatar de quem recebeu o convite e ainda não respondeu; o avatar fica esmaecido; no recrutador ganha um **anel fino de contagem regressiva** de 25 s (o prazo do convite) | listas e mesa de lugares |
| **Selo "Recrutado"** | Imagem (`seal_recrutado`) sobre o avatar de quem aceitou (avatar sem esmaecimento); também mostrado a **outros** recrutadores na lista (jogador não selecionável) | listas e mesa de lugares |
| **Imagem "Prepare-se!"** | Camada sobre a **própria tela**, que fica ao fundo esmaecida (preto ~50 %): a imagem "Prepare-se! O jogo vai começar!" centralizada, entrando com leve escala e fade; bloqueia os toques; por **no mínimo 1,5 s** em todos os celulares, até o jogo começar | Seleção de jogadores, Mesa do recrutado |

---

## 2. Mapa de navegação

```
(abertura sem nome no perfil) Boas-vindas ──► Perfil ──(com nome)──► Início
Início ──► Iniciar novo jogo (opções; ⇄ Personalizar regra) ──Selecionar jogadores──► Seleção de jogadores ──Começar Partida──► Prepare-se! (camada, ≥ 1,5 s) ──► Seletor de boosters (30 s; antes de cada partida) ──Seguir──► JOGO
  ├──► Continuar ─────────────────────────────────────────────────────────────────► JOGO
  ├──► Inventário (armário; marca até 3 boosters)
  ├──► Perfil ──► Personalizar regra
  └──► Estatísticas

(qualquer tela fora de um jogo, Wi-Fi do jogo ligado) modal Convite ──Aceitar──► Mesa do recrutado (só leitura) ──(Começar do recrutador: Prepare-se!)──► JOGO (multijogador)
                                                        └─Recusar──► (continua onde estava)
Início ──► Continuar (sessão multijogador) ──► reingresso na mesa ──► JOGO (multijogador)

JOGO: mesa ─(fim da partida: banner e animação de 2 s)─► mesa final (banner minimizado + Seguir) ─► pontos da partida ─► (próxima partida | fim de jogo) ─► Início
```

Voltar do jogo leva ao Início (as telas de mesa saem da pilha). Voltar da Seleção de jogadores leva a Iniciar novo jogo e encerra o recrutamento. Rotas em `ui/AppNavHost.kt`.

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
5. **Ver mesas - Iniciar** (abre *Iniciar novo jogo*): dourado sem jogo salvo; secundário com jogo salvo.
6. **Barra fixa** embaixo: Perfil · **Inventário** (ícone do armário, tag `inventory`) · Estatísticas; versão discreta logo abaixo.
7. Botão de **som** no canto superior direito, sempre à vista.

**Variações:** "Ver mesas - Iniciar" com jogo salvo abre o modal *Começar um novo jogo?* (o jogo salvo conta como derrota por desistência). Com uma **sessão multijogador** guardada (§16.12), um segundo botão **Continuar mesa** (tag `continue-session`) aparece ao lado do jogo salvo contra robôs (cada um no seu botão), com o nome do anfitrião ("Mesa de <anfitrião>") e o assento ("Você: lugar 3"): tocar nele leva a *Voltar à mesa* (3.17) e reingressa como jogador comum, mesmo para quem era anfitrião. A sessão some quando o jogo termina e o resultado é entregue (§16.11), quando o jogador desiste e não volta, quando a mesa é encerrada ou a vaga é preenchida, quando ele sai da mesa pelo botão e **12 h** depois de gravada.
**Presente do dia (§17.5):** na primeira abertura do dia, **só aqui** (não nas Boas-vindas), se o sorteio acertar, o modal *Presente do dia* aparece sobre o Início.
**Regras:** §14 (jogo salvo), §14.6 (moedas), §13.1 (desistência), §17.5 (presente).
**Tags:** `inventory`, `home-bots`, `new-game`, `continue`, `continue-session`, `home-balance`, `claim-daily`, `profile`, `stats`, `home-version`.

### 3.2 Iniciar novo jogo (`TablesScreen`)
**Objetivo:** escolher as **opções da mesa** (§14.4): modo, dificuldade dos robôs, aposta, regras e pontuação-alvo; os jogadores são escolhidos na tela seguinte (3.3). Substitui a antiga *Mesas disponíveis*: a grade de participantes, os cards de mesas de outros anfitriões e o pedido de entrada deixaram de existir; **só se entra numa mesa sendo chamado** (§16.2).
**De cima para baixo (rola):**
1. Título **"Iniciar novo jogo"**, o ícone do tour e o **botão de Wi-Fi** (configuração **Wi-Fi do jogo**, a mesma do Perfil: tocar alterna e grava; ao ligar, passa pelo modal *Procurar mesas por perto*; recusar deixa desligado). É ele que torna o jogador **livre** para ser chamado (§16.2). Sem linha de saldo (só o do topo, seção 0).
2. **Lista de recrutadores** (só com o Wi-Fi ligado): título "Jogadores recrutando" e os **avatares de jogador próximo** dos outros jogadores que estão na *Seleção de jogadores* (sem o próprio jogador). **Só informativa:** tocar não faz nada. Vazia, mostra só uma legenda discreta ("Ninguém recrutando por perto"). A lista acompanha a busca: quem desliga o Wi-Fi do jogo some na hora (aviso de "saindo"), quem some sem avisar sai em até cerca de 31 s (§16.15; no modo ativo, cerca de 16 s), quem continua não pisca. A foto dos recrutadores aparece como **inicial** (limitação do `P2P_STAR`, §16.2). À direita do título da lista fica o **botão de atualizar** (ícone discreto de duas setas em círculo, alvo de 48 dp, descrição "Atualizar jogadores por perto", tag `refresh-players`): faz a busca em rajada (até 2 recomeços rápidos dentro do orçamento de buscas, §16.15); enquanto a rajada roda o ícone **gira** e fica desabilitado. Só aparece com o Wi-Fi ligado. "Puxar para atualizar" não existe (conflita com a rolagem da tela).
3. **Quadro de opções** (sempre preenchido com a última configuração gravada):
   - **Modo** (Individual / Duplas): define 2 ou 4 lugares na tela 3.3.
   - **Dificuldade dos Robôs** (Iniciante / Maduro / Sênior).
   - **Aposta (moedas):** três **valores de aposta** (cada um com o ícone do saco de dinheiro) que dependem da dificuldade: Iniciante 50/80/100, Maduro 100/150/200, Sênior 200/300/500 (§14.6). Ao trocar a dificuldade a aposta mantém o **mesmo índice**; valor acima do saldo fica desabilitado, e o que passa do saldo ao trocar de nível vira o maior valor coberto. Sem linha de saldo ao lado.
   - **Regras:** chips Clássica, Tranca Paulista e Personalizada ("Personalizada" sempre abre *Personalizar regra*, 3.5). O **resumo das regras não fica na tela**: o botão discreto **"Ver detalhes da regra"**, dentro do quadro de regras, abre o modal *Detalhes da regra* (4).
   - **Pontuação-alvo** (campo numérico, padrão 3000, com mensagens de erro).
4. Barra fixa: **Voltar** (sempre habilitado, mais estreito que o outro botão) e **Selecionar jogadores** (habilitado quando as opções são válidas e há saldo para a aposta). O texto de cada botão fica **numa linha só**: com fonte grande a letra diminui, nunca quebra.

**Selecionar jogadores:** grava a configuração (**modo, dificuldade, aposta, regras e pontuação-alvo**; os lugares **não** são gravados) e abre a *Seleção de jogadores* (3.3).
**Chamado por outro recrutador:** o modal *Convite* (4) aparece sobre esta tela (ou qualquer tela fora de um jogo). Aceito, a tela vira a *Mesa do recrutado* (3.13); quem estava em *Personalizar regra* volta à tela anterior **sem salvar**.
**Lembra a última mesa:** ao carregar, o que ficou inválido é ajustado sem aviso: aposta que não existe na tabela do nível vira o valor mais próximo (empate: o menor); aposta acima do saldo vira a maior que o saldo cobre; "Personalizada" sem regras salvas vira Clássica; pontuação-alvo inválida volta ao padrão.
**Wi-Fi ligado sem permissão:** ao abrir, aparece o modal *Procurar mesas por perto*; **Agora não** desliga a configuração. Se o Nearby recusar a busca, um aviso explica e a configuração é desligada.
**Regras:** §14.4, §14.6, §16.2.
**Tags:** `tables-screen`, `tables-wifi`, `recruiters`, `recruiter-<id>`, `recruiters-empty`, `refresh-players`, `mode-individual`, `mode-duplas`, `difficulty-<nível>`, `stake-<valor>`, `rules-classic`, `rules-paulista`, `rules-custom`, `rules-details`, `target-score`, `select-players`, `tables-back`.

### 3.3 Seleção de jogadores (`PlayerSelectionScreen`)
**Objetivo:** montar a mesa — quem ocupa cada lugar — e começar a partida (§14.4, §16.2). As opções (3.2) aparecem só como resumo e **não mudam** aqui.
**De cima para baixo:**
1. Título **"Seleção de jogadores"** e o ícone do tour; um resumo das opções (tipo, nível, aposta, regras). O **Voltar** fica na barra fixa, ao lado de **Começar Partida** (volta a *Iniciar novo jogo* e **encerra o recrutamento**: chamados cancelados, recrutados recebem "Mesa encerrada").
2. **Mesa de lugares:** 2 lugares (individual, um **em cima** e um **embaixo**) ou 4 (duplas). O **jogador fica sempre embaixo**, com o nome e a foto/inicial; os demais lugares começam **VAGO**. Um lugar pode estar: **VAGO**, com um **robô** (retrato e nome), com um **Chamado** (avatar esmaecido com o selo **Chamado**) ou com um **Recrutado** (avatar com o selo **Recrutado**). Em duplas, o lugar da frente é o do **parceiro**. A imagem da mesa é **20 % menor** que a do desenho original e fica **centralizada**; o espaço que sobra volta ao conteúdo. Embaixo da mesa, uma **dica de uma linha** explica o estado da seleção (tag `seat-hint`), p.ex. "Escolha um lugar para o Bolt", "Escolha quem vai para este lugar" ou "Toque em outro lugar para trocar, ou na lista para retirar".
   - **Selecionar e tocar, nos dois sentidos:** o jogador escolhe um **item da lista** (robô ou jogador próximo livre) **e depois um lugar VAGO**, ou **um lugar VAGO e depois o item**: o robô é **colocado** (e fica esmaecido na lista) ou o jogador remoto é **chamado** (o convite só sai quando **os dois** estão escolhidos: jogador + lugar). A seleção pendente fica **destacada**; tocar de novo no mesmo item ou numa área vazia a **cancela**.
   - **Trocar:** escolher **dois lugares** da mesa (ocupados ou VAGO) **troca** os lugares (VAGO ↔ ocupado **move**); nunca envolve o lugar do próprio jogador. Pode trocar também um **Chamado** (a posição no convite dele acompanha).
   - **Retirar:** escolher primeiro um **robô ou jogador que já está na mesa** e depois **qualquer item da lista de baixo** (robô ou jogador) **retira** o da mesa: o robô volta à lista; o **Chamado** tem o convite cancelado (some sem aviso); o **Recrutado** recebe "<nome> tirou você da mesa". O item tocado na lista **não** entra no lugar: só retira.
   - **Arrastar e soltar** continua valendo: arrastar o avatar para outro lugar **troca** (ocupado) ou **move** (VAGO), pelas mesmas regras de Trocar. O menu "Remover / Mover" do lugar deixou de existir (substituído por Trocar e Retirar). Cada avatar tem descrição para o leitor de tela ("Bolt, robô, disponível", "Ana, chamado", "Ana, recrutado").
3. **Lista de robôs:** os 4 robôs (**foto de apresentação em círculo** e o nome embaixo), numa fileira horizontal.
4. **Bloco Multiplayer:** "Multiplayer" à esquerda e o **sinal de Wi-Fi** à direita (reflete a configuração do Perfil; ligar e desligar **não trava** a tela). Com o Wi-Fi ligado o jogador é **recrutador**: aparece na lista da tela 3.2 dos outros e, **abaixo do título "Multiplayer"**, aparece a lista de **jogadores próximos livres** (os que estão em *Iniciar novo jogo* com o Wi-Fi ligado). Recrutar: escolher o jogador remoto e o lugar (em qualquer ordem); ele fica **esmaecido com o selo Chamado** na lista (e o lugar na mesa ganha o avatar dele com o selo); tocar nele não faz mais nada. Jogador já chamado/recrutado por **outro** recrutador aparece como **Recrutado** e não é selecionável. O selo **Chamado** leva um **anel fino de contagem regressiva** (25 s, o prazo do convite, §16.15); sem resposta, vale como recusa. Recusou ou não respondeu: modal do recrutado fecha, o recrutador é avisado (toast com o motivo), o lugar volta a **VAGO**, o avatar perde o selo e pode ser chamado de novo. Aceitou: o selo vira **Recrutado** e o avatar perde o esmaecimento. Ao lado do título "Multiplayer", o mesmo **botão de atualizar** (tag `selection-refresh`) refaz a busca dos jogadores próximos; só com o Wi-Fi ligado.
5. **Completar com robôs:** atalho (botão secundário) que sorteia robôs **ainda não usados** nos lugares VAGO.
6. **Começar Partida** (botão dourado no fim): **desabilitado** até todos os lugares estarem preenchidos **e** sem nenhum **Chamado** pendente. Ao tocar, a camada **"Prepare-se! O jogo vai começar!"** aparece **sobre a própria tela, esmaecida,** em **todos** os celulares por **no mínimo 1,5 s** (sem apagar a tela de baixo), e então o jogo começa.
**Sem Wi-Fi (ou ninguém por perto):** a mesma tela, sem a lista de jogadores; só com robôs, e o jogo começa no próprio aparelho.
**Regras:** §14.4, §14.5, §16.2, §16.3.
**Tags:** `player-selection`, `seat-<n>` (`seat-<n>-vacant`, `seat-<n>-called`, `seat-<n>-recruited`), `seat-hint`, `seat-<n>-timer`, `robot-<perfil>`, `selection-wifi`, `selection-refresh`, `nearby-free-<id>`, `fill-with-robots`, `start-match`, `get-ready`, `selection-back`.

### 3.5 Personalizar regra (`RulesEditorScreen`)
**Objetivo:** editar o perfil de regras personalizado do jogador (§14.2, §14.4). Hoje se chega por **Iniciar novo jogo** ("Personalizada") e pelo **Perfil** ("Minhas regras").
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
6d. Painel **Ações impedidas** (três botões de escolha única; grava na hora; padrão **Explicar sempre**): o que a mesa faz quando o motor recusa uma jogada. **Não avisar**: nada aparece. **Aviso discreto**: o *aviso discreto* (faixa pequena na altura do último toque, logo acima do dedo, com uma frase simples do problema). **Explicar sempre**: o *modal de jogada não permitida* (o que aconteceu, regra da mesa, o que fazer). O modal tem os botões **Não avisar mais**, **Avisar discretamente** e **Continuar avisando**, que mudam esta mesma preferência na hora. Tags `profile-refusal-none`, `profile-refusal-subtle`, `profile-refusal-explain`.
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
**Boosters na mão (§17):** o realce das cartas da mão (ponto verde nas que participam de jogada; com seleção, borda forte nas que completam e esmaecimento das demais) **só existe com o b1 (Green-LED)** na partida; sem ele, só a carta selecionada se destaca. Com o **b2 (Red-LED)**, depois de comprar ou pegar o lixo, as cartas que se encaixam nos jogos do adversário ganham o círculo vermelho no canto inferior esquerdo (e pulsam se fecham uma canastra dele). Com o **b3 (Reações)**, o **botão de reações** fica na linha "Sua mão (N)", entre o título e o botão da última jogada (`own-last-play`); o toque abre o **balão de reações** acima dele; a reação escolhida aparece junto da pílula do assento (a do próprio jogador, junto da linha da mão) por alguns segundos e a contagem cai de 3 para 0. Tags: `reactions-button`, `reactions-balloon`, `reaction-<nome>`.
**Ordem do detalhe da vez (pílula e modal):** a vez aparece na **ordem real, a mesma das animações**: troca de 3 vermelho do início da vez, compra ou lixo (ao pegar o lixo, primeiro o jogo com o topo e as cartas da mão, depois o resto que foi à mão), troca do 3 vermelho que veio na compra, baixas e acréscimos na ordem em que foram feitos, e o descarte por último.
**Sinal da conexão (multijogador, só na tela dos convidados):** a pílula do **anfitrião** mostra um ícone de sinal com **1 a 3 barras** (tag `host-signal`): a qualidade da conexão deste aparelho com o anfitrião (§16.15). No anfitrião não aparece.
**Esconder cartas (só na mesa multijogador):** na linha "Sua mão (N)", à esquerda do botão de ordenação, o botão de **olho** (tag `hand-hide`; olho riscado quando escondida). Ligado, as cartas da mão aparecem **viradas** (verso na cor do baralho) e não podem ser selecionadas; a contagem continua. O estado é só deste aparelho (não vai pela rede) e vale até o fim do jogo; **não se desliga sozinho** na vez do jogador: Comprar e Pegar o lixo continuam funcionando, e tocar numa carta virada mostra o aviso "Mostre suas cartas para jogar". Contra robôs o botão não aparece.
**Som da vez:** quando a vez passa a ser do jogador deste aparelho (no momento em que ela é liberada, depois das animações), toca um som curto e suave (dois tons leves). Respeita o botão de som; não toca na revisão da mesa final nem enquanto a vez continua com o mesmo jogador.
**Espaço vertical:** a mesa prioriza o feltro: cabeçalho, pílulas, margens do feltro, títulos das seções de jogos, linha "Sua mão" e barra de ações são compactos, e as cartas dos jogos na mesa e do lixo são um pouco maiores (34 × 50 dp). **Telas pequenas e fonte grande:** as pílulas e o feltro rolam juntos; o feltro ocupa pelo menos o espaço livre e **cresce com o conteúdo** (nunca corta jogos nem o lixo: o que não cabe fica abaixo, na rolagem). Na barra de ações, o texto da vez fica numa linha (a fonte diminui até caber) e os botões dividem a largura numa linha só, com alvo de 48 dp; **Limpar** vira um ícone "✕" (descrição "Limpar"). Na pílula do parceiro, o rótulo "Parceiro" diminui até caber em vez de ser cortado. Títulos de destaque (logotipo, títulos de tela e de resultado) diminuem a fonte até caber, sem quebrar palavra.
**Pílula do assento:** medalhão com o robô — **com as cartas na mão por padrão** e a pose de **pensador** só na vez dele —, nome sem posição (o parceiro leva a legenda "Parceiro"), mão em cartas viradas, contador e símbolos da última jogada. Tocar abre o modal de detalhe.
**Multijogador (§16):** os outros assentos humanos usam a **pílula de humano remoto**; o **indicador de vez** (destaque e texto "Vez de <nome>") vale para humanos remotos e robôs. Cada aparelho anima na própria velocidade (§16.6). Se quem tem a vez caiu, a mesa mostra *Aguardando <nome>* (modais em 4). O jogo só entrega a vez ao jogador depois de a animação do seu aparelho terminar. A **bandeira** (desistir) leva ao modal de desistência de grupo.
**Jogada recusada:** quando o jogador tenta algo que o motor não permite (baixar, acrescentar, pegar o lixo, comprar, descartar, ficar sem cartas/bater), abre o **modal de jogada não permitida** (4), com a explicação e a regra da mesa. O que a mesa faz depende da preferência **Ações impedidas** do Perfil (3.6, item 6d): **Explicar sempre** (padrão) abre o modal; **Aviso discreto** mostra só a faixa pequena na altura do último toque, logo acima do dedo, com uma frase simples do problema; **Não avisar** não mostra nada. A faixa "Mostre suas cartas para jogar" (mesa multijogador com a mão escondida) não é recusa de regra e aparece sempre. O modal não fecha sozinho; nada mais é bloqueado, e a seleção de cartas continua como estava.
**Faixas e encenações:** troca de 3 vermelho de outro assento (faixa breve); cartas em voo; selo e pulso de canastra.
**Camadas (z-order):** a **barra superior de ícones** (cabeçalho) fica **à frente** das animações da mesa: as cartas em voo e os fogos de artifício da batida passam **por baixo** dela e nunca por cima (são cortados na borda do cabeçalho). Só os avisos, banners e modais (troca de 3 vermelho, banner de fim de partida, diálogos) cobrem a tela inteira, cabeçalho incluído.
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

**Dramatização da batida (antes do banner):** a batida é encenada em três tempos, para o jogador entender o que aconteceu. Vale para qualquer jogador que bata (o próprio, o parceiro, um robô ou outro humano da mesa online) e para a batida que encerra o jogo; o banner só aparece no fim.
1. **Última jogada em câmera lenta:** o voo das cartas da última ação da partida (o descarte ou as cartas baixadas por quem bateu) dura **3 vezes** o normal (a pausa entre as cartas do mesmo movimento também), respeitando a velocidade das animações do Perfil e a escala do sistema. Nesse voo a carta **cresce até 1,4 vez** o tamanho nos primeiros 40% do trajeto, **fica grande** nos 20% seguintes e **volta ao normal** nos últimos 40%, chegando à posição final no tamanho de sempre. A mesa continua visível e a última jogada nunca é cortada pelo banner: ele espera o voo pousar.
2. **Fogos de artifício por 1,5 s** depois que a carta chega, sobre a mesa final (sem escurecê-la); no instante da chegada a **mesa treme** (shake curto de ~450 ms, amortecido, deslocamento de poucos dp e rotação mínima, só a mesa: o banner depois não treme e os toques não são afetados; sem tremida com as animações do Android desligadas): **verdes** se o jogador (ou a dupla dele) venceu a partida, **vermelhos** se perdeu. Desenhados em Canvas (partículas), não bloqueiam a mesa além do necessário (um toque pula os fogos) e cancelam limpo se a tela sair. Não há fogos no fim sem batida (§11.2).
3. **Banner de bateu/perdeu** (acima), com o confete, a borda vermelha e os sons de sempre.
Com as animações do Android desligadas: sem voo lento e sem fogos (vai direto ao banner).
**Tags:** `go-out-overlay`, `go-out-message`, `go-out-banner` (grande), `go-out-banner-mini` (minimizado), `go-out-fireworks`.

### 3.10 Pontos da partida (`RoundSummaryScreen`)
**Topo:** abaixo do título, a **mesma arte do banner de fim de partida** (3.9: Bateu! / Perdeu! / Ninguém Bateu, com a legenda em duplas), em tamanho menor, no lugar da faixa de texto antiga. **Objetivo:** detalhar a pontuação da partida (§12) por lado: cartas na mesa e na mão (3 vermelhos, canastras, batida, morto, cartas por valor), subtotais, total da partida e acumulado, com o rosto vencedor/perdedor/pensador por lado. **Coluna do item:** nas linhas de cartas, no lugar da descrição, **cartas de exemplo** pequenas em leque, de naipes variados e sem explicação (3 vermelhos: 3♥ 3♦; 3 pretos: 3♠ 3♣; canastra limpa: 9♥ 10♥ J♥ Q♥; suja: 9♣ 2♦ J♣ Q♣; 4 a 10: 4♣ 7♥ 10♠; figuras e ás: J♦ K♠ A♥; coringas: 2♠ 2♥); o leitor de tela lê a descrição. Batida, morto não pego, subtotais e totais continuam em texto. **Títulos das colunas** (Valor e, para cada lado, Qtd e Pontos) no topo da tabela e **repetidos** no topo de cada seção (Cartas na mesa e Cartas na mão). **Tour** (3.16): ícone ao lado do título; abre sozinho na primeira vez e explica, de forma didática, cada coluna e cada linha (as de coringa e canastra suja só quando a mesa tem coringa), rolando a tabela até a linha do passo. **Coluna Valor:** logo depois do item, o valor de cada carta, canastra, batida ou morto pelas regras da mesa (§12), com sinal; vazia nos subtotais e totais. Sem a observação do 3 vermelho abaixo da linha. **Colunas:** item | valor | e, para cada lado, **Qtd** e **Pontos** (a quantidade de cartas numa coluna própria, sem parênteses; linhas sem quantidade — batida, morto não pego, subtotais e totais — deixam a de quantidade vazia). No título de cada lado, o avatar e, abaixo, o **ícone de 3 cartas** (na cor do baralho do perfil para o seu lado, cinza-claro para o adversário) junto do nome do lado ("Nós"/"Eles" ou o nome no individual). Números com largura fixa; cabe em 360 dp com fonte 1,3×. Tags: `count-<item>-<lado>`. Aviso de empate quando for o caso. Botão **Próxima partida** ou **Continuar** (fim do jogo: vai direto ao *Fim de jogo*, 3.11). Sem desistir (decisão do usuário).
**Tags:** `round-summary`, `next-round`, `show-final`, `tie-notice`.

### 3.11 Fim de jogo / resultado final (`GameOverScreen`)
**Objetivo:** fechar o jogo (§13).
**De cima para baixo:** título, **arte do resultado** (`banner_venceu` na vitória, caindo com um balanço; `banner_perdeu` na derrota, parada; o leitor de tela lê "Vitória" / "Derrota"), os jogadores (vencedores no tamanho normal; **perdedores com metade do tamanho**), o **saco de moedas** com o resultado em moedas (§14.6) — na vitória **"+N"** (o que ganhou), na derrota **"−N"** (a aposta perdida) em vermelho; sem aposta, o texto "Jogo sem aposta" sem o saco —, a **tabela** partida a partida com o total, e **Voltar ao início**. Sem coroa, sem a linha dos nomes dos vencedores, sem "Sua dupla venceu!" e sem a frase de moedas (o leitor de tela lê "Você ganhou N moedas" / "Você perdeu N moedas").
**Tags:** `game-over`, `outcome-ribbon`, `game-over-cast`, `coins-result`, `game-over-table`, `back-home`.

### 3.12 Revisão da mesa final
Depois da batida (ou do fim sem batida, §11.2) o jogador continua na mesa de jogo vendo a **mesa final**, "só para olhar": a barra de ações mostra o **banner de fim de partida minimizado** (3.9) e o botão **Seguir**, que vai direto a *Pontos da partida* (3.10). Desistir continua pela engrenagem. No multijogador, o Seguir do anfitrião segue para os pontos e, de lá, a próxima partida, como antes (§16.11). Tag `final-table-continue`.

### 3.13 Mesa do recrutado (`RecruitedTableScreen`, ex-*Escolha sua posição*)
**Objetivo:** mostrar a quem **aceitou o convite** (§16.2) a mesa do recrutador, **só para leitura**, sincronizada ao vivo. Não há rotação, confirmação de posição, nome editável nem lista de jogadores próximos.
**De cima para baixo:** título "Mesa de <recrutador>"; a **mesa de lugares** (com os lugares e quem os ocupa: o recrutador, robôs, outros recrutados e chamados com os selos; **o recrutado se vê embaixo**, com a mesa girada para o lugar dele — *decisão provisória*); o **resumo da montagem**: modo, dificuldade dos robôs (estrelas), aposta (com o ícone do saco de dinheiro), pontuação-alvo e o título das regras com o botão **"Ver detalhes da regra"**; embaixo, o botão **Sair da mesa** (secundário) e um aviso "Aguardando o recrutador começar a partida".
**Sincronização:** tudo o que o recrutador muda (lugares, robôs, remoções) aparece na hora; a tela só lê de `Recruited.table` (nunca do estado local do jogador). Enquanto a primeira sincronização não chega, mostra o indicador de espera.
**Sair da mesa:** o lugar volta a VAGO, o recrutador é avisado, e a tela volta a *Iniciar novo jogo* com as **opções do próprio jogador** (o espelho nunca altera a última mesa gravada).
**Avisos:** "<recrutador> tirou você da mesa" (removido), "Saldo insuficiente" (não ocorre com opções fixas; só se a conexão for restabelecida com outra aposta), *Mesa encerrada* (o recrutador voltou, saiu ou desligou o Wi-Fi, ou a conexão não voltou em 8 s). Depois de **Começar Partida**, se o recrutado não chega à mesa do jogo, o modal *Mesa encerrada* traz a causa: *Mesa não encontrada por perto.*, *Não foi possível conectar à mesa.*, *A mesa não respondeu a tempo.* ou *O lugar passou para um robô porque você não chegou a tempo.*; o botão **Voltar ao início** leva ao Início.
**Início:** quando o recrutador toca em **Começar Partida**, a camada **"Prepare-se! O jogo vai começar!"** aparece sobre a tela da mesa do recrutador, esmaecida, por **no mínimo 1,5 s** (até o jogo começar) e o jogador entra na mesa do jogo (3.8); a camada só sai quando todos os recrutados confirmaram **Pronto** ou viraram robô (até 30 s).
**Regras:** §16.2, §16.3.
**Origem:** o modal **Convite** (Aceitar).
**Tags:** `recruited-table`, `recruited-seat-<n>`, `recruited-summary`, `recruited-rules-details`, `recruited-leave`, `recruited-waiting`, `get-ready`.

### 3.14 Mesa multijogador durante o jogo
**Botão de Wi-Fi (cabeçalho da mesa de jogo, 3.8):** na linha do saco de moedas. Reflete o **Wi-Fi do jogo**.
- **Jogo só com robôs, ligar:** o jogo não muda; o jogador vira **anfitrião** (§16.7) e passa a poder liberar assentos de robô para entrada.
- **Desligar, anfitrião com humanos:** abre o modal **Desconectar a mesa?**; confirmado, todos os humanos remotos viram robôs e o jogo segue no aparelho. **Anfitrião só com robôs:** desliga sem pergunta e o jogo continua normalmente.
- **Desligar, convidado:** abre **Desistir da mesa?** (§16.9); cancelar mantém o Wi-Fi ligado e o jogo.
**Menu de engrenagem, seção Multijogador** (só com o Wi-Fi ligado):
- **Anfitrião:** *Trocar jogador ou robô* (escolhe um assento: humano → robô na hora; robô → abre a vaga e mostra o modal **Chamar jogador**, §16.7, §16.8) e *Entregar a mesa* (o antigo *Repassar a mesa*, §16.10).
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
**Anfitrião (modal de pausa):** "<nome> não está na mesa" e três botões: **Aguardar** (o modal fica em "Aguardando <nome>" e fecha quando ele reingressa), **Colocar um robô** (efeito imediato) e **Abrir vaga** (abre a vaga e o modal **Chamar jogador**; o modal de pausa passa a "Aguardando recrutamento", ainda com **Colocar um robô** e com o botão **Chamar jogador** `mp-pause-call`, que reabre a lista). Se o dono volta antes, a chamada some e o chamado vê "A vaga foi preenchida.".
**Modal Chamar jogador (anfitrião, §16.7):** lista os **jogadores livres por perto** (os que estão na tela *Iniciar novo jogo*), cada um com foto ou inicial e nome (tags `mp-call-<n>`); tocar em um **chama** (a chamada é a aprovação). Com a chamada em andamento o modal mostra "Aguardando <nome> responder…" com o botão **Cancelar chamada** (`mp-call-cancel`); se o chamado recusa, não responde no prazo, tem saldo menor que o débito ou sai do alcance, volta à lista com o aviso do motivo (**Recusa**, 4). Aceita a chamada, o modal fecha e o chamado entra no assento. **Fechar** (`mp-call-close`) fecha o modal; num assento de robô liberado sem chamada pendente, fechar **devolve a vaga ao robô**. Lista vazia: "Ninguém livre por perto. Quem estiver em *Iniciar novo jogo* com o Wi-Fi do jogo ligado aparece aqui." A lista atualiza sozinha (busca no modo ativo enquanto o modal está aberto).
**Convidados:** faixa "Jogo pausado: aguardando <nome>" / "aguardando o anfitrião" / "aguardando recrutamento", atualizada a cada ação do anfitrião; some quando o jogo segue.
**Avisos:** "<nome> saiu da mesa" (queda), "<nome> desistiu", "<nome> foi substituído por <personagem>" (sempre um personagem que não estava na mesa), "<nome> entrou na mesa", "<nome> é o novo anfitrião" (§16.10; no anfitrião provisório, a mesa só segue quando outro jogador reingressa nele), "<nome> voltou a ser convidado" (o provisório que achou o anfitrião original) e, só ao anfitrião, "Sem sucessor: uma queda sua encerra a mesa".
**Regras:** §16.7, §16.8, §16.9, §16.10.
**Tags:** `mp-pause`, `mp-pause-wait`, `mp-pause-robot`, `mp-pause-recruit`, `mp-join-request`, `mp-join-accept`, `mp-join-refuse`, `mp-paused-banner`.

---

### 3.16 Tour guiado
**Objetivo:** apresentar as telas na primeira vez do jogador. Cada tela com tour mostra o seu **uma única vez** (registro por tela, guardado no aparelho); o ícone do tour (letra "i" num círculo, tag `tour-button`) no alto da tela o mostra de novo a qualquer momento, e a chave **Tour guiado** do Perfil (3.6) liga ou desliga a abertura automática (ligar zera o registro de todas).
**Formato:** a tela escurece e um **recorte** destaca o elemento real do passo; por cima do véu, um **balão** de fundo marfim com texto azul-marinho (contraste AA, ≥ 4,5:1) e borda dourada, com texto curto (até ~2 linhas), o **indicador de passo** ("2/6") e os botões **Voltar**, **Próximo** (no último, **Concluir**) e **Pular**. O tour **nunca joga nem executa nada**: tocar fora do balão não faz nada; só os botões do balão funcionam. O leitor de tela lê o texto do passo. Tags: `tour-overlay`, `tour-text`, `tour-step`, `tour-next`, `tour-back`, `tour-skip`.
**Iniciar novo jogo (3.2)** — aparece ao abrir a tela pela primeira vez; ícone ao lado do título (junto do Wi-Fi):
(passos na ordem da tela, de cima para baixo e, na mesma linha, da esquerda para a direita)
1. Ícone do tour: "Toque aqui para rever este tour quando quiser."
2. Botão de Wi-Fi: "Wi-Fi do jogo: ligado, outros jogadores por perto podem te chamar para jogar junto."
3. Lista de recrutadores: "Jogadores por perto que estão montando uma mesa. Eles podem te chamar; você aceita ou recusa."
4. Atualizar: "Procura de novo, por alguns segundos, quem está por perto."
5. Quadro de opções: "Modo, nível dos robôs, aposta, regras e pontuação-alvo. A última escolha fica guardada."
6. Selecionar jogadores: "Pronto? Escolha quem joga com você: robôs ou jogadores por perto."
**Seleção de jogadores (3.3)** — aparece ao abrir a tela pela primeira vez:
1. Ícone do tour: "Toque aqui para rever este tour quando quiser."
2. Mesa de lugares: "A mesa: você fica embaixo. Toque num robô ou jogador e depois num lugar, ou ao contrário; toque em dois lugares para trocar. A dica embaixo da mesa explica cada passo."
3. Lista de robôs: "Os robôs que podem ocupar os lugares. O que já está sentado fica esmaecido. Para tirar alguém da mesa, toque nele e depois num item desta lista."
4. Bloco Multiplayer: "Com o Wi-Fi ligado, você chama jogadores por perto. Eles aparecem aqui; quem você chamou fica com o selo Chamado até responder."
5. Atualizar: "Procura de novo, por alguns segundos, quem está por perto."
6. Completar com robôs: "Preenche os lugares vagos com robôs."
7. Começar Partida: "Fica ativo quando todos os lugares estão preenchidos e ninguém está sendo chamado."
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
10. Mão: "Sua mão. Toque nas cartas para selecionar." (com o booster Green-LED, acrescenta: "as marcadas com ponto verde podem ser jogadas agora.")
11. Barra de ações: "Aqui ficam as ações que valem agora: comprar, baixar, acrescentar e descartar."
**Multijogador:** o tour é só da tela deste aparelho e não pausa o jogo; a vez continua aberta enquanto ele está na tela (não há limite de tempo para jogar, §16).

### 3.17 Voltar à mesa (`RejoinScreen`)
**Objetivo:** reingressar, como jogador comum, na mesa da sessão guardada (§16.5, §16.12), a partir de **Continuar mesa** (3.1).
**Fluxo:**
1. **Wi-Fi do jogo desligado:** primeiro o modal *Ligar o Wi-Fi do jogo?* (4); confirmado, passa pelo modal *Procurar mesas por perto* se faltar permissão; recusar volta ao Início.
2. **Procurando a mesa…:** indicador de espera, o nome do anfitrião e o botão **Cancelar** (tag `rejoin-cancel`). Procura por até **30 s** (a busca é repetida); achada, envia o token e volta ao **mesmo assento**, recebe a vista atual (e o fim da última partida que faltar, §16.5, §16.11) e abre a *Mesa de jogo* (3.8). A aposta retida volta com o assento (§16.9, §14.6).
3. **Falhas** (modal com **OK**, que volta ao Início; a sessão é apagada nos itens que dizem "apaga"): *Mesa não encontrada por perto* (não apaga: dá para tentar de novo; expira em 12 h); *A vaga foi preenchida.* (token recusado: apaga); *Mesa encerrada.* (apaga).
Se o jogo já terminou e o resultado ainda não foi recebido, o jogador recebe só o fim do jogo e o prêmio (§16.11).
**Regras:** §16.5, §16.9, §16.11, §16.12.
**Tags:** `rejoin`, `rejoin-searching`, `rejoin-cancel`, `rejoin-failed`, `rejoin-wifi`, `rejoin-wifi-on`.

### 3.18 Inventário (`InventoryScreen`)
**Objetivo:** ver os itens e marcar até 3 boosters para a próxima partida (§17.1, §17.2).
**Conteúdo:** o **armário** (`booster-stock-v1.png`, 9 nichos) com um **nicho de booster** por item do catálogo, na ordem do catálogo; as **moedas** aparecem como item (saldo), **sem** poder ser marcadas. Tocar num nicho com quantidade marca/desmarca (aro dourado); no máximo 3 marcados (o 4º é recusado com um aviso discreto); quantidade 0: esmaecido e não marca. Texto de apoio: "Escolha até 3 boosters para a próxima partida". A marcação é gravada na hora. Botão **Voltar**.
**Regras:** §17.1, §17.2.
**Tags:** `inventory-screen`, `booster-slot-<id>`, `inventory-back`.

### 3.19 Seletor de boosters (`BoosterSelectorScreen`)
**Objetivo:** escolher os boosters da partida **antes de ver a distribuição** (§17.3). Aparece **antes de cada partida**, depois do *Prepare-se!* na primeira e depois dos pontos nas seguintes, também contra robôs.
**Conteúdo:** a imagem `boosters-selector-v1.png`: "Selecione até três boosters para usar nesse jogo." (no jogo, "nessa partida") e **3 nichos**, **pré-preenchidos** com a marcação do Inventário (sem os de quantidade 0); nicho vazio mostra o "+" tracejado e abre a lista de boosters (os de quantidade 0 esmaecidos, sem seleção); tocar num nicho cheio o esvazia. Botão verde **Seguir ▶** (tag `boosters-go`) com um **contador** discreto do prazo de **30 s**; ao zerar vale o que está selecionado, como Seguir. Seguir debita o custo de cada booster; sem nenhum selecionado, segue sem booster. A mesa não aparece por trás (nada de cartas) e depois do Seguir mostra as animações de distribuição e os descartes já feitos (§16.6).
**Multijogador:** cada humano tem a sua tela. Os que já confirmaram jogam; ao chegar a vez de quem ainda não escolheu, a mesa mostra *Aguardando <nome> escolher os boosters*.
**Regras:** §17.3, §17.4, §16.15.
**Tags:** `boosters-selector`, `booster-pick-<n>`, `boosters-go`, `boosters-timer`.

## 4. Modais

Todos levam o **cabeçalho de marca**.

| Modal | Tipo | Quando abre | Conteúdo |
|---|---|---|---|
| Começar um novo jogo? | mensagem (perigo) | Início, "Novo jogo" com jogo salvo | explica que o jogo salvo vira desistência |
| Voltar ao início? | mensagem (aviso) | voltar durante o jogo | confirma sair |
| Presente do dia | `GDialog` | Início, primeira abertura do dia, se o sorteio (§17.5) acertar | "Você recebeu um presente": **ícone** do booster, **nome** e **quantidade**; botão **Guardar** (o presente já foi creditado ao abrir). Tags `gift-modal`, `gift-ok` |
| Desistir do jogo? | mensagem (perigo) | bandeira (menu de engrenagem) | §13.1; mostra as **apostas em jogo** (aposta de cada jogador, total em jogo, quanto o jogador perde e que deixa de concorrer ao prêmio) e os **pontos** da mesa (placar de cada lado, com os nomes de quem está nele); cancelar volta ao estado exato |
| **Convite** | mensagem | jogador próximo **chamado** por um recrutador (§16.2), em qualquer tela fora de um jogo. **Partida em andamento (§16.7):** o chamado que está em *Iniciar novo jogo* recebe o mesmo modal, com o título "<anfitrião> está te chamando para uma partida em andamento", a linha **Débito** (a aposta cheia, ou "nenhum: você herda a aposta de quem saiu") e a posição do assento; aceitar entra direto na mesa de jogo (sem *Mesa do recrutado* nem "Prepare-se!"). Tags iguais, mais `invite-debit`; quem recusa ou deixa passar o prazo continua em *Iniciar novo jogo* | "<recrutador> está te recrutando"; **aposta** (com o ícone do saco de dinheiro), **tipo** (individual / duplas), **posição** (**Parceiro** se o lugar é o da frente; senão **Adversário**; no individual, **Adversário**) e **título da regra** (Clássica / Tranca Paulista / Personalizada); botões **Detalhes da regra** (abre o modal *Detalhes da regra*), **Recusar** e **Aceitar**. **25 s para responder, com contagem na tela**: uma **barra de progresso que esvazia** com os **segundos restantes** ao lado, atualizada a cada ~100 ms, que **muda de cor nos últimos 5 s**; o leitor de tela anuncia a contagem sem repetir demais (a cada 10 s e aos 5 s). Ao zerar o modal fecha sozinho (sem resposta vale como recusa, motivo "não respondeu a tempo"; **o aceite nunca é automático**); a posição acompanha o recrutador movendo o chamado; some se o recrutador o remover. Saldo menor que a aposta: recusa automática com aviso. Tags `invite`, `invite-details`, `invite-accept`, `invite-refuse`, `invite-countdown` |
| **Detalhes da regra** | diálogo | botão "Ver detalhes da regra" (Iniciar novo jogo, Mesa do recrutado) ou "Detalhes da regra" (Convite) | o nome do perfil de regras e o **resumo** das regras da mesa (§14.2: as diferenças em relação à Clássica) e a pontuação-alvo; **Fechar**. Tags `rules-details-dialog` |
| **Prepare-se!** | camada sobre a tela atual | **Começar Partida** (todos os celulares da mesa) | sobre a própria tela, que fica esmaecida (preto ~50 %), a imagem **"Prepare-se! O jogo vai começar!"** centralizada, entrando com leve escala e fade (sem movimento se as animações do sistema estão desligadas); bloqueia os toques; **no mínimo 1,5 s**, até o jogo começar; não tem botões. Tag `get-ready` |
| **Ligar o Wi-Fi do jogo?** | mensagem (aviso) | **Continuar mesa** com o Wi-Fi do jogo desligado (3.17) | "Para voltar à mesa de <anfitrião> o Wi-Fi do jogo precisa estar ligado."; **Ligar** / **Cancelar**. Tags `rejoin-wifi`, `rejoin-wifi-on` |
| **Desconectar a mesa?** | mensagem (aviso) | anfitrião desliga o botão de Wi-Fi na mesa de jogo com humanos (3.14) | "Os jogadores por perto serão substituídos por robôs e o jogo segue só neste celular."; **Desconectar** / **Cancelar** (no convidado, o botão abre *Desistir da mesa?*) |
| **Recusa** | aviso breve (toast) | chamado recusou, saiu ou ficou fora do alcance (§16.2); chamado do meio do jogo (§16.7) que recusou, não respondeu a tempo, tem saldo menor que o débito ou saiu do alcance | quem recusou e o **motivo** (recusou o convite, não respondeu a tempo, saldo insuficiente, fora do alcance); o lugar volta a VAGO (no meio do jogo, a vaga continua aberta para nova chamada) |
| Recusar a compra? | mensagem | monte acabou sem morto | encerra a partida sem batida |
| Zerar estatísticas? | mensagem | Estatísticas | confirma zerar |
| **Informações do jogo** | diálogo | tocar em "Partida N · alvo X" ou no placar, na mesa de jogo | **Jogo:** regras (nome do perfil e o resumo, o mesmo de *Detalhes da regra*), modo, nível dos robôs, pontuação-alvo, aposta de cada jogador e quanto recebe cada vencedor (§14.6, §16.13); **Lados:** quem joga em cada lado (avatar, nome, "robô" ou "humano"; no multijogador, quem é o anfitrião); **Partida atual:** número e quem começou; **Partidas:** tabela partida a partida (número, total de cada lado e quem bateu ou "sem batida") e o acumulado. Só informação pública (no multijogador, o histórico recebido). Botão **Fechar**. Tags `match-info`, `match-info-rounds`, `match-info-close` |
| Símbolos das jogadas | diálogo | **?** na mesa | legenda de comprar, pegar, baixar, acrescentar, descartar, recusar |
| **Detalhe do jogador** | diálogo | tocar na pílula | imagem de apresentação do robô, bordão, nome e a última jogada (com cartas e 3 vermelhos baixados) |
| Lixo completo | diálogo | ícone na linha do lixo | todas as cartas do lixo |
| **Procurar mesas por perto** | mensagem | ligar o Wi-Fi do jogo no Perfil ou no botão de Wi-Fi de Iniciar novo jogo ou da Seleção de jogadores, ou abrir uma delas com ele ligado e sem permissão | explica, em linguagem simples, as permissões pedidas (Bluetooth, dispositivos próximos ou localização, conforme o Android) e que **nada sai do aparelho e não usa internet**; **Permitir** / **Agora não** (o modo fica desligado) |
| **Versão do jogo diferente** | mensagem (aviso) | entrar em mesa de outra versão | "O anfitrião está com outra versão do jogo (anfitrião: versão N; você: versão M). Atualizem todos os celulares para a mesma versão e criem uma mesa nova."; recusa a entrada |
| **Pedido de entrada** | mensagem | **sem porta na tela** (§16.7): ninguém escolhe uma mesa em andamento; a entrada é pela chamada do anfitrião (modal **Chamar jogador**, 3.15). A mensagem de rede existe, mas nenhuma tela a oferece | — |
| **Jogo pausado** | mensagem | convidados, quando o jogo pausa por queda ou recrutamento (§16.8) | quem caiu e o que o anfitrião está fazendo; fecha sozinho quando o jogo segue |
| **Aguardando <nome>** | diálogo (anfitrião) | chega a vez de quem caiu | três ações: **Aguardar o reingresso**, **Substituir por um robô** e **Recrutar outro jogador** (desativa o que não vale; com o recrutamento ativo vira "Aguardando recrutamento", ainda com **Substituir por robô** e **Chamar jogador**) |
| **Chamar jogador** | diálogo (anfitrião) | **Recrutar outro jogador** na pausa, ou *Trocar jogador ou robô* num assento de robô (§16.7) | lista dos jogadores livres por perto; tocar chama; "Aguardando <nome> responder…" com **Cancelar chamada**; **Fechar** (ver 3.15). Tags `mp-call`, `mp-call-<n>`, `mp-call-cancel`, `mp-call-close` |
| **Entregar a mesa** | diálogo (anfitrião) | seção Multijogador da engrenagem ou ao desistir | lista os humanos disponíveis pela ordem de entrada (nunca robôs); **Passar** / **Cancelar** |
| **Você agora é o anfitrião** | mensagem | sucessor, ao assumir (§16.10) | avisa que a mesa passou para ele |
| **Aguardando os outros jogadores** | mensagem (anfitrião provisório) | sucessor que assumiu e ainda não foi confirmado (§16.10) | explica que a mesa espera alguém reingressar; depois de 30 s (§16.15) mostra o botão **Assumir mesmo assim**, que confirma o cargo e retoma o jogo (*provisório, revisar com produto*) |
| **Desistir da mesa?** | mensagem (perigo) | bandeira (menu de engrenagem) ou Voltar do Android, em mesa multijogador | explica que um robô assume o lugar, que a aposta se perde e que o jogo segue para os demais; no 1×1 entre humanos, que o outro vence; mostra as mesmas **apostas em jogo** e **pontos** de *Desistir do jogo?* (só o que este aparelho já sabe) |
| **Mesa encerrada** | mensagem | sem humano para assumir ou o anfitrião fechou; falha ao entrar na mesa (texto próprio: não encontrada, falha de conexão, sem resposta ou lugar dado a um robô) (quem volta depois que a entrega do resultado acabou, §16.11, também vê esta mensagem; quem está nas telas de resultado não é interrompido) | volta ao início |
| **Jogada não permitida** | diálogo | o jogador toca em Baixar, Acrescentar, Pegar o lixo, Comprar, Recusar ou Descartar e o motor recusa (a recusa vem de `RoundEngine.validate`; a tela não valida por conta própria) | **título curto** (ex.: "Você ainda não pode bater"); **O que aconteceu**; **Regra desta mesa** (cita o nome da regra: Clássica, Tranca Paulista ou Personalizada, e o parâmetro que causa a recusa: canastra limpa para bater, mínimo de cartas por conjunto, coringa, números de grupo, morto que vira monte); **O que fazer**. Usa só o que o jogador já vê (a própria mão, os jogos e os mortos da mesa), nunca cartas ocultas. Casos: ficar sem cartas ou com 1 carta sem poder bater (falta canastra, ou só há canastra suja onde a limpa é exigida), 3 preto travando o lixo, lixo sem as cartas exigidas, lixo vazio, comprar fora de hora, monte acabado, recusar a compra fora de hora, descartar 3 vermelho, selecionar uma carta para descartar e cada erro de formação de conjunto (poucas cartas, 3 em conjunto, coringa demais ou proibido, naipe ou número diferente, número que não forma grupo, sequência que continua outra). Botões **Continuar avisando**, **Avisar discretamente** e **Não avisar mais** (não fecha sozinho; os dois últimos mudam a preferência *Ações impedidas*). Tags `refusal-dialog`, `refusal-keep`, `refusal-subtle`, `refusal-none` |
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
| 3.2 Iniciar novo jogo (**capturas antigas da *Mesas disponíveis*, mantidas como histórico**; novas a fazer) | `02-mesas-disponiveis.png`, `02b-mesas-disponiveis-duplas.png` |
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
| 3.3 / 3.13 Seleção de jogadores e Mesa do recrutado (**a fazer**; a antiga referência *Escolha sua posição* fica como histórico) | `escolha-posicao-multiplayer.png` |

**Sem captura (a fazer depois)** — dependem de situações que o emulador não força por toques:
- 3.9 Animação da batida, 3.10 Pontos da partida, 3.12 Revisão da mesa final e a variação de **vitória** do Fim de jogo: exigem terminar uma partida jogando. Desistir leva direto ao Fim de jogo (derrota), sem passar pela mesa final. Caminho sugerido: adaptar [EndScreensTest.kt](../app/src/androidTest/kotlin/com/gtranca/EndScreensTest.kt), que monta essas telas com dados fixos.
- Modais *Onde baixar?*, *Como usar o descarte?* e *Recusar a compra?*: dependem da mão sorteada (ou do monte acabar sem morto).
- Faixa de troca de 3 vermelho de outro assento e pulso/selo de canastra (animações curtas).
- Variações: mesa com "saldo insuficiente", Perfil com foto, Estatísticas com partidas jogadas.

## 7. O que ainda não está aqui

- Textos exatos de cada mensagem de erro (ver `strings.xml`).
- Telas do multijogador: primeira versão na etapa 6 do `docs/plano-multijogador-local.md` (capturas depois do teste nos celulares). Fora dela: card completo da mesa (anúncio maior) e **Continuar** da sessão multijogador (§16.12, falta o espaço da sessão no `:data`).
- Conferência visual no celular das mudanças de 18–19/10 (ver o status em `docs/plano-nomes-aos-bots.md`).
