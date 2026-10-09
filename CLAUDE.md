# G-Tranca

Nome público do app: **Mestre da Canastra** (`app_name`), subtítulo **Buraco & Biriba** (`home_title_tagline`). `applicationId` = `br.com.funnyandplay.mestredacanastra` (definitivo); o código continua no pacote `com.gtranca` e "G-Tranca" é o nome interno.

Jogo de Tranca (família Canastra) para Android, 100% offline: humano contra a máquina (individual) ou humano + parceiro virtual contra dupla virtual (duplas).

## Fonte da verdade

- **[definition.md](definition.md)** é a especificação das regras. Nunca invente regra: se algo não estiver lá, pare e pergunte ao usuário.
- Toda regra implementada tem teste que cita a seção, ex.: `// §5.3 3 preto no topo trava o lixo`.
- Se uma regra mudar, atualize `definition.md` **antes** do código.

## Stack

- Kotlin, Jetpack Compose, Coroutines/Flow, kotlinx.serialization
- Testes: JUnit 5 + Kotest (assertions e property-based testing)
- Build: Gradle (Kotlin DSL) com version catalog (`gradle/libs.versions.toml`)
- JDK: o Gradle roda com o **JDK 21** (`JAVA_HOME`); o código é compilado para **Java 17** via `jvmToolchain(17)` (o Gradle detecta o `jdk-17` instalado). No Windows use `.\gradlew.bat`.
- Android: SDK em `%LOCALAPPDATA%\Android\Sdk` (`ANDROID_HOME`), `compileSdk`/`targetSdk` 36+, build-tools 36.

## Módulos

| Módulo | Tipo | Responsabilidade |
|---|---|---|
| `:core-engine` | Kotlin/JVM puro | Modelos, `RuleSet`, máquina de estados, validação, pontuação. **Sem dependência de Android.** |
| `:ai` | Kotlin/JVM puro | Jogadores virtuais (`BotPlayer`) por dificuldade. Depende só de `:core-engine`. |
| `:sim` | Kotlin/JVM (CLI) | Simulação headless bot × bot para achar estados ilegais e medir força dos bots. |
| `:data` | Android library | DataStore (configurações), persistência da partida salva e estatísticas. |
| `:app` | Android app | Telas Compose, ViewModels, `GameController`. |

Regra de dependência: `app → data, ai, core-engine`; `ai → core-engine`; `sim → ai, core-engine`. O `core-engine` não depende de nada do projeto.

## Princípios do motor

- Estado imutável; transição pura: `fun apply(state: GameState, action: Action): GameState`.
- `legalActions(state, seat)` é a **única** fonte de jogadas válidas. UI e bots nunca validam por conta própria.
- Aleatoriedade só via RNG com semente injetada (partidas reproduzíveis).
- Bots recebem um `PlayerView` (informação visível ao jogador: própria mão, lixo aberto, mesa, tamanhos de mãos/monte/mortos). **Nunca** o `GameState` completo.

## Glossário (PT → código)

| Regra (PT) | Código |
|---|---|
| Jogo (até a pontuação-alvo) | `Match` |
| Partida | `Round` |
| Lado (jogador ou dupla) | `Side` |
| Assento/jogador | `Seat` |
| Monte | `Stock` |
| Lixo | `DiscardPile` |
| Morto | `Morto` |
| Conjunto | `Meld` |
| Sequência / Grupo | `Sequence` / `Group` |
| Canastra limpa / suja | `Canasta` (`isClean`) |
| Coringa | `Wild` (o 2) |
| 3 vermelho / 3 preto | `RedThree` / `BlackThree` |
| Batida | `GoOut` |
| Pontuação-alvo | `targetScore` |

Identificadores em inglês; textos da interface em português (pt-BR) em `strings.xml`.

## Comandos

```bash
./gradlew.bat :core-engine:test        # testes do motor (rápidos, rodar sempre)
./gradlew.bat test                     # todos os testes JVM
./gradlew.bat :app:assembleDebug       # APK debug
./gradlew.bat :sim:run --args="--games 1000 --seed 42"
```

## Agentes do projeto (`.claude/agents/`)

- `rules-engine-dev`: implementa o `:core-engine` com TDD.
- `bot-ai-dev`: implementa os jogadores virtuais no `:ai`.
- `android-ui-dev`: telas Compose, ViewModels e `:data`.
- `rules-auditor`: revisão somente leitura do código contra `definition.md`.
