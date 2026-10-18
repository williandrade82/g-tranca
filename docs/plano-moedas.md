# Plano: moedas locais contra os robôs

Status: **aprovado em 2026-10-18** (recomendações M1–M6 aceitas); todas as etapas feitas (PR aberto). Parte do plano de evolução (decidido em 15/10): perfis de regra, perfil com foto e,
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

## Decisões (aceitas em 2026-10-18: todas as recomendações)
M1 saldo inicial 1.000 e ganho diário 200 (um por dia, sem acumular); M2 apostas 50, 100, 250 e 500; M3 todos apostam o valor da mesa
(a fórmula proporcional fica pronta e testada); M4 débito ao começar, prêmio no fim, desistência perde a aposta, jogo salvo guarda a aposta;
M5 o ganho diário nunca deixa o saldo abaixo de 200 e mesas acima do saldo ficam bloqueadas; M6 saldo no Início e em Mesas, aposta no
cartão e na tela da mesa, resultado em moedas na tela final e nas estatísticas.

## Etapas (um commit por etapa; testes antes de seguir)
1. **Regra** no `definition.md` §14.6. — **FEITA**
2. **Modelo puro** (`:core-engine`, `Coins` e `Wallet`) com testes de propriedade (soma do rateio, perdedores 0, vencedores ao menos a aposta). — **FEITA**
3. **Persistência (`:data`):** carteira no arquivo das estatísticas (o prêmio e a contagem do jogo gravados juntos, uma vez por jogo), aposta
   no jogo salvo (jogo salvo antigo = sem aposta). — **FEITA**
4. **Mesas:** aposta nas mesas dos robôs (sorteio das quatro) e em "Criar mesa"; mesa bloqueada por saldo; débito ao iniciar. — **FEITA**
5. **Fim de jogo:** prêmio no registro, resultado em moedas na tela final, desistência e "Novo jogo sobre o salvo" perdem a aposta. — **FEITA**
6. **Início e estatísticas:** saldo, resgate do ganho diário, saldo e resultado total nas estatísticas. — **FEITA**
7. **Revisão** (`rules-auditor`), APK e PR. — **FEITA** (achados tratados: débito que falha começa o jogo sem aposta; relógio atrasado, perda da aposta se o app fecha antes do primeiro salvamento e carteira x estatísticas documentados no `definition.md`)
