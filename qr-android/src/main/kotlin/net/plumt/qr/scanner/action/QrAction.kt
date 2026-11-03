package net.plumt.qr.scanner.action

import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import java.net.IDN
import java.util.Locale

/**
 * 읽은 내용으로 할 수 있는 일 하나. 만드는 것만으로는 아무 일도 일어나지 않는다 — 실행은 앱이 [QrActionExecutor.execute] 를 불러야 한다.
 *
 * `toString` 은 비밀번호·OTP 비밀을 넣지 않는다.
 */
public sealed interface QrAction {

    /**
     * 링크 열기. 열기 전에 [host] 를 사용자에게 보인다 — 화면의 글자(유니코드)와 실제로 접속할 주소(ASCII)가 다를 수 있다.
     *
     * @property url 열 주소(원문 그대로)
     * @property host 실제 접속할 호스트(소문자, 국제화 도메인은 퓨니코드 `xn--`). 읽지 못하면 빈 문자열
     * @property displayHost 사람이 읽는 호스트(퓨니코드를 유니코드로 푼 것)
     * @property warnings 열기 전에 알려야 할 점
     */
    public data class OpenUrl(
        val url: String,
        val host: String,
        val displayHost: String,
        val warnings: Set<UrlWarning>,
    ) : QrAction

    /** Wi-Fi 연결. API 30 이상은 시스템 「네트워크 추가」 창(사용자가 승인), 그 아래·지원하지 않는 보안 방식은 Wi-Fi 설정 화면. */
    public data class ConnectWifi(val wifi: QrPayload.Wifi) : QrAction

    /** Wi-Fi 비밀번호를 클립보드에 넣는다(민감 표시). */
    public data class CopyWifiPassword(val wifi: QrPayload.Wifi) : QrAction

    /** 연락처 앱의 새 연락처 화면을 채워 연다. 저장은 사용자가 한다. */
    public data class AddContact(val contact: QrPayload.Contact) : QrAction

    /** 캘린더 앱의 일정 추가 화면을 채워 연다. 저장은 사용자가 한다. */
    public data class AddEvent(val event: QrPayload.Event) : QrAction

    /** 전화 앱에 번호를 띄운다(걸지 않는다 — `CALL_PHONE` 권한이 필요 없다). */
    public data class Dial(val number: String) : QrAction

    /** 문자 앱에 번호·내용을 채워 연다. 보내지 않는다. */
    public data class SendSms(val number: String, val message: String) : QrAction

    /** 메일 앱에 받는 사람·제목·본문을 채워 연다. 보내지 않는다. */
    public data class SendEmail(val address: String, val subject: String, val body: String) : QrAction

    /** 지도 앱에 위치를 띄운다. */
    public data class ShowLocation(val geo: QrPayload.Geo) : QrAction

    /** OTP 등록을 인증 앱에 넘긴다(`otpauth://` 를 처리하는 앱). 비밀이 그 앱으로 간다. */
    public data class AddOtp(val otp: QrPayload.Otp) : QrAction

    /** OTP 비밀을 클립보드에 넣는다(민감 표시). 인증 앱이 없을 때 쓴다. */
    public data class CopyOtpSecret(val otp: QrPayload.Otp) : QrAction

    /** 상품 번호를 기기의 웹 검색으로 찾는다. 검색 제공자는 기기가 고른다. */
    public data class SearchProduct(val code: String) : QrAction

    /**
     * 글을 클립보드에 넣는다.
     * @property text 넣을 글
     * @property sensitive 클립보드 미리보기·키보드 기록에 값을 가리라고 표시한다(Android 13+ 가 읽는다)
     */
    public data class CopyText(val text: String, val sensitive: Boolean = false) : QrAction {
        override fun toString(): String = if (sensitive) "CopyText(text=****, sensitive=true)" else "CopyText(text=$text, sensitive=false)"
    }
}

/** 링크를 열기 전에 알릴 점. */
public enum class UrlWarning {
    /** `http://` — 암호화되지 않는다. */
    NOT_HTTPS,

    /** 주소에 `사용자@` 부분이 있다. `https://bank.example.com@evil.com/` 은 evil.com 으로 간다. */
    USER_INFO,

    /** 국제화 도메인이다. 비슷하게 생긴 다른 글자로 만든 가짜 주소일 수 있다 — [QrAction.OpenUrl.host] 의 퓨니코드를 함께 보인다. */
    INTERNATIONALIZED,

    /** 도메인이 아니라 IP 주소다. */
    IP_ADDRESS,

    /** 호스트를 읽지 못했다. */
    UNREADABLE_HOST,
}

/** 내용별로 할 수 있는 일을 정한다. 아무것도 실행하지 않는다. 던지지 않는다. */
public object QrActions {

    /** 대표 동작 하나(칩을 탭했을 때 제안할 것). */
    @JvmStatic
    public fun primary(payload: QrPayload): QrAction = when (payload) {
        is QrPayload.Url -> openUrl(payload.url)
        is QrPayload.Wifi -> QrAction.ConnectWifi(payload)
        is QrPayload.Contact -> QrAction.AddContact(payload)
        is QrPayload.Event -> QrAction.AddEvent(payload)
        is QrPayload.Phone -> QrAction.Dial(payload.number.trim())
        is QrPayload.Sms -> QrAction.SendSms(payload.number.trim(), payload.message)
        is QrPayload.Email -> QrAction.SendEmail(payload.address.trim(), payload.subject, payload.body)
        is QrPayload.Geo -> QrAction.ShowLocation(payload)
        is QrPayload.Otp -> QrAction.AddOtp(payload)
        is QrPayload.Product -> QrAction.SearchProduct(payload.code.trim())
        is QrPayload.Text -> QrAction.CopyText(payload.text)
    }

    /**
     * 할 수 있는 일 전부. 첫째가 [primary] 이고, 비밀이 있는 내용은 비밀 복사를, 그 밖은 원문 복사를 뒤에 붙인다.
     * Wi-Fi·OTP 의 원문 복사는 넣지 않는다(원문에 비밀이 든다 — 비밀 복사로 대신한다).
     */
    @JvmStatic
    public fun all(result: ScanResult): List<QrAction> {
        val p = result.payload
        val primary = primary(p)
        return when (p) {
            is QrPayload.Wifi -> if (p.password.isEmpty()) listOf(primary) else listOf(primary, QrAction.CopyWifiPassword(p))
            is QrPayload.Otp -> listOf(primary, QrAction.CopyOtpSecret(p))
            is QrPayload.Text -> listOf(primary)
            else -> listOf(primary, QrAction.CopyText(result.text))
        }
    }

    /** 주소를 살펴 [QrAction.OpenUrl] 을 만든다. */
    @JvmStatic
    public fun openUrl(url: String): QrAction.OpenUrl {
        val trimmed = url.trim()
        val warnings = LinkedHashSet<UrlWarning>()
        val schemeEnd = trimmed.indexOf("://")
        val scheme = if (schemeEnd > 0) trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT) else ""
        if (scheme == "http") warnings += UrlWarning.NOT_HTTPS
        val afterScheme = if (schemeEnd > 0) trimmed.substring(schemeEnd + 3) else trimmed
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore('\\')
        val at = authority.lastIndexOf('@')
        if (at >= 0) warnings += UrlWarning.USER_INFO
        val hostPort = authority.substring(at + 1)
        val rawHost = when {
            hostPort.startsWith("[") -> hostPort.substringBefore(']') + if (']' in hostPort) "]" else ""
            else -> hostPort.substringBefore(':')
        }.trimEnd('.').lowercase(Locale.ROOT)

        if (rawHost.isEmpty()) {
            warnings += UrlWarning.UNREADABLE_HOST
            return QrAction.OpenUrl(trimmed, "", "", warnings)
        }
        if (isIp(rawHost)) {
            warnings += UrlWarning.IP_ADDRESS
            return QrAction.OpenUrl(trimmed, rawHost, rawHost, warnings)
        }
        val ascii = try {
            IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            // 도메인 규칙에 맞지 않는 호스트다 — 경고로 알리고 원문 호스트를 그대로 보인다
            warnings += UrlWarning.UNREADABLE_HOST
            return QrAction.OpenUrl(trimmed, rawHost, rawHost, warnings)
        }
        val display = IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED)
        if (ascii.split('.').any { it.startsWith("xn--") }) warnings += UrlWarning.INTERNATIONALIZED
        return QrAction.OpenUrl(trimmed, ascii, display, warnings)
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private fun isIp(host: String): Boolean = host.startsWith("[") || IPV4.matches(host)
}
