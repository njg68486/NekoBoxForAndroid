#!/usr/bin/env python3
"""
kl 应用图标生成。

两套产物，语义不同（跟上游一致，别混）：
  1. mipmap-*/ic_launcher.png            —— legacy 图标（Android 7.1 及以下、以及部分
     启动器的 roundIcon 回退）。这层必须自带**白色底**，因为没有 adaptive 图层来兜底。
  2. mipmap-*/ic_launcher_foreground.png —— adaptive 图标的前景层（v26+）。
     **透明底**，白底由 mipmap-anydpi-v26/ic_launcher.xml 的
     <background android:drawable="@color/ic_launcher_background"/>（#FFFFFF）提供。

adaptive 前景的安全区：整图 108dp，只有中心 72dp 一定可见（外圈 18dp 会被各家
启动器的 mask 裁掉）。所以前景里的图案要缩到 72/108 = 0.667 再居中，
否则圆形 mask 下猫耳朵和尾巴会被切掉。
"""
import os
import sys
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else "/var/minis/attachments/uploads/1000123623.png"
RES = os.path.join(os.path.dirname(__file__), "../../app/src/main/res")

# legacy 图标边长（px）
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# adaptive 前景边长（px）= legacy * 108/48
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# 前景安全区占比：72dp / 108dp
SAFE_RATIO = 72 / 108
# legacy 图标留白：图案占 90%，四周留一点边，跟系统图标观感一致
LEGACY_RATIO = 0.90


def load_trimmed(path):
    """载入并裁掉四周全透明/纯白边，避免图案在画布里偏小。"""
    img = Image.open(path).convert("RGBA")
    # 原图是白底 PNG（不是透明底），先把接近纯白的像素当背景找边界
    rgb = img.convert("RGB")
    mask = rgb.point(lambda v: 255 if v < 246 else 0).convert("L")
    box = mask.getbbox()
    if box:
        img = img.crop(box)
    return img


def fit_square(img, canvas_size, ratio, background):
    """把 img 等比缩放进 canvas_size*ratio 的方框并居中。"""
    inner = int(round(canvas_size * ratio))
    w, h = img.size
    scale = min(inner / w, inner / h)
    new = img.resize((max(1, int(round(w * scale))), max(1, int(round(h * scale)))), Image.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), background)
    canvas.alpha_composite(new, ((canvas_size - new.width) // 2, (canvas_size - new.height) // 2))
    return canvas


def to_transparent(img):
    """把白底换成透明：按亮度做 alpha，保留抗锯齿边缘的半透明过渡。"""
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            m = min(r, g, b)
            if m >= 250:
                px[x, y] = (r, g, b, 0)
            elif m > 235:
                # 边缘过渡带：越白越透
                px[x, y] = (r, g, b, int(a * (250 - m) / 15))
    return img


def main():
    src = load_trimmed(SRC)
    cut = to_transparent(src)

    for dpi, size in LEGACY.items():
        out = fit_square(cut, size, LEGACY_RATIO, (255, 255, 255, 255))
        path = os.path.join(RES, f"mipmap-{dpi}", "ic_launcher.png")
        out.convert("RGB").save(path, "PNG", optimize=True)
        print("legacy", path, out.size)

    for dpi, size in FOREGROUND.items():
        out = fit_square(cut, size, SAFE_RATIO, (0, 0, 0, 0))
        path = os.path.join(RES, f"mipmap-{dpi}", "ic_launcher_foreground.png")
        out.save(path, "PNG", optimize=True)
        print("foreground", path, out.size)


if __name__ == "__main__":
    main()
