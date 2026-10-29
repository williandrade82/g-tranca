# Tranca-Bot (G-Tranca)

**Tranca-Bot — um jogo de tranca robotizado pra jogar entre família.** Identificador na loja: `br.com.funnyandplay.trancabot`. "G-Tranca" é o nome interno do projeto (pacote do código `com.gtranca`, módulos).

Jogo de **Tranca** (família Canastra) para Android. O jogo contra os robôs funciona 100% offline.

## O que o jogo oferece

- **Individual:** você contra um jogador virtual. **Duplas:** você e um parceiro virtual contra uma dupla virtual.
- **Robôs com personalidade:** quatro perfis em três níveis de dificuldade (fácil, médio e difícil), sorteados ao formar a mesa, cada um com nome, avatar e poses.
- **Regras flexíveis por mesa:** pontuação-alvo, 3 vermelhos, perfis de regras (definition.md §14).
- **Moedas e apostas locais**, inventário, **loja de boosters** e presente diário (§14.6, §17).
- **Multijogador local:** até 4 jogadores em celulares próximos, sem internet (Nearby), com anfitrião, reconexão e sucessão (§16).
- **Jogo online (em construção):** mesas por convite em servidor dedicado, partida autoritativa e conta Google. É um adicional: não altera o jogo offline nem o multijogador local (§18, [docs/plano-online.md](docs/plano-online.md)).

## Regras

As regras completas estão em [definition.md](definition.md), a especificação que o código segue. Cada regra implementada tem um teste que cita a sua seção; o mapa entre regras e testes está em [docs/rules-traceability.md](docs/rules-traceability.md). As telas e os componentes estão descritos em [docs/telas.md](docs/telas.md).

## Módulos

| Módulo | Responsabilidade |
|---|---|
| `:core-engine` | Motor de regras em Kotlin puro: modelos, máquina de estados, validação das jogadas, pontuação, moedas e loja. |
| `:ai` | Jogadores virtuais por dificuldade. O difícil usa busca em árvore Monte Carlo (ISMCTS). |
| `:sim` | Simulação bot × bot pela linha de comando, para achar estados ilegais e medir a força dos bots. |
| `:net` | Multijogador local: protocolo versionado, sessão (anfitrião/convidado) e transportes. |
| `:data` | Configurações, jogo salvo, estatísticas, carteira, inventário e loja (DataStore). |
| `:app` | Interface em Jetpack Compose. |
| `online/` | Backend do jogo online em microserviços Kotlin/Ktor, com build Gradle próprio: `contracts`, `lobby-service`, `game-service`, `probe` e `deploy/` (Helm). Veja [online/deploy/README.md](online/deploy/README.md). |

O motor não depende de Android nem de nenhum outro módulo. Os bots e as telas só enxergam a visão do próprio jogador (`PlayerView`), nunca o estado completo.

## Como compilar

Requisitos: JDK 21 (o código é compilado para Java 17) e o Android SDK com a plataforma 36 ou superior.

```bash
./gradlew :core-engine:test        # testes do motor
./gradlew test                     # todos os testes JVM
./gradlew :app:assembleDebug       # APK de depuração
./gradlew :sim:run --args="--games 1000 --seed 42"
```

No Windows, use `.\gradlew.bat`.

### Backend online

O `online/` tem Gradle próprio e roda em Kubernetes local (Docker Desktop). Para testar e subir, siga [online/deploy/README.md](online/deploy/README.md).

## Documentação

- [definition.md](definition.md): regras (fonte da verdade).
- [docs/telas.md](docs/telas.md) e [docs/telas-gerenciamento.md](docs/telas-gerenciamento.md): telas, modais e glossário de componentes.
- [docs/plano-online.md](docs/plano-online.md): decisões e fases do jogo online.
- Outros planos em [docs/](docs/): loja, moedas, boosters, robôs, multijogador local.

## Licença

Copyright © 2026 williandrade82. Todos os direitos reservados. Veja [LICENSE](LICENSE).
