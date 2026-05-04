package com.anthonycr.grant

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Drop-in stub for com.anthonycr.grant.PermissionsManager.
 * Uses AndroidX ActivityCompat under the hood.
 * Mirrors the getInstance() / requestPermissionsIfNecessaryForResult() /
 * hasPermission() / notifyPermissionsChange() API of the original library.
 */
class PermissionsManager private constructor() {

    // Pending actions waiting for permission results, keyed by requestCode
    private val pending = mutableMapOf<Int, Pair<Array<String>, PermissionsResultAction>>()
    private var nextCode = 1000

    fun hasPermission(activity: Activity, permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) ==
                PackageManager.PERMISSION_GRANTED

    fun requestPermissionsIfNecessaryForResult(
        activity: Activity,
        permissions: Array<String>,
        action: PermissionsResultAction?
    ) {
        if (action == null) return
        val missing = permissions.filter { !hasPermission(activity, it) }
        if (missing.isEmpty()) {
            action.onGranted()
            return
        }
        val code = nextCode++
        pending[code] = Pair(permissions, action)
        ActivityCompat.requestPermissions(activity, missing.toTypedArray(), code)
    }

    /** Call this from Activity.onRequestPermissionsResult */
    fun notifyPermissionsChange(
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val (requestedPerms, action) = entry.value
            val allGranted = requestedPerms.all { perm ->
                val idx = permissions.indexOf(perm)
                idx >= 0 && grantResults[idx] == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                action.onGranted()
            } else {
                val denied = requestedPerms.firstOrNull { perm ->
                    val idx = permissions.indexOf(perm)
                    idx < 0 || grantResults[idx] != PackageManager.PERMISSION_GRANTED
                } ?: ""
                action.onDenied(denied)
            }
            iter.remove()
        }
    }

    companion object {
        @Volatile private var INSTANCE: PermissionsManager? = null
        @JvmStatic fun getInstance(): PermissionsManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PermissionsManager().also { INSTANCE = it }
            }
    }
}
