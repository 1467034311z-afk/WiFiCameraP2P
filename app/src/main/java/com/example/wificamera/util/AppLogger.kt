package com.example.wificamera.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量文件日志：同时写 logcat 和应用私有目录下的 app_log.txt，
 * 方便每次迭代后在 App 内查看或导出。
 */
object AppLogger {
    private const val FILE_NAME = "app_log.txt"
    private const val MAX_BYTES = 512 * 1024L
    private var logFile: File? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(context.getExternalFilesDir(null), FILE_NAME)
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D/$tag: $msg")
    }

    @Synchronized
    fun w(tag: String, msg: String, e: Throwable? = null) {
        Log.w(tag, msg, e)
        write("W/$tag: $msg${e?.let { " | " + it.javaClass.simpleName + ": " + it.message } ?: ""}")
    }

    @Synchronized
    fun e(tag: String, msg: String, e: Throwable? = null) {
        Log.e(tag, msg, e)
        write("E/$tag: $msg${e?.let { " | " + it.javaClass.simpleName + ": " + it.message } ?: ""}")
    }

    private fun write(line: String) {
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                f.delete()
            }
            f.appendText(timeFmt.format(Date()) + " " + line + "\n")
        } catch (_: Exception) { }
    }

    fun read(): String {
        val f = logFile ?: return "日志文件不存在"
        return try { f.readText() } catch (e: Exception) { "读取失败：${e.message}" }
    }

    fun file(): File? = logFile

    @Synchronized
    fun clear() {
        runCatching { logFile?.delete() }
    }
}
