package com.necessarysdk

/**
 * The phone's side of each piece: what the SDK asks of Android's keystore,
 * its collector and its window. Each call answers what the native code
 * answers, or throws a [NativeCallException] with its code and its words.
 * The Android implementation is [AndroidNative]; tests replace it.
 */
internal interface InstallationKeyNative {
    suspend fun getOrCreateKey(): Map<String, Any?>
    suspend fun sign(payload: String): String
    suspend fun deleteKey(): Boolean

    /** The reinstall hint's source, ANDROID_ID; empty when the platform gives none. */
    suspend fun reinstallHintIdentifier(): String
}

internal interface StepUpKeyNative {
    suspend fun getOrCreateStepUpKey(factor: String, mode: String, windowSeconds: Int): Map<String, Any?>

    /** Shows the prompt, on the main thread, and signs inside its answer. */
    suspend fun signWithStepUpKey(payload: String, reason: String, mode: String): String
    suspend fun deleteStepUpKey(): Boolean
}

internal interface CollectorNative {
    suspend fun collect(
        requiredProbes: List<String>,
        challengeNonce: String,
        optionalProbes: List<String>,
        enabledMeasurements: List<String>,
    ): Map<String, Any?>

    suspend fun requestLocationPermission(): String
}

internal interface ScreenNative {
    suspend fun protect(on: Boolean): Map<String, Any?>
}

/** A native side asked for where the SDK has none: the tests' own objects, which override every call. */
internal object NoNative : InstallationKeyNative, StepUpKeyNative, CollectorNative, ScreenNative {
    private fun missing(): Nothing = throw NativeCallException("NATIVE_MISSING", "No native side is attached.")
    override suspend fun getOrCreateKey(): Map<String, Any?> = missing()
    override suspend fun sign(payload: String): String = missing()
    override suspend fun deleteKey(): Boolean = missing()
    override suspend fun reinstallHintIdentifier(): String = missing()
    override suspend fun getOrCreateStepUpKey(factor: String, mode: String, windowSeconds: Int): Map<String, Any?> =
        missing()
    override suspend fun signWithStepUpKey(payload: String, reason: String, mode: String): String = missing()
    override suspend fun deleteStepUpKey(): Boolean = missing()
    override suspend fun collect(
        requiredProbes: List<String>,
        challengeNonce: String,
        optionalProbes: List<String>,
        enabledMeasurements: List<String>,
    ): Map<String, Any?> = missing()
    override suspend fun requestLocationPermission(): String = missing()
    override suspend fun protect(on: Boolean): Map<String, Any?> = missing()
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObject(): Map<String, Any?>? = (this as? Map<*, *>)?.let { map ->
    if (map.keys.all { it is String }) map as Map<String, Any?> else null
}
