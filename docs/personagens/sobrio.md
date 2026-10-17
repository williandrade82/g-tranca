# Ficha de arte: Sóbrio (`SOBRIO`)

Parte do plano [plano-bots-personas.md](../plano-bots-personas.md); regras e níveis em `definition.md` §14.5.
Convenções de arquivo, formato e checklist: [README.md](README.md).

## Descrição visual (vale para os 3 níveis)
- Adulto maduro (não idoso): porte volumoso, ombros largos.
- Pele azul com detalhes esbranquiçados envelhecidos (patina, desgaste).
- Adornos dourados (botões, abotoaduras, corrente).
- Olhos cansados (pálpebras pesadas), ar de sobriedade.
- **Cor dominante da pele:** azul profundo com detalhes esbranquiçados envelhecidos (azul ~220-235° de matiz; marfim/cinza-claro nos detalhes).

## Estilo de jogo (preferências; não mudam regra)
Sóbrio e conservador: guarda o coringa, evita abrir o lixo grande, descarta só o que é seguro, prefere canastra limpa e bate tarde.

Intenção dos ajustes no `:ai`: mais custo em sujar canastra e em gastar coringa; menos disposição para pegar o lixo; só bate com a mão bem resolvida.

## Nomes (sorteio sem repetição na mesa; sobrenomes vêm da lista comum)
Henrique, Eduardo, Augusto, Fernando, Roberto, Ricardo, Antônio, Marcos, Alberto, Sérgio

## Níveis (mesmo desenho-base, sinais de experiência crescentes)
- Iniciante: desgaste leve, adornos dourados simples; 1 estrela.
- Maduro: patina mais visível, mais um adorno dourado; 2 estrelas.
- Sênior: patina marcada e adornos dourados elaborados, olhar sereno e calculista; 3 estrelas.

## Poses (4 por nível; arquivo `char_sobrio_<nivel>_<pose>.webp`)
| Pose | Descrição | Animação cíclica (2 a 4 s, procedural no app) |
|---|---|---|
| `apresentacao` | hands clasped, a measured half-smile, gold pocket chain catching the light | respiração/flutuação suave + brilho |
| `vencedor` | a restrained proud nod, one gold cufflink raised, warm tired eyes, quiet satisfaction | saltinho e confete |
| `perdedor` | a long sigh, hand over the forehead, eyes closed, shoulders lowered, dignified disappointment | cabeça balançando devagar, gota/nuvem |
| `pensador` | one hand stroking the chin, brows slightly lowered, heavy-lidded eyes fixed on the cards | respiração lenta e pontos de pensamento |

## Prompt-base (inglês, para o gerador de imagens)
> Semi-realistic 3D character, bust shot, a mature adult robot (middle-aged, not elderly), sturdy and broader than the others, deep blue metallic skin with aged off-white / ivory patina details, tired heavy-lidded eyes, gold ornaments (cufflinks, buttons, a pocket chain), sober dignified posture. Transparent background, studio three-point lighting with warm key light and soft rim light, premium mobile game character, high detail, centered, square 1:1.

Para cada arquivo, acrescente ao prompt-base: o nível (ver "Níveis") e a pose (ver "Poses"), por exemplo: "Level 2 (mature): ...; pose: ...".
