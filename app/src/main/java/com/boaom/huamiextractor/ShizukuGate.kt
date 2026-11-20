package com.boaom.huamiextractor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 借系统权限执行 root shell。
 *
 * 关键事实（Shizuku 13.x 源码实测，勿凭旧文档改）：
 *  - 手机上跑的是 Shizuku 13.6.0
 *  - **13.1.5 起 `Shizuku.newProcess()` 被收成 private**，只剩 transactRemote / bindUserService 等公开入口
 *  - 因此走官方路径：**UserService** —— Shizuku 用 app_process 以 root(uid 0) 反射实例化
 *    我们自己的 Binder（ShellService），命令在那个 root 进程里执行
 *  - UserServiceArgs 的 **processNameSuffix 必填**，否则 forAdd() 直接 NPE
 *  - 手机已用 Magisk 给 Shizuku uid=0，所以 UserService 拿到的是真 root
 */
object ShizukuGate {

    const val REQUEST_CODE = 1001

    private var appContext: Context? = null
    @Volatile private var remote: IBinder? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

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

    /** 以 root 身份执行一条命令，返回 stdout；失败抛异常。 */
    fun runShell(cmd: String): String {
        if (!permissionGranted()) throw IllegalStateException("Shizuku 未授权")
        val binder = ensureUserService()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeString(cmd)
            if (!binder.transact(ShellService.TRANSACTION_EXEC, data, reply, 0)) {
                throw IllegalStateException("root 执行失败")
            }
            reply.readException()
            val err = reply.readString()
            val out = reply.readString() ?: ""
            if (!err.isNullOrEmpty()) throw IllegalStateException("命令出错: ${err.take(500)}")
            return out
        } catch (e: RemoteException) {
            throw IllegalStateException("IPC 失败: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun deviceInfo(): String =
        runShell("id; getprop ro.product.model; getprop ro.build.version.release")

    /** 确保 UserService（root 进程）已连上 */
    private fun ensureUserService(): IBinder {
        remote?.let { return it }
        val ctx = appContext ?: throw IllegalStateException("ShizukuGate 未初始化")
        val latch = CountDownLatch(1)
        var failure: Exception? = null

        val args = Shizuku.UserServiceArgs(
            ComponentName(ctx, ShellService::class.java)
        )
            .daemon(false)
            .processNameSuffix("huami_shell")
            .tag("huami-extractor")
            .version(1)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    failure = IllegalStateException("服务返回空 binder")
                } else {
                    remote = service
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
                latch.countDown()
            }
        }

        serviceArgs = args
        Shizuku.bindUserService(args, conn)
        if (!latch.await(25, TimeUnit.SECONDS)) {
            throw IllegalStateException("UserService 连接超时。确认 Shizuku 已授权，且 Shizuku 管理器已给本应用权限")
        }
        failure?.let { throw it }
        return remote ?: throw IllegalStateException("UserService 未连接")
    }
}
