package com.necessarysdk

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceTrust against a backend that answers as the public guide says, with
 * the phone's native side replaced: what it sends, under which session, and
 * what it does about each of the guide's protocol errors.
 */
class DeviceTrustTest {
    class Call(
        val method: String,
        val path: String,
        val body: Map<String, Any?>,
        val bearer: String?,
        val headers: Map<String, String>,
    )

    /** Answers each path from its queue, the last answer repeating; an ApiException in the queue is thrown. */
    open class Backend(answers: Map<String, List<Any>> = emptyMap()) : DeviceTrustTransport {
        val answers: MutableMap<String, MutableList<Any>> = linkedMapOf(
            "/v1/installations/register" to mutableListOf<Any>(mapOf("installation_id" to "proposed-id")),
            "/v1/installations/challenge" to mutableListOf<Any>(mapOf("challenge_id" to "key-challenge", "payload" to "cGF5")),
            "/v1/installations/verify" to mutableListOf<Any>(mapOf("device_token" to "installation-session")),
            "/v1/integrity/challenge" to mutableListOf<Any>(
                mapOf(
                    "challenge_id" to "scan-challenge",
                    "nonce" to "bm9uY2U",
                    "platform" to "android",
                    "required_probes" to listOf("app_identity"),
                    "optional_probes" to listOf("location"),
                ),
            ),
            "/v1/integrity/report" to mutableListOf<Any>(mapOf("integrity" to mapOf("report_id" to "report-1"))),
            "/v1/account/app-pin" to mutableListOf<Any>(mapOf("app_pin_set" to true)),
            "/v1/account/app-pin/proof" to mutableListOf<Any>(mapOf("app_pin_proof" to "pin-proof")),
            "/v1/installations/stepup-key" to mutableListOf<Any>(mapOf("stepup_key_registered" to true)),
        ).also { defaults -> answers.forEach { (path, queue) -> defaults[path] = queue.toMutableList() } }

        val calls: MutableList<Call> = CopyOnWriteArrayList()
        val paths: List<String> get() = calls.map { it.path }

        override suspend fun send(
            method: String,
            path: String,
            encodedBody: String?,
            bearerToken: String?,
            headers: Map<String, String>,
        ): Map<String, Any?> {
            calls.add(
                Call(
                    method,
                    path,
                    if (encodedBody.isNullOrEmpty()) emptyMap() else Json.decodeObject(encodedBody),
                    bearerToken,
                    headers,
                ),
            )
            val queue = synchronized(answers) { answers[path]!! }
            val next = synchronized(queue) { if (queue.size > 1) queue.removeAt(0) else queue.first() }
            if (next is ApiException) throw next
            @Suppress("UNCHECKED_CAST")
            return LinkedHashMap(next as Map<String, Any?>)
        }

        override fun close() {}
    }

    /** Answers as [Backend] does, but holds the answer to [held] until [gate] completes. */
    class HeldBackend(private val held: String, private val gate: CompletableDeferred<Unit>) : Backend() {
        val asked = CompletableDeferred<Unit>()

        override suspend fun send(
            method: String,
            path: String,
            encodedBody: String?,
            bearerToken: String?,
            headers: Map<String, String>,
        ): Map<String, Any?> {
            if (path == held) {
                asked.complete(Unit)
                gate.await()
            }
            return super.send(method, path, encodedBody, bearerToken, headers)
        }
    }

    companion object {
        fun metadata(created: Boolean = false) = NativeKeyMetadata(
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

        fun refusal(code: String, status: Int, details: Map<String, Any?>? = null) =
            ApiException(code, statusCode = status, code = code, details = details)
    }

    class Key : NativeInstallationKey(NoNative) {
        override suspend fun getOrCreateKey(): NativeKeyMetadata = metadata()
        override suspend fun signPayload(payloadBase64Url: String): String = "signed"
        override suspend fun deleteKey() {}
    }

    class Store : InstallationStore(Key(), MemoryStorage()) {
        @Volatile var saved: String? = null
        @Volatile var reenrolPending = false

        override suspend fun loadOrCreate(): InstallationIdentity =
            InstallationIdentity(installationId = "proposed-id", nativeKey = key, metadata = metadata())

        override suspend fun saveInstallationId(installationId: String) {
            saved = installationId
        }

        override suspend fun stepUpReenrolPending(): Boolean = reenrolPending

        override suspend fun setStepUpReenrolPending(pending: Boolean) {
            reenrolPending = pending
        }

        override suspend fun clear() {
            saved = null
            reenrolPending = false
        }
    }

    class Collector : NativeIntegrityCollector(NoNative) {
        @Volatile var enabled: List<String>? = null

        override suspend fun collect(
            requiredProbes: List<String>,
            challengeNonce: String,
            enabledMeasurements: List<String>,
            optionalProbes: List<String>,
        ): Map<String, Any?> {
            enabled = enabledMeasurements
            return linkedMapOf(
                "platform" to "android",
                "challenge_nonce_echo" to challengeNonce,
                "collector_version" to 8,
                "probes" to requiredProbes.associateWith { mapOf("status" to "ok") },
            )
        }
    }

    class Hints : ReinstallHintReader(NoNative) {
        override suspend fun read(): ReinstallHint = ReinstallHint(kind = "android_id_sha256", value = "hint")
    }

    open class StepUpKey(private val refuse: NativeKeyException? = null) : NativeStepUpKey(NoNative) {
        @Volatile var reason: String? = null

        override suspend fun getOrCreateKey(factor: String, mode: String, windowSeconds: Int): StepUpKeyMetadata {
            if (refuse != null) throw refuse
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

        override suspend fun sign(payloadBase64Url: String, reason: String): String {
            this.reason = reason
            return "approved"
        }

        override suspend fun deleteKey() {}
    }

    /** A key that died with the screen lock: its first signature fails, and a new one is made once it is deleted. */
    open class DyingStepUpKey : StepUpKey() {
        @Volatile var deleted = 0

        override suspend fun sign(payloadBase64Url: String, reason: String): String {
            if (deleted == 0) throw NativeKeyException("STEPUP_KEY_INVALIDATED", "the lock was removed")
            return super.sign(payloadBase64Url, reason)
        }

        override suspend fun deleteKey() {
            deleted += 1
        }
    }

    /** A key the keystore cannot vouch for (STEPUP_KEY_UNAVAILABLE) until it is replaced. */
    class UnavailableStepUpKey : StepUpKey() {
        @Volatile var deleted = 0

        override suspend fun getOrCreateKey(factor: String, mode: String, windowSeconds: Int): StepUpKeyMetadata {
            if (deleted == 0) {
                throw NativeKeyException("STEPUP_KEY_UNAVAILABLE", "the keystore could not say whether the key is usable")
            }
            return super.getOrCreateKey(factor, mode, windowSeconds)
        }

        override suspend fun deleteKey() {
            deleted += 1
        }
    }

    /** A dying key whose first prompt after its replacement is cancelled. */
    class CancelledStepUpKey : DyingStepUpKey() {
        @Volatile var cancelled = false

        override suspend fun sign(payloadBase64Url: String, reason: String): String {
            if (deleted > 0 && !cancelled) {
                cancelled = true
                throw NativeKeyException("STEPUP_CANCELLED", "the person cancelled")
            }
            return super.sign(payloadBase64Url, reason)
        }
    }

    /** Refuses the first key it is asked for, then makes one. */
    class FlakyStepUpKey : StepUpKey() {
        @Volatile var attempts = 0

        override suspend fun getOrCreateKey(factor: String, mode: String, windowSeconds: Int): StepUpKeyMetadata {
            attempts += 1
            if (attempts == 1) throw NativeKeyException("STEPUP_NO_DEVICE_CREDENTIAL", "no screen lock yet")
            return super.getOrCreateKey(factor, mode, windowSeconds)
        }
    }

    private fun trust(
        backend: Backend,
        store: Store? = null,
        collector: Collector? = null,
        stepUpKey: StepUpKey? = null,
        personal: Set<PersonalMeasurement> = emptySet(),
        stepUp: StepUpOptions? = null,
        appPin: AppPinOptions? = null,
        onRegistered: ((Map<String, Any?>) -> Unit)? = null,
    ) = DeviceTrust(
        transport = backend,
        store = store ?: Store(),
        collector = collector ?: Collector(),
        hintReader = Hints(),
        stepUpKeyNative = stepUpKey ?: StepUpKey(),
        personalMeasurements = personal,
        stepUp = stepUp,
        appPin = appPin,
        onRegistered = onRegistered?.let { record -> { answer -> record(answer) } },
    )

    /** One approval with the step-up key, for a payment. */
    private suspend fun approve(trust: DeviceTrust) =
        trust.signRequest(method = "POST", path = "/v1/pay", bearer = "account-session", stepUp = true)

    private suspend fun expectReenrolPending(block: suspend () -> Any?) =
        assertEquals("STEPUP_REENROL_PENDING", expectThrows<NativeKeyException> { block() }.code)

    @Test
    fun startRegistersTheKeyWithTheStepUpKeyAndProvesIt() = runBlocking {
        val backend = Backend()
        val trust = trust(backend, stepUp = StepUpOptions())
        trust.start()
        assertEquals(
            listOf("/v1/installations/register", "/v1/installations/challenge", "/v1/installations/verify"),
            backend.paths,
        )
        val registration = backend.calls.first().body
        assertEquals("proposed-id", registration["installation_id"])
        assertEquals("android", registration["platform"])
        assertEquals("EC", registration["public_key"].asObject()!!["kty"])
        assertEquals(mapOf("kind" to "android_id_sha256", "value" to "hint"), registration["reinstall_hint"])
        assertEquals(true, registration["key_security"].asObject()!!["hardware_backed"])
        assertEquals("sx", registration["stepup_public_key"].asObject()!!["x"])
        assertEquals(
            mapOf("factor" to "passcode", "mode" to "per_use", "window_seconds" to 0L),
            registration["stepup_key_auth"],
        )
        assertEquals("signed", backend.calls[2].body["signature"])
        assertEquals("installation-session", trust.installationSession)
    }

    @Test
    fun theInstallationTheBackendHoldsForTheKeyIsKept() = runBlocking {
        val store = Store()
        val answers = CopyOnWriteArrayList<Map<String, Any?>>()
        val backend = Backend(
            mapOf("/v1/installations/register" to listOf(mapOf("installation_id" to "held-id", "more" to 1))),
        )
        val trust = trust(backend, store = store, onRegistered = { answers.add(it) })
        trust.start()
        assertEquals("held-id", trust.identity!!.installationId)
        assertEquals("held-id", store.saved)
        assertEquals("held-id", backend.calls[1].body["installation_id"])
        assertEquals(1, answers.single()["more"])
    }

    @Test
    fun aScanUnderTheInstallationSessionTheEnabledMeasurementsInTheGuidesOrder() = runBlocking {
        val backend = Backend()
        val collector = Collector()
        val trust = trust(
            backend,
            collector = collector,
            personal = setOf(PersonalMeasurement.MOCK_LOCATION, PersonalMeasurement.LOCAL_ADDRESSES),
        )
        trust.start()
        val scan = trust.scan()
        assertEquals(listOf("local_addresses", "mock_location"), collector.enabled)
        val challenge = backend.calls[3]
        assertEquals("/v1/integrity/challenge", challenge.path)
        assertEquals("installation-session", challenge.bearer)
        assertTrue(challenge.body.isEmpty())
        assertTrue("X-Access-Proof" in challenge.headers)
        val report = backend.calls[4]
        assertEquals("installation-session", report.bearer)
        val signed = decoded(report.body["report_payload"] as String)
        assertEquals("scan-challenge", signed["challenge_id"])
        assertEquals(listOf("local_addresses", "mock_location"), signed["enabled_measurements"])
        assertEquals("report-1", scan.reportId)
    }

    @Test
    fun thePersonalMeasurementsChangeWithTheAppTheInstallationAndItsStepUpKeyStay() = runBlocking {
        val backend = Backend()
        val collector = Collector()
        val trust = trust(backend, collector = collector, stepUp = StepUpOptions())
        trust.start()
        trust.scan()
        assertEquals(emptyList<String>(), collector.enabled)
        trust.personalMeasurements = setOf(PersonalMeasurement.LOCATION, PersonalMeasurement.ACCESSIBILITY)
        trust.scan()
        assertEquals(listOf("accessibility", "location"), collector.enabled)
        assertNotNull(trust.stepUpKey)
        assertEquals(3, backend.paths.count { it.startsWith("/v1/installations") })
    }

    @Test
    fun reenrolmentADeadKeyReplacedItsProofBoundToTheRequestProofsNonce() = runBlocking {
        val backend = Backend()
        val key = DyingStepUpKey()
        val trust = trust(backend, stepUpKey = key, stepUp = StepUpOptions())
        trust.start()
        assertEquals(
            true,
            trust.reenrolStepUp(session = "account-session", reauthentication = mapOf("password" to "pw"))["stepup_key_registered"],
        )
        assertEquals(1, key.deleted)
        val call = backend.calls.last()
        assertEquals("/v1/installations/stepup-key", call.path)
        assertEquals("account-session", call.bearer)
        assertEquals("pw", call.body["password"])
        assertEquals("sx", call.body["stepup_public_key"].asObject()!!["x"])
        assertEquals("passcode", call.body["stepup_key_auth"].asObject()!!["factor"])
        assertEquals("approved", call.body["stepup_key_signature"])
        val proof = decoded(call.body["stepup_key_proof"] as String)
        assertEquals(1L, proof["version"])
        assertEquals("stepup_reenrol", proof["purpose"])
        assertEquals("proposed-id", proof["installation_id"])
        assertEquals(decoded(call.headers["X-Access-Proof"]!!)["nonce"], proof["nonce"])
        val approval = trust.signRequest(method = "POST", path = "/v1/pay", bearer = "account-session", stepUp = true)
        assertTrue("X-Step-Up-Proof" in approval.headers)
    }

    @Test
    fun aStepUpKeyTheKeystoreCannotVouchForIsKeptAtStartAndReplacedOnlyByAReenrolment() = runBlocking {
        val backend = Backend()
        val key = UnavailableStepUpKey()
        val trust = trust(backend, stepUpKey = key, stepUp = StepUpOptions())
        trust.start()
        assertEquals("STEPUP_KEY_UNAVAILABLE", trust.stepUpUnavailable?.code)
        assertEquals(0, key.deleted)
        assertEquals(
            true,
            trust.reenrolStepUp(session = "account-session", reauthentication = mapOf("password" to "pw"))["stepup_key_registered"],
        )
        assertEquals(1, key.deleted)
        assertNull(trust.stepUpUnavailable)
    }

    @Test
    fun aReenrolmentTheBackendRefusedLeavesApprovalsWaitingAcrossARestartTooUntilASecondSucceeds() = runBlocking {
        val backend = Backend(
            mapOf(
                "/v1/installations/stepup-key" to listOf(
                    refusal("reauthentication_failed", 401),
                    mapOf("stepup_key_registered" to true),
                ),
            ),
        )
        val store = Store()
        val key = DyingStepUpKey()
        fun started() = trust(backend, store = store, stepUpKey = key, stepUp = StepUpOptions())
        val trust = started()
        trust.start()
        expectThrows<ApiException> {
            trust.reenrolStepUp(session = "account-session", reauthentication = mapOf("password" to "wrong"))
        }
        // The dead key is gone and the new one not taken: approvals wait.
        expectReenrolPending { approve(trust) }
        assertEquals("STEPUP_REENROL_PENDING", trust.stepUpUnavailable?.code)
        val restarted = started()
        restarted.start()
        expectReenrolPending { approve(restarted) }
        assertEquals(
            true,
            restarted.reenrolStepUp(session = "account-session", reauthentication = mapOf("password" to "pw"))["stepup_key_registered"],
        )
        assertFalse(store.reenrolPending)
        assertNull(restarted.stepUpUnavailable)
        assertTrue("X-Step-Up-Proof" in approve(restarted).headers)
    }

    @Test
    fun aReenrolmentWhosePromptWasCancelledThenWhoseAnswerWasLostIsFinishedByTheNextOne() = runBlocking {
        val backend = Backend(
            mapOf(
                "/v1/installations/stepup-key" to listOf(
                    refusal("bad_gateway", 502),
                    mapOf("stepup_key_registered" to true),
                ),
            ),
        )
        val store = Store()
        val trust = trust(backend, store = store, stepUpKey = CancelledStepUpKey(), stepUp = StepUpOptions())
        trust.start()
        suspend fun reenrol() =
            trust.reenrolStepUp(session = "account-session", reauthentication = mapOf("password" to "pw"))
        assertEquals("STEPUP_CANCELLED", expectThrows<NativeKeyException> { reenrol() }.code)
        expectReenrolPending { approve(trust) }
        expectThrows<ApiException> { reenrol() }
        expectReenrolPending { approve(trust) }
        assertTrue(store.reenrolPending)
        assertEquals(true, reenrol()["stepup_key_registered"])
        assertFalse(store.reenrolPending)
        assertTrue("X-Step-Up-Proof" in approve(trust).headers)
    }

    @Test
    fun aBackendThatDoesNotHoldTheOfferedKeyLeavesApprovalsWaitingOneThatDoesClearsThat() = runBlocking {
        val backend = Backend(
            mapOf(
                "/v1/installations/register" to listOf(
                    mapOf("installation_id" to "proposed-id", "stepup_key_registered" to false, "stepup_key_matches" to false),
                    mapOf("installation_id" to "proposed-id", "stepup_key_registered" to true, "stepup_key_matches" to true),
                ),
            ),
        )
        val store = Store()
        val trust = trust(backend, store = store, stepUp = StepUpOptions())
        trust.start()
        assertEquals("STEPUP_REENROL_PENDING", trust.stepUpUnavailable?.code)
        expectReenrolPending { approve(trust) }
        assertTrue(store.reenrolPending)
        trust.start()
        assertNull(trust.stepUpUnavailable)
        assertFalse(store.reenrolPending)
        assertTrue("X-Step-Up-Proof" in approve(trust).headers)
    }

    @Test
    fun aStepUpKeyMadeAtTheNextStartWaitsForReenrolmentEvenWhereTheBackendSaysNothing() = runBlocking {
        val backend = Backend()
        val store = Store()
        val trust = trust(backend, store = store, stepUp = StepUpOptions(), stepUpKey = FlakyStepUpKey())
        trust.start()
        assertEquals("STEPUP_NO_DEVICE_CREDENTIAL", trust.stepUpUnavailable?.code)
        trust.start()
        assertEquals("STEPUP_REENROL_PENDING", trust.stepUpUnavailable?.code)
        expectReenrolPending { approve(trust) }
        assertEquals(
            true,
            trust.reenrolStepUp(session = "account-session", reauthentication = mapOf("password" to "pw"))["stepup_key_registered"],
        )
        assertTrue("X-Step-Up-Proof" in approve(trust).headers)
    }

    @Test
    fun resetDeletesTheStepUpKeyWithTheInstallation() = runBlocking {
        val key = DyingStepUpKey()
        val trust = trust(Backend(), stepUpKey = key, stepUp = StepUpOptions())
        trust.start()
        trust.reset()
        assertEquals(1, key.deleted)
        assertNull(trust.stepUpKey)
    }

    @Test
    fun aResetWhileAReenrolmentWaitsForItsAnswerWaitsForItThenDeletesItsKeyAndItsMarker() = runBlocking {
        val answer = CompletableDeferred<Unit>()
        val backend = HeldBackend("/v1/installations/stepup-key", answer)
        val store = Store()
        val key = DyingStepUpKey()
        val trust = trust(backend, store = store, stepUpKey = key, stepUp = StepUpOptions())
        trust.start()
        val reenrolled = async {
            expectThrows<InterruptedCallException> {
                trust.reenrolStepUp(session = "account-session", reauthentication = mapOf("password" to "pw"))
            }
        }
        backend.asked.await()
        assertTrue(store.reenrolPending)
        assertEquals(1, key.deleted)
        var reset = false
        val resetting = async {
            trust.reset()
            reset = true
        }
        delay(50)
        assertFalse("the reset waits for the re-enrolment", reset)
        answer.complete(Unit)
        reenrolled.await()
        resetting.await()
        assertFalse(store.reenrolPending)
        assertEquals("the re-enrolled key deleted after it", 2, key.deleted)
    }

    @Test
    fun reenrolmentWithStepUpOffRefusesBeforeSending() = runBlocking {
        val backend = Backend()
        val trust = trust(backend)
        trust.start()
        assertEquals("STEPUP_OFF", expectThrows<NativeKeyException> { trust.reenrolStepUp(session = "account-session") }.code)
        assertFalse("/v1/installations/stepup-key" in backend.paths)
    }

    @Test
    fun aScanForOneOperationIsAskedUnderTheAccountSession() = runBlocking {
        val backend = Backend()
        val trust = trust(backend)
        trust.start()
        val operation = describeOperation("POST", "/v1/pay", "{\"amount\":5}")
        expectThrows<IllegalArgumentException> { trust.scan(operation = operation) }
        trust.scan(session = "account-session", operation = operation)
        val challenge = backend.calls[3]
        assertEquals("account-session", challenge.bearer)
        assertEquals(operation, challenge.body["operation"])
        assertEquals("account-session", backend.calls[4].bearer)
    }

    @Test
    fun anExpiredInstallationSessionIsProvedAgainOnceAnExpiredAccountSessionGoesToTheApp() = runBlocking {
        val backend = Backend(
            mapOf(
                "/v1/integrity/challenge" to listOf(
                    refusal("expired_token", 401),
                    mapOf(
                        "challenge_id" to "scan-challenge",
                        "nonce" to "bm9uY2U",
                        "platform" to "android",
                        "required_probes" to listOf("app_identity"),
                    ),
                ),
            ),
        )
        val trust = trust(backend)
        trust.start()
        trust.scan()
        assertEquals(
            listOf(
                "/v1/integrity/challenge",
                "/v1/installations/challenge",
                "/v1/installations/verify",
                "/v1/integrity/challenge",
                "/v1/integrity/report",
            ),
            backend.paths.subList(3, backend.paths.size),
        )

        val account = Backend(mapOf("/v1/integrity/challenge" to listOf(refusal("expired_token", 401))))
        val other = trust(account)
        other.start()
        assertEquals(
            "expired_token",
            expectThrows<ApiException> { other.scan(session = "account-session", operation = emptyMap()) }.code,
        )
        assertEquals(1, account.paths.count { it == "/v1/installations/verify" })
    }

    @Test
    fun aLostInstallationRegistersAgainUnderTheInstallationSessionNeverUnderAnAccountSession() = runBlocking {
        val backend = Backend(
            mapOf(
                "/v1/integrity/challenge" to listOf(
                    refusal("installation_not_found", 404),
                    mapOf(
                        "challenge_id" to "scan-challenge",
                        "nonce" to "bm9uY2U",
                        "platform" to "android",
                        "required_probes" to listOf("app_identity"),
                    ),
                ),
            ),
        )
        val trust = trust(backend)
        trust.start()
        trust.scan()
        assertEquals(
            listOf(
                "/v1/integrity/challenge",
                "/v1/installations/register",
                "/v1/installations/challenge",
                "/v1/installations/verify",
            ),
            backend.paths.subList(3, 7),
        )

        val account = Backend(mapOf("/v1/integrity/challenge" to listOf(refusal("installation_not_found", 404))))
        val other = trust(account)
        other.start()
        expectThrows<ApiException> { other.scan(session = "account-session", operation = emptyMap()) }
        assertEquals(1, account.paths.count { it == "/v1/installations/register" })
    }

    @Test
    fun aShortWaitIsWaitedOutOnceALongOneGoesToTheApp() = runBlocking {
        val backend = Backend(
            mapOf(
                "/v1/integrity/report" to listOf(
                    refusal("too_many", 429, mapOf("retry_after_seconds" to 0)),
                    mapOf("integrity" to mapOf("report_id" to "report-2")),
                ),
            ),
        )
        val trust = trust(backend)
        trust.start()
        assertEquals("report-2", trust.scan().reportId)

        val slow = Backend(
            mapOf("/v1/integrity/report" to listOf(refusal("too_many", 429, mapOf("retry_after_seconds" to 30)))),
        )
        val other = trust(slow)
        other.start()
        expectThrows<ApiException> { other.scan() }
        assertEquals(1, slow.paths.count { it == "/v1/integrity/report" })
    }

    @Test
    fun aRefusalOutsideTheGuideGoesToTheAppAsItCame() = runBlocking {
        val backend = Backend(mapOf("/v1/installations/register" to listOf(refusal("try_again_later", 409))))
        assertEquals("try_again_later", expectThrows<ApiException> { trust(backend).start() }.code)
        assertEquals(listOf("/v1/installations/register"), backend.paths)
    }

    @Test
    fun aSignedRequestThePersonApprovesFirstTheRequestProofAfterOnTheSameNonce() = runBlocking {
        val backend = Backend(mapOf("/v1/pay" to listOf(mapOf("paid" to true))))
        val key = StepUpKey()
        val trust = trust(backend, stepUpKey = key, stepUp = StepUpOptions(), appPin = AppPinOptions())
        trust.start()
        val proof = trust.signRequest(
            method = "post",
            path = "/v1/pay",
            bearer = "account-session",
            body = "{\"amount\":5}",
            stepUp = true,
            reason = "Approve the payment",
            appPin = "246810",
            reportId = "report-1",
        )
        val access = decoded(proof.headers["X-Access-Proof"]!!)
        val stepUp = decoded(proof.headers["X-Step-Up-Proof"]!!)
        assertEquals(access["nonce"], stepUp["nonce"])
        assertEquals("POST", stepUp["method"])
        assertEquals("passcode", stepUp["factor"])
        assertEquals("approved", proof.headers["X-Step-Up-Signature"])
        assertEquals("Approve the payment", key.reason)
        assertEquals("pin-proof", proof.headers["X-App-PIN-Proof"])
        assertEquals("report-1", proof.headers["X-Integrity-Report"])
        val pin = backend.calls.last()
        assertEquals("/v1/account/app-pin/proof", pin.path)
        assertEquals("account-session", pin.bearer)
        assertEquals(
            mapOf(
                "pin" to "246810",
                "request" to mapOf(
                    "method" to "POST",
                    "path" to "/v1/pay",
                    "query" to "",
                    "body_sha256" to sha256Hex("{\"amount\":5}"),
                    "nonce" to access["nonce"],
                ),
            ),
            pin.body,
        )
        trust.send(proof)
        assertEquals("/v1/pay", backend.calls.last().path)
        assertEquals(proof.headers, backend.calls.last().headers)
    }

    @Test
    fun stepUpAndTheAppPinAreOptionsOffTheyRefuse() = runBlocking {
        val trust = trust(Backend())
        trust.start()
        expectThrows<NativeKeyException> { trust.signRequest(method = "POST", path = "/p", bearer = "b", stepUp = true) }
        expectThrows<IllegalStateException> { trust.signRequest(method = "POST", path = "/p", bearer = "b", appPin = "1") }
        expectThrows<IllegalStateException> { trust.setAppPin(session = "b", pin = "1") }
        Unit
    }

    @Test
    fun theAppPinIsSetWithTheReauthenticationTheAppGives() = runBlocking {
        val backend = Backend()
        val trust = trust(backend, appPin = AppPinOptions())
        trust.start()
        trust.setAppPin(session = "account-session", pin = "246810", reauthentication = mapOf("password" to "secret"))
        assertEquals("/v1/account/app-pin", backend.calls.last().path)
        assertEquals("account-session", backend.calls.last().bearer)
        assertEquals(mapOf("password" to "secret", "pin" to "246810"), backend.calls.last().body)
    }

    @Test
    fun aPhoneThatCannotMakeTheStepUpKeyRegistersWithoutIt() = runBlocking {
        val backend = Backend()
        val trust = trust(
            backend,
            stepUp = StepUpOptions(),
            stepUpKey = StepUpKey(refuse = NativeKeyException("STEPUP_NO_DEVICE_CREDENTIAL", "no screen lock")),
        )
        trust.start()
        assertFalse(backend.calls.first().body.containsKey("stepup_public_key"))
        assertEquals("STEPUP_NO_DEVICE_CREDENTIAL", trust.stepUpUnavailable?.code)
        assertEquals("installation-session", trust.installationSession)
    }

    @Test
    fun aStepUpKeyThePhoneCouldNotMakeAtRegistrationIsNotMadeLaterForARequest() = runBlocking {
        val backend = Backend()
        val key = FlakyStepUpKey()
        val trust = trust(backend, stepUp = StepUpOptions(), stepUpKey = key)
        trust.start()
        assertFalse(backend.calls.first().body.containsKey("stepup_public_key"))
        assertEquals(
            "STEPUP_NO_DEVICE_CREDENTIAL",
            expectThrows<NativeKeyException> {
                trust.signRequest(method = "POST", path = "/p", bearer = "b", stepUp = true)
            }.code,
        )
        assertEquals(1, key.attempts)
    }

    @Test
    fun thePersonalMeasurementsAreThePublishedContracts() {
        val schema = Json.decodeObject(repositoryFile("contract/report-1/android-8-ios-5/report.schema.json").readText())
        val enabled = schema["properties"].asObject()!!["enabled_measurements"].asObject()!!
        assertEquals(PersonalMeasurement.entries.map { it.wireName }, enabled["items"].asObject()!!["enum"])
    }
}
