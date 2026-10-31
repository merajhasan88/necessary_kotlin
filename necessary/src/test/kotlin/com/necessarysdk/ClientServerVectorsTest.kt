package com.necessarysdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared client-server vectors, vectors/client_server.json: the SDK must
 * produce, byte for byte, the request proof it signs, the operation a scan
 * names, its key's thumbprint, the step-up proofs and the app PIN's request --
 * the integration guide's sections 1 to 3. A backend can test itself against
 * the same file.
 */
class ClientServerVectorsTest {
    private val vectors: Map<String, Any?> = Json.decodeObject(repositoryFile("vectors/client_server.json").readText())

    @Suppress("UNCHECKED_CAST")
    private fun section(name: String): List<Map<String, Any?>> = vectors[name] as List<Map<String, Any?>>

    private val someKey: Map<String, Any?> = section("thumbprint").first()["jwk"].asObject()!!

    /** Signs nothing: the vectors fix what is signed, and ECDSA signatures are randomized. */
    private val vectorKey = object : NativeInstallationKey(NoNative) {
        override suspend fun getOrCreateKey(): NativeKeyMetadata = throw UnsupportedOperationException()
        override suspend fun signPayload(payloadBase64Url: String): String = "unsigned"
        override suspend fun deleteKey() {}
    }

    private fun identity(installationId: String, jwk: Map<String, Any?>) = InstallationIdentity(
        installationId = installationId,
        nativeKey = vectorKey,
        metadata = NativeKeyMetadata(
            publicKey = jwk,
            algorithm = "ES256",
            keyAlias = "vector",
            provider = "vector",
            securityLevel = "software",
            hardwareBacked = false,
            privateKeyExportable = false,
            signatureFormat = "der",
            created = false,
        ),
    )

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    fun accessProof() = runTest {
        val all = section("access_proof")
        assertEquals(5, all.size)
        for (vector in all) {
            val name = vector["name"] as String
            val body = vector["body"]
            val proof = signRequest(
                identity = identity(vector["installation_id"] as String, someKey),
                method = vector["method"] as String,
                path = vector["path"] as String,
                bearerToken = vector["bearer"] as String,
                encodedBody = if (body == null) "" else Json.encode(body),
                timestamp = (vector["timestamp"] as Number).toLong(),
                nonce = base64UrlDecode(vector["nonce"] as String),
            )
            assertEquals("$name: the body sent", vector["sent_body"], proof.encodedBody)
            assertEquals("$name: the proof, as JSON", vector["proof_json"], text(base64UrlDecode(proof.payload)))
            assertEquals("$name: the proof, as sent", vector["proof"], proof.payload)
        }
    }

    @Test
    fun operationAScanNames() {
        val all = section("operation")
        assertEquals(2, all.size)
        for (vector in all) {
            val name = vector["name"] as String
            assertEquals("$name: the body sent", vector["sent_body"], Json.encode(vector["body"]))
            assertEquals(
                name,
                vector["operation"],
                describeOperation(vector["method"] as String, vector["path"] as String, Json.encode(vector["body"])),
            )
        }
    }

    @Test
    fun keyThumbprint() {
        val all = section("thumbprint")
        assertEquals(2, all.size)
        for (vector in all) {
            val identity = identity("00000000-0000-4000-8000-000000000000", vector["jwk"].asObject()!!)
            assertEquals(vector["name"] as String, vector["thumbprint"], identity.keyThumbprint)
        }
    }

    // The step-up proofs (the public guide, section 3): what the step-up key
    // signs for one request, and what a new step-up key signs at re-enrolment.
    @Test
    fun stepUpProofV2() {
        val all = section("step_up_proof")
        assertEquals(3, all.size)
        for (vector in all) {
            val name = vector["name"] as String
            val sent = Json.encode(vector["body"])
            assertEquals("$name: the body sent", vector["sent_body"], sent)
            val payload = stepUpProof(
                installationId = vector["installation_id"] as String,
                accessToken = vector["bearer"] as String,
                method = "POST",
                path = vector["path"] as String,
                encodedBody = sent,
                factor = vector["factor"] as String,
                nonce = vector["nonce"] as String,
                timestamp = (vector["timestamp"] as Number).toLong(),
            )
            assertEquals("$name: the proof, as JSON", vector["proof_json"], text(base64UrlDecode(payload)))
            assertEquals("$name: the proof, as sent", vector["proof"], payload)
        }
    }

    @Test
    fun stepUpKeyReenrolmentProof() {
        val all = section("step_up_reenrol_proof")
        assertEquals(2, all.size)
        for (vector in all) {
            val name = vector["name"] as String
            val payload = stepUpReenrolProof(
                installationId = vector["installation_id"] as String,
                stepUpKeyThumbprint = vector["stepup_key_thumbprint"] as String,
                nonce = vector["nonce"] as String,
                timestamp = (vector["timestamp"] as Number).toLong(),
            )
            assertEquals("$name: the proof, as JSON", vector["proof_json"], text(base64UrlDecode(payload)))
            assertEquals("$name: the proof, as sent", vector["proof"], payload)
            // The thumbprint the proof names is the new key's own.
            assertEquals("$name: the key's thumbprint", vector["stepup_key_thumbprint"], thumbprintOf(vector["jwk"].asObject()!!))
        }
    }

    // What the app sends to have its backend check the app PIN for one request
    // (the public guide, section 3).
    @Test
    fun appPinProofRequest() {
        val all = section("app_pin_proof_request")
        assertEquals(2, all.size)
        for (vector in all) {
            val name = vector["name"] as String
            val sent = Json.encode(vector["body"])
            assertEquals("$name: the body of the request it unlocks", vector["sent_body"], sent)
            assertEquals(
                name,
                vector["asked_body"],
                Json.encode(
                    appPinProofRequest(
                        pin = vector["pin"] as String,
                        method = "POST",
                        path = vector["path"] as String,
                        encodedBody = sent,
                        nonce = vector["nonce"] as String,
                    ),
                ),
            )
        }
    }
}
