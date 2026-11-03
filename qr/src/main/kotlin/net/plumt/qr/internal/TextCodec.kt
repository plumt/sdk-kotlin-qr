package net.plumt.qr.internal

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 내용 형식들이 함께 쓰는 이스케이프·분리·퍼센트 인코딩.
 *
 * 두 갈래의 이스케이프가 있다.
 * - 역슬래시형(`WIFI:`·`MECARD:`·`MATMSG:`): 특수문자 앞에 `\` 를 붙이고, 읽을 때는 `\` 뒤 한 글자를 그대로 둔다
 * - vCard·iCalendar 형(RFC 6350·5545): `\\`·`\,`·`\;`·`\n`
 */
internal object TextCodec {

    /** `WIFI:` 형식에서 이스케이프하는 글자. ZXing 이 정한 사실상 표준이다. */
    const val WIFI_SPECIALS: String = "\\;,:\""

    /** `MECARD:` 값에서 이스케이프하는 글자. 이름의 `,` 는 성·이름 구분자라 이름에만 더한다. */
    const val MECARD_SPECIALS: String = "\\;:"

    fun escapeBackslash(value: String, specials: String): String = buildString(value.length) {
        value.forEach { c ->
            if (c in specials) append('\\')
            append(c)
        }
    }

    fun unescapeBackslash(value: String): String = buildString(value.length) {
        var escaped = false
        value.forEach { c ->
            when {
                escaped -> { append(c); escaped = false }
                c == '\\' -> escaped = true
                else -> append(c)
            }
        }
        if (escaped) append('\\')
    }

    /** 이스케이프되지 않은 [delimiter] 로 나눈다. 조각 안의 이스케이프는 그대로 남긴다. */
    fun splitUnescaped(value: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        value.forEach { c ->
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' -> { current.append(c); escaped = true }
                c == delimiter -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    fun indexOfUnescaped(value: String, target: Char): Int {
        var escaped = false
        value.forEachIndexed { i, c ->
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == target -> return i
            }
        }
        return -1
    }

    /** `KEY:value;KEY:value;;` 꼴을 키(대문자)와 원문 값(이스케이프 유지) 쌍으로 푼다. 빈 조각은 버린다. */
    fun prefixedFields(body: String): List<Pair<String, String>> =
        splitUnescaped(body, ';').mapNotNull { field ->
            val colon = indexOfUnescaped(field, ':')
            if (colon <= 0) null else field.substring(0, colon).trim().uppercase() to field.substring(colon + 1)
        }

    fun escapeText(value: String): String = buildString(value.length) {
        value.replace("\r\n", "\n").replace('\r', '\n').forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                ',' -> append("\\,")
                ';' -> append("\\;")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    fun unescapeText(value: String): String = buildString(value.length) {
        var escaped = false
        value.forEach { c ->
            if (escaped) {
                append(if (c == 'n' || c == 'N') '\n' else c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                append(c)
            }
        }
        if (escaped) append('\\')
    }

    /**
     * RFC 3986 의 비예약 문자만 남기고 UTF-8 바이트를 `%XX` 로 바꾼다. 공백은 `%20` 이다.
     * 짝 없는 서로게이트는 UTF-8 로 나타낼 수 없어(조용히 `?` 로 바뀌면 값이 달라진다) [IllegalArgumentException] 이다.
     */
    fun percentEncode(value: String, keep: String = ""): String = buildString {
        require(isWellFormed(value)) { "짝 없는 서로게이트가 있어 UTF-8 로 나타낼 수 없다" }
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b < 0x80 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~" || c in keep)) {
                append(c)
            } else {
                append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
    }

    /**
     * `%XX` 를 UTF-8 로 되돌린다. 깨진 `%` 는 글자 그대로 두고, 잘못된 바이트열은 대체 문자가 된다 — 던지지 않는다.
     * [plusAsSpace] 면 `+` 를 공백으로 읽는다(폼 인코딩을 쓰는 생성기가 흔하다).
     */
    fun percentDecode(value: String, plusAsSpace: Boolean): String {
        if ('%' !in value && !(plusAsSpace && '+' in value)) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '%' && i + 2 < value.length) {
                hexValue(value[i + 1], value[i + 2])
            } else {
                -1
            }
            when {
                hex >= 0 -> { out.write(hex); i += 3 }
                c == '+' && plusAsSpace -> { out.write(' '.code); i++ }
                else -> {
                    val end = if (Character.isHighSurrogate(c) && i + 1 < value.length) i + 2 else i + 1
                    out.write(value.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 쿼리 문자열을 키(소문자)→값(디코드) 으로 푼다. 같은 키가 여러 번이면 처음 것만 남긴다. */
    fun queryParameters(query: String, plusAsSpace: Boolean = true): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (query.isEmpty()) return result
        query.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = percentDecode(pair.substringBefore('='), plusAsSpace).trim().lowercase()
            val value = percentDecode(pair.substringAfter('=', ""), plusAsSpace)
            if (key.isNotEmpty() && key !in result) result[key] = value
        }
        return result
    }

    fun isWellFormed(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                i++
            }
        }
        return true
    }

    /** 이름이 틀린 문자셋은 UTF-8 로 본다. */
    fun charsetOrUtf8(name: String?): Charset =
        if (name.isNullOrBlank()) Charsets.UTF_8 else runCatching { Charset.forName(name.trim('"')) }.getOrDefault(Charsets.UTF_8)

    private fun hexValue(high: Char, low: Char): Int {
        val h = Character.digit(high, 16)
        val l = Character.digit(low, 16)
        return if (h < 0 || l < 0) -1 else (h shl 4) or l
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
