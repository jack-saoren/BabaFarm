"""纯观察实验：只采样页面，不做任何点击/滑动。

目的：查清「明日7点后记得来领」弹窗的触发条件。
用法：python watch_popup.py [秒数，默认 180]
"""
import sys, os, re, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import explore

MARKS = {
    "明日弹窗": "明日7点后记得来领",
    "提醒按钮": "提醒我明天领",
    "任务面板": "肥料明细",
    "农场页": "阳光农场",
    "买多返多": "买多返多",
    "阅读页": "点击中部展示工具栏",
    "小说面板": "看书剧领肥料",
    "验证码": "拖动下方滑块",
    "首页": "淘宝首页",
}

def focus():
    return explore.sh(["shell", "dumpsys", "window"]).split("mCurrentFocus")[-1][:60].strip()

def sample():
    xml = explore.dump_raw()
    hits = {k: (v in xml) for k, v in MARKS.items()}
    return hits, focus()

def main():
    dur = int(sys.argv[1]) if len(sys.argv) > 1 else 180
    interval = 3.0
    print("纯观察：每 %.0f 秒采样一次，共 %d 秒。不做任何操作。" % (interval, dur))
    print("时间点            命中特征")
    print("-" * 60)
    t0 = time.time()
    last = None
    appeared_at = None
    while time.time() - t0 < dur:
        el = int(time.time() - t0)
        hits, f = sample()
        active = tuple(k for k, v in hits.items() if v)
        if active != last:
            print("[%3ds] %s" % (el, " + ".join(active) if active else "(无特征)"))
            print("        窗口: %s" % f)
            if "明日弹窗" in hits and appeared_at is None:
                appeared_at = el
            last = active
        time.sleep(interval)
    print("-" * 60)
    print("观察结束：弹窗%s出现" % ("在第 %d 秒" % appeared_at if appeared_at is not None else "【全程未】"))

if __name__ == "__main__":
    main()
