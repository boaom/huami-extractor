package com.boaom.huamiextractor

import android.content.Context
import android.os.Binder
import android.os.Parcel
import androidx.annotation.Keep
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 以 root 身份运行的 shell 服务（Shizuku UserService）。
 *
 * 官方契约（Shizuku 13.x 源码实测确认）：
 *  - 必须是 IBinder，**不是** android.app.Service
 *  - 由 Shizuku 用 app_process 以 uid 0 反射实例化，**不需要 manifest 声明**
 *  - 构造函数用 () 或 (Context)；带 Context 的优先，失败再退回无参
 *  - 服务端用事务码 16777115 调用 destroy() 来收尾
 *
 * 这里手写 Binder + onTransact，不依赖 AIDL 代码生成：
 * AIDL 生成的 Stub 构造签名随工具链变化，手写更可控。
 *
 * ⚠ Kotlin 坑：类若只有次构造函数，次构造里的 `this()` 委托是非法的
 *   （报 "Supertype initialization is impossible without primary constructor"），
 *   所以必须显式写出无参主构造函数。
 *
 * IPC 协议（两端都是我们自己，私有协议）：
 *   请求: writeString(cmd)
 *   应答: writeNoException(); writeString(error); writeString(stdout)
 */
class ShellService() : Binder() {

    @Keep
    constructor(context: Context) : this()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_EXEC -> {
                try {
                    val cmd = data.readString() ?: ""
                    val out = run(cmd)
                    reply?.writeNoException()
                    reply?.writeString(null)
                    reply?.writeString(out)
                } catch (e: Exception) {
                    reply?.writeNoException()
                    reply?.writeString(e.message ?: e.toString())
                    reply?.writeString("")
                }
                true
            }
            // Shizuku 服务端保留事务码：销毁用户服务
            16777115 -> {
                System.exit(0)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
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

    companion object {
        const val TRANSACTION_EXEC = 1
    }
}
