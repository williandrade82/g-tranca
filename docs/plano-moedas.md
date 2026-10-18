# Plano: moedas locais contra os robôs

Status: **plano para aprovação** (2026-10-18). Parte do plano de evolução (decidido em 15/10): perfis de regra, perfil com foto e,
agora, moedas locais; o online vem depois. Fluxo de sempre: `definition.md` primeiro, depois testes, código e `rules-auditor`.

## O que já está decidido (conversa de 15/10)
- **Nunca haverá compra de moedas.** Só se ganha jogando e pelo ganho diário.
- Carteira **local**, guardada no aparelho (pode ser adulterada, aceito); é separada da futura carteira online, que ficará no servidor.
- **Ganho diário** de moedas.
- A **mesa define o valor da aposta** (e, mais adiante, se aceita só esse valor ou um intervalo pré-configurado).
- **Os robôs também apostam.**
- **1×1:** o vencedor leva a aposta do outro (o valor da mesa).
- **Duplas, rateio proporcional:** os perdedores perdem a aposta; o prêmio é a soma das apostas perdedoras; cada vencedor recebe a
  própria aposta mais o prêmio × (sua aposta ÷ soma das apostas vencedoras); a sobra do arredondamento vai por maior resto.
- Não existe empate de jogo (§13: joga-se nova partida). **Saldo menor que a aposta mínima impede entrar na mesa.**
- Desistência: sem punição extra por enquanto (robôs nunca abandonam), mas a aposta do desistente é perdida (o outro lado vence, §13.1).

## Decisões que preciso de você (com a minha recomendação)
| # | Pergunta | Recomendação |
|---|---|---|
| M1 | Saldo inicial e ganho diário | Saldo inicial **1.000**; ganho diário **200** (um resgate por dia, sem acumular dias; botão no Início). |
| M2 | Valores de aposta das mesas | **50, 100, 250 e 500**. Mesas dos robôs sorteiam um deles; "Criar mesa" escolhe entre os quatro. |
| M3 | Todos apostam o mesmo valor? | **Sim, o da mesa** (jogador, parceiro e adversários). A fórmula proporcional fica pronta e testada para quando houver intervalo. |
| M4 | Quando se paga e se recebe | **Débito ao começar** o jogo; **crédito no fim** (vitória ou derrota). Jogo salvo ("Continuar") guarda a aposta já debitada. Desistência: a aposta se perde. |
| M5 | Saldo abaixo da menor aposta (50) | O ganho diário **completa o saldo até pelo menos 200** (sem socorro extra). Mesas acima do saldo ficam bloqueadas com o aviso "saldo insuficiente". |
| M6 | Onde aparece | Saldo no **Início** (e no cabeçalho de Mesas), aposta no cartão e na tela da mesa, resultado em moedas na **tela final** e no histórico de **estatísticas**. |

## Etapas (um commit por etapa; testes antes de seguir)
1. **Regra** no `definition.md` (nova seção "Moedas e apostas": carteira, ganho diário, aposta da mesa, rateio, desistência, saldo mínimo). Fórmulas com exemplos.
2. **Modelo (`:core-engine` ou `:app/game`, puro, sem Android):** `Wallet`, `Stake`, `payouts(...)` (rateio proporcional por maior resto), ganho diário por data. Testes de propriedade (soma zero, nunca negativo).
3. **Persistência (`:data`):** carteira, data do último ganho, aposta do jogo salvo; teste de leitura de dados antigos (sem moedas = saldo inicial).
4. **Mesas:** aposta nas mesas dos robôs e em "Criar mesa"; mesa bloqueada por saldo; débito ao iniciar.
5. **Fim de jogo:** crédito/débito e apresentação (tela final, estatísticas); desistência.
6. **Início:** saldo e resgate do ganho diário.
7. **Revisão** (`rules-auditor`), APK e PR.
