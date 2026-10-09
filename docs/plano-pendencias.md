# Plano: pendências do Tranca Família

Status: **em execução** — D1 = não (mão em grade), D2 = não (pilha continua "Lixo"). Ordem: do mais simples ao mais trabalhoso. Cada etapa termina com testes,
um commit e, se mexer no app, um APK `TrancaFamilia-<hash>-debug.apk` para teste no celular.

Fora deste plano: redesenho dos avatares e da tela de Perfil (instruções mantidas em
[plano-revisao-avatares.md](plano-revisao-avatares.md)) e a publicação na loja (Fase 4, adiada pelo usuário,
listada no fim só como lembrete).

## Decisões do usuário antes de começar

| # | Pergunta | Opções |
|---|---|---|
| D1 | Mão em leque? | (a) não fazer: grade atual, toque garantido de 48dp; (b) leque leve só quando a mão couber em uma linha |
| D2 | A pilha "Lixo" passa a se chamar "Descarte" em toda a interface? | (a) sim: atualizar `definition.md` primeiro e depois os textos; (b) não: só a ação "Pegar descarte" (situação atual) |

## Etapa 1 — Rápidas (uma sessão curta) — FEITA

1. **Commitar os planos** em `docs/` (`plano-revisao-visual-luxo.md`, `plano-revisao-avatares.md`, este arquivo).
2. **"Novo jogo" sobre jogo salvo = derrota por desistência** (já aprovado). Em `HomeViewModel.onConfirmNewGame`,
   gravar `GameResult.RESIGNATION` com o `gameId` e a configuração do salvo antes de apagá-lo
   (`StatsRepository.record` já ignora repetidos). Teste em `HomeViewModelTest`.
3. **Chip/cartão "Duplas" no Início:** conferir no emulador se o texto ainda quebra em 3 linhas nos cartões de
   modo novos; se quebrar, encurtar o texto ou reduzir a fonte só ali.
4. **Tela de abertura do Android 12+** com o fundo meia-noite e o ícone (tema `Theme.SplashScreen`
   em `res/values-v31`), para não piscar branco ou verde antes do app.
5. **D2**, se aprovado: `definition.md` → `strings.xml` ("Lixo" → "Descarte") → testes que procuram o texto.
6. **D1**, se escolhida a opção (b).

## Etapa 2 — Pequenas técnicas

7. **Testes automáticos da animação de distribuição** (`TableAnimations.deal`): ordem das cartas, origem no
   monte, uma por assento por vez, nenhuma carta revelada de outro assento.
8. **Voo próprio ao pegar o morto**: as cartas do morto voam da pilha do morto para a mão (direto) ou ficam
   prontas para a próxima compra (indireto), com o som de deslizar; nada muda nas regras.
9. **Salvar o sorteio interno dos bots** no jogo salvo, para que um jogo retomado continue igual ao que
   seria sem a interrupção. Teste em `RestoreTest`.

## Etapa 3 — Bots (requer liberar o `:sim`, hoje suspenso)

10. **Conferir a memória dos bots no app:** `onNewRound()` a cada partida e `observe(PublicEvent)` a cada
    jogada (inclusive as do próprio bot), com `takenFromDiscard` calculado antes do `apply`. Corrigir se faltar.
11. **Pontuação nova no Médio:** valorizar 3 vermelho (±100 conforme ter canastra) e cartas na mesa.
12. **Descarte do Médio (§5.1):** considerar que o adversário pode pegar o lixo levando o topo a um jogo dele na mesa.
13. **Medições** com as regras de 07/10 (canastra de 7, 3 vermelho na vez): Fácil × Médio × Difícil nos dois
    modos, lados trocados, e taxa de partidas sem vencedor. Usar o resultado para recalibrar o `HardBotConfig`.

## Etapa 4 — Desempenho

14. **Otimizar `RoundEngine.legalActions`** (≈ 80% do tempo do Difícil): evitar recriar estados, reaproveitar
    a validação de conjuntos, cortar combinações repetidas. Garantir resultado idêntico com teste de
    propriedade comparando a versão antiga e a nova em milhares de estados aleatórios.
15. **Calibrar o Difícil no celular real**: medir `HardBot.timeLimitHits` com o teto de 800 ms, em duplas;
    ajustar iterações se o teto for atingido com frequência.

## Lembrete — Publicação (Fase 4, quando o usuário pedir)

Versão, assinatura, R8, AAB, ícone final, orientação de tela, textos da loja, política de privacidade e
auditoria final com o `rules-auditor`.
