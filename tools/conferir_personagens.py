"""Confere as imagens dos personagens (docs/personagens) contra as fichas (§14.5): recorte, fundo transparente e cor
dominante da pele por perfil.

Uso: py -3 tools/conferir_personagens.py   (Pillow, numpy e scipy necessários; sai com código 1 se algo falhar)

Para cada imagem derivada em app/src/main/res/drawable-nodpi/char_<perfil>_<nivel>_<pose>.webp:
- tamanho (576x384 nas poses grandes, 256x256 no pensador);
- fundo transparente e figura opaca (entre 20% e 95% dos pixels opacos);
- a figura toca a borda de baixo (busto) e não é cortada nos lados nem em cima;
- cor da pele: entre os pixels saturados, a fatia dentro da faixa de matiz do perfil (ver FAIXAS) é de pelo menos 25%
  e maior que a de qualquer faixa que não se confunda com a dele (jovem e sóbrio são ambos azuis; o marrom do Alternativo cai na faixa do laranja).
"""
import colorsys
import glob
import os
import re
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

# Faixa de matiz (graus) da pele de cada perfil, conforme as fichas em docs/personagens.
FAIXAS = {
    "jovem": (185, 235),        # azulado
    "sobrio": (200, 245),       # azul profundo (acessórios marrons e marfim reduzem a fatia)
    "alternativo": (45, 140),   # esverdeado (do oliva ao verde; colete e chapéu marrons reduzem a fatia)
    "atleta": (12, 42),         # laranja
}
MIN_FATIA = 0.25
PERFIS = list(FAIXAS)
NIVEIS = ["iniciante", "maduro", "senior"]
POSES = ["apresentacao", "vencedor", "perdedor", "pensador"]


def fatias(rgba):
    """Fatia dos pixels saturados de [rgba] em cada faixa de matiz de FAIXAS, ou `None` sem pixels saturados."""
    a = rgba[:, :, 3] > 200
    rgb = rgba[:, :, :3][a] / 255.0
    if len(rgb) == 0:
        return None
    hsv = np.array([colorsys.rgb_to_hsv(*p) for p in rgb[:: max(1, len(rgb) // 20000)]])
    forte = hsv[(hsv[:, 1] > 0.35) & (hsv[:, 2] > 0.25)]
    if len(forte) == 0:
        return None
    graus = forte[:, 0] * 360
    resultado = {}
    for perfil, (lo, hi) in FAIXAS.items():
        dentro = (graus >= lo) & (graus <= hi)
        if perfil == "atleta":
            # laranja vivo: o marrom escuro (colete) tem o mesmo matiz, mas é bem mais escuro e menos saturado
            dentro &= (forte[:, 2] >= 0.65) & (forte[:, 1] >= 0.55)
        resultado[perfil] = float(dentro.mean())
    return resultado


def confundivel(a, b):
    """Faixas que se sobrepõem, ou perfis cujos acessórios marrons (chapéu de palha, colete) caem na faixa do outro."""
    return (FAIXAS[a][0] <= FAIXAS[b][1] and FAIXAS[b][0] <= FAIXAS[a][1]) or {a, b} == {"alternativo", "atleta"}


def main():
    erros = []
    total = 0
    for perfil in PERFIS:
        for nivel in NIVEIS:
            for pose in POSES:
                path = os.path.join(RES, f"char_{perfil}_{nivel}_{pose}.webp")
                nome = os.path.basename(path)
                if not os.path.exists(path):
                    erros.append(f"{nome}: não existe")
                    continue
                total += 1
                im = Image.open(path).convert("RGBA")
                esperado = (256, 256) if pose == "pensador" else (576, 384)
                if im.size != esperado:
                    erros.append(f"{nome}: tamanho {im.size}, esperado {esperado}")
                rgba = np.array(im)
                opaco = rgba[:, :, 3] > 128
                fracao = opaco.mean()
                if not 0.20 < fracao < 0.95:
                    erros.append(f"{nome}: fração opaca {fracao:.2f} fora de 0,20–0,95 (fundo não transparente ou figura minúscula)")
                ys, xs = np.nonzero(opaco)
                if len(ys):
                    h, w = opaco.shape
                    if ys.max() < h - 3:
                        erros.append(f"{nome}: a figura não encosta no fundo (busto cortado em cima do chão)")
                    if pose != "pensador" and (xs.min() <= 0 or xs.max() >= w - 1 or ys.min() <= 0):
                        erros.append(f"{nome}: a figura é cortada pela borda")
                f = fatias(rgba)
                if f is None:
                    erros.append(f"{nome}: sem cores saturadas")
                else:
                    if f[perfil] < MIN_FATIA:
                        erros.append(f"{nome}: só {f[perfil]:.0%} das cores saturadas na faixa de matiz do perfil {perfil}")
                    for outro, valor in f.items():
                        if outro != perfil and not confundivel(perfil, outro) and valor >= f[perfil]:
                            erros.append(f"{nome}: {valor:.0%} na faixa de {outro}, mais que {f[perfil]:.0%} na de {perfil}")
    for perfil in PERFIS:
        for nivel in NIVEIS:
            for pose in ("vencedor", "perdedor"):
                path = os.path.join(RES, f"char_{perfil}_{nivel}_{pose}_av.webp")
                nome = os.path.basename(path)
                if not os.path.exists(path):
                    erros.append(f"{nome}: não existe")
                    continue
                total += 1
                im = Image.open(path)
                if im.size != (256, 256):
                    erros.append(f"{nome}: tamanho {im.size}, esperado (256, 256)")
    print(f"{total} imagens conferidas")
    for e in erros:
        print("ERRO:", e)
    return 1 if erros else 0


if __name__ == "__main__":
    sys.exit(main())
