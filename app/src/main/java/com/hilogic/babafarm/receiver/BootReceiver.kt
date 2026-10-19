package com.hilogic.babafarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hilogic.babafarm.core.ScriptLog
import com.hilogic.babafarm.schedule.AlarmScheduler

/**
 * 开机 / 应用更新后重排闹钟。
 *
 * 关键原因：进程被杀或设备重启后，之前设定的闹钟全部失效，
 * 必须靠这个广播重建。这是「每天稳定执行」的前提。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ScriptLog.init(context)
                SelectorsInit.ensure(context)
                AlarmScheduler.init(context)
                AlarmScheduler.reschedule(context)
                ScriptLog.i("开机/更新广播已处理，闹钟已重排")
            }
        }
    }
}

private object SelectorsInit {
    fun ensure(context: Context) {
        com.hilogic.babafarm.core.Selectors.init(context)
        com.hilogic.babafarm.core.Selectors.reload()
    }
}