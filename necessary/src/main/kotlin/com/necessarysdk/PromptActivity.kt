package com.necessarysdk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * The SDK's own invisible activity, for the two answers an app's activity
 * would otherwise have to forward: the confirm-credential screen a step-up
 * approval opens on Android 9 and 10 (onActivityResult), and the location
 * permission (onRequestPermissionsResult). It shows nothing of its own.
 *
 * A configuration change recreates it, and the answer reaches the new one:
 * the approval keeps waiting. Gone for good without an answer, it ends the
 * wait -- the approval as STEPUP_CANCELLED, so that it never holds the next
 * one at STEPUP_BUSY.
 */
class PromptActivity : Activity() {
    internal companion object {
        private const val EXTRA_KIND = "com.necessarysdk.prompt.kind"
        private const val EXTRA_INTENT = "com.necessarysdk.prompt.intent"
        private const val EXTRA_REQUEST = "com.necessarysdk.prompt.request"
        private const val KIND_CONFIRM = "confirm_credential"
        private const val KIND_LOCATION = "location"
        private const val LOCATION_REQUEST = 4721
        private const val STATE_ANSWERED = "answered"

        fun confirmCredential(from: Activity, intent: Intent, requestCode: Int) {
            from.startActivity(
                Intent(from, PromptActivity::class.java)
                    .putExtra(EXTRA_KIND, KIND_CONFIRM)
                    .putExtra(EXTRA_INTENT, intent)
                    .putExtra(EXTRA_REQUEST, requestCode),
            )
            from.overridePendingTransition(0, 0)
        }

        fun requestLocation(from: Activity) {
            from.startActivity(Intent(from, PromptActivity::class.java).putExtra(EXTRA_KIND, KIND_LOCATION))
            from.overridePendingTransition(0, 0)
        }
    }

    private var answered = false
    private val kind: String? get() = intent?.getStringExtra(EXTRA_KIND)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated: the prompt it opened answers this one.
            answered = savedInstanceState.getBoolean(STATE_ANSWERED)
            if (answered) finish()
            return
        }
        when (kind) {
            KIND_CONFIRM -> try {
                @Suppress("DEPRECATION")
                val target: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_INTENT)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(
                    target ?: throw IllegalStateException("no confirm-credential intent"),
                    intent.getIntExtra(EXTRA_REQUEST, 0),
                )
            } catch (error: RuntimeException) {
                // The confirmation never opened: nothing may wait for it.
                answered = true
                AndroidNative.installed()?.confirmationFailed(error)
                finish()
            }
            KIND_LOCATION -> requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_REQUEST,
            )
            else -> finish()
        }
    }

    @Deprecated("The platform's own result call; the SDK targets every Android from 7.0.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        answered = true
        AndroidNative.installed()?.confirmationAnswered(requestCode, resultCode)
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_REQUEST) return
        answered = true
        AndroidNative.installed()?.locationAnswered()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ANSWERED, answered)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Gone for good, not recreated for a configuration change: the answer
        // will never come.
        if (!answered && !isChangingConfigurations) {
            when (kind) {
                KIND_CONFIRM -> AndroidNative.installed()?.confirmationGone()
                KIND_LOCATION -> AndroidNative.installed()?.locationAnswered()
            }
        }
    }
}
