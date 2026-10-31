// How an app sets up the SDK: one DeviceTrust for its backend, with the
// options it chooses. Everything else in the example uses what is made here.
package com.necessarysdk.example

import android.content.Context
import com.necessarysdk.AppPinOptions
import com.necessarysdk.DeviceTrust
import com.necessarysdk.PersonalMeasurement
import com.necessarysdk.StepUpOptions

/**
 * The backends to choose among, by name: the Gradle property
 * necessary.backends ("Name=https://...;Other=https://..."), or one address.
 * Without it, the Android emulator's address for the computer it runs on.
 */
val backends: Map<String, String> = BuildConfig.NECESSARY_BACKENDS.let { list ->
    when {
        list.isBlank() -> linkedMapOf("Sample" to "http://10.0.2.2:8787")
        !list.contains('=') -> linkedMapOf("Sample" to list.trim())
        else -> list.split(';').filter { it.contains('=') }.associateTo(LinkedHashMap()) { entry ->
            entry.substringBefore('=').trim() to entry.substringAfter('=').trim()
        }
    }
}

/** A [DeviceTrust] for the backend at [baseUrl]. */
fun setUpDeviceTrust(
    context: Context,
    baseUrl: String,
    personalMeasurements: Set<PersonalMeasurement> = emptySet(),
) = DeviceTrust(
    context,
    baseUrl = baseUrl,
    // The personal measurements this app allows -- none unless it names them.
    // The backend asks for those it needs, and only what both name is measured.
    // They can change while the app runs: trust.personalMeasurements = setOf(...).
    personalMeasurements = personalMeasurements,
    // Step-up: a second key, which the person unlocks to approve one request.
    // factor "passcode" (the phone's PIN, pattern or password) or "biometric";
    // mode "per_use", or "windowed" with windowSeconds. Leave it out for no
    // step-up.
    stepUp = StepUpOptions(factor = "passcode", mode = "per_use"),
    // The app PIN, which the backend checks for one request. Its two calls
    // default to the guide's paths. Leave it out for no app PIN.
    appPin = AppPinOptions(),
)
