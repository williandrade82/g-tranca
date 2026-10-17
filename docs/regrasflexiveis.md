# Tranca — Regras, variações e matriz de configurações

**Objetivo:** documentar as principais regras e opções de variação da Tranca para orientar a implementação de um jogo digital com perfis configuráveis.

> **Nota importante:** não existe um único regulamento nacional universalmente oficial para todas as mesas de Tranca. As regras variam entre regiões, famílias, plataformas e torneios. Os valores indicados como padrão neste documento são sugestões para um perfil inicial e devem ser validados com os jogadores da comunidade que o jogo pretende atender.

## 1. Origem das variações

A Tranca pertence à família de jogos de cartas relacionada à Canastra/Canasta. A difusão desses jogos pela América do Sul e sua transmissão informal entre grupos contribuíram para o aparecimento de regras locais.

As diferenças normalmente surgem por:
- **Tradição regional:** cada grupo pode preservar regras aprendidas com outras pessoas.
- **Transmissão informal:** regras ensinadas verbalmente podem ser modificadas ao longo do tempo.
- **Adaptação da partida:** algumas mesas preferem jogos rápidos; outras privilegiam partidas mais estratégicas.
- **Regulamentos específicos:** torneios e plataformas definem suas próprias condições de jogo e pontuação.

### Existem quantos regulamentos oficiais?

Não há um número universal de regulamentos oficiais reconhecidos para toda a Tranca brasileira. “Oficial” depende do contexto: pode ser o regulamento de uma competição, de uma plataforma ou de uma comunidade. Por isso, é mais útil representar as diferenças como parâmetros independentes e oferecer perfis pré-configurados.

| Modalidade ou referência | O que representa |
|---|---|
| Tranca tradicional | Conjunto de regras praticado por uma comunidade ou grupo. |
| Tranca de torneio | Regulamento de uma competição específica. |
| Tranca de plataforma | Regras implementadas por um site ou aplicativo. |
| Tranca personalizada | Combinação de regras escolhida pelos jogadores. |
| Buraco e Canastra | Jogos aparentados, mas que não devem ser tratados automaticamente como sinônimos da Tranca. |

---

## 2. Matriz de configurações

As opções abaixo são possibilidades para o software, não uma afirmação de que todas sejam regras comuns ou oficiais. O perfil inicial precisa ser validado com jogadores.

### 2.1 Estrutura e distribuição

| Configuração | Opções possíveis | Padrão inicial sugerido |
|---|---|---|
| Número de jogadores | 2 ou 4 | 4 |
| Formação | Individual ou duplas | Duplas |
| Número de baralhos | 1 ou 2 | 2 |
| Curingões (Jokers) | Incluídos ou excluídos | Excluídos |
| Cartas na mão inicial | 9, 11 ou valor personalizado | 11 |
| Cartas por morto | 11, 13 ou valor personalizado | 11 |
| Quantidade de mortos | 1, 2 ou valor personalizado | 2 |
| Sentido do jogo | Horário ou anti-horário | Configurável |
| Meta de pontuação | 1.000, 1.500, 2.000, 3.000 ou personalizada | 3.000 |
| Duração | Uma mão, melhor de N mãos ou até a meta | Até a meta |

### 2.2 Compra, descarte e formação de jogos

| Configuração | Opções possíveis | Padrão inicial sugerido |
|---|---|---|
| Compra do monte | Uma carta ou quantidade configurável | Uma carta |
| Compra do lixo | Lixo fechado ou aberto | Fechado |
| Condição para pegar o lixo | A carta do topo deve formar jogo novo; pode estender jogo existente; regra personalizada | Exigir aproveitamento válido |
| Compra do lixo com duas cartas na mão | Permitida ou proibida | Permitida se formar jogo válido |
| Formação de sequências | Três ou mais cartas consecutivas do mesmo naipe | Permitida |
| Formação de trincas/lavadeiras | Permitida ou proibida | Permitida |
| Limite de curingas por jogo | Nenhum, um ou quantidade personalizada | Máximo de um |
| Substituição de curinga por carta natural | Permitida ou proibida | Configurável |
| Reorganização de jogos baixados | Permitida, restrita ou proibida | Permitida conforme regras |
| Descarte ao fim do turno | Obrigatório ou não | Obrigatório |
| Turno sem baixar cartas | Permitido ou proibido | Permitido |

**Observação:** a compra do lixo deve ser especificada com cuidado. O regulamento precisa dizer se a carta do topo deve participar de um jogo novo ou se pode ser encaixada em um jogo já baixado.

### 2.3 Regras especiais dos três

| Configuração | Opções possíveis | Padrão inicial sugerido |
|---|---|---|
| 3 preto bloqueia o lixo | Sim ou não | Sim |
| Duração do bloqueio | Até a próxima compra; até ser coberto; regra personalizada | Definir expressamente |
| 3 vermelho vai para a mesa | Automaticamente ou mediante ação | Automaticamente |
| Pontuação do 3 vermelho | Bônus, penalidade, valor fixo ou condição personalizada | Regra especial |
| Condição para pontuar o 3 vermelho positivamente | Ter canastra limpa; ter qualquer canastra; outra condição | Definir no perfil |
| 3 preto mantido na mão ao final | Penalidade, sem penalidade ou valor configurável | Definir expressamente |
| 3 pode formar sequência ou trinca | Sim ou não | Não |

O 3 preto costuma ter função de bloqueio do lixo, enquanto o 3 vermelho recebe tratamento especial de pontuação em diversas versões. O comportamento exato deve ser explicitado no perfil escolhido.

### 2.4 Canastras e batidas

| Configuração | Opções possíveis | Padrão inicial sugerido |
|---|---|---|
| Quantidade mínima para canastra | 7 ou valor personalizado | 7 |
| Canastra limpa | Sem curinga | Sim |
| Canastra suja | Com curinga | Sim |
| Valor da canastra limpa | 200, 300 ou personalizado | 200 |
| Valor da canastra suja | 100, 200 ou personalizado | 100 |
| Canastra suja pode virar limpa | Sim ou não | Não, como hipótese inicial |
| Canastra de Ás a Rei com pontuação especial | Ativada ou desativada | Desativada |
| Canastra de Ás a Ás com pontuação especial | Ativada ou desativada | Desativada |
| Pode pegar o morto sem canastra? | Sim ou não | Definir no perfil |
| Exigência para batida final | Uma canastra limpa; qualquer canastra; quantidade mínima personalizada | Uma limpa |
| Batida direta | Permitida ou proibida | Permitida |
| Batida indireta | Permitida ou proibida | Permitida |
| Parceiro que já pegou o morto pode bater | Sim ou não | Definir no perfil |
| Bater sem descarte final | Permitido ou proibido | Proibido, como hipótese inicial |

**Importante:** pegar o morto, encerrar a mão e encerrar a partida são eventos diferentes. Modele suas condições separadamente.

### 2.5 Pontuação das cartas e da partida

| Configuração | Opções possíveis | Padrão inicial sugerido |
|---|---|---|
| Valor do Ás | 10, 15 ou personalizado | 15 |
| Valor das cartas 4 a 7 | 5, 10 ou personalizado | 5 |
| Valor das cartas 8 a Rei | 10 ou personalizado | 10 |
| Valor do 2/curinga | 10 ou personalizado | 10 |
| Valor do 3 vermelho | Regra especial, valor fixo ou personalizado | Regra dos três |
| Bônus por batida | 50, 100 ou personalizado; nenhum | 100 |
| Penalidade por não pegar o morto | -100, outro valor ou nenhuma | -100 |
| Cartas restantes na mão | Descontar valores ou aplicar penalidade fixa | Descontar valores |
| Bônus de canastra limpa | Valor configurável | 200 |
| Bônus de canastra suja | Valor configurável | 100 |
| Meta para vencer | Valor configurável | 3.000 |
| Empate ao atingir a meta | Empate permitido, desempate ou jogar outra mão | Jogar outra mão |
| Pontuação negativa | Permitida ou limitada a zero | Permitida |

Os valores acima são sugestões de configuração, não um regulamento universal. O perfil deve definir de forma inequívoca o valor de cada carta, bônus e penalidade.

### 2.6 Situações especiais e procedimentos

| Configuração | Opções possíveis | Padrão inicial sugerido |
|---|---|---|
| Compra da última carta do monte | Turno normal ou encerramento imediato | Turno normal |
| Monte acabou e há lixo | Permitir pegar lixo ou encerrar mão | Definir no perfil |
| Morto indisponível | Encerrar ou continuar conforme regras | Definir no perfil |
| 3 vermelho esquecido na mão | Penalidade, desconto automático ou tolerância | Penalidade definida |
| Jogador esquece de baixar 3 vermelho | Permitir na próxima rodada, penalizar ou regra específica | Definir no perfil |
| Jogada inválida | Impedir ação, desfazer ou aplicar penalidade | Impedir ação |
| Desconexão online | Pausar, aguardar reconexão ou bot | Aguardar reconexão |
| Tempo máximo por turno | Sem limite, 30 s, 60 s ou personalizado | 60 s em partidas online |
| Desistência | Derrota, cancelamento ou substituição por bot | Definir por modo |

---

## 3. Perfis pré-configurados sugeridos

### Perfil A — Tranca tradicional
- Dois baralhos sem curingões.
- Quatro jogadores em duplas.
- 11 cartas por jogador e dois mortos de 11 cartas.
- Lixo fechado.
- Sequências e trincas permitidas.
- 3 preto bloqueador e 3 vermelho com pontuação especial.
- Pontuação e condição de batida definidas por um regulamento documentado.

> Este perfil é apenas uma proposta inicial. Valide cada detalhe com os jogadores que representam a variante desejada.

### Perfil B — Competitivo
- Regras e pontuação estritamente definidas antes da partida.
- Controle de tempo e tratamento de jogadas inválidas.
- Registro das ações e pontuação.
- Regulamento selecionado por competição, sem presumir que exista um padrão universal.

### Perfil C — Familiar
- Meta de pontos, distribuição e regras especiais ajustáveis.
- Possibilidade de salvar regras usadas por uma família ou grupo de amigos.
- Interface simples para configurar as opções mais frequentes.

### Perfil D — Personalizado
- Todos os parâmetros editáveis.
- Salvar, duplicar, exportar e compartilhar perfis.
- Validar combinações incompatíveis antes de iniciar uma partida.

---

## 4. Modelo de configuração JSON

O exemplo abaixo demonstra como persistir um perfil. É um esboço de arquitetura, não um regulamento completo pronto para produção.

```json
{
  "profileId": "tranca_tradicional",
  "profileName": "Tranca tradicional",
  "version": 1,
  "players": {
    "count": 4,
    "mode": "teams"
  },
  "deck": {
    "numberOfDecks": 2,
    "includeJokers": false,
    "initialHandSize": 11,
    "deadPileSize": 11,
    "deadPileCount": 2
  },
  "draw": {
    "discardPileMode": "closed",
    "requireTopCardToFormMeld": true,
    "allowPickupToExtendMeld": true
  },
  "melds": {
    "allowSequences": true,
    "allowSets": true,
    "minimumSequenceSize": 3,
    "minimumMeldSize": 3,
    "minimumCanastaSize": 7,
    "maxWildcardsPerMeld": 1,
    "allowDirtyToClean": false
  },
  "specialCards": {
    "blackThreeBlocksDiscardPile": true,
    "redThreeMode": "conditional_bonus",
    "redThreeBonus": 100,
    "redThreePenalty": -100
  },
  "ending": {
    "requireDeadPile": true,
    "requireCleanCanasta": true,
    "allowDirtyCanastaToFinish": false,
    "targetScore": 3000
  },
  "scoring": {
    "cleanCanasta": 200,
    "dirtyCanasta": 100,
    "goingOutBonus": 100,
    "uncollectedDeadPilePenalty": -100,
    "allowNegativeScore": true
  }
}
```

Esse modelo precisará de campos adicionais para valores individuais das cartas, condições completas dos três vermelhos, esgotamento do monte e demais exceções identificadas durante a implementação.

---

## 5. Organização recomendada no Flutter/Dart

Separe as configurações em objetos de domínio independentes:

- `GameProfile`: identidade, nome, versão e configuração geral.
- `DeckRules`: baralhos, curingões e distribuição.
- `DrawRules`: compra do monte e do lixo.
- `MeldRules`: sequências, trincas e canastras.
- `SpecialCardRules`: comportamento dos três e outras cartas especiais.
- `EndingRules`: morto, batidas e encerramento.
- `ScoringRules`: valores das cartas, bônus e penalidades.
- `TurnRules`: ordem, descarte, tempo e ações inválidas.

A lógica do jogo deve consultar esses objetos em vez de espalhar regras fixas pelas telas e funções. Por exemplo, a condição de canastra limpa para bater deve ser validada por um mecanismo central de regras.

### Versionamento
- Cada perfil deve possuir um identificador estável e um número de versão.
- Uma partida iniciada deve guardar uma cópia imutável das regras utilizadas.
- Atualizações futuras não devem alterar retroativamente partidas em andamento.
- Perfis importados precisam ser validados antes de serem aceitos.

---

## 6. Prioridades para o MVP

1. Número de jogadores e formação das equipes.
2. Compra do lixo e bloqueio.
3. Sequências e trincas.
4. Uso de curingas.
5. Regras dos três pretos e vermelhos.
6. Canastras e condições de batida.
7. Valores das cartas, bônus, penalidades e meta.
8. Retirada do morto e fim do monte.
9. Regras online: tempo, reconexão, abandono e jogadas inválidas.

## 7. Recomendação final

O diferencial do jogo será permitir que uma mesa reproduza fielmente o conjunto de regras que seus jogadores consideram correto.

Comece com um perfil-base documentado, mantenha as regras configuráveis de forma independente e permita salvar combinações personalizadas. Para regras interdependentes, valide a consistência antes de iniciar a partida — por exemplo, não permitir exigir canastra limpa para bater e, ao mesmo tempo, proibir sua formação.

Assim, será possível acomodar variantes regionais, regras familiares e regulamentos de torneios sem reescrever o motor do jogo a cada mudança.
