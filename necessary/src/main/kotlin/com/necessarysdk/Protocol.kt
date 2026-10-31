package com.necessarysdk

import kotlin.math.roundToLong

/**
 * What the client does about a refusal from your backend's API: the
 * protocol errors of the integration guide's section 4, each acted on
 * once. Any other refusal goes to the app as it came.
 */
enum class ProtocolStep { ASK_AGAIN, PROVE_AGAIN, REGISTER_AGAIN, WAIT_THEN_RETRY, STOP }

/**
 * The longest wait the client sits through before it retries a 429 itself;
 * a longer one goes to the app.
 */
const val PROTOCOL_MAX_WAIT_SECONDS: Int = 10

/**
 * [deviceSession]: the request ran under the session from
 * /v1/installations/verify, which the client can renew by proving its key
 * again; an account session is the app's to renew.
 */
fun protocolStepFor(error: ApiException, deviceSession: Boolean): ProtocolStep {
    when (error.code) {
        "challenge_expired", "challenge_used", "integrity_challenge_expired", "integrity_challenge_replay" ->
            return ProtocolStep.ASK_AGAIN
        "expired_token" -> return if (deviceSession) ProtocolStep.PROVE_AGAIN else ProtocolStep.STOP
        "installation_not_found" -> return ProtocolStep.REGISTER_AGAIN
    }
    return if (protocolWait(error) == null) ProtocolStep.STOP else ProtocolStep.WAIT_THEN_RETRY
}

/** How long a 429 asks the client to wait, in milliseconds, when that is short enough to wait. */
fun protocolWait(error: ApiException): Long? {
    val seconds = error.details?.get("retry_after_seconds")
    if (error.statusCode != 429 || seconds !is Number) return null
    val value = seconds.toDouble()
    if (value < 0 || value > PROTOCOL_MAX_WAIT_SECONDS) return null
    return (value * 1000).roundToLong()
}
