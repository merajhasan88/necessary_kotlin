// The SDK against its sample backends (backend/), section by section: the
// installation, the scan with every measurement it sent, an account, requests
// with and without the person's approval, and requests a backend must refuse.
// Setup.kt makes the DeviceTrust, SampleApi.kt signs what the app sends,
// Measurements.kt describes a scan; this file is the screen.
package com.necessarysdk.example

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.necessarysdk.ApiException
import com.necessarysdk.DeviceTrust
import com.necessarysdk.NativeKeyException
import com.necessarysdk.PersonalMeasurement
import com.necessarysdk.ScanResult
import com.necessarysdk.SecureScreen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What a backend that makes the guide's checks does with a request. */
enum class Expect { ACCEPTED, REFUSED }

/** One action's result as shown: [asExpected] when the backend did what [Expect] says. */
class Outcome(val action: String, val text: String, val asExpected: Boolean)

class MainActivity : Activity() {
    private val scope = MainScope()
    private var backend = backends.keys.first()
    private lateinit var trust: DeviceTrust
    private lateinit var api: SampleApi

    private var scan: ScanResult? = null
    private var locationPermission: String? = null
    private var busy = false
    private val latest = HashMap<String, Outcome>()
    private val log = ArrayList<Outcome>()
    private val expanded = hashSetOf("Installation")
    private val openMeasurements = HashSet<String>()

    private lateinit var root: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var handle: EditText
    private lateinit var password: EditText
    private lateinit var pin: EditText
    private lateinit var shared: EditText
    private var useHere: Button? = null
    private var screenProtected = false

    private val colorPrimary = Color.rgb(0, 121, 107)
    private val colorError = Color.rgb(179, 38, 30)
    private val colorMuted = Color.rgb(96, 96, 96)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trust = setUpDeviceTrust(this, backends.getValue(backend))
        api = SampleApi(trust)
        handle = edit("Handle").apply { setText("example") }
        password = edit("Password").apply {
            setText("example password")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        pin = edit("App PIN (6 digits)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
        }
        shared = edit("A session from another phone").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    useHere?.isEnabled = !busy && trust.installationSession != null && !s.isNullOrBlank()
                }
            })
        }
        // No screenshots or recordings of the window while a secret is typed.
        val protectSecrets = View.OnFocusChangeListener { _, _ -> protectSecrets() }
        password.onFocusChangeListener = protectSecrets
        pin.onFocusChangeListener = protectSecrets

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(32))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
                addView(ScrollView(this@MainActivity).apply { addView(root) })
            },
        )
        render()
    }

    private fun protectSecrets() {
        val typing = password.hasFocus() || pin.hasFocus()
        if (typing != screenProtected) {
            screenProtected = typing
            scope.launch { SecureScreen.protect(typing) }
        }
    }

    override fun onDestroy() {
        if (screenProtected) scope.launch { SecureScreen.protect(false) }
        trust.close()
        scope.cancel()
        super.onDestroy()
    }

    /** Another backend: the same key, an installation of its own there, and the same personal measurements. */
    private fun choose(name: String) {
        val enabled = trust.personalMeasurements
        trust.close()
        backend = name
        trust = setUpDeviceTrust(this, backends.getValue(name), personalMeasurements = enabled)
        api = SampleApi(trust)
        scan = null
        latest.clear()
        render()
    }

    /** Runs one action of [section] and shows how the backend answered it. */
    private fun run(section: String, action: String, expect: Expect = Expect.ACCEPTED, step: suspend () -> String) {
        busy = true
        render()
        scope.launch {
            val outcome = try {
                val answer = step()
                if (expect == Expect.ACCEPTED) {
                    Outcome(action, answer, asExpected = true)
                } else {
                    Outcome(action, "Accepted, but a backend should refuse this: $answer", asExpected = false)
                }
            } catch (failure: ApiException) {
                val refusal = "${failure.statusCode ?: ""} ${failure.code ?: ""}".trim()
                // A session the backend no longer takes says nothing of the check a
                // request was made wrong to test: the sample's session lasts ten minutes.
                val sessionGone = failure.code == "expired_token" || failure.code == "not_signed_in"
                if (expect == Expect.REFUSED && !sessionGone) {
                    Outcome(action, "Refused, as it should be: $refusal", asExpected = true)
                } else if (sessionGone) {
                    Outcome(action, "Refused: $refusal. The session is no longer valid: sign in again.", false)
                } else {
                    Outcome(action, "Refused: $refusal. ${failure.message}", asExpected = false)
                }
            } catch (failure: NativeKeyException) {
                Outcome(action, "The phone said ${failure.code}: ${failure.message}", asExpected = false)
            } catch (failure: Exception) {
                Outcome(action, failure.toString(), asExpected = false)
            }
            // Also to the device log (logcat), for whoever runs a test.
            Log.i("EXAMPLE", "[$backend] $action — ${outcome.text}")
            busy = false
            latest[section] = outcome
            log.add(0, outcome)
            render()
        }
    }

    // --- The installation.

    private suspend fun start(): String {
        val identity = trust.start()
        val stepUpOff = trust.stepUpUnavailable
        return "Installation ${identity.installationId}, proved; step-up " +
            (if (stepUpOff == null) "on" else "off here (${stepUpOff.code})") + "."
    }

    private fun newInstallation() {
        AlertDialog.Builder(this)
            .setTitle("A new installation?")
            .setMessage(
                "This deletes the installation key, its ID and the step-up key, as a reinstall would, " +
                    "and starts again with new keys.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start again") { _, _ ->
                run("Installation", "New installation") {
                    trust.reset()
                    api.account = null
                    scan = null
                    start()
                }
            }
            .show()
    }

    // --- The scan.

    private fun setPersonal(enabled: Set<PersonalMeasurement>) {
        trust.personalMeasurements = enabled
        render()
    }

    private suspend fun doScan(): String {
        val result = trust.scan()
        scan = result
        if (BuildConfig.LOG_REPORTS) {
            // The report exactly as signed and sent: report_payload, base64url, in pieces the log takes.
            val parts = result.report.payload.chunked(3000)
            parts.forEachIndexed { index, part -> Log.i("EXAMPLE_REPORT", "${index + 1}/${parts.size} $part") }
        }
        val next = result.answer["integrity"].let { it as? Map<*, *> }?.get("next")
        return "The backend answered next: ${next ?: "not said"}, report ${result.reportId ?: "not named"}."
    }

    // --- The screen.

    private fun render() {
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        // EditTexts are kept, and moved into the screen as it is drawn again.
        for (field in listOf(handle, password, pin, shared)) (field.parent as? ViewGroup)?.removeView(field)
        root.removeAllViews()
        val started = trust.installationSession != null
        val signedIn = api.account != null
        if (backends.size > 1) {
            root.addView(
                HorizontalScrollView(this).apply {
                    addView(
                        RadioGroup(this@MainActivity).apply {
                            orientation = RadioGroup.HORIZONTAL
                            for (name in backends.keys) {
                                addView(
                                    RadioButton(this@MainActivity).apply {
                                        text = name
                                        id = View.generateViewId()
                                        isChecked = name == backend
                                        isEnabled = !busy
                                        setOnClickListener { if (name != backend) choose(name) }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        root.addView(small("Backend: ${backends[backend]}"))
        section(
            "Installation",
            if (started) "Its key made, registered and proved" else "Start: make the key, register it, prove it",
        ) { installation(it, started) }
        section(
            "Measurements",
            scan?.let { "${(it.report.report["probe_results"] as Map<*, *>).size} sent in the last scan" }
                ?: "What the phone measures, signed with its key",
        ) { measurements(it, started) }
        section("Account", if (signedIn) "Signed in" else "Sign in to the sample backend") { account(it, started, signedIn) }
        section("Requests", "A proof on each; the person's approval on a sensitive one") { requests(it, signedIn) }
        section("Requests a backend must refuse", "Made wrong on purpose") { refusals(it, started, signedIn) }
        section("Log", "${log.size} results, the latest first") { body -> log.forEach { body.addView(outcomeLine(it)) } }
    }

    private fun installation(body: LinearLayout, started: Boolean) {
        val identity = trust.identity
        val stepUp = trust.stepUpKey
        val stepUpOff = trust.stepUpUnavailable
        body.addView(
            actions(
                button(if (started) "Start again" else "Start", primary = true) { run("Installation", it) { start() } },
                button("Prove again", enabled = started) {
                    run("Installation", it) {
                        trust.prove()
                        "A new installation session."
                    }
                },
                button("New installation", enabled = identity != null, outlined = true) { newInstallation() },
            ),
        )
        latestLine(body, "Installation")
        if (identity != null) {
            body.addView(field("Installation ID", identity.installationId))
            body.addView(field("Key thumbprint", identity.keyThumbprint))
            body.addView(field("Key", "${identity.algorithm}, ${identity.provider}, ${identity.securityLevel}"))
            body.addView(field("Hardware-backed", if (identity.hardwareBacked) "yes" else "no"))
            body.addView(field("Can leave the phone", if (identity.privateKeyExportable) "yes" else "no"))
            body.addView(field("New this launch", if (identity.createdThisLaunch) "yes" else "no"))
            body.addView(
                field(
                    "Step-up key",
                    when {
                        stepUp != null -> "${stepUp.description}, ${stepUp.securityLevel}"
                        stepUpOff != null -> "off on this phone: ${stepUpOff.code}"
                        else -> "made at start"
                    },
                ),
            )
        }
    }

    private fun measurements(body: LinearLayout, started: Boolean) {
        val enabled = trust.personalMeasurements
        val locationOn = PersonalMeasurement.LOCATION in enabled || PersonalMeasurement.MOCK_LOCATION in enabled
        body.addView(title("Personal measurements"))
        body.addView(
            small("Measured only when this app turns one on and the backend asks for it. None is on until the app says so."),
        )
        body.addView(
            switch("All of them", null, enabled.size == PersonalMeasurement.entries.size) { on ->
                setPersonal(if (on) PersonalMeasurement.entries.toSet() else emptySet())
            },
        )
        for (measurement in PersonalMeasurement.entries) {
            val info = personalInfo(measurement)
            body.addView(
                switch(info.title, info.about, measurement in enabled) { on ->
                    setPersonal(if (on) enabled + measurement else enabled - measurement)
                },
            )
        }
        body.addView(code(personalCode(enabled)))
        if (locationOn) {
            body.addView(
                actions(
                    button(locationPermission?.let { "Location permission: $it" } ?: "Ask for the location permission") {
                        run("Measurements", it) {
                            val permission = trust.requestLocationPermission()
                            locationPermission = permission
                            "The app holds: $permission."
                        }
                    },
                ),
            )
        }
        body.addView(actions(button("Scan", enabled = started, primary = true) { run("Measurements", it) { doScan() } }))
        latestLine(body, "Measurements")
        scan?.let { scanView(body, it) }
    }

    /** The code for the personal measurements chosen above. */
    private fun personalCode(enabled: Set<PersonalMeasurement>): String =
        if (enabled.isEmpty()) {
            "trust.personalMeasurements = emptySet()"
        } else {
            (listOf("trust.personalMeasurements = setOf(") +
                PersonalMeasurement.entries.filter { it in enabled }.map { "    PersonalMeasurement.${it.name}," } +
                listOf(")")).joinToString("\n")
        }

    private fun account(body: LinearLayout, started: Boolean, signedIn: Boolean) {
        body.addView(handle)
        body.addView(password)
        body.addView(small("The sample makes the account at its first sign-in"))
        body.addView(
            actions(
                button("Sign in", enabled = started, primary = true) {
                    run("Account", it) {
                        api.signIn(handle.text.toString(), password.text.toString())
                        "An account session for this installation."
                    }
                },
                button("Sign out", enabled = signedIn, outlined = true) {
                    api.account = null
                    render()
                },
            ),
        )
        latestLine(body, "Account")
    }

    private fun requests(body: LinearLayout, signedIn: Boolean) {
        body.addView(
            small(
                "Each request carries a proof signed by this installation's key. The sensitive one also needs " +
                    "the person's approval: the step-up key, unlocked with the screen lock, or the app PIN.",
            ),
        )
        body.addView(
            actions(
                button("Protected request", enabled = signedIn) { run("Requests", it) { Show.json(api.protectedRequest()) } },
                button("Sensitive, without approval", enabled = signedIn, outlined = true) {
                    run("Requests", it, Expect.REFUSED) { Show.json(api.sensitive()) }
                },
                button("Sensitive, with step-up", enabled = signedIn) {
                    run("Requests", it) { Show.json(api.sensitive(stepUp = true)) }
                },
                button("Re-enrol the step-up key", enabled = signedIn) {
                    run("Requests", it) { Show.json(api.reenrolStepUp(password.text.toString())) }
                },
            ),
        )
        body.addView(pin)
        body.addView(small("The screen is protected while it is typed"))
        body.addView(
            actions(
                button("Set the app PIN", enabled = signedIn) {
                    run("Requests", it) {
                        api.setAppPin(pin.text.toString(), password.text.toString())
                        "Set, with the account password."
                    }
                },
                button("Sensitive, with the app PIN", enabled = signedIn) {
                    run("Requests", it) { Show.json(api.sensitive(appPin = pin.text.toString())) }
                },
            ),
        )
        latestLine(body, "Requests")
    }

    private fun refusals(body: LinearLayout, started: Boolean, signedIn: Boolean) {
        body.addView(small("Each of these is a request made wrong on purpose. A backend that makes the guide's checks refuses every one."))
        body.addView(
            actions(
                refusal("Send one request twice", signedIn) { api.replay() },
                refusal("Change the body after signing", signedIn) { api.changedBody() },
                refusal("Send it to another path", signedIn) { api.otherPath() },
                refusal("A proof ten minutes old", signedIn) { api.staleProof() },
            ),
        )
        body.addView(title("A session copied to another phone"))
        body.addView(
            small(
                "Copy this phone's session, get it to another phone running this example, paste it there and " +
                    "use it: that phone's key cannot sign for this installation.",
            ),
        )
        body.addView(
            actions(
                button("Copy this phone's session", enabled = signedIn, outlined = true) {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("session", api.shareSession()))
                    Toast.makeText(this, "Copied this phone's session", Toast.LENGTH_SHORT).show()
                },
            ),
        )
        body.addView(shared)
        val use = refusal("Use it here", started && shared.text.isNotBlank()) {
            api.sessionFromAnotherPhone(shared.text.toString())
        }
        useHere = use
        body.addView(actions(use))
        latestLine(body, "Refusals")
    }

    // --- Reading a scan.

    private fun scanView(body: LinearLayout, scan: ScanResult) {
        val results = scan.report.report["probe_results"] as Map<*, *>
        val required = scan.challenge.requiredProbes
        val optional = scan.challenge.optionalProbes
        val enabled = (scan.report.report["enabled_measurements"] as List<*>).map { it.toString() }
        val catalog = androidMeasurements + personalCatalog
        body.addView(
            small("The phone reports what it measured; your backend decides what it means.").apply {
                setTypeface(typeface, Typeface.ITALIC)
            },
        )
        for (group in MeasurementGroup.entries) {
            val members = catalog.filter {
                it.group == group &&
                    (group != MeasurementGroup.PERSONAL || it.name in optional || results.containsKey(it.name))
            }
            body.addView(title(group.title).apply { setPadding(0, dp(12), 0, 0) })
            body.addView(
                small(
                    if (group == MeasurementGroup.PERSONAL) {
                        "${group.note} On: ${if (enabled.isEmpty()) "none" else enabled.joinToString(", ")}."
                    } else {
                        group.note
                    },
                ),
            )
            for (info in members) measurementTile(body, info, results[info.name] as? Map<*, *>, info.name in required || info.name in optional)
            if (members.isEmpty()) body.addView(small("The backend asked for none."))
        }
        val other = results.keys.map { it.toString() }.filter { name -> catalog.none { it.name == name } }
        if (other.isNotEmpty()) {
            body.addView(title("Other measurements").apply { setPadding(0, dp(12), 0, 0) })
            body.addView(small("In the report as sent."))
            for (name in other) {
                measurementTile(body, MeasurementInfo(name, name, "Not described here.", MeasurementGroup.DEVICE), results[name] as? Map<*, *>, true)
            }
        }
    }

    /** One measurement: its title and status, opening to what it tells a backend and every field the phone sent. */
    private fun measurementTile(body: LinearLayout, info: MeasurementInfo, value: Map<*, *>?, asked: Boolean) {
        val unread = value?.get("unread") as? Map<*, *>
        val partly = unread != null && unread.isNotEmpty()
        val status = if (value == null) (if (asked) "missing" else "not asked") else (value["status"] as? String ?: "no status")
        val open = info.name in openMeasurements
        val warning = status == "error" || status == "missing" || (status == "not asked" && info.group == MeasurementGroup.HOOKS)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            addView(TextView(this@MainActivity).apply { text = info.title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (partly) addView(badge("unread", warning = true))
            if (value?.get("truncated") == true) addView(badge("truncated"))
            addView(badge(statusLabels[status] ?: status, warning = warning))
            setOnClickListener {
                if (!openMeasurements.add(info.name)) openMeasurements.remove(info.name)
                render()
            }
        }
        body.addView(header)
        body.addView(small(info.name).apply { typeface = Typeface.MONOSPACE })
        if (!open) return
        body.addView(small(info.about))
        if (value == null) {
            body.addView(
                small(
                    if (asked) {
                        "Asked for, but not in the report: a backend refuses such a report."
                    } else {
                        "Not asked for in this scan, so not measured."
                    },
                ),
            )
            return
        }
        if (value["status"] == "not_enabled") body.addView(small("The backend asked; this app has not turned it on."))
        if (partly) {
            body.addView(
                small("Parts it could not read are not measured: never read them as a clean \"no\".").apply { setTextColor(colorError) },
            )
        }
        val fields = value.entries.filter { it.key != "status" }.associate { it.key.toString() to it.value }
        for (row in fieldRows(fields)) body.addView(fieldLine(row))
    }

    // --- Pieces of the screen.

    private fun section(title: String, subtitle: String, content: (LinearLayout) -> Unit) {
        val open = title in expanded
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(242, 247, 246))
            }
        }
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply { text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTypeface(typeface, Typeface.BOLD) })
                addView(small(subtitle))
                setOnClickListener {
                    if (!expanded.add(title)) expanded.remove(title)
                    render()
                }
            },
        )
        if (open) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            content(body)
            card.addView(body)
        }
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun button(label: String, enabled: Boolean = true, primary: Boolean = false, outlined: Boolean = false, onClick: (String) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            isEnabled = !busy && enabled
            if (primary) setTextColor(colorPrimary)
            if (outlined) setTextColor(colorMuted)
            setOnClickListener { onClick(label) }
        }

    private fun refusal(label: String, enabled: Boolean, step: suspend () -> Map<String, Any?>): Button =
        button(label, enabled = enabled, outlined = true) { run("Refusals", it, Expect.REFUSED) { Show.json(step()) } }

    private fun actions(vararg buttons: Button): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        for (item in buttons) addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun latestLine(body: LinearLayout, section: String) {
        latest[section]?.let { body.addView(outcomeLine(it)) }
    }

    private fun outcomeLine(outcome: Outcome): View = TextView(this).apply {
        text = (if (outcome.asExpected) "✓ " else "✗ ") + "${outcome.action}: ${outcome.text}"
        setTextColor(if (outcome.asExpected) colorPrimary else colorError)
        setTextIsSelectable(true)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun field(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(2))
        addView(TextView(this@MainActivity).apply { text = label; setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        addView(TextView(this@MainActivity).apply { text = value; setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
    }

    private fun fieldLine(row: FieldRow): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12) * row.depth, dp(1), 0, dp(1))
        addView(small(row.name).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f))
        addView(small(row.value ?: "").apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 6f))
    }

    private fun switch(label: String, about: String?, on: Boolean, changed: (Boolean) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            Switch(this@MainActivity).apply {
                text = label
                isChecked = on
                isEnabled = !busy
                setOnCheckedChangeListener { _, checked -> changed(checked) }
            },
        )
        about?.let { addView(small(it)) }
    }

    private fun badge(label: String, warning: Boolean = false): View = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val color = when {
            warning -> colorError
            label == "measured" -> colorPrimary
            else -> colorMuted
        }
        setTextColor(color)
        setPadding(dp(6), dp(1), dp(6), dp(1))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), color)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
    }

    private fun code(text: String): View = TextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextIsSelectable(true)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.rgb(226, 232, 231)) }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun small(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(colorMuted)
    }

    private fun edit(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
