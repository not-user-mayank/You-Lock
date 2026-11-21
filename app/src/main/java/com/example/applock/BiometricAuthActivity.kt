package com.example.applock

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class BiometricAuthActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        if (lockedPackage.isNullOrBlank()) {
            finish()
            return
        }

        val canAuthenticate = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            notifyService(ACTION_BIOMETRIC_CANCELLED, lockedPackage)
            finish()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    notifyService(ACTION_BIOMETRIC_SUCCEEDED, lockedPackage)
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    notifyService(ACTION_BIOMETRIC_CANCELLED, lockedPackage)
                    finish()
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Still")
            .setSubtitle("Confirm your identity to open the protected app")
            .setNegativeButtonText("Use PIN instead")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun notifyService(action: String, lockedPackage: String) {
        startService(
            Intent(this, ForegroundMonitorService::class.java)
                .setAction(action)
                .putExtra(EXTRA_LOCKED_PACKAGE, lockedPackage)
        )
    }
}
