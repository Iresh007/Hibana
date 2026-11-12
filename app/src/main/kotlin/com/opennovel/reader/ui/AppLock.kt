package com.opennovel.reader.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Thin wrapper over BiometricPrompt that only ever reports success or failure. */
object AppLock {
    fun authenticators(allowBiometrics: Boolean): Int =
        if (allowBiometrics || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        } else {
            DEVICE_CREDENTIAL
        }

    /** False when the device has no screen lock at all, so the lock can't be satisfied. */
    fun canAuthenticate(activity: FragmentActivity, allowBiometrics: Boolean): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators(allowBiometrics)) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, allowBiometrics: Boolean, onResult: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Hibana")
            .setAllowedAuthenticators(authenticators(allowBiometrics))
            .build()
        prompt.authenticate(info)
    }
}
