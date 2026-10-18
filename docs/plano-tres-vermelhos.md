# Plano: fechar o 3 vermelho (início da vez, morto, cadeia, compra)

Status: **em execução** (2026-10-17). Parte do plano de pendências; regras em `definition.md` §3.5, §6.5, §9.4 e §10.

## O que já existia
Troca pública de 3 vermelho no registro do motor (`redThreeLog`, com `atTurnStart` e `chained`), encenação na mesa
(voos da mão à área dos 3 vermelhos e do monte à mão), avisos dos outros assentos e confirmação "Baixar 3 vermelho" do
jogador. Início do jogo encenado na ordem certa (distribuição, depois trocas; commits `eb303de`..`2c92ae6`).

## Pedidos novos (2026-10-17)
1. **Troca em lote:** dois ou mais 3 vermelhos do mesmo jogador, na distribuição inicial ou no morto, são trocados **de uma
   só vez**: as cartas saem juntas da mão (ou do morto) para a área dos 3 vermelhos, a segunda um pouco depois da primeira, e
   depois as reposições saem juntas do monte para a mão, também com a segunda um pouco atrasada. Vale para jogador humano e robôs.
2. **Aviso do próprio jogador:** deixa de ser diálogo com botões ("Baixar 3 vermelho" e "Desistir") e passa a ser só um modal de
   aviso, como o dos demais, que fecha sozinho.
3. Cenários restantes a conferir e testar: humano abre o jogo, cadeia, compra durante a jogada (robô e humano), morto direto e
   indireto, monte vazio.

## Pedidos que chegaram durante o trabalho (2026-10-17)
- O aviso de 3 vermelho do jogador seguinte aparecia antes de o descarte anterior terminar a animação: o aviso e o voo da troca
  agora esperam todos os voos em andamento (menos os da distribuição) e o fim do assentamento da jogada anterior.
- Ao pegar o morto, as cartas apareciam na mão antes da animação: agora as cartas do morto do humano voam abertas (as que
  passam do limite de voos aparecem junto com o último) e ficam fora da mão até pousar.

## Etapas
1. **Planos e regra** (este arquivo, `definition.md` §3.5 com a encenação em lote). — **FEITA**
2. **Agrupar as trocas** do mesmo jogador (mesmo momento, sem cadeia) em um aviso só; reposições do humano e do morto por troca
   em lote; voos com a segunda carta atrasada; reposição também no morto (humano e robôs). — **FEITA**
3. **Aviso do próprio jogador** sem botões (fecha sozinho depois do tempo de leitura; "Na sua vez, você baixou..."). — **FEITA**
4. **Cenários restantes** com testes: humano abre o jogo, cadeia, compra durante a jogada (robô e humano), morto direto,
   monte vazio. Cobertos por testes do motor (`RoundEngine`, registro público) e do app (`GameViewModelTest`,
   `DealRedThreeOrderTest`, `TableAnimationsTest`, `RestoreTest`); novos desta etapa: troca em lote (animação e agrupamento),
   aviso depois do descarte, morto só na mão depois do voo. — **FEITA** (morto indireto do humano e monte vazio com 3 vermelho
   seguem cobertos só pelo motor; uma revisão visual no celular fecha o resto)
5. **Revisão** (`rules-auditor`), APK e PR. — em andamento
