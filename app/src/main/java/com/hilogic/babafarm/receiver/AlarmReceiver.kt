package com.hilogic.babafarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hilogic.babafarm.accessibility.FarmAccessibilityService
import com.hilogic.babafarm.core.ScriptLog
import com.hilogic.babafarm.schedule.AlarmScheduler
import com.hilogic.babafarm.service.FarmForegroundService

/**
 * 闹钟触发入口。
 *
 * 为什么还要启一个前台服务：
 *   Android 9 上 BroadcastReceiver 的执行时间只有约 10 秒，
 *   而芭芭农场全流程需要 2~5 分钟。必须把工作转交给
 *   START_STICKY 的前台服务，否则进程会被系统冻结，任务半途而废。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.getStringExtra(AlarmScheduler.EXTRA_REASON) ?: "定时"
        ScriptLog.i("闹钟触发: $reason")

        // 备用闹钟的幂等保护：主闹钟当天已跑过就跳过
        if (reason.contains("备用") && AlarmScheduler.hasFiredToday(context)) {
            ScriptLog.i("主闹钟今日已执行，跳过备用闹钟")
            return
        }

        AlarmScheduler.markFired(context)

        // 重排下一天的闹钟（无论这次是否成功执行，都要保证明天还有定时）
        AlarmScheduler.reschedule(context)

        // 交给前台服务保活执行
        FarmForegroundService.start(context, reason)

        // 若无障碍服务没开，直接无法执行，主动拉起应用提示用户
        if (!FarmAccessibilityService.isConnected) {
            ScriptLog.e("无障碍服务未开启，无法执行自动化，请检查系统设置")
        }
    }
}