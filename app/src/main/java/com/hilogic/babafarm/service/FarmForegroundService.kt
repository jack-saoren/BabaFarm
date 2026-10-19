package com.hilogic.babafarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.hilogic.babafarm.accessibility.FarmAccessibilityService
import com.hilogic.babafarm.core.ScriptLog
import com.hilogic.babafarm.ui.MainActivity

/**
 * 执行期前台服务。
 *
 * 承担四件事，都是为了「后台稳定性」：
 * 1. 前台通知：让进程获得前台优先级，系统不会随手回收；
 * 2. PARTIAL_WAKE_LOCK：灭屏时 CPU 不休眠，避免点击动作被 Doze 拖慢或中断；
 * 3. START_STICKY：进程被误杀后系统会重建服务（会再次校验任务是否已完成）；
 * 4. 总超时自杀：无论何种情况，最多跑 18 分钟就结束，防止服务无限存活耗电。
 */
class FarmForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "farm_run"
        private const val NOTIF_ID = 1001
        private const val EXTRA_REASON = "reason"
        private const val MAX_EXECUTION_MS = FarmAccessibilityService.TOTAL_TIMEOUT_MS

        /** 告警通道：风控等需要人工介入的情况 */
        private const val ALERT_CHANNEL_ID = "farm_alert"
        private const val ALERT_NOTIF_ID = 2001

        fun start(context: Context, reason: String) {
            val intent = Intent(context, FarmForegroundService::class.java).apply {
                putExtra(EXTRA_REASON, reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 任务结束后立即停掉前台服务。
         *
         * 为什么要显式停：不主动停的话，服务会一直存活到 18 分钟看门狗，
         * 通知残留、而且服务实例被复用（见 lastHandledStartId 的注释）。
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FarmForegroundService::class.java)) }
        }

        /**
         * 被风控拦截时发高优先级通知。
         *
         * 为什么必须发通知：任务在早上 7 点无人值守时执行，
         * 若被验证码拦截而只是静默失败，用户根本不会知道，
         * 可能连续多天白跑。必须主动提醒人工介入。
         */
        fun notifyBlocked(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "需要人工处理",
                        NotificationManager.IMPORTANCE_HIGH // 高优先级：会弹横幅
                    ).apply { description = "风控验证等需人工介入的情况" }
                    nm.createNotificationChannel(ch)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pi = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    flags
                )
                val n = Notification.Builder(context, ALERT_CHANNEL_ID)
                    .setContentTitle("芭芭农场助手：需要手动验证")
                    .setContentText("检测到淘宝安全验证，任务已安全中止。请打开淘宝手动完成滑块验证。")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                nm.notify(ALERT_NOTIF_ID, n)
                ScriptLog.w("已发出风控告警通知")
            } catch (e: Exception) {
                ScriptLog.e("风控告警通知发送失败: ${e.message}", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private var startedAt = 0L

    /**
     * 已受理过的 startId，用来保证「一次 start 请求只提交一次任务」。
     *
     * ⚠️ 这里不能用 boolean submitted 标志 —— 实测踩过：
     * 前台服务在执行结束后仍会存活到 18 分钟看门狗才自杀，
     * 这段时间内用户再点「立即执行一次」，startService 复用的是【同一个服务实例】，
     * 旧的 boolean 标志还是 true，整个提交逻辑被跳过 →
     * 表现为「点了立即执行完全没反应，日志里一行都没有」。
     * startId 每次 startService 都会递增，用它判断就不会吞掉新请求。
     */
    private var lastHandledStartId = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "手动触发"
        startForeground(NOTIF_ID, buildNotification("芭芭农场任务执行中 · $reason"))
        startedAt = SystemClock.elapsedRealtime()

        if (startId != lastHandledStartId) {
            lastHandledStartId = startId
            when (FarmAccessibilityService.submit(reason)) {
                FarmAccessibilityService.SubmitResult.ACCEPTED ->
                    scheduleSelfStop(MAX_EXECUTION_MS)
                FarmAccessibilityService.SubmitResult.BUSY -> {
                    // ⚠️ 上一轮仍在执行时【不能】3 秒后停服务：
                    //   那会撤掉正在使用的 WakeLock 与前台通知，削弱保活。
                    ScriptLog.w("上一轮任务仍在执行，本次触发忽略；保持前台服务运行")
                    updateNotification("上一轮任务仍在执行，本次触发已忽略")
                }
                FarmAccessibilityService.SubmitResult.NOT_CONNECTED -> {
                    ScriptLog.w("服务已启动但任务未受理: $reason")
                    updateNotification("任务未启动（无障碍未开启）")
                    scheduleSelfStop(3000)
                }
            }
        } else {
            // 同一 startId 的重复回调（系统重投递），不重复提交
            ScriptLog.i("重复的 start 回调(startId=$startId)，忽略")
        }
        // START_STICKY：被系统回收后重建，服务重新校验并补跑（幂等由 last_fire_date + 运行锁保证）
        return START_STICKY
    }

    /**
     * 看门狗：超时后停止自己。
     * 不用 Handler.postDelayed 链式计时，改用 uptimeMillis 差值判断，
     * 避免设备休眠导致计时漂移。
     */
    private fun scheduleSelfStop(timeoutMs: Long) {
        watchdog.postDelayed(object : Runnable {
            override fun run() {
                val cost = SystemClock.elapsedRealtime() - startedAt
                if (cost >= timeoutMs) {
                    ScriptLog.w("执行超时(${cost}ms)，主动停止服务")
                    stopSelfSafely()
                } else {
                    watchdog.postDelayed(this, timeoutMs - cost)
                }
            }
        }, timeoutMs)
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        // minSdk 24，STOP_FOREGROUND_REMOVE 自 API 24 起可用
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watchdog.removeCallbacksAndMessages(null)
        releaseWakeLock()
        ScriptLog.i("前台服务已销毁")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "farm:automation"
        ).apply {
            setReferenceCounted(false)
            // 18 分钟 + 1 分钟余量，防止异常情况下永久持锁
            acquire(MAX_EXECUTION_MS + 60_000)
        }
        ScriptLog.i("已获取唤醒锁")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "农场任务执行状态",
                NotificationManager.IMPORTANCE_LOW // 低优先级：不发声、不打扰
            ).apply {
                description = "显示芭芭农场自动化任务的运行状态"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            flags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("芭芭农场助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}