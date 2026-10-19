# -*- coding: utf-8 -*-
"""查「搜一搜」这段文案到底是哪个节点、在哪、可不可点。"""
import sys, os, re, time
sys.path.insert(0, os.getcwd())
import explore

nodes = explore.parse(explore.dump_raw())
vis = [n for n in nodes if n["label"].strip() and n["x2"] > n["x1"]]

print("面板已开:", any("肥料明细" in n["label"] for n in nodes))
print("可见节点数:", len(vis))

print()
print("=" * 70)
print("① 所有含「搜」的节点（完整文案 + 位置 + 可点击性）")
print("=" * 70)
for n in vis:
    if "搜" in n["label"]:
        print("  bounds=[%d,%d][%d,%d] clickable=%s class=%s" % (
            n["x1"], n["y1"], n["x2"], n["y2"], n["clickable"], n["cls"].split(".")[-1]))
        print("      label = %r" % n["label"])

print()
print("=" * 70)
print("② 面板内全部可见文案（按 y 排序，完整打印）")
print("=" * 70)
for n in sorted(vis, key=lambda n: (n["y1"], n["x1"])):
    print("  y=%5d-%5d x=%5d-%5d [%s] %-14s %r" % (
        n["y1"], n["y2"], n["x1"], n["x2"],
        "C" if n["clickable"] else " ", n["cls"].split(".")[-1], n["label"][:60]))

print()
print("=" * 70)
print("③ 带 (x/y) 的行 vs 不带的行")
print("=" * 70)
rx = re.compile(r"\((\d+)/(\d+)\)")
withp = [n["label"] for n in vis if rx.search(n["label"])]
print("  带进度括号的（%d 个）:" % len(withp))
for l in withp:
    print("     %r" % l)
alltxt = explore.sh(["shell", "cat", "/sdcard/_explore.xml"])
print("  原始 XML 里含「搜一搜」次数:", alltxt.count("搜一搜"))
for m in re.finditer(r'<node[^>]*搜一搜[^>]*>', alltxt):
    t = m.group(0)
    def g(k):
        mm = re.search(k + r'="([^"]*)"', t)
        return mm.group(1) if mm else ""
    print("     text=%r desc=%r clickable=%s bounds=%s" % (
        g("text"), g("content-desc"), g("clickable"), g("bounds")))
