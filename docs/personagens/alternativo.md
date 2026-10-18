# Ficha de arte: Alternativo (`ALTERNATIVO`)

Parte do plano [plano-bots-personas.md](../plano-bots-personas.md); regras e níveis em `definition.md` §14.5.
Convenções de arquivo, formato e checklist: [README.md](README.md).

## Descrição visual (vale para os 3 níveis)
- Traços arredondados, braços visivelmente mais longos.
- Pele esverdiada (musgo/oliva).
- Colar de material natural marrom, colete marrom-escuro, chapéu de palha.
- Olhar sonhador, como quem procura sentido oculto.
- **Cor dominante da pele:** esverdeado (verde-musgo/oliva, ~90-130° de matiz) com marrons naturais nos adornos.

## Estilo de jogo (preferências; não mudam regra)
Ousado: pega o lixo com frequência, abre jogos incomuns, troca de plano no meio da partida; parece buscar um sentido oculto em cada descarte.

Intenção dos ajustes no `:ai`: mais disposição para pegar o lixo e arriscar, menos aversão a sujar canastra, escolhas menos óbvias entre jogadas equivalentes.

## Nomes (sorteio sem repetição na mesa; sobrenomes vêm da lista comum)
Ariel, Caetano, Dandara, Jaci, Íris, Raul, Naiara, Gil, Lia, Ravi

## Níveis (mesmo desenho-base, sinais de experiência crescentes)
- Iniciante: chapéu de palha simples, colar curto; 1 estrela.
- Maduro: chapéu com fita e pena, colar com mais elementos; 2 estrelas.
- Sênior: chapéu e colete trabalhados, colar longo com pedra, olhar enigmático; 3 estrelas.

## Poses (4 por nível; arquivo `char_alternativo_<nivel>_<pose>.webp`)
| Pose | Descrição | Animação cíclica (2 a 4 s, procedural no app) |
|---|---|---|
| `apresentacao` | long arms spread wide like offering a mystery, head tilted, a knowing smile | respiração/flutuação suave + brilho |
| `vencedor` | long arms thrown up, spinning joy, hat tipped back, wide delighted eyes | saltinho e confete |
| `perdedor` | long arms dangling to the sides, hat sliding over the eyes, a puzzled sad look | cabeça balançando devagar, gota/nuvem |
| `pensador` | a long finger tapping the temple, eyes drifting sideways as if reading hidden signs | respiração lenta e pontos de pensamento |

## Prompt-base (inglês, para o gerador de imagens)
> Semi-realistic 3D character, bust shot, an eccentric robot with greenish moss-colored skin and rounded soft shapes, noticeably longer arms, natural brown ornaments: a brown natural-material necklace (seeds, wood, cord), a dark brown vest, and a straw hat; dreamy thoughtful eyes, an expression of searching for a hidden meaning. Transparent background, studio three-point lighting with warm key light and soft rim light, premium mobile game character, high detail, centered, square 1:1.

Para cada arquivo, acrescente ao prompt-base: o nível (ver "Níveis") e a pose (ver "Poses"), por exemplo: "Level 2 (mature): ...; pose: ...".
