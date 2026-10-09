# G-Tranca — Mestre da Canastra: Buraco & Biriba

Nome do app na loja e no celular: **Mestre da Canastra: Buraco & Biriba** (sob o ícone: "Mestre da Canastra"). "G-Tranca" é o nome interno do projeto (pacote `com.gtranca`, módulos).

Jogo de **Tranca** (família Canastra) para Android, 100% offline. Você joga contra a máquina:

- **Individual:** você contra um jogador virtual.
- **Duplas:** você e um parceiro virtual contra uma dupla virtual.

Os jogadores virtuais têm três níveis de dificuldade (fácil, médio e difícil), e a pontuação-alvo do jogo é configurável (padrão 3.000).

## Regras

As regras completas estão em [definition.md](definition.md), a especificação que o código segue. Cada regra implementada tem um teste que cita a sua seção; o mapa entre regras e testes está em [docs/rules-traceability.md](docs/rules-traceability.md).

## Módulos

| Módulo | Responsabilidade |
|---|---|
| `:core-engine` | Motor de regras em Kotlin puro: modelos, máquina de estados, validação das jogadas e pontuação. |
| `:ai` | Jogadores virtuais por dificuldade. O difícil usa busca em árvore Monte Carlo (ISMCTS). |
| `:sim` | Simulação bot × bot pela linha de comando, para achar estados ilegais e medir a força dos bots. |
| `:data` | Configurações, jogo salvo e estatísticas (DataStore). |
| `:app` | Interface em Jetpack Compose. |

## Como compilar

Requisitos: JDK 21 (o código é compilado para Java 17) e o Android SDK com a plataforma 37.

```bash
./gradlew :core-engine:test        # testes do motor
./gradlew test                     # todos os testes JVM
./gradlew :app:assembleDebug       # APK de depuração
./gradlew :sim:run --args="--games 1000 --seed 42"
```

No Windows, use `.\gradlew.bat`.

## Licença

Copyright © 2026 williandrade82. Todos os direitos reservados. Veja [LICENSE](LICENSE).
