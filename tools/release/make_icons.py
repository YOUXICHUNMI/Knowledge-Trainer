#!/usr/bin/env python3
"""生成 Knowledge Trainer 的应用图标（Android 各密度 + 桌面 .ico）。

为什么要有这个脚本：图标是二进制资源，直接提交一堆 PNG 谁也说不清它们是怎么来的、
下次要改配色得重新找设计文件。这里用代码把图标画出来 —— 改颜色改尺寸都只改一处，
而且任何人跑一遍就能得到逐字节一致的产物（没有随机、没有时间戳）。

设计：靛蓝渐变底 + 一张"答题卡"（白色圆角纸面 + 三条题目占位线）+ 右下角的绿色判对徽标。
小尺寸下只剩"卡片 + 绿点"，大尺寸下能看出是对勾。

用法（在仓库根目录）：
    python tools/release/make_icons.py
"""

from __future__ import annotations

import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 与 shared/.../presentation/theme/Palette.kt 保持一致
HERO_START = (0x3A, 0x2F, 0xB4)
HERO_END = (0x6E, 0x5A, 0xE6)
PAPER = (0xFF, 0xFE, 0xFB)
LINE = (0xD9, 0xD4, 0xF7)
CORRECT = (0x0B, 0x7B, 0x58)

MASTER = 1024


def vertical_gradient(size: int, top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    img = Image.new("RGB", (1, size))
    px = img.load()
    for y in range(size):
        t = y / max(1, size - 1)
        px[0, y] = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return img.resize((size, size), Image.BILINEAR)


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def draw_glyph(size: int, margin_scale: float = 1.0) -> Image.Image:
    """透明底的"答题卡 + 对勾"图形，用于自适应图标的前景层。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / MASTER * margin_scale
    # 以 1024 画布为基准，缩放后居中
    def X(v: float) -> float:
        return size / 2 + (v - MASTER / 2) * s

    card = [X(232), X(268), X(792), X(756)]
    d.rounded_rectangle(card, radius=X(72) - X(0), fill=PAPER)

    for i, width in enumerate((400, 320, 240)):
        y = 384 + i * 92
        d.rounded_rectangle(
            [X(296), X(y), X(296 + width), X(y + 44)],
            radius=X(22) - X(0),
            fill=LINE,
        )

    # 右下角判对徽标：白色描边让它在卡片与底色上都分得开
    cx, cy, r = 742, 740, 150
    d.ellipse([X(cx - r), X(cy - r), X(cx + r), X(cy + r)], fill=PAPER)
    d.ellipse([X(cx - r + 26), X(cy - r + 26), X(cx + r - 26), X(cy + r - 26)], fill=CORRECT)
    width = max(2, round(X(40) - X(0)))
    d.line(
        [(X(cx - 66), X(cy + 2)), (X(cx - 12), X(cy + 56)), (X(cx + 74), X(cy - 46))],
        fill=PAPER,
        width=width,
        joint="curve",
    )
    # line() 不画端点圆头，补两个小圆让拐角不至于秃
    for px, py in ((cx - 66, cy + 2), (cx + 74, cy - 46)):
        rr = width / 2
        d.ellipse([X(px) - rr, X(py) - rr, X(px) + rr, X(py) + rr], fill=PAPER)
    return img


def legacy_icon(size: int, round_icon: bool = False) -> Image.Image:
    """传统图标：整块不透明。圆形版用于 launcher roundIcon。"""
    grad = vertical_gradient(size, HERO_START, HERO_END).convert("RGBA")
    glyph = draw_glyph(size, margin_scale=1.0)
    card = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    card.alpha_composite(glyph)

    radius = size // 2 if round_icon else round(size * 0.215)
    mask = rounded_mask(size, radius)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    out.alpha_composite(card)
    # 圆角/圆形之外再裁一次，防止图形溢出边界
    clipped = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    clipped.paste(out, (0, 0), mask)
    return clipped


def write_android() -> list[str]:
    res = os.path.join(ROOT, "androidApp", "src", "main", "res")
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    written = []
    for name, size in densities.items():
        folder = os.path.join(res, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        for filename, is_round in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            path = os.path.join(folder, filename)
            legacy_icon(size, round_icon=is_round).save(path, "PNG", optimize=True)
            written.append(path)

    # 自适应图标前景：108dp 画布，432px 放在 xxxhdpi（4x）刚好等于 108dp
    fg_dir = os.path.join(res, "drawable-xxxhdpi")
    os.makedirs(fg_dir, exist_ok=True)
    fg = draw_glyph(432, margin_scale=0.66)
    fg_path = os.path.join(fg_dir, "ic_launcher_foreground.png")
    fg.save(fg_path, "PNG", optimize=True)
    written.append(fg_path)
    return written


def write_desktop() -> list[str]:
    out = os.path.join(ROOT, "desktopApp", "icons")
    os.makedirs(out, exist_ok=True)
    master = legacy_icon(MASTER)
    ico = os.path.join(out, "app.ico")
    master.save(
        ico,
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    png = os.path.join(out, "app.png")
    master.resize((512, 512), Image.LANCZOS).save(png, "PNG", optimize=True)
    return [ico, png]


if __name__ == "__main__":
    for path in write_android() + write_desktop():
        print(f"{os.path.getsize(path):>8}  {os.path.relpath(path, ROOT)}")
