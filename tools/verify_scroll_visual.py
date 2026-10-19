# -*- coding: utf-8 -*-
"""严格验证：滚动前后用【截图人工对比】确认面板内容是否真的换位，同时 dump 对比。
回答「滚动后 y=1067 上到底是哪个任务」。
"""
import sys, os, re, time, subprocess
sys.path.insert(0, os.getcwd())
import explore

DEVICE = "YOUR_DEVICE_SERIAL"
RX_ROW = re.compile(r"^(.+?)\((\d+)/(\d+)\)$")
LOCAL = os.path.join("..", ".cache")
os.makedirs(LOCAL, exist_ok=True)


def adb(*args):
    return subprocess.run(["adb", "-s", DEVICE] + list(args),
                          capture_output=True, text=True, encoding="utf-8", errors="ignore").stdout


def shot(name):
    out = os.path.abspath(os.path.join(LOCAL, "verify_%s.png" % name))
    with open(out, "wb") as f:
        subprocess.run(["adb", "-s", DEVICE, "exec-out", "screencap", "-p"], stdout=f)
    return out


def rows(tag):
    xml = explore.dump_raw()
    valid, zero = [], []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        def g(k):
            mm = re.search(k + r'="([^"]*)"', t)
            return mm.group(1) if mm else ""
        lbl = (g("text") + g("content-desc")).strip()
        if not RX_ROW.match(lbl):
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if not b:
            continue
        x1, y1, x2, y2 = (int(b.group(i)) for i in range(1, 5))
        if x2 > x1 and y2 > y1:
            valid.append((y1, lbl))
        else:
            zero.append(lbl)
    print("  [%s] 有效行 %d 个:" % (tag, len(valid)))
    for y, l in sorted(valid):
        print("        y=%-5d %s" % (y, l[:40]))
    print("       零坐标行 %d 个: %s" % (len(zero), ", ".join(x[:13] for x in zero)))
    return valid


if "肥料明细" not in explore.dump_raw():
    nodes = explore.parse(explore.dump_raw())
    e = next((n for n in nodes if n["clickable"] and "集肥料" in n["label"]), None)
    if e:
        explore.sh(["shell", "input", "tap", str(e["cx"]), str(e["cy"])])
        time.sleep(9)
print("面板已开:", "肥料明细" in explore.dump_raw())
print()

print("=" * 74)
print("【A】滚动前")
print("=" * 74)
print("  截图:", shot("a"))
rows("A")

print()
print("=" * 74)
print("【滚动】大幅滑动 1500px")
print("=" * 74)
explore.sh(["shell", "input", "swipe", "720", "2300", "720", "800", "400"])
time.sleep(4)
print("  截图:", shot("b"))
rows("B 滚动后")

print()
print("=" * 74)
print("【再滚一次 1500px】")
print("=" * 74)
explore.sh(["shell", "input", "swipe", "720", "2300", "720", "800", "400"])
time.sleep(4)
print("  截图:", shot("c"))
rows("C 再滚后")
