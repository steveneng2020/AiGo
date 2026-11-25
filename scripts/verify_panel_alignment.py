#!/usr/bin/env python3
"""面板数值右对齐验收：逐行量出文字右边缘，检查是否落在同一列。

为什么需要脚本：右对齐是像素级属性，差几 dp 在整屏截图里肉眼根本看不出来。
上一轮我就是靠目测把「数字在中间飘、单位孤零零贴右」判成了通过。

判定方法
--------
1. 在指定区域切出「文字行带」（连续非背景像素），按行高过滤掉分隔线/图标。
2. 每行测量最右暗像素的 x 坐标（排除橙色等彩色标注）。
3. 把这些右边缘分两簇：
   - 数值列（靠右的一簇）：应当全部相等，容差 RIGHT_TOL。
   - 标签列（靠左的一簇）：不参与判定。
4. 数值列内部若出现「大间隙」说明数值与单位被拆开，同样判失败。

用法:
    python3 verify_panel_alignment.py <screenshot.png> [y0 y1]
"""
import sys
from PIL import Image

RIGHT_TOL = 6       # 像素，1080p 下约 1.5dp，足以容下字形墨迹差异
MIN_BAND_H = 20     # 行高下限：过滤分隔线（1px）与图标碎片
VALUE_MIN_X = 550   # 标签普遍短于屏幕中线，右半区的才是最右文字


def is_text_pixel(px, x, y, max_v=150, max_sat=45):
    """暗且低饱和 = 灰黑文字；排除橙色高亮标注与彩色强调值。"""
    r, g, b = px[x, y]
    if (r + g + b) / 3 > max_v:
        return False
    if max(r, g, b) - min(r, g, b) > max_sat:
        return False
    return True


def text_bands(px, w, y0, y1, x0=60, x1=1020):
    bands, cur = [], None
    for y in range(y0, y1):
        has = any(is_text_pixel(px, x, y) for x in range(x0, x1, 2))
        if has and cur is None:
            cur = y
        elif not has and cur is not None:
            bands.append((cur, y - 1))
            cur = None
    if cur is not None:
        bands.append((cur, y1 - 1))
    return [b for b in bands if b[1] - b[0] >= MIN_BAND_H]


def right_edge(px, top, bot, x0=60, x1=1020):
    best = 0
    for y in range(top, bot + 1):
        for x in range(x1 - 1, x0, -1):
            if is_text_pixel(px, x, y):
                best = max(best, x)
                break
    return best


def main():
    path = sys.argv[1]
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    y0 = int(sys.argv[2]) if len(sys.argv) > 2 else 150
    y1 = int(sys.argv[3]) if len(sys.argv) > 3 else h - 150

    rows = []
    for top, bot in text_bands(px, w, y0, y1):
        r = right_edge(px, top, bot)
        if r > 0:
            rows.append((top, bot, r))

    print(f"图: {path}  尺寸 {w}x{h}  区域 y={y0}..{y1}")
    print(f"检出文字行 {len(rows)} 条\n")
    print(f"{'y 范围':>14} {'右边缘':>8} {'偏离':>8}")
    for top, bot, r in rows:
        print(f"{top:6d}-{bot:<7d} {r:8d} {'':>8}")

    value_rows = [x for x in rows if x[2] >= VALUE_MIN_X]
    if not value_rows:
        print(f"\n[FAIL] 没有检出数值列（最右暗像素都不 >= {VALUE_MIN_X}）")
        return 1

    rights = [r for _, _, r in value_rows]
    lo, hi = min(rights), max(rights)
    spread = hi - lo
    print(f"\n数值列右边缘: 最小 {lo} 最大 {hi} 跨度 {spread}px 容差 {RIGHT_TOL}px")

    ok = True
    for top, bot, r in value_rows:
        dev = r - hi
        flag = "" if abs(dev) <= RIGHT_TOL else "  <-- 不齐"
        print(f"  y={top:5d}-{bot:<5d} right={r:5d} 偏离={dev:+5d}{flag}")
        if abs(dev) > RIGHT_TOL:
            ok = False

    print()
    if ok:
        print(f"[PASS] 数值列右对齐，最大跨度 {spread}px <= {RIGHT_TOL}px")
        return 0
    print(f"[FAIL] 数值列右边缘不齐，跨度 {spread}px > {RIGHT_TOL}px")
    return 1


if __name__ == "__main__":
    sys.exit(main())
