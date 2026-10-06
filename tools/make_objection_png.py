"""把「異議あり！」原图处理成透明底 PNG，供牌桌浮层使用。

原图是白底 + 红字 + 黑描边。直接按"白色变透明"会把字内部的白色描边也打穿，
所以用 floodfill 只把**与四边相连的**那片背景去掉，内部白色保持不变。

用法: python make_objection_png.py <原图> <输出png>
"""
import os
import sys

from PIL import Image, ImageDraw

MARK = (255, 0, 255)      # 用洋红当"临时背景标记"，原图里不会出现这个颜色


def main():
    src, dst = sys.argv[1], sys.argv[2]
    im = Image.open(src).convert("RGBA")
    w, h = im.size

    rgb = im.convert("RGB")
    # 从四个角把连通的背景灌成标记色
    for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        try:
            ImageDraw.floodfill(rgb, seed, MARK, thresh=70)
        except Exception as e:
            print("  floodfill %s 失败: %s" % (seed, e))

    px = rgb.load()
    out = im.load()
    cleared = 0
    for y in range(h):
        for x in range(w):
            if px[x, y] == MARK:
                r, g, b, _a = out[x, y]
                out[x, y] = (r, g, b, 0)
                cleared += 1

    # 裁掉四周多余透明边，贴图更紧凑
    bbox = im.getbbox()
    if bbox:
        im = im.crop(bbox)

    os.makedirs(os.path.dirname(dst), exist_ok=True)
    im.save(dst, "PNG", optimize=True)
    print("[ok] %s -> %s  透明像素 %d, 输出尺寸 %s, %d bytes"
          % (os.path.basename(src), os.path.basename(dst), cleared, im.size,
             os.path.getsize(dst)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
