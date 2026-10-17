# Ficha de arte: Jovem (`JOVEM`)

Parte do plano [plano-bots-personas.md](../plano-bots-personas.md); regras e níveis em `definition.md` §14.5.
Convenções de arquivo, formato e checklist: [README.md](README.md).

## Descrição visual (vale para os 3 níveis)
- Adolescente: magro, cabeça um pouco grande, olhos vivos.
- Pele azulada com juntas e painéis robóticos discretos.
- Fone de ouvido e boné (obrigatórios em todas as poses).
- Detalhes juvenis: adesivos, LED, tênis/pulseira se aparecerem.
- **Cor dominante da pele:** azulado (azul-ciano claro, ~190-210° de matiz).

## Estilo de jogo (preferências; não mudam regra)
Habilidoso e sagaz: aproveita o lixo quando vale, descarta com segurança lendo o que o adversário pega, bate assim que está à frente.

Intenção dos ajustes no `:ai`: mais peso em pegar o lixo útil e em descartes seguros (cartas que o adversário já descartou); bate cedo quando à frente.

## Nomes (sorteio sem repetição na mesa; sobrenomes vêm da lista comum)
Davi, Enzo, Théo, Lucas, Gabriel, Miguel, Caio, Vitor, Heitor, Nicolas

## Níveis (mesmo desenho-base, sinais de experiência crescentes)
- Iniciante: boné e fone novos, brilhantes, sem marcas; 1 estrela.
- Maduro: boné com um adesivo, fone com detalhes de cor; 2 estrelas.
- Sênior: boné e fone customizados com detalhes dourados discretos, olhar confiante; 3 estrelas.

## Poses (4 por nível; arquivo `char_jovem_<nivel>_<pose>.webp`)
| Pose | Descrição | Animação cíclica (2 a 4 s, procedural no app) |
|---|---|---|
| `apresentacao` | pointing at the viewer with a confident wink, one hand adjusting the cap | respiração/flutuação suave + brilho |
| `vencedor` | arms raised, big grin, eyes bright, small confetti sparks | saltinho e confete |
| `perdedor` | slumped shoulders, cap pulled down, eyes dim, frustrated pout, small raincloud-like droop | cabeça balançando devagar, gota/nuvem |
| `pensador` | eyes narrowed, one hand on the chin, head slightly tilted, focused | respiração lenta e pontos de pensamento |

## Prompt-base (inglês, para o gerador de imagens)
> Semi-realistic 3D character, bust shot, a teenage robot character, bluish cyan metallic skin with visible robotic seams and small panel lines, youthful proportions (slim, slightly oversized head), headphones around the neck or on the ears, a cap worn slightly sideways, playful juvenile details (stickers, a small LED strip). Transparent background, studio three-point lighting with warm key light and soft rim light, premium mobile game character, high detail, centered, square 1:1.

Para cada arquivo, acrescente ao prompt-base: o nível (ver "Níveis") e a pose (ver "Poses"), por exemplo: "Level 2 (mature): ...; pose: ...".
