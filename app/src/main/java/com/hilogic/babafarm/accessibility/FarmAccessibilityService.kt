package com.hilogic.babafarm.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import com.hilogic.babafarm.core.Locator
import com.hilogic.babafarm.core.ScriptLog
import com.hilogic.babafarm.core.Selectors
import com.hilogic.babafarm.service.FarmForegroundService

/**
 * 无障碍服务宿主。
 *
 * 职责边界：
 * - 只负责「被调度 + 执行点击」，不持有业务状态机；
 * - 业务状态机放在 FarmWorkflow，便于单测与日志分段。
 *
 * 关键稳定性设计：
 * 1. onServiceConnected 后才允许提交任务，避免 NPE；
 * 2. 执行期不允许并发（running 标志），防止闹钟与手动触发打架；
 * 3. 每个步骤都有超时上限，整轮有总超时，任何一层卡死都不会挂死系统。
 */
class FarmAccessibilityService : AccessibilityService() {

    /**
     * submit 的结果：区分「未连接」与「上一轮未结束」。
     *
     * 前台服务必须区分这两种失败：
     *  - NOT_CONNECTED：本次请求根本没执行，可以尽快停掉刚启动的服务；
     *  - BUSY：上一轮仍在跑，【不能】停服务（会撤掉 WakeLock 与前台通知）。
     */
    enum class SubmitResult { ACCEPTED, NOT_CONNECTED, BUSY }

    companion object {
        private const val TAG = "FarmA11y"
        @Volatile
        var instance: FarmAccessibilityService? = null
            private set

        @Volatile
        var isConnected: Boolean = false
            private set

        // 单轮总上限 18 分钟（实测需求：新增「看小说领肥料」步骤需驻留 6 分钟，
        // 叠加前置的启动/进农场/任务/施肥约 2~5 分钟与收尾，10 分钟不够。
        // ⚠️ 这个值同时是前台服务的看门狗 —— 到点会【强杀】服务，
        // 所以内部 OVERALL_BUDGET_MS 只设 15 分钟，给自己留 3 分钟收尾余量。
        const val TOTAL_TIMEOUT_MS = 18 * 60 * 1000L

        /** 由 AlarmReceiver / Activity 调用；返回是否被接受执行。 */
        fun submit(reason: String): SubmitResult {
            val svc = instance
            if (svc == null || !isConnected) {
                ScriptLog.w("无障碍服务未连接，任务[$reason]被丢弃")
                return SubmitResult.NOT_CONNECTED
            }
            if (svc.running) {
                ScriptLog.w("上一轮任务尚未结束，忽略本次触发[$reason]")
                return SubmitResult.BUSY
            }
            svc.running = true
            svc.worker.execute { svc.runJob(reason) }
            return SubmitResult.ACCEPTED
        }
    }

    @Volatile
    private var running = false

    /** 上一次打印的 a11y 事件包名，用于抑制刷屏日志。 */
    private var lastEventPkg: String? = null

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "farm-worker").apply { isDaemon = true }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        ScriptLog.i("无障碍服务已连接")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ScriptLog.w("无障碍服务断开")
        instance = null
        isConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        isConnected = false
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * AccessibilityService 的抽象方法必须实现。
     * 本工程采用「主动轮询 + 按需点击」策略，事件回调本身不驱动逻辑，
     * 但保留实现并记录事件类型，便于排查「为什么没收到事件」。
     */
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 不在此处执行点击：事件回调发生在系统主线程，
        // 在其中做耗时操作会导致 ANR 与无障碍服务被系统判定卡死。
        //
        // ⚠️ 2026-10-19：notificationTimeout=50ms，原实现每个事件都打一条
        // 「a11y event」，实测每秒数条，logcat 被刷屏、影响排查。
        // 现在只在包名变化时打印，保留「页面切换」这一关键信息。
        val pkg = event?.packageName?.toString()
        if (pkg != lastEventPkg) {
            lastEventPkg = pkg
            Log.d(TAG, "a11y event: ${event?.eventType} pkg=$pkg")
        }
    }

    override fun onInterrupt() {
        ScriptLog.w("无障碍服务被中断")
    }

    private fun runJob(reason: String) {
        val start = SystemClock.uptimeMillis()
        ScriptLog.i("===== 开始执行 [$reason] =====")
        var result = "未知"
        try {
            val workflow = FarmWorkflow(this)
            result = workflow.run()
        } catch (t: Throwable) {
            ScriptLog.e("任务执行异常: ${t.message}", t)
            result = "异常终止: ${t.message}"
        } finally {
            val cost = SystemClock.uptimeMillis() - start
            ScriptLog.i("===== 执行结束 [$reason] 结果=$result 耗时=${cost}ms =====")
            running = false
            // ⚠️ 这里【绝对不要】按返回。
            // 原实现是无条件 `Locator.pressBack(this)`，注释写「回到桌面，避免停在淘宝里」，
            // 但实测后果与注释完全相反：任务结束时已经停在农场首页，
            // 这一下返回把它直接退到【淘宝首页】（Welcome → 首页），
            // 直接违背「完成后保留在芭芭农场首页」这条需求。
            // 用户反馈「又返回到淘宝首页了」就是这一句造成的；
            // 它与 step7_finish() 里那次误判返回叠加，一共多退了两层。
            // 「留在农场页」本身就是需求，因此收尾不做任何页面操作。
            // 立刻停掉前台服务（不等 18 分钟看门狗），
            // 否则通知残留，且服务实例被复用会吞掉用户的下一次手动触发
            runCatching { FarmForegroundService.stop(this) }
        }
    }

    /**
     * 关闭挡路弹窗。淘宝启动/页面切换时高频出现，
     * 不处理会让后续所有点击偏移到弹窗上。
     *
     * 性能注意：不能用「逐个文案串行等超时」的方式探测，
     * 7 个候选文案 × 400ms × 4 轮 ≈ 11s，光清弹窗就吃掉一分钟。
     * 因此先用一次整页快照做 O(n) 语义预筛，只有确实存在候选文案时才进入逐项查找。
     */
    fun dismissPopups(maxRound: Int = 4) {
        // ⚠️ 实测闸门：任务面板打开时【一律不清弹窗】。
        // 原因：面板是 bottom sheet，其下农场页节点仍在树中，且面板自身的
        // 关闭按钮文案就是「关闭」（Button）。清弹窗会直接把面板关掉，
        // 造成「任务列表被反复开关」+ 全部产出为 0。
        // 这里用面板状态做闸门，比按坐标/文案区分更可靠。
        val snapshot = Locator.dumpTexts(this)
        if (Selectors.TASK_PANEL_MARKS.any { snapshot.contains(it) }) {
            Log.d(TAG, "任务面板已打开，跳过清弹窗以免误关面板")
            return
        }
        repeat(maxRound) {
            // 快速预筛：整页快照里没有任何候选文案就直接退出本轮
            if (!Locator.pageContainsAny(this, *Selectors.POPUP_CLOSE_TEXTS.toTypedArray())) {
                return
            }
            val node = Locator.findFirstByPriority(
                this, Selectors.POPUP_CLOSE_TEXTS, timeoutMs = 400L,
                blacklist = Selectors.FARM_BLACKLIST_TEXTS
            ) ?: return
            ScriptLog.i("关闭弹窗")
            Locator.click(this, node)
            SystemClock.sleep(600)
        }
    }

    /**
     * 当前是否停在芭芭农场页。
     *
     * ⚠️ 判断依据必须是【农场页专有】文案（阳光农场/集肥料/可施肥）。
     * 不能用「芭芭农场」——实测淘宝首页也有同名图标，用它会 100% 误判，
     * 导致步骤2 跳过进入、后续操作全落在首页上。
     */
    fun isOnFarmPage(): Boolean {
        val snapshot = Locator.dumpTexts(this)
        val hasFarmMark = snapshot.contains(Selectors.FARM_PAGE_MARK) ||
                snapshot.contains(Selectors.FARM_PAGE_MARK_ALT) ||
                snapshot.contains(Selectors.FARM_PAGE_MARK_ALT2)
        if (!hasFarmMark) return false

        // ★ 面板开着时直接判「在农场页」。
        // 面板是盖在农场页之上的 bottom sheet，底层就是农场页；
        // 而面板底部的商品推荐区会带出「闪购 / 百亿补贴 / 今日推荐」这类首页文案，
        // 若照旧用 HOME_PAGE_MARKS 排除，就会把「面板 + 农场页」误判成「不在农场页」——
        // 实测后果：收尾时 isOnFarmPage() 连续 12 秒为 false，
        // 白等到超时后误按一次返回（日志已实证）。
        if (Selectors.TASK_PANEL_MARKS.any { snapshot.contains(it) }) return true

        // 出现首页专有文案时，即使有农场标志也判为不在农场页（防止混合页误判）
        val looksLikeHome = Selectors.HOME_PAGE_MARKS.any { snapshot.contains(it) }
        return !looksLikeHome
    }

    /**
     * 当前是否停在【芭芭农场首页】（而不是盖在它上面的任务面板/子页）。
     *
     * ⚠️ 必须与 isOnFarmPage() 区分开，这是「任务做完要留在农场首页」这条需求的关键。
     *
     * 实测事实：任务面板是 bottom sheet，打开时农场页节点
     * （阳光农场 / 集肥料 / 可施肥）依然完整存在且 clickable，
     * 因此 isOnFarmPage() 在面板打开时同样返回 true。
     * 用 isOnFarmPage() 做收尾判断，会误判成「已在农场首页」，
     * 结果把任务面板留在屏幕上就收工了。
     *
     * 因此这里额外要求：页面上不出现任何浮层特征（面板/浏览详情页/答题页）。
     */
    fun isOnFarmHomePage(): Boolean {
        // ⚠️ 只 dump 一次。
        // 早期写成 `if (!isOnFarmPage()) return false` + 再 dump 一次，
        // 两次 dump 之间页面若正在重绘（例如施肥后的瞬时残留），
        // 会出现「第一次判定是农场页、第二次却命中了浮层特征」的自相矛盾，
        // 于是误报「收尾：未确认回到农场首页」（实测日志里出现过，但屏幕
        // 快照明明就是农场首页）。合并到同一个快照判定即可消除。
        val snapshot = Locator.dumpTexts(this)
        val hasFarmMark = snapshot.contains(Selectors.FARM_PAGE_MARK) ||
                snapshot.contains(Selectors.FARM_PAGE_MARK_ALT) ||
                snapshot.contains(Selectors.FARM_PAGE_MARK_ALT2)
        if (!hasFarmMark) return false
        if (Selectors.HOME_PAGE_MARKS.any { snapshot.contains(it) }) return false
        if (Selectors.FARM_OVERLAY_MARKS.any { snapshot.contains(it) }) return false
        // 出现任务行进度形如 (0/4) 也说明任务面板还开着。
        // 实测：面板关闭后部分文案仍会以「可见」状态残留在 H5 节点树里，
        // 只靠固定文案判断会漏，用「进度括号」这个结构化特征更可靠。
        // （已实测复核：面板正常关闭后无残留，该判据不会误伤农场首页。）
        if (Regex("\\(\\d+/\\d+\\)").containsMatchIn(snapshot)) return false
        return true
    }

    /**
     * 任务面板是否真的开着。
     *
     * ⚠️ 必须【或】上「进度括号 (x/y)」这个结构化特征。
     * 实测踩坑：面板内容可滚动，扫完一屏后标题「肥料明细 / 做任务赚肥料」
     * 会滚出屏幕，只按固定文案判断会误判成「面板没开」——
     * 于是该关的时候没关，面板一路留到收尾（日志：收尾明细里仍报
     * 「命中浮层文案=[肥料明细, 每天答题领肥料] 进度括号=[(0/1)×7]」）。
     * 而 `(x/y)` 是面板独有特征（农场首页实测不含），不受滚动影响。
     */
    private fun taskPanelPresent(): Boolean {
        val snapshot = Locator.dumpTexts(this)
        if (Selectors.TASK_PANEL_MARKS.any { snapshot.contains(it) }) return true
        return Regex("\\(\\d+/\\d+\\)").containsMatchIn(snapshot)
    }

    /** 关闭任务面板。优先点面板自己的「关闭」按钮，失败再退回返回键。 */
    fun closeTaskPanel(): Boolean {
        if (!taskPanelPresent()) return true // 本来就没开

        val close = Locator.findFirstByPriority(this, Selectors.TASK_PANEL_CLOSE_TEXTS, timeoutMs = 1200L)
        if (close != null && Locator.click(this, close)) {
            SystemClock.sleep(1500)
        }
        // 仍在（说明「关闭」按钮没点到）才退回返回键。
        // ⚠️ 这个二次确认不能省：面板已关时盲按返回 = 直接退出农场到淘宝首页。
        if (taskPanelPresent()) {
            Locator.pressBack(this)
            SystemClock.sleep(1500)
        }
        return !taskPanelPresent()
    }
}