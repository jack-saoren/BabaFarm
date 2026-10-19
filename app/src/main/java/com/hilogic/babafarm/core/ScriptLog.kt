package com.hilogic.babafarm.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 双通道日志：logcat + 文件。
 *
 * 自动化脚本最大的痛点是「昨晚跑失败了但没人知道」，
 * 所以每次执行都落盘，主界面可直接查看最后 N 行，无需连 adb。
 */
object ScriptLog {

    private const val TAG = "FarmScript"
    private const val LOG_DIR = "farm_logs"
    private const val MAX_LINES = 500

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private var logFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        // 每次进程启动切一个新文件
        logFile = File(dir, "run_${fileFmt.format(Date())}.log")
        // 只保留最近 10 个日志文件，防止无限增长
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach { it.delete() }
    }

    fun i(msg: String) {
        write("INFO", msg)
    }

    fun w(msg: String) {
        write("WARN", msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        val line = "${timeFmt.format(Date())} ERROR $msg${t?.let { " | ${it.message}" } ?: ""}"
        synchronized(buffer) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
        Log.e(TAG, msg, t)
        logFile?.appendText(line + "\n")
    }

    private fun write(level: String, msg: String) {
        val line = "${timeFmt.format(Date())} $level $msg"
        synchronized(buffer) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
        Log.i(TAG, msg)
        logFile?.appendText(line + "\n")
    }

    /** 取最近 N 行，主界面展示用 */
    fun tail(n: Int = 200): List<String> =
        synchronized(buffer) { buffer.toList().takeLast(n) }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    fun currentFile(): String = logFile?.absolutePath ?: "<未初始化>"
}