# Tranca — Regras, variações e matriz de configurações

**Objetivo:** documentar as principais regras e opções de variação da Tranca para orientar a implementação de um jogo digital com perfis configuráveis.

> **Status (2026-11-02):** as seções 1 e 2 são um catálogo de referência sobre variações *possíveis* da
> Tranca — nem todas foram implementadas. As seções 3 a 5 foram reescritas para descrever **o que existe
> de fato** no motor (`:core-engine`), substituindo uma versão anterior especulativa (que sugeria Flutter/Dart
> e perfis que nunca chegaram a ser construídos). Fonte normativa dos parâmetros: [`definition.md`](../definition.md)
> §14; implementação: `RuleSet.kt`.

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

**O que virou parâmetro real:** só uma parte desta matriz foi implementada como parâmetro de `RuleSet`
(§14.2) — `groupRanks`, `mortoBecomesStock`, `goOutRequiresCleanCanasta` e `handBlackThreePoints`. O
restante das linhas abaixo é regra fixa no motor (vale igual em qualquer perfil) ou ainda não foi
implementado. Ver a seção 3 para o que está disponível hoje.

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

## 3. Perfis implementados (`RuleSet`, §14.3)

Um perfil é uma instância de `RuleSet`: identidade (`id`, `name`, `version`, `baseId`) + um valor para
cada parâmetro de regra (§14.2) + as regras fixas de pontuação e distribuição (iguais em qualquer
perfil, mas reunidas na mesma classe por conveniência). Não existem "categorias" de perfil
(tradicional/competitivo/familiar) — existem **perfis da casa** (prontos, sorteados nas mesas dos
robôs) e **perfis personalizados** (cópia de qualquer perfil, com nome e parâmetros alterados).

### Perfis da casa (`RuleSet.HOUSE`)

| Parâmetro | Clássica (`CLASSICA`, base) | Tranca Paulista (`PAULISTA`) |
|---|---|---|
| `groupRanks` — números que formam grupo (§6.1) | qualquer número (4 a A) | só A, K, Q, J |
| `mortoBecomesStock` — morto vira monte quando o monte acaba (§10) | sim | **não** |
| `goOutRequiresCleanCanasta` — bater exige canastra limpa (§11.1) | não (qualquer canastra) | **sim** |
| `handBlackThreePoints` — 3 preto na mão no fim (§12.2) | −5 | **−100** |
| `maxWildsPerMeld`, `targetScore`, pontuação de canastras etc. | iguais nos dois perfis hoje | iguais nos dois perfis hoje |

`RuleSet.DEFAULT` é `CLASSICA`. Um perfil novo é `CLASSICA.copy(...)` ou `PAULISTA.copy(...)` com os
campos que mudam — não há um perfil "Competitivo" ou "Familiar" prontos; a ideia de categorias largas
da versão anterior deste documento não foi implementada.

### Perfis personalizados

O jogador duplica qualquer perfil (`RuleSet.duplicate(id, name)`), que copia todos os valores e marca
`baseId` = o perfil de origem. A tela mostra o que difere da base. A mesa/partida guarda uma cópia
completa e imutável do `RuleSet` usado (§14.4): alterar um perfil depois não afeta jogos já iniciados.

---

## 4. Estrutura real do perfil (`RuleSet.kt`)

Não existe um esquema JSON de configuração separado do código: `RuleSet` é `@Serializable`
(kotlinx.serialization) e **é** a representação persistida. Abaixo, os campos reais (não um esboço):

```kotlin
data class RuleSet(
    val id: String = "classica",
    val name: String = "Clássica",
    val version: Int = 1,
    val baseId: String? = null,          // perfil de origem; null na base
    val groupRanks: GroupRanks = GroupRanks.ANY,              // §6.1 / §14.2
    val mortoBecomesStock: Boolean = true,                    // §10 / §14.2
    val goOutRequiresCleanCanasta: Boolean = false,           // §11.1 / §14.2
    val cardsPerHand: Int = 11,                               // §3.1
    val cardsPerMorto: Int = 11,                              // §3.2
    val mortoCount: Int = 2,                                  // §3.2
    val minMeldSize: Int = 3,                                 // §6.2
    val minCanastaSize: Int = 7,                              // §7.1
    val maxWildsPerMeld: Int = 1,                             // §6.3 / §14.2
    val redThreePoints: Int = 100,                            // §12.1
    val redThreeWithoutCanastaPoints: Int = -100,             // §12.1
    val cleanCanastaPoints: Int = 200,                        // §7.2 / §12.1 / §14.2
    val dirtyCanastaPoints: Int = 100,                        // §7.2 / §12.1 / §14.2
    val goOutPoints: Int = 100,                               // §12.1
    val tableRedThreePoints: Int = 5,                         // §12.1
    val tableFourToTenPoints: Int = 8,                        // §12.1
    val tableFaceCardOrAcePoints: Int = 10,                   // §12.1
    val tableWildPoints: Int = 10,                            // §12.1
    val mortoNotTakenPoints: Int = -100,                      // §12.2
    val handRedThreePoints: Int = -5,                         // §12.2
    val handBlackThreePoints: Int = -5,                       // §12.2 / §14.2
    val handFourToTenPoints: Int = -8,                        // §12.2
    val handFaceCardOrAcePoints: Int = -10,                   // §12.2
    val handWildPoints: Int = -10,                            // §12.2
    val targetScore: Int = 3000,                              // §13 / §14.2
)
```

`GroupRanks` é um enum (`ANY`, `FACE_AND_ACE`, `NONE`). `maxWildsPerMeld` só aceita 0 ou 1; validações
de faixa (`cleanCanastaPoints >= 0`, `targetScore > 0` etc.) ficam no bloco `init` da classe.

---

## 5. Organização no motor (Kotlin)

A versão anterior deste documento sugeria separar as configurações em várias classes de domínio
(`DeckRules`, `DrawRules`, `MeldRules`...) num app Flutter/Dart. Na implementação real (Kotlin,
`:core-engine`), optou-se por **uma única `data class` imutável** (`RuleSet`, seção 4) em vez de vários
objetos — mais simples de serializar, copiar (`copy()`) e comparar entre perfis, sem perder a
rastreabilidade por seção (`§`) em cada campo (ver os comentários no código).

- A lógica do jogo (`RoundEngine`, `legalActions`) lê os campos de `RuleSet` em vez de ter regras
  fixas espalhadas; é a única fonte de jogadas válidas (ver `CLAUDE.md`, "Princípios do motor").
- Versionamento: `id` + `version` identificam o perfil; a partida guarda a cópia do `RuleSet` com que
  começou (§14.4), então mudar um perfil não afeta jogos já em andamento.
- Bots leem o mesmo `RuleSet` da mesa (ex.: o bot sabe que o 3 preto pesa −100 na Paulista).

---

## 6. Status

A lista de prioridades do MVP (número de jogadores, compra do lixo, sequências/trincas, curingas,
regras dos três, canastras e batida, pontuação, morto e monte) **foi entregue** — é o que está em
`definition.md` §1 a §13 e os parâmetros variáveis de §14. O que ainda não tem parâmetro de regra
são as regras online (tempo de turno, reconexão, abandono, jogadas inválidas), tratadas em
[plano-online.md](plano-online.md) e `definition.md` §18, não aqui.

## 7. Recomendação final

O diferencial do jogo será permitir que uma mesa reproduza fielmente o conjunto de regras que seus jogadores consideram correto.

Comece com um perfil-base documentado, mantenha as regras configuráveis de forma independente e permita salvar combinações personalizadas. Para regras interdependentes, valide a consistência antes de iniciar a partida — por exemplo, não permitir exigir canastra limpa para bater e, ao mesmo tempo, proibir sua formação.

Assim, será possível acomodar variantes regionais, regras familiares e regulamentos de torneios sem reescrever o motor do jogo a cada mudança.
