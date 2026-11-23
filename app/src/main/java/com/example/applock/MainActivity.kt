package com.example.applock

import android.Manifest
import android.app.AppOpsManager
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom

private val Ink = Color(0xFF17211D)
private val Muted = Color(0xFF7F8984)
private val Green = Color(0xFF176B50)
private val Canvas = Color(0xFFF5F7F4)
internal const val SETTINGS_FILE = "still_settings"
internal const val PIN_HASH_KEY = "pin_hash"
internal const val PIN_SALT_KEY = "pin_salt"
internal const val LOCKED_PACKAGES_KEY = "locked_packages"
internal const val MONITOR_ENABLED_KEY = "monitor_enabled"
internal const val UNLOCK_MODE_KEY = "unlock_mode"
internal const val PATTERN_HASH_KEY = "pattern_hash"
internal const val PATTERN_SALT_KEY = "pattern_salt"
internal const val SELF_LOCK_KEY = "self_lock_enabled"
internal const val ALWAYS_MONITOR_KEY = "always_monitor"
internal const val UNINSTALL_PROTECTION_KEY = "uninstall_protection"
internal const val BLOCK_ALL_APP_UNINSTALL_KEY = "block_all_app_uninstall"
internal const val FAILED_ATTEMPT_THRESHOLD_KEY = "failed_attempt_threshold"
internal const val CAMERA_CAPTURE_ENABLED_KEY = "camera_capture_enabled"
internal const val FAILED_ATTEMPTS_KEY = "failed_attempts"
internal const val CAPTURED_FOR_SESSION_KEY = "captured_for_session"
internal const val RECOVERY_QUESTION_KEY = "recovery_question"
internal const val RECOVERY_HASH_KEY = "recovery_hash"
internal const val RECOVERY_SALT_KEY = "recovery_salt"

internal enum class UnlockMode(val label: String) {
    PIN("PIN"),
    PATTERN("Pattern"),
    FINGERPRINT("Fingerprint");

    companion object {
        fun fromStoredValue(value: String?): UnlockMode =
            entries.firstOrNull { it.name == value } ?: PIN
    }
}

private data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?
)

private data class DetectedApp(
    val name: String,
    val packageName: String
)

private enum class ChangePinStep {
    CURRENT,
    NEW,
    CONFIRM
}

class MainActivity : FragmentActivity() {
    private val permissionCallbacks = mutableMapOf<Int, (Boolean) -> Unit>()
    private var nextPermissionRequestCode = 0x1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Canvas
                ) {
                    AppLockHome()
                }
            }
        }
    }

    fun requestRuntimePermission(permission: String, onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }

        val requestCode = nextPermissionRequestCode++
        permissionCallbacks[requestCode] = onResult
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionCallbacks.remove(requestCode)?.invoke(
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        )
    }
}

@Composable
private fun AppLockHome() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    }
    var pin by remember { mutableStateOf("") }
    var unlocked by remember(preferences) {
        mutableStateOf(!preferences.getBoolean(SELF_LOCK_KEY, true))
    }
    var message by remember { mutableStateOf("") }
    var pinConfigured by remember(preferences) { mutableStateOf(hasSavedPin(preferences)) }
    var unlockMode by remember(preferences) {
        mutableStateOf(UnlockMode.fromStoredValue(preferences.getString(UNLOCK_MODE_KEY, null)))
    }
    var usePinFallback by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var showRecoverySetup by remember { mutableStateOf(false) }
    var lockedPackages by remember(preferences) {
        mutableStateOf(readLockedPackages(preferences))
    }

    if (!pinConfigured) {
        PinSetupScreen { newPin ->
            savePin(preferences, newPin)
            pinConfigured = true
            unlocked = true
            if (preferences.getString(RECOVERY_QUESTION_KEY, null).isNullOrBlank()) {
                showRecoverySetup = true
            }
        }
    } else if (unlocked) {
        InstalledAppsScreen(
            lockedPackages = lockedPackages,
            unlockMode = unlockMode,
            preferences = preferences,
            onRecoverySetup = { showRecoverySetup = true },
            onLockChange = { packageName, shouldLock ->
                val updatedPackages = if (shouldLock) {
                    lockedPackages + packageName
                } else {
                    lockedPackages - packageName
                }
                lockedPackages = updatedPackages
                preferences.edit()
                    .putStringSet(LOCKED_PACKAGES_KEY, updatedPackages.toSet())
                    .apply()
            },
            onUnlockModeChange = { mode ->
                unlockMode = mode
                preferences.edit().putString(UNLOCK_MODE_KEY, mode.name).apply()
            }
        )
    } else {
        val onUnlockSuccess = {
            unlocked = true
            message = ""
            usePinFallback = false
        }
        when {
            unlockMode == UnlockMode.PATTERN && !usePinFallback -> {
                PatternEntryScreen(
                    onPatternEntered = { pattern ->
                        if (secretMatches(preferences, PATTERN_HASH_KEY, PATTERN_SALT_KEY, pattern)) {
                            FailedAttemptPhotos.resetSession(context)
                            onUnlockSuccess()
                        } else {
                            FailedAttemptPhotos.registerWrongPin(context)
                            message = "That pattern doesn’t match. Try again."
                        }
                    },
                    message = message,
                    onUsePin = { usePinFallback = true }
                )
            }
            unlockMode == UnlockMode.FINGERPRINT && !usePinFallback -> {
                FingerprintEntryScreen(
                    onAuthenticated = onUnlockSuccess,
                    onUsePin = { usePinFallback = true }
                )
            }
            else -> {
                PinEntryScreen(
                    pin = pin,
                    message = message,
                    onForgotPin = { showRecoveryDialog = true },
                    onPinChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) {
                            pin = value
                            message = ""
                        }
                    },
                    onUnlock = {
                        if (pinMatches(preferences, pin)) {
                            FailedAttemptPhotos.resetSession(context)
                            onUnlockSuccess()
                        } else {
                            if (pin.length == 4) FailedAttemptPhotos.registerWrongPin(context)
                            message = if (pin.length < 4) {
                                "Enter all 4 digits to continue."
                            } else {
                                "That PIN doesn’t match. Try again."
                            }
                        }
                    }
                )
            }
        }
    }

    if (showRecoveryDialog) {
        RecoveryResetDialog(
            preferences = preferences,
            onDismiss = { showRecoveryDialog = false },
            onPinReset = { newPin ->
                savePin(preferences, newPin)
                preferences.edit().putString(UNLOCK_MODE_KEY, UnlockMode.PIN.name).apply()
                unlockMode = UnlockMode.PIN
                pin = ""
                showRecoveryDialog = false
                unlocked = true
            }
        )
    }
    if (showRecoverySetup) {
        RecoverySetupDialog(
            preferences = preferences,
            onDismiss = { showRecoverySetup = false },
            onSaved = { showRecoverySetup = false }
        )
    }
}

@Composable
private fun PatternEntryScreen(
    message: String,
    onPatternEntered: (String) -> Unit,
    onUsePin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()
        Spacer(Modifier.weight(1f))
        Text("Draw your pattern", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Connect at least 4 dots to unlock.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        AndroidView(
            factory = { context -> PatternInputView(context).apply { onPatternComplete = onPatternEntered } },
            update = { it.onPatternComplete = onPatternEntered },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )
        Text(message.ifEmpty { "Your pattern stays on this device." }, color = if (message.isEmpty()) Muted else Color(0xFFB0443E), fontSize = 12.sp)
        TextButton(onClick = onUsePin) { Text("Use PIN instead", color = Green) }
        Spacer(Modifier.weight(1f))
        Footer()
    }
}

@Composable
private fun FingerprintEntryScreen(
    onAuthenticated: () -> Unit,
    onUsePin: () -> Unit
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("Use your enrolled fingerprint to continue.") }
    val biometricManager = remember(context) { BiometricManager.from(context) }
    val biometricAvailable = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(Color(0xFFE5EFE9), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = Green, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(30.dp))
        Text("Unlock with fingerprint", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(message, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val activity = context as? FragmentActivity
                if (!biometricAvailable || activity == null) {
                    message = "Set up a fingerprint on this device, or use your PIN."
                } else {
                    val prompt = BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(activity),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                onAuthenticated()
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                message = errString.toString()
                            }
                        }
                    )
                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("Unlock Still")
                        .setSubtitle("Confirm your identity to continue")
                        .setNegativeButtonText("Use PIN instead")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .build()
                    prompt.authenticate(promptInfo)
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) {
            Text("Use fingerprint", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onUsePin) { Text("Use PIN instead", color = Green) }
        Spacer(Modifier.weight(1f))
        Footer()
    }
}

@Composable
private fun PinSetupScreen(onPinCreated: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    val isConfirming = firstPin != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(Color(0xFFE5EFE9), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = Green,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(30.dp))
        Text(
            if (isConfirming) "Confirm your PIN" else "Create a PIN",
            color = Ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.7).sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (isConfirming) "Enter the same 4 digits once more." else "Choose a 4-digit PIN for your private space.",
            color = Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(34.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                if (value.length <= 4 && value.all(Char::isDigit)) {
                    pin = value
                    message = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("4-digit PIN") },
            placeholder = { Text("••••", letterSpacing = 8.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                focusedLabelColor = Green,
                cursorColor = Green
            )
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message.ifEmpty { "Your PIN is stored on this device." },
            color = if (message.isEmpty()) Muted else Color(0xFFB0443E),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (pin.length != 4) {
                    message = "Enter all 4 digits to continue."
                } else if (!isConfirming) {
                    firstPin = pin
                    pin = ""
                    message = ""
                } else if (pin == firstPin) {
                    onPinCreated(pin)
                } else {
                    firstPin = null
                    pin = ""
                    message = "Those PINs didn’t match. Start again."
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) {
            Text(
                if (isConfirming) "Confirm PIN" else "Continue",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.weight(1f))
        Footer()
    }
}

@Composable
private fun PinEntryScreen(
    pin: String,
    message: String,
    onForgotPin: () -> Unit,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandHeader()
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(Color(0xFFE5EFE9), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = Green,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(Modifier.height(30.dp))
        Text(
            "Welcome back",
            color = Ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.7).sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Enter your PIN to open your private space.",
            color = Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(34.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = onPinChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("4-digit PIN") },
            placeholder = { Text("••••", letterSpacing = 8.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                focusedLabelColor = Green,
                cursorColor = Green
            )
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message.ifEmpty { "Your PIN is only used on this device." },
            color = if (message.isEmpty()) Muted else Color(0xFFB0443E),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) {
            Text("Unlock", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onForgotPin) {
            Text("Forgot password?", color = Green)
        }
        Spacer(Modifier.weight(1f))
        Footer()
    }
}

@Composable
private fun InstalledAppsScreen(
    lockedPackages: Set<String>,
    unlockMode: UnlockMode,
    preferences: android.content.SharedPreferences,
    onRecoverySetup: () -> Unit,
    onLockChange: (String, Boolean) -> Unit,
    onUnlockModeChange: (UnlockMode) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var isLoading by remember { mutableStateOf(true) }
    var usageAccessGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var overlayAccessGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var monitorRunning by remember { mutableStateOf(ForegroundMonitorService.isRunning) }
    var cameraServicePromoted by remember { mutableStateOf(false) }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    var lastDetectedApp by remember { mutableStateOf<DetectedApp?>(null) }
    var showPatternSetup by remember { mutableStateOf(false) }
    var unlockModeMessage by remember { mutableStateOf("") }
    val fingerprintAvailable = remember(context) {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        isLoading = false
    }

    LaunchedEffect(context) {
        while (true) {
            val accessGranted = withContext(Dispatchers.IO) { hasUsageAccess(context) }
            usageAccessGranted = accessGranted
            overlayAccessGranted = Settings.canDrawOverlays(context)
            monitorRunning = ForegroundMonitorService.isRunning
            if (!monitorRunning) cameraServicePromoted = false
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
            if (accessGranted) {
                val detectedApp = withContext(Dispatchers.IO) {
                    findMostRecentForegroundApp(context)
                }
                if (detectedApp != null) lastDetectedApp = detectedApp
            }
            delay(2_000)
        }
    }

    LaunchedEffect(monitorRunning, preferences) {
        val cameraEnabled = preferences.getBoolean(CAMERA_CAPTURE_ENABLED_KEY, false)
        val cameraAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (monitorRunning && cameraEnabled && cameraAllowed && !cameraServicePromoted) {
            cameraServicePromoted = true
            setCameraForegroundMode(context, enabled = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        BrandHeader()
        Spacer(Modifier.height(32.dp))
        Text(
            "Choose apps",
            color = Ink,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.7).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Turn on a switch to add an app to your lock list.",
            color = Muted,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(14.dp))
        Text("UNLOCK METHOD", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnlockMode.entries.forEach { mode ->
                val modeEnabled = mode != UnlockMode.FINGERPRINT || fingerprintAvailable
                Button(
                    onClick = {
                        when (mode) {
                            UnlockMode.PIN -> {
                                unlockModeMessage = ""
                                onUnlockModeChange(mode)
                            }
                            UnlockMode.PATTERN -> {
                                if (preferences.contains(PATTERN_HASH_KEY)) {
                                    unlockModeMessage = ""
                                    onUnlockModeChange(mode)
                                } else {
                                    showPatternSetup = true
                                }
                            }
                            UnlockMode.FINGERPRINT -> {
                                if (fingerprintAvailable) {
                                    unlockModeMessage = ""
                                    onUnlockModeChange(mode)
                                } else {
                                    unlockModeMessage = "Enroll a fingerprint in your device settings first."
                                }
                            }
                        }
                    },
                    enabled = modeEnabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (unlockMode == mode) Green else Color.White,
                        contentColor = if (unlockMode == mode) Color.White else Ink,
                        disabledContainerColor = Color(0xFFE8ECE9),
                        disabledContentColor = Muted
                    )
                ) {
                    Text(mode.label, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        if (unlockModeMessage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(unlockModeMessage, color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))
        UsageAccessCard(
            hasAccess = usageAccessGranted,
            lastDetectedApp = lastDetectedApp,
            onGrantAccess = {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        )
        Spacer(Modifier.height(10.dp))
        MonitorControlCard(
            isRunning = monitorRunning,
            overlayAccessGranted = overlayAccessGranted,
            hasUsageAccess = usageAccessGranted,
            selectedAppCount = lockedPackages.size,
            onToggle = {
                if (monitorRunning) {
                    context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(MONITOR_ENABLED_KEY, false)
                        .apply()
                    context.stopService(Intent(context, ForegroundMonitorService::class.java))
                } else if (!usageAccessGranted) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else if (!overlayAccessGranted) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } else if (lockedPackages.isNotEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        requestRuntimePermission(context, Manifest.permission.POST_NOTIFICATIONS) {
                            startForegroundMonitor(context)
                        }
                    } else {
                        startForegroundMonitor(context)
                    }
                }
            }
        )
        Spacer(Modifier.height(10.dp))
        BatteryOptimizationCard(
            isIgnored = batteryOptimizationIgnored,
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )
        Spacer(Modifier.height(10.dp))
        SecurityOptionsCard(
            preferences = preferences,
            onRecoverySetup = onRecoverySetup
        )
        Spacer(Modifier.height(20.dp))
        Text(
            if (isLoading) "Finding apps…" else "${apps.size} APPS  ·  ${lockedPackages.size} SELECTED",
            color = Green,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(10.dp))

        if (isLoading) {
            Spacer(Modifier.weight(1f))
        } else if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No launchable apps were found.", color = Muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        isLocked = app.packageName in lockedPackages,
                        onLockChange = { onLockChange(app.packageName, it) }
                    )
                }
            }
        }

    }

    if (showPatternSetup) {
        PatternSetupDialog(
            onDismiss = { showPatternSetup = false },
            onPatternSaved = {
                showPatternSetup = false
                unlockModeMessage = ""
                onUnlockModeChange(UnlockMode.PATTERN)
            }
        )
    }
}

@Composable
private fun SecurityOptionsCard(
    preferences: android.content.SharedPreferences,
    onRecoverySetup: () -> Unit
) {
    val context = LocalContext.current
    val devicePolicyManager = remember(context) {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    val adminComponent = remember(context) {
        android.content.ComponentName(context, UninstallAdminReceiver::class.java)
    }
    val isDeviceOwner = remember(devicePolicyManager, context) {
        devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }
    var uninstallProtection by remember(preferences) {
        mutableStateOf(devicePolicyManager.isAdminActive(adminComponent))
    }
    var selfLock by remember(preferences) {
        mutableStateOf(preferences.getBoolean(SELF_LOCK_KEY, true))
    }
    var continuous by remember(preferences) {
        mutableStateOf(preferences.getBoolean(ALWAYS_MONITOR_KEY, false))
    }
    var cameraEnabled by remember(preferences) {
        mutableStateOf(preferences.getBoolean(CAMERA_CAPTURE_ENABLED_KEY, false))
    }
    var failedAttemptThreshold by remember(preferences) {
        mutableStateOf(preferences.getInt(FAILED_ATTEMPT_THRESHOLD_KEY, 2))
    }
    var showPhotos by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var protectLockedAppUninstall by remember(preferences) {
        mutableStateOf(preferences.getBoolean(BLOCK_ALL_APP_UNINSTALL_KEY, false))
    }
    var showDeviceOwnerSetup by remember { mutableStateOf(false) }
    var showUninstallPolicyError by remember { mutableStateOf(false) }
    LaunchedEffect(devicePolicyManager, adminComponent) {
        while (true) {
            val active = devicePolicyManager.isAdminActive(adminComponent)
            if (uninstallProtection != active) {
                uninstallProtection = active
                preferences.edit().putBoolean(UNINSTALL_PROTECTION_KEY, active).apply()
            }
            delay(1_000)
        }
    }
    LaunchedEffect(isDeviceOwner, protectLockedAppUninstall) {
        if (isDeviceOwner) {
            if (!setUninstallRestriction(context, protectLockedAppUninstall)) {
                showUninstallPolicyError = true
                if (protectLockedAppUninstall) {
                    protectLockedAppUninstall = false
                    preferences.edit()
                        .putBoolean(BLOCK_ALL_APP_UNINSTALL_KEY, false)
                        .apply()
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("SECURITY OPTIONS", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            SettingsSwitchRow("Lock Still when reopened", selfLock) {
                selfLock = it
                preferences.edit().putBoolean(SELF_LOCK_KEY, it).apply()
            }
            SettingsSwitchRow("Restart monitoring after reboot", continuous) {
                continuous = it
                preferences.edit().putBoolean(ALWAYS_MONITOR_KEY, it).apply()
            }
            SettingsSwitchRow("Photo after wrong password attempts", cameraEnabled) { enabled ->
                if (!enabled) {
                    cameraEnabled = false
                    preferences.edit().putBoolean(CAMERA_CAPTURE_ENABLED_KEY, false).apply()
                    FailedAttemptPhotos.resetSession(context)
                    setCameraForegroundMode(context, enabled = false)
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    cameraEnabled = true
                    preferences.edit().putBoolean(CAMERA_CAPTURE_ENABLED_KEY, true).apply()
                    FailedAttemptPhotos.resetSession(context)
                    setCameraForegroundMode(context, enabled = true)
                } else {
                    requestRuntimePermission(context, Manifest.permission.CAMERA) { granted ->
                        cameraEnabled = granted
                        preferences.edit().putBoolean(CAMERA_CAPTURE_ENABLED_KEY, granted).apply()
                        if (granted) {
                            FailedAttemptPhotos.resetSession(context)
                            setCameraForegroundMode(context, enabled = true)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Capture after", modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                listOf(2, 4, 6).forEach { count ->
                    Button(
                        onClick = {
                            failedAttemptThreshold = count
                            preferences.edit().putInt(FAILED_ATTEMPT_THRESHOLD_KEY, count).apply()
                        },
                        modifier = Modifier.padding(start = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (failedAttemptThreshold == count) Green else Color(0xFFEAF1EC),
                            contentColor = if (failedAttemptThreshold == count) Color.White else Ink
                        )
                    ) { Text("$count", fontSize = 12.sp) }
                }
            }
            Text(
                "Android asks for camera permission and shows its camera indicator. An ongoing notification appears while camera protection runs. Photos stay private until you save them.",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val photoCount = FailedAttemptPhotos.list(context).size
                Text("Private photos ($photoCount)", modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                TextButton(onClick = { showPhotos = true }) { Text("View", color = Green) }
            }
            SettingsSwitchRow("Protect You Lock from uninstall", uninstallProtection) {
                if (it) {
                    context.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Still must be deactivated in Android settings before it can be uninstalled.")
                    })
                } else {
                    context.startActivity(Intent("android.settings.DEVICE_ADMIN_SETTINGS"))
                }
            }
            SettingsSwitchRow(
                "Block uninstall of all apps",
                isDeviceOwner && protectLockedAppUninstall
            ) { enabled ->
                if (!isDeviceOwner) {
                    showDeviceOwnerSetup = true
                } else {
                    protectLockedAppUninstall = enabled
                    preferences.edit()
                        .putBoolean(BLOCK_ALL_APP_UNINSTALL_KEY, enabled)
                        .apply()
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recovery question", modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                TextButton(onClick = onRecoverySetup) { Text("Set up", color = Green) }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("App password", modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                TextButton(onClick = { showChangePin = true }) { Text("Change PIN", color = Green) }
            }
            Text(
                "Android requires you to deactivate this device administrator before uninstalling Still. It cannot block factory reset or a user who disables protection.",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }
    if (showPhotos) {
        CapturedPhotosDialog(
            context = context,
            onDismiss = { showPhotos = false }
        )
    }
    if (showChangePin) {
        ChangePinDialog(
            preferences = preferences,
            onDismiss = { showChangePin = false }
        )
    }
    if (showDeviceOwnerSetup) {
        DeviceOwnerSetupDialog(onDismiss = { showDeviceOwnerSetup = false })
    }
    if (showUninstallPolicyError) {
        AlertDialog(
            onDismissRequest = { showUninstallPolicyError = false },
            title = { Text("Could not update uninstall protection") },
            text = { Text("Android did not accept the device-owner policy. Confirm You Lock is still the device owner and try again.", color = Muted) },
            confirmButton = {
                TextButton(onClick = { showUninstallPolicyError = false }) { Text("OK", color = Green) }
            }
        )
    }
}

@Composable
private fun ChangePinDialog(
    preferences: android.content.SharedPreferences,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(ChangePinStep.CURRENT) }
    var input by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val title = when (step) {
        ChangePinStep.CURRENT -> "Verify current PIN"
        ChangePinStep.NEW -> "Choose a new PIN"
        ChangePinStep.CONFIRM -> "Confirm new PIN"
    }
    val label = when (step) {
        ChangePinStep.CURRENT -> "Current PIN"
        ChangePinStep.NEW -> "New 4-digit PIN"
        ChangePinStep.CONFIRM -> "Confirm new PIN"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Enter your current PIN, then choose and confirm a new one.", color = Muted)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) {
                            input = value
                            message = ""
                        }
                    },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (message.isNotEmpty()) {
                    Text(message, color = Color(0xFFB0443E), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (step) {
                    ChangePinStep.CURRENT -> when {
                        input.length != 4 -> message = "Enter all 4 digits."
                        !pinMatches(preferences, input) -> message = "That PIN doesn’t match."
                        else -> {
                            currentPin = input
                            input = ""
                            message = ""
                            step = ChangePinStep.NEW
                        }
                    }
                    ChangePinStep.NEW -> when {
                        input.length != 4 -> message = "Choose a 4-digit PIN."
                        input == currentPin -> message = "Choose a PIN different from your current one."
                        else -> {
                            newPin = input
                            input = ""
                            message = ""
                            step = ChangePinStep.CONFIRM
                        }
                    }
                    ChangePinStep.CONFIRM -> if (input == newPin) {
                        savePin(preferences, newPin)
                        onDismiss()
                    } else {
                        input = ""
                        newPin = ""
                        message = "Those PINs didn’t match. Enter a new PIN again."
                        step = ChangePinStep.NEW
                    }
                }
            }) { Text(if (step == ChangePinStep.CONFIRM) "Save PIN" else "Continue", color = Green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeviceOwnerSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val command = "adb shell dpm set-device-owner com.example.applock/.UninstallAdminReceiver"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device-owner setup required") },
        text = {
            Column {
                Text(
                    "Android only lets a device-owner app block other apps from uninstalling. Set You Lock as device owner from a computer during device provisioning, then reopen this setting.",
                    color = Muted
                )
                Spacer(Modifier.height(12.dp))
                Text(command, color = Ink, fontSize = 12.sp)
                Text(
                    "This blocks uninstalling every app, including apps outside You Lock's lock list. Device-owner setup usually requires a newly set-up phone and may require a factory reset.",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Device-owner command", command))
                onDismiss()
            }) { Text("Copy command", color = Green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun CapturedPhotosDialog(context: Context, onDismiss: () -> Unit) {
    var photos by remember { mutableStateOf(FailedAttemptPhotos.list(context)) }
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private failed-attempt photos") },
        text = {
            Column {
                if (photos.isEmpty()) Text("No photos have been captured.", color = Muted)
                else androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(photos, key = { it.name }) { file ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val bitmap = remember(file.absolutePath) {
                                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                            }
                            if (bitmap != null) Image(
                                bitmap = bitmap,
                                contentDescription = "Captured photo",
                                modifier = Modifier.size(88.dp),
                                contentScale = ContentScale.Crop
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified())), fontSize = 11.sp, color = Muted)
                                TextButton(onClick = {
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        requestRuntimePermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) { granted ->
                                            message = when {
                                                !granted -> "Storage permission is needed to save this photo."
                                                FailedAttemptPhotos.saveToGallery(context, file) != null -> "Saved to Pictures/You Lock."
                                                else -> "Could not save this photo."
                                            }
                                        }
                                    } else if (FailedAttemptPhotos.saveToGallery(context, file) != null) {
                                        message = "Saved to Pictures/You Lock."
                                    } else message = "Could not save this photo."
                                }) { Text("Save", color = Green) }
                                TextButton(onClick = {
                                    file.delete()
                                    photos = FailedAttemptPhotos.list(context)
                                }) { Text("Delete", color = Color(0xFFB0443E)) }
                            }
                        }
                    }
                }
                if (message.isNotEmpty()) Text(message, color = Muted, fontSize = 12.sp)
                Text("Photos stay inside You Lock unless you choose Save. Camera access is used only after repeated failed unlock attempts while protection is enabled.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Green) } }
    )
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Ink, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RecoverySetupDialog(
    preferences: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var question by remember { mutableStateOf(preferences.getString(RECOVERY_QUESTION_KEY, "").orEmpty()) }
    var answer by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set recovery question") },
        text = {
            Column {
                OutlinedTextField(question, { question = it }, label = { Text("Your question") }, singleLine = true)
                OutlinedTextField(answer, { answer = it }, label = { Text("Answer") }, singleLine = true)
                if (message.isNotEmpty()) Text(message, color = Color(0xFFB0443E), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (question.isBlank() || answer.isBlank()) message = "Enter both a question and answer."
                else {
                    preferences.edit().putString(RECOVERY_QUESTION_KEY, question.trim()).apply()
                    saveSecret(preferences, RECOVERY_HASH_KEY, RECOVERY_SALT_KEY, answer.trim().lowercase())
                    onSaved()
                }
            }) { Text("Save", color = Green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

@Composable
private fun RecoveryResetDialog(
    preferences: android.content.SharedPreferences,
    onDismiss: () -> Unit,
    onPinReset: (String) -> Unit
) {
    var answer by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val question = preferences.getString(RECOVERY_QUESTION_KEY, null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recover your PIN") },
        text = {
            Column {
                Text(question ?: "No recovery question is set on this device.", color = Muted)
                OutlinedTextField(answer, { answer = it }, label = { Text("Answer") }, singleLine = true)
                OutlinedTextField(newPin, { value -> if (value.length <= 4 && value.all(Char::isDigit)) newPin = value }, label = { Text("New 4-digit PIN") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
                if (message.isNotEmpty()) Text(message, color = Color(0xFFB0443E), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    question == null -> message = "Set up a recovery question from the app settings first."
                    !secretMatches(preferences, RECOVERY_HASH_KEY, RECOVERY_SALT_KEY, answer.trim().lowercase()) -> message = "That answer doesn’t match."
                    newPin.length != 4 -> message = "Enter a new 4-digit PIN."
                    else -> onPinReset(newPin)
                }
            }) { Text("Reset PIN", color = Green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PatternSetupDialog(
    onDismiss: () -> Unit,
    onPatternSaved: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    }
    var firstPattern by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Connect at least 4 dots.") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (firstPattern == null) "Create a pattern" else "Confirm your pattern") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (firstPattern == null) "Draw a pattern using at least 4 dots."
                    else "Draw the same pattern again.",
                    textAlign = TextAlign.Center,
                    color = Muted
                )
                AndroidView(
                    factory = { PatternInputView(it).apply { onPatternComplete = { drawn ->
                        when {
                            drawn.split(',').distinct().size < 4 -> message = "Use at least 4 dots."
                            firstPattern == null -> {
                                firstPattern = drawn
                                message = "Now draw it again to confirm."
                            }
                            drawn == firstPattern -> {
                                saveSecret(preferences, PATTERN_HASH_KEY, PATTERN_SALT_KEY, drawn)
                                onPatternSaved()
                            }
                            else -> {
                                firstPattern = null
                                message = "Those patterns didn’t match. Start again."
                            }
                        }
                    } } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                )
                Text(message, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun UsageAccessCard(
    hasAccess: Boolean,
    lastDetectedApp: DetectedApp?,
    onGrantAccess: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFEAF1EC), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AccessTime, contentDescription = null, tint = Green)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (hasAccess) "Usage access is on" else "Usage access is needed",
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        !hasAccess -> "Allow access to read app activity"
                        lastDetectedApp != null -> "Most recent foreground: ${lastDetectedApp.name}"
                        else -> "Waiting for app activity…"
                    },
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            Button(
                onClick = onGrantAccess,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) {
                Text(if (hasAccess) "Settings" else "Allow", fontSize = 12.sp)
            }
        }
    }

}

@Composable
private fun MonitorControlCard(
    isRunning: Boolean,
    overlayAccessGranted: Boolean,
    hasUsageAccess: Boolean,
    selectedAppCount: Int,
    onToggle: () -> Unit
) {
    val status = when {
        isRunning -> "Your selected apps are being monitored."
        selectedAppCount == 0 -> "Select at least one app below to begin."
        !hasUsageAccess -> "Usage Access is required to detect apps."
        !overlayAccessGranted -> "Allow display over other apps to show the lock."
        else -> "Ready to protect $selectedAppCount selected app${if (selectedAppCount == 1) "" else "s"}."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFEAF1EC), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Green)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isRunning) "App lock is on" else "Background app lock",
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(status, color = Muted, fontSize = 11.sp, maxLines = 2)
            }
            Button(
                onClick = onToggle,
                enabled = isRunning || selectedAppCount > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) {
                Text(
                    when {
                        isRunning -> "Stop"
                        !overlayAccessGranted && selectedAppCount > 0 -> "Allow"
                        else -> "Start"
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    isIgnored: Boolean,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFEAF1EC), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.BatteryFull, contentDescription = null, tint = Green)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isIgnored) "Battery optimization is off" else "Keep monitoring reliable",
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (isIgnored) "Android is less likely to pause the monitor."
                    else "Allow Still to run without battery optimization.",
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            if (!isIgnored) {
                TextButton(onClick = onOpenSettings) {
                    Text("Adjust", color = Green, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    isLocked: Boolean,
    onLockChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.name,
                    modifier = Modifier.size(42.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color(0xFFEAF1EC), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, tint = Green)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, color = Muted, fontSize = 10.sp, maxLines = 1)
            }
            Switch(
                checked = isLocked,
                onCheckedChange = onLockChange
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Color(0xFFE3EEE8), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = Green)
        }
        Text(
            "STILL",
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.5.sp
        )
        Spacer(Modifier.weight(1f))
        Text("YOUR SPACE", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun Footer() {
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(Icons.Rounded.Security, contentDescription = null, tint = Muted, modifier = Modifier.size(14.dp))
        Text("A little more privacy, every day.", color = Muted, fontSize = 12.sp)
    }
}

private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val packageManager = context.packageManager
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, 0)
    }

    return activities.asSequence()
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.applicationInfo?.packageName
                ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            packageName to resolveInfo
        }
        .distinctBy { (packageName, _) -> packageName }
        .map { (packageName, resolveInfo) ->
            InstalledApp(
                name = resolveInfo.loadLabel(packageManager).toString(),
                packageName = packageName,
                icon = runCatching { resolveInfo.loadIcon(packageManager).toImageBitmap() }.getOrNull()
            )
        }
        .sortedBy { it.name.lowercase() }
        .toList()
}

@Suppress("DEPRECATION")
internal fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val bitmapSize = 96
    val originalBounds = Rect(bounds)
    val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: bitmapSize
    val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: bitmapSize
    val scale = minOf(
        bitmapSize.toFloat() / sourceWidth,
        bitmapSize.toFloat() / sourceHeight
    )
    val width = (sourceWidth * scale).toInt()
    val height = (sourceHeight * scale).toInt()
    val left = (bitmapSize - width) / 2
    val top = (bitmapSize - height) / 2

    setBounds(left, top, left + width, top + height)
    draw(canvas)
    bounds = originalBounds
    return bitmap.asImageBitmap()
}

@Suppress("DEPRECATION")
private fun findMostRecentForegroundApp(context: Context): DetectedApp? {
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val events = usageStatsManager.queryEvents(endTime - 60_000, endTime)
    val event = UsageEvents.Event()
    var mostRecentPackage: String? = null

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
        if (isForegroundEvent) {
            mostRecentPackage = event.packageName
        }
    }

    val packageName = mostRecentPackage ?: return null
    val appName = runCatching {
        val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }
        context.packageManager.getApplicationLabel(applicationInfo).toString()
    }.getOrDefault(packageName)

    return DetectedApp(name = appName, packageName = packageName)
}

private fun hasSavedPin(preferences: android.content.SharedPreferences): Boolean =
    preferences.contains(PIN_HASH_KEY) && preferences.contains(PIN_SALT_KEY)

internal fun readLockedPackages(preferences: android.content.SharedPreferences): Set<String> =
    preferences.getStringSet(LOCKED_PACKAGES_KEY, emptySet()).orEmpty().toSet()

private fun savePin(preferences: android.content.SharedPreferences, pin: String) {
    saveSecret(preferences, PIN_HASH_KEY, PIN_SALT_KEY, pin)
}

private fun saveSecret(
    preferences: android.content.SharedPreferences,
    hashKey: String,
    saltKey: String,
    secret: String
) {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    preferences.edit()
        .putString(saltKey, Base64.encodeToString(salt, Base64.NO_WRAP))
        .putString(hashKey, hashSecret(secret, salt))
        .apply()
}

internal fun pinMatches(preferences: android.content.SharedPreferences, pin: String): Boolean {
    return secretMatches(preferences, PIN_HASH_KEY, PIN_SALT_KEY, pin)
}

internal fun secretMatches(
    preferences: android.content.SharedPreferences,
    hashKey: String,
    saltKey: String,
    secret: String
): Boolean {
    val encodedSalt = preferences.getString(saltKey, null) ?: return false
    val savedHash = preferences.getString(hashKey, null) ?: return false

    return runCatching {
        val salt = Base64.decode(encodedSalt, Base64.NO_WRAP)
        val candidateHash = hashSecret(secret, salt)
        MessageDigest.isEqual(
            savedHash.toByteArray(Charsets.UTF_8),
            candidateHash.toByteArray(Charsets.UTF_8)
        )
    }.getOrDefault(false)
}

private fun hashSecret(secret: String, salt: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(salt + secret.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(hash, Base64.NO_WRAP)
}

private fun startForegroundMonitor(context: Context) {
    val preferences = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    preferences.edit()
        .putBoolean(MONITOR_ENABLED_KEY, true)
        .apply()
    val cameraEnabled = preferences.getBoolean(CAMERA_CAPTURE_ENABLED_KEY, false) &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val serviceIntent = Intent(context, ForegroundMonitorService::class.java)
    if (cameraEnabled) serviceIntent.action = ACTION_ENABLE_CAMERA_CAPTURE
    context.startForegroundService(serviceIntent)
}

private fun requestRuntimePermission(
    context: Context,
    permission: String,
    onResult: (Boolean) -> Unit
) {
    val activity = context as? MainActivity
    if (activity == null) {
        onResult(false)
    } else {
        activity.requestRuntimePermission(permission, onResult)
    }
}

private fun setCameraForegroundMode(context: Context, enabled: Boolean) {
    if (!ForegroundMonitorService.isRunning) return
    val action = if (enabled) ACTION_ENABLE_CAMERA_CAPTURE else ACTION_DISABLE_CAMERA_CAPTURE
    ContextCompat.startForegroundService(
        context,
        Intent(context, ForegroundMonitorService::class.java).setAction(action)
    )
}
