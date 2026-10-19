# -*- coding: utf-8 -*-
"""需求梳理用：抓农场页兔子图标状态 + 任务面板全部任务行（含副标题/按钮）。"""
import sys, os, re, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import explore


def farm_dump(tag):
    nodes = explore.parse(explore.dump_raw())
    rows = [n for n in nodes if n["label"].strip() and n["x2"] > n["x1"]]
    print("=== [%s] 农场页左侧 y 1650-1960 区域（兔子图标所在）===" % tag, flush=True)
    for n in sorted(rows, key=lambda n: (n["y1"], n["x1"])):
        if 1650 <= n["y1"] <= 1960:
            print("   y=%5d-%5d x=%5d-%5d [%s] %-11s \"%s\"" % (
                n["y1"], n["y2"], n["x1"], n["x2"], "C" if n["clickable"] else " ",
                n["cls"].split(".")[-1], n["label"][:44]), flush=True)
    return rows


explore.sh(["shell", "am", "force-stop", "com.taobao.taobao"])
time.sleep(2)
explore.sh(["shell", "monkey", "-p", "com.taobao.taobao", "-c", "android.intent.category.LAUNCHER", "1"])
time.sleep(27)

xml = explore.dump_raw()
print("验证码?:", ("拖动下方滑块" in xml) or ("向右滑动验证" in xml), flush=True)
if "阳光农场" not in xml and "集肥料" not in xml:
    nodes = explore.parse(xml)
    icon = next((n for n in nodes if n["clickable"] and "芭芭农场" in n["label"]), None)
    if icon:
        explore.sh(["shell", "input", "tap", str(icon["cx"]), str(icon["cy"])])
        time.sleep(22)
print("在农场页:", flush=True)
farm_dump("农场页")

# 打开任务面板
explore.sh(["shell", "input", "tap", "1078", "2346"])
time.sleep(10)
panel = explore.dump_raw()
print("\n面板已开:", "肥料明细" in panel, flush=True)

nodes = explore.parse(panel)
rows = [n for n in nodes if n["label"].strip() and n["x2"] > n["x1"]]
# 找出所有带 (x/y) 的任务行作为锚点，再收集同行的其它文案
rx = re.compile(r"^(.+?)\((\d+)/(\d+)\)$")
print("\n=== 任务面板任务行清单（含同行副标题与按钮）===", flush=True)
seen = set()
for n in sorted(rows, key=lambda n: n["y1"]):
    m = rx.match(n["label"].strip())
    if not m:
        continue
    name = m.group(1)
    if name in seen:
        continue
    seen.add(name)
    cy = (n["y1"] + n["y2"]) // 2
    band = [x for x in rows if abs((x["y1"] + x["y2"]) // 2 - cy) <= 90]
    band.sort(key=lambda x: x["x1"])
    parts = []
    for x in band:
        tag = ("[C]" if x["clickable"] else "   ")
        parts.append("%s%s" % (tag, x["label"][:26]))
    print("  行 y=%5d  进度=%s/%s" % (cy, m.group(2), m.group(3)), flush=True)
    print("        同行: " + " | ".join(parts), flush=True)

explore.sh(["shell", "screencap", "-p", "/sdcard/_panel.png"])
print("\n已截图 /sdcard/_panel.png", flush=True)
