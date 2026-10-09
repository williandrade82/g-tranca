# Plano: redesenho dos avatares e da tela de Perfil

Status: **pendente** — executar quando o usuário pedir. Depende da paleta/componentes do [plano-revisao-visual-luxo.md](plano-revisao-visual-luxo.md) (se ainda não feito, aplicar ao menos a paleta e o medalhão).

## Objetivo

Trocar os avatares de estética infantil (desenhados em `Canvas` em `ui/persona/PersonaAvatar.kt`) por retratos **3D semi-realistas**, adultos e elegantes, com acabamento de jogo premium (referência de acabamento: Royal Match — render, luz e moldura; **não** o traço cartoon).

## Restrições

- Manter a estrutura da §14.1 de `definition.md`: avatar = profissão × gênero, com uniforme e acessório próprios, e o nome da profissão sob o avatar.
- 16 profissões × 2 gêneros = **32 retratos**: DOCTOR, CHEF, ENGINEER, TEACHER, FIREFIGHTER, SCIENTIST, PILOT, ARTIST, NURSE, POLICE, FARMER, MECHANIC, MUSICIAN, LAWYER, PHOTOGRAPHER, SAILOR.
- Sem funções novas (as abas Moldura/Nome/Insígnia da referência não entram).
- Claude não gera imagens: o usuário gera/encomenda as 32 imagens; Claude integra.

## Direção de arte (para quem gerar as imagens)

- Busto, frente ou 3/4, rosto ~45% do quadro; adultos 25–60 anos, proporções reais, expressão serena e confiante.
- Luz de estúdio em três pontos (principal dourada quente, recorte suave), texturas de pele/tecido/metal refinadas.
- Fundo liso em gradiente `#0B1026` → `#2A3290` com halo dourado suave; sem cenário (legível em 48dp).
- Uniformes "premium" com detalhes dourados, mantendo o acessório atual de cada profissão.
- Diversidade de tons de pele, cabelos e idades.
- Entrega: 1024×1024 px, WebP qualidade 90 (o app recorta em círculo e aplica moldura).

Prompt-modelo:

> Semi-realistic 3D character portrait, bust shot, [female/male] [profession] aged [30–50], elegant [uniform description] with gold details, holding/wearing [accessory], calm confident expression, subtle smile, realistic human proportions, studio three-point lighting with warm golden key light, soft rim light, refined skin and fabric textures, plain deep midnight-blue to indigo gradient background with soft gold halo, premium mobile game avatar, high detail, centered, square

Primeiro passo ao executar: gerar a lista dos 32 prompts prontos (um por profissão e gênero, lendo uniforme/acessório atuais em `PersonaAvatar.kt`).

## Integração no app (Claude)

1. Reduzir as imagens para 512 px e colocar em `app/src/main/res/drawable-nodpi/avatar_<profession>_<gender>.webp` (~1,5–3 MB no total).
2. `PersonaAvatar`: exibir a imagem recortada em círculo dentro do medalhão dourado (aro biselado 3dp + filete champanhe + sombra); se a imagem faltar, usar o desenho em `Canvas` atual como reserva.
3. Mesmo avatar nos medalhões da Mesa, Resumo e Fim de jogo.

## Tela de Perfil (layout inspirado na referência, paleta luxo)

- Faixa de título "Perfil" em Cinzel dourado; botão fechar circular com aro dourado.
- Cartão superior: avatar grande + campo de nome editável.
- Painel índigo com grade de avatares em 3 colunas, nome da profissão em chip abaixo de cada um.
- Selecionado: aro mais brilhante com brilho girando lentamente e selo dourado com ✓.
- Botão "Salvar" em cápsula ouro.

## Etapas de execução (um commit por etapa)

1. Lista dos 32 prompts (entregar ao usuário).
2. Após receber as imagens: integração + reserva em `Canvas`.
3. Redesenho da tela de Perfil.
4. Testes, `:app:assembleDebug`, APK com hash do commit para teste no celular.
