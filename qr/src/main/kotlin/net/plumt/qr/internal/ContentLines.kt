package net.plumt.qr.internal

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * vCard·iCalendar 의 "내용 줄"(`group.NAME;PARAM=V;BARE:value`).
 *
 * @property name 속성 이름(대문자, 그룹 접두사 제거)
 * @property params 매개변수. 값 없는 매개변수(vCard 2.1 의 `TEL;CELL:`)는 값이 null 이다
 * @property rawValue `:` 뒤의 원문(이스케이프·인코딩 유지)
 */
internal class ContentLine(
    val name: String,
    val params: List<Pair<String, String?>>,
    val rawValue: String,
) {
    fun param(key: String): String? = params.firstOrNull { it.first == key }?.second?.trim('"')

    /** `TYPE=a,b`·`TYPE="a,b"`·`TYPE=a;TYPE=b`·2.1 의 맨 매개변수를 모두 모은 대문자 집합. */
    val types: Set<String> by lazy {
        params.flatMap { (key, value) ->
            when {
                key == "TYPE" && value != null -> value.trim('"').split(',')
                value == null -> listOf(key)
                else -> emptyList()
            }
        }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** base64 등 글이 아닌 값. 이런 속성(사진 등)은 읽지 않는다. */
    val isBinary: Boolean
        get() = param("ENCODING")?.uppercase().let { it == "B" || it == "BASE64" } || "BASE64" in types

    /** QUOTED-PRINTABLE·CHARSET 을 풀었지만 이스케이프는 남긴 값. */
    val decodedValue: String by lazy {
        val qp = param("ENCODING")?.uppercase() == "QUOTED-PRINTABLE" || "QUOTED-PRINTABLE" in types
        if (qp) quotedPrintable(rawValue, TextCodec.charsetOrUtf8(param("CHARSET"))) else rawValue
    }

    /** 이스케이프까지 푼 글 값(앞뒤 공백 제거). */
    fun text(): String = TextCodec.unescapeText(decodedValue).trim()

    /** `;` 로 나뉜 구조 값(`N`·`ADR`·`ORG`)의 각 구성 요소. */
    fun components(): List<String> =
        TextCodec.splitUnescaped(decodedValue, ';').map { TextCodec.unescapeText(it).trim() }

    private fun quotedPrintable(value: String, charset: java.nio.charset.Charset): String {
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val h = if (c == '=') hex(value, i + 1) else -1
            if (h >= 0) {
                out.write(h)
                i += 3
                continue
            }
            out.write(c.toString().toByteArray(charset))
            i++
        }
        return String(out.toByteArray(), charset)
    }

    private fun hex(value: String, at: Int): Int {
        if (at + 1 >= value.length) return -1
        val h = Character.digit(value[at], 16)
        val l = Character.digit(value[at + 1], 16)
        return if (h < 0 || l < 0) -1 else (h shl 4) or l
    }
}

internal object ContentLines {

    private val LINE_BREAK = Regex("\r\n|\n|\r")

    /**
     * 원문을 내용 줄로 나눈다. 접힌 줄(다음 줄이 공백·탭으로 시작)과 QUOTED-PRINTABLE 의 소프트 줄바꿈(`=` 로 끝남)을 잇는다.
     * 줄바꿈이 하나도 없으면 줄바꿈이 공백으로 뭉개진 것으로 보고 [boundary] 의 속성 이름 앞에서 끊는다([splitCollapsed]).
     */
    fun parse(content: String, boundary: Boundary): List<ContentLine> {
        val physical = content.split(LINE_BREAK).let { lines ->
            if (lines.size == 1) splitCollapsed(content, boundary) else lines
        }
        val logical = mutableListOf<StringBuilder>()
        physical.forEach { line ->
            val last = logical.lastOrNull()
            when {
                last != null && (line.startsWith(' ') || line.startsWith('\t')) -> last.append(line, 1, line.length)
                last != null && last.endsWith("=") && isQuotedPrintable(last) -> {
                    last.setLength(last.length - 1)
                    last.append(line)
                }
                else -> logical += StringBuilder(line)
            }
        }
        return logical.mapNotNull { parseLine(it.toString().trim()) }
    }

    /**
     * 뭉개진 원문에서 줄 경계로 볼 속성 이름.
     * @property names 대문자 속성 이름
     * @property allowGroup `item1.TEL:` 처럼 그룹 접두사(`[A-Za-z0-9-]+.`)를 허용하는가
     */
    class Boundary(val names: Set<String>, val allowGroup: Boolean)

    /**
     * 공백 덩어리(정규식 `\s` 와 같은 ASCII 공백) 뒤에 `[그룹.]이름` + `:`·`;` 가 오면 그 덩어리를 지우고 나눈다.
     *
     * 한 번 훑기다(선형 시간). 공백 덩어리 끝에서만 바로 뒤의 토큰 하나를 본다 — 토큰은 공백이 아니므로 덩어리와 겹치지 않아
     * 글자마다 많아야 두 번 읽는다. 전방탐색 정규식(`\s+(?=...)`)은 키워드가 없는 긴 공백 덩어리에서 시작 위치마다
     * 덩어리 끝까지 되돌아가 이차 시간이 된다.
     */
    fun splitCollapsed(content: String, boundary: Boundary): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var i = 0
        val n = content.length
        while (i < n) {
            if (!isSpace(content[i])) {
                i++
                continue
            }
            val runStart = i
            while (i < n && isSpace(content[i])) i++
            if (i < n && startsProperty(content, i, boundary)) {
                parts += content.substring(start, runStart)
                start = i
            }
        }
        parts += content.substring(start)
        return parts
    }

    private fun startsProperty(s: String, at: Int, boundary: Boundary): Boolean {
        val end = tokenEnd(s, at)
        if (end == at || end >= s.length) return false
        return when (s[end]) {
            ':', ';' -> s.substring(at, end).uppercase(Locale.ROOT) in boundary.names
            '.' -> {
                if (!boundary.allowGroup) return false
                val nameEnd = tokenEnd(s, end + 1)
                nameEnd > end + 1 && nameEnd < s.length && (s[nameEnd] == ':' || s[nameEnd] == ';') &&
                    s.substring(end + 1, nameEnd).uppercase(Locale.ROOT) in boundary.names
            }
            else -> false
        }
    }

    private fun tokenEnd(s: String, from: Int): Int {
        var j = from
        while (j < s.length && isTokenChar(s[j])) j++
        return j
    }

    private fun isTokenChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-'

    // java.util.regex 의 \s(UNICODE_CHARACTER_CLASS 없음)와 같은 집합
    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    private fun isQuotedPrintable(line: CharSequence): Boolean {
        val colon = line.indexOf(':')
        return colon > 0 && line.substring(0, colon).uppercase().contains("QUOTED-PRINTABLE")
    }

    private fun parseLine(line: String): ContentLine? {
        if (line.isEmpty()) return null
        val colon = indexOutsideQuotes(line, ':')
        if (colon <= 0) return null
        val head = splitOutsideQuotes(line.substring(0, colon), ';')
        val name = head.first().substringAfterLast('.').trim().uppercase()
        if (name.isEmpty()) return null
        val params = head.drop(1).filter { it.isNotBlank() }.map { param ->
            val eq = param.indexOf('=')
            if (eq < 0) param.trim().uppercase() to null else param.substring(0, eq).trim().uppercase() to param.substring(eq + 1).trim()
        }
        return ContentLine(name, params, line.substring(colon + 1))
    }

    private fun indexOutsideQuotes(value: String, target: Char): Int {
        var quoted = false
        value.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted else if (c == target && !quoted) return i
        }
        return -1
    }

    private fun splitOutsideQuotes(value: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        value.forEach { c ->
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == delimiter && !quoted -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }
}
