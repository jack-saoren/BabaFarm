package com.hilogic.babafarm.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 节点查找与交互工具层。
 *
 * 设计要点（针对淘宝 H5 页面的现实约束）：
 * 1. 所有查找都是「带超时的轮询」而非一次性查询。H5 节点挂载有延迟，
 *    一次性 find 几乎必然落空。
 * 2. 每个语义动作都提供多条路径：文案 -> contentDescription -> viewId -> 坐标兜底。
 *    淘宝 A/B 实验会改文案，因此文案只作为第一优先级而非唯一依据。
 * 3. 每次查找失败都会 dump 当前页面文案快照到日志，便于线上定位。
 */
object Locator {

    private const val TAG = "Locator"
    private const val DEFAULT_TIMEOUT_MS = 8000L
    private const val POLL_INTERVAL_MS = 250L

    // ---------------- 节点查找 ----------------

    /**
     * 按 viewId 查找。淘宝布局 id 相对稳定，比文案更抗改版，
     * 因此在工程中作为文案失效时的第二优先级。
     */
    /**
     * 按 viewId 查找。
     *
     * 注意：不能用 AccessibilityNodeInfo.findAccessibilityNodeInfosById()——
     * 该方法在 android.jar 编译桩中是 @hide（已用 javap 核实：公开 SDK 只暴露
     * findAccessibilityNodeInfosByText / findFocus），引用它会直接编译失败。
     * 因此改为遍历节点树匹配 viewId，行为等价且可编译。
     */
    fun findById(
        service: AccessibilityService,
        viewId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                findByViewIdInTree(root, viewId)?.let { return it }
            }
            if (SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        Log.w(TAG, "findById 未命中: $viewId")
        return null
    }

    private fun findByViewIdInTree(
        node: AccessibilityNodeInfo,
        viewId: String
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.viewIdResourceName == viewId) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByViewIdInTree(child, viewId)?.let { return it }
        }
        return null
    }

    /**
     * 单次遍历匹配任一候选文案。
     *
     * 相比「按候选文案逐个 findAccessibilityNodeInfosByText」的优势：
     * 后者是候选数 × 超时的串行开销（3 个候选 × 800ms = 2.4s），
     * 在循环里会累积成几十秒的纯等待。这里每轮只遍历一次树，O(候选数) 只体现在比较上。
     *
     * @param clickableOnly 只返回可点击节点（用于按钮语义）
     */
    fun findFirstByTexts(
        service: AccessibilityService,
        texts: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        clickableOnly: Boolean = false
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root = service.rootInActiveWindow
            if (root != null) {
                searchTree(root, texts, clickableOnly)?.let { return it }
            }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    /** 深度优先，父节点优先——H5 中按钮容器通常也是可点的，父优先更接近真实点击目标 */
    private fun searchTree(
        node: AccessibilityNodeInfo,
        texts: List<String>,
        clickableOnly: Boolean
    ): AccessibilityNodeInfo? {
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (node.isVisibleToUser && (!clickableOnly || node.isClickable)) {
            for (kw in texts) {
                if (t == kw || d == kw) return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchTree(child, texts, clickableOnly)?.let { return it }
        }
        return null
    }

/**
     * 单次遍历，按优先级匹配文案。
     *
     * 为什么需要「优先级」而不是「先命中先用」：
     * 真机实测农场页同时存在三个都含「施肥」的节点：
     *   - 「再施肥 3 次可领」        y=1701  ← 引导标签，点了只打开面板，不执行施肥
     *   - 「每次施肥5次，未选择」      y=2100  ← 次数选择器，也不是执行按钮
     *   - 「施肥，肥料15700，可施肥28次」 y=2215  ← 这才是真正的一键施肥按钮
     * 若用「包含匹配 + 先命中先用」，必然先命中 y=1701 的引导标签，导致施肥永远失败。
     * 因此改成：按 priority 列表顺序，先尝试高优先级关键词，找不到再降级。
     *
     * @param priority 关键词列表，越靠前优先级越高
     * @param blacklist 命中即跳过的文案（会跳离农场的干扰项）
     */
    fun findFirstByPriority(
        service: AccessibilityService,
        priority: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        blacklist: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                // 每轮都按优先级重新尝试，保证高优先级目标一旦出现就能抢占
                for (kw in priority) {
                    searchTreeContains(root, listOf(kw), blacklist)?.let { return it }
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * 单次遍历，【包含】匹配任一候选文案。
     *
     * 注意：业务按钮通常是「施肥，肥料15700，可施肥28次」这类带动态数值的长串，
     * 不能全等匹配。但不要单独用它做关键动作的定位——
     * 应优先用 {@link findFirstByPriority}，否则容易命中引导文案。
     *
     * @param blacklist 命中这些文案的节点直接跳过（如「搜索」「返回首页」），
     *                  防止点到会离开农场的干扰项导致流程走偏。
     */
    fun findFirstByTextsContaining(
        service: AccessibilityService,
        texts: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        blacklist: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                searchTreeContains(root, texts, blacklist)?.let { return it }
            }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun searchTreeContains(
        node: AccessibilityNodeInfo,
        texts: List<String>,
        blacklist: List<String>
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            val hit = texts.any { kw -> t.contains(kw) || d.contains(kw) }
            val blocked = blacklist.any { kw -> t == kw || d == kw }
            if (hit && !blocked) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchTreeContains(child, texts, blacklist)?.let { return it }
        }
        return null
    }

    /** 遍历整棵树，找第一个满足条件且可点击的节点（深搜，覆盖 WebView 深层节点） */
    fun findClickableInTree(
        service: AccessibilityService,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root = service.rootInActiveWindow
            if (root != null) {
                dfs(root, predicate)?.let { return it }
            }
            if (SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    // predicate 必须显式作为参数传入：在 object 的方法里直接引用外部函数的参数
    // 会导致 Kotlin 无法解析该符号。
    private fun dfs(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isClickable && predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dfs(child, predicate)?.let { return it }
        }
        return null
    }

    /**
     * 在「锚点节点」的同一行内，找可点击的按钮。
     *
     * 为什么需要它：任务面板每行结构是「任务名 + 奖励 + 按钮」。
     * 实测面板里有 6 个同名「去完成」按钮（对应 6 个不同任务），
     * 若直接按文案找「去完成」，会点到第一个而不是目标任务行的那个。
     *
     * 做法：以任务名节点为锚点，在同一垂直区间（±yTolerance）内找锚点右侧
     * 最接近的可点击节点；先排除黑名单/价格/超长商品文案，再按垂直距离取胜。
     *
     * @param anchor 锚点节点（通常是任务名）
     * @param yTolerance 垂直容差（px），默认 90 可覆盖一行高度
     * @param blacklist 命中即排除的文案（如「红包待使用」这类悬浮横幅）
     */
    fun findClickableInSameRow(
        service: AccessibilityService,
        anchor: AccessibilityNodeInfo,
        yTolerance: Int = 90,
        blacklist: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return null
        val anchorRect = Rect()
        anchor.getBoundsInScreen(anchorRect)
        if (anchorRect.isEmpty) return null
        val anchorCy = anchorRect.centerY()
        val anchorCx = anchorRect.centerX()

        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectRowCandidates(root, anchorCy, yTolerance, anchorCx, candidates)

        // ⚠️ 实测坑点：面板底部被悬浮横幅（「红包待使用·赚大额肥料」「¥8 满300可用」）
        // 和面板底下商品流的卡片覆盖，这些节点同样 clickable 且落在同一行垂直区间内。
        // 若只按「水平方向离锚点最近」挑选，最后一行（领百亿外卖红包）会选中商品卡片
        // 而不是「去完成」，点下去直接跳走。
        // 因此规则收紧为：先排除黑名单 / 价格 / 超长商品标题，
        // 再取【垂直方向最接近锚点】的候选（水平距离仅作并列时的次选）。
        var best: AccessibilityNodeInfo? = null
        var bestDy = Int.MAX_VALUE
        var bestDx = Int.MAX_VALUE
        for (node in candidates) {
            val label = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
            if (label.isBlank() || label.length > 24 || label.contains("¥")) continue
            if (blacklist.any { label.contains(it) }) continue
            val r = Rect()
            node.getBoundsInScreen(r)
            val dy = Math.abs(r.centerY() - anchorCy)
            val dx = Math.abs(r.centerX() - anchorCx)
            if (dy < bestDy || (dy == bestDy && dx < bestDx)) {
                best = node
                bestDy = dy
                bestDx = dx
            }
        }
        return best
    }

    private fun collectRowCandidates(
        node: AccessibilityNodeInfo,
        anchorCy: Int,
        yTolerance: Int,
        anchorCx: Int,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isVisibleToUser && node.isClickable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty &&
                Math.abs(r.centerY() - anchorCy) <= yTolerance &&
                r.centerX() > anchorCx
            ) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectRowCandidates(child, anchorCy, yTolerance, anchorCx, out)
        }
    }

    /**
     * 收集与锚点【同一行】内所有节点的文案（用 '|' 连接）。
     *
     * 用途：判断这一行任务属于哪一类。
     * 实测依据：淘宝内的「浏览类」任务，行内副标题一定是「浏览Xs得 +N」
     * （浏览15秒得 / 浏览10s得 / 浏览5秒得 / 浏览得奖励）；
     * 而跳第三方 App 的任务副标题是「去逛逛得」「还可领」，两者可明确区分。
     */
    fun collectRowTexts(
        service: AccessibilityService,
        anchor: AccessibilityNodeInfo,
        yTolerance: Int = 90
    ): String {
        val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return ""
        val r = Rect()
        anchor.getBoundsInScreen(r)
        if (r.isEmpty) return ""
        val sb = StringBuilder()
        collectRowTextsRec(root, r.centerY(), yTolerance, sb)
        return sb.toString()
    }

    private fun collectRowTextsRec(
        node: AccessibilityNodeInfo,
        anchorCy: Int,
        yTolerance: Int,
        sb: StringBuilder
    ) {
        if (node.isVisibleToUser) {
            val rr = Rect()
            node.getBoundsInScreen(rr)
            if (!rr.isEmpty && Math.abs(rr.centerY() - anchorCy) <= yTolerance) {
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                val lbl = if (t.isNotEmpty()) t else d
                if (lbl.isNotEmpty()) sb.append(lbl).append('|')
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectRowTextsRec(child, anchorCy, yTolerance, sb)
        }
    }

    /**
     * 节点是否真的在当前屏幕内可见。
     *
     * 为什么要单独判：AccessibilityNodeInfo 的 isVisibleToUser 并不可靠，
     * H5 容器关闭后节点常常仍留在树里，bounds 是 [0,0][0,0] 或已被移出屏幕。
     * 不过滤的话，页面状态判定（是否在农场页 / 任务面板是否打开）会被
     * 这些残留节点污染。
     */
    fun isNodeOnScreen(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val m = service.resources.displayMetrics
        return r.bottom > 0 && r.top < m.heightPixels &&
                r.right > 0 && r.left < m.widthPixels
    }

    /**
     * 在指定纵向位置附近，找文案匹配正则的节点。
     *
     * 用途：任务面板里同一行任务的【名称会随进度推进而改写】
     * （实测：`看严选推荐商品(2/5)` 推进后变成 `搜一搜你心仪的宝贝(3/5)`），
     * 因此「按名字追踪同一个任务」必然失配。改为按【纵向位置】定位同一行，
     * 因为面板的行顺序和行高是稳定的。
     */
    fun findTextNodeNearY(
        service: AccessibilityService,
        yCenter: Int,
        tolerance: Int = 80,
        pattern: Regex
    ): AccessibilityNodeInfo? {
        val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestDy = Int.MAX_VALUE

        fun walk(node: AccessibilityNodeInfo) {
            if (isNodeOnScreen(service, node)) {
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() && pattern.containsMatchIn(t)) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    val dy = Math.abs(r.centerY() - yCenter)
                    if (dy <= tolerance && dy < bestDy) {
                        best = node
                        bestDy = dy
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return best
    }

    /**
     * 找【可点击】且文案（text 或 contentDescription）【全等】于候选词的节点。
     *
     * 为什么需要它：
     * 实测淘宝不少按钮把文案挂在 contentDescription 上，node.text 是空的。
     * 典型例子就是搜索页的「搜索」按钮 —— 用只比对 text 的 findByText 找不到，
     * 结果退化成「点击输入框当提交」，等于没提交（日志特征：
     * 「写入搜索词…结果=true」紧跟「未找到『搜索』按钮，退回点击输入框」，
     * 之后搜索页纹丝不动）。
     *
     * 用【全等】而非包含：搜索页上「搜索有福利」「搜索后浏览立得奖励」
     * 「历史搜索」「搜索发现」都含「搜索」二字，包含匹配会命中这些纯文案节点。
     */
    fun findClickableByExactText(
        service: AccessibilityService,
        texts: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                findClickableExactInTree(service, root, texts)?.let { return it }
            }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun findClickableExactInTree(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        if (node.isClickable && isNodeOnScreen(service, node)) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (texts.any { it == t || it == d }) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableExactInTree(service, child, texts)?.let { return it }
        }
        return null
    }

    /**
     * 找【可点击】且文案（text 或 contentDescription）【包含】任一候选词的节点。
     * 用于文案带后缀的可点条目（如搜索页的热搜词条「冲锋衣男 近一个月点击超5千」）。
     */
    fun findClickableByTextsContaining(
        service: AccessibilityService,
        texts: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                findClickableContainsInTree(service, root, texts)?.let { return it }
            }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun findClickableContainsInTree(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        if (node.isClickable && isNodeOnScreen(service, node)) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (texts.any { t.contains(it) || d.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableContainsInTree(service, child, texts)?.let { return it }
        }
        return null
    }

    /**
     * 收集当前页面【屏幕内可见】的文本快照。
     * 用于页面状态判定（pageContainsAny）与失败诊断。
     *
     * ⚠️ 实测踩坑：早期版本收集全树文案（不过滤可见性），
     * 任务面板关闭后残留的不可见节点仍被算进快照，导致
     *   1) isTaskPanelOpen() 在面板已关闭时仍返回 true（后续不再重新打开面板）；
     *   2) isOnFarmHomePage() 永远判不出「农场首页」→ 收尾反复按返回，
     *      最后把农场页也退掉，停在淘宝首页。
     * 因此这里必须只收屏幕内可见的节点。
     */
    fun dumpTexts(service: AccessibilityService): String {
        val sb = StringBuilder()
        val root = service.rootInActiveWindow ?: return "<root=null>"
        collectTexts(service, root, sb, 0)
        return if (sb.isEmpty()) "<empty>" else sb.toString()
    }

    /**
     * 收集当前页面【全部】文案，不做屏幕可见性过滤。
     *
     * ⚠️ 存在的理由（实测结论）：淘宝任务面板是 H5，屏外的任务行在无障碍树里
     * bounds 恒为 `[0,0][0,0]`，会被 dumpTexts() 的可见性过滤丢掉。
     * 双向对照实测（上滑 3 次 / 下滑 3 次）与 ACTION_SCROLL_FORWARD 均确认：
     * 这些行的 bounds【不随滚动更新】—— 读得到名字，但拿不到坐标、点不了。
     *
     * 那为什么还要读？因为它们必须被【如实登记】。
     * 否则日志只报「面板共扫到 6 个任务行」，
     * 会把「搜一搜你喜欢的商品」这类真实存在的淘宝内任务整个掩盖掉，
     * 看起来像「面板里根本没有这个任务」。
     *
     * 用途仅限「登记清单」。页面状态判定必须继续用 dumpTexts()（严格可见）。
     */
    fun dumpAllTexts(service: AccessibilityService): String {
        val sb = StringBuilder()
        val root = service.rootInActiveWindow ?: return "<root=null>"
        collectAllTexts(root, sb, 0)
        return if (sb.isEmpty()) "<empty>" else sb.toString()
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 25) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty() || !d.isNullOrEmpty()) {
            val label = if (!t.isNullOrEmpty()) t else d
            sb.append(label).append('|')
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllTexts(it, sb, depth + 1) }
        }
    }

    private fun collectTexts(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int
    ) {
        if (depth > 25) return
        if (isNodeOnScreen(service, node)) {
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty() || !d.isNullOrEmpty()) {
                val label = if (!t.isNullOrEmpty()) t else d
                sb.append(label).append('|')
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(service, it, sb, depth + 1) }
        }
    }

    // ---------------- 交互动作 ----------------

    /**
     * 点击节点。策略顺序：
     * 1. ACTION_CLICK
     * 2. 若节点自身不可点击，向上找最近的可点击祖先（文案常挂在子节点上）
     * 3. 都失败则用坐标手势点击节点中心（H5 场景下这是主要命中路径）
     */
    fun click(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 6) {
            if (target.isClickable && target.isEnabled) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "click via ACTION_CLICK")
                    return true
                }
            }
            target = target.parent
            depth++
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        Log.i(TAG, "click via gesture at ${rect.centerX()},${rect.centerY()}")
        return tap(service, rect.centerX(), rect.centerY())
    }

    /**
     * 用【真实坐标手势】点击节点中心。
     *
     * 为什么需要它：click() 优先走 ACTION_CLICK，而 H5 页面里的按钮
     * 对 ACTION_CLICK 常常「返回 true 但什么都不做」——看起来点了，实际没生效。
     * 需要确定性触发时（如农场页的「施肥」）用真实手势更可靠。
     *
     * 实测依据：对「施肥，肥料18116，可施肥30次」按钮发真实触摸后，
     * 可施肥次数 30→29→28、等级进度推进、阶段从「展叶」升到「茂盛」；
     * 而走 ACTION_CLICK 的路径在同一页面上拿不到这种确定性。
     */
    fun tapNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        Log.i(TAG, "tapNode center=${r.centerX()},${r.centerY()}")
        return tap(service, r.centerX(), r.centerY())
    }

    /** 坐标手势点击。x/y 为绝对像素。 */    fun tap(service: AccessibilityService, x: Int, y: Int, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "tap done at $x,$y")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "tap cancelled at $x,$y")
                }
            },
            null
        )
    }

    /**
     * 按屏幕比例点击。最后的兜底手段。
     * 用比例而非绝对像素，可跨机型/分辨率复用同一套坐标配置。
     */
    fun tapRatio(service: AccessibilityService, ratioX: Float, ratioY: Float): Boolean {
        val metrics = service.resources.displayMetrics
        val x = (metrics.widthPixels * ratioX).toInt()
        val y = (metrics.heightPixels * ratioY).toInt()
        Log.i(TAG, "tapRatio ($ratioX,$ratioY) -> $x,$y")
        return tap(service, x, y)
    }

    /** 滑动（用于滚动列表找元素） */
    fun swipe(
        service: AccessibilityService,
        x1: Int, y1: Int, x2: Int, y2: Int,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /** 向上滚动一屏（内容向上，即浏览下方内容） */
    fun scrollDown(service: AccessibilityService) {
        val m = service.resources.displayMetrics
        val cx = m.widthPixels / 2
        swipe(service, cx, (m.heightPixels * 0.72).toInt(),
            cx, (m.heightPixels * 0.32).toInt())
    }

/**
 * 查找当前页面中的可编辑输入框（EditText）。
     *
     * 说明：不能用 findFocus(InputMethodManager.FOCUS_INPUT)，
     * FOCUS_INPUT 在公开 SDK 中是 @hide（已用 javap 核实），引用会编译失败。
     * 改为遍历树找 isEditable 的节点，行为等价。
     */
    /**
     * 查找当前页面中的可编辑输入框（EditText）。
     *
     * 说明：不能用 findFocus(InputMethodManager.FOCUS_INPUT)，
     * FOCUS_INPUT 在公开 SDK 中是 @hide（已用 javap 核实），引用会编译失败。
     * 改为遍历树找 isEditable 的节点，行为等价。
     */
    fun findEditableInput(service: AccessibilityService, timeoutMs: Long = 3000L): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                findEditableInTree(root)?.let { return it }
            }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun findEditableInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableInTree(child)?.let { return it }
        }
        return null
    }

    /** 返回键。用全局动作，比 keyevent 更稳（不依赖焦点） */
    fun pressBack(service: AccessibilityService): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    /** 当前前台包名 */
    fun currentPackage(service: AccessibilityService): String? {
        val root = service.rootInActiveWindow ?: return null
        return root.packageName?.toString()
    }

    /** 等待指定包名成为前台页面 */
    fun waitForPackage(service: AccessibilityService, pkg: String, timeoutMs: Long = 15000L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (currentPackage(service) == pkg) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** 判断当前页面是否包含任一特征文案（页面状态校验的基础） */
    fun pageContainsAny(service: AccessibilityService, vararg keywords: String): Boolean {
        val snapshot = dumpTexts(service)
        return keywords.any { snapshot.contains(it) }
    }
}