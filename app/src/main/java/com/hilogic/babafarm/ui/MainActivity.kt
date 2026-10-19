package com.hilogic.babafarm.ui

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hilogic.babafarm.accessibility.FarmAccessibilityService
import com.hilogic.babafarm.core.Locator
import com.hilogic.babafarm.core.ScriptLog
import com.hilogic.babafarm.core.Selectors
import com.hilogic.babafarm.schedule.AlarmScheduler
import com.hilogic.babafarm.service.FarmForegroundService

/**
 * 主界面。
 *
 * 这个界面的主要价值不是「设置」，而是「诊断」——
 * 自动化脚本失败时能立刻看出：无障碍是否连通、定时是否在、
 * 电池白名单是否生效、页面节点快照长什么样。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var timeText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScriptLog.init(this)
        Selectors.init(this)
        Selectors.reload()
        AlarmScheduler.init(this)
        buildUi()
        // 每次打开都校正一次闹钟，防止用户改过系统设置后定时丢失
        AlarmScheduler.reschedule(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        root.addView(statusText)

        timeText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }
        root.addView(timeText)

        root.addView(btn("开启无障碍服务") { openAccessibilitySettings() })
        root.addView(btn("加入电池优化白名单") { requestIgnoreBatteryOptimizations() })
        root.addView(btn("设置定时时间") { showTimePicker() })
        root.addView(btn("立即执行一次") { runNow() })
        root.addView(btn("查看当前页面节点（诊断）") { dumpCurrentPage() })
        root.addView(btn("自定义按钮文案（改版自救）") { showSelectorEditor() })
        root.addView(btn("运行日志") { showLogs() })

        logText = TextView(this).apply {
            textSize = 11f
            setPadding(0, 24, 0, 0)
            setTextIsSelectable(true)
            text = ScriptLog.tail(60).joinToString("\n")
        }
        root.addView(logText)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun btn(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun refreshStatus() {
        val a11yOk = FarmAccessibilityService.isConnected
        val alarmOk = AlarmScheduler.isEnabled(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoreBattery = pm.isIgnoringBatteryOptimizations(packageName)
        val (h, m) = AlarmScheduler.getTime(this)
        val next = AlarmScheduler.millisUntilNext(this)

        statusText.text = buildString {
            append("无障碍服务：").append(if (a11yOk) "✅ 已开启" else "❌ 未开启").append('\n')
            append("定时开关：").append(if (alarmOk) "✅ 已开启" else "❌ 已关闭").append('\n')
            append("电池白名单：").append(if (ignoreBattery) "✅ 已加入" else "⚠️ 未加入（Doze 下可能被延迟）").append('\n')
            append("权限提示：若通知栏无「允许后台运行」，请在系统电池设置中允许").append('\n')
        }

        timeText.text = String.format(
            "定时时间 %02d:%02d\n距离下次执行 %s",
            h, m, formatDuration(next)
        )
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "已过期（下次打开应用会自动重排）"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return String.format("%d 小时 %d 分钟", h, m)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            toast("无法打开无障碍设置，请到「设置 - 无障碍」手动开启")
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在白名单中")
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast("无法自动跳转，请手动到「设置 - 电池 - 后台活动/优化」中将本应用设为不限")
        }
    }

    private fun showTimePicker() {
        val (h, m) = AlarmScheduler.getTime(this)
        TimePickerDialog(this, { _, hour, minute ->
            AlarmScheduler.setTime(this, hour, minute, true)
            toast(String.format("已设定 %02d:%02d 每天执行", hour, minute))
            refreshStatus()
        }, h, m, true).show()
    }

    private fun runNow() {
        if (!FarmAccessibilityService.isConnected) {
            AlertDialog.Builder(this)
                .setTitle("无障碍服务未开启")
                .setMessage("脚本依赖无障碍服务执行点击。请先开启「芭芭农场助手」的无障碍权限。")
                .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        FarmForegroundService.start(this, "手动触发")
        toast("已提交执行，稍后查看结果")
    }

    /**
     * 诊断入口：打印当前页面的所有可见文案。
     * 这是改版后定位「为什么点不到」的最直接手段——
     * 能立刻看出文案变了、H5 节点没挂载、还是页面根本没进去。
     */
    private fun dumpCurrentPage() {
        val svc = FarmAccessibilityService.instance
        if (svc == null) {
            toast("无障碍服务未连接")
            return
        }
        val snapshot = Locator.dumpTexts(svc)
        ScriptLog.i("=== 诊断快照 ===\n$snapshot")
        AlertDialog.Builder(this)
            .setTitle("当前页面节点快照")
            .setMessage(snapshot.take(1500))
            .setPositiveButton("复制", { _, _ ->
                copyToClipboard(snapshot)
                toast("已复制")
            })
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showSelectorEditor() {
        val input = EditText(this).apply {
            hint = """{"FERTILIZE_TEXTS":["一键施肥","全部施用"]}"""
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText("""{"FERTILIZE_TEXTS":["一键施肥","全部施肥","批量施肥"],"SHAKE_TEXTS":["摇一摇"],"TASK_ENTRY_TEXTS":["集肥料"]}""")
            setPadding(40, 40, 40, 40)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义按钮文案")
            .setMessage("淘宝改版后，把新文案填进来即可，无需重新打包。\n可覆盖字段：" +
                    "FERTILIZE_TEXTS / SHAKE_TEXTS / TASK_ENTRY_TEXTS")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                Selectors.overrideFromJson(input.text.toString())
                toast("已保存并生效")
            }
            .setNegativeButton("恢复默认") { _, _ ->
                getSharedPreferences("selectors", MODE_PRIVATE).edit().clear().apply()
                Selectors.reload()
                toast("已恢复默认")
            }
            .show()
    }

    private fun showLogs() {
        val logs = ScriptLog.tail(300).joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("运行日志（最近300行）")
            .setMessage(logs.ifEmpty { "暂无日志" })
            .setPositiveButton("复制") { _, _ ->
                copyToClipboard(logs)
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("farm_log", text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}