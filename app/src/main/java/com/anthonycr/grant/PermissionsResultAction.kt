package com.anthonycr.grant

/**
 * Drop-in stub for com.anthonycr.grant.PermissionsResultAction.
 * Mirrors the original abstract class API so existing code compiles unchanged.
 */
abstract class PermissionsResultAction {
    abstract fun onGranted()
    open fun onDenied(permission: String) {}
}
