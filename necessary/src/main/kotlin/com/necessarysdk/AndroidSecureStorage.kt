package com.necessarysdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SecureStorage] on Android: each value AES-256-GCM encrypted under a key
 * that never leaves Android Keystore, kept in the app's private preferences.
 *
 * The storage key is made only where the keystore, answering, holds none
 * (KeyPresence): a lookup that failed reads as "no key", and making one then
 * would replace the key the installation ID is encrypted under, and lose the
 * ID. A keystore that cannot be reached fails the call with KEY_UNAVAILABLE.
 */
internal class AndroidSecureStorage(context: Context) : SecureStorage {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("necessary.secure_storage.v1", Context.MODE_PRIVATE)
    private val keyAlias = "${app.packageName}.necessary.storage_key.v1"
    private val probeAlias = "$keyAlias.probe"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        // Making the key runs one at a time in the process.
        val keyLock = Any()
    }

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val stored = preferences.getString(key, null) ?: return@withContext null
        val secret = storageKey(create = false) ?: return@withContext null
        val bytes = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        if (bytes.size <= IV_BYTES) return@withContext null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            // Written under a key that is gone: there is no value to read.
            null
        } catch (error: GeneralSecurityException) {
            throw failure("could not decrypt a stored value", error)
        } catch (error: ProviderException) {
            throw failure("could not decrypt a stored value", error)
        }
    }

    override suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        val secret = storageKey(create = true)!!
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        } catch (error: GeneralSecurityException) {
            throw failure("could not encrypt a value", error)
        } catch (error: ProviderException) {
            throw failure("could not encrypt a value", error)
        }
        if (!preferences.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            throw NativeKeyException("STORAGE_WRITE_FAILED", "The installation's storage could not be written.")
        }
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        if (!preferences.edit().remove(key).commit()) {
            throw NativeKeyException("STORAGE_WRITE_FAILED", "The installation's storage could not be written.")
        }
    }

    /** The storage key; with [create], made where the keystore, answering, holds none. */
    private fun storageKey(create: Boolean): SecretKey? = synchronized(keyLock) {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        } catch (error: GeneralSecurityException) {
            throw failure("is unavailable", error)
        } catch (error: java.io.IOException) {
            throw failure("is unavailable", error)
        }
        val found = KeyPresence.of(object : KeyPresence.Keystore {
            override fun containsKey() = keyStore.containsAlias(keyAlias)
            override fun deleteProbe() = keyStore.deleteEntry(probeAlias)
        })
        when (found.answer) {
            KeyPresence.Answer.PRESENT -> {}
            KeyPresence.Answer.ABSENT -> {
                if (!create) return null
                try {
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                keyAlias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build(),
                        )
                    }.generateKey()
                } catch (error: GeneralSecurityException) {
                    throw failure("could not make the storage key", error)
                } catch (error: ProviderException) {
                    throw failure("could not make the storage key", error)
                }
            }
            KeyPresence.Answer.UNAVAILABLE -> throw NativeKeyException(
                "KEY_UNAVAILABLE",
                Causes.withCauses(
                    "AndroidKeyStore could not be reached for the key the installation ID is kept under; " +
                        "nothing was replaced. Try again.",
                    found.cause,
                ),
            )
        }
        try {
            (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw NativeKeyException(
                    "KEY_UNAVAILABLE",
                    "AndroidKeyStore did not return the key the installation ID is kept under; it was kept.",
                )
        } catch (error: GeneralSecurityException) {
            throw failure("could not load the storage key", error)
        } catch (error: UnsupportedOperationException) {
            throw failure("could not load the storage key", error)
        }
    }

    private fun failure(what: String, cause: Throwable) = NativeKeyException(
        "KEY_UNAVAILABLE",
        Causes.withCauses("The installation's storage $what.", cause),
    )
}
