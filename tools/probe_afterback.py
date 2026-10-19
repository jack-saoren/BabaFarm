# -*- coding: utf-8 -*-
"""验证：在农场页按一次返回，页面是否会进入「含浮层特征」的状态。
目的：查清 `收尾：未确认回到农场首页（在农场页=true）` 到底命中了哪个判据。
"""
import sys, os, re, time
sys.path.insert(0, os.getcwd())
import explore

OVERLAY = ["做任务赚肥料", "肥料明细", "滑动浏览得肥料", "浏览得肥料", "浏览得奖励",
           "浏览15秒得", "浏览10s得", "浏览5秒得", "每天答题领肥料", "今日答题",
           "继续施肥，免费水果送到家"]
HOME_MARKS = ["超级立减", "淘宝直播", "闪购", "百亿补贴", "试用领取", "今日推荐"]
FARM_MARK = ["阳光农场", "集肥料", "可施肥"]


def dump():
    nodes = explore.parse(explore.dump_raw())
    return "|".join(n["label"] for n in nodes if n["label"].strip() and n["x2"] > n["x1"])


def focus():
    return explore.sh(["shell", "dumpsys", "window"]).split("mCurrentFocus")[-1].strip()


def judge(tag, snap):
    farm = any(m in snap for m in FARM_MARK)
    ov = [m for m in OVERLAY if m in snap]
    xy = re.findall(r"[^|]{0,24}\(\d+/\d+\)[^|]{0,8}", snap)
    hm = [m for m in HOME_MARKS if m in snap]
    ok = farm and not ov and not xy and not hm
    print("  [%-14s] 农场标记=%-5s overlay=%-28s (x/y)=%-22s 首页=%s" % (
        tag, farm, str(ov or "无")[:28], str(xy or "无")[:22], hm or "无"))
    print("  %-16s => isOnFarmPage=%s  isOnFarmHomePage=%s" % ("", farm and not hm, ok))
    return ok


print("前台:", focus()[:80])

# 导航到农场页
snap = dump()
if not any(m in snap for m in FARM_MARK):
    for _ in range(4):
        time.sleep(8)
        nodes = explore.parse(explore.dump_raw())
        icon = next((n for n in nodes if n["clickable"] and "芭芭农场" in n["label"]), None)
        if icon:
            explore.sh(["shell", "input", "tap", str(icon["cx"]), str(icon["cy"])])
            time.sleep(20)
            break
    snap = dump()
print("已在农场页:", any(m in snap for m in FARM_MARK))
print()

print("=" * 70)
print("阶段 1：不按返回（现状基线）")
print("=" * 70)
judge("基线A", snap)

print()
print("=" * 70)
print("阶段 2：在农场页按 1 次返回（复刻 step7 收尾里那次动作）")
print("=" * 70)
explore.sh(["shell", "input", "keyevent", "KEYCODE_BACK"])
print("已发 BACK，前台现在:", focus()[:70])
for t in [2, 5, 9]:
    time.sleep(3 if t == 2 else (3 if t == 5 else 4))
    s = dump()
    print("\n  --- BACK 后约 %ds ---" % t)
    print("      前台:", focus()[:70])
    judge("BACK+%ds" % t, s)

print()
print("=" * 70)
print("阶段 3：再次按返回（复刻第 2 次，看是否会到淘宝首页）")
print("=" * 70)
explore.sh(["shell", "input", "keyevent", "KEYCODE_BACK"])
time.sleep(4)
s = dump()
print("  前台:", focus()[:70])
judge("BACK×2", s)
