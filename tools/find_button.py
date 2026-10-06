"""在模拟器截图里找"大号绿色按钮"，用来判断当前在哪个页面并给出点击坐标。

启动页有「创建房间」，大厅页有「进入房间」，两者都是占满一行的大绿按钮；
靠它们在屏幕上的位置不同来区分，比硬编码坐标可靠。

用法: python find_button.py <截图.png>
输出:  <名字> <中心x> <中心y>   —— 没找到则输出 NONE
"""
import sys

from PIL import Image

# (名字, x0, y0, x1, y1) —— 设备像素坐标(1920x1080)
BANDS = [
    ("start_host", 150, 385, 545, 460),       # 启动页「进入牌桌」（我是主机）
    ("start_join", 985, 345, 1380, 430),      # 启动页「连接并进入」
    ("lobby_enter", 200, 655, 900, 785),      # 大厅页「进入房间」
    ("lobby_start", 1250, 480, 1520, 560),    # 大厅页「开始游戏」
    ("lobby_addbot", 1250, 660, 1520, 730),   # 大厅页「＋ 添加机器人」（灰底）
]


def green_ratio(im, band):
    x0, y0, x1, y1 = band
    crop = im.crop((x0, y0, x1, y1)).convert("RGB")
    px = crop.load()
    w, h = crop.size
    hit = 0
    total = 0
    for y in range(0, h, 3):
        for x in range(0, w, 3):
            r, g, b = px[x, y]
            total += 1
            if g > 110 and g > r + 35 and g > b + 35:
                hit += 1
    return hit / max(total, 1)


def main():
    im = Image.open(sys.argv[1])
    best = None
    for name, x0, y0, x1, y1 in BANDS:
        ratio = green_ratio(im, (x0, y0, x1, y1))
        if ratio > 0.30 and (best is None or ratio > best[1]):
            best = (name, ratio, (x0 + x1) // 2, (y0 + y1) // 2)
    if best:
        print("%s %d %d" % (best[0], best[2], best[3]))
    else:
        print("NONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
