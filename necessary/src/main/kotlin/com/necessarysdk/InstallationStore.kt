package com.necessarysdk

import android.content.Context
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Values kept on this phone, encrypted under a key that never leaves its
 * keystore. [read] answers null for a value never written, or one that can no
 * longer be decrypted (its key was lost); it throws when the keystore cannot
 * say.
 */
interface SecureStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun delete(key: String)
}

/**
 * Where the SDK keeps the installation ID: in the phone's secure storage
 * beside the key, encrypted under a key held in Android Keystore.
 */
open class InstallationStore(
    val key: NativeInstallationKey,
    private val storage: SecureStorage,
    context: CoroutineContext = Dispatchers.Default,
) {
    constructor(context: Context) : this(NativeInstallationKey(context), AndroidSecureStorage(context))

    private companion object {
        const val INSTALLATION_ID_KEY = "recognition.installation_id.v2"
        const val STEP_UP_REENROL_KEY = "recognition.stepup_reenrol_pending.v1"
    }

    private val scope = CoroutineScope(SupervisorJob() + context)
    private val lock = Any()
    private var loading: Deferred<InstallationIdentity>? = null

    /**
     * The installation: its key, made on first use, and its ID. A key made
     * now ([InstallationIdentity.createdThisLaunch]) is a new installation, so
     * it gets a new ID even where one was stored: that ID belongs to the key
     * that was lost, and a backend refuses a known ID under another key. Calls
     * at once share one load, so that one key never gets two IDs.
     */
    open suspend fun loadOrCreate(): InstallationIdentity {
        val running = synchronized(lock) {
            loading ?: run {
                lateinit var load: Deferred<InstallationIdentity>
                load = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        loadOrCreateOnce()
                    } finally {
                        synchronized(lock) { if (loading === load) loading = null }
                    }
                }
                loading = load
                load
            }
        }
        running.start()
        return running.await()
    }

    private suspend fun loadOrCreateOnce(): InstallationIdentity {
        val stored = storage.read(INSTALLATION_ID_KEY)
        val metadata = key.getOrCreateKey()
        var installationId = stored ?: ""
        if (installationId.isEmpty() || metadata.created) {
            installationId = UUID.randomUUID().toString()
            saveInstallationId(installationId)
        }
        return InstallationIdentity(installationId = installationId, nativeKey = key, metadata = metadata)
    }

    /**
     * Keeps the installation your backend answered for this key, which may
     * not be the ID proposed. Only this opaque ID is stored here; the private
     * key never leaves the phone's keystore.
     */
    open suspend fun saveInstallationId(installationId: String) = storage.write(INSTALLATION_ID_KEY, installationId)

    /**
     * Whether a step-up key was replaced and the backend has not yet taken
     * the new one (DeviceTrust.reenrolStepUp) -- kept across restarts.
     */
    open suspend fun stepUpReenrolPending(): Boolean = storage.read(STEP_UP_REENROL_KEY) != null

    open suspend fun setStepUpReenrolPending(pending: Boolean) {
        if (pending) storage.write(STEP_UP_REENROL_KEY, "1") else storage.delete(STEP_UP_REENROL_KEY)
    }

    /** Deletes the key and the installation ID: the next start is a new installation. */
    open suspend fun clear() {
        key.deleteKey()
        storage.delete(INSTALLATION_ID_KEY)
        storage.delete(STEP_UP_REENROL_KEY)
    }
}
