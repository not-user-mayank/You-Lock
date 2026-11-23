package com.example.applock

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UninstallAdminReceiver : DeviceAdminReceiver()

class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(ALWAYS_MONITOR_KEY, false) ||
            readLockedPackages(preferences).isEmpty()
        ) return
        preferences.edit().putBoolean(MONITOR_ENABLED_KEY, true).apply()
        val serviceIntent = Intent(context, ForegroundMonitorService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
