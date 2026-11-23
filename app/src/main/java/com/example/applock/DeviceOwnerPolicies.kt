package com.example.applock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/** Applies Android's device-wide uninstall restriction. */
internal fun setUninstallRestriction(context: Context, enabled: Boolean): Boolean {
    val policyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    if (!policyManager.isDeviceOwnerApp(context.packageName)) return false

    val admin = ComponentName(context, UninstallAdminReceiver::class.java)
    return runCatching {
        if (enabled) {
            policyManager.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
        } else {
            policyManager.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
        }
    }.isSuccess
}
