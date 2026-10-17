# Personagens: fichas de arte e convenções

Os 4 perfis de robô × 3 níveis × 4 poses = **48 imagens**. Plano: [../plano-bots-personas.md](../plano-bots-personas.md).
Regras: `definition.md` §14.5. Claude não gera imagens: você gera/encomenda e Claude integra e confere.

| Perfil | Ficha |
|---|---|
| Jovem | [jovem.md](jovem.md) |
| Sóbrio | [sobrio.md](sobrio.md) |
| Alternativo | [alternativo.md](alternativo.md) |
| Atleta | [atleta.md](atleta.md) |

## Convenção de arquivos
`char_<perfil>_<nivel>_<pose>.webp` com perfil = `jovem | sobrio | alternativo | atleta`, nivel = `iniciante | maduro | senior`,
pose = `apresentacao | vencedor | perdedor | pensador`. Exemplo: `char_sobrio_senior_vencedor.webp`.

## Formato
- **1024 × 1024 px, fundo transparente** (o app recorta, enquadra e anima; WebP qualidade 90). Busto centrado, personagem ocupando ~85% do quadro.
- Mesma proporção, enquadramento e iluminação em todas as imagens do mesmo personagem (ele tem de ser reconhecível entre níveis e poses).
- **A insígnia de estrelas não entra na imagem**: o app a desenha.
- Pasta de entrega: a que você preferir; Claude converte para 512 px (256 px no pensador) e coloca em `app/src/main/res/drawable-nodpi/`.

## Checklist de conferência (Claude abre cada imagem e confere)
1. Cor da pele dentro do matiz do perfil.
2. Acessórios obrigatórios do perfil presentes (ver "Descrição visual").
3. Sinais do nível coerentes (ver "Níveis").
4. Expressão da pose correta (ver "Poses").
5. Fundo transparente, recorte limpo, sem texto nem marca d'água.
