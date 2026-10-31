package com.necessarysdk

import android.content.Context

/**
 * The installation key as the phone's keystore describes it: one
 * non-exportable P-256 key per installation, made and held by Android
 * Keystore -- in StrongBox where the phone has it, in its trusted environment
 * otherwise.
 */
class NativeKeyMetadata(
    val publicKey: Map<String, Any?>,
    val algorithm: String,
    val keyAlias: String,
    val provider: String,
    val securityLevel: String,
    val hardwareBacked: Boolean,
    val privateKeyExportable: Boolean,
    val signatureFormat: String,
    val created: Boolean,
) {
    companion object {
        /** The key as the native side answered it; an answer without an EC P-256 public JWK is refused. */
        fun fromPlatform(value: Map<String, Any?>): NativeKeyMetadata {
            val publicKey = value["public_key"].asObject()
                ?: throw IllegalArgumentException("The native key provider returned no public key.")

            fun requiredString(field: String): String {
                val item = value[field]
                if (item !is String || item.isEmpty()) {
                    throw IllegalArgumentException("The native key provider returned an invalid $field.")
                }
                return item
            }

            fun requiredBool(field: String): Boolean =
                value[field] as? Boolean
                    ?: throw IllegalArgumentException("The native key provider returned an invalid $field.")

            if (publicKey["kty"] != "EC" || publicKey["crv"] != "P-256" || publicKey["alg"] != "ES256" ||
                publicKey["x"] !is String || publicKey["y"] !is String
            ) {
                throw IllegalArgumentException("The native key provider did not return an EC P-256 public JWK.")
            }
            return NativeKeyMetadata(
                publicKey = LinkedHashMap(publicKey),
                algorithm = requiredString("algorithm"),
                keyAlias = requiredString("key_alias"),
                provider = requiredString("provider"),
                securityLevel = requiredString("security_level"),
                hardwareBacked = requiredBool("hardware_backed"),
                privateKeyExportable = requiredBool("private_key_exportable"),
                signatureFormat = requiredString("signature_format"),
                created = requiredBool("created"),
            )
        }
    }
}

/** The installation key in Android Keystore: made on first use, never exported. */
open class NativeInstallationKey internal constructor(private val native: InstallationKeyNative) {
    constructor(context: Context) : this(AndroidNative.of(context))

    open suspend fun getOrCreateKey(): NativeKeyMetadata {
        val value = try {
            native.getOrCreateKey()
        } catch (error: NativeCallException) {
            throw NativeKeyException(error.code, error.message ?: "The native key provider failed.")
        }
        return NativeKeyMetadata.fromPlatform(value)
    }

    open suspend fun signPayload(payloadBase64Url: String): String {
        val signature = try {
            native.sign(payloadBase64Url)
        } catch (error: NativeCallException) {
            throw NativeKeyException(
                error.code,
                error.message ?: "The native key provider could not sign the challenge.",
            )
        }
        if (signature.isEmpty()) {
            throw NativeKeyException("EMPTY_NATIVE_SIGNATURE", "The native key provider returned no signature.")
        }
        return signature
    }

    open suspend fun deleteKey() {
        try {
            native.deleteKey()
        } catch (error: NativeCallException) {
            throw NativeKeyException(
                error.code,
                error.message ?: "The native installation key could not be deleted.",
            )
        }
    }
}

/** The installation: its ID, and the key that is its identity. */
class InstallationIdentity(
    val installationId: String,
    val nativeKey: NativeInstallationKey,
    val metadata: NativeKeyMetadata,
) {
    val publicKeyJson: Map<String, Any?> get() = metadata.publicKey
    val algorithm: String get() = metadata.algorithm
    val keyAlias: String get() = metadata.keyAlias
    val provider: String get() = metadata.provider
    val securityLevel: String get() = metadata.securityLevel
    val hardwareBacked: Boolean get() = metadata.hardwareBacked
    val privateKeyExportable: Boolean get() = metadata.privateKeyExportable
    val signatureFormat: String get() = metadata.signatureFormat

    /** A key made at this launch: a new installation, whatever the app kept belongs to the one before. */
    val createdThisLaunch: Boolean get() = metadata.created

    /** The key's thumbprint: SHA-256, in hex, of its JWK's members in RFC 7638's order. */
    val keyThumbprint: String get() = thumbprintOf(publicKeyJson)

    suspend fun signPayload(payloadBase64Url: String): String = nativeKey.signPayload(payloadBase64Url)

    fun withInstallationId(canonicalInstallationId: String): InstallationIdentity =
        InstallationIdentity(canonicalInstallationId, nativeKey, metadata)
}

/** RFC 7638's members of an EC public JWK, in its order, hashed: the form a backend derives from the JWK. */
internal fun thumbprintOf(jwk: Map<String, Any?>): String =
    sha256Hex(
        Json.encode(
            linkedMapOf("crv" to jwk["crv"], "kty" to "EC", "x" to jwk["x"], "y" to jwk["y"]),
        ),
    )
