package com.necessarysdk

/** The most a backend takes: a report of 65,536 bytes of JSON (the measurement contract, section 1). */
const val REPORT_BYTE_LIMIT: Int = 65536

/**
 * The lists a report may shorten, by measurement -- each beside the
 * `truncated` flag that says the phone had more (the measurement contract,
 * section 6).
 */
val shortenableLists: Map<String, List<String>> = linkedMapOf(
    "accessibility" to listOf("services", "settings_only"),
    "notification_access" to listOf("listeners"),
    "other_access" to listOf("device_admins", "remote_access_apps"),
)

/**
 * [report] made to fit [REPORT_BYTE_LIMIT]: the longest list it may shorten is
 * halved, and marked `truncated`, until it fits. A report that cannot fit is
 * never cut anywhere else: the answer is an error naming the limit.
 */
fun fitReport(report: Map<String, Any?>): Map<String, Any?> {
    val probes = LinkedHashMap(report["probe_results"].asObject() ?: emptyMap())
    val fitted = LinkedHashMap(report).apply { put("probe_results", probes) }
    fun size(): Int = utf8(Json.encode(fitted)).size
    var bytes = size()
    while (bytes > REPORT_BYTE_LIMIT) {
        var probe: String? = null
        var field: String? = null
        var longest = 0
        for ((name, fields) in shortenableLists) {
            val result = probes[name].asObject()
            for (list in fields) {
                val items = result?.get(list) as? List<*>
                if (items != null && items.size > longest) {
                    probe = name
                    field = list
                    longest = items.size
                }
            }
        }
        if (probe == null || field == null) {
            throw ApiException(
                "The report would be $bytes bytes; a backend takes at most $REPORT_BYTE_LIMIT.",
                code = "report_too_large",
                details = linkedMapOf("bytes" to bytes, "limit" to REPORT_BYTE_LIMIT),
            )
        }
        val shortened = LinkedHashMap(probes[probe].asObject()!!)
        shortened[field] = (shortened[field] as List<*>).subList(0, longest / 2).toList()
        shortened["truncated"] = true
        probes[probe] = shortened
        bytes = size()
    }
    return fitted
}

/** A challenge for a scan, as your backend sent it. */
class ScanChallenge(
    val challengeId: String,
    val nonce: String,
    val platform: String,
    /** The measurements the report must answer. */
    val requiredProbes: List<String>,
    /**
     * The personal measurements asked for, answered only where the app
     * enabled them; none from a backend that names none.
     */
    val optionalProbes: List<String>,
    /** The challenge whole. */
    val answer: Map<String, Any?>,
) {
    companion object {
        fun fromJson(value: Map<String, Any?>): ScanChallenge {
            fun names(list: Any?): List<String> = (list as? List<*>)?.map { it.toString() } ?: emptyList()
            return ScanChallenge(
                challengeId = answerText(value, "challenge_id"),
                nonce = answerText(value, "nonce"),
                platform = answerText(value, "platform"),
                requiredProbes = names(value["required_probes"]),
                optionalProbes = names(value["optional_probes"]),
                answer = value,
            )
        }
    }
}

/** A report as the installation key signed it. */
class SignedReport(
    /** The report, as signed. */
    val report: Map<String, Any?>,
    /** `report_payload`: the base64url of the report's JSON bytes. */
    val payload: String,
    /** `report_signature`. */
    val signature: String,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf("report_payload" to payload, "report_signature" to signature)
}

/**
 * Measures what [challenge] asks for -- and, of the personal measurements it
 * asks for, those in [enabledMeasurements] -- and signs the report with
 * [identity]'s key.
 */
suspend fun collectReport(
    challenge: ScanChallenge,
    identity: InstallationIdentity,
    collector: NativeIntegrityCollector,
    enabledMeasurements: List<String>,
): SignedReport {
    val native = collector.collect(
        requiredProbes = challenge.requiredProbes,
        challengeNonce = challenge.nonce,
        enabledMeasurements = enabledMeasurements,
        optionalProbes = challenge.optionalProbes,
    )
    val probes = native["probes"].asObject()
        ?: throw ApiException("The native collector returned no probe map.", code = "invalid_native_integrity_result")
    if (native["platform"] != challenge.platform || native["challenge_nonce_echo"] != challenge.nonce) {
        throw ApiException(
            "The native integrity response did not match the server challenge.",
            code = "integrity_challenge_binding_failed",
        )
    }
    val report = fitReport(
        linkedMapOf(
            "challenge_id" to challenge.challengeId,
            "challenge_nonce" to challenge.nonce,
            "installation_id" to identity.installationId,
            "platform" to challenge.platform,
            "collector_version" to ((native["collector_version"] as? Number)?.toInt() ?: 1),
            "collected_at" to nowSeconds(),
            "probe_results" to LinkedHashMap(probes),
            // The personal measurements this app enabled, whether or not this scan asked for them.
            "enabled_measurements" to enabledMeasurements,
            "version" to 1,
        ),
    )
    val payload = base64UrlNoPadding(utf8(Json.encode(report)))
    return SignedReport(report = report, payload = payload, signature = identity.signPayload(payload))
}
