package com.necessarysdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report-size rule: a report a backend would refuse for its size is never
 * sent. The lists the contract lets a collector shorten are halved, and say
 * truncated, until it fits; anything else is an error naming the limit --
 * nothing is cut silently.
 */
class ReportSizeTest {
    private fun report(probes: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "challenge_id" to "c",
        "challenge_nonce" to "n",
        "installation_id" to "i",
        "platform" to "android",
        "collector_version" to 8,
        "collected_at" to 1,
        "probe_results" to probes,
        "enabled_measurements" to listOf("accessibility"),
        "version" to 1,
    )

    private fun bytes(report: Map<String, Any?>) = utf8(Json.encode(report)).size

    private fun service(i: Int): Map<String, Any?> = linkedMapOf(
        "package" to "com.example.service.with.a.long.name.number$i",
        "service" to "com.example.service.with.a.long.name.number$i.Service",
        "system" to false,
        "installer" to "com.android.vending",
    )

    @Test
    fun aReportThatFitsIsSentAsItIs() {
        val report = report(
            mapOf("accessibility" to linkedMapOf("status" to "ok", "services" to listOf(service(1)), "truncated" to false)),
        )
        assertEquals(report, fitReport(report))
    }

    @Test
    fun aListTheContractLetsShortenIsHalvedAndSaysSoUntilItFits() {
        val report = report(
            linkedMapOf(
                "accessibility" to linkedMapOf(
                    "status" to "ok",
                    "services" to (0 until 2000).map { service(it) },
                    "settings_only" to emptyList<String>(),
                    "truncated" to false,
                ),
                "device" to linkedMapOf("status" to "ok", "model" to "m"),
            ),
        )
        assertTrue(bytes(report) > REPORT_BYTE_LIMIT)
        val fitted = fitReport(report)
        assertTrue(bytes(fitted) <= REPORT_BYTE_LIMIT)
        val accessibility = fitted["probe_results"].asObject()!!["accessibility"].asObject()!!
        assertEquals(true, accessibility["truncated"])
        assertTrue((accessibility["services"] as List<*>).size < 2000)
        assertEquals(mapOf("status" to "ok", "model" to "m"), fitted["probe_results"].asObject()!!["device"])
        assertEquals(
            "the collector answer itself is left as it came",
            2000,
            (report["probe_results"].asObject()!!["accessibility"].asObject()!!["services"] as List<*>).size,
        )
    }

    @Test
    fun anythingElseTooLargeIsAnErrorNamingTheLimit() {
        val report = report(mapOf("device" to linkedMapOf("status" to "ok", "model" to "x".repeat(REPORT_BYTE_LIMIT + 1))))
        val error = try {
            fitReport(report)
            null
        } catch (failure: ApiException) {
            failure
        }
        assertEquals("report_too_large", error?.code)
        assertEquals(REPORT_BYTE_LIMIT, error?.details?.get("limit"))
    }
}
