package dev.caraalink.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
        if (helperProcess != null) return@post
        log("starting helper (Shizuku ${shizukuInfo()})")
        bindStartedAt = System.currentTimeMillis()
        helperLog = log
        startHelper(context, log)
    }

    /** CarLink's side of the helper's life: while this lives, the helper does too. */
    val appToken: IBinder = android.os.Binder()

    private var bindStartedAt = 0L
    @Volatile private var helperProcess: Process? = null
    @Volatile private var helperLog: (String) -> Unit = {}

    /**
     * Starts [ShellMain] with app_process through Shizuku (the shell user), the way scrcpy
     * starts. Shizuku's own service starter crashed on a Xiaomi build, so it isn't used.
     */
    private fun startHelper(context: Context, log: (String) -> Unit) {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' exec /system/bin/app_process /system/bin " +
            "--nice-name=${context.packageName}:cardisplay ${ShellMain::class.java.name} ${context.packageName}"
        val p = try {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        } catch (e: Exception) {
            log("couldn't start helper: ${e.cause ?: e}")
            finish(null)
            return
        }
        helperProcess = p
        // Everything the helper prints (its errors included) goes to CarLink's log.
        for ((name, stream) in listOf("out" to p.inputStream, "err" to p.errorStream)) {
            Thread({
                runCatching { stream.bufferedReader().forEachLine { log("helper $name: $it") } }
            }, "Desktop-helper-$name").start()
        }
        Thread({
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            main.post {
                if (helperProcess === p) {
                    helperProcess = null
                    shell = null
                    log("helper exited ($code)")
                    finish(null)
                }
            }
        }, "Desktop-helper-wait").start()
    }

    /** From [HelperProvider], on a binder thread. */
    internal fun onHelperAttached(binder: IBinder) = main.post {
        val s = IShellDisplay.Stub.asInterface(binder)
        runCatching { binder.linkToDeath({ main.post { if (shell === s) shell = null } }, 0) }
        shell = s
        helperLog("helper connected")
        finish(s)
    }

    private fun unbind(@Suppress("UNUSED_PARAMETER") context: Context) {
        runCatching { shell?.destroy() }
        shell = null
        helperProcess?.let { runCatching { it.destroy() } }
        helperProcess = null
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
                // Everything since the bind started (a busy phone logs thousands of lines a minute).
                val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date((bindStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis()) - 2_000))
                val p = newProcess.invoke(null, arrayOf("logcat", "-d", "-v", "time", "-T", since), null, null) as Process
                val out = p.inputStream.bufferedReader().readLines()
                p.waitFor()
                out.filter { line ->
                    SHELL_LOG_HINTS.any { line.contains(it, ignoreCase = true) } &&
                        SHELL_LOG_NOISE.none { line.contains(it, ignoreCase = true) }
                }.takeLast(200).joinToString("\n")
            }.getOrElse { "couldn't read the helper's log: $it" }
            log("helper log via Shizuku:\n${text.ifBlank { "(nothing about the helper)" }}")
        }, "Desktop-shell-log").start()
    }

    private val SHELL_LOG_HINTS = listOf(
        "cardisplay", "CarLinkShell", "ShellDisplay", "UserService", "Starter", "shizuku",
        "AndroidRuntime", "FATAL", "app_process", "dev.caraalink", "ClassNotFound", "NoSuchMethod",
    )
    private val SHELL_LOG_NOISE = listOf("gralloc", "BufferQueue", "queueBuffer", "SMPTE")
    private const val CONNECT_TIMEOUT_MS = 15_000L

    private fun finish(s: IShellDisplay?) {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(s) }
    }

    /**
     * Settings' "test" without a car: start the helper and have it make a small display.
     * [onResult] gets a one-line answer on the main thread; details go to the log.
     */
    fun selfTest(context: Context, log: (String) -> Unit, onResult: (ok: Boolean, text: String) -> Unit) {
        connect(context, log) { s ->
            if (s == null) {
                onResult(false, "helper didn't start")
                return@connect
            }
            val reader = android.media.ImageReader.newInstance(640, 360, android.graphics.PixelFormat.RGBA_8888, 2)
            val id = runCatching { s.create(reader.surface, 640, 360, 160) }.getOrElse { log("test display: $it"); -1 }
            runCatching { s.release() }
            reader.close()
            if (id < 0) onResult(false, "helper started, display failed") else onResult(true, "helper and display OK ($id)")
        }
    }

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
