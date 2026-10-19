package com.hilogic.babafarm.accessibility

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.hilogic.babafarm.core.Locator
import com.hilogic.babafarm.core.ScriptLog
import com.hilogic.babafarm.core.Selectors
import com.hilogic.babafarm.service.FarmForegroundService

/**
 * 芭芭农场自动化状态机。
 *
 * 流程（农场内的顺序按用户指定）：
 *   冷启动淘宝 → 清弹窗 → 进入农场
 *   → ① 领每日肥料（右侧「N，肥料，明日7点可领」，可点才领）
 *   → ② 日常任务
 *   → ③ 挖肥料 → 施肥循环（施肥到 0 → 领等级条上方的肥料 → 有肥料就继续施肥）
 *   → 收尾（回到农场首页）
 *
 * 三条贯穿始终的工程原则：
 * 1. 每步都「先校验页面状态，再执行动作」，绝不盲点。
 * 2. 每个动作都有语义(文案/id)与坐标两条路径，语义失效时降级而非失败。
 * 3. 单步失败不终止整轮；把能做的做完，最后汇总报告。
 */
class FarmWorkflow(private val svc: FarmAccessibilityService) {

companion object {
        private const val TAOBAO_PKG = "com.taobao.taobao"

        /**
         * 「我的淘宝」页芭芭农场图标的 viewId。
         * 实测该入口是纯图片节点，text/desc 均为"芭芭农场"，
         * 因此优先用文案匹配，viewId 仅作兜底（若后续版本改版后文案也变了）。
         */
    private const val FARM_ICON_ID = "com.taobao.taobao:id/icon"

        /**
         * 施肥次数选择面板的特征文案（实测：「每次施肥5次，未选择」）。
         * 出现它说明点开了面板，还需要再点一次确认才真正施肥。
         */
        private val FERTILIZE_PANEL_MARK = listOf("未选择", "每次施肥")

        /** 单任务轮次上限。防御解析异常导致的死循环（正常不会触及）。 */
        private const val MAX_ROUNDS_PER_TASK = 8

        /** 任务进度在面板里的形态：(当前/总数)。 */
        private val PROGRESS_PATTERN = Regex("\\((\\d+)/(\\d+)\\)")

        /**
         * 领取「每日肥料」的时间截止（HHMM，超过就不点）。
         *
         * 用户要求：过了 07:30 不要再点那个「明日7点可领」按钮。
         * 实测理由：上午之后按钮文案变成 `N，肥料，明日7点可领`，
         * 点它领不到肥料，还会弹「明日7点后记得来领」浮窗 ——
         * 而那个浮窗会整块盖住「集肥料」，导致后面任务面板打不开
         * （实测 15:25 探测脚本因此找不到任务入口）。
         */
        private const val CLAIM_DAILY_DEADLINE_HHMM = 730

        /**
         * 步骤3「领取每日肥料」总开关。
         *
         * true  = **正常执行**（当前值）：时刻判断交给下面的 07:30 闸门。
         * false = 屏蔽：`step2b_claimDailyFertilizer()` 在任何时刻都不被调用。
         * 开关存在的意义：临时屏蔽该步骤做验证时，只改这一行，不动流程代码。
         * （2026-10-15 17:10 曾置 false 做验证，17:21 按用户要求恢复为 true。）
         */
        private const val ENABLE_STEP_CLAIM_DAILY = true

        /**
         * 当前时间是否已过「领取每日肥料」的截止时刻（07:30）。
         *
         * 放在 `run()` 的调用处使用 —— 超时就【完全不调用】步骤3，
         * 而不是进方法里提前 return（那样方法仍会被调用、日志仍会打）。
         */
        private fun isPastDailyClaimDeadline(): Boolean {
            val c = java.util.Calendar.getInstance()
            val hhmm = c.get(java.util.Calendar.HOUR_OF_DAY) * 100 +
                    c.get(java.util.Calendar.MINUTE)
            return hhmm > CLAIM_DAILY_DEADLINE_HHMM
        }

        /**
         * 单任务时限（动态计算，见 handleBrowseTask）。
         *
         * 2026-10-19 实测：一个浏览轮次约 45~50 秒（点击等待 + 浏览 30 秒 + 返回 +
         * 重开面板 + 读进度），固定 150 秒只够 3 轮，`发现精选好物(0/8)` 永远做不满。
         * 现在按 plan 动态计算，并由调用方保证给「看小说」步骤留出时间。
         */
        private const val TASK_BUDGET_PER_ROUND_MS = 60_000L
        /** 单任务动态时限上限。 */
        private const val TASK_BUDGET_MAX_MS = 8 * 60 * 1000L
        /** 给「看小说」步骤预留的时间（6 分钟阅读 + 2 分钟进出余量）。 */
        private const val NOVEL_RESERVED_MS = 8 * 60 * 1000L

        /**
         * 按返回后等待「回到农场页」的上限。
         *
         * 实测依据：从淘宝内置浏览器（com.taobao.browser.BrowserActivity）返回时，
         * 页面要先经过 com.alibaba.wireless.security 过渡态，
         * +2s/+4s/+6s 都还没回来，直到 +9s 才真正回到农场页。
         * 取 12 秒留出余量。
         */
        private const val RETURN_TO_FARM_WAIT_MS = 12_000L

        /**
         * 「稳定确认」的短等时长（用于 returnToFarmHome 入口）。
         *
         * 实测依据：从【农场页本身】按返回会【立即】退出农场到淘宝首页，
         * 而农场 H5 重绘期间 isOnFarmPage() 会瞬时为 false。
         * 短等 2.5 秒足以让重绘结束（实测重绘约 1.2 秒恢复），
         * 从而避免把重绘误判成「不在农场页」而误按返回退掉农场。
         */
        private const val SETTLE_WAIT_MS = 2_500L

        /**
         * 「点开任务面板后最多等多久」（30 秒上限）。
         *
         * 只在**真的点了「集肥料」**这条路径上生效 —— 面板本来就开着时
         * `openTaskPanel()` 会提前 return。
         *
         * ⚠️ 2026-10-19：不再固定睡满 30 秒，改为「就绪即继续」（见 PANEL_READY_POLL_MS）；
         *   单任务时限也已改为按 plan 动态计算，不再有「150 秒 / 5 轮」这种写死关系。
         */
        private const val PANEL_SETTLE_WAIT_MS = 30_000L

        /**
         * 面板就绪轮询间隔。
         *
         * 2026-10-19 实测：点「集肥料」后任务面板通常 1~2 秒就渲染出任务行，
         * 旧实现固定睡满 30 秒（两轮运行共白等 60 秒）。现在最多等 30 秒，
         * 一旦检测到面板/任务行就立即继续。
         */
        private const val PANEL_READY_POLL_MS = 800L

        /**
         * 步骤「看小说领肥料」的总时长（6 分钟）。
         * 实测里程碑：30秒 +300 / 2分钟 +600 / 5分钟 +900，6 分钟已能拿满 5 分钟档。
         */
        private const val NOVEL_READ_MS = 6 * 60 * 1000L

        /** 步骤「看小说」的翻页间隔（5 秒）—— 模拟真人阅读，避免风控。 */
        private const val NOVEL_PAGE_INTERVAL_MS = 5_000L

        /** 「看书剧领肥料」面板出现的等待上限（点击后轮询，不再固定 7 秒）。 */
        private const val NOVEL_PANEL_WAIT_MS = 20_000L
        /** 书籍卡片加载的等待上限（面板打开后书单仍会先显示「加载中」）。 */
        private const val NOVEL_CARDS_WAIT_MS = 20_000L
        /** 点书后进入阅读页的等待上限（轮询）。 */
        private const val NOVEL_READER_WAIT_MS = 12_000L
        /** 点击任务「去完成」后等待页面跳转的上限（轮询替代固定 7 秒）。 */
        private const val TASK_PAGE_CHANGE_WAIT_MS = 8_000L

        /** 施肥循环的单次总时限（肥料多时可能要施很多次）。 */
        private const val FERTILIZE_BUDGET_MS = 240 * 1000L

        /** 施肥点击次数上限。实测「可施肥32次」，留足余量防死循环。 */
        private const val MAX_FERTILIZE_ROUNDS = 50

        /** 「施肥到 0 → 领等级条肥料」最多循环几轮。 */
        private const val MAX_FERTILIZE_CLAIM_ROUNDS = 6

        /**
         * 整轮内部时限。
         * 看门狗 TOTAL_TIMEOUT_MS 是 18 分钟且会【强杀】服务，
         * 因此这里提前到 15 分钟收手，让自己先走完收尾，而不是被杀在半路。
         * （原 10 分钟是按「施肥到肥料为 0」定的；新增「看小说领肥料」需驻留
         *   6 分钟，10 分钟不够，故放宽到 15 分钟，看门狗相应放到 18 分钟。）
         */
        private const val OVERALL_BUDGET_MS = 15 * 60 * 1000L

        /** 施肥确认按钮候选文案 */
        private val FERTILIZE_CONFIRM_TEXTS = listOf("确认", "确定", "施用", "立即施肥")

        /**
         * 风控/验证码页面的特征文案（真机实测）。
         *
         * ⚠️ 这是实测踩到过的真实场景：短时间内反复导航会触发淘宝滑块验证。
         * 出现这些文案说明已被风控拦截，此时【必须立即终止】而不是继续点击——
         * 继续点只会加重风控，甚至影响账号。
         *
         * 实测节点特征：
         *   验证码拦截 / 亲，请拖动下方滑块完成验证 / 向右滑动验证 / 通过验证以确保正常访问
         *   滑块按钮位于 (244,1711)
         */
        private val CAPTCHA_MARKS = listOf(
            "拖动下方滑块",
            "向右滑动验证",
            "滑动验证",
            "通过验证以确保正常访问",
            "验证码拦截",
            "安全验证",
            "请完成验证"
        )
    }

    private val errors = mutableListOf<String>()

    /**
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

    /** 本轮整体时限（由 run() 设置）。看门狗 18 分钟会强杀，这里提前收手。 */
    private var overallDeadlineMs = 0L

    /** 当前任务时限（由 handleBrowseTask 设置）。 */
    private var taskDeadlineMs = 0L

    /** 本轮是否被风控拦截。一旦为 true，所有后续步骤立即短路。 */
    @Volatile
    private var blocked = false

    /**
     * 检测是否被风控/验证码拦截。
     * 每个步骤开始前都会调用，一旦命中立即中止整个流程。
     */
    private fun checkBlocked(stepName: String): Boolean {
        // ★ 先确认前台是淘宝，再检测验证码文案。
        // 起因（实测）：用户在本应用里打开「运行日志」面板时，
        // 日志里恰好打印过「特征文案: 拖动下方滑块」这一句，
        // 而 dumpTexts() 扫的是【屏幕可见文案】—— 于是把自己的日志当成了验证码，
        // 导致「明明没有风控，按立即执行却一律被拦」。
        // 验证码只可能出现在淘宝里；本应用里出现这些词只可能是日志文本。
        if (Locator.currentPackage(svc) != TAOBAO_PKG) return false
        val snapshot = Locator.dumpTexts(svc)
        val hit = CAPTCHA_MARKS.firstOrNull { snapshot.contains(it) }
        if (hit != null) {
            if (!blocked) {
                blocked = true
                ScriptLog.e("⚠️ 检测到风控拦截[$stepName]，特征文案: $hit")
                ScriptLog.e("⚠️ 已立即终止本轮任务。请手动完成滑块验证后再试，")
                ScriptLog.e("⚠️ 否则继续自动化操作可能导致账号被风控。")
                // 无人值守场景下必须主动告警，否则用户不知道需要人工介入
                FarmForegroundService.notifyBlocked(svc.applicationContext)
            }
            return true
        }
        return false
    }

    fun run(): String {
        val start = SystemClock.uptimeMillis()
        // 内部时限：看门狗会强杀服务，必须让自己先走到收尾，而不是被杀在半路
        overallDeadlineMs = start + OVERALL_BUDGET_MS
        taskDeadlineMs = 0L
        try {
            // 启动前先检查，避免在验证码页上白跑一遍
            if (checkBlocked("启动前")) {
                return "已中止: 检测到淘宝风控验证码，请手动完成滑块验证后再试"
            }

            step0_launchTaobao()
            step1_clearPopups()
            step2_enterFarm()

            // ===== 农场内的执行顺序（按用户指定的流程）=====
            //   ① 先领每日肥料：右侧「N，肥料，明日7点可领」可点就领（不含「明日」即已可领）
            //   ② 再做日常任务（任务逻辑不变）
            //   ③ 最后施肥循环：施肥到肥料为 0 → 点【等级条上方的「领取」】领肥料
            //      → 领到肥料 > 0 就继续施肥，直到领不到为止
            // 注：函数名沿用历史命名，与下面的步骤编号不是一一对应的，编号只看日志。
            // ★ 步骤3：不再用固定 07:30 硬闸门一刀切。
            //   2026-10-19 实测：14:20 右侧按钮文案是「1500，肥料，点击领取」
            //   （不含「明日」），说明当天奖励还没领时下午依然可领；旧闸门会直接跳过。
            //   现在总是调用，由方法内部按按钮文案判断：含「明日」才不点。
            if (!ENABLE_STEP_CLAIM_DAILY) {
                ScriptLog.i("步骤3: 领取每日肥料")
                ScriptLog.i("  已【屏蔽】本步骤（ENABLE_STEP_CLAIM_DAILY=false），不做任何点击")
            } else {
                if (isPastDailyClaimDeadline()) {
                    ScriptLog.i("步骤3: 领取每日肥料")
                    ScriptLog.i("  已过 ${CLAIM_DAILY_DEADLINE_HHMM / 100}:" +
                            "${CLAIM_DAILY_DEADLINE_HHMM % 100}，仅当按钮显示可领取时才点击")
                }
                step2b_claimDailyFertilizer()      // 日志里的「步骤3」
            }
            val tasks = step6_dailyTasks()          // 日志里的「步骤4」
            // ★ 步骤4 结束时可能仍留着任务面板，必须在这里收干净。
            // step6_dailyTasks() 有 4 个 return 点（未打开面板 / 解析不到任务行 /
            // 没有符合范围的任务 / 正常结束），每一个都可能把面板留在屏幕上。
            // 实测后果（18:14 那轮日志的直接证据）：
            //   · 步骤6 报「未找到施肥按钮，停止施肥」—— 施肥按钮被面板遮住；
            //   · 收尾 isOnFarmPage() 被判 false（面板底部商品区含首页文案）
            //     → 白等 12 秒，超时后误按一次返回；
            //   · 收尾明细：命中浮层文案=[肥料明细, 每天答题领肥料]，
            //     进度括号=[(0/1)(0/1)(0/3)(0/1)(0/1)(0/1)(0/1)]，即面板从步骤4 起没关过。
            closeTaskPanelQuiet()
            val shaken = step3_shake()              // 日志里的「步骤5」
            val fertilized = step4_fertilize()      // 日志里的「步骤6」
            step8_readNovel()                      // 日志里的「步骤7」（看小说领肥料）
            step7_finish()                          // 日志里的「步骤8」（收尾）

            if (blocked) {
                return "已中止: 执行途中被风控拦截，已完成 施肥${fertilized}次/挖肥料${shaken}次/任务${tasks}项"
            }

            val elapsed = SystemClock.uptimeMillis() - start
            return "施肥${fertilized}次 / 摇一摇${shaken}次 / 任务${tasks}项 " +
                    "耗时${elapsed / 1000}s" + if (errors.isEmpty()) "" else " | 异常: ${errors.joinToString("; ")}"
        } catch (t: Throwable) {
            ScriptLog.e("工作流异常: ${t.message}", t)
            // 抓一份现场快照，是排查改版的第一手证据
            ScriptLog.w("现场节点快照: ${Locator.dumpTexts(svc).take(2000)}")
            return "执行异常: ${t.message}"
        }
    }

    // ---------------- 各步骤 ----------------

    private fun step0_launchTaobao() {
        ScriptLog.i("步骤0: 启动淘宝")
        svc.dismissPopups(2)
        // FarmWorkflow 不是 Context，包管理器必须从 AccessibilityService 上取
        val launch = svc.packageManager.getLaunchIntentForPackage(TAOBAO_PKG)
        if (launch == null) {
            errors.add("未安装淘宝")
            throw IllegalStateException("未安装淘宝，请先安装")
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        svc.startActivity(launch)

        val ok = Locator.waitForPackage(svc, TAOBAO_PKG, timeoutMs = 30000L)
        if (!ok) {
            errors.add("淘宝启动超时")
            ScriptLog.e("淘宝启动超时，包名=${Locator.currentPackage(svc)}")
            throw IllegalStateException("淘宝启动超时")
        }
        // 等首页骨架加载完，否则后续查找全部落空
        SystemClock.sleep(4000)
        ScriptLog.i("淘宝已就绪")
    }

    private fun step1_clearPopups() {
        ScriptLog.i("步骤1: 清理启动弹窗")
        // 开屏广告/推送弹窗会盖住整个页面
        repeat(3) {
            svc.dismissPopups(2)
            SystemClock.sleep(500)
        }
    }

    private fun step2_enterFarm() {
        ScriptLog.i("步骤2: 进入芭芭农场")

        // ★ 入口先查风控。
        // 实测踩到（18:38 那轮）：淘宝已停在验证码页
        //（com.alibaba.wireless.security…Ext2ContainerActivity）时，
        // 页面既不是农场页也不是首页，代码会一路走到「我的淘宝」兜底路径，
        // 最后抛出「无法进入我的淘宝页」——报了个误导性的原因，
        // 日志里完全看不出真正问题是风控。
        if (checkBlocked("进入农场")) {
            throw IllegalStateException("检测到淘宝风控验证码，请手动完成滑块验证后再试")
        }

        // ① 已经在农场页：先等它渲染完，不要急着换路径。
        //    2026-10-19 实测：点图标后前台已进入 TMSActivity，但「阳光农场/集肥料」
        //    等节点会晚几秒才挂载。旧逻辑只看 isFarmReady()，判定失败后直接走路径B，
        //    结果在农场页上按「我的淘宝」兜底坐标盲点，把流程带进了商品详情页。
        if (svc.isOnFarmPage()) {
            if (waitFarmPage(30000)) {
                ScriptLog.i("已在农场页，跳过进入")
                return
            }
            ScriptLog.w("在农场页但关键按钮未就绪，继续执行（后续步骤会重试）")
            return
        }
        if (isFarmReady()) {
            ScriptLog.i("已在农场页，跳过进入")
            return
        }

        // ② 不在农场页：确认自己在淘宝【主 Tab 页】（首页/我的淘宝等）。
        //    ⚠️ 不能用 HOME_PAGE_MARKS 判断——商品详情页/农场页商品区也会命中
        //    「百亿补贴/今日推荐/闪购」，10-19 实测曾把商品详情页误判成首页。
        if (!isOnTaobaoMainTabs()) {
            ScriptLog.i("当前非农场页也非淘宝主 Tab 页，回到主 Tab 页")
            if (!returnToHome()) {
                ScriptLog.w("现场快照: ${Locator.dumpTexts(svc).take(1200)}")
                throw IllegalStateException("无法回到淘宝首页")
            }
        }

        // ③ 路径A：首页农场图标
        val node = Locator.findFirstByPriority(
            svc, Selectors.FARM_ENTRY_TEXTS, timeoutMs = 2000L,
            blacklist = Selectors.FARM_BLACKLIST_TEXTS
        )
        if (node != null) {
            ScriptLog.i("路径A: 命中首页农场图标")
            Locator.click(svc, node)
            // 冷启动 + 大促浮层时，农场页可能要 30~40 秒才真正可操作。
            // 旧值 25s 实测会「差 2~5 秒」误判失败（10-19 14:05 那轮）。
            if (waitFarmPage(45000)) {
                SystemClock.sleep(3000) // 等农场 H5 首屏
                return
            }
            ScriptLog.w("路径A 点击后未到达农场页，尝试路径B")
        } else {
            ScriptLog.i("路径A: 首页未找到农场图标，走路径B")
        }

        // ④ 路径B：我的淘宝 → 芭芭农场
        //    真机实测：淘宝搜索框不是标准 EditText（无法写中文），搜索路径已否决；
        //    「我的淘宝」页确实存在「芭芭农场」图标入口。
        ScriptLog.i("路径B: 我的淘宝 → 芭芭农场")
        if (!openMyTaobao()) {
            throw IllegalStateException("无法进入「我的淘宝」页")
        }

        val farmEntry = Locator.findFirstByTexts(svc, Selectors.FARM_ENTRY_TEXTS, timeoutMs = 6000L)
            ?: Locator.findById(svc, FARM_ICON_ID, timeoutMs = 2000L)
        if (farmEntry == null) {
            ScriptLog.e("「我的淘宝」页未找到芭芭农场入口")
            ScriptLog.w("现场快照: ${Locator.dumpTexts(svc).take(1200)}")
            throw IllegalStateException("「我的淘宝」页未找到芭芭农场入口")
        }
        ScriptLog.i("路径B: 命中农场入口，点击进入")
        Locator.click(svc, farmEntry)
        if (!waitFarmPage(45000)) {
            errors.add("进入农场页超时")
            ScriptLog.w("现场快照: ${Locator.dumpTexts(svc).take(1200)}")
            throw IllegalStateException("进入芭芭农场页超时")
        }
        SystemClock.sleep(3000)
        ScriptLog.i("已进入农场页")
    }
    /**
     * 回到淘宝主 Tab 页（首页/我的淘宝/购物车/消息）。
     *
     * ⚠️ 2026-10-19 修正：旧实现用 HOME_PAGE_MARKS 判断是否回到首页，
     * 但商品详情页/农场页底部商品推荐区也会出现「百亿补贴/今日推荐/闪购」，
     * 于是「已回到淘宝首页」是误判，后续在错误页面上盲点，直接进了商品详情。
     * 现在改用【底部 Tab 栏】这个结构判据：主 Tab 页一定有
     * 「首页/消息/购物车/我的淘宝」这排 tab，商品页/农场页没有。
     */
    private fun returnToHome(): Boolean {
        repeat(4) {
            if (isOnTaobaoMainTabs()) {
                ScriptLog.i("已回到淘宝主 Tab 页")
                return true
            }
            Locator.pressBack(svc)
            SystemClock.sleep(2000)
        }
        return isOnTaobaoMainTabs()
    }

    /** 当前是否在淘宝主 Tab 页（用底部 tab 栏做结构判据，避免商品页误判）。 */
    private fun isOnTaobaoMainTabs(): Boolean {
        // 「购物车」必须是【全等】匹配：商品详情页只有「加入购物车」，不会误命中。
        return Selectors.TAOBAO_MAIN_TAB_TEXTS.all { tab ->
            Locator.findFirstByTexts(svc, listOf(tab), timeoutMs = 500L) != null
        }
    }

    /**
     * 切到底部「我的淘宝」tab。实测 content-desc="我的淘宝"。
     *
     * ⚠️ 2026-10-19 修正：旧实现在找不到 tab 时直接用比例坐标 (0.9, 0.91) 盲点，
     * 结果在农场页上点进商品详情页（两次）。现在：不在主 Tab 页就先回去；
     * 找不到 tab 就如实返回 false，绝不再盲点坐标。
     */
    private fun openMyTaobao(): Boolean {
        if (!isOnTaobaoMainTabs()) {
            ScriptLog.i("不在淘宝主 Tab 页，先回主 Tab 页")
            if (!returnToHome()) return false
        }
        val tab = Locator.findFirstByTexts(svc, listOf(Selectors.MY_TAOBAO_DESC), timeoutMs = 3000L)
        if (tab == null) {
            ScriptLog.w("未找到「我的淘宝」tab（不再盲点坐标），放弃路径B")
            return false
        }
        Locator.click(svc, tab)
        SystemClock.sleep(4000)
        // 确认已进入「我的淘宝」页：只认个人中心专有文案。
        // ⚠️ 不能用「芭芭农场」——首页图标也叫芭芭农场，会误判成功。
        return Locator.pageContainsAny(svc, *Selectors.MY_TAOBAO_PAGE_MARKS.toTypedArray())
    }

    private fun waitFarmPage(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            clearKnownOverlays()
            if (isFarmReady()) {
                ScriptLog.i("已进入农场页（关键按钮已就绪）")
                return true
            }
            SystemClock.sleep(1000)
        }
        // 兜底：页面标记在但按钮始终没出现时，仍按「已进入」处理，避免直接失败；
        // 但必须留日志说明——这种情况后续步骤大概率会空转。
        if (svc.isOnFarmPage()) {
            ScriptLog.w("已进入农场页，但关键按钮始终未出现（页面未渲染完或有大面积遮挡）")
            return true
        }
        return false
    }

    /**
     * 领取「昨日施肥奖励可领取」浮层（2026-10-19 实测，淘宝 10.66.40 新增）。
     *
     * 该浮层带全屏遮罩，会盖住「施肥」「看小说」等按钮，点击被吃掉：
     *   - 10-19 第 4 轮：可施肥 7→3 后连续两次点击无效，提前收工；
     *   - 10-19 第 4 轮：点「看小说」后面板迟迟不出现，直接跳过。
     * 关闭方式：点浮层主按钮「立即领取」（实测点完即领取并关闭）。
     * 找不到主按钮时退到无文案 ✕ 坐标；绝不按返回（从农场页返回会退出农场）。
     */
    private fun clearFertilizeRewardOverlay(): Boolean {
        if (!Locator.pageContainsAny(svc, *Selectors.FERTILIZE_REWARD_MARKS.toTypedArray())) return true
        repeat(2) { i ->
            ScriptLog.w("  检测到「昨日施肥奖励可领取」浮层（第 ${i + 1} 次尝试领取）")
            val btn = Locator.findClickableByExactText(
                svc, Selectors.FERTILIZE_REWARD_BUTTON_TEXTS, timeoutMs = 1500L
            )
            if (btn != null) {
                ScriptLog.i("  点浮层主按钮「${buttonLabel(btn).take(12)}」领取")
                Locator.click(svc, btn)
            } else {
                ScriptLog.w("  未见「立即领取」，退到 ✕ 坐标兜底")
                Locator.tapRatio(
                    svc, Selectors.FERTILIZE_REWARD_CLOSE_RATIO_X,
                    Selectors.FERTILIZE_REWARD_CLOSE_RATIO_Y
                )
            }
            SystemClock.sleep(1800)
            if (!Locator.pageContainsAny(svc, *Selectors.FERTILIZE_REWARD_MARKS.toTypedArray())) {
                ScriptLog.i("  浮层已关闭（已校验）")
                return true
            }
        }
        ScriptLog.w("  「昨日施肥奖励」浮层关不掉，放弃本次点击")
        return false
    }

    /**
     * 清理所有已知挡路浮层：先领「昨日施肥奖励」，再关「明日7点后记得来领」。
     * 返回 false 表示仍有浮层清不掉，调用方必须放弃后续点击。
     */
    private fun clearKnownOverlays(): Boolean {
        if (!clearFertilizeRewardOverlay()) return false
        return clearNotReadyOverlay()
    }

    /**
     * 农场页是否已经【渲染到可操作】。
     *
     * ⚠️ 不能只看 isOnFarmPage()：实测「阳光农场」这个标记比
     * 施肥/集肥料/挖肥料 这些按钮【先挂载】。只判标记的话，脚本会在按钮
     * 挂载前就往下走，步骤3~6 全部空转（耗时 37 秒、四项全 0）。
     */
    private fun isFarmReady(): Boolean {
        if (!svc.isOnFarmPage()) return false
        return Locator.findClickableByTextsContaining(
            svc, Selectors.FARM_READY_MARKS, timeoutMs = 400L
        ) != null
    }

    /**
     * 摇一摇 -> 获得肥料。
     * 有明确的「每日次数上限」，到顶后按钮文案会变（如「明日再来」）。
     * 因此以「按钮消失或文案变化」作为终止条件，而不是死循环次数。
     */
    private fun step3_shake(): Int {
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
    }

    /**
     * 领取每日肥料（用户指定流程的第 ① 步）。
     *
     * 对应农场页右侧那个按钮，实测文案「3900，肥料，明日7点可领」，
     * bounds [1158,1729][1414,2012]，class=android.widget.Button。
     *
     * 可领判断：文案里【不含「明日」】即已到可领状态。
     * 含「明日」时点击只会弹「明日7点后记得来领」提示，所以直接跳过，不做无效点击。
     *
     * ⚠️ 2026-10-19：14:20 实测该按钮文案是「1500，肥料，点击领取」（不含「明日」），
     * 说明当天奖励未领时下午仍可领；因此不再由 07:30 时间闸门一刀切，
     * 是否点击完全交给这里的文案判断。
     */
    private fun step2b_claimDailyFertilizer() {
        ScriptLog.i("步骤3: 领取每日肥料")
        // 与其它步骤保持一致：每步开头都自检一次风控，命中即整轮短路
        if (checkBlocked("步骤3")) return
        clearKnownOverlays()
        val screenW = svc.resources.displayMetrics.widthPixels
        // 定位条件三要素（缺一不可）：
        //  1) 文案含「肥料」
        //  2) 文案含「可领」或「领取」—— 覆盖「明日7点可领」与到达可领后的「领取」两种形态
        //  3) 位于屏幕【右侧】（实测该按钮 center x≈1286 / 屏宽 1440），
        //     否则会误命中等级条上方那个「icon 100 肥料 领取」（center x≈721）
        val node = Locator.findClickableInTree(svc, { n ->
            if (!Locator.isNodeOnScreen(svc, n)) return@findClickableInTree false
            val l = n.text?.toString().orEmpty() + n.contentDescription?.toString().orEmpty()
            if (!l.contains("肥料") || !(l.contains("可领") || l.contains("领取"))) {
                return@findClickableInTree false
            }
            val r = Rect()
            n.getBoundsInScreen(r)
            r.centerX() * 10 > screenW * 7   // center x > 屏宽 70%
        }, timeoutMs = 2500L)

        if (node == null) {
            ScriptLog.w("  未找到「N，肥料，可领」按钮，跳过")
            return
        }
        val label = buttonLabel(node)
        if (label.contains(Selectors.CLAIM_DAILY_NOT_READY)) {
            ScriptLog.i("  暂不可领（$label），跳过")
            return
        }
        ScriptLog.i("  可领取：$label，点击领取")
        Locator.click(svc, node)
        SystemClock.sleep(3000)
        // 领取后可能会弹「明日7点后记得来领」/「昨日施肥奖励」浮层，顺手清掉
        clearKnownOverlays()
        svc.dismissPopups(1)
    }

    /**
     * 施肥按钮上可读到的两个数。
     *
     * 实测（17:10，真实触摸点击施肥 3 次）：
     *   `施肥，肥料18116，可施肥30次` —— 点击后
     *   可施肥次数 30→29→28（每次 -1）、等级进度推进、阶段升级，
     *   但【肥料18116 全程不变】。
     * 所以判断施肥是否生效、是否该停，必须以【可施肥次数】为主，
     * 肥料数量只作为「是否变成 0」的参考。
     */
    private data class FertilizeState(val fertilizer: Int, val times: Int)

    private fun fertilizeState(): FertilizeState? {
        val btn = Locator.findClickableByTextsContaining(
            svc, Selectors.FERTILIZE_BUTTON_MARKS, timeoutMs = 1200L
        ) ?: Locator.findFirstByPriority(svc, Selectors.FERTILIZE_TEXTS, timeoutMs = 1000L)
        val label = buttonLabel(btn)
        if (label.isBlank()) return null
        val f = Selectors.FERTILIZER_COUNT_PATTERN.find(label)?.groupValues?.get(1)?.toIntOrNull()
        val t = Selectors.FERTILIZE_TIMES_PATTERN.find(label)?.groupValues?.get(1)?.toIntOrNull()
        if (f == null && t == null) return null
        return FertilizeState(f ?: -1, t ?: -1)
    }

    private fun fertilizeUntilEmpty(): Int {
        var count = 0
        var stall = 0
        repeat(MAX_FERTILIZE_ROUNDS) {
            if (blocked) return count
            if (SystemClock.uptimeMillis() > taskDeadlineMs ||
                SystemClock.uptimeMillis() > overallDeadlineMs
            ) return count

            // ★ 2026-10-19 实测：农场页会弹「昨日施肥奖励可领取」浮层，
            //   它带全屏遮罩，遮住施肥按钮后点击不生效（可施肥 7→3 卡住）。
            //   每次施肥前先把已知浮层清干净，清不掉就不点。
            if (!clearKnownOverlays()) {
                ScriptLog.w("  浮层未清掉，本次施肥放弃")
                return count
            }

            val before = fertilizeState()
            if (before != null && (before.fertilizer == 0 || before.times == 0)) {
                ScriptLog.i("  肥料已用完（肥料=${before.fertilizer} 可施肥=${before.times} 次），停止施肥")
                return count
            }
            if (Locator.pageContainsAny(svc, *Selectors.FERTILIZE_EMPTY_TEXTS.toTypedArray())) {
                ScriptLog.i("  页面提示无肥料，停止施肥")
                return count
            }

            val btn = Locator.findFirstByPriority(
                svc, Selectors.FERTILIZE_TEXTS, timeoutMs = 1500L,
                blacklist = Selectors.FARM_BLACKLIST_TEXTS
            )
            if (btn == null) {
                ScriptLog.w("  未找到施肥按钮，停止施肥")
                return count
            }
            // ⚠️ 点击前的最后一道防线（用户报的「明日7点后记得来领」浮窗根因）。
            //   农场页右侧那个每日领取按钮文案是「N，肥料，明日7点可领」，
            //   只要定位链降级，它就会被当成施肥按钮点中 → 弹浮窗。
            //   带「明日」或「可领」的一律不是施肥按钮，宁可停下也不误点。
            val btnLabel = buttonLabel(btn)
            if (btnLabel.contains(Selectors.CLAIM_DAILY_NOT_READY) || btnLabel.contains("可领")) {
                ScriptLog.w("  定位到的是每日领取按钮（$btnLabel），不是施肥按钮，停止施肥")
                return count
            }
            ScriptLog.i("  施肥第 ${count + 1} 次（肥料=${before?.fertilizer} 可施肥=${before?.times} 次）")
            if (!Locator.tapNode(svc, btn)) {
                ScriptLog.w("  施肥点击失败，停止")
                return count
            }
            count++
            SystemClock.sleep(3500)
            handleFertilizeConfirm()
            svc.dismissPopups(2)

            // ⚠️ 施肥施到果树升级时会弹「恭喜果树升级啦！」浮层，
            // 弹窗期间点击施肥【不生效】——实测可施肥次数会卡住不再减少。
            // 不关掉它，下面的「未生效」判定会在两轮后误判成「施不动了」而提前收工。
            dismissLevelUpPopup()
            // 升级/奖励浮层可能随点击出现；状态判定前先清干净
            clearKnownOverlays()

            val after = fertilizeState()
            if (before != null && after != null &&
                after.fertilizer >= before.fertilizer && after.times >= before.times
            ) {
                stall++
                ScriptLog.w("  施肥未生效（$before → $after），第 $stall 次")
                if (stall >= 2) {
                    ScriptLog.w("  连续未生效，判定施不动了；现场文案=${Locator.dumpTexts(svc).take(160)}")
                    return count
                }
            } else {
                stall = 0
            }
        }
        return count
    }

    /**
     * 关闭「恭喜果树升级啦！」弹窗。
     *
     * 实测弹窗结构（升级到 9 级时抓到的真实节点树）：
     *   y= 791-1263  left-tree / right-tree      升级前后对比图
     *   y=1270-1428  「8级」→「9级」+「箭头」
     *   y=1529-1596  「继续施肥，免费水果送到家」  （不可点击）
     *   y=1659-1820  「继续施肥」                  ★ 主按钮
     *   y=2044-2187  「关闭」                      右下角圆形 × 按钮
     *
     * 优先点「继续施肥」：就在弹窗正中、文案自解释，点完直接继续施肥。
     * 实测验证：点它之后弹窗消失，再点施肥 可施肥次数 4 → 3 恢复正常。
     *
     * ⚠️ 为什么不优先点 ×：× 的文案是「关闭」，而任务面板自己的关闭按钮
     * 也叫「关闭」（在右上角 y≈675 x≈1361），同名会误命中 ——
     * 所以「关闭」被刻意排除在通用清弹窗列表之外，只在兜底时按【屏幕下半部分】
     * 这个位置条件区分。
     *
     * @return 是否处理了弹窗
     */
    private fun dismissLevelUpPopup(): Boolean {
        val goOn = Locator.findClickableByTextsContaining(
            svc, Selectors.LEVEL_UP_CONTINUE_TEXTS, timeoutMs = 1200L
        )
        if (goOn != null) {
            ScriptLog.i("  检测到升级弹窗，点「${buttonLabel(goOn).take(12)}」继续施肥")
            Locator.click(svc, goOn)
            SystemClock.sleep(2500)
            return true
        }
        // 兜底：点弹窗右下角那个圆形的 ×（无障碍文案是「关闭」）。
        // 用「必须位于屏幕下半部分」区分任务面板那个同名按钮。
        val screenH = svc.resources.displayMetrics.heightPixels
        val close = Locator.findClickableInTree(svc, { n ->
            if (!Locator.isNodeOnScreen(svc, n)) return@findClickableInTree false
            val l = (n.text?.toString().orEmpty() + n.contentDescription?.toString().orEmpty()).trim()
            if (l != "关闭") return@findClickableInTree false
            val r = Rect()
            n.getBoundsInScreen(r)
            r.centerY() * 2 > screenH   // 下半屏
        }, timeoutMs = 1200L)
        if (close != null) {
            ScriptLog.i("  检测到升级弹窗，点右下角「关闭」")
            Locator.click(svc, close)
            SystemClock.sleep(2500)
            return true
        }
        return false
    }

    /**
     * 点【等级条上方的「领取」按钮】领肥料（用户指定流程的第 ③ 步后半）。
     * 实测该按钮文案「icon 100 肥料 领取」，bounds [497,1701][945,1813]，
     * 正好在「6级」等级条（y≈1834）正上方。
     *
     * @return 是否真的点到了「领取」（没有可领的返回 false，由调用方结束循环）
     */
    private fun claimLevelFertilizer(): Boolean {
        val node = Locator.findClickableByTextsContaining(
            svc, Selectors.CLAIM_LEVEL_TEXTS, timeoutMs = 1500L
        )
        if (node == null) {
            ScriptLog.i("  等级条上方已无「领取」按钮")
            return false
        }
        ScriptLog.i("  点等级条上方「领取」：${buttonLabel(node).take(20)}")
        if (!Locator.click(svc, node)) return false
        SystemClock.sleep(3000)
        svc.dismissPopups(2)
        return true
    }

    /**
     * 施肥。优先「一键施肥」，失败再逐块点。
     * 若页面提示肥料不足，说明已施满，直接结束（这是幂等的）。
     */
    private fun step4_fertilize(): Int {
        ScriptLog.i("步骤6: 施肥（循环至肥料为 0，再领等级条肥料）")
        if (checkBlocked("步骤6")) return 0
        taskDeadlineMs = SystemClock.uptimeMillis() + FERTILIZE_BUDGET_MS

        var total = 0
        var claimRound = 0
        while (!blocked) {
            if (SystemClock.uptimeMillis() > taskDeadlineMs ||
                SystemClock.uptimeMillis() > overallDeadlineMs
            ) {
                ScriptLog.w("  施肥循环触发时限闸门，停止")
                break
            }
            // A. 一直施肥到肥料为 0
            val used = fertilizeUntilEmpty()
            total += used
            // B. 领等级条上方的肥料；领不到就说明没有新的了，结束
            if (!claimLevelFertilizer()) {
                ScriptLog.i("  施肥循环结束（共施肥 $total 次）")
                break
            }
            claimRound++
            if (claimRound >= MAX_FERTILIZE_CLAIM_ROUNDS) {
                ScriptLog.w("  领取轮次达到上限，停止")
                break
            }
            // C. 领完看肥料是否回来了；没有就结束。
            // 严格按用户要求：「肥料数量超过零则继续施肥」——
            // 因此只有【肥料数 > 0 且 可施肥次数 > 0】才继续，否则收尾。
            val st = fertilizeState()
            if (st == null || st.fertilizer <= 0 || st.times <= 0) {
                ScriptLog.i("  领取后无肥料可继续（肥料=${st?.fertilizer} 可施肥=${st?.times} 次），施肥循环结束")
                break
            }
            ScriptLog.i("  领取后 肥料=${st.fertilizer} 可施肥=${st.times} 次，继续施肥")
        }
        return total
    }

    /**
     * 日常任务。
     * 真实语义是「进任务页 -> 逐个领/做 -> 返回农场」。
     * 任务条目会随版本增删，因此不写死任务列表，而是：
     * 遍历任务页中所有可点击的「去完成/领取」按钮，逐个处理。
     */
    // ---------------- 日常任务模块 ----------------

    /**
     * 任务分类。决定面板里的一行任务怎么处理。
     *
     * 分类依据全部来自真机实测的面板结构。
     */
    private enum class TaskKind(val label: String) {
        BROWSE("浏览类"),
        SEARCH_BROWSE("搜索+浏览类"),
        QUIZ("答题类"),
        CROSS_APP("跨App类"),
        OTHER("其它/未知")
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
        val kind: TaskKind,                     // 归属判别结果（采集时就算好，避免节点失效）
        val skipReason: String,                 // 非空 = 不做，并写明原因（便于审计）
        val yCenter: Int                        // 行纵向位置（任务名会改写，靠它兜底追踪）
    )

    /**
     * 解析【当前屏】的任务行。前提：面板已打开。
     *
     * 归类判断在采集时就算好并存进 TaskRow：
     * handleBrowseTask 会离开面板再返回，届时 AccessibilityNodeInfo 已失效，
     * 若在循环里再取行内副标题会读到无效节点（这个坑已经踩过一次）。
     *
     */
    private fun readTaskRows(): List<TaskRow> {
        val snapshot = Locator.dumpTexts(svc)   // 只含屏幕内可见文案
        val rows = ArrayList<TaskRow>()
        val seen = HashSet<String>()
        val regex = Regex("([^|]{2,60}?)\\((\\d+)/(\\d+)\\)")
        for (m in regex.findAll(snapshot)) {
            val name = m.groupValues[1].trim()
            val cur = m.groupValues[2].toIntOrNull() ?: continue
            val total = m.groupValues[3].toIntOrNull() ?: continue
            if (name.isEmpty() || total <= 0) continue
            // ⚠️ 2026-10-19 实测：面板里可能同时存在【同名但不同目标】的两行
            //（如 `发现精选好物(3/8)` 与 `发现精选好物(0/6)`）。
            // 只按 name 去重会丢掉后一行；按 name#total 去重才能如实登记。
            if (!seen.add("$name#$total")) continue
            val anchor = Locator.findFirstByTextsContaining(svc, listOf(m.value.trim()), timeoutMs = 500L)
                ?: Locator.findFirstByTextsContaining(svc, listOf(name), timeoutMs = 300L)
                ?: continue
            val btn = Locator.findClickableInSameRow(
                svc, anchor, blacklist = Selectors.FARM_BLACKLIST_TEXTS
            ) ?: continue
            val rowText = Locator.collectRowTexts(svc, anchor)
            val verdict = judgeTask(name, buttonLabel(btn), rowText)
            val r = Rect()
            anchor.getBoundsInScreen(r)
            rows.add(TaskRow(name, cur, total, verdict.kind, verdict.reason, r.centerY()))
        }
        return rows
    }

    /** 任务归属判别结果：kind = 归类，reason = 非空表示不做并写明原因。 */
    private class Verdict(val kind: TaskKind, val reason: String)

    /**
     * 判断一行任务做不做 —— 用户要求：**只做淘宝 App 内的任务，跨 App 的不做**。
     *
     * 分两层，顺序不可调换。
     *
     * 【第一层 · 跨 App 硬拦截】命中即跳过。
     * 必须先判，是为了不给白名单留漏网机会 ——
     * 万一某天跨 App 任务的副标题也写成「浏览…得」，硬拦截能兜住。
     *   1) 副标题含「逛逛」或「还可领」
     *      —— 实测这是跳第三方 App 的【统一标记】：
     *         蚂蚁森林 / 美团短视频 / 蚂蚁庄园 三行副标题全是「逛逛得」，
     *         支付宝芭芭农场是「还可领」。
     *   2) 按钮含「去逛逛」
     *   3) 任务名含品牌词（支付宝/蚂蚁/芝麻/美团/快手/微博/点评/闲鱼/极速版…）
     *   4) 行内出现「前往」横幅（面板底部那条「前往支付宝-芭芭农场」）
     *
     * 【第二层 · 白名单放行】只有同时满足两条才做：
     *   - 按钮文案含「去完成」
     *   - 行内副标题含「浏览」
     *     —— 实测淘宝内浏览任务固定是「浏览15秒得 / 浏览10s得 / 浏览5秒得 / 浏览得奖励」
     *   其余一律不做，并写明原因。
     *
     * ⚠️ 明确排除「需下单」类：实测「买限时折扣好物得奖励」副标题是
     *   「下单领大额肥料最高 +130000」，按钮也是「去完成」，容易被当成浏览类，
     *   但做它需要【真实付款】—— 这种绝不能自动点。
     *
     * ⚠️ 每行左侧的品牌图标（折扣/支付宝/蚂蚁森林/美团）是【纯图片】，
     *   节点树里不暴露品牌名（每行最左节点就是任务名 TextView），
     *   所以【不能靠图标判别】，只能靠文案。
     */
    private fun judgeTask(name: String, btn: String, rowText: String): Verdict {
        // ---------- 第零层：白名单（用户规则：名字在表里就执行）----------
        // 不看副标题、不看位置、不做跨 App 检测 —— 命中即执行。
        // 任务名里带「搜一搜」的会自动走「搜索+浏览」流程（见下）。
        Selectors.TASK_NAME_WHITELIST.firstOrNull { name.contains(it) }?.let {
            val search = Selectors.TASK_SEARCH_MARKS.any { k -> name.contains(k) } ||
                    rowText.contains("搜一搜")
            return if (search) Verdict(TaskKind.SEARCH_BROWSE, "")
            else Verdict(TaskKind.BROWSE, "")
        }

        // ---------- 第一层：跨 App 硬拦截 ----------
        Selectors.TASK_CROSS_APP_SUBTITLE_MARKS.firstOrNull { rowText.contains(it) }?.let {
            return Verdict(TaskKind.CROSS_APP, "副标题含「$it」= 跳第三方 App")
        }
        if (btn.contains(Selectors.TASK_CROSS_APP_BUTTON_MARK)) {
            return Verdict(TaskKind.CROSS_APP, "按钮是「${Selectors.TASK_CROSS_APP_BUTTON_MARK}」= 跳第三方 App")
        }
        Selectors.TASK_CROSS_APP_MARKS.firstOrNull { name.contains(it) }?.let {
            return Verdict(TaskKind.CROSS_APP, "任务名含品牌词「$it」")
        }
        if (rowText.contains(Selectors.TASK_CROSS_APP_BANNER_MARK)) {
            return Verdict(TaskKind.CROSS_APP, "行内出现「${Selectors.TASK_CROSS_APP_BANNER_MARK}」横幅 = 跳第三方 App")
        }

        // ---------- 第二层：白名单外，一律不做，写明原因 ----------
        return when {
            rowText.contains("下单") || rowText.contains("立即购买") ->
                Verdict(TaskKind.OTHER, "需要下单付款，不自动做")
            Selectors.TASK_QUIZ_MARKS.any { name.contains(it) } || btn.contains("去答题") ->
                Verdict(TaskKind.QUIZ, "答题类，脚本无法保证答对")
            name.contains("邀请") || btn.contains("去邀请") ->
                Verdict(TaskKind.OTHER, "邀请好友类，会跳分享页")
            else ->
                Verdict(TaskKind.OTHER, "不在白名单")
        }
    }

    private fun step6_dailyTasks(): Int {
        ScriptLog.i("步骤4: 日常任务")
        if (checkBlocked("步骤4")) return 0

        // ★ 2026-10-19：上一轮可能把任务面板留在屏幕上（例如被风控中止），
        //   残留面板里的任务名/进度是旧快照。这里先关闭再重开，确保读到最新数据。
        if (isTaskPanelOpen()) {
            ScriptLog.i("  检测到残留任务面板，先关闭以刷新")
            closeTaskPanelQuiet()
        }

        if (!openTaskPanel()) {
            ScriptLog.w("未能打开任务面板，跳过日常任务")
            errors.add("任务面板未打开")
            return 0
        }

        // 面板可滚动，屏外的任务也要扫进来（用户已确认「滚动扫一遍全做」）
        val rows = readTaskRows()
        ScriptLog.i("  面板共扫到 ${rows.size} 个可定位任务行")
        // ★ 再如实登记【全部】任务行（含 H5 屏外、读得到名字但拿不到坐标的行）。
        // 不登记的话，「搜一搜你喜欢的商品」这类真实存在的淘宝内任务
        // 会完全不出现在日志里，看起来像「面板里根本没有这个任务」。
        logFullTaskRoster()
        if (rows.isEmpty()) {
            errors.add("任务面板解析不到可定位的任务行")
            return 0
        }

        // 先打印完整清单与归属判断 —— 让「只做淘宝内」这条规则可审计
        for (r in rows) {
            val inScope = r.kind == TaskKind.BROWSE || r.kind == TaskKind.SEARCH_BROWSE
            val status = if (inScope) {
                "执行（${r.kind.label}）"
            } else {
                "跳过（${r.kind.label}：${r.skipReason}）"
            }
            ScriptLog.i("    [${if (inScope) "✔" else "✘"}] ${r.name}(${r.current}/${r.total})  $status")
        }

        val todo = rows.filter { it.kind == TaskKind.BROWSE || it.kind == TaskKind.SEARCH_BROWSE }
        if (todo.isEmpty()) {
            ScriptLog.i("  没有符合「淘宝内」范围的任务，结束")
            return 0
        }
        // 注：原先在这里算一次 planned 列表顺序执行，
        // 现改为「每做完一个就重新扫描一遍再筛」（见下方 while 循环）。

        var done = 0
        // 白名单一个都没命中时提醒：任务名每天会改写，白名单可能需要更新。
        // （用户要求用完整名匹配，这是必然会遇到的维护点，所以必须让它可见。）
        if (rows.none { it.kind == TaskKind.BROWSE || it.kind == TaskKind.SEARCH_BROWSE }) {
            ScriptLog.w("  ⚠️ 白名单未命中任何任务 —— 面板任务名可能已改写，需更新 Selectors.TASK_NAME_WHITELIST")
            ScriptLog.w("     当前白名单=${Selectors.TASK_NAME_WHITELIST}")
        }
        // ★ 每做完一个任务就重新扫描一遍全部任务、再筛一次。
        //
        // 为什么必须重扫：任务列表是【动态】的 ——
        //   · 同一行的任务名会随进度推进而改写
        //     （实测 `看严选推荐商品(2/5)` → `搜一搜你心仪的宝贝(3/5)`）；
        //   · 做掉一个任务后面板会刷新，可能出现新的可做任务；
        //   · 进度也在推进，继续用旧快照会导致重复/漏做。
        //
        // 「已处理过」的判定用【行的纵向位置 yCenter】，不用任务名 ——
        //   因为名字会改写，用名字会导致同一个任务被反复执行（死循环）。
        //   实测同一行的 yCenter 是稳定的。
        val handledRows = HashSet<Int>()
        while (true) {
            if (blocked) break
            if (SystemClock.uptimeMillis() > overallDeadlineMs) {
                ScriptLog.w("  已到本轮时限，停止后续任务")
                break
            }
            if (done >= Selectors.MAX_TASKS_PER_RUN) {
                ScriptLog.w("  已完成 ${Selectors.MAX_TASKS_PER_RUN} 个任务，停止本轮")
                break
            }
            // 每轮都确保面板打开 —— handleBrowseTask 会离开面板再回来
            if (!openTaskPanel()) {
                ScriptLog.w("  面板未能打开，停止任务执行")
                break
            }
            val fresh = readTaskRows()
            // 再筛一次：只保留淘宝内、可执行、且本轮还没处理过的
            val pending = fresh.filter {
                (it.kind == TaskKind.BROWSE || it.kind == TaskKind.SEARCH_BROWSE) &&
                        it.yCenter !in handledRows &&
                        it.current < it.total
            }
            if (pending.isEmpty()) {
                ScriptLog.i("  重新扫描后已无待执行任务（可执行 ${fresh.count { r -> r.kind == TaskKind.BROWSE || r.kind == TaskKind.SEARCH_BROWSE }} 个，已处理 ${handledRows.size} 个）")
                break
            }
            val row = pending.first()
            ScriptLog.i("  第 ${handledRows.size + 1} 轮重新扫描 → 选中「${row.name}(${row.current}/${row.total})」（本轮共 ${pending.size} 个待执行）")
            handledRows.add(row.yCenter)
            // 单任务失败隔离：一个任务失败不影响其它任务
            try {
                if (handleBrowseTask(row, search = row.kind == TaskKind.SEARCH_BROWSE)) done++
            } catch (t: Throwable) {
                ScriptLog.e("  任务[${row.name}]异常: ${t.message}", t)
                errors.add("任务${row.name}异常")
                runCatching { returnToFarmHome() }
            }
        }

        ScriptLog.i("日常任务完成 $done 项（经过 ${handledRows.size} 轮重新扫描）")
        return done
    }

    /**
     * 【只登记不执行】把面板里所有任务行列出来，含 H5 屏外、无坐标的行。
     *
     * 为什么需要它：实测淘宝任务面板是 H5，屏外的任务行在无障碍树里
     * bounds 恒为 `[0,0][0,0]`。双向对照实测（上滑 3 次 / 下滑 3 次）
     * 与 ACTION_SCROLL_FORWARD 都确认：**滚动只改视觉、不更新 bounds**，
     * 所以这些行读得到名字、却拿不到坐标、点不了。
     *
     * 但「点不了」不等于「可以不提」——不登记的话，日志只会说
     * 「面板共扫到 6 个可定位任务行」，把「搜一搜你喜欢的商品」这类
     * 真实存在的淘宝内任务整个掩盖掉，看起来像面板里没有这个任务。
     * 登记后每条都会标明「可执行 / ⚠️无坐标」，边界一目了然。
     */
    private fun logFullTaskRoster() {
        val strict = Locator.dumpTexts(svc)      // 屏幕内可见（能定位）
        val loose = Locator.dumpAllTexts(svc)    // 全部（含 bounds=0 的屏外行）
        val roster = LinkedHashMap<String, Boolean>()
        for (m in Regex("([^|]{2,60}?)\\((\\d+)/(\\d+)\\)").findAll(loose)) {
            val full = m.value.trim()
            if (roster.containsKey(full)) continue
            roster[full] = strict.contains(full)
        }
        if (roster.isEmpty()) return
        val blocked = roster.count { !it.value }
        ScriptLog.i("  面板完整清单：共 ${roster.size} 条，其中 $blocked 条无坐标（H5 屏外行）")
        for ((full, locatable) in roster) {
            ScriptLog.i("    ${if (locatable) "可定位" else "⚠️无坐标"}  $full")
        }
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

        // 顺带检测风控：实测验证码页上点「集肥料」是无效的，
        // 不在这里拦一次的话会白等 8 秒才由下一步的 checkBlocked 发现。
        if (checkBlocked("打开任务面板")) return false

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

        // ★ 面板打开后轮询等待就绪（最多 PANEL_SETTLE_WAIT_MS）。
        //   旧实现固定睡 30 秒，但实测面板通常 1~2 秒就出任务行，白等约 28 秒。
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < PANEL_SETTLE_WAIT_MS) {
            if (isTaskPanelOpen()) {
                ScriptLog.i("  面板已就绪（等待 ${(SystemClock.uptimeMillis() - start) / 1000}s）")
                SystemClock.sleep(800)   // 让任务行渲染完整
                return true
            }
            // 若出现「昨日施肥奖励」浮层，先领取掉（它会挡住面板）
            clearFertilizeRewardOverlay()
            SystemClock.sleep(PANEL_READY_POLL_MS)
        }

        // 未打开：先区分「肥料未成熟」这个确定性原因
        if (Locator.pageContainsAny(svc, *Selectors.FERTILIZER_NOT_READY_MARKS.toTypedArray())) {
            ScriptLog.w("  出现「肥料未成熟」提示，本次不展开任务面板")
            closeNotReadyDialog()
            return false
        }
        // 也可能只是渲染慢。但绝不再点第二次（按钮是开关，会互抵）
        ScriptLog.w("  等待 ${PANEL_SETTLE_WAIT_MS / 1000}s 后仍未检测到任务面板（不再重复点击，避免开关互抵）")
        return false
    }

    /**
     * 任务面板是否开着。
     *
     * ⚠️ 必须【或】上进度括号 (x/y)。
     * 面板内容可滚动，扫完一屏后标题「肥料明细 / 做任务赚肥料」会滚出屏幕，
     * 只按固定文案判断会误判成「没开」—— 实测踩到过：该关的时候没关，
     * 面板从步骤4 一路留到收尾，遮挡施肥按钮并让页面判据失效。
     * `(x/y)` 是面板独有特征（农场首页实测不含），不受滚动影响。
     */
    private fun isTaskPanelOpen(): Boolean {
        if (Locator.pageContainsAny(svc, *Selectors.TASK_PANEL_MARKS.toTypedArray())) return true
        return PROGRESS_PATTERN.containsMatchIn(Locator.dumpTexts(svc))
    }

    /**
     * 关掉任务面板（若开着），并留日志。
     *
     * 为什么必须显式关：面板是盖在农场页之上的 bottom sheet，
     * 一直留着会遮住施肥按钮，且面板底部商品区含首页文案，
     * 会让 isOnFarmPage() 误判为「不在农场页」。
     */
    private fun closeTaskPanelQuiet() {
        if (!isTaskPanelOpen()) return
        ScriptLog.i("  关闭任务面板（避免遮挡施肥按钮、干扰页面判据）")
        val ok = svc.closeTaskPanel()
        ScriptLog.i("  任务面板${if (ok) "已关闭" else "关闭失败（未确认）"}")
        if (!ok) {
            // 面板关不掉时把现场留下，便于定位（16:52/17:18 都吃过没日志的亏）
            ScriptLog.w("  面板关闭失败现场：${Locator.dumpTexts(svc).take(200)}")
        }
        SystemClock.sleep(1000)
    }

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

    /**
     * 清掉「明日7点后记得来领」浮窗（若在屏幕上），并**校验它真的没了**。
     *
     * 为什么必须做 —— 真机日志 + 截图为证：
     *  1) 这个浮窗自带**全屏遮罩**，会整块盖住右侧「看小说」按钮 (1338,752)
     *     与任务行的「去完成」按钮；此时点击会被遮罩吃掉，什么都不发生。
     *     步骤4 报的「点击后仍在任务面板（疑似被悬浮广告遮挡）」、
     *     步骤7 报的「点开后未出现『看书剧领肥料』」，根因都是它。
     *  2) 它**不是被哪个按钮点出来的**：16:42 那轮从头到尾没点过
     *     「N，肥料，明日7点可领」（步骤3 被 07:30 闸门跳过、步骤5 兔子不可领、
     *     步骤6 一次未点），浮窗照样出现；且 1.5 分钟后仍挂在屏幕上（16:44 截屏）。
     *     所以这道闸门拦不住它 —— 闸门管的是「我们点不点」，管不了浮窗自己出现。
     *  3) 「点一下 ✕ 就当关掉了」是**无效**的：16:52 那轮按 ✕ 坐标点了两次
     *     （每次间隔 1.8 秒复查），浮窗都还在 → 放弃。
     *     改用浮窗自己的**主按钮「继续施肥」**，它才是能真正关掉浮窗的入口。
     *
     * ★ 关闭方式已实测验证（2026-10-15 17:27~17:28，真机 1440x2960）：
     *   · dump 到的节点：`text="继续施肥"`，bounds `[227,1715][1214,1876]`，
     *     `clickable=true`，中心 **(720,1795)**；
     *   · 对它做一次真实手势点击后 3 秒复查：
     *     `明日7点后记得来领` 与 `继续施肥` 两个字符串**都已从节点树消失**，
     *     截屏确认农场页恢复可用（集肥料 / 施肥 / 右侧按钮列全部露出）。
     *
     * ⚠️ 找不到主按钮时**绝不按返回键** —— 从农场页按返回会直接退到淘宝首页。
     *
     * @return true = 屏幕上已无此浮窗（本来就没有，或已关掉并校验过）；
     *         false = 关不掉，调用方必须放弃后续点击（点下去也会被遮罩吃掉）。
     */
    private fun clearNotReadyOverlay(): Boolean {
        val mark = Selectors.NOT_READY_DIALOG_MARK
        if (!Locator.pageContainsAny(svc, mark)) return true
        repeat(2) { i ->
            ScriptLog.w("  检测到「$mark」浮窗（第 ${i + 1} 次尝试关闭）")
            // ★ 首选：点浮窗自己的主按钮「继续施肥」（用户指定）。
            //   实测（17:27 截屏）它是黄色主按钮，点它即关掉浮窗。
            val btn = Locator.findClickableByTextsContaining(
                svc, Selectors.NOT_READY_CONTINUE_TEXTS, timeoutMs = 1500L
            )
            if (btn != null) {
                ScriptLog.i("  点浮窗主按钮「${buttonLabel(btn).take(12)}」关闭")
                Locator.click(svc, btn)
            } else {
                // 兜底：主按钮文案失效时才退到无文案的 ✕ 坐标。
                // 已知它常常点不动（16:52 实测两次无效），所以只当兜底。
                ScriptLog.w("  未见主按钮，退到 ✕ 坐标兜底")
                Locator.tapRatio(
                    svc, Selectors.NOT_READY_CLOSE_RATIO_X, Selectors.NOT_READY_CLOSE_RATIO_Y
                )
            }
            SystemClock.sleep(1800)
            if (!Locator.pageContainsAny(svc, mark)) {
                ScriptLog.i("  浮窗已关闭（已校验）")
                return true
            }
        }
        ScriptLog.w("  浮窗关不掉，放弃本次点击（点下去会被遮罩吃掉，做了也是白做）")
        return false
    }

    /**
     * 定位某个任务行的操作按钮。
     *
     * 两条路径，缺一不可：
     *  A. 按任务名 —— 名称未变时最准；
     *  B. 按【纵向位置】—— 实测同一行任务的名称会随进度推进而改写
     *     （`看严选推荐商品(2/5)` 推进后变成 `搜一搜你心仪的宝贝(3/5)`），
     *     此时按名字必然失配，只能靠行位置追踪。
     */
    private fun findRowButton(row: TaskRow): AccessibilityNodeInfo? {
        Locator.findFirstByTextsContaining(svc, listOf(row.name), timeoutMs = 1200L)
            ?.takeIf { Locator.isNodeOnScreen(svc, it) }
            ?.let { anchor ->
                Locator.findClickableInSameRow(svc, anchor, blacklist = Selectors.FARM_BLACKLIST_TEXTS)
                    ?.let { return it }
            }
        val anchor = Locator.findTextNodeNearY(svc, row.yCenter, 80, PROGRESS_PATTERN) ?: return null
        return Locator.findClickableInSameRow(svc, anchor, blacklist = Selectors.FARM_BLACKLIST_TEXTS)
    }

    private fun buttonLabel(node: AccessibilityNodeInfo?): String =
        node?.text?.toString().orEmpty() + node?.contentDescription?.toString().orEmpty()

    /**
     * 读取某任务行的当前进度。面板未打开或读不到时返回 -1（未知）。
     *
     * ⚠️ 实测踩坑：
     * 1) 早期用「整页正则」匹配 `名称(x/y)`，而面板里存在同名不同目标的两行
     *    （`搜一搜你喜欢的商品(3/4)` 与离屏的 `(0/1)`），整页正则取到树中第一个匹配，
     *    于是给 0/1 的任务读出 3，日志出现「进度 3/1」这种不可能的读数。
     * 2) 改为按名字定位后又发现：同一行任务的名称会随进度推进而改写，
     *    按名字读必然失配（日志「返回后读不到进度」）。
     * 因此：先按名字读，读不到再按【纵向位置】读。
     */
    private fun readProgress(row: TaskRow): Int {
        if (!isTaskPanelOpen()) return -1
        // ★ 优先按【纵向位置】读取，并要求 total 与任务行一致。
        //   2026-10-19 实测：面板里同时有 `发现精选好物(3/8)` 与 `发现精选好物(0/6)`，
        //   按名字读会读到另一行的 0，日志出现「进度 6 → 0」而中止任务。
        val near = Locator.findTextNodeNearY(svc, row.yCenter, 80, PROGRESS_PATTERN)
        if (near != null) {
            val hit = PROGRESS_PATTERN.find(near.text?.toString().orEmpty())
            if (hit != null && hit.groupValues[2].toIntOrNull() == row.total) {
                return hit.groupValues[1].toIntOrNull() ?: -1
            }
        }
        // 再按名字读，同样要求 total 一致
        Locator.findFirstByTextsContaining(svc, listOf(row.name), timeoutMs = 800L)?.let { anchor ->
            val hit = PROGRESS_PATTERN.find(anchor.text?.toString().orEmpty())
                ?: PROGRESS_PATTERN.find(Locator.collectRowTexts(svc, anchor))
            if (hit != null && hit.groupValues[2].toIntOrNull() == row.total) {
                return hit.groupValues[1].toIntOrNull() ?: -1
            }
        }
        return -1
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
    private fun handleBrowseTask(row: TaskRow, search: Boolean): Boolean {
        // search 参数只用于日志标注；是否真的需要搜索由 performSearch()
        // 依据实际打开的页面自行判断（任务名会改写，名字分类不可靠）。
        val kindLabel = if (search) "搜索+浏览" else "纯浏览"
        var current = row.current
        val total = row.total
        if (current >= total) {
            ScriptLog.i("  任务[${row.name}] 已完成($current/$total)，跳过")
            return false
        }
        val plan = minOf(total - current, MAX_ROUNDS_PER_TASK)
        // ★ 2026-10-19：时限按 plan 动态计算，并给「看小说」步骤留出时间。
        //   旧值固定 150 秒只够 3 轮（0/8 的任务永远做不满）。
        val now0 = SystemClock.uptimeMillis()
        val latestAllowed = (overallDeadlineMs - NOVEL_RESERVED_MS - 60_000L)
            .coerceAtLeast(now0 + 30_000L)
        val budget = minOf(
            plan * TASK_BUDGET_PER_ROUND_MS + 20_000L,
            TASK_BUDGET_MAX_MS,
            (latestAllowed - now0).coerceAtLeast(30_000L)
        )
        taskDeadlineMs = now0 + budget
        ScriptLog.i("  任务[${row.name}]（$kindLabel）进度 $current/$total" +
                "（还差 ${total - current} 次），本轮计划 $plan 次，时限 ${budget / 1000}s")

        var round = 0
        var anySuccess = false
        // ★ 2026-10-19：浏览任务偶发「本轮不计数」（实测手动同流程能计数，
        //   脚本连续第 2 轮却读到原值）。允许连续 2 轮不推进再放弃，
        //   避免一次服务端延迟/重复商品直接判死整个任务。
        var noAdvance = 0
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
            // ⚠️ 取按钮节点之前先清掉已知浮层（「昨日施肥奖励」/「明日7点后记得来领」）：
            //    它们带全屏遮罩，会盖住任务行的「去完成」，点击被遮罩吃掉。
            //    顺序也重要：先清浮层再取节点，避免浮层消失后 H5 重绘导致 bounds 失效。
            if (!clearKnownOverlays()) {
                ScriptLog.w("  任务[${row.name}] 浮层未清掉，停止该任务")
                break
            }
            val btn = findRowButton(row)
            if (btn == null) {
                ScriptLog.w("  未找到任务行[${row.name}]的按钮，停止该任务")
                break
            }
            if (buttonLabel(btn).contains("已完成")) {
                ScriptLog.i("  任务[${row.name}] 按钮已显示「已完成」")
                break
            }
            // ★ 2026-10-19：任务行的「去完成」按钮改用手势点击（tapNode）。
            //   H5 按钮对 ACTION_CLICK 常常「返回 true 但什么都不做」——
            //   施肥按钮早已因此改成 tapNode；任务按钮同样适用。
            val clicked = Locator.tapNode(svc, btn) || Locator.click(svc, btn)
            if (!clicked) {
                ScriptLog.w("  任务[${row.name}] 按钮点击失败，停止该任务")
                break
            }

            // ★ 点击后轮询等页面跳转（旧实现固定睡 7 秒；实测通常 2~4 秒就跳走）
            var leftPanel = waitLeaveTaskPanel(TASK_PAGE_CHANGE_WAIT_MS)
            if (!leftPanel) {
                // 仍停在任务面板：先清浮层，再重试一次点击。
                // 2026-10-19 实测：点击偶发 no-op / 被浮层遮挡，会表现为
                // 「点击后仍在任务面板」，旧逻辑直接跳过，白丢一个任务。
                val snap = Locator.dumpTexts(svc).take(160)
                ScriptLog.w("  任务[${row.name}] 点击后仍在任务面板，现场=${snap}；清浮层后重试一次")
                clearKnownOverlays()
                val retryBtn = findRowButton(row)
                if (retryBtn != null && !buttonLabel(retryBtn).contains("已完成")) {
                    Locator.tapNode(svc, retryBtn)
                    leftPanel = waitLeaveTaskPanel(TASK_PAGE_CHANGE_WAIT_MS)
                }
                if (!leftPanel && isTaskPanelOpen() && svc.isOnFarmPage()) {
                    ScriptLog.w("  任务[${row.name}] 重试后仍在任务面板，跳过")
                    break
                }
            }

            if (checkBlocked("任务浏览页")) break

            // 是否要先搜索，按【实际打开的页面】判断，而不是只看任务名。
            // 实测：同一行任务的名称会改写（有时叫「看严选推荐商品」，
            // 有时叫「搜一搜你心仪的宝贝」），单靠名字分类必然漏判。
            // performSearch() 自己会识别页面：不是搜索页就直接返回 true（按纯浏览走）。
            if (!performSearch()) {
                ScriptLog.w("  任务[${row.name}] 搜索步骤未生效，停止该任务")
                break
            }

            // ⚠️ 浏览时长：实测真机对比 —— 滑 20 秒（原 4 次）返回后进度不变，
            // 滑 30 秒（6 次）返回后进度才推进（现场实测 2/5 → 3/5）。
            browseInPage(times = if (search) Selectors.BROWSE_SWIPES_SEARCH else Selectors.BROWSE_SWIPES_NORMAL)
            claimRewardIfAny()
            returnToFarmHome()
            SystemClock.sleep(2000)

            // ★ 2026-10-19：返回后残留的面板可能仍是旧快照，先关再开强制刷新，
            //   否则 readProgress 会读到旧进度（表现为「进度未推进」/「读不到进度」）。
            closeTaskPanelQuiet()
            // 返回后面板通常已关闭，必须重新打开才能读进度
            if (!openTaskPanel()) {
                ScriptLog.w("  返回后无法重新打开任务面板，停止该任务")
                break
            }
            var now = readProgress(row)
            if (now < 0) {
                // ★ 2026-10-19：读不到进度时不再直接放弃，先重扫面板判断这一行去哪了。
                //   实测「看严选推荐商品(7/8)」做完一次后，原行被新任务
                //   「发现精选好物(0/6)」替换，按名字/总数都读不到 → 旧逻辑误判失败，
                //   实际该任务已经做满。
                val fresh = readTaskRows()
                val samePos = fresh.minByOrNull { Math.abs(it.yCenter - row.yCenter) }
                    ?.takeIf { Math.abs(it.yCenter - row.yCenter) <= 60 }
                when {
                    samePos == null -> {
                        ScriptLog.i("  任务[${row.name}] 行已消失（隐藏/完成），视为已完成")
                        anySuccess = true
                    }
                    samePos.name == row.name && samePos.total == row.total -> {
                        ScriptLog.w("  任务[${row.name}] 行仍在但读不到进度，停止该任务")
                    }
                    else -> {
                        ScriptLog.i("  任务[${row.name}] 行已变为「${samePos.name}(${samePos.current}/${samePos.total})」，" +
                                "旧任务视为已完成")
                        anySuccess = true
                    }
                }
                break
            }
            // ⚠️ 进度刷新有延迟。实测：浏览完立刻读到的仍是旧值，
            // 稍等一会儿才更新。只读一次会把「其实已完成」误判成「未推进」而放弃任务。
            // 因此读不到推进时，最多再等 2 轮（每轮 4 秒）后重读。
            var retry = 0
            while (retry < 3 && now <= current && !blocked) {
                retry++
                SystemClock.sleep(4000)
                val again = readProgress(row)
                if (again < 0) break
                now = again
            }
            if (now < current) {
                // ★ 2026-10-19 实测出现「6 → 0」：同名行串读或面板刷新重置。
                //   这里明确打日志，避免看起来像普通的「未推进」。
                ScriptLog.w("  任务[${row.name}] 进度回退（$current → $now，total=$total），" +
                        "疑似读到同名任务行或面板已刷新，停止该任务")
                break
            }
            ScriptLog.i("  任务[${row.name}] 第 $round 次结束，进度 $now/$total" +
                    if (retry > 0) "（含 $retry 次延迟重读）" else "")
            if (now >= total) {
                ScriptLog.i("  任务[${row.name}] 已做满 $total/$total")
                anySuccess = true
                break
            }
            if (now <= current) {
                noAdvance++
                if (noAdvance >= 2) {
                    ScriptLog.w("  任务[${row.name}] 连续 $noAdvance 轮进度未推进（$current），停止该任务")
                    break
                }
                ScriptLog.w("  任务[${row.name}] 本轮进度未推进（$current → $now），继续下一轮再试")
            } else {
                noAdvance = 0
                current = now
                anySuccess = true
            }
        }
        return anySuccess
    }

    /**
     * 点击任务按钮后，等待离开任务面板（轮询）。
     *
     * 返回 true = 已离开面板（跳到了详情页/搜索页）；
     * 返回 false = 超时仍停在面板（多半是被浮层遮挡或点击没生效）。
     */
    private fun waitLeaveTaskPanel(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isTaskPanelOpen()) return true
            SystemClock.sleep(500)
        }
        return false
    }

    /**
     * 在搜索页执行一次「输入关键词 → 提交搜索」。
     *
     * 实测依据（点「搜一搜你喜欢的商品」的「去完成」后抓到的真实节点树）：
     *   打开的正是淘宝搜索页，头部文案「搜索有福利 / 搜索后浏览立得奖励」，
     *   存在【真正的 EditText】搜索框（center ≈ 722,379），以及「搜索」按钮（1193,325）。
     *
     * ⚠️ 与淘宝【首页】搜索框的关键区别：首页那个不是 EditText（写不进去，
     *    已在早期实测中被否决，并因此放弃了「首页搜索框进农场」这条路）；
     *    但搜索页这个是真 EditText，ACTION_SET_TEXT 可以正常写入。
     *
     * 完成条件（用户确认）：搜索出结果后要【上下滑动浏览】，因此调用方在
     * 本方法之后还会执行 browseInPage()。
     */
    private fun performSearch(): Boolean {
        if (!Locator.pageContainsAny(svc, *Selectors.SEARCH_PAGE_MARKS.toTypedArray())) {
            // 有些任务点「去完成」后直接落到商品页，那就退化成纯浏览
            ScriptLog.i("  未出现搜索页特征，按纯浏览处理")
            return true
        }
        // 路径A（首选）：直接点「搜索发现 / 历史搜索」里的词条。
        // 实测这些词条本身 clickable，点一下即执行一次真实搜索，
        // 而且完全绕开「H5 输入框是否接受 ACTION_SET_TEXT」这个不确定点
        // （实测 ACTION_SET_TEXT 返回 true，但页面没反应，怀疑文本没真正写入）。
        val chip = Locator.findClickableByTextsContaining(
            svc, Selectors.SEARCH_SUGGEST_MARKS, timeoutMs = 2000L
        )
        var submitted = false
        if (chip != null) {
            submitted = Locator.click(svc, chip)
            ScriptLog.i("  点击搜索建议词条「${buttonLabel(chip).take(16)}」，结果=$submitted")
        }

        // 路径B（兜底）：写入关键词 + 点「搜索」按钮。
        // ⚠️ 必须用「可点击 + 全等」匹配：
        //  - 实测「搜索」按钮的文案挂在 contentDescription 上（node.text 为空）；
        //  - 且不能用包含匹配：「搜索有福利」「历史搜索」「搜索发现」都含「搜索」。
        if (!submitted) {
            val edit = Locator.findEditableInput(svc, timeoutMs = 2000L)
            if (edit != null) {
                val typed = edit.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            Selectors.SEARCH_KEYWORD
                        )
                    }
                )
                ScriptLog.i("  写入搜索词「${Selectors.SEARCH_KEYWORD}」结果=$typed")
                SystemClock.sleep(1500)
            }
            val btn = Locator.findClickableByExactText(
                svc, Selectors.SEARCH_SUBMIT_TEXTS, timeoutMs = 2000L
            )
            if (btn != null) {
                submitted = Locator.click(svc, btn)
                ScriptLog.i("  点击「搜索」按钮，结果=$submitted")
            }
        }

        SystemClock.sleep(6000)
        // 只提示，不再中止。
        // 实测「搜索有福利 / 搜索后浏览立得奖励」是农场任务横幅，
        // 很可能一路保留到结果页；用它做中止判定会误杀正常流程。
        if (Locator.pageContainsAny(svc, *Selectors.SEARCH_PAGE_MARKS.toTypedArray())) {
            ScriptLog.w("  提示：搜索页横幅仍在（可能已进入结果页，继续浏览）")
        } else {
            ScriptLog.i("  已进入搜索结果页")
        }
        return true
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
    /**
     * 离开当前页、回到农场首页。
     *
     * ⚠️ 实测踩坑（用户反馈：「点了美团任务，返回两次直接到淘宝首页」）。
     * 旧实现是 `repeat(5) { if (isOnFarmPage()) return; pressBack(); sleep(2000) }`，
     * 我用真机复刻了这条路径：
     *   点任务「去完成」→ com.taobao.browser.BrowserActivity（淘宝【内置浏览器】H5）
     *   BACK 后 +2s / +4s / +6s → 前台仍是 com.alibaba.wireless.security 过渡态
     *   BACK 后 +9s             → 才真正回到农场页
     * 也就是返回后要经过 6 秒以上的过渡态。两个缺陷叠加：
     *   1. 固定 sleep(2000) 恰好落在这个过渡态里，判定「不在农场页」→ 又按一次返回
     *      → 直接退到淘宝首页（Welcome → 首页，日志能看到「超级立减/淘宝直播/闪购」）；
     *   2. 到了淘宝首页还继续按返回只会退出淘宝，越退越远。
     *
     * 现在改成两点：按返回后【轮询等待】而不是固定 sleep；
     * 按之前先确认自己没停在淘宝首页（是则绝不再按返回，改用图标重进）。
     * 用户预期「返回一次就够」在实测中是对的 —— 一次返回 + 等够时间即可。
     *
     * ⚠️ 但随后又实测出【第二种】返回行为，两者必须同时兼顾：
     * 从【农场页本身】按返回 = **立即**退出农场到淘宝首页，
     * 且 BACK 后 2s / 5s / 9s 都停在淘宝首页，不会自己回来。
     * 而农场 H5 重绘期间（例如刚施肥完）节点树会【瞬时】不含农场标记，
     * isOnFarmPage() 返回 false —— 若据此就按返回，等于把农场退掉。
     * 收尾阶段那次「凭空按了一次返回」就是这么来的。
     *
     * 因此：入口先做一次「稳定确认」（短等），避开重绘造成的误判。
     */
    private fun returnToFarmHome() {
        // ★ 2026-10-19：返回途中也要先查风控。
        //   实测这轮在商品页返回时页面已经变成淘宝验证码页，
        //   旧逻辑会继续等 12 秒 ×2 再按返回，既浪费时间也可能加重风控。
        if (checkBlocked("返回农场")) return
        if (waitBackToFarm(SETTLE_WAIT_MS)) {
            ScriptLog.i("  已在农场页，无需返回")
            return
        }
        var tries = 0
        while (tries < 2 && !svc.isOnFarmPage()) {
            tries++
            // 已停在淘宝首页：再按返回只会退出淘宝，改为用图标重进。
            if (Locator.pageContainsAny(svc, *Selectors.HOME_PAGE_MARKS.toTypedArray())) {
                ScriptLog.i("  已停在淘宝首页，改用农场图标重新进入")
                break
            }
            Locator.pressBack(svc)
            // ★ 轮询等待，最多 RETURN_TO_FARM_WAIT_MS。
            // 一旦回到农场页立即收手，绝不再因为「等得不够」而多按一次返回。
            if (waitBackToFarm(RETURN_TO_FARM_WAIT_MS)) {
                ScriptLog.i("  已返回农场页（第 $tries 次返回）")
                return
            }
            ScriptLog.w("  第 $tries 次返回后仍未回到农场页，继续判定")
            if (checkBlocked("返回农场")) return
        }
        if (!svc.isOnFarmPage()) {
            // 兜底：改用「首页图标」重新进入，而不是继续盲按返回。
            ScriptLog.w("  返回农场失败，改用首页图标重新进入")
            runCatching { step2_enterFarm() }
        }
    }

    /**
     * 轮询等待回到农场页。
     *
     * 为什么不能固定 sleep：实测从淘宝内置浏览器返回要经过
     * `com.alibaba.wireless.security` 过渡态，第 9 秒才真正回到农场页；
     * 固定等 2 秒必然误判，进而多按一次返回把淘宝也退掉。
     */
    private fun waitBackToFarm(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (svc.isOnFarmPage()) return true
            SystemClock.sleep(500)
        }
        return false
    }

    /**
     * 处理点击施肥后弹出的「次数选择 + 确认」面板。
     *
     * 实测：点「施肥，肥料15700，可施肥28次」后会弹出
     * 「每次施肥5次，未选择」这类次数选择器，需要再点一次确认按钮才真正施肥。
     * 不处理这一步会导致「施肥0次但流程走完」的假成功。
     */
    private fun handleFertilizeConfirm() {
        val panel = Locator.findFirstByTexts(svc, FERTILIZE_PANEL_MARK, timeoutMs = 1500L)
        if (panel == null) {
            ScriptLog.i("无次数选择面板，施肥应已直接执行")
            return
        }
        ScriptLog.i("检测到施肥次数面板，点击确认")
        // 优先点「确认/确定/施肥」，没有则点次数选项本身
        val confirm = Locator.findFirstByPriority(
            svc, FERTILIZE_CONFIRM_TEXTS, timeoutMs = 1500L,
            blacklist = Selectors.FARM_BLACKLIST_TEXTS
        )
        if (confirm != null) {
            Locator.click(svc, confirm)
            SystemClock.sleep(2500)
        } else {
            ScriptLog.w("未找到确认按钮，点击次数选择器")
            Locator.click(svc, panel)
            SystemClock.sleep(2000)
        }
        svc.dismissPopups(1)
    }

    // ==================== 步骤7：看小说领肥料 ====================

    /**
     * 看小说领肥料（实测坐标：1440x2960 / 三星 SM-G9550 / Android 9）。
     *
     * 流程：农场页右侧「看小说」→ 「看书剧领肥料」面板 → 随机点一本 →
     *       阅读页循环（每 5 秒翻页 + 关弹窗）→ 返回面板 → 关面板 → 回农场
     *
     * ⚠️ 关键约束：农场页右侧那一列按钮（兑换 / 更多 / 看小说 / 玩游戏）
     *   无障碍文案全是 `undefined，`，**只能按位置取第 3 个**。
     *   所以点开后【必须】校验「看书剧领肥料」是否出现 —— 不出现就立即回退，
     *   绝不在错误的页面上继续操作。
     */
    private fun step8_readNovel() {
        ScriptLog.i("步骤7: 看小说领肥料")
        if (checkBlocked("步骤7")) return

        // 保险：若「看书剧领肥料」面板本来就开着（步骤4 留下的），
        // 面板内的「关闭」按钮 (1361,654) 会落进右侧按钮的 y 窗口内并排在最前，
        // 导致误选 —— 所以先确保面板是关着的。
        if (isNovelPanelOpen()) {
            ScriptLog.i("  面板仍开着，先关闭")
            closeNovelPanel()
            returnToFarmHome()
        }

        // ★ 必须先清掉所有已知浮层（尤其是 10-19 新增的「昨日施肥奖励可领取」），
        //   它们都会盖住右侧「看小说」按钮，点击会被吃掉。
        if (!clearKnownOverlays()) {
            ScriptLog.w("  浮层未清掉，本步骤无法点「看小说」，跳过")
            return
        }

        val entry = novelEntryButton()
        if (entry == null) {
            ScriptLog.w("  未找到「看小说」按钮，跳过本步骤")
            return
        }
        val er = Rect(); entry.getBoundsInScreen(er)
        ScriptLog.i("  点「看小说」@(${er.centerX()},${er.centerY()})")
        Locator.tapNode(svc, entry)

        // ★ 轮询等待面板出现（旧实现固定 7 秒，实测面板打开需要 4~7.4 秒，
        //   踩线时会误判「面板没打开」；见 10-19 第 4 轮）。
        if (!waitNovelPanel(NOVEL_PANEL_WAIT_MS)) {
            ScriptLog.w("  点开后 ${NOVEL_PANEL_WAIT_MS / 1000}s 内未出现「看书剧领肥料」面板，直接回农场")
            returnToFarmHome()
            return
        }
        ScriptLog.i("  已打开「看书剧领肥料」面板")

        if (!openRandomNovel()) {
            ScriptLog.w("  未找到可点的书籍，关闭面板")
            closeNovelPanel()
            return
        }
        dismissNovelGuide()
        readNovelLoop()
        backToFarmFromNovel()
    }

    /**
     * 取农场页右侧竖排按钮里的「看小说」。
     *
     * 实测（1440x2960）：右侧 `x>1267` 的可点击按钮按 y 排序为
     *   (1349,161) 返回首页 → (1338,337) 兑换 → **(1291,334) 搜索** →
     *   (1338,521) 更多 → **(1338,752) 看小说** → (1338,960) 玩游戏
     * ⚠️ 顶部导航栏的「返回首页」与「搜索」中心 x 也 > 88%屏宽，
     *    所以**必须同时限定 y 窗口**，否则索引会整体偏移。
     *
     * 锁定方式：只取中心 y 落在屏高 20%~33% 区间的按钮 ——
     * 实测该区间内只有「看小说」(752/2960=25.4%) 一个，
     * 「更多」(17.6%) 在区间外、「玩游戏」(32.4%) 虽然在区间内但排在后面。
     * 取第一个即命中。区间内一个都没有时直接放弃（宁可不做，也不误点）。
     */
    private fun novelEntryButton(): AccessibilityNodeInfo? {
        val list = rightSideClickables()
        if (list.isEmpty()) {
            ScriptLog.w("  右侧 y=20%~33% 区间内没有可点击按钮，为避免点错而跳过")
            return null
        }
        return list.first()
    }

    /**
     * 收集「农场页右侧竖列中部」的可点击节点，按 y 从上到下排序。
     *
     * 过滤条件（两个都要）：
     *  · 中心 x > 88% 屏宽 —— 靠右的一列
     *  · 中心 y 在屏高 20%~33% —— 排除顶部导航栏（返回首页/搜索）
     *    与底部商品区（集肥料/超惠买/¥8 横幅），把范围收窄到那一列中部
     */
    private fun rightSideClickables(): List<AccessibilityNodeInfo> {
        val root = svc.rootInActiveWindow ?: return emptyList()
        val m = svc.resources.displayMetrics
        val rightX = m.widthPixels * 0.88f
        val yFrom = (m.heightPixels * 0.24f).toInt()
        val yTo = (m.heightPixels * 0.30f).toInt()
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (n.isClickable && !rect.isEmpty &&
                rect.centerX() > rightX && rect.centerY() in yFrom..yTo
            ) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out.sortedBy { n ->
            val b = Rect()
            n.getBoundsInScreen(b)
            b.centerY()
        }
    }

    /** 「看书剧领肥料」面板是否已打开 */
    private fun isNovelPanelOpen(): Boolean =
        Locator.pageContainsAny(svc, *Selectors.NOVEL_PANEL_MARKS.toTypedArray())

    /**
     * 打开「看书剧领肥料」面板后点一本书进入阅读页。
     *
     * ⚠️ 2026-10-19 实测淘宝 10.66.40 布局已改：
     *  - 榜单 tab（推荐榜/完结榜…）从顶部移到了面板【底部】(y≈2689)；
     *    旧实现把「榜单下边界」当成书单上边界，算出来的候选区间为空 → 永远找不到卡片。
     *  - 书籍卡片不再是无文案空容器，而是可点击【带文案】的卡片
     *    （如「3141人在读 大明王朝1566：全2册」）。
     *  - 面板打开后书单还会先显示「加载中」，不能打开就立刻扫。
     * 因此这里：① 等书单加载；② 用新布局筛卡片；③ 逐个试，进阅读页即停。
     */
    private fun openRandomNovel(): Boolean {
        val cards = waitNovelCards(NOVEL_CARDS_WAIT_MS)
        if (cards.isEmpty()) {
            ScriptLog.w("  书籍列表区等待 ${NOVEL_CARDS_WAIT_MS / 1000}s 仍无可点卡片")
            ScriptLog.w("  现场快照=${Locator.dumpTexts(svc).take(200)}")
            return false
        }
        val shuffled = cards.shuffled()
        val tryCount = minOf(Selectors.NOVEL_BOOK_TRY, shuffled.size)
        for (i in 0 until tryCount) {
            val pick = shuffled[i]
            val r = Rect(); pick.getBoundsInScreen(r)
            Locator.tapNode(svc, pick)
            if (waitNovelReader(NOVEL_READER_WAIT_MS)) {
                ScriptLog.i("  点书 @(${r.centerX()},${r.centerY()}) 进入阅读页" +
                        "（第 ${i + 1} 次尝试，候选 ${cards.size} 个）")
                return true
            }
            ScriptLog.w("  第 ${i + 1} 次点击 @(${r.centerX()},${r.centerY()}) 未进阅读页，换一本")
            logNovelWhereAmI()
            if (!isNovelPanelOpen()) {
                // 已离开面板（可能被点进了别的页），按返回再试下一本
                ScriptLog.w("  已离开面板，按返回后换下一本")
                Locator.pressBack(svc)
                SystemClock.sleep(2500)
                if (!isNovelPanelOpen()) {
                    ScriptLog.w("  返回后面板仍未回来，放弃剩余 ${tryCount - i - 1} 次尝试")
                    return false
                }
            } else {
                // 还在面板上 —— 说明只是没点中书，直接换下一本，【不要按返回】
                ScriptLog.w("  仍在面板上（未跳走），直接换下一本")
            }
        }
        ScriptLog.w("  连试 $tryCount 本都没进阅读页，放弃")
        return false
    }

    /** 等待「看书剧领肥料」面板出现（轮询）。 */
    private fun waitNovelPanel(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isNovelPanelOpen()) return true
            SystemClock.sleep(1000)
        }
        return false
    }

    /** 等待书单加载出可点卡片（轮询；返回空表示超时）。 */
    private fun waitNovelCards(timeoutMs: Long): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val cards = novelCards()
            if (cards.isNotEmpty()) return cards
            SystemClock.sleep(1000)
        }
        return emptyList()
    }

    /** 等待进入阅读页（轮询）。 */
    private fun waitNovelReader(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isInNovelReader()) return true
            SystemClock.sleep(800)
        }
        return false
    }

    /**
     * 收集当前「看书剧领肥料」面板里的书籍卡片。
     *
     * 10.66.40 实测：
     *  - 可点卡片带文案（含「人在读」/「读至第」），尺寸约 294x532；
     *  - 榜单 tab 在面板底部（y≈2689），因此书单范围是「屏高 30% ~ 榜单上边界」；
     *  - 面板盖在农场页之上，农场页底部按钮（一起种/集肥料/施肥…）仍在树里，
     *    必须用文案特征 + 黑名单排除，否则会点到它们。
     */
    private fun novelCards(): List<AccessibilityNodeInfo> {
        val root = svc.rootInActiveWindow ?: return emptyList()
        val m = svc.resources.displayMetrics
        val yFrom = (m.heightPixels * Selectors.NOVEL_LIST_TOP_RATIO).toInt()
        val rankTop = rankTabsTopY()
        val yTo = minOf(
            (m.heightPixels * Selectors.NOVEL_LIST_BOTTOM_RATIO).toInt(),
            (rankTop - 10).coerceAtLeast((m.heightPixels * 0.5f).toInt())
        )
        val xFrom = (m.widthPixels * 0.05f).toInt()
        val xTo = (m.widthPixels * 0.95f).toInt()
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (n.isClickable && !rect.isEmpty &&
                rect.centerY() in yFrom..yTo &&
                rect.centerX() in xFrom..xTo &&
                rect.width() in 150..1000 && rect.height() in 100..900
            ) {
                val label = (n.text?.toString().orEmpty() + " " +
                        n.contentDescription?.toString().orEmpty()).trim()
                val isBook = Selectors.NOVEL_CARD_TEXT_MARKS.any { label.contains(it) }
                val isFarmNoise = Selectors.FARM_BLACKLIST_TEXTS.any { label.contains(it) } ||
                        label.contains("肥料") || label.contains("下单") || label.contains("¥")
                // 书名卡片带「人在读/读至第」；另保留无文案的空容器（部分「继续阅读」卡片）
                if (!isFarmNoise && (isBook || label.isEmpty())) out.add(n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /**
     * 打印「当前到底在哪一页」。
     *
     * 用来把「没进阅读页」这种笼统结论拆开：
     * 到底还在面板 / 进了阅读页但判据没命中 / 跳到了别的页面。
     */
    private fun logNovelWhereAmI() {
        val snapshot = Locator.dumpTexts(svc)
        ScriptLog.w("    现场：在小说面板=${snapshot.contains("看书剧领肥料")}" +
                " 在阅读页=${isInNovelReader()}" +
                " 在看书赚钱浮层=${snapshot.contains("看书赚钱")}" +
                " 农场页=${svc.isOnFarmPage()}")
        ScriptLog.w("    快照=" + snapshot.take(160))
    }

    /**
     * 榜单 Tab 区的上边界。
     *
     * ⚠️ 2026-10-19 实测淘宝 10.66.40：榜单 tab（推荐榜/完结榜…）已经移到
     * 面板【底部】(y≈2689)，书籍列表在其【上方】。旧实现取「榜单下边界」当书单
     * 上界，会得到空区间，导致「书籍列表区没有找到可点的卡片」。
     */
    private fun rankTabsTopY(): Int {
        val m = svc.resources.displayMetrics
        val fallback = (m.heightPixels * 0.88f).toInt()
        val tab = Locator.findFirstByTexts(svc, Selectors.NOVEL_RANK_TABS, timeoutMs = 600L)
            ?: return fallback
        val r = Rect()
        tab.getBoundsInScreen(r)
        return if (r.isEmpty) fallback else r.top
    }

    /** 关掉阅读页的新手引导蒙层（实测文案「点击中间 呼出菜单」，点屏幕中部即可）。 */
    private fun dismissNovelGuide() {
        if (!Locator.pageContainsAny(svc, *Selectors.NOVEL_GUIDE_MARKS.toTypedArray())) return
        ScriptLog.i("  关闭新手引导蒙层")
        Locator.tapRatio(svc, 0.5f, 0.42f)
        SystemClock.sleep(1500)
    }

    /**
     * 关掉「赚钱眼」激励浮层。
     *
     * ⚠️ 白名单策略：只点已知文案 `继续阅读`。
     * 不认识的浮层一律不点 —— 宁可弹窗留着，也不能误触到正文里的其他元素。
     */
    private fun dismissEarnPopup() {
        if (!Locator.pageContainsAny(svc, *Selectors.NOVEL_EARN_POPUP_MARKS.toTypedArray())) return
        val btn = Locator.findClickableByExactText(
            svc, Selectors.NOVEL_EARN_CLOSE_TEXTS, timeoutMs = 800L
        )
        if (btn != null && Locator.tapNode(svc, btn)) {
            ScriptLog.i("  已关闭「赚钱眼」弹窗")
            SystemClock.sleep(800)
        }
    }

    /**
     * 关掉「看书赚钱」全屏浮层（实测 21:00 首次抓到）。
     *
     * ★ 必须放在每轮【翻页之前】，因为这个浮层：
     *   1. 把整屏换成搜索框+商品卡片，但阅读页节点仍在树里
     *      → `isInNovelReader()` 会误判为「还在阅读页」；
     *   2. 右侧翻页落点 (0.83,0.48) 正好压在它的商品卡片上
     *      → 不关掉就会误点商品，可能跳进商品详情甚至下单页。
     *
     * 关闭方式：点右上角 ✕（实测 @(1349,161)，`clickable=true`，
     * 节点树里文案为空、只有一个可见 Image）。
     * 用比例定位：1349/1440 ≈ 0.937，161/2960 ≈ 0.054。
     */
    private fun dismissEarnShop() {
        if (!Locator.pageContainsAny(svc, *Selectors.NOVEL_EARN_SHOP_MARKS.toTypedArray())) return
        // 优先点「领取免广告时长」—— 它就是「跳过广告、回到阅读」的入口（实测 @(722,2240)）。
        val skip = Locator.findClickableByExactText(
            svc, listOf("领取免广告时长"), timeoutMs = 800L
        )
        if (skip != null && Locator.tapNode(svc, skip)) {
            ScriptLog.i("  已点击「领取免广告时长」关闭看书赚钱浮层")
            SystemClock.sleep(1500)
            return
        }
        // 兜底：点右上角 ✕（实测 @(1349,161)）。
        // ⚠️ 这里【必须确认按钮真的存在】再点 —— 兜底坐标 (0.937,0.054) 落在页面顶部，
        //   万一判据误触发就会每 5 秒点一次页眉。所以先找「看书赚钱」标题同排的可点击节点，
        //   找不到就放弃（宁可浮层留着，也不能乱点）。
        val topRight = findTopRightClickableOnShop()
        if (topRight != null && Locator.tapNode(svc, topRight)) {
            ScriptLog.i("  已点右上角 ✕ 关闭看书赚钱浮层")
        } else {
            ScriptLog.w("  检测到看书赚钱浮层，但找不到可靠的关闭按钮，本轮不处理")
            return
        }
        SystemClock.sleep(1500)
    }

    /**
     * 在「看书赚钱」浮层里定位右上角的关闭按钮（✕）。
     *
     * 约束：必须位于【顶部区域】且【在「看书赚钱」标题右侧】——
     * 不能只靠固定坐标，否则判据一旦误触发就会点到页面其它元素。
     */
    private fun findTopRightClickableOnShop(): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        val m = svc.resources.displayMetrics
        val topLimit = (m.heightPixels * 0.10f).toInt()
        var best: AccessibilityNodeInfo? = null
        var bestX = Int.MIN_VALUE
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20) return
            val r = Rect()
            n.getBoundsInScreen(r)
            if (n.isClickable && !r.isEmpty &&
                r.centerY() in 0..topLimit &&
                r.centerX() > m.widthPixels * 0.75f &&
                r.width() > 60 && r.width() < 400 &&
                r.height() > 20 && r.height() < 300
            ) {
                if (r.centerX() > bestX) { bestX = r.centerX(); best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return best
    }

    /** 是否还在小说阅读页（实测四轮，稳定命中的是底部工具栏提示） */
    private fun isInNovelReader(): Boolean {
        // ⚠️ 判据词必须「专有」—— 同一个词若在多个页面都出现，就不能用来判页面。
        //   本项目在这上面连踩两次（「热卖榜」「看书赚钱」），详见 Selectors 注释。
        val snapshot = Locator.dumpTexts(svc)
        // 实测（1440x2960）：
        //   `点击中部展示工具栏` 在阅读页稳定可见 → 最可靠的判据
        //   `目录` / `夜间` 只在点开底部工具栏后出现，单用其一比同时要求更稳
        if (snapshot.contains("点击中部展示工具栏")) return true
        if (snapshot.contains("目录") || snapshot.contains("夜间")) return true
        return Regex("\\d+:\\d+后\\+\\d+肥料").containsMatchIn(snapshot)
    }

    /**
     * 阅读循环：每 NOVEL_PAGE_INTERVAL_MS 翻一页。
     *
     * ★ 两条规则，全部来自「连续翻完 4 章」的实测（22:00-22:07），不是推断：
     *
     *   规则1  平时：点右侧 `(0.83, 0.48)` → 本章内翻页；到 `N/N` 时自动进入下一章。
     *          实测四章都是如此（21/21→第2章、20/20→第3章、26/26→第4章），
     *          **不需要任何「章节末尾」特殊处理**。
     *
     *   规则2  「看书赚钱」浮层出现时（`领取免广告时长`）：点「下一页」按钮。
     *          实测浮层与「下一页」【总是同时出现】，
     *          且点「下一页」一次同时完成两件事：翻一页 + 关掉浮层
     *          （实测 12/20→13/20 且 浮层 True→False）。
     *          浮层会挡住正文区，此时点右侧无效 —— 所以规则2 是必需的。
     *
     * ⚠️ 曾在这里加过「到章节末尾就必须点下一页、否则不点右侧」的逻辑，
     *   那是基于一次偶发观察（第2章 12/20 时出现过）得出的错误 generalize，
     *   正常情况下永远不执行，属于多余代码，已删除。
     *
     * 退出条件（任一命中即停）：到时长 / 被风控拦截 / 连续多轮不在阅读页。
     */
    private fun readNovelLoop() {
        val deadline = SystemClock.uptimeMillis() + NOVEL_READ_MS
        var round = 0
        var miss = 0
        ScriptLog.i("  开始阅读 ${NOVEL_READ_MS / 60000} 分钟，每 ${NOVEL_PAGE_INTERVAL_MS / 1000} 秒翻一页")
        while (SystemClock.uptimeMillis() < deadline) {
            round++
            if (checkBlocked("阅读中")) {
                ScriptLog.w("  阅读过程中被风控拦截，提前结束")
                return
            }
            if (!isInNovelReader()) {
                // ★ 不立刻结束：浮层出现时判据可能短暂不成立。
                //   旧实现直接 return → 6 分钟只跑 65 秒（实测）。
                //   现在连续 3 轮都不在才算结束，且中间什么都不点。
                miss++
                ScriptLog.w("  第 $round 轮不在阅读页（连续第 $miss 次）")
                if (miss >= 3) {
                    ScriptLog.w("  连续 3 轮不在阅读页，结束阅读")
                    return
                }
                SystemClock.sleep(NOVEL_PAGE_INTERVAL_MS)
                continue
            }
            miss = 0
            dismissEarnPopup()

            // 规则2：有浮层 → 点「下一页」（同时翻页 + 关浮层）
            val hasShop = Locator.pageContainsAny(
                svc, *Selectors.NOVEL_EARN_SHOP_MARKS.toTypedArray()
            )
            if (hasShop) {
                val next = Locator.findFirstByTextsContaining(
                    svc, Selectors.NOVEL_NEXT_PAGE_TEXTS, timeoutMs = 800L
                )
                if (next != null && Locator.tapNode(svc, next)) {
                    ScriptLog.i("  检测到看书赚钱浮层，点「下一页」（翻页+关浮层）")
                } else {
                    // 点不到「下一页」才退回关浮层（实测 21:45 走的就是这条）
                    dismissEarnShop()
                    ScriptLog.w("  有浮层但点不到「下一页」，改为仅关闭浮层")
                }
            } else {
                // 规则1：平时点右侧翻页
                Locator.tapRatio(svc, 0.83f, 0.48f)
            }

            if (round % 10 == 0) {
                val left = (deadline - SystemClock.uptimeMillis()) / 1000
                ScriptLog.i("  已翻 $round 页，剩余 ${left}s，" + novelProgress())
            }
            SystemClock.sleep(NOVEL_PAGE_INTERVAL_MS)
        }
        ScriptLog.i("  阅读完成，共翻 $round 页，" + novelProgress())
    }

    /** 读取当前小说进度（页码/章节），仅用于日志。没有就返回空串。 */
    private fun novelProgress(): String {
        val xml = Locator.dumpTexts(svc)
        val pg = Regex("\\b(\\d+)/(\\d+)\\b").find(xml)
        val ch = Regex("第\\d+章[^|<]{0,12}").find(xml)
        val p = pg?.let { "第 ${it.groupValues[1]}/${it.groupValues[2]} 页" } ?: "页码未知"
        val c = ch?.value?.trim() ?: "章节未知"
        return "进度 $p · $c"
    }

    /** 从阅读页返回农场：阅读页 → 点返回 → 面板 → 关面板 → 校验农场页 */
    private fun backToFarmFromNovel() {
        val back = Locator.findClickableByExactText(svc, listOf("返回"), timeoutMs = 1200L)
        if (back != null && Locator.tapNode(svc, back)) {
            ScriptLog.i("  已从阅读页返回")
        } else {
            Locator.pressBack(svc)
        }
        SystemClock.sleep(4000)

        if (isNovelPanelOpen()) closeNovelPanel()

        if (svc.isOnFarmPage()) {
            ScriptLog.i("  已回到农场页")
        } else {
            ScriptLog.w("  返回后不在农场页，尝试重入")
            returnToFarmHome()
        }
    }

    /** 关闭「看书剧领肥料」面板（实测：右上角，文案「关闭」） */
    private fun closeNovelPanel() {
        val btn = Locator.findClickableByExactText(
            svc, Selectors.NOVEL_PANEL_CLOSE_TEXTS, timeoutMs = 1200L
        )
        if (btn != null && Locator.tapNode(svc, btn)) {
            ScriptLog.i("  已关闭「看书剧领肥料」面板")
            SystemClock.sleep(3000)
            return
        }
        // ⚠️ 必须有兜底。实测 21:06 那轮：找不到「关闭」按钮就直接放弃，
        // 面板留在屏幕上 → 收尾 isOnFarmPage() 判 false → 白等 12 秒才靠返回键回农场。
        ScriptLog.w("  未找到面板的「关闭」按钮，改按返回键")
        Locator.pressBack(svc)
        SystemClock.sleep(2500)
        if (Locator.pageContainsAny(svc, *Selectors.NOVEL_PANEL_MARKS.toTypedArray())) {
            ScriptLog.w("  返回后面板仍在，放弃关闭")
        }
    }

    private fun step7_finish() {
        ScriptLog.i("步骤8: 收尾（回到农场首页）")
        // ⚠️ 这里【不能】用 isOnFarmPage() 判断。
        // 实测：任务面板是盖在农场页之上的 bottom sheet，面板打开时农场页节点
        // （阳光农场 / 集肥料 / 可施肥）依然完整存在且 clickable，
        // 于是 isOnFarmPage() 返回 true，旧实现直接打印「已在农场页，收尾完成」就收工，
        // 实际屏幕上留着任务面板 —— 这就是「任务做完没回到农场首页」的根因。
        // 因此改用 isOnFarmHomePage()：要求是农场页【且】无任何浮层特征。
        // 第一步：先关掉浮层（任务面板）。
        if (isTaskPanelOpen()) {
            ScriptLog.i("关闭任务面板")
            svc.closeTaskPanel()
            SystemClock.sleep(1500)
        }
        // 第二步：先【轮询等待页面稳定】，再决定要不要返回。
        // ⚠️ 实测踩坑（日志证据）：
        //   18:08:19.997  施肥循环结束
        //   18:08:19.998 步骤7: 收尾
        //   18:08:21.246   已返回农场页（第 1 次返回）  ← 收尾里凭空按了一次返回
        // 原因：施肥刚结束时农场 H5 正在重绘，节点树会【短暂】不含农场标记
        // （阳光农场/集肥料），此时 isOnFarmPage() 返回 false，
        // 旧写法 `if (!isOnFarmPage()) returnToFarmHome()` 就直接按了返回。
        // 这一下 + 服务结束时那次无条件返回，一共多退两层，最终落到淘宝首页。
        // 现在改为：先等待（已在农场页时首次检查即通过，不浪费时间），
        // 只有等完确实还不在农场页，才走返回逻辑。
        if (waitBackToFarm(RETURN_TO_FARM_WAIT_MS)) {
            // 已稳定在农场页 —— 无需任何返回动作
        } else {
            ScriptLog.w("  收尾：等待 ${RETURN_TO_FARM_WAIT_MS / 1000} 秒后仍不在农场页，尝试返回")
            returnToFarmHome()
        }
        if (svc.isOnFarmHomePage()) {
            ScriptLog.i("已回到农场首页，收尾完成")
        } else {
            // 打「命中了哪个判据」的明细，而不是截断快照 ——
            // 只有说清是浮层文案 / 进度括号 / 首页标记中的哪一项触发，
            // 才可能定位这种误报，否则只能看到一句「未确认」。
            val snap = Locator.dumpTexts(svc)
            val ov = Selectors.FARM_OVERLAY_MARKS.filter { snap.contains(it) }
            val xy = Regex("\\(\\d+/\\d+\\)").findAll(snap).map { it.value }.toList()
            val hm = Selectors.HOME_PAGE_MARKS.filter { snap.contains(it) }
            ScriptLog.w("收尾：未确认回到农场首页（在农场页=${svc.isOnFarmPage()}）" +
                    " 命中浮层文案=$ov 进度括号=$xy 首页标记=$hm")
        }
    }
}
