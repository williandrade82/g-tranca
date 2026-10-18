# Ficha de arte: Atleta (`ATLETA`)

Parte do plano [plano-bots-personas.md](../plano-bots-personas.md); regras e níveis em `definition.md` §14.5.
Convenções de arquivo, formato e checklist: [README.md](README.md).

## Descrição visual (vale para os 3 níveis)
- Adulto musculoso, físico forte e evidente.
- Feições mais humanizadas (o mais humano dos quatro).
- Pele laranja.
- Bandana na cabeça e camiseta regata (obrigatórias em todas as poses).
- **Cor dominante da pele:** alaranjado (laranja vivo, ~20-35° de matiz).

## Estilo de jogo (preferências; não mudam regra)
Agressivo: pressiona o adversário pegando o lixo para tirar material dele, abre conjuntos rápido e bate cedo.

Intenção dos ajustes no `:ai`: mais peso em pegar o lixo para tirar material do adversário, em baixar cedo e em bater cedo; menos reserva de 3 preto.

## Nomes (sorteio sem repetição na mesa; sobrenomes vêm da lista comum)
Marcelo, Rodrigo, Thiago, Leandro, Maurício, Alexandre, Diego, Fábio, Rogério, Wagner

## Níveis (mesmo desenho-base, sinais de experiência crescentes)
- Iniciante: físico atlético, bandana lisa; 1 estrela.
- Maduro: mais definido, bandana com detalhe, pulseira; 2 estrelas.
- Sênior: físico imponente, bandana e regata com listras douradas, olhar de campeão; 3 estrelas.

## Poses (4 por nível; arquivo `char_atleta_<nivel>_<pose>.webp`)
| Pose | Descrição | Animação cíclica (2 a 4 s, procedural no app) |
|---|---|---|
| `apresentacao` | flexing one arm with a confident smirk, the other hand on the hip | respiração/flutuação suave + brilho |
| `vencedor` | both fists raised, roaring grin, veins and muscles tensed, sweat sparkle | saltinho e confete |
| `perdedor` | hands on the knees, head hanging, exhausted frustrated face, headband slightly loose | cabeça balançando devagar, gota/nuvem |
| `pensador` | arms crossed over the chest, jaw set, intense focused eyes | respiração lenta e pontos de pensamento |

## Prompt-base (inglês, para o gerador de imagens)
> Semi-realistic 3D character, bust shot, a robot with a more humanized face, the look of an adult who always goes to the gym: large muscles, visibly strong physique, vivid orange skin, a headband (bandana) and a tank top; confident competitive expression. Transparent background, studio three-point lighting with warm key light and soft rim light, premium mobile game character, high detail, centered, square 1:1.

Para cada arquivo, acrescente ao prompt-base: o nível (ver "Níveis") e a pose (ver "Poses"), por exemplo: "Level 2 (mature): ...; pose: ...".
