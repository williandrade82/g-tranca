---
name: simulate-matches
description: Rodar simulações headless bot × bot no módulo :sim para encontrar estados ilegais, exceções e travamentos do motor, e para comparar a força dos níveis de dificuldade. Use após mudanças no :core-engine ou :ai.
---

# Simulações bot × bot

## Execução
```powershell
.\gradlew.bat :sim:run --args="--games 1000 --mode duplas --sides medio,facil --seed 42 --target 3000"
```

Parâmetros do CLI do `:sim` (fonte: `sim/.../SimConfig.kt`):
- `--games N`: número de jogos.
- `--mode individual|duplas`.
- `--sides A,B`: bot de cada lado (`facil`, `medio`, `dificil` ou `aleatorio`, referência que joga ao acaso).
- `--seed S`: semente base (o jogo *i* usa `S + i`), para reproduzir falhas.
- `--target P`: pontuação-alvo (padrão 3000; use 1000 para medições mais rápidas).
- `--check-invariants`: valida as invariantes da skill `tranca-rules` após cada ação (mais lento; ligar sempre que mexer no motor).
- `--threads N`: jogos em paralelo (o resultado não muda).
- `--max-actions N`, `--max-rounds N`: limites contra travamento.
- Difícil: `--hard-iterations` (padrão 20), `--hard-time-ms` (teto de tempo; **só para medir custo**, torna o resultado não reproduzível), `--hard-rollout-turns`, `--hard-exploration`, `--hard-candidates`, `--hard-tree-depth`, `--hard-margin`, `--hard-min-visits`, `--hard-confidence` (ver `HardBotConfig`).

No PowerShell o progresso (stderr) aparece como `NativeCommandError`: é inofensivo, confira o código de saída. O `dificil` é lento: use `--threads` e menos jogos (100–200).

## Saída esperada
- Vitórias por lado com intervalo de confiança de 95%.
- Média de partidas por jogo, de jogadas por partida e % de partidas sem vencedor (§11.2).
- Tempo médio/máximo por decisão de cada lado e, para o Difícil, vezes em que o teto de tempo foi atingido e falhas de busca.
- Lista de falhas: semente, jogada, ação e mensagem. Toda falha deve virar um teste de regressão no `:core-engine` ou no `:ai` usando a mesma semente.

## Critérios
- Zero exceções e zero violações de invariantes.
- Com bots do mesmo nível e lados trocados, a taxa de vitória deve ficar perto de 50%.
- `dificil` vence `medio`, e `medio` vence `facil`, com diferença estatisticamente significativa.
