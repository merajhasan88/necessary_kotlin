package com.necessarysdk

import android.content.Context

/** The native integrity collector: measures what a challenge asks for and the app enabled, and nothing else. */
open class NativeIntegrityCollector internal constructor(private val native: CollectorNative) {
    constructor(context: Context) : this(AndroidNative.of(context))

    /**
     * [enabledMeasurements]: the personal measurements this app's developers
     * allow; with [optionalProbes], what the backend asks for, the collector
     * measures only what is in both.
     */
    open suspend fun collect(
        requiredProbes: List<String>,
        challengeNonce: String,
        enabledMeasurements: List<String>,
        optionalProbes: List<String> = emptyList(),
    ): Map<String, Any?> = try {
        native.collect(requiredProbes, challengeNonce, optionalProbes, enabledMeasurements)
    } catch (error: NativeCallException) {
        throw NativeKeyException(error.code, error.message ?: "The native integrity collector failed.")
    }

    /**
     * Asks the person for the OS location permission and returns what the app
     * now holds: precise, approximate or none. Only the app asks, from its own
     * screen; a scan never prompts.
     */
    open suspend fun requestLocationPermission(): String = try {
        native.requestLocationPermission()
    } catch (error: NativeCallException) {
        throw NativeKeyException(error.code, error.message ?: "The location permission request failed.")
    }
}
