package net.plumt.qr.internal

import net.plumt.qr.EventTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 한 줄 요약의 공통 규칙. 비밀이 든 원문은 여기서 가린다. */
internal object Summaries {

    const val WIFI_LABEL: String = "Wi-Fi"
    const val OTP_LABEL: String = "OTP"

    private const val OTP_SCHEME = "otpauth://"
    private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

    /**
     * 구조 없는 원문의 첫 줄. `WIFI:` 는 비밀번호를 담을 수 있어 표지만, `otpauth` 는 경로의 계정 이름만 보인다
     * (쿼리의 `secret=` 은 버린다). `otpauth-migration://` 처럼 계정 이름이 없는 형식은 표지만 보인다.
     */
    fun text(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("WIFI:", ignoreCase = true) -> WIFI_LABEL
            trimmed.startsWith(OTP_SCHEME, ignoreCase = true) -> otpLabel(trimmed)
            trimmed.startsWith("otpauth", ignoreCase = true) -> OTP_LABEL
            else -> trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        }
    }

    /** 비밀(비밀번호·OTP 비밀)이 들 수 있는 원문인가 — [text] 가 표지로 가리는 것과 같은 기준이다. */
    fun mayHoldSecret(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("WIFI:", ignoreCase = true) || t.startsWith("otpauth", ignoreCase = true)
    }

    private val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#@]*@")

    /**
     * 주소의 사용자 정보(`사용자:비밀번호@`)를 `****@` 로 바꾼다. 앞 공백은 유니코드 공백(전각 등)까지 건너뛴다 —
     * 정규식 `\s` 는 엔진마다 공백 집합이 달라(JVM 은 ASCII, Android 는 유니코드) 가리기 여부가 갈렸다.
     */
    fun maskUserInfo(url: String): String {
        val lead = url.takeWhile { it.isWhitespace() }
        return lead + USER_INFO.replace(url.substring(lead.length)) { it.groupValues[1] + "****@" }
    }

    /** 종일은 날짜만, 시각은 [zone] 의 현지 시각. 시간대 없는 시각은 적힌 그대로 보인다. */
    fun eventTime(time: EventTime, zone: ZoneId): String = when (time) {
        is EventTime.AllDay -> time.date.format(DATE)
        is EventTime.Local -> (time.toZonedDateTime()?.withZoneSameInstant(zone)?.toLocalDateTime() ?: time.dateTime).format(DATE_TIME)
        is EventTime.Utc -> time.instant.atZone(zone).format(DATE_TIME)
    }

    private fun otpLabel(uri: String): String {
        val path = uri.substring(OTP_SCHEME.length).substringBefore('?').substringBefore('#')
        val label = path.substringAfter('/', missingDelimiterValue = "")
        return TextCodec.percentDecode(label, plusAsSpace = false).trim().lineSequence().firstOrNull()?.trim()
            .orEmpty().ifEmpty { OTP_LABEL }
    }
}
