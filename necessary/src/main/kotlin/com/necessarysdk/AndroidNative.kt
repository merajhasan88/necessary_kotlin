package com.necessarysdk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The Android side of every piece: the installation key, the step-up key, the
 * collector and screen protection, called directly. One per process. Blocking
 * work runs on one worker thread; the step-up prompt, the location prompt and
 * the window are the visible activity's, on the main thread.
 */
internal class AndroidNative private constructor(private val context: Context) :
    InstallationKeyNative, StepUpKeyNative, CollectorNative, ScreenNative {

    companion object {
        @Volatile private var instance: AndroidNative? = null

        fun of(context: Context): AndroidNative =
            instance ?: synchronized(this) {
                instance ?: AndroidNative(context.applicationContext).also {
                    Activities.install(context.applicationContext)
                    instance = it
                }
            }

        /** The process's, if anything made it. */
        fun installed(): AndroidNative? = instance

        /**
         * AndroidKeyStore's own error code, which its exception's message may leave
         * out: public from Android 13 (API 33), with whether the keystore deems the
         * failure transient.
         */
        fun keystoreDetail(error: Throwable): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val keystore = error as? android.security.KeyStoreException ?: return null
            return "code ${keystore.numericErrorCode}" + if (keystore.isTransientFailure) ", transient" else ""
        }
    }

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "necessary-worker").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val installationKeys = InstallationKeyManager(context)
    private val integrityProbes = IntegrityProbeManager(context) { Activities.visible }
    private val stepUpKeys = StepUpKeyManager(context) { activity, intent, requestCode ->
        PromptActivity.confirmCredential(activity, intent, requestCode)
    }
    private val screenGuard = ScreenGuard()

    // The app's own request for the location permission, answered when the
    // person chooses; the collector itself never asks.
    private var pendingLocation: CompletableDeferred<String>? = null

    init {
        // A window made while the screen is protected is protected too.
        Activities.onNewWindow = { activity -> reattachScreen(activity) }
    }

    // MARK: the installation key

    override suspend fun getOrCreateKey(): Map<String, Any?> = offMainThread { installationKeys.getOrCreateKey() }

    override suspend fun sign(payload: String): String = offMainThread { installationKeys.signPayload(payload) }

    override suspend fun deleteKey(): Boolean = offMainThread { installationKeys.deleteKey() }

    // The reinstall hint's source, ANDROID_ID: scoped to the phone, the user
    // and the app's signing key. Hashed before it leaves the phone; empty when
    // the platform gives none.
    override suspend fun reinstallHintIdentifier(): String = offMainThread {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }

    // MARK: the step-up key

    override suspend fun getOrCreateStepUpKey(factor: String, mode: String, windowSeconds: Int): Map<String, Any?> =
        offMainThread { stepUpKeys.getOrCreateKey(factor, mode, windowSeconds) }

    // On the main thread: it shows the system prompt, and the keystore signs
    // inside the prompt's callback.
    override suspend fun signWithStepUpKey(payload: String, reason: String, mode: String): String =
        withContext(Dispatchers.Main.immediate) {
            val activity = Activities.visible
                ?: throw NativeCallException("NO_ACTIVITY", "The step-up prompt needs a visible activity.")
            suspendCancellableCoroutine { continuation ->
                fun fail(error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(stepUpFailure(error))
                }
                try {
                    stepUpKeys.sign(activity, payload, reason, mode) { outcome ->
                        outcome.fold(
                            { signature -> if (continuation.isActive) continuation.resume(signature) },
                            { error -> fail(error) },
                        )
                    }
                } catch (error: Throwable) {
                    // Whatever failed before the prompt, said with its causes.
                    fail(error)
                }
            }
        }

    override suspend fun deleteStepUpKey(): Boolean = offMainThread { stepUpKeys.deleteKey() }

    /** The confirm-credential screen answered (API < 30). */
    fun confirmationAnswered(requestCode: Int, resultCode: Int) {
        stepUpKeys.onActivityResult(requestCode, resultCode)
    }

    /** The confirm-credential screen could not be opened. */
    fun confirmationFailed(error: Throwable) = stepUpKeys.failPendingConfirmation(error)

    /** The screen that asked for the passcode is gone for good: its answer will never come. */
    fun confirmationGone() = stepUpKeys.cancelPendingConfirmation()

    /** A step-up signature that failed: its code, and its words with every cause beneath them. */
    private fun stepUpFailure(error: Throwable): NativeCallException =
        if (error is InstallationKeyFailure) {
            NativeCallException(error.errorCode, error.report(::keystoreDetail))
        } else {
            NativeCallException(
                "NATIVE_STEPUP_ERROR",
                "The step-up signature failed: " + Causes.chain(error, ::keystoreDetail),
            )
        }

    // MARK: the collector

    override suspend fun collect(
        requiredProbes: List<String>,
        challengeNonce: String,
        optionalProbes: List<String>,
        enabledMeasurements: List<String>,
    ): Map<String, Any?> = offMainThread {
        integrityProbes.collect(requiredProbes, challengeNonce, optionalProbes, enabledMeasurements.toSet())
    }

    // For the location and mock_location measurements: asks the person once,
    // from a button on the app's own screen.
    override suspend fun requestLocationPermission(): String {
        val waiting = withContext(Dispatchers.Main.immediate) {
            pendingLocation?.let { return@withContext it }
            val activity = Activities.visible
            if (locationState() == "precise" || activity == null) return@withContext null
            CompletableDeferred<String>().also { pending ->
                pendingLocation = pending
                try {
                    PromptActivity.requestLocation(activity)
                } catch (error: RuntimeException) {
                    pendingLocation = null
                    pending.complete(locationState())
                }
            }
        } ?: return locationState()
        return waiting.await()
    }

    /** The person answered the location prompt, or it is gone. */
    fun locationAnswered() {
        val pending = pendingLocation ?: return
        pendingLocation = null
        pending.complete(locationState())
    }

    private fun locationState(): String = when {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED -> "precise"
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED -> "approximate"
        else -> "none"
    }

    // MARK: screen protection

    // On the main thread: it changes the window.
    override suspend fun protect(on: Boolean): Map<String, Any?> = withContext(Dispatchers.Main.immediate) {
        val activity = Activities.visible
        if (on) {
            if (activity == null) {
                throw NativeCallException("NO_ACTIVITY", "Screen protection needs a visible activity.")
            }
            screenGuard.acquire(windowState(activity))?.let { applyScreen(activity, it) }
        } else {
            // Counted even without a window, so a release is never lost.
            val change = screenGuard.release()
            if (activity != null && change != null) applyScreen(activity, change)
        }
        screenState(activity)
    }

    private fun reattachScreen(activity: Activity) {
        screenGuard.reattach(windowState(activity))?.let { applyScreen(activity, it) }
    }

    /**
     * While a secret is typed: the window cannot be captured in screenshots,
     * recordings or casts (FLAG_SECURE); other apps' windows are not drawn
     * over it (Android 12 and later, HIDE_OVERLAY_WINDOWS, a normal
     * permission); and a touch that passed through another app's window is
     * dropped. ScreenGuard decides when, and what the window gets back. The
     * phone's own screen-lock prompt is the system's window and needs none of
     * this from the app.
     */
    private fun applyScreen(activity: Activity, change: ScreenGuard.Change) {
        val window = activity.window
        if (change.secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (overlaysHideable()) window.setHideOverlayWindows(change.hideOverlays)
        window.decorView.filterTouchesWhenObscured = change.dropsObscuredTouches
    }

    private fun overlaysHideable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission("android.permission.HIDE_OVERLAY_WINDOWS") ==
            PackageManager.PERMISSION_GRANTED

    private fun windowState(activity: Activity) = ScreenGuard.WindowState(
        secure = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
        dropsObscuredTouches = activity.window.decorView.filterTouchesWhenObscured,
    )

    /** What the window has now, and whether the SDK holds it. */
    private fun screenState(activity: Activity?): Map<String, Any?> = linkedMapOf(
        "held" to screenGuard.held,
        "secure" to (activity?.let { windowState(it).secure } ?: false),
        "overlays_hidden" to (screenGuard.held && overlaysHideable()),
        "obscured_touches_dropped" to (activity?.let { windowState(it).dropsObscuredTouches } ?: false),
    )

    // MARK: the worker thread

    private suspend fun <T> offMainThread(operation: () -> T): T = withContext(worker) {
        try {
            operation()
        } catch (error: InstallationKeyFailure) {
            throw NativeCallException(error.errorCode, error.report(::keystoreDetail))
        } catch (error: Throwable) {
            // Name the exception and its cause: a release build's log output is
            // suppressed on some phones, so the message the app is given is the
            // only diagnostic there is.
            val detail = buildString {
                append("The Android native operation failed: ")
                append(Causes.chain(error, ::keystoreDetail))
                append(" at ")
                append(
                    error.stackTrace.take(6).joinToString(" < ") {
                        "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                    },
                )
            }
            throw NativeCallException("NATIVE_INTEGRITY_ERROR", detail)
        }
    }
}
