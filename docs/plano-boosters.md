# Plano: Boosters

Status: **decisões aceitas em 2026-10-27**; nada implementado. Branch `feature/boosters`. Fluxo de sempre: `definition.md` e `docs/telas.md` primeiro, depois testes, código, `rules-auditor`, um commit por etapa, APK com hash do commit.

## O que são
Itens do inventário do jogador que ele escolhe usar **antes de cada partida**. As **moedas** são um item do inventário (o único que muda de dono entre jogos: ganha ao vencer, perde ao perder, como hoje). Custo padrão: **1 unidade por partida** em que o booster é escolhido.

## Decisões (respostas de 27/10)
1. **Custo por partida, no "Seguir" do seletor** (não por uso dentro da partida).
2. **b3 Reações:** cada partida com o booster dá **3 reações** (o balão mostra os usos restantes).
3. **b1 substitui o destaque atual:** o destaque grátis que existe hoje na mão (`handEmphasis`/`state.highlighted`) **sai** e passa a ser o recurso do b1, agora como **ponto verde** no **canto superior direito** da carta (onde o círculo verde já fica hoje).
4. **Seletor antes de cada partida** (também contra robôs), **sem o jogador ver a distribuição**:
   - Ele escolhe e registra os boosters **localmente e no anfitrião**.
   - **30 s** de prazo; sem escolher, vale o que estiver selecionado na tela (começa pré-carregado com a escolha do Inventário, sem os de quantidade zero). Nenhum selecionado: a partida começa sem boosters.
   - Os demais jogadores já podem começar. Ao chegar a **vez** de quem ainda não escolheu, o jogo **espera**, como parte da jogada dele.
   - Ao tocar **Seguir**, ele vê as animações de distribuição e os descartes já feitos.
5. **Os boosters valem só para a partida** (nova partida, novo seletor e nova cobrança).
6. **Ícone do b2:** `docs/boosters/booster2-icon-v1.png` (pronto). **Áudios das reações:** o usuário vai criar novos; os de `reacoes_audio_jogo*.zip` e `1.salva de palmas.mp3` **não serão usados**. Não bloqueia: as reações entram sem som e o som entra quando os arquivos chegarem.
7. **Red-LED (b2):** círculo vermelho do mesmo tamanho do verde, no **canto inferior esquerdo** da carta.

## Catálogo inicial (JSON)
| id | Nome | Resumo | Qtd. inicial | Sorteio |
|---|---|---|---|---|
| b1 | Green-LED | Ponto verde nas cartas que pode baixar | 5 | 1 a 3 |
| b2 | Red-LED | Ponto vermelho nas cartas que se encaixam no jogo do adversário; a carta pulsa se fecha uma canastra dele | 5 | 1 a 3 |
| b3 | Reações | 3 reações por partida: Aplausos, Amei, Pensativo, Nervoso, Frango | 5 | 2 a 7 |

Custo de cada um: 1. Moedas: item do inventário, fora do sorteio e da seleção. O catálogo é um JSON local pensado para virar banco com sincronização on-line depois.

## Regras de produto
- **Inventário:** nichos do armário (`booster-stock-v1.png`): ícone, nome embaixo à esquerda, quantidade embaixo à direita. O jogador marca **até 3** (nunca moedas) para o próximo jogo. Ícone de acesso no Início, junto de Perfil e Estatísticas.
- **Seletor** (`boosters-selector-v1.png`): os 3 selecionados nos 3 nichos, com nome e quantidade; quantidade zero **não seleciona** e aparece esmaecido. **Seguir** debita 1 de cada.
- **Assento:** o booster fica no **assento**. Quem sai e quem entra depois herda os boosters daquele assento **sem descontar do seu inventário** (§16.7). O anfitrião guarda os boosters de todos os assentos.
- **Desistência/queda:** o booster já foi debitado e não volta.
- **Jogo salvo:** guarda os boosters da partida em andamento (já debitados), como já faz com a aposta.
- **Presente diário:** na **primeira abertura do dia**, só na tela inicial, modal "Você recebeu um presente" (ícone, nome, quantidade). Sorteio de **3 a cada 5 dias** (60 % por dia), booster e quantidade (faixa do catálogo) sorteados; **não sorteia** o booster que o jogador já tem com **10 ou mais** (a quantidade também nunca passa de 10). Marca o dia como na carteira (`claimDaily`).
- **Bots não usam boosters.** O b2 só usa informação pública (jogos na mesa), não vaza mão oculta.

## Reações (b3)
- Recortar as 5 de `reacts-v1.png` (fundo escuro com brilho: remover o fundo). Ícone do botão: `reacts-icon-v1.png`.
- Botão **entre "Sua mão" e o botão da última jogada**. Toque abre um **balão discreto acima do botão** (imagem, nome e usos restantes); fecha ao escolher ou ao tocar fora.
- A reação aparece para a mesa (na pílula de quem enviou); no multijogador viaja pela rede.

## Etapas
0. **Especificação:** `definition.md` (seção Boosters, catálogo, b1, b2, b3, herança no assento) e `docs/telas.md` (Inventário, Seletor, Presente do dia, balão de reações, ícone no Início, mudança no destaque da mão).
1. **`:core-engine` (puro, testes citando a seção):** `BoosterDef`, `Inventory` (consumir, teto 10), sorteio do presente com RNG injetado, `Loadout` (até 3, distintos, sem moeda, sem zero), regra do b2 (carta que se encaixa em jogo do adversário e fecha canastra).
2. **`:data`:** catálogo JSON (assets), inventário em arquivo JSON próprio, dia do último presente, boosters da partida no jogo salvo. Moeda continua no `WalletRepository` e aparece como item do inventário.
3. **Inventário** (armário).
4. **Seletor** (30 s, pré-carregado, esconde a distribuição, espera na vez de quem não escolheu).
5. **b1** (ponto verde substitui o destaque atual).
6. **b2** (ponto vermelho e pulso).
7. **b3** (recortes, botão, balão, 3 usos por partida).
8. **Presente diário.**
9. **Multijogador:** protocolo **18** (boosters por assento no anfitrião, escolha do seletor, herança no §16.7, reações pela rede). **Teste com 2–3 celulares pendente**, como nas outras frentes.
10. **Fechamento:** `rules-auditor`, testes de UI, APK com hash do commit.

## Riscos e observações
- Tirar o destaque grátis muda a experiência de quem joga sem b1; o tour guiado e os textos que citam o destaque precisam ser revistos.
- Esconder a distribuição exige que a mesa só desenhe a mão/animações depois do **Seguir**, sem mexer na ordem das animações já ajustada (3 vermelho, vez).
- Ao esperar a vez de quem não escolheu, o anfitrião não pode travar os demais assentos nem o relógio de queda (§16.15): a espera é uma fase da jogada, com o prazo de 30 s.
- Relógio do aparelho manda no presente diário (adulterável, aceito, como na carteira).
