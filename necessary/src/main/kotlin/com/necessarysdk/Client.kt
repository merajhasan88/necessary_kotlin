package com.necessarysdk

/**
 * The public guide's five calls (section 4) over a [DeviceTrustTransport],
 * the two scan calls each with its request proof (section 3), and the app
 * PIN's two calls.
 */
class DeviceTrustClient(val transport: DeviceTrustTransport) {
    companion object {
        /** The app PIN's default paths (the public guide, section 4). */
        const val APP_PIN_PATH: String = "/v1/account/app-pin"
        const val APP_PIN_PROOF_PATH: String = "/v1/account/app-pin/proof"

        /** The fields of the guide's registration, which [registerInstallation]'s `extra` cannot replace. */
        val registrationFields: Set<String> = linkedSetOf(
            "installation_id",
            "platform",
            "public_key",
            "reinstall_hint",
            "key_security",
            "stepup_public_key",
            "stepup_key_auth",
        )
    }

    /**
     * Sends [proof]'s request. [method], [path] and [encodedBody] replace what
     * the proof was signed for -- only to test that a backend refuses them.
     */
    suspend fun sendSigned(
        proof: RequestProof,
        method: String? = null,
        path: String? = null,
        encodedBody: String? = null,
    ): Map<String, Any?> {
        val sentMethod = (method ?: proof.method).uppercase()
        return transport.send(
            sentMethod,
            path ?: proof.path,
            encodedBody = if (sentMethod == "GET") null else (encodedBody ?: proof.encodedBody),
            bearerToken = proof.bearerToken,
            headers = proof.headers,
        )
    }

    /**
     * Signs and sends one request under [bearerToken], its [body] as JSON.
     * [installationId] replaces the installation the proof names, only to test
     * that a backend refuses it.
     */
    suspend fun signAndSend(
        method: String,
        path: String,
        bearerToken: String,
        identity: InstallationIdentity,
        body: Map<String, Any?>? = null,
        installationId: String? = null,
    ): Map<String, Any?> = sendSigned(
        signRequest(
            identity = identity,
            method = method,
            path = path,
            bearerToken = bearerToken,
            encodedBody = Json.encode(body ?: emptyMap<String, Any?>()),
            installationId = installationId,
        ),
    )

    /**
     * Registers the installation's key, and with [stepUpKey] the step-up key.
     * The answer's `installation_id` is the installation your backend holds
     * for this key, which may not be the one proposed. [extra] adds fields of
     * your backend's own.
     */
    suspend fun registerInstallation(
        identity: InstallationIdentity,
        hint: ReinstallHint?,
        stepUpKey: StepUpKeyMetadata? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val replaced = extra.keys.filter { it in registrationFields }
        require(replaced.isEmpty()) { "extra replaces the registration fields ${replaced.joinToString(", ")}" }
        val body = linkedMapOf<String, Any?>(
            "installation_id" to identity.installationId,
            "platform" to "android",
            "public_key" to identity.publicKeyJson,
            "reinstall_hint" to hint?.toJson(),
            // What the platform's keystore says about how the key is protected:
            // a claim, not proof. Its value is comparative -- a non-exportable
            // hardware key cannot move into software, so the same key later
            // claiming a weaker level contradicts itself.
            "key_security" to linkedMapOf(
                "security_level" to identity.securityLevel,
                "hardware_backed" to identity.hardwareBacked,
                "provider" to identity.provider,
            ),
        )
        // The step-up key, and how the phone says it is unlocked: also a
        // claim, recorded and compared with what the backend requires.
        if (stepUpKey != null) {
            body["stepup_public_key"] = stepUpKey.publicKey
            body["stepup_key_auth"] = stepUpKey.authJson
        }
        body.putAll(extra)
        return transport.send("POST", "/v1/installations/register", encodedBody = Json.encode(body))
    }

    /** Asks for a key challenge: `challenge_id` and the `payload` to sign. */
    suspend fun createInstallationChallenge(installationId: String): Map<String, Any?> = transport.send(
        "POST",
        "/v1/installations/challenge",
        encodedBody = Json.encode(linkedMapOf("installation_id" to installationId)),
    )

    /** Answers a key challenge; the answer carries `device_token`, your backend's session for this installation. */
    suspend fun verifyInstallation(
        installationId: String,
        challengeId: String,
        payload: String,
        signature: String,
    ): Map<String, Any?> = transport.send(
        "POST",
        "/v1/installations/verify",
        encodedBody = Json.encode(
            linkedMapOf(
                "installation_id" to installationId,
                "challenge_id" to challengeId,
                "payload" to payload,
                "signature" to signature,
            ),
        ),
    )

    /**
     * Asks for a scan under [session]: the installation's session, from
     * [verifyInstallation] -- or, for a scan that approves one [operation]
     * ([describeOperation]), your account session for this installation.
     */
    suspend fun integrityChallenge(
        session: String,
        identity: InstallationIdentity,
        operation: Map<String, Any?>? = null,
    ): ScanChallenge = ScanChallenge.fromJson(
        signAndSend(
            "POST",
            "/v1/integrity/challenge",
            bearerToken = session,
            identity = identity,
            body = if (operation == null) linkedMapOf() else linkedMapOf("operation" to operation),
        ),
    )

    /**
     * Sends [report] under the [session] that asked for its scan. The answer
     * is your backend's own; for a scan that approves one request it carries
     * `report_id`, sent with that request as `X-Integrity-Report`.
     */
    suspend fun submitIntegrityReport(
        session: String,
        identity: InstallationIdentity,
        report: SignedReport,
    ): Map<String, Any?> = signAndSend(
        "POST",
        "/v1/integrity/report",
        bearerToken = session,
        identity = identity,
        body = report.toJson(),
    )

    /**
     * Sets the account's app PIN under its account [session]. The backend
     * requires the person's own re-authentication with it -- [reauthentication]
     * carries it, the account's password for example: `mapOf("password" to ...)`.
     */
    suspend fun setAppPin(
        session: String,
        identity: InstallationIdentity,
        pin: String,
        reauthentication: Map<String, Any?> = emptyMap(),
        path: String = APP_PIN_PATH,
    ): Map<String, Any?> = signAndSend(
        "POST",
        path,
        bearerToken = session,
        identity = identity,
        body = LinkedHashMap(reauthentication).apply { put("pin", pin) },
    )

    /**
     * Has the backend check the app [pin] for the one signed [request] -- sent
     * once, in a signed body, never in a header -- and answers that request
     * unlocked by its proof, valid for that request alone.
     */
    suspend fun proveAppPin(
        session: String,
        identity: InstallationIdentity,
        pin: String,
        request: RequestProof,
        path: String = APP_PIN_PROOF_PATH,
    ): RequestProof {
        val answer = signAndSend(
            "POST",
            path,
            bearerToken = session,
            identity = identity,
            body = appPinProofRequest(
                pin = pin,
                method = request.method,
                path = request.path,
                encodedBody = request.encodedBody,
                nonce = request.nonce,
            ),
        )
        val proof = answer["app_pin_proof"]
        if (proof !is String || proof.isEmpty()) {
            throw ApiException("The backend answered no app PIN proof.", code = "app_pin_proof_missing")
        }
        return request.withAppPinProof(proof)
    }

    fun close() = transport.close()
}
