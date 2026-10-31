package com.necessarysdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK's reports against the published measurement contract: from the
 * collector answer in each sample of contract/report-1/android-8-ios-5/, the
 * SDK builds that sample's report -- every member but the phone's clock. The
 * SDK judges nothing: an invalid or refused sample is built just as
 * faithfully.
 */
class ReportContractTest {
    private val samples = repositoryFile("contract/report-1/android-8-ios-5/samples/android-8-complete.json")
        .parentFile!!.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.path }

    private val key = object : NativeInstallationKey(NoNative) {
        override suspend fun getOrCreateKey(): NativeKeyMetadata = throw UnsupportedOperationException()
        override suspend fun signPayload(payloadBase64Url: String): String = "signed"
        override suspend fun deleteKey() {}
    }

    /** Answers what the sample's collector answered. */
    private class Recorded(private val report: Map<String, Any?>) : NativeIntegrityCollector(NoNative) {
        override suspend fun collect(
            requiredProbes: List<String>,
            challengeNonce: String,
            enabledMeasurements: List<String>,
            optionalProbes: List<String>,
        ): Map<String, Any?> = linkedMapOf(
            "platform" to report["platform"],
            "challenge_nonce_echo" to challengeNonce,
            "collector_version" to report["collector_version"],
            "probes" to report["probe_results"],
        )
    }

    /** The same value as JSON reads it back, whatever kind of number each was built with. */
    private fun asRead(value: Any?): Any? = Json.decode(Json.encode(value))

    @Test
    fun theContractHasItsSamples() = assertEquals(11, samples.size)

    @Test
    fun eachSampleIsBuiltAsTheContractHasIt() = runBlocking {
        for (file in samples) {
            val sample = Json.decodeObject(file.readText())
            val expected = sample["report"].asObject()!!
            val plan = sample["plan"].asObject()!!
            val built = collectReport(
                challenge = ScanChallenge.fromJson(
                    linkedMapOf(
                        "challenge_id" to expected["challenge_id"],
                        "nonce" to expected["challenge_nonce"],
                        "platform" to expected["platform"],
                        "required_probes" to plan["required_probes"],
                        "optional_probes" to plan["optional_probes"],
                    ),
                ),
                identity = InstallationIdentity(
                    installationId = expected["installation_id"] as String,
                    nativeKey = key,
                    metadata = NativeKeyMetadata(
                        publicKey = emptyMap(),
                        algorithm = "ES256",
                        keyAlias = "sample",
                        provider = "sample",
                        securityLevel = "software",
                        hardwareBacked = false,
                        privateKeyExportable = false,
                        signatureFormat = "der",
                        created = false,
                    ),
                ),
                collector = Recorded(expected),
                enabledMeasurements = (expected["enabled_measurements"] as List<*>).map { it.toString() },
            )
            assertTrue(file.name, built.report["collected_at"] is Long)
            assertEquals(
                file.name,
                asRead(expected),
                asRead(LinkedHashMap(built.report).apply { put("collected_at", expected["collected_at"]) }),
            )
            assertEquals("${file.name}: the payload is the report, as signed", asRead(built.report), decoded(built.payload))
        }
    }
}
