package net.plumt.qr.internal

/**
 * 스킴 없는 이메일 주소·전화번호 판정. 정규식 없이 한 번 훑는다(입력 길이에 선형, ASCII 만).
 * 규칙의 정본은 [net.plumt.qr.ParseOptions] 의 KDoc 표다.
 */
internal object BareContact {

    private const val MAX_EMAIL = 254
    private const val MAX_LOCAL = 64
    private const val MAX_LABEL = 63
    private const val MAX_PHONE = 32
    private const val LOCAL_SYMBOLS = "._%+-"

    fun isEmail(t: String): Boolean {
        if (t.length > MAX_EMAIL) return false
        val at = t.indexOf('@')
        if (at <= 0 || at != t.lastIndexOf('@')) return false
        val local = t.substring(0, at)
        val domain = t.substring(at + 1)
        return isLocal(local) && isDomain(domain)
    }

    private fun isLocal(s: String): Boolean {
        if (s.length > MAX_LOCAL) return false
        if (s.first() == '.' || s.last() == '.' || ".." in s) return false
        return s.all { isAsciiAlnum(it) || it in LOCAL_SYMBOLS }
    }

    private fun isDomain(s: String): Boolean {
        val labels = s.split('.')
        if (labels.size < 2) return false
        if (!labels.all(::isLabel)) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private fun isLabel(s: String): Boolean =
        s.length in 1..MAX_LABEL && s.first() != '-' && s.last() != '-' && s.all { isAsciiAlnum(it) || it == '-' }

    fun isPhone(t: String): Boolean {
        if (t.isEmpty() || t.length > MAX_PHONE) return false
        val body = if (t[0] == '+') t.substring(1) else t
        if (body.isEmpty() || !(body[0] in '0'..'9' || body[0] == '(')) return false
        var digits = 0
        var depth = 0
        for (c in body) {
            when (c) {
                in '0'..'9' -> digits++
                ' ', '-' -> Unit
                '(' -> if (++depth > 1) return false
                ')' -> if (--depth < 0) return false
                else -> return false
            }
        }
        if (depth != 0 || body.last() !in '0'..'9') return false
        return digits in 7..15 && !looksLikeDate(body)
    }

    /** `2024-01-15`·`2024 01 15` — 4·2·2 자리 세 묶음이고 연이 19xx·20xx, 월 01..12, 일 01..31. */
    private fun looksLikeDate(s: String): Boolean {
        val groups = s.split('-', ' ').filter(String::isNotEmpty)
        if (groups.size != 3 || groups.map(String::length) != listOf(4, 2, 2)) return false
        val (y, m, d) = groups.map { g -> g.toIntOrNull() ?: return false }
        return y in 1900..2099 && m in 1..12 && d in 1..31
    }

    private fun isAsciiAlnum(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'
}
