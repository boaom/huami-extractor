package com.boaom.huamiextractor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * Shizuku 借系统权限执行 root shell。
 * 手机已用 Magisk 给 Shizuku uid=0，因此这里读得到 Zepp 应用沙箱内的数据库。
 */
object ShizukuGate {

    const val REQUEST_CODE = 1001

    fun binderAlive(): Boolean = Shizuku.pingBinder()

    fun permissionGranted(): Boolean =
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission(): Boolean {
        if (!binderAlive()) return false
        if (permissionGranted()) return true
        Shizuku.requestPermission(REQUEST_CODE)
        return false
    }

    /** 以 shell 执行一条命令，返回 stdout；失败抛异常。 */
    fun runShell(cmd: String): String {
        if (!permissionGranted()) throw IllegalStateException("Shizuku 未授权")
        val process = Shizuku.newProcess(
            arrayOf("sh", "-c", cmd),
            null,
            Context.getDir("work", Context.MODE_PRIVATE).absolutePath
        )
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException("命令失败($code): ${err.take(400)}")
        return out
    }

    fun deviceInfo(): String = runShell("id; getprop ro.product.model; getprop ro.build.version.release")
}
