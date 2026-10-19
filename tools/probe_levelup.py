# -*- coding: utf-8 -*-
"""施肥直到升级弹窗出现并抓取现场。"""
import sys, os, re, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import explore


def state():
    nodes = explore.parse(explore.dump_raw())
    d = {}
    for n in nodes:
        l = n["label"]
        if not l.strip() or n["x2"] <= n["x1"]:
            continue
        m = re.search(r"可施肥(\d+)次", l)
        if m:
            d["times"] = int(m.group(1))
        m = re.search(r"施肥，肥料(\d+)", l)
        if m:
            d["fert"] = int(m.group(1))
    return d


explore.sh(["shell", "am", "force-stop", "com.taobao.taobao"])
time.sleep(2)
explore.sh(["shell", "monkey", "-p", "com.taobao.taobao", "-c", "android.intent.category.LAUNCHER", "1"])
time.sleep(27)

xml = explore.dump_raw()
if "阳光农场" not in xml and "集肥料" not in xml:
    nodes = explore.parse(xml)
    icon = next((n for n in nodes if n["clickable"] and "芭芭农场" in n["label"]), None)
    if icon:
        explore.sh(["shell", "input", "tap", str(icon["cx"]), str(icon["cy"])])
        time.sleep(22)

xml = explore.sh(["shell", "cat", "/sdcard/_explore.xml"])
print("在农场页:", ("阳光农场" in xml) or ("集肥料" in xml), flush=True)
prev = state()
print("基线:", prev, flush=True)

for i in range(1, 16):
    explore.sh(["shell", "input", "tap", "721", "2334"])  # 施肥按钮
    time.sleep(5)
    cur = state()
    print("第%d次施肥 -> %s" % (i, cur), flush=True)
    if prev and cur and cur.get("times") == prev.get("times") and cur.get("fert") == prev.get("fert"):
        print("  *** 两个数都没变，疑似升级弹窗，抓现场", flush=True)
        nodes = explore.parse(explore.dump_raw())
        rows = [n for n in nodes if n["label"].strip() and n["x2"] > n["x1"]]
        rows.sort(key=lambda n: n["y1"])
        print("  === 当前页面节点 ===", flush=True)
        for n in rows[:28]:
            print("    y=%5d x=%5d [%s] %-11s \"%s\"" % (
                n["y1"], n["x1"], "C" if n["clickable"] else " ", n["cls"].split(".")[-1], n["label"][:38]), flush=True)
        explore.sh(["shell", "screencap", "-p", "/sdcard/_lvl.png"])
        print("  已截图 /sdcard/_lvl.png", flush=True)
        break
    prev = cur
else:
    print("15 次内未卡住（可能今天已升过级/肥料不足）", flush=True)
