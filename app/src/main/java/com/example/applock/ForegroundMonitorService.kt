package com.example.applock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color as AndroidColor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ACTION_BIOMETRIC_BEGIN = "com.example.applock.BIOMETRIC_BEGIN"
internal const val ACTION_BIOMETRIC_SUCCEEDED = "com.example.applock.BIOMETRIC_SUCCEEDED"
internal const val ACTION_BIOMETRIC_CANCELLED = "com.example.applock.BIOMETRIC_CANCELLED"
internal const val ACTION_ENABLE_CAMERA_CAPTURE = "com.example.applock.ENABLE_CAMERA_CAPTURE"
internal const val ACTION_DISABLE_CAMERA_CAPTURE = "com.example.applock.DISABLE_CAMERA_CAPTURE"
internal const val ACTION_CAPTURE_FAILED_ATTEMPT = "com.example.applock.CAPTURE_FAILED_ATTEMPT"
internal const val EXTRA_LOCKED_PACKAGE = "locked_package"

class ForegroundMonitorService : Service() {
    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_CHANNEL_ID = "still_app_monitor"
        private const val NOTIFICATION_ID = 41
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var windowManager: WindowManager
    private var monitorJob: Job? = null
    private var currentPackageName: String? = null
    private var unlockedPackageName: String? = null
    private var biometricPendingPackage: String? = null
    private var biometricUnlockOnReturnPackage: String? = null
    private var overlayPackageName: String? = null
    private var overlayView: FrameLayout? = null

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE_CAMERA_CAPTURE -> {
                startAsForegroundService(cameraCapture = true)
            }
            ACTION_DISABLE_CAMERA_CAPTURE -> {
                startAsForegroundService(cameraCapture = false)
                return START_STICKY
            }
            ACTION_CAPTURE_FAILED_ATTEMPT -> {
                startAsForegroundService(cameraCapture = true)
                AttemptCameraCapture.capture(this) { file ->
                    if (file == null) {
                        preferences.edit().putBoolean(CAPTURED_FOR_SESSION_KEY, false).apply()
                    }
                    if (!preferences.getBoolean(MONITOR_ENABLED_KEY, false)) stopSelf(startId)
                }
                return START_STICKY
            }
            ACTION_BIOMETRIC_BEGIN -> {
                biometricPendingPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
                removeLockOverlay()
                return START_STICKY
            }
            ACTION_BIOMETRIC_SUCCEEDED -> {
                val lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
                if (lockedPackage == currentPackageName) {
                    unlockedPackageName = lockedPackage
                    biometricUnlockOnReturnPackage = null
                } else {
                    biometricUnlockOnReturnPackage = lockedPackage
                }
                biometricPendingPackage = null
                return START_STICKY
            }
            ACTION_BIOMETRIC_CANCELLED -> {
                biometricPendingPackage = null
                return START_STICKY
            }
        }

        if (intent == null && !preferences.getBoolean(MONITOR_ENABLED_KEY, false)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForegroundService(cameraCapture = intent?.action == ACTION_ENABLE_CAMERA_CAPTURE)
        preferences.edit().putBoolean(MONITOR_ENABLED_KEY, true).apply()
        isRunning = true

        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch { monitorSelectedApps() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        monitorJob?.cancel()
        serviceScope.cancel()
        removeLockOverlay()
        super.onDestroy()
    }

    private fun startAsForegroundService(cameraCapture: Boolean = false) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Still app lock is on")
            .setContentText(if (cameraCapture) "Monitoring apps; failed-attempt camera is enabled" else "Monitoring your selected apps")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (cameraCapture) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceTypes
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cameraCapture) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App lock monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Still is monitoring selected apps."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private suspend fun monitorSelectedApps() {
        while (serviceScope.isActive) {
            if (!preferences.getBoolean(MONITOR_ENABLED_KEY, false)) {
                stopSelf()
                return
            }

            val foregroundPackage = withContext(Dispatchers.IO) {
                readForegroundPackage()
            }
            if (foregroundPackage != null) {
                if (foregroundPackage != currentPackageName) {
                    currentPackageName = foregroundPackage
                    if (foregroundPackage == biometricUnlockOnReturnPackage) {
                        unlockedPackageName = foregroundPackage
                        biometricUnlockOnReturnPackage = null
                        biometricPendingPackage = null
                    } else {
                        if (biometricUnlockOnReturnPackage != null &&
                            foregroundPackage != packageName
                        ) {
                            biometricUnlockOnReturnPackage = null
                        }
                        if (biometricPendingPackage == null || foregroundPackage != packageName) {
                            unlockedPackageName = null
                        }
                    }
                    removeLockOverlay()
                }

                val selectedPackages = readLockedPackages(preferences)
                if (!Settings.canDrawOverlays(this@ForegroundMonitorService)) {
                    removeLockOverlay()
                } else if (foregroundPackage in selectedPackages &&
                    foregroundPackage != unlockedPackageName
                ) {
                    showLockOverlay(foregroundPackage)
                } else if (foregroundPackage !in selectedPackages) {
                    removeLockOverlay()
                }
            }
            delay(800)
        }
    }

    @Suppress("DEPRECATION")
    private fun readForegroundPackage(): String? {
        val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val events = usageStats.queryEvents(endTime - 15_000, endTime)
        val event = UsageEvents.Event()
        var mostRecentPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (isForegroundEvent) mostRecentPackage = event.packageName
        }
        return mostRecentPackage
    }

    private fun showLockOverlay(packageName: String) {
        if (overlayView != null && overlayPackageName == packageName) return
        removeLockOverlay()

        val packageLabel = getAppLabel(packageName)
        val unlockMode = UnlockMode.fromStoredValue(preferences.getString(UNLOCK_MODE_KEY, null))
        val root = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.argb(235, 23, 33, 29))
            isFocusableInTouchMode = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(30), dp(28), dp(26))
            background = GradientDrawable().apply {
                setColor(AndroidColor.WHITE)
                cornerRadius = dp(28).toFloat()
            }
        }

        val eyebrow = TextView(this).apply {
            text = "STILL  ·  APP LOCK"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(AndroidColor.rgb(23, 107, 80))
            gravity = Gravity.CENTER
        }
        val heading = TextView(this).apply {
            text = "${packageLabel} is locked"
            textSize = 24f
            setTextColor(AndroidColor.rgb(23, 33, 29))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
        }
        val instruction = TextView(this).apply {
            text = when (unlockMode) {
                UnlockMode.PIN -> "Enter your Still PIN to continue."
                UnlockMode.PATTERN -> "Draw your pattern to continue."
                UnlockMode.FINGERPRINT -> "Verify your fingerprint to continue."
            }
            textSize = 14f
            setTextColor(AndroidColor.rgb(127, 137, 132))
            gravity = Gravity.CENTER
        }
        val pinInput = EditText(this).apply {
            hint = "4-digit PIN"
            textSize = 20f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val errorText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(AndroidColor.rgb(176, 68, 62))
            setPadding(0, dp(4), 0, dp(8))
        }
        val unlockButton = Button(this).apply {
            text = "Unlock app"
            setTextColor(AndroidColor.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                AndroidColor.rgb(23, 107, 80)
            )
        }

        unlockButton.setOnClickListener {
            val enteredPin = pinInput.text.toString()
            when {
                enteredPin.length != 4 -> errorText.text = "Enter all 4 digits to continue."
                pinMatches(preferences, enteredPin) -> {
                    FailedAttemptPhotos.resetSession(this@ForegroundMonitorService)
                    unlockedPackageName = overlayPackageName
                    removeLockOverlay()
                }
                else -> {
                    errorText.text = "That PIN doesn’t match. Try again."
                    FailedAttemptPhotos.registerWrongPin(this)
                    pinInput.text.clear()
                }
            }
        }

        val pinSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pinInput, matchWidth().apply {
                topMargin = dp(20)
                bottomMargin = dp(8)
            })
            addView(errorText, matchWidth())
            addView(unlockButton, matchWidth().apply { topMargin = dp(8) })
        }

        var patternInput: PatternInputView? = null
        var patternErrorText: TextView? = null
        var fallbackButton: Button? = null
        var fingerprintButton: Button? = null
        if (unlockMode == UnlockMode.PATTERN) {
            patternInput = PatternInputView(this).apply {
                onPatternComplete = { pattern ->
                    when {
                        pattern.split(',').distinct().size < 4 -> patternErrorText?.text = "Use at least 4 dots."
                        secretMatches(preferences, PATTERN_HASH_KEY, PATTERN_SALT_KEY, pattern) -> {
                            FailedAttemptPhotos.resetSession(this@ForegroundMonitorService)
                            unlockedPackageName = packageName
                            removeLockOverlay()
                        }
                        else -> patternErrorText?.text = "That pattern doesn’t match. Try again."
                    }
                }
            }
            patternErrorText = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(AndroidColor.rgb(176, 68, 62))
            }
            fallbackButton = Button(this).apply {
                text = "Use PIN instead"
                setOnClickListener {
                    patternInput?.visibility = View.GONE
                    patternErrorText?.visibility = View.GONE
                    it.visibility = View.GONE
                    pinSection.visibility = View.VISIBLE
                    pinInput.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        } else if (unlockMode == UnlockMode.FINGERPRINT) {
            fingerprintButton = Button(this).apply {
                text = "Use fingerprint"
                setOnClickListener {
                    startService(
                        Intent(this@ForegroundMonitorService, ForegroundMonitorService::class.java)
                            .setAction(ACTION_BIOMETRIC_BEGIN)
                            .putExtra(EXTRA_LOCKED_PACKAGE, packageName)
                    )
                    removeLockOverlay()
                    startActivity(
                        Intent(this@ForegroundMonitorService, BiometricAuthActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(EXTRA_LOCKED_PACKAGE, packageName)
                    )
                }
            }
            fallbackButton = Button(this).apply {
                text = "Use PIN instead"
                setOnClickListener {
                    it.visibility = View.GONE
                    fingerprintButton?.visibility = View.GONE
                    pinSection.visibility = View.VISIBLE
                    pinInput.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        card.addView(eyebrow, matchWidth())
        card.addView(heading, matchWidth())
        card.addView(instruction, matchWidth())
        patternInput?.let { input ->
            card.addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(250)
            ))
            patternErrorText?.let { card.addView(it, matchWidth()) }
        }
        fingerprintButton?.let { card.addView(it, matchWidth()) }
        fallbackButton?.let { card.addView(it, matchWidth()) }
        pinSection.visibility = if (unlockMode == UnlockMode.PIN) View.VISIBLE else View.GONE
        card.addView(pinSection, matchWidth())
        root.addView(
            card,
            FrameLayout.LayoutParams(dp(360), LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.28f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        try {
            windowManager.addView(root, overlayParams)
            overlayView = root
            overlayPackageName = packageName
            if (unlockMode == UnlockMode.PIN) {
                root.requestFocus()
                pinInput.requestFocus()
                pinInput.postDelayed({
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
                }, 250)
            }
        } catch (_: SecurityException) {
            overlayView = null
            overlayPackageName = null
        } catch (_: WindowManager.BadTokenException) {
            overlayView = null
            overlayPackageName = null
        }
    }

    private fun removeLockOverlay() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // The window can already be detached when Android revokes overlay access.
        }
        overlayView = null
        overlayPackageName = null
    }

    private fun getAppLabel(packageName: String): String = runCatching {
        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(appInfo).toString()
    }.getOrDefault(packageName)

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
