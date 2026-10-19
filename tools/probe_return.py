# -*- coding: utf-8 -*-
"""决定性验证两个问题：
A. 任务面板「关闭」后，文案是否残留导致 isOnFarmHomePage() 永久失败（收尾误报「未确认回到农场首页」）
B. 点「去美团短视频领现金」后，按 1 次返回会落到哪个页面（用户投诉：返回 2 次直接到淘宝首页）
"""
import sys, os, re, time
sys.path.insert(0, os.getcwd())
import explore

OVERLAY = ["做任务赚肥料", "肥料明细", "滑动浏览得肥料", "浏览得肥料", "浏览得奖励",
           "浏览15秒得", "浏览10s得", "浏览5秒得", "每天答题领肥料", "今日答题",
           "继续施肥，免费水果送到家"]
HOME_MARKS = ["超级立减", "淘宝直播", "闪购", "百亿补贴", "试用领取", "今日推荐"]


def dump():
    """复刻 Locator.dumpTexts：非空 label + 有版面（近似屏幕内可见），'|' 连接。"""
    nodes = explore.parse(explore.dump_raw())
    return "|".join(n["label"] for n in nodes if n["label"].strip() and n["x2"] > n["x1"])


def focus():
    return explore.sh(["shell", "dumpsys", "window"]).split("mCurrentFocus")[-1].strip()


def judge(tag, snap):
    ov = [m for m in OVERLAY if m in snap]
    xy = re.findall(r"[^|]{0,24}\(\d+/\d+\)[^|]{0,8}", snap)
    hm = [m for m in HOME_MARKS if m in snap]
    farm = ("阳光农场" in snap) or ("集肥料" in snap)
    print("  [%s] 在农场页标记=%s" % (tag, farm))
    print("        overlay命中=%s  (x/y)命中=%s  首页标记命中=%s" % (ov or "无", xy or "无", hm or "无"))
    print("        => isOnFarmHomePage 应为: %s" % (
        "True ✓" if (farm and not ov and not xy and not hm) else "False ✗"))
    return ov, xy, hm


def find_btn(nodes, key):
    """按文案找 clickable 节点"""
    for n in nodes:
        if n["clickable"] and key in n["label"] and n["x2"] > n["x1"]:
            return n
    return None


print("=" * 66)
print("当前前台:", focus()[:80])

# ---------- 到达农场页 ----------
snap = dump()
if "集肥料" not in snap:
    xml = explore.dump_raw()
    nodes = explore.parse(xml)
    icon = find_btn(nodes, "芭芭农场")
    if icon:
        explore.sh(["shell", "input", "tap", str(icon["cx"]), str(icon["cy"])])
        time.sleep(20)
    snap = dump()
print("已在农场页:", "集肥料" in snap)

print()
print("=" * 66)
print("【验证 A】任务面板关闭后是否残留")
print("=" * 66)
print("A1. 打开面板前（基线）")
judge("基线", snap)

# 打开面板：点「集肥料」
nodes = explore.parse(explore.dump_raw())
entry = find_btn(nodes, "集肥料")
print("\nA2. 点「集肥料」@(%d,%d) 打开面板" % (entry["cx"], entry["cy"]))
explore.sh(["shell", "input", "tap", str(entry["cx"]), str(entry["cy"])])
time.sleep(10)
snap_open = dump()
print("  面板已开(含肥料明细):", "肥料明细" in snap_open)
ov, xy, _ = judge("面板打开", snap_open)

# 关闭面板：复刻 closeTaskPanel() —— 点「关闭」按钮
nodes = explore.parse(explore.dump_raw())
close = find_btn(nodes, "关闭")
print("\nA3. 点「关闭」按钮关闭面板", ("@(%d,%d)" % (close["cx"], close["cy"])) if close else "(未找到)")
if close:
    explore.sh(["shell", "input", "tap", str(close["cx"]), str(close["cy"])])
    time.sleep(4)
snap_closed = dump()
print("  面板标记还在?:", any(m in snap_closed for m in ["做任务赚肥料", "肥料明细"]))
judge("关闭后", snap_closed)

print()
print("  >>> A 结论：", end="")
_, xy_c, _ = judge("复核", snap_closed)
if xy_c:
    print("      ★ 面板已关但 (x/y) 文案残留 —— 这就是收尾误报「未确认回到农场首页」的根因")
else:
    print("      面板关闭后无残留，(x/y) 判据正常")

# ---------- 验证 B：美团任务 ----------
print()
print("=" * 66)
print("【验证 B】点「去美团短视频领现金」后的返回行为")
print("=" * 66)

# 重开面板
nodes = explore.parse(explore.dump_raw())
entry = find_btn(nodes, "集肥料")
if entry and "肥料明细" not in dump():
    explore.sh(["shell", "input", "tap", str(entry["cx"]), str(entry["cy"])])
    time.sleep(10)

nodes = explore.parse(explore.dump_raw())
# 找到「去美团短视频领现金」那一行，取同行右侧的按钮
mt = None
for n in nodes:
    if "美团" in n["label"] and n["x2"] > n["x1"]:
        mt = n
        break

if mt is None:
    print("面板里未找到美团任务行，跳过 B")
else:
    cy = (mt["y1"] + mt["y2"]) // 2
    row = sorted([n for n in nodes if n["clickable"] and n["x2"] > n["x1"]
                  and abs((n["y1"] + n["y2"]) // 2 - cy) <= 90],
                 key=lambda n: n["x1"])
    print("美团任务行: %r (y中心=%d)" % (mt["label"][:30], cy))
    for n in row:
        print("    同行按钮 x=%d-%d %r" % (n["x1"], n["x2"], n["label"][:30]))
    btn = row[-1] if row else None
    if btn:
        print("\n点该行按钮 @(%d,%d) %r" % (btn["cx"], btn["cy"], btn["label"][:20]))
        explore.sh(["shell", "input", "tap", str(btn["cx"]), str(btn["cy"])])
        for i in range(1, 7):
            time.sleep(4)
            f = focus()
            print("    跳转后 %2ds 前台: %s" % (i * 4, f[:78]))
            if "meituan" in f.lower() or "dianping" in f.lower():
                break

        print("\n--- 现在模拟脚本的 returnToFarmHome：按 1 次返回，等 2 秒后判断 ---")
        explore.sh(["shell", "input", "keyevent", "KEYCODE_BACK"])
        for w in [2, 4, 6, 9]:
            time.sleep(w if w == 2 else 2)
            f = focus()
            s = dump()
            onfarm = ("阳光农场" in s) or ("集肥料" in s)
            print("  BACK后约%2ds 前台=%-52s 在农场页=%s" % (w, f[:52], onfarm))
            if onfarm:
                print("    ★ 已回到农场页（说明 1 次返回就够，等够时间即可）")
                break

        print("\n--- 再按 1 次返回，看是否会退到淘宝首页 ---")
        explore.sh(["shell", "input", "keyevent", "KEYCODE_BACK"])
        time.sleep(3)
        f = focus()
        s = dump()
        onfarm = ("阳光农场" in s) or ("集肥料" in s)
        homemark = [m for m in HOME_MARKS if m in s]
        print("  第2次BACK后 前台=%-52s 在农场页=%s 首页标记=%s" % (f[:52], onfarm, homemark or "无"))
