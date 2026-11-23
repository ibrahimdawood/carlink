package dev.caraalink.desktop

import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import kotlin.system.exitProcess

/**
 * Entry point of the car-apps helper when CarLink starts it itself:
 * `app_process` as the shell user (through Shizuku), the way scrcpy starts.
 *
 * Shizuku's own service starter crashes on some phones (a Xiaomi build threw in
 * LoadedApk.makeApplication before any CarLink code ran), so this avoids it: no
 * Application object is made, just the system context the display service needs.
 * The helper hands its binder to CarLink through CarLink's [HelperProvider] and
 * exits when CarLink goes away.
 */
object ShellMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0) ?: run {
            System.err.println("usage: ShellMain <package>")
            exitProcess(2)
        }
        try {
            @Suppress("DEPRECATION")
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val context = at.getMethod("getSystemContext").invoke(thread) as Context
            val service = ShellDisplayService(context, pkg)
            val token = attach(pkg, service) ?: run {
                System.err.println("CarLink didn't accept the helper")
                exitProcess(3)
            }
            token.linkToDeath({ exitProcess(0) }, 0)
            println("helper attached")
            Looper.loop()
        } catch (t: Throwable) {
            t.printStackTrace()
            exitProcess(1)
        }
    }

    /** Calls CarLink's provider as the shell user, handing over [service]; returns CarLink's token. */
    private fun attach(pkg: String, service: IBinder): IBinder? {
        val authority = "$pkg.helper"
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = am.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java,
        ).invoke(am, authority, 0, token, "CarLink") ?: error("provider $authority not found")
        val provider = holder.javaClass.getDeclaredField("provider").apply { isAccessible = true }.get(holder)!!
        val extras = Bundle().apply { putBinder(HelperProvider.KEY_HELPER, service) }
        try {
            val s = String::class.java
            val b = Bundle::class.java
            val result = when {
                Build.VERSION.SDK_INT >= 31 -> provider.javaClass
                    .getMethod("call", AttributionSource::class.java, s, s, s, b)
                    .invoke(
                        provider,
                        AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build(),
                        authority, HelperProvider.METHOD_ATTACH, null, extras,
                    )
                Build.VERSION.SDK_INT == 30 -> provider.javaClass
                    .getMethod("call", s, s, s, s, s, b)
                    .invoke(provider, SHELL_PACKAGE, null, authority, HelperProvider.METHOD_ATTACH, null, extras)
                else -> provider.javaClass
                    .getMethod("call", s, s, s, s, b)
                    .invoke(provider, SHELL_PACKAGE, authority, HelperProvider.METHOD_ATTACH, null, extras)
            } as Bundle?
            return result?.getBinder(HelperProvider.KEY_TOKEN)
        } finally {
            runCatching {
                am.javaClass.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                    .invoke(am, authority, token)
            }
        }
    }

    private const val SHELL_PACKAGE = "com.android.shell"
}
