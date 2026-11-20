package com.boaom.huamiextractor

import android.content.Context
import android.os.RemoteException
import androidx.annotation.Keep
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 以 root 身份运行的 shell 服务（Shizuku UserService）。
 *
 * 官方契约（Shizuku 13.x 实测源码确认）：
 *  - 必须是 IBinder（AIDL Stub），**不是** android.app.Service
 *  - 由 Shizuku 用 app_process 以 uid 0 反射实例化，**不需要 manifest 声明**
 *  - 构造函数用 () 或 (Context)；带 Context 的那个要 @Keep 防止混淆
 *  - destroy()/exit() 是服务端保留方法
 */
class ShellService : IShellService.Stub() {

    @Keep
    constructor(context: Context) : this()

    override fun exec(cmd: String): String {
        try {
            return run(cmd)
        } catch (e: Exception) {
            throw RemoteException(e.message ?: e.toString())
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        System.exit(0)
    }

    /** 执行命令，返回 stdout；退出码非 0 视为错误 */
    private fun run(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(false).start()
        val errBuf = StringBuilder()
        val errThread = Thread {
            try {
                p.errorStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (errBuf.isNotEmpty()) errBuf.append('\n')
                        errBuf.append(line)
                    }
                }
            } catch (_: Exception) { }
        }
        errThread.isDaemon = true
        errThread.start()

        val stdout = drain(p.inputStream)
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroy()
            throw IllegalStateException("命令超时(120s)")
        }
        errThread.join(1000)
        if (p.exitValue() != 0) {
            throw IllegalStateException("命令失败(${p.exitValue()}): ${errBuf.toString().take(400)}")
        }
        return stdout
    }

    private fun drain(stream: InputStream): String {
        val sb = StringBuilder()
        stream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        return sb.toString()
    }
}
