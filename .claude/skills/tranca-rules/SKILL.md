---
name: tranca-rules
description: Referência para implementar ou testar regras da Tranca do G-Tranca. Use antes de escrever código ou testes do motor, ao revisar regras, ou quando surgir dúvida sobre o comportamento de uma jogada.
---

# Regras da Tranca no código

A especificação é `definition.md`. Esta skill não a substitui: ela indica **como** transformar cada regra em código e testes.

## Fluxo para uma regra nova ou alterada
1. Localize a seção (`§N.N`) em `definition.md`. Se a regra não existir ou for ambígua, **pare e pergunte ao usuário**; não escolha por conta própria.
2. Escreva testes no `:core-engine` citando a seção no comentário. Cubra o caso permitido **e** o proibido.
3. Implemente em `legalActions` (o que pode) e em `apply` (o efeito).
4. Atualize `docs/rules-traceability.md` (tabela: Seção | Regra resumida | Testes).
5. Rode `.\gradlew.bat :core-engine:test`.

## Construção de cenários de teste
Use o builder de estado `RoundStateBuilder` e os helpers `c("7H")`/`cards("7H 8H 9H")` de `core-engine/src/test/.../EngineTestSupport.kt` para montar mãos, lixo, mesa e monte exatos, em vez de embaralhar (monte: 1ª carta = topo; lixo: última = topo). Notação de cartas: `"7H"`, `"QS"`, `"AD"`, `"2C"` (naipes H, D, S, C; `10` como `"TH"`; `'` no fim marca a cópia do 2º baralho, ex.: `"7H'"`).

`legalActions` é completa **a menos de cartas idênticas**: cada classe de jogada aparece uma vez com cartas representativas. Nos testes, compare ações por valor e naipe quando a cópia do baralho não importar.

## Casos difíceis (sempre testar)
- §5.1 pegar o lixo: topo + 2 cartas da mão (incluindo coringa da mão), topo em conjunto existente; **proibido** combinar o topo com outras cartas do lixo.
- §5.3 3 preto no topo trava só o próximo jogador; depois disso ele vai para a mão junto com o lixo.
- §5.4 coringa no topo **pode** ser pego: ele é o único coringa do conjunto (mão entra só com naturais); conjunto novo com ≥2 naturais da mão ou acréscimo a conjunto sem coringa; leva todo o lixo.
- §6.2 A-2-3 e K-A-2 inválidos; maior sequência 4…A; nenhum 3 em conjunto.
- §6.3 máx. 1 coringa; situação do coringa definida pelas naturais: **travado** no buraco (5-2-7), **solto** na ponta sem valor fixo (6-7-2 aceita 8, 5, 5-4 ou só 4, que o trava no 5) ou **sem posição** na sequência 4..A completa; a natural do buraco libera o coringa e é sempre aceita; coringa **pode** entrar em canastra limpa, que passa a ser suja (evitar isso é estratégia do bot, não regra do motor).
- §6.4 grupos do mesmo número podem se repetir (inclusive quando as cartas caberiam no existente); nova sequência não pode ser continuação de outra do mesmo naipe (**exceto** quando o coringa dela sujaria uma canastra limpa, ou quando caber exigiria travar ou mudar o valor do coringa da existente: aí pode ser conjunto separado); sequências nunca se unem e o jogador indica o conjunto de destino ao acrescentar.
- §6.5 reposição em cadeia de 3 vermelho; 3 vermelho na distribuição e no morto; sem monte nem morto → sem reposição. Toda troca é pública (§3.5): entra em `RoundState.redThreeLog` (`RedThreeLaid(seat, card, atDeal)`) na ordem real, com o assento de quem baixou (em duplas, qual parceiro); a reposição não entra (é oculta).
- §8 ficar sem cartas só se resultar em morto ou batida.
- §9.2 morto direto continua a jogada; §9.3 morto indireto só na próxima vez; duplas: só um morto por dupla.
- §10 morto vira monte (apenas um, se houver dois); compra impossível → fim sem vencedor (salvo pegar o lixo).
- §11 batida exige morto do lado + canastra do lado; baixando tudo ou descartando a última carta.
- §12 3 vermelho na mesa +100 só se o lado tiver canastra (limpa ou suja), senão –100 cada (com ou sem vencedor); –100 por morto não pego; cartas em conjuntos sem valor próprio; batida +100 só com vencedor.
- §13 `>=` pontuação-alvo; empate no topo → nova partida.

## Invariantes (property-based tests com Kotest)
Para partidas aleatórias com semente:
- Total de cartas sempre 104 (mãos + monte + lixo + mortos + mesa).
- Nenhum conjunto na mesa viola §6.2/§6.3.
- Nenhum jogador fica sem cartas fora das condições de §8.
- `apply` de qualquer ação de `legalActions` nunca lança exceção.
- §3.5 / §6.5 por lado, as cartas de `redThreeLog` dos assentos do lado == `redThrees[side]`, na mesma ordem; trocas da distribuição (`atDeal`) antes das da jogada (helper `redThreeLogViolation()` em `EngineTestSupport.kt`).
