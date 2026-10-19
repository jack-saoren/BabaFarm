# -*- coding: utf-8 -*-
"""干净对照实验：滑动面板一次，看
 A. 截图是否变化（视觉滚动）
 B. 节点树里的「屏内行」是否变化（bounds 是否更新）
这决定修复方向：是修抓取，还是修滑动。
"""
import sys, os, re, time, hashlib
sys.path.insert(0, os.getcwd())
import explore

RX = re.compile(r"^(.+?)\((\d+)/(\d+)\)$")


def shot(tag):
    p = "/sdcard/_cmp_%s.png" % tag
    explore.sh(["shell", "screencap", "-p", p])
    raw = explore.sh(["shell", "cat", p])
    return hashlib.md5(raw.encode("utf-8", "ignore")).hexdigest()[:10]


def rows(tag):
    """按「屏幕内(bounds有效)」过滤，模拟 Locator.dumpTexts 的结果"""
    xml = explore.dump_raw()
    ins, off = [], []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        def g(k):
            mm = re.search(k + r'="([^"]*)"', t)
            return mm.group(1) if mm else ""
        lbl = (g("text") + g("content-desc")).strip()
        if not RX.match(lbl):
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if not b:
            continue
        x1, y1, x2, y2 = (int(b.group(i)) for i in range(1, 5))
        if x2 > x1 and y2 > y1:
            ins.append((lbl, y1))
        else:
            off.append(lbl)
    print("  [%s] 屏内行 %d 个 / 离屏行 %d 个" % (tag, len(ins), len(off)))
    for lbl, y in sorted(ins, key=lambda r: r[1]):
        print("        屏内 y=%-5d %s" % (y, lbl[:42]))
    if off:
        print("        离屏: %s" % ", ".join(x[:16] for x in off))
    return ins


print("=" * 70)
print("步骤 1：基线")
print("=" * 70)
h1 = shot("a")
print("  截图 md5:", h1)
r1 = rows("基线")

print()
print("=" * 70)
print("步骤 2：滑动面板一次（面板区域内，向上滑=看下面内容）")
print("=" * 70)
explore.sh(["shell", "input", "swipe", "720", "2200", "720", "900", "300"])
time.sleep(4)
h2 = shot("b")
print("  截图 md5:", h2, "→ 与基线", "不同（视觉确实滚动了）" if h2 != h1 else "相同（没滚动）")
r2 = rows("滑动1次后")

print()
print("=" * 70)
print("步骤 3：再滑一次")
print("=" * 70)
explore.sh(["shell", "input", "swipe", "720", "2200", "720", "900", "300"])
time.sleep(4)
h3 = shot("c")
print("  截图 md5:", h3)
r3 = rows("滑动2次后")

print()
print("=" * 70)
print("结论")
print("=" * 70)
names1 = set(x[0] for x in r1)
names2 = set(x[0] for x in r2)
names3 = set(x[0] for x in r3)
print("  截图变化: %s / %s" % ("基线≠滑动1" if h1 != h2 else "基线=滑动1",
                             "滑动1≠滑动2" if h2 != h3 else "滑动1=滑动2"))
print("  屏内行变化: 基线%d → 滑动1 %d → 滑动2 %d" % (len(names1), len(names2), len(names3)))
print("  滑动后新出现的屏内行:", (names2 - names1) or "无", (names3 - names2) or "无")
print()
print("  判定：")
if h2 != h1 and not (names2 - names1):
    print("   ★ 视觉滚动生效，但节点树屏内行不变 —— bounds 不随滚动更新")
    print("     → 只能靠视觉(截图)定位，或另找抓取方式")
elif not (names2 - names1) and h2 == h1:
    print("   ★ 滑动完全无效（视觉与节点树都没变）→ 需换滑动方式/坐标")
else:
    print("   ★ 滑动可让新行进入屏内 → 修 readTaskRows 的累积逻辑即可")
