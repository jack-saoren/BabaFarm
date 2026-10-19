# -*- coding: utf-8 -*-
"""验证「逛精选好物」的 bounds 是否随滚动改变。
同时用屏内有效行「买限时折扣好物得奖励」做对照：
  · 若连有效行的 y 都不变 → 节点树完全冻结
  · 若有效行的 y 变了、而「逛精选好物」仍为 0 → 说明只有屏外行不更新
"""
import sys, os, re, time, hashlib
sys.path.insert(0, os.getcwd())
import explore

WATCH = ["逛精选好物", "买限时折扣好物得奖励"]
RX_ROW = re.compile(r"^(.+?)\((\d+)/(\d+)\)$")


def md5():
    explore.sh(["shell", "screencap", "-p", "/sdcard/_g.png"])
    raw = explore.sh(["shell", "cat", "/sdcard/_g.png"])
    return hashlib.md5(raw.encode("utf-8", "ignore")).hexdigest()[:6]


def probe():
    xml = explore.dump_raw()
    got = {}
    nrow = 0
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        def g(k):
            mm = re.search(k + r'="([^"]*)"', t)
            return mm.group(1) if mm else ""
        lbl = (g("text") + g("content-desc")).strip()
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if not b:
            continue
        x1, y1, x2, y2 = (int(b.group(i)) for i in range(1, 5))
        if RX_ROW.match(lbl) and x2 > x1 and y2 > y1:
            nrow += 1
        for w in WATCH:
            if lbl.startswith(w):
                got.setdefault(w, []).append("[%d,%d][%d,%d]%s" % (
                    x1, y1, x2, y2, "" if (x2 > x1 and y2 > y1) else "  ← 零坐标"))
    return got, nrow


# 确保面板打开
if "肥料明细" not in explore.dump_raw():
    nodes = explore.parse(explore.dump_raw())
    e = next((n for n in nodes if n["clickable"] and "集肥料" in n["label"]), None)
    if e:
        explore.sh(["shell", "input", "tap", str(e["cx"]), str(e["cy"])])
        time.sleep(9)
print("面板已开:", "肥料明细" in explore.dump_raw())
print()

print("=" * 76)
print("滚动 8 次（600px/步），每步记录这两行的 bounds")
print("=" * 76)

history = {w: [] for w in WATCH}
for step in range(9):
    h = md5()
    got, nrow = probe()
    print("\n[第 %d 步] 截图=%s  屏内有效行=%d" % (step, h, nrow))
    for w in WATCH:
        vals = got.get(w, ["不在节点树"])
        print("   %-14s → %s" % (w, " | ".join(vals)))
        history[w].append(tuple(vals))
    if step < 8:
        explore.sh(["shell", "input", "swipe", "720", "2200", "720", "1600", "300"])
        time.sleep(2)

print()
print("=" * 76)
print("结论")
print("=" * 76)
for w in WATCH:
    uniq = set(history[w])
    changed = len(uniq) > 1
    print("  %-14s bounds 是否变化: %s" % (w, "变化了 ✓" if changed else "始终不变 ✗"))
    for u in uniq:
        print("      %s" % " | ".join(u))
