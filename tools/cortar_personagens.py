"""Corta as folhas de personagens (docs/personagens/NN - perfil - nivel.png, 4 poses por folha) nas imagens do jogo.

Uso: py -3 tools/cortar_personagens.py   (Pillow, numpy e scipy necessários)

Folha 1536x1024 com 4 figuras, uma por quadrante: sup. esq. apresentação, sup. dir. vencedor, inf. esq. perdedor,
inf. dir. pensador. Mãos e chapéus passam da linha entre os quadrantes, por isso cada figura é isolada pelo seu
contorno (componente conexo de pixels opacos), e não pelo quadrante.

Saída em app/src/main/res/drawable-nodpi/char_<perfil>_<nivel>_<pose>.webp:
- apresentacao, vencedor, perdedor: 576x384 (3:2), mesma escala em todas as poses (2/3 do original), figura centrada
  na horizontal e encostada no fundo (o busto termina no quadro);
- pensador: recorte quadrado do busto, 256x256, para os medalhões pequenos da mesa.
Os originais nunca são alterados.
"""
import glob
import os
import re
import sys
import unicodedata

import numpy as np
from PIL import Image
from scipy import ndimage

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs", "personagens")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
POSES = {(0, 0): "apresentacao", (1, 0): "vencedor", (0, 1): "perdedor", (1, 1): "pensador"}
HALF_W, HALF_H = 768, 512
CANVAS_W, CANVAS_H = 864, 576  # em pixels do original; 576x384 depois de reduzir a 2/3
LARGE = (576, 384)
SMALL = 256
MIN_ALPHA = 24


def ascii_lower(text):
    return unicodedata.normalize("NFD", text).encode("ascii", "ignore").decode().lower()


def clean_alpha(im):
    """Fundo (alfa quase zero) vira transparente; o personagem, opaco (os PNGs saem com alfa ~250)."""
    r, g, b, a = im.split()
    a = a.point(lambda v: 0 if v < MIN_ALPHA else min(255, round(v * 255 / 250)))
    return Image.merge("RGBA", (r, g, b, a))


def split_figures(sheet):
    """Uma máscara por pose. As figuras de cima e de baixo se tocam em poucos pixels (chapéus e ombros): a folha é
    cortada na linha horizontal menos ocupada perto do meio. Em cada metade, os componentes opacos (alfa >= 200) vão
    para a esquerda ou a direita pelo centro de massa (mãos que passam da linha do meio seguem o braço), e são
    alargados em 3 px para recuperar a borda suave."""
    alpha = np.array(sheet.getchannel("A"))
    strong = alpha >= 200
    cut = 470 + int(strong[470:540].sum(axis=1).argmin())
    masks = {pose: np.zeros(alpha.shape, bool) for pose in POSES}
    for row, (y0, y1) in enumerate([(0, cut), (cut, alpha.shape[0])]):
        labels, count = ndimage.label(strong[y0:y1])
        for i in range(1, count + 1):
            part = labels == i
            if part.sum() < 200:  # poeira
                continue
            xs = np.nonzero(part)[1]
            masks[(int(xs.mean() >= HALF_W), row)][y0:y1][part] = True
    return {POSES[q]: ndimage.binary_dilation(m, iterations=3) & (alpha > 0) for q, m in masks.items()}


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for path in sorted(glob.glob(os.path.join(SRC, "*.png"))):
        m = re.match(r"\d+\s*-\s*(\w+)\s*-\s*(\w+)\.png$", ascii_lower(os.path.basename(path)))
        if not m:
            print("ignorado:", path)
            continue
        profile, level = m.groups()
        sheet = clean_alpha(Image.open(path).convert("RGBA"))
        assert sheet.size == (2 * HALF_W, 2 * HALF_H), (path, sheet.size)
        rgba = np.array(sheet)
        for pose, mask in split_figures(sheet).items():
            ys, xs = np.nonzero(mask)
            x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
            figure = rgba.copy()
            figure[~mask] = 0
            img = Image.fromarray(figure)
            if pose == "pensador":
                side = max(x1 - x0, y1 - y0)
                left, top = (x0 + x1) // 2 - side // 2, y1 - side
                out = Image.new("RGBA", (side, side))
                out.paste(img.crop((max(left, 0), max(top, 0), min(left + side, img.width), min(top + side, img.height))),
                          (max(-left, 0), max(-top, 0)))
                out = out.resize((SMALL, SMALL), Image.LANCZOS)
            else:
                if x1 - x0 > CANVAS_W or y1 - y0 > CANVAS_H:
                    print("AVISO: figura maior que o quadro:", path, pose, x1 - x0, y1 - y0)
                left, top = (x0 + x1) // 2 - CANVAS_W // 2, y1 - CANVAS_H
                out = Image.new("RGBA", (CANVAS_W, CANVAS_H))
                out.paste(img.crop((max(left, 0), max(top, 0), min(left + CANVAS_W, img.width), min(top + CANVAS_H, img.height))),
                          (max(-left, 0), max(-top, 0)))
                out = out.resize(LARGE, Image.LANCZOS)
            name = f"char_{profile}_{level}_{pose}.webp"
            out.save(os.path.join(OUT, name), "WEBP", quality=88, method=4)
            total += 1
        print(profile, level, "ok", flush=True)
    print(total, "imagens")


if __name__ == "__main__":
    sys.exit(main())
