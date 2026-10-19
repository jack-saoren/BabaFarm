# -*- coding: utf-8 -*-
"""验证 isOnFarmHomePage() 的两个判据在真实农场首页上的表现。
重点：农场首页是否存在 (x/y) 形态文案（会让收尾误判为「未回到农场首页」）。
"""
import sys, os, re, time
sys.path.insert(0, os.getcwd())
import explore


def texts():
    """复刻 Locator.dumpTexts：深度优先收集非空 label，用 '|' 连接。"""
    nodes = explore.parse(explore.dump_raw())
    return "|".join(n["label"] for n in nodes if n["label"].strip())


def go_farm():
    xml = explore.dump_raw()
    if "阳光农场" in xml or "集肥料" in xml:
        return True
    nodes = explore.parse(xml)
    icon = next((n for n in nodes if n["clickable"] and "芭芭农场" in n["label"]), None)
    if icon is None:
        # 可能在 Welcome / 首页未加载完
        for _ in range(3):
            time.sleep(8)
            nodes = explore.parse(explore.dump_raw())
            icon = next((n for n in nodes if n["clickable"] and "芭芭农场" in n["label"]), None)
            if icon:
                break
    if icon is None:
        return False
    explore.sh(["shell", "input", "tap", str(icon["cx"]), str(icon["cy"])])
    time.sleep(20)
    return "阳光农场" in explore.dump_raw() or "集肥料" in explore.dump_raw()


OVERLAY = ["做任务赚肥料", "肥料明细", "滑动浏览得肥料", "浏览得肥料", "浏览得奖励",
           "浏览15秒得", "浏览10s得", "浏览5秒得", "每天答题领肥料", "今日答题",
           "继续施肥，免费水果送到家"]
HOME_MARKS = ["超级立减", "淘宝直播", "闪购", "百亿补贴", "试用领取", "今日推荐"]

print("前台:", explore.sh(["shell", "dumpsys", "window"]).split("mCurrentFocus")[-1][:80])
print("进场成功:", go_farm())
time.sleep(3)

snap = texts()
print("\n快照长度:", len(snap))
print("\n=== ① isOnFarmHomePage 判据1：FARM_OVERLAY_MARKS 是否误命中 ===")
for m in OVERLAY:
    if m in snap:
        print("   ★ 命中（会导致误判）: %r" % m)
print("  （无输出=全部未命中 ✓）")

print("\n=== ② isOnFarmHomePage 判据2：(x/y) 正则是否误命中 ===")
hits = re.findall(r"[^|]{0,30}\(\d+/\d+\)[^|]{0,10}", snap)
if hits:
    print("   ★ 命中（会导致误判）:")
    for h in hits:
        print("      %r" % h)
else:
    print("  未命中 ✓")

print("\n=== ③ isOnFarmPage 判据：HOME_PAGE_MARKS 是否误命中 ===")
for m in HOME_MARKS:
    if m in snap:
        print("   ★ 命中: %r" % m)
print("  （无输出=未命中 ✓）")

print("\n=== ④ 农场页关键标记 ===")
for k in ["阳光农场", "集肥料", "可施肥", "施肥", "兔兔挖肥料"]:
    print("  %s: %s" % (k, k in snap))

print("\n=== ⑤ 全部可见文案（去重后，用于人工核对）===")
items = [x for x in snap.split("|") if x.strip()]
seen = []
for x in items:
    if x not in seen:
        seen.append(x)
for x in seen:
    print("   %r" % x)
