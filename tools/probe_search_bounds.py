# -*- coding: utf-8 -*-
"""精确验证：把「搜一搜你喜欢的商品」逐步滚进视口，看它的 bounds 是否变成有效值。

这是用户提出的关键质疑：
  「上面 7 行能读到坐标，为什么滚上来的 9 行读不到？」

做法：小步滚动（600px/步），每步记录
  · 截图 md5（确认视觉真的滚了）
  · 「搜一搜」所有匹配节点的 bounds
  · 当前有有效 bounds 的行列表
若某一步「搜一搜」的 bounds 变为非零 → 说明滚动能带来坐标（用户的思路可行）。
"""
import sys, os, re, time, hashlib
sys.path.insert(0, os.getcwd())
import explore

TARGET = "搜一搜"
RX_ROW = re.compile(r"^(.+?)\((\d+)/(\d+)\)$")


def md5():
    explore.sh(["shell", "screencap", "-p", "/sdcard/_v.png"])
    raw = explore.sh(["shell", "cat", "/sdcard/_v.png"])
    return hashlib.md5(raw.encode("utf-8", "ignore")).hexdigest()[:6]


def probe():
    """返回 (所有匹配 TARGET 的节点信息, 有效行列表)"""
    xml = explore.dump_raw()
    hits = []
    valid_rows = []
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
        if TARGET in lbl:
            hits.append("[%d,%d][%d,%d] click=%s %r" % (x1, y1, x2, y2, g("clickable"), lbl[:36]))
        if RX_ROW.match(lbl) and x2 > x1 and y2 > y1:
            valid_rows.append((lbl, y1))
    return hits, valid_rows


# ---------- 确保面板打开 ----------
xml = explore.dump_raw()
if "肥料明细" not in xml:
    nodes = explore.parse(xml)
    e = next((n for n in nodes if n["clickable"] and "集肥料" in n["label"]), None)
    if e is None:
        print("未找到「集肥料」，无法打开面板")
        sys.exit(1)
    print("点「集肥料」打开面板 @(%d,%d)" % (e["cx"], e["cy"]))
    explore.sh(["shell", "input", "tap", str(e["cx"]), str(e["cy"])])
    time.sleep(9)
print("面板已开:", "肥料明细" in explore.dump_raw())
print()

print("=" * 74)
print("逐步滚动，每步记录「搜一搜」的 bounds")
print("=" * 74)

found_valid = False
for step in range(13):
    h = md5()
    hits, rows = probe()
    marker = ""
    if hits and not all("][0,0]" in x or "[0,0][0,0]" in x for x in hits):
        marker = "   ★★★ 出现有效坐标！"
        found_valid = True
    print("\n[第 %2d 步] 截图=%s  有有效bounds的行=%d%s" % (step, h, len(rows), marker))
    if hits:
        for x in hits:
            print("        搜一搜 → %s" % x)
    else:
        print("        搜一搜 → 不在节点树")
    if rows:
        print("        有效行: %s" % ", ".join(r[0][:18] for r in sorted(rows, key=lambda r: r[1])))

    # 小步滚动（手指上移 600px = 看下面内容）
    explore.sh(["shell", "input", "swipe", "720", "2200", "720", "1600", "300"])
    time.sleep(2)

print()
print("=" * 74)
print("结论")
print("=" * 74)
print("  滚动过程中「搜一搜」是否出现过有效 bounds: %s" % ("是 ✓（用户思路可行）" if found_valid else "否 ✗（始终 [0,0][0,0]）"))
