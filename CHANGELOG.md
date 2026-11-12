# Changelog

## 0.1.8

- Every scan carries `app_host`, whether another app runs yours inside its own: where Android says your
  app's package file and data are, whether its process runs under your app's own user ID, which other apps
  Android names for that user ID, which other apps' libraries the processes your app starts are made to
  load first, and which other apps' code is in its memory. `doc/measurements.md` says how to read it.
- `debug_state` says whether the runtime's debugger thread runs in your app (`jdwp_thread`).
- `other_access` names the app that handles calls (`call_app`).
- A command that exits with an error is not a reading: `root_shell` and `system_properties` say why they
  could not read, rather than taking what the command printed as the answer.
- `emulator` also reads the processor and what the device is (`form_factor`, `cpu_vendor`,
  `cpu_implementers`, `cpu_model`, `hypervisor`, `abis`, `machine`). On a phone or tablet, an x86
  processor, a hypervisor or Apple's cores count as reasons (`cpu`, `hypervisor`, `abi`), whatever the
  build values say; on a Chromebook, a PC, a car, a TV or a watch they do not.
- The collector is version 11.
- `doc/integration_guide.md`, section 7.7: the genuine users who show a strong reading, how to tell them
  apart, and what to do.
- The example's steps start the Python sample backend in Python 3.11 or later, with its packages in a
  virtual environment of its own (`.venv`), as the sample backends' README does.
- The Python sample backend stops quietly on Ctrl+C.

## 0.1.7

- `runtime_maps` and `instrumentation_threads` leave the app's own folders and package name out of what
  they read as names.
- `root_shell` keeps the shell's error stream out of `su_path`.
- A step-up signature that fails after the person approved answers the app, whatever failed.
- A step-up key the phone lost and a re-enrolment made again leaves approvals waiting until the backend
  takes it, like a key replaced.
- A backend's answer that lacks a field the SDK needs is an `ApiException` (`invalid_backend_answer`).
- The answer to a call is read on the HTTP client's own thread, never the caller's.
- The Python sample backend answers a malformed request with a refusal.
- `doc/measurements.md`: `installer_package` is left out or null when no installer is named; every SDK
  sends its report in the same format.
- On an Android too old for the installation key, the message says that it requires Android 6.0.

## 0.1.6

- Apps on Kotlin 2.0 and later can use the SDK: its classes are Kotlin 2.0's, and it asks for the
  standard library 2.0.21 and kotlinx-coroutines 1.9.0 (0.1.5 asked for Kotlin 2.4's). Nothing else
  changed.

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
