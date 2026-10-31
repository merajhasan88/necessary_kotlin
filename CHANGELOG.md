# Changelog

## 0.1.6

- Apps on Kotlin 2.0 and later can use the SDK. 0.1.5 was compiled as Kotlin 2.4 and asked for Kotlin
  2.4's standard library, so an app on Kotlin 2.2 or earlier — the Android Gradle plugin 9's own Kotlin
  among them — could not compile against it. The SDK's classes are now Kotlin 2.0's, and it asks for the
  standard library 2.0.21 and kotlinx-coroutines 1.9.0. Checked with an app on the Android Gradle plugin
  9.1 and its own Kotlin 2.2, and one on 8.13 with Kotlin 2.0. Nothing else changed.

## 0.1.5

The first release: the Necessary SDK for Android apps, in Kotlin, at the protocol and measurements of
the other Necessary SDKs' version 0.1.5.

- **The installation key**: EC P-256 in Android Keystore — StrongBox first, the trusted environment
  where the phone has none — never exportable; its public JWK, its thumbprint, and what the phone says
  about it (`key_security`). A key is made only where the keystore, answering, holds none: a keystore
  that cannot be reached answers `KEY_UNAVAILABLE`, and nothing is made over a key it may hold. Making
  and deleting a key run one at a time in the process.
- **Registration, proof of possession and the installation session** (the guide's section 1), with the
  reinstall hint (`android_id_sha256`). The installation ID is kept encrypted under a keystore key.
- **The scan** (section 2): the collector, version 10, measures what the backend's challenge asks for;
  the report is signed as sent. The comparison of code in memory with its files takes every region from
  one snapshot of the memory map, one comparison at a time in the process; its `app` region is the app's
  own APKs and the SDK's own library, and the app's executable files outside it are counted and named. A
  read that failed is in `unread`, or the probe answers `error`: never a clean value.
- **The personal measurements** (section 8): seven, each measured only when the app enables it and the
  backend asks for it.
- **Request proofs** (section 3), **a scan for one action**, **step-up** with a second key the person
  unlocks — re-enrolled with the person's re-authentication, deleted only when Android says it is dead
  — and **the app PIN**, which the backend checks for one request.
- **The API** (section 4): the guide's calls under the base URL the app sets, HTTPS only in a release
  build, 15 seconds a call; the protocol errors acted on once; or the pieces, for an app that carries
  the messages over its own API.
- **One installation at a time**: `reset()` and `close()` end the calls still running, which keep
  nothing; a reset waits for every write and key change still running.
- **Every failure says what failed beneath it**: a native failure's message ends with `[cause: …]`,
  down to the keystore's own answer.
- **Screen protection** while a secret is typed: no screenshots, recordings or casts of the window.
- The SDK starts with the app and follows its activities; the step-up prompt on Android 9 and 10 and
  the location request run through an invisible activity of its own, so the app forwards nothing.
- The example app, the three sample backends (Python, TypeScript, C#), the measurement contract, the
  shared client-server vectors, and the tests: the SDK's unit tests and its end-to-end test against each
  sample.
