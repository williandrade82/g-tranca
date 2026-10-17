# Personagens: fichas de arte e convenções

Os 4 perfis de robô × 3 níveis × 4 poses = **48 imagens**. Plano: [../plano-bots-personas.md](../plano-bots-personas.md).
Regras: `definition.md` §14.5. Claude não gera imagens: você gera/encomenda e Claude integra e confere.

| Perfil | Ficha |
|---|---|
| Jovem | [jovem.md](jovem.md) |
| Sóbrio | [sobrio.md](sobrio.md) |
| Alternativo | [alternativo.md](alternativo.md) |
| Atleta | [atleta.md](atleta.md) |

## Arquivos (situação em 2026-10-17)
- **Originais** (referência; não mexer): `NN - perfil - nivel.png` nesta pasta, 1536 × 1024 com 4 poses por folha: superior esquerdo = apresentação, superior direito = vencedor, inferior esquerdo = perdedor, inferior direito = pensador. Fundo transparente.
- **Derivadas para o jogo:** `app/src/main/res/drawable-nodpi/char_<perfil>_<nivel>_<pose>.webp` (48 arquivos, ~2,5 MB), geradas por `py -3 tools/cortar_personagens.py` (Pillow, numpy, scipy). Cada figura é isolada pelo contorno, porque mãos e chapéus passam da linha entre os quadrantes.
  - `apresentacao`, `vencedor`, `perdedor`: 576 × 384 (3:2), mesma escala em todas as poses, figura centrada e encostada no fundo.
  - `pensador`: recorte quadrado do busto, 256 × 256 (medalhões pequenos).
- **A insígnia de estrelas já vem desenhada na roupa** (1, 2 ou 3 estrelas; a Sênior também tem coroa no Jovem): o app não precisa desenhá-la.
- Perfil = `jovem | sobrio | alternativo | atleta`; nivel = `iniciante | maduro | senior`; pose = `apresentacao | vencedor | perdedor | pensador`.
- Para trocar uma imagem: substitua o original com o mesmo nome e rode o script.
- Conferência automática (tamanho, fundo transparente, recorte e cor da pele por perfil): `py -3 tools/conferir_personagens.py`. Os testes JVM (`CharacterAssetsTest`) conferem existência, tamanho e peso dos 48 arquivos.

## Formato desejado para novas imagens
- Fundo transparente, busto centrado; mesma proporção, enquadramento e iluminação em todas as imagens do mesmo personagem.

## Checklist de conferência (Claude abre cada imagem e confere)
1. Cor da pele dentro do matiz do perfil.
2. Acessórios obrigatórios do perfil presentes (ver "Descrição visual").
3. Sinais do nível coerentes (ver "Níveis").
4. Expressão da pose correta (ver "Poses").
5. Fundo transparente, recorte limpo, sem texto nem marca d'água.
