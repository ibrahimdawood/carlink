package dev.caraalink.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.caraalink.BuildConfig
import rikka.shizuku.Shizuku

/**
 * Full-screen apps on the car ("car apps"): the app side of the Shizuku helper.
 *
 * Shizuku has to be installed and running, and CarLink allowed in it. Then the
 * helper ([ShellDisplayService]) is started on demand and kept while a car
 * session uses it.
 */
object Desktop {

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val TAG = "Desktop"
    private const val PERMISSION_REQUEST = 41

    private val main = Handler(Looper.getMainLooper())

    @Volatile var shell: IShellDisplay? = null
        private set
    private var connection: ServiceConnection? = null
    private val waiting = mutableListOf<(IShellDisplay?) -> Unit>()

    fun status(context: Context): Status {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) {
            val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
            return if (installed) Status.NOT_RUNNING else Status.NOT_INSTALLED
        }
        if (Shizuku.isPreV11()) return Status.NOT_RUNNING
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) Status.READY else Status.NO_PERMISSION
    }

    /** The one step that gets [status] closer to READY, run from a screen the driver is looking at. */
    fun fixNextStep(context: Context) {
        when (status(context)) {
            Status.NOT_INSTALLED -> context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Status.NOT_RUNNING -> context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { context.startActivity(it) }
            Status.NO_PERMISSION -> runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
            Status.READY -> Unit
        }
    }

    /**
     * Hands the helper to [onReady] on the main thread, starting it if needed; null when it
     * can't start. Every caller gets an answer within [CONNECT_TIMEOUT_MS].
     */
    fun connect(context: Context, log: (String) -> Unit = {}, onReady: (IShellDisplay?) -> Unit) = main.post {
        shell?.let { if (it.asBinder().isBinderAlive) { onReady(it); return@post } }
        shell = null
        if (status(context) != Status.READY) {
            onReady(null)
            return@post
        }
        waiting += onReady
        main.postDelayed({
            if (waiting.remove(onReady)) {
                log("helper didn't answer in ${CONNECT_TIMEOUT_MS / 1000} s (Shizuku ${shizukuInfo()})")
                // Next time start it from scratch instead of waiting on this bind forever.
                unbind(context)
                collectShellLog(context, log)
                onReady(null)
            }
        }, CONNECT_TIMEOUT_MS)
        if (connection != null) return@post
        log("starting helper (Shizuku ${shizukuInfo()})")
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val s = binder?.takeIf { it.pingBinder() }?.let { IShellDisplay.Stub.asInterface(it) }
                log("helper ${if (s != null) "connected" else "not usable"}")
                shell = s
                if (s == null) connection = null
                finish(s)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                log("helper gone")
                shell = null
                connection = null
            }
        }
        connection = conn
        try {
            Shizuku.bindUserService(args(context), conn)
        } catch (e: Exception) {
            log("bindUserService failed: $e")
            connection = null
            finish(null)
        }
    }

    private fun unbind(context: Context) {
        val conn = connection ?: return
        connection = null
        runCatching { Shizuku.unbindUserService(args(context), conn, true) }
    }

    private fun shizukuInfo(): String = runCatching {
        "v${Shizuku.getVersion()}, uid ${Shizuku.getUid()}, server patch ${Shizuku.getServerPatchVersion()}"
    }.getOrElse { "unknown: $it" }

    /**
     * The helper runs as another user, so its errors never reach CarLink's own log. Read them
     * through Shizuku (as the shell user, which can see every app's log) and keep them.
     */
    private fun collectShellLog(context: Context, log: (String) -> Unit) {
        Thread({
            val text = runCatching {
                val newProcess = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
                ).apply { isAccessible = true }
                val p = newProcess.invoke(null, arrayOf("logcat", "-d", "-t", "1500", "-v", "time"), null, null) as Process
                val out = p.inputStream.bufferedReader().readLines()
                p.waitFor()
                out.filter { line ->
                    SHELL_LOG_HINTS.any { line.contains(it, ignoreCase = true) }
                }.takeLast(120).joinToString("\n")
            }.getOrElse { "couldn't read the helper's log: $it" }
            log("helper log via Shizuku:\n${text.ifBlank { "(nothing about the helper)" }}")
        }, "Desktop-shell-log").start()
    }

    private val SHELL_LOG_HINTS = listOf(
        "cardisplay", "CarLinkShell", "ShellDisplay", "UserService", "ServiceStarter", "shizuku",
        "AndroidRuntime", "FATAL", "carlink",
    )
    private const val CONNECT_TIMEOUT_MS = 15_000L

    private fun finish(s: IShellDisplay?) {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(s) }
    }

    private fun args(context: Context) = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellDisplayService::class.java.name))
        .daemon(false)
        .processNameSuffix("cardisplay")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    /** Opens an app on the car display; off the main thread, since `am start` takes a moment. */
    fun launch(component: String, onError: (String) -> Unit = {}) {
        val s = shell ?: return onError("not connected")
        Thread({
            val err = runCatching { s.start(component) }.getOrElse { it.toString() }
            if (err != null) {
                Log.w(TAG, "launch $component: $err")
                main.post { onError(err) }
            }
        }, "Desktop-launch").start()
    }
}
