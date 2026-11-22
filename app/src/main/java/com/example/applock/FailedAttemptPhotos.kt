package com.example.applock

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

internal object FailedAttemptPhotos {
    fun registerWrongPin(context: Context) {
        val preferences = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(CAMERA_CAPTURE_ENABLED_KEY, false)) return
        val count = preferences.getInt(FAILED_ATTEMPTS_KEY, 0) + 1
        val threshold = preferences.getInt(FAILED_ATTEMPT_THRESHOLD_KEY, 2)
        val editor = preferences.edit().putInt(FAILED_ATTEMPTS_KEY, count)
        val shouldCapture = !preferences.getBoolean(CAPTURED_FOR_SESSION_KEY, false) && count >= threshold &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (shouldCapture) {
            editor.putInt(FAILED_ATTEMPTS_KEY, 0)
                .putBoolean(CAPTURED_FOR_SESSION_KEY, true)
        }
        editor.apply()
        if (shouldCapture) {
            ContextCompat.startForegroundService(
                context,
                android.content.Intent(context, ForegroundMonitorService::class.java)
                    .setAction(ACTION_CAPTURE_FAILED_ATTEMPT)
            )
        }
    }

    fun resetSession(context: Context) {
        context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE).edit()
            .putInt(FAILED_ATTEMPTS_KEY, 0)
            .putBoolean(CAPTURED_FOR_SESSION_KEY, false)
            .apply()
    }

    fun list(context: Context): List<File> =
        File(context.filesDir, "failed_attempt_photos")
            .listFiles { file -> file.isFile && file.extension.equals("jpg", true) }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()

    fun saveToGallery(context: Context, file: File): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/You Lock")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        }
        uri
    }.getOrNull()
}
