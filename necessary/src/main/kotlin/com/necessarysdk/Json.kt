package com.necessarysdk

import java.math.BigDecimal

/**
 * The SDK's JSON: what it signs and sends, and what it reads back. Objects keep
 * their members in the order they were put in or read, so a body is sent
 * exactly as it was built; nothing is re-encoded after it is signed.
 *
 * Encoding, for the shared vectors' bytes: compact (no spaces), text beyond
 * ASCII as itself (UTF-8 on the wire), `"` and `\` escaped, control characters
 * as `\b \t \n \f \r` or `\u00xx`, a lone surrogate as `\uxxxx` (lower-case
 * hex). Integers as digits; a fractional number in its shortest plain form
 * with at least one decimal (`1.0`), and in exponent form (`1e+21`, `1e-7`)
 * from 1e21 and below 1e-6.
 */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    /** [text] as a JSON value: objects as ordered maps, lists, strings, Long or Double, Boolean, null. */
    fun decode(text: String): Any? = Reader(text).readDocument()

    /** [text] as a JSON object, or a [JsonFormatException] if it is anything else. */
    fun decodeObject(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return decode(text) as? Map<String, Any?> ?: throw JsonFormatException("not a JSON object")
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is Number -> out.append(number(value.toDouble()))
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    require(key is String) { "a JSON object's member names are strings: $key" }
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    write(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.asList())
            else -> throw IllegalArgumentException("not a JSON value: ${value.javaClass.name}")
        }
    }

    private fun number(value: Double): String {
        require(!value.isNaN() && !value.isInfinite()) { "not a JSON number: $value" }
        if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
        val magnitude = Math.abs(value)
        val exact = BigDecimal(value.toString())
        if (magnitude >= 1e21 || magnitude < 1e-6) {
            val digits = exact.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            val exponent = exact.precision() - exact.scale() - 1
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            return (if (value < 0) "-" else "") + mantissa + "e" + (if (exponent >= 0) "+" else "") + exponent
        }
        val plain = exact.stripTrailingZeros().toPlainString()
        return if (plain.contains('.')) plain else "$plain.0"
    }

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c.code < 0x20 -> when (c) {
                    '\b' -> out.append("\\b")
                    '\t' -> out.append("\\t")
                    '\n' -> out.append("\\n")
                    '\u000c' -> out.append("\\f")
                    '\r' -> out.append("\\r")
                    else -> out.append("\\u00").append(hex(c.code shr 4)).append(hex(c.code and 0xf))
                }
                Character.isHighSurrogate(c) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    out.append(c).append(text[index + 1])
                    index++
                }
                Character.isSurrogate(c) -> {
                    out.append("\\u")
                    for (shift in intArrayOf(12, 8, 4, 0)) out.append(hex((c.code shr shift) and 0xf))
                }
                else -> out.append(c)
            }
            index++
        }
        out.append('"')
    }

    private fun hex(digit: Int): Char = if (digit < 10) ('0' + digit) else ('a' + (digit - 10))

    private class Reader(private val text: String) {
        private var at = 0

        fun readDocument(): Any? {
            skipSpace()
            val value = readValue()
            skipSpace()
            if (at != text.length) fail("unexpected text after the value")
            return value
        }

        private fun readValue(): Any? {
            if (at >= text.length) fail("unexpected end")
            return when (val c = text[at]) {
                '{' -> readObject()
                '[' -> readList()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected character '$c'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            at++
            skipSpace()
            if (peek() == '}') {
                at++
                return result
            }
            while (true) {
                skipSpace()
                if (peek() != '"') fail("expected a member name")
                val key = readString()
                skipSpace()
                if (peek() != ':') fail("expected ':'")
                at++
                skipSpace()
                result[key] = readValue()
                skipSpace()
                when (peek()) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return result
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readList(): List<Any?> {
            val result = ArrayList<Any?>()
            at++
            skipSpace()
            if (peek() == ']') {
                at++
                return result
            }
            while (true) {
                skipSpace()
                result.add(readValue())
                skipSpace()
                when (peek()) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return result
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            val out = StringBuilder()
            at++
            while (true) {
                if (at >= text.length) fail("unterminated string")
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (at >= text.length) fail("unterminated escape")
                        when (val e = text[at++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) fail("short \\u escape")
                                val code = text.substring(at, at + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                out.append(code.toChar())
                                at += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c.code < 0x20 -> fail("a control character in a string")
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = at
            if (peek() == '-') at++
            if (peek() == '0') {
                at++
            } else if (peek() in '1'..'9') {
                while (peek() in '0'..'9') at++
            } else {
                fail("bad number")
            }
            var integral = true
            if (peek() == '.') {
                integral = false
                at++
                if (peek() !in '0'..'9') fail("bad number")
                while (peek() in '0'..'9') at++
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                at++
                if (peek() == '+' || peek() == '-') at++
                if (peek() !in '0'..'9') fail("bad number")
                while (peek() in '0'..'9') at++
            }
            val literal = text.substring(start, at)
            if (integral) literal.toLongOrNull()?.let { return it }
            return literal.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) fail("unexpected text")
            at += word.length
            return value
        }

        private fun peek(): Char = if (at < text.length) text[at] else '\u0000'

        private fun skipSpace() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }

        private fun fail(why: String): Nothing = throw JsonFormatException("$why at $at")
    }
}

internal class JsonFormatException(message: String) : IllegalArgumentException(message)
