# Plano: revisão visual "luxo noturno de cassino" (app inteiro)

Status: **executado** (commits c724e5b, 7930e56, e02bb30, 90a7655) — falta só validar no celular. Mão em leque não aplicada (ver nota no fim). Os avatares têm plano próprio em [plano-revisao-avatares.md](plano-revisao-avatares.md).

## Escopo e restrições

- Só aparência. **Não** alterar regras, pontuação, fluxo de turnos, mecânicas, `legalActions` nem a ordem das ações (descarte sempre por último na tela).
- Nenhuma animação pode bloquear toques ou escolher jogadas sozinha; respeitar "remover animações" do Android.
- Não criar funções novas (ex.: abas Moldura/Insígnia das referências ficam de fora).
- Tudo offline: fontes em `res/font`, ornamentos vetoriais desenhados em Compose (sem imagens pesadas).

## Referências do usuário

1. **Artofit (UI roxa escura):** fundo azul-arroxeado, cartões bem arredondados, chips, ícones de linha fina, barra inferior com botão de destaque.
2. **Slots (vecteezy):** moldura com luzes, contadores em cápsula com ícone, botão principal grande e brilhante, raios de luz. Evitar saturação excessiva e fontes cartoon.
3. **Sueca Card Game UI Kit:** gradiente royal → índigo, avatares em medalhões com aro brilhante, faixas Vencedor/Derrota, mesa oval com moldura, cartões de modo com ilustração. Evitar explosões na derrota.

Direção escolhida: base índigo/meia-noite + ouro brilhante; feltro esmeralda apenas na mesa.

## 1. Paleta (substituir valores em `ui/theme/Color.kt` → `GColors`)

| Papel | Hex | Uso |
|---|---|---|
| Fundo meia-noite | `#0B1026` | Fundo das telas |
| Índigo profundo | `#1A1F4B` | Painéis/cartões |
| Royal (gradiente) | `#2A3290` → `#4A2A8C` | Fundo Início e Fim de jogo |
| Ametista suave | `#6C5BD4` | Chips, seleção secundária, foco |
| Ouro brilhante | `#F5C542` | Bordas, títulos, botão principal |
| Ouro profundo | `#B8862B` | Sombra/bisel do metal |
| Champanhe | `#FFE9A8` | Reflexos e brilho |
| Feltro da mesa | `#0F4A3A` → `#08291F` | Só a mesa (oval com moldura dourada) |
| Bordô | `#7A1630` | Verso das cartas, derrota, alerta |
| Rubi | `#D23A4F` | Naipe vermelho / erro |
| Esmeralda viva | `#2FBF84` | Sucesso, canastra limpa (com ícone) |
| Marfim | `#F5EFE3` | Texto principal, face das cartas |
| Lavanda acinzentada | `#9A9CC4` | Texto secundário |

Carta selecionada: trocar o azul atual por borda ouro + leve elevação. Ouro só em títulos/números grandes (contraste).

## 2. Tipografia (`ui/theme/Type.kt`)

- Títulos/logotipo: **Cinzel** (texto com gradiente dourado + contorno escuro fino).
- Interface: **Poppins** ou **Nunito Sans** (Medium/SemiBold).
- Números: Poppins SemiBold com algarismos tabulares, dentro das cápsulas.
- Índices das cartas: **Playfair Display** Bold.

## 3. Componentes (`ui/theme/Components.kt`)

- **Fundo:** gradiente meia-noite → royal, raios suaves (5–8%) e partículas douradas estáticas.
- **Painel:** índigo 85%, cantos 20dp, borda 1dp ouro 40%, sombra arroxeada.
- **Moldura de destaque:** borda dourada dupla com bisel (Ouro profundo → Champanhe) e pontos de luz nas quinas — só mesa e faixas de vitória.
- **Botão principal:** cápsula ouro com bisel, texto escuro em versalete, brilho que varre periodicamente.
- **Botão secundário:** cápsula índigo com borda ametista.
- **Cápsula contadora:** ícone dourado + número, pílula escura com borda dourada (monte, mortos, cartas dos oponentes, placar).
- **Medalhão de avatar:** aro dourado biselado 3dp, filete champanhe interno; jogador da vez com aro pulsante.
- **Verso das cartas:** bordô com losango dourado e monograma "MC" (era "TF" antes da troca de nome).

## 4. Telas

- **Início:** logotipo Cinzel com coroa/naipes; cartões grandes por modo (Individual/Duplas); dificuldade e pontuação-alvo em chips; "Jogar/Continuar" em ouro; barra inferior com Perfil, Estatísticas, Som.
- **Perfil:** ver plano de avatares (layout da tela fica lá).
- **Estatísticas:** cartões índigo, número grande em ouro, anel de progresso para vitórias/derrotas; gráfico de barras só se os dados já existirem.
- **Mesa:** feltro oval com moldura dourada sobre o fundo meia-noite; jogadores em medalhões com chip de nome e cápsula de cartas; monte, lixo e mortos no centro com cápsulas; jogos Nós/Eles em faixas translúcidas; canastras com selo-ícone (limpa/suja, não só cor); mão em leque; barra de ações em cápsulas com descarte por último. Brilhos/raios < 10% de opacidade.
- **Anúncio:** painel central com moldura biselada, título curto em Cinzel.
- **Resumo da partida / Detalhe da pontuação:** painel índigo, linhas alinhadas, números tabulares, total em cápsula ouro, lados lado a lado com medalhões.
- **Fim de jogo:** vitória com faixa "VITÓRIA" ouro, louros e coroa, raios girando lentamente, medalhões dos vencedores; derrota com faixa bordô "DERROTA", sóbria, sem explosões.

## 5. Animações

| Momento | Efeito |
|---|---|
| Início | Brilho percorre o logotipo; raios giram muito devagar |
| Toque em botão | Afunda 3% + reflexo dourado (120 ms) |
| Compra de carta | Voa do monte à mão em curva, vira no meio (~400 ms) |
| Contador muda | Número "rola" (200 ms) |
| Vez do jogador | Aro do medalhão respira em ouro |
| Canastra fechada | Selo com flash curto + partículas mínimas |
| Troca de tela | Fade com véu dourado |
| Vitória | Faixa desce com balanço, raios, confete dourado fino (2–3 s) |

## 6. Ícones

Linha 1,75dp; dourado ativo, lavanda inativo (`ui/game/ActionSymbols.kt`): baixar, descartar, pegar lixo, comprar, menu, som, perfil, estatísticas. Ornamentos vetoriais: coroa, louros, naipes vazados, losangos.

## 7. Usabilidade

- Contraste marfim/meia-noite > 12:1.
- Cor nunca é o único sinal (canastra, carta jogável, vez do jogador têm ícone/forma).
- Alvos de toque ≥ 48dp.

## Etapas de execução (um commit por etapa)

1. Paleta + fontes (`Color.kt`, `Type.kt`, `Theme.kt`, `res/font`).
2. Componentes (fundo, painel, botões, cápsulas, medalhão, verso da carta).
3. Mesa (`TableScreen`, `GameScreen`, `PlayingCard`, `TableAnimations`).
4. Demais telas (Início, Estatísticas, Anúncio, Resumo, Pontuação, Fim de jogo) + animação de vitória.
5. Rodar `./gradlew.bat test` e `:app:assembleDebug`; gerar APK com hash do commit para teste no celular.

## Nota da execução

- **Mão em leque** não foi aplicada: a mão quebra em várias linhas (até 3) com cartas de 48dp; girar as cartas em leque reduziria a área de toque e embaralharia as linhas. Mantida em grade, com a carta selecionada em borda ouro e elevação.
- **Perfil** não mexido (fica para o plano de avatares), só as cores de texto/seleção para o tema escuro.
