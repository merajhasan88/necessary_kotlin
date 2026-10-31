package com.necessarysdk

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The SDK end to end against a sample backend (backend/), over real HTTP
 * with real P-256 signatures: only the phone's keystore and collector are
 * replaced, by a software key and the contract's Android samples -- the
 * complete one unless a test says otherwise.
 *
 * Runs when NECESSARY_BACKEND_URL names a backend; skipped otherwise. The
 * tests of the backend's test controls need NECESSARY_TEST_TOKEN, the token
 * they answer (backend/run_e2e.sh sets both); without it they are skipped, as
 * for a deployed sample.
 */
class BackendE2eTest {
    private val backend: String? = System.getenv("NECESSARY_BACKEND_URL")
    private val testToken: String? = System.getenv("NECESSARY_TEST_TOKEN")
    private val http = OkHttpClient()

    @Before
    fun needsBackend() = assumeTrue("NECESSARY_BACKEND_URL is not set", backend != null)

    // MARK: the phone, in software

    /** A P-256 key in software: signs as the phone's keystore does, ECDSA over SHA-256 with an ASN.1 DER signature. */
    class SoftwareKey {
        private val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        val jwk: Map<String, Any?> = (pair.public as ECPublicKey).let { key ->
            linkedMapOf(
                "kty" to "EC",
                "crv" to "P-256",
                "alg" to "ES256",
                "x" to base64UrlNoPadding(unsigned(key.w.affineX)),
                "y" to base64UrlNoPadding(unsigned(key.w.affineY)),
            )
        }

        fun sign(payloadBase64Url: String): String =
            base64UrlNoPadding(
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(pair.private as ECPrivateKey)
                    update(base64UrlDecode(payloadBase64Url))
                    sign()
                },
            )

        private fun unsigned(value: BigInteger): ByteArray {
            val raw = value.toByteArray().let { if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else it }
            return ByteArray(32 - raw.size) + raw
        }
    }

    class InstallationKey : NativeInstallationKey(NoNative) {
        val key = SoftwareKey()
        var made = false

        override suspend fun getOrCreateKey(): NativeKeyMetadata {
            val created = !made
            made = true
            return NativeKeyMetadata(
                publicKey = key.jwk,
                algorithm = "ES256",
                keyAlias = "software",
                provider = "AndroidKeyStore",
                securityLevel = "software",
                hardwareBacked = false,
                privateKeyExportable = false,
                signatureFormat = "der",
                created = created,
            )
        }

        override suspend fun signPayload(payloadBase64Url: String): String = key.sign(payloadBase64Url)

        override suspend fun deleteKey() {
            made = false
        }
    }

    /** The installation ID in memory, beside the software key. */
    class MemoryStore : InstallationStore(InstallationKey(), MemoryStorage()) {
        var stored: String? = null
        var reenrolPending = false

        override suspend fun loadOrCreate(): InstallationIdentity {
            val metadata = key.getOrCreateKey()
            if (stored == null || metadata.created) stored = UUID.randomUUID().toString()
            return InstallationIdentity(installationId = stored!!, nativeKey = key, metadata = metadata)
        }

        override suspend fun saveInstallationId(installationId: String) {
            stored = installationId
        }

        override suspend fun stepUpReenrolPending(): Boolean = reenrolPending

        override suspend fun setStepUpReenrolPending(pending: Boolean) {
            reenrolPending = pending
        }
    }


    class StepUpKey : NativeStepUpKey(NoNative) {
        var key = SoftwareKey()

        /** The screen lock removed: the key can no longer sign. */
        var dead = false

        /** Whether the phone has a screen lock: without one no key is made. */
        var locked = true

        override suspend fun getOrCreateKey(factor: String, mode: String, windowSeconds: Int): StepUpKeyMetadata {
            if (!locked) throw NativeKeyException("STEPUP_NO_DEVICE_CREDENTIAL", "no lock")
            return StepUpKeyMetadata(
                publicKey = key.jwk,
                factor = factor,
                mode = mode,
                windowSeconds = windowSeconds,
                securityLevel = "software",
                hardwareBacked = false,
                created = false,
            )
        }

        override suspend fun sign(payloadBase64Url: String, reason: String): String {
            if (dead) throw NativeKeyException("STEPUP_KEY_INVALIDATED", "the lock was removed")
            return key.sign(payloadBase64Url)
        }

        override suspend fun deleteKey() {
            key = SoftwareKey()
            dead = false
        }
    }

    /** Answers with one of the contract's Android samples, whatever is asked. */
    class SampleCollector(sample: String = "android-8-complete", private val collectorVersion: Int = 8) :
        NativeIntegrityCollector(NoNative) {
        private val probes: Map<String, Any?> = sample(sample)["report"].asObject()!!["probe_results"].asObject()!!

        override suspend fun collect(
            requiredProbes: List<String>,
            challengeNonce: String,
            enabledMeasurements: List<String>,
            optionalProbes: List<String>,
        ): Map<String, Any?> = linkedMapOf(
            "platform" to "android",
            "challenge_nonce_echo" to challengeNonce,
            "collector_version" to collectorVersion,
            "probes" to probes,
        )
    }

    class Hint : ReinstallHintReader(NoNative) {
        override suspend fun read(): ReinstallHint = ReinstallHint(
            kind = "android_id_sha256",
            value = (1..64).map { "0123456789abcdef"[SecureRandom().nextInt(16)] }.joinToString(""),
        )
    }

    private fun trust(
        stepUp: StepUpOptions? = null,
        appPin: AppPinOptions? = null,
        stepUpKey: StepUpKey? = null,
        collector: SampleCollector? = null,
    ) = DeviceTrust(
        transport = HttpTransport(backend!!, releaseBuild = false),
        store = MemoryStore(),
        collector = collector ?: SampleCollector(),
        hintReader = Hint(),
        stepUpKeyNative = stepUpKey ?: StepUpKey(),
        stepUp = stepUp,
        appPin = appPin,
    )

    companion object {
        const val SAMPLES = "contract/report-1/android-8-ios-5/samples"

        /** One of the contract's sample reports, with its plan and how it is judged. */
        fun sample(name: String): Map<String, Any?> = Json.decodeObject(repositoryFile("$SAMPLES/$name.json").readText())
    }

    // MARK: the backend's test controls

    /** A test control, and its answer. */
    private suspend fun control(path: String, body: Map<String, Any?>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$backend$path")
            .header("X-Test-Token", testToken ?: "")
            .post(Json.encode(body).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            assertEquals("the backend runs with --test-controls, answering NECESSARY_TEST_TOKEN", 200, response.code)
            Json.decodeObject(response.body!!.string())
        }
    }

    /** From now on the backend holds each app PIN check once it has claimed its attempt, until [releasePinChecks]. */
    private suspend fun holdPinChecks() = control("/test/pin-checks", mapOf("hold" to true))

    /** Releases the checks held -- with [fail], as if their verifier broke. */
    private suspend fun releasePinChecks(fail: Boolean = false) =
        control("/test/pin-checks", mapOf("hold" to false, "fail" to fail))

    /** Waits until [count] app PIN checks are held. */
    private suspend fun pinChecksHeld(count: Int) {
        repeat(200) {
            if ((control("/test/pin-checks", emptyMap())["waiting"] as? Number)?.toInt() == count) return
            delay(50)
        }
        fail("the backend never held $count app PIN checks")
    }

    /** This backend's own sign-in, under the installation session: its account session for this installation. */
    private suspend fun signIn(trust: DeviceTrust, password: String = "correct horse"): String {
        val proof = trust.signRequest(
            method = "POST",
            path = "/v1/demo/sign-in",
            bearer = trust.installationSession!!,
            body = Json.encode(linkedMapOf("handle" to "e2e-${SecureRandom().nextInt() ushr 1}", "password" to password)),
        )
        return trust.send(proof)["account_session"] as String
    }

    /**
     * Refused with [code] -- and, like every refusal here, without naming the
     * check that failed: the phone is told what to do next, never what gave a
     * request away.
     */
    private suspend fun assertRefused(code: String, reason: String = "", call: suspend () -> Any?) {
        try {
            val answer = call()
            fail("$reason: accepted, but should be refused with $code: $answer")
        } catch (error: ApiException) {
            assertEquals("$reason: the code (${error.statusCode} ${error.message})", code, error.code)
            val named = error.details?.keys.orEmpty().intersect(setOf("check", "at"))
            assertTrue("$reason: the failed check named: $named", named.isEmpty())
        }
    }

    // MARK: the tests

    @Test
    fun startRegistersAndProvesAScanIsAnsweredWithItsReportId() = runBlocking {
        val trust = trust()
        trust.start()
        val scan = trust.scan()
        assertTrue(!scan.reportId.isNullOrEmpty())
        assertEquals("allow", scan.answer["integrity"].asObject()!!["next"])
        // What catches hooks, gadgets and changes in memory is asked for on
        // every scan, never left to chance.
        repeat(3) {
            assertTrue(
                trust.scan().challenge.requiredProbes.containsAll(
                    listOf(
                        "runtime_maps", "frida_ports", "instrumentation_threads", "exec_mappings",
                        "code_integrity", "tracer", "debug_state",
                    ),
                ),
            )
        }
        // Every personal measurement is asked for, so that the example app
        // can show each one it enables.
        assertEquals(PersonalMeasurement.entries.map { it.wireName }, scan.challenge.optionalProbes)
    }

    @Test
    fun aRequestCarriesItsProofOnce() = runBlocking {
        val trust = trust()
        trust.start()
        val account = signIn(trust)
        val proof = trust.signRequest(method = "POST", path = "/v1/demo/echo", bearer = account, body = "{\"hello\":1}")
        assertEquals("/v1/demo/echo", trust.send(proof)["done"])
        assertRefused("proof_reused") { trust.send(proof) }
    }

    @Test
    fun aScanForOneActionApprovesThatRequestOnce() = runBlocking {
        val trust = trust()
        trust.start()
        val account = signIn(trust)
        val body = "{\"amount\":25,\"to\":\"payee-7\"}"
        val scan = trust.scan(session = account, operation = describeOperation("POST", "/v1/demo/transfer", body))
        suspend fun transfer(reportId: String?) = trust.send(
            trust.signRequest(method = "POST", path = "/v1/demo/transfer", bearer = account, body = body, reportId = reportId),
        )
        assertRefused("needs_scan") { transfer(null) }
        assertEquals("/v1/demo/transfer", transfer(scan.reportId)["done"])
        assertRefused("needs_scan") { transfer(scan.reportId) }
    }

    @Test
    fun aScanForOneActionApprovesItOnlyWhenTheBackendAllowsIt() = runBlocking {
        val body = "{\"amount\":25,\"to\":\"payee-7\"}"
        for ((sample, next) in listOf(
            "android-8-complete" to "allow",
            "android-8-incomplete" to "scan_again",
            "android-8-invalid" to "review",
        )) {
            val trust = trust(collector = SampleCollector(sample))
            trust.start()
            val account = signIn(trust)
            val scan = trust.scan(session = account, operation = describeOperation("POST", "/v1/demo/transfer", body))
            assertEquals(sample, next, scan.answer["integrity"].asObject()!!["next"])
            suspend fun transfer() = trust.send(
                trust.signRequest(
                    method = "POST",
                    path = "/v1/demo/transfer",
                    bearer = account,
                    body = body,
                    reportId = scan.reportId,
                ),
            )
            if (next == "allow") {
                assertEquals(sample, "/v1/demo/transfer", transfer()["done"])
            } else {
                assertRefused("needs_scan", sample) { transfer() }
            }
        }
        // A collector older than the contract is refused, never read.
        val old = trust(collector = SampleCollector("android-8-complete", 7))
        old.start()
        assertRefused("report_not_accepted") { old.scan() }
    }

    @Test
    fun everySampleOfTheContractIsJudgedAsItSaysUnderItsOwnPlan() = runBlocking {
        assumeTrue("NECESSARY_TEST_TOKEN is not set", testToken != null)
        val samples = repositoryFile("$SAMPLES/android-8-complete.json").parentFile!!
            .listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.path }
        assertEquals(11, samples.size)
        for (file in samples) {
            val sample = Json.decodeObject(file.readText())
            val answer = control(
                "/test/judge",
                linkedMapOf(
                    "report" to sample["report"],
                    "required_probes" to sample["plan"].asObject()!!["required_probes"],
                ),
            )
            assertEquals(file.path, sample["expect"], answer["judged"])
        }
    }

    @Test
    fun aSensitiveRequestNeedsThePersonsApprovalStepUpTheAppPinOrBoth() = runBlocking {
        val trust = trust(stepUp = StepUpOptions(), appPin = AppPinOptions())
        trust.start()
        val account = signIn(trust, password = "pin owner")
        suspend fun sensitive(stepUp: Boolean = false, pin: String? = null) = trust.send(
            trust.signRequest(
                method = "POST",
                path = "/v1/demo/sensitive",
                bearer = account,
                body = "{\"payee\":\"payee-7\"}",
                stepUp = stepUp,
                appPin = pin,
            ),
        )
        assertRefused("needs_approval") { sensitive() }
        assertEquals("/v1/demo/sensitive", sensitive(stepUp = true)["done"])
        assertRefused("app_pin_not_set") { sensitive(pin = "135790") }
        assertEquals(
            true,
            trust.setAppPin(session = account, pin = "135790", reauthentication = mapOf("password" to "pin owner"))["app_pin_set"],
        )
        assertRefused("app_pin_invalid") { sensitive(pin = "000000") }
        assertEquals("/v1/demo/sensitive", sensitive(pin = "135790")["done"])
        assertEquals("/v1/demo/sensitive", sensitive(stepUp = true, pin = "135790")["done"])
    }

    @Test
    fun aRequestProofIsRefusedForAnotherBodyPathOrTimeAndUnderAnotherPhonesKey() = runBlocking {
        val trust = trust()
        trust.start()
        val account = signIn(trust)
        val body = "{\"amount\":25}"
        suspend fun echo() = trust.signRequest(method = "POST", path = "/v1/demo/echo", bearer = account, body = body)
        assertRefused("proof_not_accepted") { trust.client.sendSigned(echo(), encodedBody = "{\"amount\":2500}") }
        assertRefused("proof_not_accepted") { trust.client.sendSigned(echo(), path = "/v1/demo/sensitive") }
        assertRefused("proof_not_accepted") {
            trust.send(
                signRequest(
                    identity = trust.identity!!,
                    method = "POST",
                    path = "/v1/demo/echo",
                    bearerToken = account,
                    encodedBody = body,
                    timestamp = System.currentTimeMillis() / 1000 - 600,
                ),
            )
        }
        // The session copied to another phone: its key cannot sign for this
        // installation, even naming it.
        val other = trust()
        other.start()
        assertRefused("invalid_installation_signature") {
            other.send(
                signRequest(
                    identity = other.identity!!,
                    method = "POST",
                    path = "/v1/demo/echo",
                    bearerToken = account,
                    encodedBody = body,
                    installationId = trust.identity!!.installationId,
                ),
            )
        }
    }

    @Test
    fun reenrolmentADeadStepUpKeyReplacedWithThePasswordForThatAccountAlone() = runBlocking {
        val stepUpKey = StepUpKey()
        val trust = trust(stepUp = StepUpOptions(), stepUpKey = stepUpKey)
        trust.start()
        val account = signIn(trust, password = "key owner")
        suspend fun sensitive(session: String) = trust.send(
            trust.signRequest(
                method = "POST",
                path = "/v1/demo/sensitive",
                bearer = session,
                body = "{\"payee\":\"payee-7\"}",
                stepUp = true,
            ),
        )
        assertEquals("/v1/demo/sensitive", sensitive(account)["done"])
        // The screen lock removed: the key the backend holds cannot sign.
        stepUpKey.dead = true
        assertEquals("STEPUP_KEY_INVALIDATED", expectThrows<NativeKeyException> { sensitive(account) }.code)
        assertRefused("reauthentication_failed") {
            trust.reenrolStepUp(session = account, reauthentication = mapOf("password" to "wrong password"))
        }
        assertEquals(
            true,
            trust.reenrolStepUp(session = account, reauthentication = mapOf("password" to "key owner"))["stepup_key_registered"],
        )
        assertEquals("/v1/demo/sensitive", sensitive(account)["done"])
        // Another account on the same phone gains no step-up from it.
        val another = signIn(trust)
        assertRefused("step_up_not_accepted") { sensitive(another) }
    }

    @Test
    fun anAppPinCheckSettlesAgainstThePinItCompared() = runBlocking {
        assumeTrue("NECESSARY_TEST_TOKEN is not set", testToken != null)
        val trust = trust(appPin = AppPinOptions())
        trust.start()
        val account = signIn(trust, password = "pin owner")
        suspend fun setPin(pin: String) {
            trust.setAppPin(session = account, pin = pin, reauthentication = mapOf("password" to "pin owner"))
        }
        suspend fun unlock(pin: String) = trust.signRequest(
            method = "POST",
            path = "/v1/demo/sensitive",
            bearer = account,
            body = "{\"payee\":\"payee-7\"}",
            appPin = pin,
        )
        suspend fun sensitive(pin: String) = trust.send(unlock(pin))
        setPin("111111")

        // A right PIN still being checked when a new one is set unlocks
        // nothing; asked now, the old PIN is simply wrong. (Each check held is
        // expected at once, so that its refusal never goes unheard.)
        holdPinChecks()
        val right = async { assertRefused("app_pin_invalid") { unlock("111111") } }
        pinChecksHeld(1)
        setPin("222222")
        releasePinChecks()
        right.await()
        assertRefused("app_pin_invalid") { sensitive("111111") }
        assertEquals("/v1/demo/sensitive", sensitive("222222")["done"])

        // Five wrong PINs being checked hold every attempt, so a sixth waits;
        // when a new PIN is set meanwhile they neither count against it nor
        // lock it.
        holdPinChecks()
        val wrong = (0 until 5).map { i -> async { assertRefused("app_pin_invalid") { unlock("99999$i") } } }
        pinChecksHeld(5)
        assertRefused("pin_busy") { unlock("999995") }
        setPin("333333")
        releasePinChecks()
        wrong.awaitAll()
        assertEquals("/v1/demo/sensitive", sensitive("333333")["done"])

        // A proof made before a new PIN no longer serves.
        val made = unlock("333333")
        setPin("444444")
        assertRefused("app_pin_not_accepted") { trust.send(made) }
        assertEquals("/v1/demo/sensitive", sensitive("444444")["done"])

        // A check that could not run gives its attempt back: four wrong PINs
        // after it still leave the last attempt, which two checks at once
        // cannot both take.
        holdPinChecks()
        val broken = async { assertRefused("pin_unavailable") { unlock("444444") } }
        pinChecksHeld(1)
        releasePinChecks(fail = true)
        broken.await()
        for (i in 0 until 4) assertRefused("app_pin_invalid") { sensitive("00000$i") }
        holdPinChecks()
        val last = async { unlock("444444") }
        pinChecksHeld(1)
        assertRefused("pin_busy") { unlock("444444") }
        releasePinChecks()
        assertEquals("/v1/demo/sensitive", trust.send(last.await())["done"])
    }

    @Test
    fun aScreenLockSetOnlyAfterTheInstallationWasRegisteredWaitsForReenrolmentThenApproves() = runBlocking {
        val stepUpKey = StepUpKey().apply { locked = false }
        val trust = trust(stepUp = StepUpOptions(), stepUpKey = stepUpKey)
        trust.start()
        assertEquals("STEPUP_NO_DEVICE_CREDENTIAL", trust.stepUpUnavailable?.code)
        val account = signIn(trust, password = "lock owner")
        // The lock set again, the app started again: the key it makes now is
        // not one the backend took.
        stepUpKey.locked = true
        trust.start()
        assertEquals("STEPUP_REENROL_PENDING", trust.stepUpUnavailable?.code)
        suspend fun sensitive() = trust.send(
            trust.signRequest(
                method = "POST",
                path = "/v1/demo/sensitive",
                bearer = account,
                body = "{\"payee\":\"payee-7\"}",
                stepUp = true,
            ),
        )
        assertEquals("STEPUP_REENROL_PENDING", expectThrows<NativeKeyException> { sensitive() }.code)
        assertEquals(
            true,
            trust.reenrolStepUp(session = account, reauthentication = mapOf("password" to "lock owner"))["stepup_key_registered"],
        )
        assertEquals("/v1/demo/sensitive", sensitive()["done"])
    }

    @Test
    fun theGuidesProtocolErrorsEachActedOnOnce() = runBlocking {
        assumeTrue("NECESSARY_TEST_TOKEN is not set", testToken != null)
        val trust = trust()
        trust.start()
        // The installation session expired: the key is proved again.
        control("/test/expire-sessions", emptyMap())
        assertTrue(!trust.scan().reportId.isNullOrEmpty())
        // The backend lost the installation: registered again with the same key.
        control("/test/forget", mapOf("installation_id" to trust.identity!!.installationId))
        assertTrue(!trust.scan().reportId.isNullOrEmpty())
        // A key challenge expired before its answer: a new one, once.
        control(
            "/test/refuse-next",
            mapOf("path" to "/v1/installations/verify", "status" to 401, "code" to "challenge_expired"),
        )
        assertTrue(trust.prove().isNotEmpty())
        // A scan's challenge expired before its report: a new scan, once.
        control(
            "/test/refuse-next",
            mapOf("path" to "/v1/integrity/report", "status" to 401, "code" to "integrity_challenge_expired"),
        )
        assertTrue(!trust.scan().reportId.isNullOrEmpty())
        // A short wait is waited out, once.
        control(
            "/test/refuse-next",
            mapOf(
                "path" to "/v1/integrity/challenge",
                "status" to 429,
                "code" to "busy",
                "details" to mapOf("retry_after_seconds" to 1),
            ),
        )
        assertTrue(!trust.scan().reportId.isNullOrEmpty())
        // Any other refusal reaches the app as it came.
        control(
            "/test/refuse-next",
            mapOf("path" to "/v1/integrity/challenge", "status" to 403, "code" to "installation_inactive"),
        )
        assertRefused("installation_inactive") { trust.scan() }
    }
}
