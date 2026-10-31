package com.necessarysdk

import android.content.Context
import kotlinx.coroutines.CancellationException

/** The reinstall hint: a SHA-256 of the phone's per-app device identifier, which the backend keys before storing. Client input, never proof. */
class ReinstallHint(val kind: String, val value: String) {
    fun toJson(): Map<String, Any?> = linkedMapOf("kind" to kind, "value" to value)
}

/** Reads the reinstall hint: ANDROID_ID, scoped to the phone, the user and the app's signing key. */
open class ReinstallHintReader internal constructor(private val native: InstallationKeyNative) {
    constructor(context: Context) : this(AndroidNative.of(context))

    open suspend fun read(): ReinstallHint? {
        val kind = "android_id"
        try {
            val value = native.reinstallHintIdentifier().trim().lowercase()
            if (value.isEmpty()) return null
            val digest = sha256Hex("device-recognition-hint-v1|$kind|$value")
            return ReinstallHint(kind = "${kind}_sha256", value = digest)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A missing hint must never stop cryptographic installation identity.
        }
        return null
    }
}
