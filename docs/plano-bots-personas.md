# Plano: 4 robôs com personalidade (substituem os avatares por profissão)

Status: **aprovado em 2026-10-17** (decisões D1–D9 na seção 8, já respondidas); etapas 1 (especificação), 1b (imagens) 2 (modelo e sorteio) e 3 (estilos dos bots) feitas. Branch de trabalho: `feature/bots-personas`. Nada deste plano foi implementado.
O desenho anterior (16 profissões × 2 gêneros) foi arquivado em [arquivo-avatares/](arquivo-avatares/LEIA-ME.md).

## 1. O que muda, em uma frase

Os jogadores virtuais deixam de ser "pessoas com profissão" e passam a ser **4 robôs**, cada um com visual, estilo de jogo,
nome e três níveis de experiência. A mesma identidade (imagem + nome) aparece em **toda** comunicação do jogo.

## 2. Os 4 perfis (do pedido do usuário)

| # | Código | Idade/porte | Pele e traços | Detalhes | Estilo de jogo |
|---|---|---|---|---|---|
| 1 | `JOVEM` | adolescente | azulada, traços robóticos | fone de ouvido, boné, detalhes juvenis | habilidoso e sagaz |
| 2 | `SOBRIO` | maduro, mais volumoso | azul com detalhes esbranquiçados envelhecidos | adornos dourados, olhos cansados | sóbrio, conservador |
| 3 | `ALTERNATIVO` | braços mais longos, traços arredondados | esverdeada | colar natural marrom, colete marrom-escuro, chapéu de palha | ousado, procura "sentido oculto" em cada descarte |
| 4 | `ATLETA` | adulto musculoso, feições humanizadas | laranja | bandana, regata (academia) | **a definir** (ver D3) |

Três **níveis** por perfil: **Iniciante, Maduro, Sênior**, ligados às dificuldades do `:ai` (Fácil, Médio, Difícil).
Total: 4 × 3 = **12 personagens**. O nível também deve aparecer visualmente (D5).

## 3. Como o jogo passa a funcionar

- **Estilo × nível:** o **nível** define a força (erros e profundidade: `EasyBot`, `MediumBot`, `HardBot`); o **estilo** do perfil
  muda só as preferências (pesos), nunca as regras. Todo bot continua escolhendo só entre `legalActions` e vendo só o `PlayerView`.
- **Sorteio no início do jogo** (determinístico pela semente, como hoje, para o "Continuar" mostrar os mesmos): 1 robô no
  individual, 3 em duplas, **sem repetir perfil** na mesa (4 perfis bastam para 3 bots). O nível vem da dificuldade escolhida (D1).
- **Nome:** cada perfil tem um conjunto de nomes próprio (nome + sobrenome) e um é sorteado no início; vale o jogo todo.
- **Posição na comunicação (duplas):** adversários sempre com a posição em relação ao jogador, ex. "João Alvez (dir)" e
  "Daniel Perez (esq)". Individual: só o nome. Parceiro: ver D4.
- **Comunicação com nome e imagem:** avisos e modais (3 vermelho, baixar, pegar o lixo), indicador de vez, resumo da partida, fim
  de jogo, lista de mesas. Somem as profissões e os textos "Adversário"/"Parceiro" quando houver nome.

## 4. Imagens e animações

Quatro **poses** por personagem, cada uma com animação curta, **cíclica e reciclada em poucos segundos** (2–4 s):

| Pose | Onde aparece | Expressão | Tamanho |
|---|---|---|---|
| Apresentação | painel de seleção (mesas): "vende" o perfil | pose característica | grande |
| Vencedor | fim de jogo/partida | felicidade, vitória | grande |
| Perdedor | fim de jogo/partida | frustração, tristeza | grande |
| Pensador | mesa de jogo | concentração | pequeno (~48–72 dp) |

Total: 12 personagens × 4 poses = **48 imagens**. Claude **não gera imagens**: o usuário gera/encomenda, Claude integra. Para
isso o plano inclui as **fichas de arte** (um arquivo por perfil, com prompts por nível e pose, convenção de nomes e formato).

Animação (D2, recomendado A): **A)** imagem estática + movimento procedural em Compose (respiração/flutuação, brilho dos olhos,
efeitos por pose: confete na vitória, gota/queda de ombros na derrota, engrenagem/pontos no pensador); **B)** WebP animado por
pose (mais fiel, pesa ~15 MB+); **C)** quadros em sequência (4–8 por pose). Sem imagens prontas, o desenvolvimento segue com
**marcadores provisórios** desenhados em `Canvas` (cor do perfil + forma), que as imagens substituem sem mudar o código.

## 5. Mudanças técnicas (resumo)

- `definition.md` **primeiro** (CLAUDE.md): reescrever §14.1 (perfis do jogador virtual = robô, sem gênero/profissão) e acrescentar a
  seção dos personagens (4 perfis, 3 níveis, regra do sorteio sem repetição, sufixo de posição). Estilos de jogo ficam na ficha, não
  nas regras (não alteram regra nenhuma).
- `:ai`: `PlayStyle` (pesos por perfil) aplicado sobre os três bots; `createBot(difficulty, style, random)`.
- `:app`/`game`: `BotProfile` + `BotLevel` + `BotCharacter(profile, level, name)` no lugar de `Profession/Gender/Look` dos
  bots; `PersonaGenerator`/`Tables` passam a sortear personagens; `SeatRole` ganha nome próprio para exibição.
- `:app`/`ui/persona`: novo `CharacterPortrait(character, pose, size)` com as animações; `PersonaAvatar` fica só para o humano (D6).
- Jogo salvo: guarda a semente, então o "Continuar" de jogos **já salvos** mostraria os robôs novos no lugar das pessoas antigas
  (aceitável; confirmar em D7).

## 6. Etapas (um commit por etapa; testes antes de seguir)

1. **Especificação:** `definition.md` + fichas de arte `docs/personagens/<perfil>.md` + estilos de jogo propostos. Commit.
2. **Modelo e sorteio:** `BotCharacter`, nomes por perfil, sorteio sem repetição, sufixo (dir/esq). Testes JVM.
3. **Estilos no `:ai`:** 4 estilos × 3 níveis. Medição no `:sim`; atualizar valores de referência do `GameControllerTest`.
4. **Comunicação:** nomes e imagens em todos os modais, avisos, resumo, fim de jogo, mesas; remover profissões. Testes de texto.
5. **Retratos e animações:** `CharacterPortrait`, 4 poses, marcadores provisórios, painel de seleção, mesa, fim de jogo.
6. **Imagens reais** (quando o usuário entregar): conversão (WebP), mapeamento e conferência. Se atrasarem, o APK sai com os marcadores.
7. **Testes de qualidade e correções** (seção 7) e **APK** `MestreDaCanastra-<hash>-debug.apk` para avaliação.

## 7. Testes

- **Imagens × descrições:** (a) automático: as 48 existem, nome/tamanho/formato certos, peso dentro do orçamento, fundo e
  enquadramento consistentes, **cor dominante da pele por perfil** (azul, azul-claro, verde, laranja) medida por histograma de
  matiz; (b) revisão por Claude: abro cada imagem e confiro contra a checklist da ficha (acessórios, expressão, porte); (c)
  usuário avalia no APK.
- **Habilidades × níveis:** no `:sim`, por estilo e nível: zero jogada ilegal e zero exceção; força cresce Iniciante < Maduro < Sênior
  em cada perfil; os estilos se distinguem em métricas observáveis (ex.: frequência de pegar o lixo, momento da batida, canastras
  sujas × limpas); resultados reproduzíveis pela semente; bots só usam `PlayerView`.
- **Animações:** especificação das animações como dados testáveis (duração do ciclo entre 1 e 4 s, repetição infinita, sem
  recomposição por quadro fora do necessário); o visual final é conferido no APK. `compileDebugAndroidTestKotlin` continua no fluxo.
- **Comunicação:** texto de cada aviso/modal usa o nome do robô e o sufixo (dir/esq) nas duplas; nenhum texto antigo de profissão.
- **Geração:** nomes e perfis sem repetição, determinísticos pela semente, nunca igual ao nome do jogador.

## 8. Decisões (respondidas em 2026-10-17)

| # | Decisão |
|---|---|
| D1 | O nível vem da **dificuldade escolhida** (igual para todos os robôs da mesa); nas mesas de robôs, o do anfitrião. |
| D2 | Animação **procedural** em Compose (imagem estática + movimento e efeitos por pose). |
| D3 | `ATLETA` é **agressivo**: pega o lixo para tirar material do adversário, baixa e bate cedo. |
| D4 | Parceiro aparece **só com o nome** (adversários em duplas levam (dir)/(esq)). |
| D5 | Nível = mesma arte + **insígnia de 1/2/3 estrelas** e sinais de experiência. |
| D6 | Avatar do jogador: **foto** ou **medalhão com inicial e cor**; some a grade de profissões. |
| D7 | Jogos salvos mostram os robôs novos ao continuar (**sem migração**). |
| D8 | Só **nome e sobrenome** variam no sorteio; o visual do personagem é fixo. |
| D9 | Rótulos Iniciante/Maduro/Sênior mantidos. "Maduro" no perfil 2 = **adulto formado, nem jovem nem idoso**. |
