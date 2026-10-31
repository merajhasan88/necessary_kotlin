package com.necessarysdk

import java.security.MessageDigest
import java.security.SecureRandom

private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** Base64url without padding: how every binary value travels. */
fun base64UrlNoPadding(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size * 4 + 2) / 3)
    var index = 0
    while (index + 3 <= bytes.size) {
        val chunk = ((bytes[index].toInt() and 0xff) shl 16) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or (bytes[index + 2].toInt() and 0xff)
        out.append(BASE64URL[chunk shr 18]).append(BASE64URL[(chunk shr 12) and 63])
            .append(BASE64URL[(chunk shr 6) and 63]).append(BASE64URL[chunk and 63])
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val chunk = (bytes[index].toInt() and 0xff) shl 16
            out.append(BASE64URL[chunk shr 18]).append(BASE64URL[(chunk shr 12) and 63])
        }
        2 -> {
            val chunk = ((bytes[index].toInt() and 0xff) shl 16) or ((bytes[index + 1].toInt() and 0xff) shl 8)
            out.append(BASE64URL[chunk shr 18]).append(BASE64URL[(chunk shr 12) and 63])
                .append(BASE64URL[(chunk shr 6) and 63])
        }
    }
    return out.toString()
}

/** The bytes of a base64url value, with or without padding. */
fun base64UrlDecode(text: String): ByteArray {
    val value = text.trimEnd('=')
    require(value.length % 4 != 1) { "not base64url: $text" }
    val out = java.io.ByteArrayOutputStream(value.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in value) {
        val digit = BASE64URL.indexOf(c)
        require(digit >= 0) { "not base64url: $text" }
        buffer = (buffer shl 6) or digit
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xff)
        }
    }
    return out.toByteArray()
}

/** 32 random bytes from the platform's secure source. */
fun freshNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

internal fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

/** A request target as a request proof signs it: the path, and the query after '?' (no fragment). */
internal class RequestTarget(target: String) {
    val path: String
    val query: String

    init {
        val withoutFragment = target.substringBefore('#')
        val mark = withoutFragment.indexOf('?')
        path = if (mark < 0) withoutFragment else withoutFragment.substring(0, mark)
        query = if (mark < 0) "" else withoutFragment.substring(mark + 1)
    }
}
