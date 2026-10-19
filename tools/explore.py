#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
芭芭农场 UI 勘探工具。

为什么用文本 dump 而不是截图：
  节点树文本包含 text/desc/clickable/bounds，信息密度远高于截图，
  且能直接给出可点击坐标。截图只在需要确认视觉语义时才用。

用法：
  python explore.py dump              # dump 当前页面（紧凑文本）
  python explore.py tap X Y           # 点击坐标
  python explore.py taptext "文案"    # 按文案找节点并点击
  python explore.py shot NAME         # 截图存到 .cache/NAME.png
  python explore.py wait SEC          # 等待
  python explore.py focus             # 只看前台 Activity
"""
import re
import subprocess
import sys
import time
import os

ADB = r"adb"
DEV = "YOUR_DEVICE_SERIAL"
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".cache")
REMOTE = "/sdcard/_explore.xml"


def sh(args, timeout=60):
    """执行 adb 命令（不走 shell，避免 Git Bash 路径转换）"""
    cmd = [ADB, "-s", DEV] + args
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", errors="ignore")


def dump_raw():
    sh(["shell", "uiautomator", "dump", REMOTE])
    return sh(["shell", "cat", REMOTE])


def parse(xml):
    """解析节点，返回 [(label, class, clickable, cx, cy, y1, y2)]"""
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)

        def g(k):
            mm = re.search(k + r'="([^"]*)"', t)
            return mm.group(1) if mm else ""

        label = (g("text") + g("content-desc")).strip()
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if not b:
            continue
        x1, y1, x2, y2 = (int(b.group(i)) for i in range(1, 5))
        out.append({
            "label": label,
            "cls": g("class"),
            "clickable": g("clickable") == "true",
            "cx": (x1 + x2) // 2,
            "cy": (y1 + y2) // 2,
            "y1": y1, "y2": y2, "x1": x1, "x2": x2,
            "id": g("resource-id"),
        })
    return out


def show_dump(clickable_only=False, max_label=30, max_items=60):
    nodes = parse(dump_raw())
    print(f"--- 节点总数 {len(nodes)} ---")
    seen = set()
    shown = 0
    for n in nodes:
        if clickable_only and not n["clickable"]:
            continue
        lbl = n["label"]
        if not lbl:
            continue
        if lbl.startswith("O1CN") or lbl.endswith((".jpg", ".png")):
            continue
        if len(lbl) > max_label:
            lbl = lbl[:max_label] + "…"
        key = (lbl, n["cx"], n["cy"])
        if key in seen:
            continue
        seen.add(key)
        flag = "C" if n["clickable"] else " "
        print(f"[{flag}] {lbl}  @({n['cx']},{n['cy']})")
        shown += 1
        if shown >= max_items:
            print("...(截断)")
            break
    return nodes


def focus():
    o = sh(["shell", "dumpsys", "window"])
    for line in o.splitlines():
        if "mCurrentFocus" in line:
            print(line.strip())
            return line.strip()
    return ""


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]

    if cmd == "focus":
        focus()
    elif cmd == "dump":
        show_dump(clickable_only=("-c" in sys.argv))
    elif cmd == "tap":
        x, y = int(sys.argv[2]), int(sys.argv[3])
        sh(["shell", "input", "tap", str(x), str(y)])
        print(f"tapped ({x},{y})")
    elif cmd == "taptext":
        kw = sys.argv[2]
        nodes = parse(dump_raw())
        hit = None
        for n in nodes:
            if n["clickable"] and kw in n["label"]:
                hit = n
                break
        if not hit:
            for n in nodes:
                if kw in n["label"]:
                    hit = n
                    break
        if hit:
            sh(["shell", "input", "tap", str(hit["cx"]), str(hit["cy"])])
            print(f'tapped "{hit["label"]}" @({hit["cx"]},{hit["cy"]})')
        else:
            print(f'NOT FOUND: {kw}')
    elif cmd == "shot":
        name = sys.argv[2] if len(sys.argv) > 2 else "shot"
        os.makedirs(CACHE, exist_ok=True)
        path = os.path.join(CACHE, name + ".png")
        r = subprocess.run([ADB, "-s", DEV, "exec-out", "screencap", "-p"],
                           capture_output=True, timeout=60)
        with open(path, "wb") as f:
            f.write(r.stdout)
        print(f"saved {path} ({len(r.stdout)} bytes)")
    elif cmd == "wait":
        s = float(sys.argv[2])
        time.sleep(s)
        print(f"waited {s}s")
    elif cmd == "back":
        sh(["shell", "input", "keyevent", "KEYCODE_BACK"])
        print("back")
    else:
        print(f"unknown: {cmd}")


if __name__ == "__main__":
    main()
