package com.hilogic.babafarm.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hilogic.babafarm.core.ScriptLog
import com.hilogic.babafarm.receiver.AlarmReceiver
import java.util.Calendar

/**
 * 定时调度。
 *
 * 为什么不能用 setRepeating：
 *   setRepeating 在 Doze 下会被系统合并/延迟，甚至在深度待机时不触发，
 *   且一旦进程被杀，未执行的闹钟会直接丢失。
 *
 * 采用方案：
 *   setAlarmClock() —— 唯一在 Doze 下仍保证准时、且系统不会杀的闹钟类型。
 *   代价是会显示状态栏「下一个闹钟」图标，这是可接受的（也顺带避免了后台限制）。
 *
 * 双保险设计：
 *   1. 主闹钟：每天 07:00 准时触发
 *   2. 备用闹钟：07:10 补跑（针对主闹钟被极端策略拦截的情况）
 *   3. 开机广播：重排所有闹钟
 *   4. 启动时自检：App 每次打开都会校验闹钟还在
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    const val REQUEST_MAIN = 1001
    const val REQUEST_BACKUP = 1002
    const val EXTRA_REASON = "reason"

    private const val KEY_LAST_FIRE_DATE = "last_fire_date"

    /** 应用被设置的时间，格式 yyyy-MM-dd_HH:mm */
    private const val PREF = "schedule_config"
    private const val PREF_HOUR = "hour"
    private const val PREF_MINUTE = "minute"
    private const val PREF_ENABLED = "enabled"

    fun init(context: Context) {
        // 首次运行给个默认值：早上 7:00
        val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!sp.contains(PREF_HOUR)) {
            sp.edit().putInt(PREF_HOUR, 7).putInt(PREF_MINUTE, 0).putBoolean(PREF_ENABLED, true).apply()
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, true)

    fun setTime(context: Context, hour: Int, minute: Int, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_HOUR, hour)
            .putInt(PREF_MINUTE, minute)
            .putBoolean(PREF_ENABLED, enabled)
            .apply()
        reschedule(context)
    }

    fun getTime(context: Context): Pair<Int, Int> {
        val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Pair(sp.getInt(PREF_HOUR, 7), sp.getInt(PREF_MINUTE, 0))
    }

    /** 计算下一个触发时间戳。已过今天则顺延到明天。 */
    private fun nextTriggerAt(hour: Int, minute: Int, minuteOffset: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute + minuteOffset)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context, requestCode: Int, reason: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.hilogic.babafarm.ALARM_FIRE"
            putExtra(EXTRA_REASON, reason)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 12+ 强制要求显式声明 mutability
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * 重排主闹钟 + 备用闹钟。
     * 幂等：重复调用会覆盖旧闹钟，不会产生重复触发。
     */
    fun reschedule(context: Context) {
        if (!isEnabled(context)) {
            cancel(context)
            return
        }
        val (hour, minute) = getTime(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 主闹钟：07:00
        val mainTrigger = nextTriggerAt(hour, minute)
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(mainTrigger, pendingIntent(context, REQUEST_MAIN, "主定时")),
            pendingIntent(context, REQUEST_MAIN, "主定时")
        )
        Log.i(TAG, "主闹钟已设定: $mainTrigger")

        // 备用闹钟：+10 分钟，用于主闹钟被拦截时补跑
        val backupTrigger = nextTriggerAt(hour, minute, 10)
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            backupTrigger,
            pendingIntent(context, REQUEST_BACKUP, "备用定时")
        )
        Log.i(TAG, "备用闹钟已设定: $backupTrigger")

        ScriptLog.i("闹钟重排完成 主=$mainTrigger 备用=$backupTrigger")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, REQUEST_MAIN, "主定时"))
        am.cancel(pendingIntent(context, REQUEST_BACKUP, "备用定时"))
        ScriptLog.i("闹钟已取消")
    }

    /**
     * 当天是否已经成功触发过。用于「备用闹钟不重复跑」。
     * 判定依据：备用闹钟与主闹钟相隔 10 分钟，若主闹钟已跑过则跳过。
     */
    fun hasFiredToday(context: Context): Boolean {
        val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date())
        return sp.getString(KEY_LAST_FIRE_DATE, "") == today
    }

    fun markFired(context: Context) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date())
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_FIRE_DATE, today).apply()
    }

    /**
     * 距离下次触发的毫秒数（倒计时展示用）。
     * 注意：nextTriggerAt 返回的是墙钟时间戳（System.currentTimeMillis 基准），
     * 因此这里也必须用 currentTimeMillis 做差，不能用 elapsedRealtime。
     */
    fun millisUntilNext(context: Context): Long {
        val (hour, minute) = getTime(context)
        val next = nextTriggerAt(hour, minute)
        return next - System.currentTimeMillis()
    }
}