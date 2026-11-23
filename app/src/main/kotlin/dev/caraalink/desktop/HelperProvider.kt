package dev.caraalink.desktop

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Where the car-apps helper ([ShellMain]) hands CarLink its binder. Only the shell
 * or root user may call it: that's who the helper runs as.
 */
class HelperProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) return null
        if (method != METHOD_ATTACH) return null
        val helper = extras?.getBinder(KEY_HELPER) ?: return null
        Desktop.onHelperAttached(helper)
        return Bundle().apply { putBinder(KEY_TOKEN, Desktop.appToken) }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val METHOD_ATTACH = "attach"
        const val KEY_HELPER = "helper"
        const val KEY_TOKEN = "token"
    }
}
