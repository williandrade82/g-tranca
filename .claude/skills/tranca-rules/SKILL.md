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
Use o builder de estado (`GameStateBuilder`/DSL de teste) para montar mãos, lixo, mesa e monte exatos, em vez de embaralhar. Notação de cartas nos testes: `"7H"`, `"QS"`, `"AD"`, `"2C"` (naipes H, D, S, C; `10` como `"TH"`).

## Casos difíceis (sempre testar)
- §5.1 pegar o lixo: topo + 2 cartas da mão (incluindo coringa da mão), topo em conjunto existente; **proibido** combinar o topo com outras cartas do lixo.
- §5.3 3 preto no topo trava só o próximo jogador; depois disso ele vai para a mão junto com o lixo.
- §5.4 coringa no topo **pode** ser pego: ele é o único coringa do conjunto (mão entra só com naturais); conjunto novo com ≥2 naturais da mão ou acréscimo a conjunto sem coringa; leva todo o lixo.
- §6.2 A-2-3 e K-A-2 inválidos; maior sequência 4…A; nenhum 3 em conjunto.
- §6.3 máx. 1 coringa; posição do coringa definida pelo motor (buraco → ponta de cima → ponta de baixo); coringa corre com a mesma preferência; natural rejeitada se o coringa não couber; coringa **pode** entrar em canastra limpa, que passa a ser suja (evitar isso é estratégia do bot, não regra do motor).
- §6.4 um grupo por número por lado; nova sequência não pode ser continuação de outra do mesmo naipe (**exceto** quando o coringa dela sujaria uma canastra limpa: aí pode ser conjunto separado); sequências nunca se unem e o jogador indica o conjunto de destino ao acrescentar.
- §6.5 reposição em cadeia de 3 vermelho; 3 vermelho na distribuição e no morto; sem monte nem morto → sem reposição.
- §8 ficar sem cartas só se resultar em morto ou batida.
- §9.2 morto direto continua a jogada; §9.3 morto indireto só na próxima vez; duplas: só um morto por dupla.
- §10 morto vira monte (apenas um, se houver dois); compra impossível → fim sem vencedor (salvo pegar o lixo).
- §11 batida exige morto do lado + canastra do lado; baixando tudo ou descartando a última carta.
- §12 –100 por morto não pego; cartas em conjuntos sem valor próprio; batida +100 só com vencedor.
- §13 `>=` pontuação-alvo; empate no topo → nova partida.

## Invariantes (property-based tests com Kotest)
Para partidas aleatórias com semente:
- Total de cartas sempre 104 (mãos + monte + lixo + mortos + mesa).
- Nenhum conjunto na mesa viola §6.2/§6.3.
- Nenhum jogador fica sem cartas fora das condições de §8.
- `apply` de qualquer ação de `legalActions` nunca lança exceção.
