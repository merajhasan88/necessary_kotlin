package com.necessarysdk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The installation across a phone's life: the first start, a restart, a key
 * the platform lost (an Android reinstall), a key kept without the app's other
 * data -- two of the SDK's calls at once, and a reset or a close while calls
 * still run.
 */
class InstallationLifecycleTest {
    /** A platform keystore holding, or not holding, the installation key. */
    open class Keystore(@Volatile var holds: Boolean) : NativeInstallationKey(NoNative) {
        override suspend fun getOrCreateKey(): NativeKeyMetadata {
            val created = !holds
            holds = true
            return NativeKeyMetadata(
                publicKey = mapOf("kty" to "EC", "crv" to "P-256", "alg" to "ES256", "x" to "x", "y" to "y"),
                algorithm = "ES256",
                keyAlias = "test",
                provider = "AndroidKeyStore",
                securityLevel = "trusted_execution_environment",
                hardwareBacked = true,
                privateKeyExportable = false,
                signatureFormat = "der",
                created = created,
            )
        }

        override suspend fun signPayload(payloadBase64Url: String): String = "signed"

        override suspend fun deleteKey() {
            holds = false
        }
    }

    /** A keystore that holds the key but cannot be reached at first: the SDK's Android side then answers KEY_UNAVAILABLE and makes no key. */
    class UnreachableKeystore : Keystore(holds = true) {
        @Volatile var reachable = false

        override suspend fun getOrCreateKey(): NativeKeyMetadata {
            if (!reachable) {
                throw NativeKeyException(
                    "KEY_UNAVAILABLE",
                    "AndroidKeyStore could not be reached for the installation key; the key was kept, never replaced.",
                )
            }
            return super.getOrCreateKey()
        }
    }

    /** Answers as the guide says; counts what it was asked. */
    open class Backend : DeviceTrustTransport {
        val paths: MutableList<String> = CopyOnWriteArrayList()

        override suspend fun send(
            method: String,
            path: String,
            encodedBody: String?,
            bearerToken: String?,
            headers: Map<String, String>,
        ): Map<String, Any?> {
            paths.add(path)
            delay(1)
            return when (path) {
                "/v1/installations/register" -> mapOf("installation_id" to "held")
                "/v1/installations/challenge" -> mapOf("challenge_id" to "k", "payload" to "cA")
                "/v1/installations/verify" -> mapOf("device_token" to "session")
                "/v1/integrity/challenge" -> mapOf(
                    "challenge_id" to "s",
                    "nonce" to "bg",
                    "platform" to "android",
                    "required_probes" to listOf("app_identity"),
                )
                else -> mapOf("integrity" to mapOf("report_id" to "r"))
            }
        }

        override fun close() {}
    }

    /**
     * Answers as [Backend] does, but with a new installation ID and a new
     * session each time, in the order answered; and holds the answer to a path
     * while a test says so.
     */
    class HeldBackend : Backend() {
        private val held = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val asked = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private var registered = 0
        private var proved = 0

        /** The next request to [path] waits for the deferred's completion. */
        fun hold(path: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { held[path] = it }

        /** Completes once [path] has been asked. */
        suspend fun asked(path: String) = asked.getOrPut(path) { CompletableDeferred() }.await()

        override suspend fun send(
            method: String,
            path: String,
            encodedBody: String?,
            bearerToken: String?,
            headers: Map<String, String>,
        ): Map<String, Any?> {
            asked.getOrPut(path) { CompletableDeferred() }.complete(Unit)
            held.remove(path)?.await()
            val answer = super.send(method, path, encodedBody, bearerToken, headers)
            return when (path) {
                "/v1/installations/register" -> mapOf("installation_id" to "held-${synchronized(this) { ++registered }}")
                "/v1/installations/verify" -> mapOf("device_token" to "session-${synchronized(this) { ++proved }}")
                else -> answer
            }
        }
    }

    open class Collector : NativeIntegrityCollector(NoNative) {
        override suspend fun collect(
            requiredProbes: List<String>,
            challengeNonce: String,
            enabledMeasurements: List<String>,
            optionalProbes: List<String>,
        ): Map<String, Any?> = linkedMapOf(
            "platform" to "android",
            "challenge_nonce_echo" to challengeNonce,
            "collector_version" to 8,
            "probes" to mapOf("app_identity" to mapOf("status" to "ok")),
        )
    }

    class NoHint : ReinstallHintReader(NoNative) {
        override suspend fun read(): ReinstallHint? = null
    }

    /** A collector that waits, at its first collection, until the test lets it go on; and notes what each collection was allowed to measure. */
    class HeldCollector : Collector() {
        val reached = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()
        val enabled: MutableList<List<String>> = CopyOnWriteArrayList()

        override suspend fun collect(
            requiredProbes: List<String>,
            challengeNonce: String,
            enabledMeasurements: List<String>,
            optionalProbes: List<String>,
        ): Map<String, Any?> {
            enabled.add(enabledMeasurements)
            reached.complete(Unit)
            goOn.await()
            return super.collect(requiredProbes, challengeNonce, enabledMeasurements, optionalProbes)
        }
    }

    /** A store that counts its loads, each taking a moment. */
    class CountingStore(storage: SecureStorage) : InstallationStore(Keystore(holds = false), storage) {
        @Volatile var loads = 0

        override suspend fun loadOrCreate(): InstallationIdentity {
            loads++
            delay(5)
            return super.loadOrCreate()
        }
    }

    /** A keystore whose signatures wait, once [hold] is set, until [goOn]. */
    class HeldSigningKeystore : Keystore(holds = false) {
        @Volatile var hold = false
        val reached = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()

        override suspend fun signPayload(payloadBase64Url: String): String {
            if (hold) {
                reached.complete(Unit)
                goOn.await()
            }
            return super.signPayload(payloadBase64Url)
        }
    }

    /** A store whose first step-up marker write waits until [goOn]. */
    class HeldMarkerStore(storage: SecureStorage) : InstallationStore(Keystore(holds = false), storage) {
        val reached = CompletableDeferred<Unit>()
        val goOn = CompletableDeferred<Unit>()

        override suspend fun setStepUpReenrolPending(pending: Boolean) {
            if (reached.complete(Unit)) goOn.await()
            super.setStepUpReenrolPending(pending)
        }
    }

    /** A phone that makes no step-up key; or, given [deleteFails], one whose key the keystore will not delete. */
    class StepUpKeystore(private val makes: Boolean = false, private val deleteFails: String? = null) :
        NativeStepUpKey(NoNative) {
        override suspend fun getOrCreateKey(factor: String, mode: String, windowSeconds: Int): StepUpKeyMetadata {
            if (!makes) throw NativeKeyException("STEPUP_UNAVAILABLE", "no screen lock")
            return StepUpKeyMetadata(
                publicKey = mapOf("kty" to "EC", "crv" to "P-256", "alg" to "ES256", "x" to "sx", "y" to "sy"),
                factor = factor,
                mode = mode,
                windowSeconds = windowSeconds,
                securityLevel = "trusted_execution_environment",
                hardwareBacked = true,
                created = true,
            )
        }

        override suspend fun deleteKey() {
            if (deleteFails != null) throw NativeKeyException(deleteFails, "the keystore refused")
        }
    }

    private val stored = "recognition.installation_id.v2"
    private val storage = MemoryStorage()

    private fun storedId(): String? = storage.values[stored]

    private fun trust(
        backend: DeviceTrustTransport,
        store: InstallationStore,
        collector: NativeIntegrityCollector = Collector(),
        stepUp: StepUpOptions? = null,
        stepUpKey: NativeStepUpKey = StepUpKeystore(),
    ) = DeviceTrust(
        transport = backend,
        store = store,
        collector = collector,
        hintReader = NoHint(),
        stepUpKeyNative = stepUpKey,
        stepUp = stepUp,
    )

    private fun store(key: NativeInstallationKey) = InstallationStore(key, storage)

    private suspend fun expectInterrupted(block: suspend () -> Any?) {
        expectThrows<InterruptedCallException> { block() }
    }

    @Test
    fun theFirstStartMakesTheKeyAndAnInstallationIdARestartFindsBoth() = runBlocking {
        val keystore = Keystore(holds = false)
        val first = store(keystore).loadOrCreate()
        assertTrue(first.createdThisLaunch)
        assertEquals(first.installationId, storedId())
        val restarted = store(keystore).loadOrCreate()
        assertFalse(restarted.createdThisLaunch)
        assertEquals(first.installationId, restarted.installationId)
    }

    @Test
    fun aKeyThePlatformLostIsANewInstallationUnderANewId() = runBlocking {
        storage.values[stored] = "old-installation"
        val identity = store(Keystore(holds = false)).loadOrCreate()
        assertTrue(identity.createdThisLaunch)
        assertNotEquals("old-installation", identity.installationId)
        assertEquals(identity.installationId, storedId())
    }

    @Test
    fun aKeyKeptWithoutItsIdProposesANewOneAndKeepsTheOneTheBackendHolds() = runBlocking {
        val store = store(Keystore(holds = true))
        val proposed = store.loadOrCreate()
        assertFalse(proposed.createdThisLaunch)
        val trust = trust(Backend(), store)
        trust.start()
        assertEquals("held", trust.identity!!.installationId)
        assertEquals("held", storedId())
    }

    @Test
    fun aKeystoreThatCannotBeReachedLeavesTheInstallationAsItWas() = runBlocking {
        storage.values[stored] = "kept-installation"
        val keystore = UnreachableKeystore()
        val backend = Backend()
        val trust = trust(backend, store(keystore))
        assertEquals("KEY_UNAVAILABLE", expectThrows<NativeKeyException> { trust.start() }.code)
        assertTrue(backend.paths.isEmpty())
        assertEquals("kept-installation", storedId())
        assertTrue(keystore.holds)
        keystore.reachable = true
        val again = store(keystore).loadOrCreate()
        assertFalse(again.createdThisLaunch)
        assertEquals("kept-installation", again.installationId)
    }

    @Test
    fun resetDeletesTheKeyAndTheIdTheNextStartIsANewInstallation() = runBlocking {
        val keystore = Keystore(holds = false)
        val trust = trust(Backend(), store(keystore))
        trust.start()
        trust.reset()
        assertFalse(keystore.holds)
        assertNull(storedId())
        assertNull(trust.identity)
        assertNull(trust.installationSession)
        assertTrue(trust.loadIdentity().createdThisLaunch)
    }

    @Test
    fun twoCallsOfOneKindAtOnceShareOneRunOtherScansRunApart() = runBlocking {
        val backend = Backend()
        val trust = trust(backend, store(Keystore(holds = false)))
        val started = listOf(async { trust.start() }, async { trust.start() }).awaitAll()
        assertSame(started[0], started[1])
        assertEquals(1, backend.paths.count { it == "/v1/installations/register" })
        assertEquals(1, backend.paths.count { it == "/v1/installations/verify" })

        backend.paths.clear()
        val pay = describeOperation("POST", "/v1/pay", "{}")
        val payee = describeOperation("POST", "/v1/payee", "{}")
        val scans = listOf(
            async { trust.scan() },
            async { trust.scan() },
            async { trust.scan(session = "account", operation = pay) },
            async { trust.scan(session = "account", operation = payee) },
        ).awaitAll()
        assertSame(scans[0], scans[1])
        assertEquals(3, backend.paths.count { it == "/v1/integrity/challenge" })
        assertEquals(3, backend.paths.count { it == "/v1/integrity/report" })
    }

    @Test
    fun aResetWhileStartWaitsForItsKeyChallengesAnswerKeepsTheOldAnswerNowhere() = runBlocking {
        val backend = HeldBackend()
        val trust = trust(backend, store(Keystore(holds = false)))
        val verify = backend.hold("/v1/installations/verify")
        val old = async { expectInterrupted { trust.start() } }
        backend.asked("/v1/installations/verify")
        trust.reset()
        val fresh = trust.start()
        assertEquals("session-1", trust.installationSession)
        verify.complete(Unit)
        old.await()
        assertEquals("session-1", trust.installationSession)
        assertEquals(fresh.installationId, trust.identity!!.installationId)
        assertEquals(fresh.installationId, storedId())
    }

    @Test
    fun aResetWhileStartWaitsForItsRegistrationNeverKeepsTheIdTheOldAnswerNames() = runBlocking {
        val backend = HeldBackend()
        val trust = trust(backend, store(Keystore(holds = false)))
        val register = backend.hold("/v1/installations/register")
        val old = async { expectInterrupted { trust.start() } }
        backend.asked("/v1/installations/register")
        trust.reset()
        val fresh = trust.start()
        assertEquals("held-1", fresh.installationId)
        register.complete(Unit)
        old.await()
        assertEquals("held-1", trust.identity!!.installationId)
        assertEquals("held-1", storedId())
    }

    @Test
    fun aResetWhileTheOldRegistrationWritesTheStepUpMarkerWaitsForTheWrite() = runBlocking {
        val store = HeldMarkerStore(storage)
        val trust = trust(Backend(), store, stepUp = StepUpOptions(), stepUpKey = StepUpKeystore())
        val old = async { expectInterrupted { trust.start() } }
        store.reached.await()
        var reset = false
        val resetting = async {
            trust.reset()
            reset = true
        }
        delay(50)
        assertFalse("the reset waits for the write in flight", reset)
        store.goOn.complete(Unit)
        resetting.await()
        old.await()
        assertFalse(store.stepUpReenrolPending())
    }

    @Test
    fun aResetWhileARequestIsSignedNeverHandsTheProofOut() = runBlocking {
        val key = HeldSigningKeystore()
        val trust = trust(Backend(), store(key))
        trust.start()
        key.hold = true
        val signing = async { expectInterrupted { trust.signRequest(method = "POST", path = "/v1/pay", bearer = "session") } }
        key.reached.await()
        trust.reset()
        key.goOn.complete(Unit)
        signing.await()
    }

    @Test
    fun aStepUpKeyTheKeystoreWillNotDeleteFailsTheResetNoneToDeleteFailsNothing() = runBlocking {
        val trust = trust(
            Backend(),
            store(Keystore(holds = false)),
            stepUp = StepUpOptions(),
            stepUpKey = StepUpKeystore(makes = true, deleteFails = "KEY_DELETE_FAILED"),
        )
        trust.start()
        assertEquals("KEY_DELETE_FAILED", expectThrows<NativeKeyException> { trust.reset() }.code)
        assertNull(trust.identity)
        assertNull(storedId())
        // A phone with no step-up key at all: nothing to delete, nothing fails.
        val plain = trust(
            Backend(),
            store(Keystore(holds = false)),
            stepUp = StepUpOptions(),
            stepUpKey = StepUpKeystore(deleteFails = "UNSUPPORTED_ANDROID_VERSION"),
        )
        plain.start()
        plain.reset()
    }

    @Test
    fun aResetWhileAScanCollectsNeverSendsItsReport() = runBlocking {
        val backend = Backend()
        val collector = HeldCollector()
        val trust = trust(backend, store(Keystore(holds = false)), collector = collector)
        trust.start()
        val scan = async { expectInterrupted { trust.scan() } }
        collector.reached.await()
        trust.reset()
        collector.goOn.complete(Unit)
        scan.await()
        assertFalse("/v1/integrity/report" in backend.paths)
    }

    @Test
    fun closeWhileStartRunsTheCallKeepsNothing() = runBlocking {
        val backend = HeldBackend()
        val trust = trust(backend, store(Keystore(holds = false)))
        val verify = backend.hold("/v1/installations/verify")
        val started = async { expectInterrupted { trust.start() } }
        backend.asked("/v1/installations/verify")
        trust.close()
        verify.complete(Unit)
        started.await()
        assertNull(trust.installationSession)
    }

    @Test
    fun theStoreItselfAskedTwiceAtOnceMakesOneIdForOneKey() = runBlocking {
        val store = store(Keystore(holds = false))
        val loaded = listOf(async { store.loadOrCreate() }, async { store.loadOrCreate() }).awaitAll()
        assertEquals(loaded[0].installationId, loaded[1].installationId)
        assertEquals(loaded[0].installationId, storedId())
    }

    @Test
    fun twoFirstLoadsAtOnceLoadOnce() = runBlocking {
        val store = CountingStore(storage)
        val trust = trust(Backend(), store)
        val loaded = listOf(async { trust.loadIdentity() }, async { trust.loadIdentity() }).awaitAll()
        assertSame(loaded[0], loaded[1])
        assertEquals(1, store.loads)
        assertEquals(loaded[0].installationId, storedId())
    }

    @Test
    fun aScanAskedAfterThePersonalMeasurementsChangedNeverJoinsOneAskedBefore() = runBlocking {
        val backend = Backend()
        val collector = HeldCollector()
        val trust = trust(backend, store(Keystore(holds = false)), collector = collector)
        trust.start()
        val before = async { trust.scan() }
        collector.reached.await()
        trust.personalMeasurements = setOf(PersonalMeasurement.LOCATION)
        val after = async { trust.scan() }
        delay(20)
        collector.goOn.complete(Unit)
        val scans = listOf(before, after).awaitAll()
        assertNotSame(scans[0], scans[1])
        assertEquals(listOf(emptyList(), listOf("location")), collector.enabled)
        assertEquals(listOf("location"), scans[1].report.report["enabled_measurements"])
    }
}
