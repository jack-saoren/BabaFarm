# -*- coding: utf-8 -*-
"""
FarmWorkflow.kt 任务模块重构补丁。

做四件事（按位置从后往前改，避免索引偏移）：
  1. 替换 step7_finish：收尾必须回到【农场首页】（排除任务面板等浮层）
  2. 把 step6_dailyTasks..readTaskProgress 整段替换为动态任务模块
  3. 删除废弃的 step5_collect
  4. 删除写死的 FarmTaskDef/taskRegistry，改为范围策略
并顺带修正 run() 的步骤列表与结果汇总文案。
"""
import io
import os
import sys

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main",
                    "java", "com", "hilogic", "babafarm", "accessibility", "FarmWorkflow.kt")

with io.open(PATH, encoding="utf-8") as f:
    s = f.read()

orig_len = len(s)

# ---------------- 新 step7_finish ----------------
NEW_STEP7 = r'''    private fun step7_finish() {
        ScriptLog.i("步骤7: 收尾（回到农场首页）")
        // ⚠️ 这里【不能】用 isOnFarmPage() 判断。
        // 实测：任务面板是盖在农场页之上的 bottom sheet，面板打开时农场页节点
        // （阳光农场 / 集肥料 / 可施肥）依然完整存在且 clickable，
        // 于是 isOnFarmPage() 返回 true，旧实现直接打印「已在农场页，收尾完成」就收工，
        // 实际屏幕上留着任务面板 —— 这就是「任务做完没回到农场首页」的根因。
        // 因此改用 isOnFarmHomePage()：要求是农场页【且】无任何浮层特征。
        repeat(4) {
            if (svc.isOnFarmHomePage()) {
                ScriptLog.i("已回到农场首页，收尾完成")
                return
            }
            if (isTaskPanelOpen()) {
                ScriptLog.i("关闭任务面板")
                svc.closeTaskPanel()
            } else {
                Locator.pressBack(svc)
            }
            SystemClock.sleep(2000)
        }
        ScriptLog.w("收尾：未能确认回到农场首页，当前页面快照=" +
                Locator.dumpTexts(svc).take(300))
    }
}
'''

# ---------------- 新任务模块 ----------------
NEW_MODULE = r'''    // ---------------- 日常任务模块 ----------------

    /**
     * 任务分类。决定面板里的一行任务怎么处理。
     *
     * 分类依据全部来自真机实测的面板结构。
     */
    private enum class TaskKind(val label: String) {
        BROWSE("浏览类"),
        QUIZ("答题类"),
        CROSS_APP("跨App类")
    }

    /**
     * 面板里的一行任务。
     *
     * ⚠️ 为什么必须动态解析，而不是写死任务名：
     * 实测面板第一个任务叫 `看看#卫生抽纸(3/4)` —— 名字里带的是随机推荐商品标题，
     * 每次运行都可能变。早期代码把任务名写死成「发现精选好物」，
     * 一旦改名就完全对不上（日志表现为「未找到任务行」，整个任务空转）。
     */
    private data class TaskRow(
        val name: String,                       // 任务名（不含进度）
        val current: Int,                       // 已完成次数
        val total: Int,                         // 需要完成的总次数
        val button: AccessibilityNodeInfo       // 该行的操作按钮
    )

    /**
     * 解析当前面板里的全部任务行。前提：面板已打开。
     *
     * dumpTexts() 用 '|' 分隔节点，因此任务名不会跨节点，
     * 正则 `([^|]{2,60}?)\((\d+)/(\d+)\)` 能稳定切出「名称(当前/总数)」。
     */
    private fun readTaskRows(): List<TaskRow> {
        val snapshot = Locator.dumpTexts(svc)
        val rows = ArrayList<TaskRow>()
        val regex = Regex("([^|]{2,60}?)\\((\\d+)/(\\d+)\\)")
        for (m in regex.findAll(snapshot)) {
            val name = m.groupValues[1].trim()
            val cur = m.groupValues[2].toIntOrNull() ?: continue
            val total = m.groupValues[3].toIntOrNull() ?: continue
            if (name.isEmpty() || total <= 0) continue
            // 先用含进度的完整原文锚定，退化时再用纯名称
            val anchor = Locator.findFirstByTextsContaining(svc, listOf(m.value.trim()), timeoutMs = 1000L)
                ?: Locator.findFirstByTextsContaining(svc, listOf(name), timeoutMs = 500L)
            if (anchor == null) {
                ScriptLog.w("  任务行[$name] 定位不到节点，跳过")
                continue
            }
            val btn = Locator.findClickableInSameRow(svc, anchor)
            if (btn == null) {
                ScriptLog.w("  任务行[$name] 同行内无按钮，跳过")
                continue
            }
            rows.add(TaskRow(name, cur, total, btn))
        }
        return rows
    }

    private fun classify(row: TaskRow): TaskKind {
        val btn = row.button.text?.toString().orEmpty() +
                row.button.contentDescription?.toString().orEmpty()
        return when {
            Selectors.TASK_CROSS_APP_MARKS.any { row.name.contains(it) } -> TaskKind.CROSS_APP
            btn.contains("去逛逛") -> TaskKind.CROSS_APP
            Selectors.TASK_QUIZ_MARKS.any { row.name.contains(it) } -> TaskKind.QUIZ
            btn.contains("去答题") -> TaskKind.QUIZ
            else -> TaskKind.BROWSE
        }
    }

    private fun step6_dailyTasks(): Int {
        ScriptLog.i("步骤6: 日常任务")
        if (checkBlocked("步骤6")) return 0

        if (!openTaskPanel()) {
            ScriptLog.w("未能打开任务面板，跳过日常任务")
            errors.add("任务面板未打开")
            return 0
        }

        val rows = readTaskRows()
        ScriptLog.i("  面板识别到 ${rows.size} 个任务行")
        if (rows.isEmpty()) {
            errors.add("任务面板解析不到任务行")
            return 0
        }

        var done = 0
        for (row in rows) {
            if (blocked) break
            if (SystemClock.uptimeMillis() > overallDeadlineMs) {
                ScriptLog.w("  已到本轮时限，停止后续任务")
                break
            }
            val kind = classify(row)
            if (kind != TaskKind.BROWSE) {
                ScriptLog.i("  跳过[${row.name}]（${kind.label}，按既定范围不做）")
                continue
            }
            // 单任务失败隔离：一个任务失败不影响其它任务
            try {
                if (handleBrowseTask(row)) done++
            } catch (t: Throwable) {
                ScriptLog.e("  任务[${row.name}]异常: ${t.message}", t)
                errors.add("任务${row.name}异常")
                runCatching { returnToFarmHome() }
            }
        }

        ScriptLog.i("日常任务完成 $done 项")
        return done
    }

    /**
     * 打开任务面板（同时完成「收取肥料」）。
     *
     * 实测坑点一：点「集肥料」是有条件的——
     *   面板未开且有肥料可收 → 打开任务面板；
     *   肥料未成熟 → 只弹「明日7点后记得来领」，不展开面板。
     *
     * 实测坑点二（本次修复核心）：「集肥料」是【开关】。
     *   面板已打开时再点一次会把它关掉。早期实现在「未检测到面板」时重试点击，
     *   偶数次点击互相抵消，于是用户看到的就是「一直在重复开关任务列表」
     *   （日志特征：连续多行「未打开面板，重试」）。
     *   因此这里【绝不重复点击】，检测不到就如实返回 false。
     */
    private fun openTaskPanel(): Boolean {
        if (isTaskPanelOpen()) return true

        val entry = Locator.findFirstByPriority(
            svc, Selectors.TASK_ENTRY_TEXTS, timeoutMs = 3000L,
            blacklist = Selectors.FARM_BLACKLIST_TEXTS
        )
        if (entry == null) {
            ScriptLog.w("  未找到任务入口（集肥料）")
            return false
        }
        ScriptLog.i("  点击「集肥料」（收取 + 展开任务面板）")
        Locator.click(svc, entry)
        SystemClock.sleep(5000)
        if (isTaskPanelOpen()) return true

        // 未打开：先区分「肥料未成熟」这个确定性原因
        if (Locator.pageContainsAny(svc, *Selectors.FERTILIZER_NOT_READY_MARKS.toTypedArray())) {
            ScriptLog.w("  出现「肥料未成熟」提示，本次不展开任务面板")
            closeNotReadyDialog()
            return false
        }
        // 也可能只是渲染慢，再等一会。但绝不再点第二次（按钮是开关，会互抵）
        SystemClock.sleep(3000)
        val ok = isTaskPanelOpen()
        if (!ok) ScriptLog.w("  等待后仍未检测到任务面板（不再重复点击，避免开关互抵）")
        return ok
    }

    private fun isTaskPanelOpen(): Boolean =
        Locator.pageContainsAny(svc, *Selectors.TASK_PANEL_MARKS.toTypedArray())

    /** 关闭「肥料未成熟」提示弹窗（实测按钮文案「提醒我明天领」）。 */
    private fun closeNotReadyDialog() {
        val btn = Locator.findFirstByPriority(
            svc, listOf("提醒我明天领", "知道了", "我知道了"), timeoutMs = 1500L
        )
        if (btn != null) {
            Locator.click(svc, btn)
            SystemClock.sleep(1500)
        } else {
            Locator.pressBack(svc)
            SystemClock.sleep(1500)
        }
    }

    /** 按任务名定位该行的操作按钮。任务名是动态的，只能按名称锚定。 */
    private fun findRowButton(name: String): AccessibilityNodeInfo? {
        val anchor = Locator.findFirstByTextsContaining(svc, listOf(name), timeoutMs = 2500L)
            ?: return null
        return Locator.findClickableInSameRow(svc, anchor)
    }

    private fun buttonLabel(node: AccessibilityNodeInfo?): String =
        node?.text?.toString().orEmpty() + node?.contentDescription?.toString().orEmpty()

    /**
     * 读取某任务当前进度。面板未打开或读不到时返回 -1（未知）。
     * 读不到时【不猜测】——早期把「读不到」当成 0，导致「1/5 → 2/5 → 0/5」
     * 这种不可能的后退，进而误判「进度未推进」而放弃任务。
     */
    private fun readProgress(name: String): Int {
        if (!isTaskPanelOpen()) return -1
        val snapshot = Locator.dumpTexts(svc)
        val regex = Regex(Regex.escape(name) + "\\((\\d+)/(\\d+)\\)")
        val m = regex.find(snapshot) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }

    /**
     * 处理浏览类任务：按面板上显示的【剩余次数】逐次完成。
     *
     * 「按任务可完成的次数完成」的实现：次数直接取自面板的 (当前/总数)，
     * 即 plan = total - current。早期版本写死「单次最多 2 轮」，
     * 导致 3/4、3/5 这类任务永远做不满。
     *
     * 但仍保留两个闸门防止跑飞：
     *   - MAX_ROUNDS_PER_TASK：单任务轮次上限（防御解析异常）
     *   - taskDeadlineMs / overallDeadlineMs：时间闸门（防御被看门狗强杀）
     */
    private fun handleBrowseTask(row: TaskRow): Boolean {
        var current = row.current
        val total = row.total
        if (current >= total) {
            ScriptLog.i("  任务[${row.name}] 已完成($current/$total)，跳过")
            return false
        }
        val plan = minOf(total - current, MAX_ROUNDS_PER_TASK)
        taskDeadlineMs = SystemClock.uptimeMillis() + TASK_BUDGET_MS
        ScriptLog.i("  任务[${row.name}] 进度 $current/$total（还差 ${total - current} 次），本轮计划 $plan 次")

        var round = 0
        var anySuccess = false
        while (round < plan && !blocked) {
            val now1 = SystemClock.uptimeMillis()
            if (now1 > overallDeadlineMs || now1 > taskDeadlineMs) {
                ScriptLog.w("  任务[${row.name}] 触发时限闸门，本轮停止")
                break
            }
            round++
            ScriptLog.i("  任务[${row.name}] 第 $round/$plan 次")

            // 每轮都必须重新取节点：上一轮跳转回来后 AccessibilityNodeInfo 已失效，
            // 且面板可能已被系统关闭，必须重新确保它是打开的。
            if (!openTaskPanel()) {
                ScriptLog.w("  任务面板不可用，停止该任务")
                break
            }
            val btn = findRowButton(row.name)
            if (btn == null) {
                ScriptLog.w("  未找到任务行[${row.name}]的按钮，停止该任务")
                break
            }
            if (buttonLabel(btn).contains("已完成")) {
                ScriptLog.i("  任务[${row.name}] 按钮已显示「已完成」")
                break
            }
            if (!Locator.click(svc, btn)) {
                ScriptLog.w("  任务[${row.name}] 按钮点击失败，停止该任务")
                break
            }
            SystemClock.sleep(7000)

            if (checkBlocked("任务浏览页")) break

            browseInPage(times = 4)
            claimRewardIfAny()
            returnToFarmHome()
            SystemClock.sleep(2000)

            // 返回后面板通常已关闭，必须重新打开才能读进度
            if (!openTaskPanel()) {
                ScriptLog.w("  返回后无法重新打开任务面板，停止该任务")
                break
            }
            val now = readProgress(row.name)
            if (now < 0) {
                // 读不到：可能是任务已完成（行内不再显示 (x/y)），用按钮文案兜底确认
                if (buttonLabel(findRowButton(row.name)).contains("已完成")) {
                    ScriptLog.i("  任务[${row.name}] 已完成（进度标记消失且按钮显示已完成）")
                    anySuccess = true
                } else {
                    ScriptLog.w("  任务[${row.name}] 返回后读不到进度，停止该任务")
                }
                break
            }
            ScriptLog.i("  任务[${row.name}] 第 $round 次结束，进度 $now/$total")
            if (now >= total) {
                ScriptLog.i("  任务[${row.name}] 已做满 $total/$total")
                anySuccess = true
                break
            }
            if (now <= current) {
                ScriptLog.w("  任务[${row.name}] 进度未推进（$current → $now），停止该任务")
                break
            }
            current = now
            anySuccess = true
        }
        return anySuccess
    }

    /** 滑动浏览。实测详情页为商品瀑布流，滑动即计入浏览时长。 */
    private fun browseInPage(times: Int) {
        repeat(times) {
            if (blocked) return
            Locator.scrollDown(svc)
            SystemClock.sleep(5000) // 每次停留 5 秒，4 次≈20 秒，满足「浏览15秒」
        }
    }

    /** 领取浏览奖励（实测奖励弹窗文案候选）。 */
    private fun claimRewardIfAny() {
        val claim = Locator.findFirstByPriority(
            svc, listOf("开心收下", "收下", "领取奖励", "领取"),
            timeoutMs = 2000L,
            blacklist = Selectors.FARM_BLACKLIST_TEXTS
        )
        if (claim != null) {
            ScriptLog.i("  领取浏览奖励")
            Locator.click(svc, claim)
            SystemClock.sleep(2000)
        }
        // 面板打开时绝不清弹窗 —— 面板自己的关闭按钮文案就是「关闭」
        if (!isTaskPanelOpen()) svc.dismissPopups(1)
    }

    /**
     * 从浏览详情页退回农场页。
     * 只负责「回到农场」，面板是否打开由调用方用 openTaskPanel() 处理。
     */
    private fun returnToFarmHome() {
        repeat(3) {
            if (svc.isOnFarmPage()) return
            Locator.pressBack(svc)
            SystemClock.sleep(2500)
        }
        ScriptLog.w("  返回农场页失败")
    }

'''

# ---------------- 新范围策略（替换写死的任务注册表） ----------------
NEW_POLICY = r'''    /**
     * 任务范围策略（用户决策 + 真机实测共同确定）。
     *
     * 实测面板结构（2026-10-14 SM-G9550 / Android 9 / 淘宝 10.66.22）：
     *   y=1067  看看#卫生抽纸(3/4)         去完成  +700     浏览15秒得
     *   y=1323  农场百科问答(0/1)           去答题  +150~500
     *   y=1582  看严选推荐商品(2/5)         去完成  +600     浏览得奖励
     *   y=1841  逛逛支付宝芭芭农场(0/1)     去完成  +2300
     *   y=2096  逛一逛支付宝蚂蚁森林(0/1)    去逛逛  +300
     *   y=2355  去红包签到得肥料(0/1)       去完成  +300     浏览10s得
     *   y=2611  领百亿外卖红包(0/1)         去完成  +300     浏览5秒得
     *
     * 关键变化（相比之前的写死注册表）：
     * 1) 任务名不再写死。第一个任务名带随机商品标题（`看看#卫生抽纸`），
     *    写死必然对不上。改为每次运行从面板动态解析。
     * 2) 新增两个此前遗漏的淘宝内浏览任务：`去红包签到得肥料`、`领百亿外卖红包`。
     * 3) 答题类与跨 App（支付宝）类按用户决策跳过，判断规则见
     *    Selectors.TASK_QUIZ_MARKS / TASK_CROSS_APP_MARKS。
     */

    /** 本轮整体时限（由 run() 设置）。看门狗 12 分钟会强杀，这里提前收手。 */
    private var overallDeadlineMs = 0L

    /** 当前任务时限（由 handleBrowseTask 设置）。 */
    private var taskDeadlineMs = 0L

'''

# ---------------- 1) 尾部：step7_finish ----------------
g = s.index("    private fun step7_finish() {")
s = s[:g] + NEW_STEP7

# ---------------- 2) 任务模块主体 ----------------
f = s.index("    private fun step6_dailyTasks(): Int {")
e = s.index("    /**\n     * 处理点击施肥后弹出的「次数选择 + 确认」面板。")
s = s[:f] + NEW_MODULE + s[e:]

# ---------------- 3) 删除 step5_collect ----------------
c = s.index("    /**\n     * 收取作物/肥料产物。")
d = s.index("    /**\n     * 日常任务。")
s = s[:c] + s[d:]

# ---------------- 4) 替换任务范围策略 ----------------
a = s.index("    /**\n     * 任务定义（全部来自真机实测）。")
b = s.index("    /** 本轮是否被风控拦截。")
s = s[:a] + NEW_POLICY + s[b:]

# ---------------- 5) run() 补丁 ----------------
old1 = """    fun run(): String {
        val start = SystemClock.uptimeMillis()
        try {
"""
new1 = """    fun run(): String {
        val start = SystemClock.uptimeMillis()
        // 内部时限：看门狗会强杀服务，必须让自己先走到收尾，而不是被杀在半路
        overallDeadlineMs = start + OVERALL_BUDGET_MS
        taskDeadlineMs = 0L
        try {
"""
assert old1 in s, "run() 开头未匹配"
s = s.replace(old1, new1, 1)

old2 = """            val shaken = step3_shake()
            val fertilized = step4_fertilize()
            val collected = step5_collect()
            val tasks = step6_dailyTasks()
            step7_finish()

            if (blocked) {
                return "已中止: 执行途中被风控拦截，已完成 施肥${fertilized}次/挖肥料${shaken}次/收取${collected}次/任务${tasks}项"
            }

            val elapsed = SystemClock.uptimeMillis() - start
            return "施肥${fertilized}次 / 摇一摇${shaken}次 / 收取${collected}次 / 任务${tasks}项 " +
"""
new2 = """            val shaken = step3_shake()
            val fertilized = step4_fertilize()
            // ⚠️ 原「步骤5 收取产物」已删除。
            // 它把「集肥料」当成逐次收取的目标循环 15 次，而「集肥料」实际是
            // 【任务面板开关】：每轮都是「点开面板 → 清弹窗把面板关掉」，
            // 日志表现为连续 15 行「关闭弹窗」、耗时 44 秒、产出为 0 ——
            // 也就是用户看到的「一直在重复开关任务列表」。
            // 收取动作已并入 openTaskPanel()：点一次「集肥料」即完成收取并展开面板。
            val tasks = step6_dailyTasks()
            step7_finish()

            if (blocked) {
                return "已中止: 执行途中被风控拦截，已完成 施肥${fertilized}次/挖肥料${shaken}次/任务${tasks}项"
            }

            val elapsed = SystemClock.uptimeMillis() - start
            return "施肥${fertilized}次 / 摇一摇${shaken}次 / 任务${tasks}项 " +
"""
assert old2 in s, "run() 步骤列表未匹配"
s = s.replace(old2, new2, 1)

# ---------------- 6) companion 常量 ----------------
old3 = "        /** 施肥确认按钮候选文案 */"
new3 = """        /** 单任务轮次上限。防御解析异常导致的死循环（正常不会触及）。 */
        private const val MAX_ROUNDS_PER_TASK = 8

        /** 单任务时限。 */
        private const val TASK_BUDGET_MS = 150 * 1000L

        /**
         * 整轮内部时限。
         * 看门狗 TOTAL_TIMEOUT_MS 是 12 分钟且会【强杀】服务，
         * 因此这里提前到 9 分钟收手，让自己先走完收尾，而不是被杀在半路。
         */
        private const val OVERALL_BUDGET_MS = 9 * 60 * 1000L

        /** 施肥确认按钮候选文案 */"""
assert old3 in s, "companion 锚点未匹配"
s = s.replace(old3, new3, 1)

with io.open(PATH, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

print("OK  %d -> %d bytes" % (orig_len, len(s)))
for kw in ["step5_collect", "taskRegistry", "FarmTaskDef", "backToTaskPanel", "readTaskProgress"]:
    print("  残留 %-18s : %d" % (kw, s.count(kw)))
