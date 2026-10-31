package com.necessarysdk

/**
 * The person's approval of one request: the step-up key's [factor], and
 * [sign], which has the step-up key sign a payload -- the phone asks the
 * person (NativeStepUpKey.sign).
 */
class StepUpApproval(val factor: String, val sign: suspend (payload: String) -> String)

/**
 * What the installation key signed for one request -- with step-up, what
 * the step-up key signed; with the app PIN, the backend's proof of it; for a
 * request a scan approves, that scan's report -- and the headers that carry
 * them beside `Authorization: Bearer`.
 */
class RequestProof(
    val bearerToken: String,
    /** The request the proof was signed for: its method, its path with the query, and the exact body. */
    val method: String,
    val path: String,
    val encodedBody: String,
    /** `X-Access-Proof`: the base64url of the proof's JSON bytes. */
    val payload: String,
    /** `X-Access-Signature`. */
    val signature: String,
    val timestamp: Long,
    val nonce: String,
    /** `X-Step-Up-Proof` and `X-Step-Up-Signature`, for a request the person approved. */
    val stepUpPayload: String? = null,
    val stepUpSignature: String? = null,
    /** `X-App-PIN-Proof`: the backend's proof that the person entered the app PIN for this request. */
    val appPinProof: String? = null,
    /** `X-Integrity-Report`: the report of the scan made for this request alone (the public guide, section 2). */
    val integrityReport: String? = null,
) {
    val headers: Map<String, String>
        get() = linkedMapOf<String, String>().apply {
            put("X-Access-Proof", payload)
            put("X-Access-Signature", signature)
            stepUpPayload?.let { put("X-Step-Up-Proof", it) }
            stepUpSignature?.let { put("X-Step-Up-Signature", it) }
            appPinProof?.let { put("X-App-PIN-Proof", it) }
            integrityReport?.let { put("X-Integrity-Report", it) }
        }

    /** This request, unlocked by the backend's [proof] of the app PIN. */
    fun withAppPinProof(proof: String): RequestProof = copy(appPinProof = proof, integrityReport = integrityReport)

    /** This request, naming [reportId], the scan made for it alone. */
    fun withIntegrityReport(reportId: String): RequestProof = copy(appPinProof = appPinProof, integrityReport = reportId)

    private fun copy(appPinProof: String?, integrityReport: String?) = RequestProof(
        bearerToken, method, path, encodedBody, payload, signature, timestamp, nonce,
        stepUpPayload, stepUpSignature, appPinProof, integrityReport,
    )
}

/**
 * What the app sends to have its backend check the app PIN for one request:
 * the PIN, and that request as its request proof signs it -- method, path,
 * query, the hash of the body sent and the request proof's nonce.
 */
fun appPinProofRequest(pin: String, method: String, path: String, encodedBody: String, nonce: String): Map<String, Any?> {
    val target = RequestTarget(path)
    return linkedMapOf(
        "pin" to pin,
        "request" to linkedMapOf(
            "method" to method.uppercase(),
            "path" to target.path,
            "query" to target.query,
            "body_sha256" to sha256Hex(encodedBody),
            "nonce" to nonce,
        ),
    )
}

/**
 * The step-up proof, version 2, that the step-up key signs for one request:
 * the base64url of its JSON, the members in this order. [nonce] is the
 * request proof's nonce for the same request, so the approval is used up
 * with it.
 */
fun stepUpProof(
    installationId: String,
    accessToken: String,
    method: String,
    path: String,
    encodedBody: String,
    factor: String,
    nonce: String,
    timestamp: Long,
): String {
    val target = RequestTarget(path)
    return base64UrlNoPadding(
        utf8(
            Json.encode(
                linkedMapOf(
                    "version" to 2,
                    "purpose" to "stepup",
                    "installation_id" to installationId,
                    "access_token_sha256" to sha256Hex(accessToken),
                    "method" to method.uppercase(),
                    "path" to target.path,
                    "query" to target.query,
                    "body_sha256" to sha256Hex(encodedBody),
                    "factor" to factor,
                    "nonce" to nonce,
                    "timestamp" to timestamp,
                ),
            ),
        ),
    )
}

internal fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/**
 * Signs [method] [path] with [encodedBody] under [bearerToken]: proof
 * version 2, which signs the query string too, exactly as sent. A GET signs
 * an empty body. With [stepUp], the step-up key signs first -- the phone asks
 * the person -- and the request proof after, with the same nonce, so the
 * time the person takes does not count against the request proof's clock
 * window. [installationId], [timestamp] and [nonce] replace what the request
 * proof would otherwise carry, for the shared test vectors and for testing a
 * backend's refusals.
 */
suspend fun signRequest(
    identity: InstallationIdentity,
    method: String,
    path: String,
    bearerToken: String,
    encodedBody: String = "",
    stepUp: StepUpApproval? = null,
    installationId: String? = null,
    timestamp: Long? = null,
    nonce: ByteArray? = null,
): RequestProof {
    val normalizedMethod = method.uppercase()
    val body = if (normalizedMethod == "GET") "" else encodedBody
    val nonceText = base64UrlNoPadding(nonce ?: freshNonce())
    var stepUpPayload: String? = null
    var stepUpSignature: String? = null
    if (stepUp != null) {
        stepUpPayload = stepUpProof(
            installationId = identity.installationId,
            accessToken = bearerToken,
            method = normalizedMethod,
            path = path,
            encodedBody = body,
            factor = stepUp.factor,
            nonce = nonceText,
            timestamp = nowSeconds(),
        )
        stepUpSignature = stepUp.sign(stepUpPayload)
    }
    val signedAt = timestamp ?: nowSeconds()
    val target = RequestTarget(path)
    // The members in this order: the backend checks each against the request
    // it received, and the shared vectors hold both sides to these bytes.
    val proof = linkedMapOf<String, Any?>(
        "access_token_sha256" to sha256Hex(bearerToken),
        "body_sha256" to sha256Hex(body),
        "installation_id" to (installationId ?: identity.installationId),
        "method" to normalizedMethod,
        "nonce" to nonceText,
        "path" to target.path,
        "query" to target.query,
        "timestamp" to signedAt,
        "version" to 2,
    )
    // The key signs the exact JSON bytes this base64url value carries; the
    // private key never leaves Android Keystore.
    val payload = base64UrlNoPadding(utf8(Json.encode(proof)))
    return RequestProof(
        bearerToken = bearerToken,
        method = normalizedMethod,
        path = path,
        encodedBody = body,
        payload = payload,
        signature = identity.signPayload(payload),
        timestamp = signedAt,
        nonce = nonceText,
        stepUpPayload = stepUpPayload,
        stepUpSignature = stepUpSignature,
    )
}

/**
 * The one request a scan is to approve, described exactly as it will be
 * sent: what the challenge names as its `operation`.
 */
fun describeOperation(method: String, path: String, encodedBody: String): Map<String, Any?> {
    val target = RequestTarget(path)
    return linkedMapOf(
        "method" to method.uppercase(),
        "path" to target.path,
        "query" to target.query,
        "body_sha256" to sha256Hex(encodedBody),
    )
}
