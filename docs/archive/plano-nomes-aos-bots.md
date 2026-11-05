# Plano: Nome aos bots

Status: **implementado em 2026-10-19** (branch `feature/visual-tranca-bot`; commits `aeec398`, `2e4f13d`, `ceeffdf`). Regras em `definition.md` §14.5.

Como ficou:
- **Nomes e bordões:** `BotProfile.botName` (Bolt, Domino, Zen, Turbo) e `mottoRes()`. O sorteio não usa mais nome e sobrenome (o nome do jogador não influencia o sorteio); `firstNames` e os sobrenomes ficam guardados para os jogadores online.
- **Regras da mesa:** linha "Nível dos robôs" com o ícone de sortear (reciclagem); parceiro 40% maior e sozinho na linha; "Adversários" na linha seguinte, na posição do jogo, sem "Adv. esq./dir.".
- **Mesas disponíveis:** bordão sob o robô e ícone de lista com lupa em "Ver regras".
- **No jogo:** a pílula do assento ficou como era; a mudança é no **modal** do detalhe do jogador (imagem de apresentação inteira, bordão e nome).
- **Desdobramento (pedido depois):** o robô aparece com as cartas na mão por padrão (`cardsRes`); o pensador só na vez dele. Imagem-fonte em `docs/personagens/robos-com-cartas.png`; a de todos juntos (tela inicial), em `docs/personagens/todos-os-robos.png`.

## Objetivo
Esse plano descreve a ideia geral da nomeação dos jogadores robôs.
Até agora, os robôs tinham nomes e sobre-nomes para prever campos e formas de apresentação dessa informação na tela. Como futuramente a idéia é permitir que jogadores online interajam na tela, essa etapa foi necessária para preparar as definições e, portanto, nada do que foi definido até aqui, será descartado e deve ser mantido para os nomes dos jogadores.

No entanto, nesse momento, cada jogador robô receberá um nome único e a definição de nomes deverá ser substiuída por essa definição estática.

Todas as informações de perfis dos robôs permanecerão, o que muda é apenas o nome

## Novos nomes e bordões
Cada jogador robô terá o mesmo nome em qualquer nível.

Jogador Jovem: Seu nome será Bolt, com o bordão "Bora fechar essa mesa!"
Jogador Sóbrio: Seu nome será Domino e seu bordão "Cada carta tem seu propósito."
Jogador Atleta: seu nome será Turbo e seu bordão "Agora é pra valer!"
Jogador Alternativo: seu nome será "Zen" e seu bordão "Calma parceiro! A próxima carta pode mudar tudo!"

## Ajuste na tela de Regras da Mesa
Na personalização da mesa, na tela "Regras da mesa", deverá aparecer, logo abaixo da imagem de cada robô: o novo nome, o bordão em texto decorativo, e a descrição do seu estilo de jogo. Logo abaixo do título do jogo, deverá ter, centralizado, um texto contendo "Nível dos robôs: ", o número de estrelas do nível e a descrição do nível (iniciante, maduro ou sênior).
Nessa tela, dê mais ênfaze pra o parceiro, mostrando ele centrazliado, uns 40% maior que os adversários e único em sua linha. Os jogadores adversários poderão aparecer na linha subsequente, uma ao lado do outro, sob o título de "Adversários" e sem a identificação "Adv. (esq)" ou "Adv (dir)", porém, mantenha a posição deles nessa tela como será no jogo.
Troque o botão "Sortear outros jogadores" por um ícone de reciclagem à direita da linha que mostra o nível (lá em cima)

## Ajuste na tela Mesas disponíveis
Nessa tela, inclua abaixo da imagem de cada robô, um texto decorativo com o seu bordão. Inclua um ícone na opção "Ver regras" que tenha uma lista e uma lupa ou uma lista com um óculos.

## No jogo
No modal de card de cada robô, aumente um pouco a imagem do robô, exibindo-a completamente, não em um círculo, e inclua o texto decorativo com o bordão de cada robô logo abaixo da imagem.