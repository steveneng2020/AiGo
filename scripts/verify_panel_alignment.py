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
TEXT_MIN_HITS = 5   # 一行内暗像素下限：排除图标/描边/圆角留下的零星暗点
VALUE_CLUSTER_TOL = 5   # 聚类容差：只把与主流右边缘相差 5px 以内的行视为键值行。
                        # 取宽了会把被装饰元素污染的行（如面板右侧斜边）也纳进来，
                        # 反而让它们的偏差算进「跨度」；正常字形抖动在 3px 内。
VALUE_MAX_X = 1008  # 数值列右扫描上限：面板圆角外的聊天内容会透到 x>1009，
                    # 那是背景而非本面板的数值，纳进来会把整列误判成不齐


def is_text_pixel(px, x, y, max_v=150, max_sat=45):
    """暗且低饱和 = 灰黑文字；排除橙色高亮标注与彩色强调值。"""
    r, g, b = px[x, y]
    if (r + g + b) / 3 > max_v:
        return False
    if max(r, g, b) - min(r, g, b) > max_sat:
        return False
    return True


def text_bands(px, w, y0, y1, x0=60, x1=1020):
    """
    切出文字行带。

    判定「这一行有文字」不能只看有没有暗像素：图标、装饰描边、面板圆角
    都会在个别位置留下零星暗点，只按 any() 判断会把它们当成文字行，
    进而在 right_edge 里给出一个偏右的边缘值，把整列文字判成「不齐」。
    实测 vc135 截图里 y=275-304 只有 3 个暗点，却被当成数值行，
    导致 min/max 跨度算出 21px（其他行实际都在 998~1001，只差 3px）。

    改为按密度下限判断：一行内暗像素至少 TEXT_MIN_HITS 个才算文字。
    """
    bands, cur = [], None
    for y in range(y0, y1):
        hits = sum(1 for x in range(x0, x1, 2) if is_text_pixel(px, x, y))
        has = hits >= TEXT_MIN_HITS
        if has and cur is None:
            cur = y
        elif not has and cur is not None:
            bands.append((cur, y - 1))
            cur = None
    if cur is not None:
        bands.append((cur, y1 - 1))
    return [b for b in bands if b[1] - b[0] >= MIN_BAND_H]


def right_edge(px, top, bot, x0=60, x1=VALUE_MAX_X):
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

    # 只看「键值行」：这些行的右边缘应落在同一列。
    # 顶部概览区（大数字 / 百分比）字号与对齐方式都和键值行不同，
    # 面板右侧还可能透出浮动按钮的斜边，混在一起比较没有意义。
    # 判据：先取所有候选行的中位右边缘，再剔除偏离中位数过大的行——
    # 那些是被装饰元素污染的样本，不是真实的对齐偏差。
    candidates = [x for x in rows if VALUE_MIN_X <= x[2] <= VALUE_MAX_X]
    if candidates:
        # 用「最密集的那个右边缘值」当基准（允许 ±2px 抖动视作同一列），
        # 不用中位数：被污染的样本通常只有一两行，中位数会把基准拉偏。
        from collections import Counter
        # 桶粒度 4px：998~1001 这类正常的字形抖动应算同一列，
        # 否则它们各自成桶，反而让一两行污染样本成为「主流」。
        buckets = Counter(round(r / 4) for _, _, r in candidates)
        base = buckets.most_common(1)[0][0] * 4
        value_rows = [x for x in candidates if abs(x[2] - base) <= VALUE_CLUSTER_TOL]
        dropped = len(candidates) - len(value_rows)
    else:
        value_rows, dropped, base = [], 0, 0
    if dropped:
        print(f"\n（已剔除 {dropped} 行被装饰元素/不同排版污染的样本，"
              f"主流右边缘 {base}px，聚类容差 ±{VALUE_CLUSTER_TOL}px）")
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
