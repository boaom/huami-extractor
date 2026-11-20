package com.boaom.huamiextractor

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * Shizuku 借系统权限执行 shell。
 * 手机已用 Magisk 给 Shizuku uid=0，因此这里读得到 Zepp 应用沙箱内的数据库。
 *
 * 注意：Shizuku.newProcess 返回的是 ShizukuRemoteProcess（不是 java.lang.Process），
 * 只能读 stdout/stderr，**不能往 stdin 写**（getOutputStream 是只读的退化实现）。
 * 因此所有命令都以 "sh -c" 形式一次性给出，不做交互。
 */
object ShizukuGate {

    const val REQUEST_CODE = 1001

    fun binderAlive(): Boolean = Shizuku.pingBinder()

    fun permissionGranted(): Boolean =
        binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** 请求授权；返回 true 表示已授权 */
    fun requestPermission(): Boolean {
        if (!binderAlive()) return false
        if (permissionGranted()) return true
        Shizuku.requestPermission(REQUEST_CODE)
        return false
    }

    /** 以 shell 执行一条命令，返回 stdout；失败抛异常。 */
    fun runShell(cmd: String): String {
        if (!permissionGranted()) throw IllegalStateException("Shizuku 未授权")
        val workDir = Context.getDir("work", Context.MODE_PRIVATE).absolutePath
        val process: ShizukuRemoteProcess =
            Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, workDir)
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException("命令失败($code): ${err.take(500)}")
        return out
    }

    fun deviceInfo(): String =
        runShell("id; getprop ro.product.model; getprop ro.build.version.release")
}
