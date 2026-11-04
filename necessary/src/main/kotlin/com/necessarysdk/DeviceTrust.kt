package com.necessarysdk

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/**
 * The personal measurements an app may enable (the public guide, section
 * 8). Each is collected only when the backend asks for it as well -- the
 * location only with the person's permission too.
 */
enum class PersonalMeasurement(
    /** The name the report and the backend use. */
    val wireName: String,
) {
    LOCAL_ADDRESSES("local_addresses"),
    ACCESSIBILITY("accessibility"),
    NOTIFICATION_ACCESS("notification_access"),
    OTHER_ACCESS("other_access"),
    SCREEN_CAPTURE("screen_capture"),
    LOCATION("location"),
    MOCK_LOCATION("mock_location"),
}

/** Where the backend takes the app PIN's two calls (the public guide, section 4). */
data class AppPinOptions(
    val path: String = DeviceTrustClient.APP_PIN_PATH,
    val proofPath: String = DeviceTrustClient.APP_PIN_PROOF_PATH,
)

/** One scan: the challenge it answered, the report as signed, and the backend's answer, whole. */
class ScanResult(val challenge: ScanChallenge, val report: SignedReport, val answer: Map<String, Any?>) {
    /** The stored report (`integrity.report_id`), which a scan made for one request sends back with that request. */
    val reportId: String? get() = answer["integrity"].asObject()?.get("report_id") as? String
}

/**
 * DeviceTrust: the one object an app configures -- its backend, the
 * personal measurements it enables, step-up and the app PIN -- and what it
 * asks of it: start, scan, and sign a request (the public guide).
 *
 * Every call may be made from any thread; the work runs off the main thread,
 * and only the step-up prompt on it.
 */
class DeviceTrust(
    transport: DeviceTrustTransport,
    private val store: InstallationStore,
    private val collector: NativeIntegrityCollector,
    private val hintReader: ReinstallHintReader,
    private val stepUpKeyNative: NativeStepUpKey,
    personalMeasurements: Set<PersonalMeasurement> = emptySet(),
    stepUp: StepUpOptions? = null,
    /** The app PIN's calls, or null for no app PIN. */
    val appPin: AppPinOptions? = null,
    /**
     * Every registration's answer, whole -- also those made again inside a
     * scan, when the backend had lost the installation -- awaited before the
     * installation goes on.
     */
    private val onRegistered: (suspend (answer: Map<String, Any?>) -> Unit)? = null,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) {
    /**
     * Talks to your backend over JSON and HTTPS under [baseUrl], the SDK's
     * default API (the public guide, section 4). A release build of the app
     * refuses an `http://` address.
     */
    constructor(
        context: Context,
        baseUrl: String,
        personalMeasurements: Set<PersonalMeasurement> = emptySet(),
        stepUp: StepUpOptions? = null,
        appPin: AppPinOptions? = null,
        onRegistered: (suspend (answer: Map<String, Any?>) -> Unit)? = null,
    ) : this(
        HttpTransport(baseUrl, releaseBuild = !isDebuggable(context)),
        context,
        personalMeasurements,
        stepUp,
        appPin,
        onRegistered,
    )

    /** The same over the app's own [transport]. */
    constructor(
        transport: DeviceTrustTransport,
        context: Context,
        personalMeasurements: Set<PersonalMeasurement> = emptySet(),
        stepUp: StepUpOptions? = null,
        appPin: AppPinOptions? = null,
        onRegistered: (suspend (answer: Map<String, Any?>) -> Unit)? = null,
    ) : this(
        transport,
        InstallationStore(context),
        NativeIntegrityCollector(context),
        ReinstallHintReader(context),
        NativeStepUpKey(context),
        personalMeasurements,
        stepUp,
        appPin,
        onRegistered,
    )

    companion object {
        private fun isDebuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /** A step-up key a re-enrolment replaces: one that can never sign again, or one the keystore cannot vouch for. */
        private fun replaceableKey(error: NativeKeyException): Boolean =
            error.code == "STEPUP_KEY_INVALIDATED" || error.code == "STEPUP_KEY_UNAVAILABLE"

        /** What approvals answer while the backend does not hold the step-up key. */
        private fun reenrolPending() = NativeKeyException(
            "STEPUP_REENROL_PENDING",
            "The backend does not hold this step-up key -- a re-enrolment unfinished, or a key made after " +
                "the installation was registered: reenrolStepUp() first.",
        )
    }

    /** The guide's calls, for an app that makes them itself. */
    val client: DeviceTrustClient = DeviceTrustClient(transport)

    /**
     * What this app enables; the backend asks for what it needs of them. The
     * app may change it whenever the person does -- in a privacy setting, say:
     * the next scan measures what it holds then, and the installation, its
     * session and its step-up key stay as they are.
     */
    @Volatile var personalMeasurements: Set<PersonalMeasurement> = personalMeasurements

    /** How the step-up key is made, or null for no step-up. An app that takes these from its backend sets them before [start]. */
    @Volatile var stepUp: StepUpOptions? = stepUp

    // Everything below is read and changed under [lock], never across a
    // suspension: each segment between two suspensions is one step, as the
    // installation's state machine needs.
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + coroutineContext)

    private var currentIdentity: InstallationIdentity? = null
    private var session: String? = null

    // One call of each kind in flight; a second waits for the first's result.
    private var loading: Deferred<InstallationIdentity>? = null
    private var starting: Deferred<InstallationIdentity>? = null
    private var proving: Deferred<String>? = null
    private val scanning = HashMap<String, Deferred<ScanResult>>()

    // Which installation the calls running now belong to: reset() and close()
    // move on to the next, and a call of an earlier one keeps nothing it finds.
    private var generation = 0

    // A reset's deletion, which every later load waits for; and the
    // installation ID being saved and a re-enrolment, which a reset waits for.
    private var resetting: Job? = null
    private var saving: Job? = null
    private var reenrolling: Job? = null

    // The persistent writes and key changes running now (mutate): a reset
    // waits for each before it deletes, so none outlives the deletion.
    private val mutations = LinkedHashSet<Job>()
    private var offeredStepUpKey: StepUpKeyMetadata? = null
    private var hint: ReinstallHint? = null
    private var hintRead = false
    private var unavailableStepUp: NativeKeyException? = null

    /** Why step-up is off on this phone although the app turned it on -- no screen lock, say -- or null. */
    val stepUpUnavailable: NativeKeyException? get() = synchronized(lock) { unavailableStepUp }

    val identity: InstallationIdentity? get() = synchronized(lock) { currentIdentity }

    /** The installation session from proving the key, or null before [start]. */
    val installationSession: String? get() = synchronized(lock) { session }

    /** The step-up key offered at the last registration. */
    val stepUpKey: StepUpKeyMetadata? get() = synchronized(lock) { offeredStepUpKey }

    /** The wire names of [personalMeasurements], in the guide's order. */
    val enabledMeasurements: List<String>
        get() {
            val enabled = personalMeasurements
            return PersonalMeasurement.entries.filter { it in enabled }.map { it.wireName }
        }

    /** A call shared by everyone who asks while it runs; it forgets itself when it ends. Under [lock]. */
    private fun <T> shared(get: () -> Deferred<T>?, set: (Deferred<T>?) -> Unit, body: suspend () -> T): Deferred<T> {
        get()?.let { return it }
        lateinit var call: Deferred<T>
        call = scope.async(start = CoroutineStart.LAZY) {
            try {
                body()
            } finally {
                synchronized(lock) { if (get() === call) set(null) }
            }
        }
        set(call)
        return call
    }

    private suspend fun <T> awaitShared(call: Deferred<T>): T {
        call.start()
        return call.await()
    }

    /**
     * The installation: its key, made on first use, and its ID. A key made
     * now is a new installation ([InstallationIdentity.createdThisLaunch]):
     * whatever the app kept for the one before belongs to that one. Calls at
     * once share one load, so one key gets one ID.
     */
    suspend fun loadIdentity(): InstallationIdentity {
        val call = synchronized(lock) {
            currentIdentity?.let { return it }
            shared({ loading }, { loading = it }) { load() }
        }
        return awaitShared(call)
    }

    private suspend fun load(): InstallationIdentity {
        // A reset's deletion first, so that nothing loads the key it deletes.
        synchronized(lock) { resetting }?.join()
        val generation = synchronized(lock) { generation }
        val loaded = store.loadOrCreate()
        return synchronized(lock) {
            current(generation)
            currentIdentity ?: loaded.also { currentIdentity = it }
        }
    }

    /**
     * A persistent write or a key change for the installation [generation]:
     * refused before it starts when reset() or close() has ended that
     * installation, and waited for by a reset that began while it ran. The
     * order is the whole guarantee: endCalls() moves the generation on at once,
     * under the same lock, so a write asked for after that throws here without
     * writing; one asked for before is in [mutations], which the reset waits
     * for before it deletes anything.
     */
    private suspend fun <T> mutate(generation: Int, change: suspend () -> T): T {
        val running = synchronized(lock) {
            current(generation)
            scope.async(start = CoroutineStart.LAZY) { change() }.also { job ->
                mutations.add(job)
                job.invokeOnCompletion { synchronized(lock) { mutations.remove(job) } }
            }
        }
        return awaitShared(running)
    }

    /** Throws when reset() or close() ended the installation [generation] belongs to. Under [lock]. */
    private fun current(generation: Int) {
        if (generation != this.generation) {
            throw InterruptedCallException(
                "The installation was reset, or the client closed, while this call ran: nothing it found was kept.",
            )
        }
    }

    private fun checkCurrent(generation: Int) = synchronized(lock) { current(generation) }

    /**
     * Loads or makes the key, registers it -- with the step-up key, when
     * step-up is on -- and proves the installation holds it: the
     * installation session. A refusal outside the guide's protocol errors
     * goes to the app as it came. A second call while one runs gets its
     * result.
     */
    suspend fun start(): InstallationIdentity {
        val call = synchronized(lock) {
            val generation = generation
            shared({ starting }, { starting = it }) { startOnce(generation) }
        }
        return awaitShared(call)
    }

    private suspend fun startOnce(generation: Int): InstallationIdentity {
        loadIdentity()
        register(generation)
        prove()
        return synchronized(lock) {
            current(generation)
            currentIdentity!!
        }
    }

    /** A new installation session: the key's challenge, answered. A second call while one runs gets its result. */
    suspend fun prove(): String {
        val call = synchronized(lock) {
            val generation = generation
            shared({ proving }, { proving = it }) { proveOnce(generation, retried = false) }
        }
        return awaitShared(call)
    }

    /**
     * Deletes the key, the installation ID (InstallationStore.clear) and the
     * step-up key, and forgets the session: the next start is a new
     * installation, linked to this one by nothing it holds. Calls still
     * running end with InterruptedCallException and keep nothing; calls made
     * from now on wait for the deletion, and never join one made before.
     */
    suspend fun reset() {
        val deleting = synchronized(lock) {
            val loading = loading
            val saving = saving
            val reenrolling = reenrolling
            val earlier = resetting
            val changing = mutations.toList()
            endCalls()
            offeredStepUpKey = null
            hint = null
            hintRead = false
            scope.async(start = CoroutineStart.LAZY) {
                earlier?.join()
                // A load or a save still running finishes first, so that nothing it
                // writes outlives the deletion. So does a re-enrolment: its key, which
                // a backend may already have taken, is deleted after it, and the
                // marker it writes is cleared. So does every write and key change
                // still running (mutate): the step-up marker, a step-up key being made.
                for (job in listOfNotNull(loading, saving, reenrolling) + changing) job.join()
                store.clear()
                // The step-up key goes with the installation, as on a reinstall: kept,
                // it would link the next installation to this one. None to delete is
                // no failure; nor is a phone with no step-up key at all. A key that
                // stays is: reset() fails with it, the installation itself gone, so
                // the app knows and may call reset() again.
                try {
                    stepUpKeyNative.deleteKey()
                } catch (error: NativeKeyException) {
                    if (error.code != "UNSUPPORTED_ANDROID_VERSION") {
                        throw NativeKeyException(
                            error.code,
                            "The installation was reset, but its step-up key could not be deleted: " +
                                "${error.message} Call reset() again.",
                        )
                    }
                }
            }.also { resetting = it }
        }
        // Later loads wait for the deletion, whatever came of it; the caller of
        // reset hears what did.
        awaitShared(deleting)
    }

    /** Moves on to the next installation: the calls running now keep nothing, and the next ones start afresh. Under [lock]. */
    private fun endCalls() {
        generation++
        currentIdentity = null
        session = null
        loading = null
        starting = null
        proving = null
        scanning.clear()
    }

    private suspend fun proveOnce(generation: Int, retried: Boolean): String {
        val identity = loadIdentity()
        checkCurrent(generation)
        try {
            val challenge = client.createInstallationChallenge(identity.installationId)
            val payload = answerText(challenge, "payload")
            val verified = client.verifyInstallation(
                installationId = identity.installationId,
                challengeId = answerText(challenge, "challenge_id"),
                payload = payload,
                signature = identity.signPayload(payload),
            )
            return synchronized(lock) {
                current(generation)
                answerText(verified, "device_token").also { session = it }
            }
        } catch (error: ApiException) {
            if (retried) throw error
            when (protocolStepFor(error, deviceSession = true)) {
                ProtocolStep.ASK_AGAIN -> {}
                ProtocolStep.REGISTER_AGAIN -> register(generation)
                ProtocolStep.WAIT_THEN_RETRY -> delay(protocolWait(error)!!)
                ProtocolStep.PROVE_AGAIN, ProtocolStep.STOP -> throw error
            }
            return proveOnce(generation, retried = true)
        }
    }

    private suspend fun register(generation: Int) {
        val identity = loadIdentity()
        val readHint = synchronized(lock) {
            current(generation)
            !hintRead
        }
        if (readHint) {
            val read = hintReader.read()
            synchronized(lock) {
                current(generation)
                hint = read
                hintRead = true
            }
        }
        val offered = prepareStepUpKey(generation)
        val answer = client.registerInstallation(identity, synchronized(lock) { hint }, stepUpKey = offered)
        val saved = synchronized(lock) {
            current(generation)
            // The key is the installation's identity: a backend that holds it under
            // another ID answers with that one, which is kept.
            val held = answer["installation_id"]
            if (held is String && held.isNotEmpty() && held != identity.installationId) {
                currentIdentity = identity.withInstallationId(held)
                scope.async { store.saveInstallationId(held) }.also { saving = it }
            } else {
                null
            }
        }
        saved?.await()
        settleStepUpKey(answer, offered, generation)
        onRegistered?.invoke(answer)
    }

    /**
     * Whether the backend holds the step-up key offered: it takes one only
     * when it first registers an installation, so a key made later -- after the
     * screen lock was removed and set again, say -- it takes by re-enrolment
     * alone. Its answer says so (`stepup_key_matches`, or for an installation
     * new to it `stepup_key_registered`); until it holds this key, approvals
     * wait for reenrolStepUp, across restarts too. A registration without a
     * key, the phone unable to make one, leaves any key made later waiting the
     * same way. A backend whose answer says nothing is taken at its word unless
     * a re-enrolment is pending.
     */
    private suspend fun settleStepUpKey(answer: Map<String, Any?>, offered: StepUpKeyMetadata?, generation: Int) {
        if (stepUp == null) return
        val matches = answer["stepup_key_matches"]
        val registered = answer["stepup_key_registered"]
        val bound: Boolean? = if (offered == null) null else (matches as? Boolean) ?: (registered as? Boolean)
        if (offered == null || bound == false) {
            mutate(generation) { store.setStepUpReenrolPending(true) }
        } else if (bound == true) {
            mutate(generation) { store.setStepUpReenrolPending(false) }
        }
        val pending = offered != null && bound != true && store.stepUpReenrolPending()
        synchronized(lock) {
            current(generation)
            if (pending) {
                offeredStepUpKey = null
                unavailableStepUp = reenrolPending()
            }
        }
    }

    /**
     * The step-up key as [stepUp] asks: an existing key is kept as it is.
     * Step-up is optional, so a key the phone cannot make leaves it off. Its
     * use waits for the backend's answer (settleStepUpKey).
     */
    private suspend fun prepareStepUpKey(generation: Int): StepUpKeyMetadata? {
        val options = stepUp
        if (options == null) {
            synchronized(lock) {
                unavailableStepUp = null
                offeredStepUpKey = null
            }
            return null
        }
        stepUpKeyNative.configuredMode = options.mode
        var key: StepUpKeyMetadata? = null
        var unavailable: NativeKeyException? = null
        try {
            key = mutate(generation) {
                stepUpKeyNative.getOrCreateKey(options.factor, options.mode, options.windowSeconds)
            }
        } catch (error: NativeKeyException) {
            unavailable = error
        }
        synchronized(lock) {
            current(generation)
            unavailableStepUp = unavailable
            offeredStepUpKey = key
        }
        return key
    }

    /**
     * A scan: under the installation session -- or, for a scan that approves
     * one [operation] ([describeOperation]), under the app's account
     * [session] for this installation, the one that will send that request.
     * The guide's protocol errors are acted on once; an account session is
     * the app's to renew. A second call for the same scan while one runs gets
     * its result; a scan whose answer was lost is simply asked for again.
     */
    suspend fun scan(session: String? = null, operation: Map<String, Any?>? = null): ScanResult {
        require(operation == null || session != null) {
            "a scan for one operation is asked under the account session that will send it"
        }
        // The personal measurements enabled now are part of the scan: one asked
        // for after the person changed them never joins one asked for before.
        val enabled = enabledMeasurements
        val kind = Json.encode(listOf(session, operation, enabled))
        val call = synchronized(lock) {
            val generation = generation
            shared({ scanning[kind] }, { if (it == null) scanning.remove(kind) else scanning[kind] = it }) {
                scanWithProtocol(generation, session, operation, enabled)
            }
        }
        return awaitShared(call)
    }

    private suspend fun scanWithProtocol(
        generation: Int,
        session: String?,
        operation: Map<String, Any?>?,
        enabled: List<String>,
    ): ScanResult {
        try {
            return scanOnce(generation, session, operation, enabled)
        } catch (error: ApiException) {
            val installationSession = session == null
            when (protocolStepFor(error, deviceSession = installationSession)) {
                ProtocolStep.ASK_AGAIN -> {}
                ProtocolStep.PROVE_AGAIN -> prove()
                ProtocolStep.REGISTER_AGAIN -> {
                    // An account session belongs to the installation the backend lost:
                    // the app signs in again.
                    if (!installationSession) throw error
                    register(generation)
                    prove()
                }
                ProtocolStep.WAIT_THEN_RETRY -> delay(protocolWait(error)!!)
                ProtocolStep.STOP -> throw error
            }
            return scanOnce(generation, session, operation, enabled)
        }
    }

    private suspend fun scanOnce(
        generation: Int,
        session: String?,
        operation: Map<String, Any?>?,
        enabled: List<String>,
    ): ScanResult {
        val identity = loadIdentity()
        val bearer = session ?: synchronized(lock) { this.session } ?: prove()
        checkCurrent(generation)
        val challenge = client.integrityChallenge(session = bearer, identity = identity, operation = operation)
        val report = collectReport(
            challenge = challenge,
            identity = identity,
            collector = collector,
            enabledMeasurements = enabled,
        )
        // A report of an installation reset meanwhile is never sent.
        checkCurrent(generation)
        val answer = client.submitIntegrityReport(session = bearer, identity = identity, report = report)
        checkCurrent(generation)
        return ScanResult(challenge = challenge, report = report, answer = answer)
    }

    /**
     * Signs one request to your backend under [bearer], your account session:
     * [body] is sent exactly as given. With [stepUp] the person approves it
     * with the step-up key -- the phone asks, saying [reason]; with [appPin]
     * the backend checks the PIN for it first; [reportId] names the scan made
     * for it alone. Send it with RequestProof.headers beside
     * `Authorization: Bearer`, or with [send]; each proof serves this request
     * once.
     */
    suspend fun signRequest(
        method: String,
        path: String,
        bearer: String,
        body: String = "",
        stepUp: Boolean = false,
        reason: String = "Approve this request",
        appPin: String? = null,
        reportId: String? = null,
    ): RequestProof {
        val generation = synchronized(lock) { generation }
        val identity = loadIdentity()
        val approval = synchronized(lock) {
            current(generation)
            if (!stepUp) {
                null
            } else {
                // Only the key the backend received at registration: a key made later
                // would sign approvals the backend cannot check.
                val key = offeredStepUpKey ?: throw (
                    unavailableStepUp ?: NativeKeyException(
                        "STEPUP_OFF",
                        if (this.stepUp == null) {
                            "Step-up is off: DeviceTrust(stepUp = StepUpOptions(...))."
                        } else {
                            "No step-up key was registered: start() first."
                        },
                    )
                    )
                StepUpApproval(factor = key.factor) { payload -> stepUpKeyNative.sign(payload, reason) }
            }
        }
        var proof = com.necessarysdk.signRequest(
            identity = identity,
            method = method,
            path = path,
            bearerToken = bearer,
            encodedBody = body,
            stepUp = approval,
        )
        // The installation reset, or the client closed, while the phone signed:
        // the proof is of an installation that is gone, and is never handed out.
        checkCurrent(generation)
        if (appPin != null) {
            val options = this.appPin
                ?: throw IllegalStateException("The app PIN is off: DeviceTrust(appPin = AppPinOptions()).")
            proof = client.proveAppPin(
                session = bearer,
                identity = identity,
                pin = appPin,
                request = proof,
                path = options.proofPath,
            )
            checkCurrent(generation)
        }
        return if (reportId == null) proof else proof.withIntegrityReport(reportId)
    }

    /**
     * Re-enrols the step-up key under the account [session], with the
     * person's own [reauthentication] -- the account's password, say:
     * `mapOf("password" to ...)`. For a key the phone has lost or can no longer
     * use -- the screen lock removed (`STEPUP_KEY_INVALIDATED`) -- or one made
     * for other options: the person unlocks the new key to sign its proof, the
     * phone showing [reason], and the backend takes it for that account alone
     * (the public guide, section 3). Approvals use it from then on. A key
     * replaced before the backend took the new one -- the person cancelled,
     * the password was wrong, the answer was lost -- leaves step-up waiting:
     * approvals answer `STEPUP_REENROL_PENDING`, across restarts too, until a
     * call of this succeeds.
     */
    suspend fun reenrolStepUp(
        session: String,
        reauthentication: Map<String, Any?> = emptyMap(),
        reason: String = "Confirm your new step-up key",
    ): Map<String, Any?> {
        val running = synchronized(lock) {
            val generation = generation
            // reset() waits for it, whatever comes of it.
            scope.async(start = CoroutineStart.LAZY) {
                reenrolStepUpOnce(generation, session, reauthentication, reason)
            }.also { reenrolling = it }
        }
        return awaitShared(running)
    }

    private suspend fun reenrolStepUpOnce(
        generation: Int,
        session: String,
        reauthentication: Map<String, Any?>,
        reason: String,
    ): Map<String, Any?> {
        val options = stepUp
            ?: throw NativeKeyException("STEPUP_OFF", "Step-up is off: DeviceTrust(stepUp = StepUpOptions(...)).")
        val identity = loadIdentity()
        checkCurrent(generation)
        stepUpKeyNative.configuredMode = options.mode

        // From here the phone's key and the backend's differ until the backend
        // takes the new one: approvals wait for that.
        suspend fun holdApprovals() {
            mutate(generation) { store.setStepUpReenrolPending(true) }
            synchronized(lock) {
                current(generation)
                offeredStepUpKey = null
                unavailableStepUp = reenrolPending()
            }
        }

        suspend fun newKey(replace: Boolean): StepUpKeyMetadata {
            if (replace) {
                holdApprovals()
                mutate(generation) { stepUpKeyNative.deleteKey() }
            }
            val key = mutate(generation) {
                stepUpKeyNative.getOrCreateKey(options.factor, options.mode, options.windowSeconds)
            }
            // None to replace, the phone having lost it: this one is as new.
            if (key.created && !replace) holdApprovals()
            return key
        }

        // A re-enrolment is the explicit recovery: a key the keystore cannot
        // vouch for (STEPUP_KEY_UNAVAILABLE), or one that can never sign again,
        // is replaced here -- and only here.
        var key = try {
            newKey(replace = false)
        } catch (error: NativeKeyException) {
            if (!replaceableKey(error)) throw error
            newKey(replace = true)
        }
        if (!key.created && !options.accepts(key)) {
            key = newKey(replace = true)
        }
        // The new key signs a proof bound to this request's own nonce, which the
        // request proof then signs too.
        val nonce = freshNonce()

        suspend fun proveWith(current: StepUpKeyMetadata): Map<String, String> {
            val payload = stepUpReenrolProof(
                installationId = identity.installationId,
                stepUpKeyThumbprint = current.thumbprint,
                nonce = base64UrlNoPadding(nonce),
                timestamp = nowSeconds(),
            )
            return linkedMapOf(
                "stepup_key_proof" to payload,
                "stepup_key_signature" to stepUpKeyNative.sign(payload, reason),
            )
        }

        val keyProof = try {
            proveWith(key)
        } catch (error: NativeKeyException) {
            // A key that died with the screen lock is found only when it signs.
            if (!replaceableKey(error) && error.code != "STEPUP_KEY_NOT_FOUND") throw error
            key = newKey(replace = true)
            proveWith(key)
        }
        val body = LinkedHashMap(reauthentication).apply {
            put("stepup_public_key", key.publicKey)
            put("stepup_key_auth", key.authJson)
            putAll(keyProof)
        }
        val proof = com.necessarysdk.signRequest(
            identity = identity,
            method = "POST",
            path = options.reenrolPath,
            bearerToken = session,
            encodedBody = Json.encode(body),
            nonce = nonce,
        )
        val answer = send(proof)
        mutate(generation) { store.setStepUpReenrolPending(false) }
        synchronized(lock) {
            current(generation)
            offeredStepUpKey = key
            unavailableStepUp = null
        }
        return answer
    }

    /** Sends [proof]'s request through the SDK's transport. */
    suspend fun send(proof: RequestProof): Map<String, Any?> = client.sendSigned(proof)

    /**
     * Sets the account's app PIN under the account [session], with the
     * person's own [reauthentication] -- the account's password, say:
     * `mapOf("password" to ...)`.
     */
    suspend fun setAppPin(
        session: String,
        pin: String,
        reauthentication: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val options = appPin
            ?: throw IllegalStateException("The app PIN is off: DeviceTrust(appPin = AppPinOptions()).")
        val generation = synchronized(lock) { generation }
        val identity = loadIdentity()
        checkCurrent(generation)
        val answer = client.setAppPin(
            session = session,
            identity = identity,
            pin = pin,
            reauthentication = reauthentication,
            path = options.path,
        )
        checkCurrent(generation)
        return answer
    }

    /**
     * Asks the person for the location permission, for the location
     * measurements: from a button on the app's own screen, since a scan never
     * asks. Answers `precise`, `approximate` or `none`.
     */
    suspend fun requestLocationPermission(): String = collector.requestLocationPermission()

    /**
     * Closes the transport. Calls still running end with
     * InterruptedCallException, or with the transport's own failure, and keep
     * nothing; the key and the installation stay as they are.
     */
    fun close() {
        synchronized(lock) { endCalls() }
        client.close()
    }
}
