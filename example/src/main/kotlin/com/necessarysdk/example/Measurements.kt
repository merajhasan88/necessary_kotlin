// Reading a scan: every measurement the signed report carried, each with its
// status and its fields as the phone sent them, grouped by what it catches --
// the hooks, gadgets and changes in memory first. A measurement the backend
// did not ask for shows as not asked rather than disappearing. The titles and
// descriptions follow the integration guide's sections 6 and 8.
package com.necessarysdk.example

import com.necessarysdk.PersonalMeasurement

/** What a group of measurements catches. */
enum class MeasurementGroup(val title: String, val note: String) {
    HOOKS(
        "Hooks, gadgets and memory changes",
        "Frida and other instrumentation, found by name, by port, by thread and by memory layout; " +
            "debuggers and tracers; and the code in memory compared with its file, where an inline hook " +
            "changes bytes. A backend asks for every one of these on every scan.",
    ),
    APP("The app as built", "What is installed: its identity, its hashes and its signer."),
    SYSTEM("Root and the system", "Traces of root, and how the system is set up."),
    DEVICE("Device and network", "Sent with every scan, asked for or not."),
    PERSONAL(
        "Personal measurements",
        "Asked for by the backend, and measured only where this app turned them on.",
    ),
}

/** A measurement's name in the report, a title, what it tells a backend, and its group. */
class MeasurementInfo(val name: String, val title: String, val about: String, val group: MeasurementGroup)

val androidMeasurements: List<MeasurementInfo> = listOf(
    MeasurementInfo(
        "runtime_maps", "Memory map",
        "Instrumentation and hooking frameworks named in the app's memory map: Frida, its gadget, " +
            "Objection, Xposed and others.",
        MeasurementGroup.HOOKS,
    ),
    MeasurementInfo("frida_ports", "Frida ports", "Frida's default local ports accepting connections.", MeasurementGroup.HOOKS),
    MeasurementInfo(
        "instrumentation_threads", "Instrumentation threads",
        "Threads an instrumentation runtime creates, even renamed.", MeasurementGroup.HOOKS,
    ),
    MeasurementInfo(
        "exec_mappings", "Executable memory",
        "Memory both writable and executable, executable memory from deleted files, unlabelled " +
            "executable regions: where injected code lives.",
        MeasurementGroup.HOOKS,
    ),
    MeasurementInfo(
        "code_integrity", "Code integrity",
        "The app's native code and the system libraries in memory compared with their files: an inline " +
            "hook changes bytes, and diff_bytes counts them.",
        MeasurementGroup.HOOKS,
    ),
    MeasurementInfo("tracer", "Tracer", "Another process tracing the app.", MeasurementGroup.HOOKS),
    MeasurementInfo("debug_state", "Debugger", "A Java debugger attached or awaited.", MeasurementGroup.HOOKS),
    MeasurementInfo(
        "app_identity", "App identity",
        "The installed app: package, the base APK hash, signing certificates, flags, install source and " +
            "split APKs.",
        MeasurementGroup.APP,
    ),
    MeasurementInfo(
        "root_files", "Root files", "Known root, su and framework paths that exist; a test-keys build.",
        MeasurementGroup.SYSTEM,
    ),
    MeasurementInfo("root_shell", "Root shell", "Whether su resolves from the app's shell.", MeasurementGroup.SYSTEM),
    MeasurementInfo("system_properties", "System properties", "Build and boot-security properties.", MeasurementGroup.SYSTEM),
    MeasurementInfo("selinux", "SELinux", "The SELinux mode, read two ways.", MeasurementGroup.SYSTEM),
    MeasurementInfo("mounts", "Mounts", "System partitions mounted writable.", MeasurementGroup.SYSTEM),
    MeasurementInfo("developer_settings", "Developer settings", "Developer options and USB debugging.", MeasurementGroup.SYSTEM),
    MeasurementInfo("emulator", "Emulator", "Build values typical of an emulator.", MeasurementGroup.SYSTEM),
    MeasurementInfo(
        "device", "Device",
        "The OS version, security patch level, model, build, the app's ABI and memory page size.",
        MeasurementGroup.DEVICE,
    ),
    MeasurementInfo("network", "Network", "A VPN up on any network; an HTTP proxy set.", MeasurementGroup.DEVICE),
)

/** The personal measurements, in the order of [PersonalMeasurement]. */
val personalCatalog: List<MeasurementInfo> = listOf(
    MeasurementInfo(
        "local_addresses", "Local addresses", "The phone's own IP addresses, link-local ones left out.",
        MeasurementGroup.PERSONAL,
    ),
    MeasurementInfo(
        "accessibility", "Accessibility", "The Accessibility services that are on, and the keyboard in use.",
        MeasurementGroup.PERSONAL,
    ),
    MeasurementInfo(
        "notification_access", "Notification access", "The apps allowed to read notifications.",
        MeasurementGroup.PERSONAL,
    ),
    MeasurementInfo(
        "other_access", "Other access", "Device-administrator apps, and known remote-control apps installed.",
        MeasurementGroup.PERSONAL,
    ),
    MeasurementInfo(
        "screen_capture", "Screen capture", "The screen being recorded, mirrored or cast.",
        MeasurementGroup.PERSONAL,
    ),
    MeasurementInfo(
        "location", "Location",
        "The last location the phone already knows: never a fresh fix, never a prompt. Needs the location " +
            "permission.",
        MeasurementGroup.PERSONAL,
    ),
    MeasurementInfo("mock_location", "Mock location", "Whether that location was faked.", MeasurementGroup.PERSONAL),
)

/** What a personal measurement reports, for its switch. */
fun personalInfo(measurement: PersonalMeasurement): MeasurementInfo =
    personalCatalog.first { it.name == measurement.wireName }

/** A measurement's status as the badge says it: whether the phone measured it, never whether what it found is good or bad. */
val statusLabels: Map<String, String> = mapOf("ok" to "measured", "error" to "failed", "not_enabled" to "off")

/** One line of a measurement's fields: its name, its value, and how deep it is nested. */
class FieldRow(val name: String, val value: String?, val depth: Int)

/** Every field of [fields], nested maps and lists indented under their name. */
fun fieldRows(fields: Map<String, Any?>, depth: Int = 0): List<FieldRow> {
    val rows = mutableListOf<FieldRow>()
    for ((name, value) in fields) {
        if (value is Map<*, *> && value.isNotEmpty()) {
            rows.add(FieldRow(name, null, depth))
            rows.addAll(fieldRows(value.entries.associate { it.key.toString() to it.value }, depth + 1))
        } else if (value is List<*> && value.any { it is Map<*, *> }) {
            rows.add(FieldRow(name, "${value.size}", depth))
            value.forEachIndexed { i, item ->
                if (item is Map<*, *>) {
                    rows.addAll(fieldRows(item.entries.associate { "${i + 1}. ${it.key}" to it.value }, depth + 1))
                } else {
                    rows.add(FieldRow("${i + 1}.", scalar(item), depth + 1))
                }
            }
        } else {
            rows.add(FieldRow(name, scalar(value), depth))
        }
    }
    return rows
}

private fun scalar(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> if (value) "yes" else "no"
    is List<*> -> if (value.isEmpty()) "none" else value.joinToString(", ") { scalar(it) }
    is Map<*, *> -> "none"
    else -> value.toString().ifEmpty { "\"\"" }
}
