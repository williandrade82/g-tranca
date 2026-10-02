---
name: simulate-matches
description: Rodar simulações headless bot × bot no módulo :sim para encontrar estados ilegais, exceções e travamentos do motor, e para comparar a força dos níveis de dificuldade. Use após mudanças no :core-engine ou :ai.
---

# Simulações bot × bot

## Execução
```powershell
.\gradlew.bat :sim:run --args="--games 1000 --mode duplas --sides medio,facil --seed 42 --target 3000"
```

Parâmetros do CLI do `:sim`:
- `--games N`: número de jogos.
- `--mode individual|duplas`.
- `--sides A,B`: dificuldade de cada lado (`facil`, `medio`, `dificil`).
- `--seed S`: semente base (o jogo *i* usa `S + i`), para reproduzir falhas.
- `--target P`: pontuação-alvo (padrão 3000).
- `--check-invariants`: valida as invariantes da skill `tranca-rules` após cada ação (mais lento; ligar sempre que mexer no motor).

## Saída esperada
- Vitórias por lado com intervalo de confiança de 95%.
- Média de partidas por jogo, de jogadas por partida e % de partidas sem vencedor (§11.2).
- Lista de falhas: semente, jogada, ação e mensagem. Toda falha deve virar um teste de regressão no `:core-engine` ou no `:ai` usando a mesma semente.

## Critérios
- Zero exceções e zero violações de invariantes.
- Com bots do mesmo nível e lados trocados, a taxa de vitória deve ficar perto de 50%.
- `dificil` vence `medio`, e `medio` vence `facil`, com diferença estatisticamente significativa.
