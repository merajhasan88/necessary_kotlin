# necessary for Kotlin

Device integrity for Android apps. The SDK gives your app a key it never exports — made in the phone's
keystore, in secure hardware where the phone has it and in software where it does not — signed
measurements of the phone and of the app running on it, and a proof on each request your app sends —
and, if you turn them on, a second key the person must unlock to approve a request, and a PIN your
backend checks. **It never scores and never decides: your backend does.**

Android 7.0 (API 24) and later, from Kotlin or Java. The measuring code, the collector, is version 10.

The SDK's website is **[necessarysdk.com](https://necessarysdk.com)**.

## Documentation

- [The integration guide](doc/integration_guide.md) — the protocol: what the SDK sends, and how your
  backend checks it.
- [The measurements](doc/measurements.md) — everything the SDK measures on the phone, field by field:
  what each measurement is, how it is taken, and what a complete answer holds.
- [The measurement contract](contract/report-1/android-8-ios-5/) — the report's schema, its rules and
  sample reports. The folder's name is two versions: `report-1` is the report's format, and
  `android-8-ios-5` the lowest versions of the measuring code, the collector, that the contract accepts
  — 8 on Android and 5 on iOS (`collector_version`; this SDK's is 10). They are not the Android versions
  the SDK runs on, which are above.
- [The example app](example/) puts every piece below in a working app, against the
  [sample backends](backend/) (in the repository, not the package). [Try the example
  app](#try-the-example-app), below, installs it and tests it step by step.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.necessarysdk:necessary:0.1.5")
}
```

The SDK declares what its own features need — its permissions and package queries are merged into your
manifest — and starts with your app, so that it knows which activity the person sees: your activities
forward nothing. The step-up prompt on Android 9 and 10 and the location request run through an
invisible activity of the SDK's own. Your app adds only what you choose to use:

- **Location measurements**: `ACCESS_COARSE_LOCATION` (and `ACCESS_FINE_LOCATION` for a precise
  location) in your manifest. The SDK never asks by itself: call `trust.requestLocationPermission()` from
  your own screen.

Every call but `close()` is a `suspend` function, and may be made from any thread; the SDK does its work
off the main thread, and only the step-up prompt on it.

## Start

```kotlin
import com.necessarysdk.DeviceTrust
import com.necessarysdk.PersonalMeasurement

val trust = DeviceTrust(
    context,
    baseUrl = "https://api.example.com",
    // The personal measurements your app allows; your backend asks for what it needs of them.
    personalMeasurements = setOf(PersonalMeasurement.SCREEN_CAPTURE),
)

trust.start() // loads or makes the key, registers it, proves it holds it
```

`start()` makes the guide's first three calls under `baseUrl`: register the installation, ask for a key
challenge, answer it. Your backend answers each as the guide says. A new key is a new installation:
`trust.identity!!.createdThisLaunch` tells your app to forget what it kept for the old one.

## Scan

```kotlin
val scan: ScanResult = trust.scan()
val answer = scan.answer // your backend's answer, whole
```

The SDK measures what your backend's challenge asks for — and, of the personal measurements it asks for,
the ones your app enabled — signs the report with the installation key and sends it. It acts on the
guide's protocol errors once (a challenge expired, the installation session expired, the backend lost
the installation, a short wait); any other refusal reaches your app as an `ApiException`.

## Personal measurements

Seven measurements identify a person's phone, network or habits. Each is collected only when your app
enables it **and** your backend's challenge asks for it (`optional_probes`); otherwise the report says
`not_enabled`. None is on unless your app turns it on:

```kotlin
trust.personalMeasurements = setOf(
    PersonalMeasurement.SCREEN_CAPTURE, // the screen recorded, mirrored or cast
    PersonalMeasurement.LOCATION, // the last location the phone already knows
)
```

Set them in the constructor, or change them whenever the person does — in a privacy setting: the next
scan follows, and the installation and its step-up key stay as they are.

| `PersonalMeasurement` | What it reports |
|---|---|
| `LOCAL_ADDRESSES` | the phone's own IP addresses, link-local ones left out |
| `ACCESSIBILITY` | the Accessibility services that are on, and the keyboard in use |
| `NOTIFICATION_ACCESS` | the apps allowed to read notifications |
| `OTHER_ACCESS` | device-administrator apps, and known remote-control apps installed |
| `SCREEN_CAPTURE` | the screen being recorded (Android 15 and later), mirrored or cast |
| `LOCATION` | the last location the phone already knows — never a fresh fix, never a prompt |
| `MOCK_LOCATION` | whether that location was faked |

The location needs the person's permission as well: declare it (Install, above) and ask from your own
screen with `trust.requestLocationPermission()`, which answers `precise`, `approximate` or `none`.

## Prove your own requests

Every request your app sends to your own API can carry a proof that this installation's key signed it,
so a copied session token is useless on another phone:

```kotlin
val body = """{"amount":25,"to":"payee-7"}"""
val proof = trust.signRequest(
    method = "POST",
    path = "/v1/transfer",
    bearer = accountSession, // your backend's session for this installation
    body = body, // sent exactly as signed
)
val request = Request.Builder() // OkHttp, or any client of your own
    .url("https://api.example.com/v1/transfer")
    .header("Authorization", "Bearer $accountSession")
    .apply { proof.headers.forEach { (name, value) -> header(name, value) } } // X-Access-Proof, X-Access-Signature
    .post(body.toRequestBody("application/json".toMediaType()))
    .build()
```

Each proof serves its request once. `trust.send(proof)` sends it through the SDK's transport instead.

## A scan for one action

For a request that matters — a payment, a new payee — have the scan name it, under the account session
that will send it, and send the scan's report with the request:

```kotlin
val operation = describeOperation("POST", "/v1/transfer", body)
val scan = trust.scan(session = accountSession, operation = operation)
val proof = trust.signRequest(
    method = "POST",
    path = "/v1/transfer",
    bearer = accountSession,
    body = body,
    reportId = scan.reportId, // X-Integrity-Report
)
```

## Step-up: the person approves one request

Turn it on, and choose how the key is unlocked:

```kotlin
val trust = DeviceTrust(
    context,
    baseUrl = "https://api.example.com",
    stepUp = StepUpOptions(factor = "passcode", mode = "per_use"),
)
trust.start() // registers the step-up key with the installation

trust.stepUpUnavailable?.let {
    // This phone cannot make the key -- no screen lock, say. Step-up is off here.
}

val proof = trust.signRequest(
    method = "POST",
    path = "/v1/transfer",
    bearer = accountSession,
    body = body,
    stepUp = true,
    reason = "Approve the transfer of 25", // what the phone shows the person
)
```

| Option | Values |
|---|---|
| `factor` | `passcode` (the phone's PIN, pattern or password) or `biometric` (a fingerprint or face enrolled now) |
| `mode` | `per_use` (unlocked for every signature) or `windowed` with `windowSeconds` (one unlock for a while) |

Android 9 and 10 cannot ask for the passcode at every signature: there a passcode key is windowed, and
says so to your backend. An app whose backend sets the policy assigns `trust.stepUp` before `start()`.

Removing the screen lock, or enrolling another biometric for a biometric key, makes the key unusable:
an approval then fails with `NativeKeyException` `STEPUP_KEY_INVALIDATED`. The SDK deletes the key only
when Android itself says so; when the keystore cannot establish whether the key is usable, or cannot be
reached at all — a failure it may recover from — the approval fails with `STEPUP_KEY_UNAVAILABLE` and
the key is kept: try again later. The person re-enrols a new key with their own re-authentication, which
also replaces a key that stays unavailable:

```kotlin
trust.reenrolStepUp(
    session = accountSession,
    reauthentication = mapOf("password" to password), // as your backend requires
)
```

A new key is made, the person unlocks it to sign its proof, and your backend takes it for that account
alone; approvals use it from then on. The call goes to `/v1/installations/stepup-key`, or to your API's
own path: `StepUpOptions(reenrolPath = ...)`. Until your backend has taken the new key — the person
cancelled the prompt, the password was wrong, the answer was lost — approvals fail with
`STEPUP_REENROL_PENDING`, across restarts too: call `reenrolStepUp` again. The same holds for a key the
phone made after the installation was registered — the screen lock was off at the first `start()` and
set later, say — since your backend takes a step-up key only at an installation's first registration:
`start()` reports `stepUpUnavailable` `STEPUP_REENROL_PENDING`, and re-enrolment registers the key.

## The app PIN: the person's PIN for one request

Turn it on; the two paths default to the guide's and can be your API's own:

```kotlin
val trust = DeviceTrust(
    context,
    baseUrl = "https://api.example.com",
    appPin = AppPinOptions(), // path = "/v1/account/app-pin", proofPath = "/v1/account/app-pin/proof"
)

// Setting it: with the person's own re-authentication, as your backend requires.
trust.setAppPin(session = accountSession, pin = pin, reauthentication = mapOf("password" to password))

// Using it: your backend checks the PIN for this one request, and the request carries its proof.
val proof = trust.signRequest(method = "POST", path = "/v1/transfer", bearer = accountSession, body = body, appPin = pin)
```

The PIN travels once, in a signed body, never in a header, and the SDK never stores it. Protect the
screen while it is typed:

```kotlin
SecureScreen.protect(true) // no screenshots, recordings or casts of the window
// ... the PIN is typed ...
SecureScreen.protect(false)
```

Calls nest — each `protect(true)` needs its `protect(false)` — and when the last one ends the window gets
back what it had: a window your app keeps secure itself stays secure. While protected, other apps'
windows are not drawn over it (Android 12 and later) and a touch that passed through another app's
window is dropped.

Your backend's refusals reach your app as `ApiException` with the guide's codes: `app_pin_not_set`,
`app_pin_invalid` (it counts), `app_pin_locked` (a new PIN, set with the re-authentication, unlocks it).
Step-up and the app PIN combine: your backend decides which requests need which.

## What happens when

| When | What the SDK does |
|---|---|
| The key is lost — a reinstall, a cleared keystore | `start()` makes a new key and a new installation ID; `trust.identity!!.createdThisLaunch` is true, so forget what you kept for the old installation. Your backend links the two only provisionally, by the reinstall hint |
| Android's keystore cannot be reached | its lookups answer "no such key" then too, so the SDK makes a key only where the keystore, answering, holds none: `start()` fails with `NativeKeyException` `KEY_UNAVAILABLE`, sends nothing and keeps the installation as it is — call it again later; if it persists, `reset()` starts a new installation. Signing answers the same, and a step-up key `STEPUP_KEY_UNAVAILABLE` |
| The app is updated | the key and the installation ID stay: `start()` finds the same installation |
| The app's process restarts | nothing is kept in memory: `start()` again finds the same key and installation, and proves it for a new installation session |
| An activity is recreated — a rotation, say | screen protection follows: the SDK sets it on each new window. A step-up approval waiting for the passcode screen keeps waiting; one whose screen is gone for good ends as `STEPUP_CANCELLED`, never holding the next at `STEPUP_BUSY`: ask again |
| Two calls at once | `start()`, `prove()`, `loadIdentity()` and the same `scan()` run once, and the second caller gets the first's result; scans for different operations, or asked after the personal measurements changed, run apart. Requests through the SDK's transport are each sent as they are made |
| A step-up prompt is open | the SDK's other calls run meanwhile |
| `reset()` or `close()` while calls run | the calls end with `InterruptedCallException` and keep nothing — no session, no installation ID, no report sent, no request proof or app PIN answer handed out; a reset first waits for every write and key change still running, so none outlives it; after a reset, new calls wait for the deletion and never join one made before. A proof handed out before the reset, or a request already sent, is beyond it: reset is local |
| A key cannot be deleted | `reset()` fails with that `NativeKeyException` (`KEY_DELETE_FAILED`): call it again. If it is the installation key, the installation stays as it was, its ID with it; if it is the step-up key, the installation itself is already gone. None to delete, or no step-up on the phone, is no failure |
| A native call fails | the `NativeKeyException`'s message says what failed, then everything beneath it — `[cause: …]`, each failure's kind and words down to the keystore's own answer, and from Android 13 the keystore's numeric code wherever Android passes it on. A probe's `error` and each `unread` reason carry the same, within the contract's lengths |
| A call abandoned — its coroutine cancelled — or its answer lost | a scan is simply asked for again — a challenge serves once; the SDK never sends a signed request twice: sign it again, and let your backend decide whether the first one reached it. A call another caller shares goes on for that caller |
| A report over 65,536 bytes | it is never sent: the lists the contract lets a collector shorten are shortened, and say `truncated`; anything else answers an `ApiException` `report_too_large` |

## A release build talks only HTTPS

The SDK refuses an `http://` base URL when your app is not debuggable (`api_base_url_insecure`), and
gives each call 15 seconds. A debug build may use `http://` — to reach a backend on your computer, say.

## Your own transport

```kotlin
val trust = DeviceTrust(MyTransport(), context) // implements DeviceTrustTransport
```

A transport sends each message the SDK builds and answers your backend's JSON object, or throws an
`ApiException` with its `code`, `message` and `details`. To carry the messages over an API of your own
shape instead, use the pieces directly — `trust.client`, `collectReport`, `signRequest` — and make the
guide's checks in your backend.

## What the SDK never does

It never scores, decides, or tells your app a score; never stores your account session; never sends the
PIN in a header; and uses no platform attestation (Play Integrity, SafetyNet). It never exports a
private key, but it cannot prove to your backend where a key is kept: the registration's `key_security`
is the phone's own word, and where the secure hardware declines the key is a software key, which a
compromised phone can copy. A fully compromised phone can falsify what it measures and can use a genuine
key to sign what it likes: the measurements raise the cost of fraud, and your backend weighs them with
its own business logic.

## Try the example app

The example app (`example/`) puts every piece above in a working app, against a sample backend.

### Install it

The sample backends live in the repository, not in the package, so start from a clone.

1. Clone the repository:

   ```sh
   git clone https://github.com/merajhasan88/necessary_kotlin.git
   cd necessary_kotlin
   ```

2. Start a sample backend on your computer. It answers on `127.0.0.1` only. In Python 3.10 or later:

   ```sh
   pip install --require-hashes -r backend/python/requirements.txt
   python3 backend/python/server.py --port 8787 --schema contract/report-1/android-8-ios-5/report.schema.json
   ```

   The TypeScript and C# samples run the same way; their commands are in
   [`backend/README.md`](backend/README.md).
3. Build and install the example, which needs JDK 17 and the Android SDK:
   - **On an Android emulator**, a debug build reaches your computer at `10.0.2.2`, the example's
     default:

     ```sh
     ./gradlew :example:installDebug
     ```

   - **On an Android phone** connected by USB with USB debugging on, forward the port first:

     ```sh
     adb reverse tcp:8787 tcp:8787
     ./gradlew :example:installDebug -Pnecessary.backends=http://127.0.0.1:8787
     ```

   - **A release build** talks only HTTPS: the SDK refuses an `http://` address in a release build. Put
     the sample behind an HTTPS proxy with a certificate the phone trusts, and give that address:

     ```sh
     ./gradlew :example:installRelease -Pnecessary.backends=https://backend.example.com
     ```

     The release build is signed with your debug key. For a proxy on your own computer, reached through
     `adb reverse`, a certificate authority of your own can be trusted for `localhost` alone: a resource
     folder holding `raw/lab_ca.pem`, the authority's certificate, and `xml/lab_network_security.xml`, a
     network security configuration that trusts `@raw/lab_ca` for `localhost`, given as
     `-Pnecessary.extraRes=<folder>`.

   To offer several backends, name each:
   `-Pnecessary.backends='Python=https://…;TypeScript=https://…'`. A debug build measures itself as one
   — `debuggable` in `app_identity` — so judge the measurements on a release build.

### Test it on a phone, step by step

With several backends, tap one at the top and run the steps once for each. Every result line carries a ✓
when the backend did what it should, a red ✗ when it did not. Each section opens when its title is
tapped.

1. **Installation → Start.** Expect: `Installation …, proved; step-up on.` On a phone without a screen
   lock, step-up is off: `step-up off here (…)`, and step 7 cannot pass.
2. **Measurements:** switch on **All of them**, tap **Ask for the location permission** and allow it.
   Once is enough; the switches reset when the app restarts.
3. **Scan.** Expect: `The backend answered next: allow, report …` — the sample backends answer
   `scan_again` when a measurement could not be read, and `review` when its figures contradict each
   other. Under **Hooks, gadgets and memory changes** every row reads `measured`, none `not asked`. Open
   any row for the fields the phone sent.
4. **Account → Sign in.** Expect: `An account session for this installation.`
5. **Requests → Protected request.** Expect: ✓ `{"done":"/v1/demo/echo"}`.
6. **Sensitive, without approval.** Expect: ✓ `Refused, as it should be: 403 needs_approval`.
7. **Sensitive, with step-up**, then unlock with the phone's passcode. Expect: ✓
   `{"done":"/v1/demo/sensitive"}`.
8. Type 6 digits in **App PIN**, then **Set the app PIN**. Expect: ✓ `Set, with the account password.`
9. **Sensitive, with the app PIN.** Expect: ✓ `{"done":"/v1/demo/sensitive"}`.
10. **Requests a backend must refuse:** tap each of the four. Expect a ✓ on each: `Refused, as it should
    be: 401 proof_reused` for the request sent twice, `401 proof_not_accepted` for the other three.

If the phone's screen lock is removed and set again, the step-up key dies: step 7 then shows `The phone
said STEPUP_KEY_INVALIDATED`. Tap **Re-enrol the step-up key** and unlock with the passcode — expect ✓
`{"stepup_key_registered":true}` — and step 7 passes again. The new key is that account's alone. If the
lock was off at step 1 and set afterwards, **Start again** says `step-up off here
(STEPUP_REENROL_PENDING)`: the backend took the installation without a step-up key, so re-enrol the same
way after step 4.

The app PIN belongs to the account, not to the phone. Phones signed in with the same handle share it: a
PIN set on one unlocks requests on the other, and before any is set, step 9 answers `app_pin_not_set`.
Sign in with another handle for an account of its own.

### A session copied to another phone

A session token copied off one phone must be useless on another. You need two phones running the
example against the same backend.

1. **Phone A:** steps 1 and 4 above, then **Requests a backend must refuse → Copy this phone's session**.
   Expect: `Copied this phone's session`.
2. Get the copied text to phone B, for example in a note or a message to yourself. It holds phone A's
   session and installation ID; the sample's sessions last ten minutes.
3. **Phone B:** step 1 above, on the same backend.
4. **Phone B:** paste the text into **A session from another phone**, then tap **Use it here**. Expect: ✓
   `Refused, as it should be: 401 invalid_installation_signature`. Phone B's key cannot sign for phone A's
   installation, even when the request names it.

If step 4 reads `The session is no longer valid: sign in again`, phone A's session has expired: sign in
again on phone A and copy it again.

### Measurement by measurement

What each measurement is and every field it carries: [`doc/measurements.md`](doc/measurements.md). On a
phone of your own you can watch these change, one at a time: make the change, tap **Scan**, open the
measurement's row, and change it back afterwards.

| Measurement | Change | The row then shows |
|---|---|---|
| `developer_settings` | Settings → About phone → tap Build number seven times; then Developer options → USB debugging | `developer_options_enabled`, then `adb_enabled`, true |
| `network` | turn on a VPN app; or set a manual proxy on your Wi-Fi network | `vpn_active` true, the tunnel's interface in `interfaces`; `http_proxy_configured` true |
| `local_addresses` | switch between Wi-Fi and mobile data | the phone's own addresses, per interface |
| `screen_capture` | start the screen recorder (Android 15 and later); connect an external display | `recording` true; `external_displays` 1 |
| `location` | tap **Ask for the location permission** and allow it | the `permission`, the coordinates and their `age_seconds` |
| `mock_location` | Developer options → Select mock location app, and run a mock-location app | `mock` true |
| `accessibility` | Settings → Accessibility → turn on a service such as Select to Speak; or choose another keyboard | the service in `services`; the keyboard in `keyboard` |
| `notification_access` | Settings → Notification access (under Special app access) → allow an app | the app in `listeners` |
| `other_access` | install AnyDesk, TeamViewer QuickSupport, RustDesk or AirDroid; or make an app a device administrator | the app in `remote_access_apps`; in `device_admins` |
| `app_identity` | install a build of the example made with other code | other hashes; a debug build reads `debuggable` true |
| `debug_state` | in Android Studio, Run → Attach Debugger to Android Process, on a debug build | `debugger_connected` true |

The rest — root traces, Frida and other hooking tools, code changed in memory, an emulator — needs a
device you are allowed to change: never a phone you depend on. On an Android emulator
`emulator.suspected` is true, and a `userdebug` image shows a debuggable, test-keys build in
`system_properties` and `root_files`. Frida on a test device shows in `runtime_maps`, `frida_ports`,
`instrumentation_threads`, `exec_mappings` and `code_integrity`.

## Licence

Copyright 2026 Meraj Hasan. Licensed under the Apache License, Version 2.0 (`LICENSE`, `NOTICE`).
