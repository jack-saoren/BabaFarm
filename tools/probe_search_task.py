# -*- coding: utf-8 -*-
"""查清「搜一搜你喜欢的商品」为什么没做：
1. 面板今天到底有几行任务？
2. 有没有「搜一搜」相关任务？
3. 滚动扫描是否真的能扫到屏外的行？
"""
import sys, os, re, time
sys.path.insert(0, os.getcwd())
import explore

RX = re.compile(r"^(.+?)\((\d+)/(\d+)\)$")


def labels():
    nodes = explore.parse(explore.dump_raw())
    return [n for n in nodes if n["label"].strip() and n["x2"] > n["x1"]]


def rows_now():
    """当前屏幕内的任务行"""
    out = []
    for n in labels():
        m = RX.match(n["label"].strip())
        if m:
            out.append((m.group(1), int(m.group(2)), int(m.group(3)), n["y1"]))
    return out


def find_btn(key):
    for n in labels():
        if n["clickable"] and key in n["label"]:
            return n
    return None


print("前台:", explore.sh(["shell", "dumpsys", "window"]).split("mCurrentFocus")[-1][:80])

# 确保在农场页
xml = explore.dump_raw()
if "集肥料" not in xml:
    for _ in range(3):
        time.sleep(8)
        x = explore.dump_raw()
        if "阳光农场" in x or "集肥料" in x:
            break
        icon = find_btn("芭芭农场")
        if icon:
            explore.sh(["shell", "input", "tap", str(icon["cx"]), str(icon["cy"])])
            time.sleep(18)
print("在农场页:", ("集肥料" in explore.dump_raw()))

# 开面板
if "肥料明细" not in explore.dump_raw():
    e = find_btn("集肥料")
    if e:
        explore.sh(["shell", "input", "tap", str(e["cx"]), str(e["cy"])])
        time.sleep(9)
print("面板已开:", "肥料明细" in explore.dump_raw())

seen = {}
print()
print("=== 逐个滚动位置列出任务行 ===")
for step in range(9):
    cur = rows_now()
    if step == 0:
        print("\n[第 0 屏 / 未滚动] 共 %d 行" % len(cur))
    else:
        print("\n[滚动 %d 次后] 共 %d 行" % (step, len(cur)))
    for name, c, t, y in cur:
        tag = "★已见" if name in seen else "  新增"
        seen.setdefault(name, 0)
        seen[name] += 1
        print("   %s %-30s (%d/%d)  y=%d" % (tag, name, c, t, y))
    # 在面板区域向上滑动
    explore.sh(["shell", "input", "swipe", "720", "2050", "720", "1150", "400"])
    time.sleep(3)

print()
print("=" * 66)
print("汇总：本次共见到 %d 个不同的任务" % len(seen))
for k, v in seen.items():
    print("   %-32s 出现 %d 次" % (k, v))

print()
print("=" * 66)
print("关键检查")
print("=" * 66)
hit = [k for k in seen if "搜" in k]
print("含「搜」的任务:", hit if hit else "★ 面板里根本没有")
# 面板底部/顶部辅助文案
allx = explore.dump_raw()
for k in ["搜一搜", "搜索", "浏览", "逛逛", "签到", "浏览15秒得", "浏览10s得"]:
    print("  页面含 %-10s : %s" % (k, k in allx))
