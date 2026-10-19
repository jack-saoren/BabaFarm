# -*- coding: utf-8 -*-
"""把 step3_shake 改成「只在可领取时点一次」（用户确认的需求）。"""
import io, os

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main",
                    "java", "com", "hilogic", "babafarm", "accessibility", "FarmWorkflow.kt")

with io.open(PATH, encoding="utf-8") as f:
    s = f.read()

MARK = "    private fun step3_shake(): Int {"
start = s.index(MARK)

# 花括号匹配找函数结尾
i = s.index("{", start)
depth = 0
end = None
while i < len(s):
    c = s[i]
    if c == '"':
        # 跳过字符串字面量
        i += 1
        while i < len(s) and s[i] != '"':
            if s[i] == '\\':
                i += 1
            i += 1
    elif c == '{':
        depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
    i += 1
assert end is not None, "未找到函数结尾"

NEW = '''    private fun step3_shake(): Int {
        ScriptLog.i("步骤5: 挖肥料")
        if (checkBlocked("步骤5")) return 0

        val node = Locator.findFirstByPriority(
            svc, Selectors.SHAKE_TEXTS, timeoutMs = 1500L,
            blacklist = Selectors.FARM_BLACKLIST_TEXTS
        )
        if (node == null) {
            ScriptLog.i("  未找到兔子图标，跳过")
            return 0
        }
        val label = buttonLabel(node)
        // 用户确认的需求：不要重复点，点一次就行。
        // 且只在【可领取】时点 —— 「生产中」代表肥料还没产出，点了没有任何效果。
        // 旧实现是 repeat(8) 循环点，节点一直在就空点 8 次（日志「摇一摇执行 8 次」），
        // 既无产出也是风控敏感行为。
        if (!label.contains(Selectors.SHAKE_READY_MARK)) {
            ScriptLog.i("  兔子当前不可领（$label），跳过")
            return 0
        }
        ScriptLog.i("  可领取，点一次：$label")
        Locator.click(svc, node)
        SystemClock.sleep(2500)
        // 领取后会弹提示，必须关掉，否则后续点击全部偏移
        svc.dismissPopups(2)
        return 1
    }'''

s = s[:start] + NEW + s[end:]

with io.open(PATH, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

print("OK")
for kw in ["repeat(8)", "摇一摇执行", "SHAKE_READY_MARK"]:
    print("  %-18s %d" % (kw, s.count(kw)))
