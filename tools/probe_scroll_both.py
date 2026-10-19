# -*- coding: utf-8 -*-
"""上滑/下滑双向对照：看节点树的屏内行与离屏 bounds 是否随方向变化。
重点：某个方向是否能让「离屏行(bounds=0)」变成有效 bounds。
"""
import sys, os, re, time, hashlib
sys.path.insert(0, os.getcwd())
import explore

RX = re.compile(r"^(.+?)\((\d+)/(\d+)\)$")


def shot_md5():
    explore.sh(["shell", "screencap", "-p", "/sdcard/_c.png"])
    raw = explore.sh(["shell", "cat", "/sdcard/_c.png"])
    return hashlib.md5(raw.encode("utf-8", "ignore")).hexdigest()[:8]


def scan(tag):
    md5 = shot_md5()
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
    print("\n[%-16s] 截图=%s  屏内=%d  离屏=%d" % (tag, md5, len(ins), len(off)))
    for lbl, y in sorted(ins, key=lambda r: r[1]):
        print("      屏内 y=%-5d %s" % (y, lbl[:40]))
    sr = [l for l in off if "搜一搜" in l]
    print("      搜一搜在: %s" % ("离屏(bounds=0)" if sr else "不在树里"))
    return md5, set(x[0] for x in ins), sr


def swipe_up():
    """手指向上滑 = 内容上移 = 看下面的任务"""
    explore.sh(["shell", "input", "swipe", "720", "2200", "720", "900", "300"])


def swipe_down():
    """手指向下滑 = 内容下移 = 回上面"""
    explore.sh(["shell", "input", "swipe", "720", "900", "720", "2200", "300"])


base = scan("基线（未滑动）")
print()
print("=" * 70)
print("方向 A：连续【上滑】3 次（想看下面的任务）")
print("=" * 70)
ups = []
for i in range(1, 4):
    swipe_up()
    time.sleep(3)
    ups.append(scan("上滑 %d 次" % i))

print()
print("=" * 70)
print("方向 B：连续【下滑】3 次（想回上面）")
print("=" * 70)
downs = []
for i in range(1, 4):
    swipe_down()
    time.sleep(3)
    downs.append(scan("下滑 %d 次" % i))

print()
print("=" * 70)
print("汇总对比")
print("=" * 70)
print("  截图 md5: 基线=%s | 上滑=%s | 下滑=%s" % (
    base[0], " → ".join(x[0] for x in ups), " → ".join(x[0] for x in downs)))
print("  屏内行数: 基线=%d | 上滑=%s | 下滑=%s" % (
    len(base[1]), [len(x[1]) for x in ups], [len(x[1]) for x in downs]))
print("  屏内行是否变化(上滑): %s" % any(x[1] != base[1] for x in ups))
print("  屏内行是否变化(下滑): %s" % any(x[1] != base[1] for x in downs))
print("  离屏行里是否出现过「搜一搜」有有效 bounds: %s" % (
    "否（始终 bounds=0）" if all(x[2] for x in ups + downs) and base[2] else "是"))
