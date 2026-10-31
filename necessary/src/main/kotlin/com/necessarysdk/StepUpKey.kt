package com.necessarysdk

import android.content.Context

/**
 * The optional step-up key as the native side reports it. [factor], [mode]
 * and [windowSeconds] describe what the keystore actually enforces (read back
 * from KeyInfo), which on Android 9 and 10 is a short hardware window rather
 * than per-use.
 */
class StepUpKeyMetadata(
    val publicKey: Map<String, Any?>,
    val factor: String,
    val mode: String,
    val windowSeconds: Int,
    val securityLevel: String,
    val hardwareBacked: Boolean,
    val created: Boolean,
) {
    val authJson: Map<String, Any?>
        get() = linkedMapOf("factor" to factor, "mode" to mode, "window_seconds" to windowSeconds)

    val description: String
        get() = if (mode == "per_use") "$factor, per-use" else "$factor, ${windowSeconds}s hardware window"

    /** RFC 7638 thumbprint, the same form the server derives from the JWK. */
    val thumbprint: String get() = thumbprintOf(publicKey)

    companion object {
        fun fromPlatform(value: Map<String, Any?>): StepUpKeyMetadata {
            val publicKey = value["public_key"].asObject()
            val auth = value["auth"].asObject()
            if (publicKey == null || auth == null) {
                throw IllegalArgumentException("The native step-up key provider returned no public key or auth.")
            }
            if (publicKey["kty"] != "EC" || publicKey["crv"] != "P-256" || publicKey["alg"] != "ES256" ||
                publicKey["x"] !is String || publicKey["y"] !is String
            ) {
                throw IllegalArgumentException(
                    "The native step-up key provider did not return an EC P-256 public JWK.",
                )
            }
            val factor = auth["factor"]
            val mode = auth["mode"]
            val window = auth["window_seconds"]
            if (factor !is String || mode !is String || !(window is Int || window is Long)) {
                throw IllegalArgumentException(
                    "The native step-up key provider returned an invalid auth description.",
                )
            }
            return StepUpKeyMetadata(
                publicKey = LinkedHashMap(publicKey),
                factor = factor,
                mode = mode,
                windowSeconds = (window as Number).toInt(),
                securityLevel = value["security_level"] as? String ?: "unknown",
                hardwareBacked = value["hardware_backed"] == true,
                created = value["created"] == true,
            )
        }
    }
}

/** Where a backend takes a re-enrolled step-up key by default (the public guide, section 4). */
const val STEP_UP_REENROL_PATH: String = "/v1/installations/stepup-key"

/**
 * How the app wants its step-up key made (the public guide, section 3):
 * [factor] `passcode` or `biometric`; [mode] `per_use`, or `windowed` with
 * [windowSeconds] from 1. Android 9 and 10 cannot make a per-use passcode
 * key and make a windowed one. [reenrolPath] is where the backend takes a new
 * key (DeviceTrust.reenrolStepUp).
 */
data class StepUpOptions(
    val factor: String = "passcode",
    val mode: String = "per_use",
    val windowSeconds: Int = 0,
    val reenrolPath: String = STEP_UP_REENROL_PATH,
) {
    /**
     * A key made with other values, or already made: whether it satisfies
     * these options -- the same factor (a backend refuses any other), and a
     * per-use key or a window no longer than this one. A per-use key
     * satisfies windowed options.
     */
    fun accepts(key: StepUpKeyMetadata): Boolean =
        key.factor == factor &&
            (key.mode == "per_use" || (mode == "windowed" && key.windowSeconds <= windowSeconds))

    val description: String
        get() = "$factor, ${if (mode == "per_use") "per-use" else "${windowSeconds}s window"}"
}

/**
 * The proof a new step-up key signs at re-enrolment: the base64url of its
 * JSON, the members in this order. [nonce] is the request proof's nonce of
 * the re-enrolment request.
 */
fun stepUpReenrolProof(installationId: String, stepUpKeyThumbprint: String, nonce: String, timestamp: Long): String =
    base64UrlNoPadding(
        utf8(
            Json.encode(
                linkedMapOf(
                    "version" to 1,
                    "purpose" to "stepup_reenrol",
                    "installation_id" to installationId,
                    "stepup_key_thumbprint" to stepUpKeyThumbprint,
                    "nonce" to nonce,
                    "timestamp" to timestamp,
                ),
            ),
        ),
    )

/**
 * Whether a step-up key [options] do not accept is replaced before the
 * next registration: only where the new key can then be bound -- by a
 * re-enrolment ([replaceMismatched]), which binds it with the person's
 * re-authentication, or by this installation's first registration, which
 * binds the key it is offered. A known installation's registration never
 * binds an offered key; it only says whether it matches, so replacing the key
 * there orphans the one the server holds: on a phone that cannot make the key
 * the options ask for (Android 9 makes no per-use passcode key), the
 * registration that follows every re-enrolment would replace the key the
 * server had just bound, and step-up would stay unavailable.
 */
fun replacesStepUpKey(
    key: StepUpKeyMetadata,
    options: StepUpOptions,
    replaceMismatched: Boolean,
    knownInstallation: Boolean,
): Boolean = !key.created && !options.accepts(key) && (replaceMismatched || !knownInstallation)

/** The step-up key in Android Keystore, and its prompt. */
open class NativeStepUpKey internal constructor(
    private val native: StepUpKeyNative,
    /**
     * The mode the app asks for, which decides whether a windowed key first
     * tries to sign inside an open hardware window.
     */
    var configuredMode: String = "per_use",
) {
    constructor(context: Context) : this(AndroidNative.of(context))

    /**
     * Returns the existing key as the keystore enforces it, or creates one
     * with these values: an existing key is never changed, so a caller that
     * needs other values deletes it first.
     */
    open suspend fun getOrCreateKey(factor: String, mode: String, windowSeconds: Int): StepUpKeyMetadata {
        val value = try {
            native.getOrCreateStepUpKey(factor, mode, windowSeconds)
        } catch (error: NativeCallException) {
            throw NativeKeyException(error.code, error.message ?: "The native step-up key provider failed.")
        }
        return StepUpKeyMetadata.fromPlatform(value)
    }

    /**
     * Shows the system passcode (or biometric) prompt and completes when the
     * person answers it. A cancelled prompt is STEPUP_CANCELLED.
     */
    open suspend fun sign(payloadBase64Url: String, reason: String): String {
        val signature = try {
            native.signWithStepUpKey(payloadBase64Url, reason, configuredMode)
        } catch (error: NativeCallException) {
            throw NativeKeyException(error.code, error.message ?: "The step-up key could not sign.")
        }
        if (signature.isEmpty()) {
            throw NativeKeyException("EMPTY_NATIVE_SIGNATURE", "The step-up key returned no signature.")
        }
        return signature
    }

    open suspend fun deleteKey() {
        try {
            native.deleteStepUpKey()
        } catch (error: NativeCallException) {
            throw NativeKeyException(error.code, error.message ?: "The step-up key could not be deleted.")
        }
    }
}
