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
     * 직접 만들 수 없다 — [QrActions.openUrl] 이 주소에서 [host]·[warnings] 를 계산해 만든다. 호스트·경고를 밖에서 넣을 수 있으면
     * 확인창은 `bank.com` 을 보이고 다른 곳을 여는 값을 만들 수 있기 때문이다. 기록·서버에서 복원할 때도 주소만 저장하고 다시 만든다.
     *
     * @property url 열 주소. 스킴이 없으면 `https://` 를 붙인 것이다
     * @property host 실제 접속할 호스트(소문자, 국제화 도메인은 퓨니코드 `xn--`, 숫자 IPv4 는 `a.b.c.d`). 읽지 못하면 빈 문자열
     * @property displayHost 사람이 읽는 호스트(퓨니코드를 유니코드로 푼 것)
     * @property warnings 열기 전에 알려야 할 점. [UrlWarning.NON_WEB_SCHEME] 이 있으면 기본 실행기는 열지 않는다
     */
    @ConsistentCopyVisibility
    public data class OpenUrl internal constructor(
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

    /**
     * http·https 가 아닌 주소(`intent:`·`javascript:`·`file:`·앱 스킴 등)다. 기본 실행기는 열지 않는다 — 다른 앱을 실행하거나 스크립트가 돌 수 있다.
     * 앱이 [AppLinkPolicy] 로 고른 스킴만 예외로 연다.
     */
    NON_WEB_SCHEME,
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

    /**
     * [primary] 에 앱 링크를 더한다 — 구조 없는 글([QrPayload.Text])이 [appLinks] 가 허용한 스킴의 주소면 [openUrl] 이고,
     * 그 밖은 [primary] 와 같다. 기본 정책([AppLinkPolicy.NONE])이면 언제나 [primary] 와 같다.
     * 앱 링크 동작은 [requiresConfirmation] 이 참이다 — 실행 전에 확인창을 띄운다.
     */
    @JvmStatic
    public fun primary(payload: QrPayload, appLinks: AppLinkPolicy): QrAction =
        if (payload is QrPayload.Text && appLinks.allows(payload.text)) openUrl(payload.text) else primary(payload)

    /** [all] 에 앱 링크를 더한다. 허용한 앱 링크면 `[열기, 원문 복사]` 이고, 그 밖은 [all] 과 같다. */
    @JvmStatic
    public fun all(result: ScanResult, appLinks: AppLinkPolicy): List<QrAction> {
        val p = result.payload
        if (p is QrPayload.Text && appLinks.allows(p.text)) return listOf(openUrl(p.text), QrAction.CopyText(result.text))
        return all(result)
    }

    /**
     * 실행 전에 사용자 확인을 꼭 받아야 하는 동작인가. 링크 중 [UrlWarning.NOT_HTTPS] 말고 다른 경고가 있는 것 —
     * 다른 앱을 바로 깨우는 앱 링크([UrlWarning.NON_WEB_SCHEME]), `사용자@`·국제화 도메인·IP·읽을 수 없는 호스트 — 이 참이다.
     * 실행기는 확인을 거쳤는지 알 수 없다. 이 값이 참이면 [QrActionConfirmation] 같은 확인창을 띄운 뒤 실행한다.
     */
    @JvmStatic
    public fun requiresConfirmation(action: QrAction): Boolean =
        action is QrAction.OpenUrl && action.warnings.any { it != UrlWarning.NOT_HTTPS }

    /**
     * 주소를 살펴 [QrAction.OpenUrl] 을 만든다. 던지지 않는다.
     *
     * - 스킴이 없으면 `https://` 를 붙인다(`host:443` 처럼 콜론 뒤가 포트인 것은 스킴이 아니다)
     * - http·https 가 아니면 [UrlWarning.NON_WEB_SCHEME]
     * - 호스트가 숫자로 끝나면 브라우저(WHATWG URL)처럼 IPv4 로 읽는다 — `2130706433`·`0x7f000001`·`127.1`·`0177.0.0.1` 은
     *   모두 `127.0.0.1` 이고 [UrlWarning.IP_ADDRESS] 다. IPv4 로 읽히지 않으면 [UrlWarning.UNREADABLE_HOST] 도 붙인다
     */
    @JvmStatic
    public fun openUrl(url: String): QrAction.OpenUrl {
        val trimmed = url.trim()
        val warnings = LinkedHashSet<UrlWarning>()
        val scheme = schemeOf(trimmed)
        val full = if (scheme == null) "https://$trimmed" else trimmed
        when (scheme) {
            null, "https" -> Unit
            "http" -> warnings += UrlWarning.NOT_HTTPS
            else -> warnings += UrlWarning.NON_WEB_SCHEME
        }
        val schemeEnd = full.indexOf("://")
        if (schemeEnd < 0 || (scheme != null && schemeEnd != scheme.length)) {
            // `javascript:...`·`mailto:...` 처럼 권한부(//호스트)가 없는 주소
            warnings += UrlWarning.UNREADABLE_HOST
            return QrAction.OpenUrl(full, "", "", warnings)
        }
        val afterScheme = full.substring(schemeEnd + 3)
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
            return QrAction.OpenUrl(full, "", "", warnings)
        }
        if (rawHost.startsWith("[")) {
            warnings += UrlWarning.IP_ADDRESS
            return QrAction.OpenUrl(full, rawHost, rawHost, warnings)
        }
        // 브라우저처럼 도메인 정규화(전각 숫자·마침표 → ASCII)를 먼저 하고 IPv4 를 본다
        val ascii = try {
            IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            // 도메인 규칙에 맞지 않는 호스트다 — 아래에서 경고로 알리고 원문 호스트를 그대로 보인다
            null
        }
        val candidate = ascii ?: rawHost
        if (Ipv4.endsInNumber(candidate)) {
            warnings += UrlWarning.IP_ADDRESS
            val ip = Ipv4.parse(candidate)
            if (ip == null) warnings += UrlWarning.UNREADABLE_HOST
            val shown = ip ?: candidate
            return QrAction.OpenUrl(full, shown, shown, warnings)
        }
        if (ascii == null) {
            warnings += UrlWarning.UNREADABLE_HOST
            return QrAction.OpenUrl(full, rawHost, rawHost, warnings)
        }
        val display = IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED)
        if (ascii.split('.').any { it.startsWith("xn--") }) warnings += UrlWarning.INTERNATIONALIZED
        return QrAction.OpenUrl(full, ascii, display, warnings)
    }

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    // 포트는 ASCII 숫자만이다(WHATWG URL). \d 는 Android 에서 '８'·'٣' 같은 유니코드 숫자까지 맞춘다
    private val PORT_AFTER_COLON = Regex("^[0-9]*(?:[/?#]|$)")

    /** 소문자 스킴. 없으면 null — `example.com:8080/x` 처럼 콜론 뒤가 포트면 스킴이 아니다. */
    internal fun schemeOf(url: String): String? {
        val m = SCHEME.find(url) ?: return null
        val rest = url.substring(m.range.last + 1)
        if (!rest.startsWith("//") && PORT_AFTER_COLON.containsMatchIn(rest)) return null
        return m.groupValues[1].lowercase(Locale.ROOT)
    }
}

/** WHATWG URL 의 IPv4 판정·해석. 브라우저가 숫자 호스트를 어떻게 여는지 그대로 따른다. */
internal object Ipv4 {

    /** 마지막 라벨이 숫자(10진 또는 `0x` 16진)면 브라우저는 IPv4 로 읽는다("ends in a number"). */
    fun endsInNumber(host: String): Boolean {
        val parts = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        val last = parts.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return parseNumber(last) != null
    }

    /** 1~4 부분(각각 10·16·8진)을 해석해 `a.b.c.d` 로. 범위를 넘거나 숫자가 아니면 null. */
    fun parse(host: String): String? {
        val parts = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        if (parts.size > 4 || parts.isEmpty()) return null
        val numbers = parts.map { parseNumber(it) ?: return null }
        if (numbers.dropLast(1).any { it > 255 }) return null
        val last = numbers.last()
        if (last >= 1L shl (8 * (5 - numbers.size))) return null
        var value = last
        numbers.dropLast(1).forEachIndexed { i, n -> value += n shl (8 * (3 - i)) }
        return (3 downTo 0).joinToString(".") { ((value shr (8 * it)) and 0xFF).toString() }
    }

    private fun parseNumber(part: String): Long? {
        if (part.isEmpty()) return null
        val (digits, radix) = when {
            part.startsWith("0x") || part.startsWith("0X") -> part.substring(2) to 16
            part.length > 1 && part.startsWith("0") -> part.substring(1) to 8
            else -> part to 10
        }
        if (digits.isEmpty()) return 0
        if (!digits.all { it.code < 128 && Character.digit(it, radix) >= 0 }) return null
        // Long 을 넘는 긴 숫자도 숫자다 — 범위 밖(IPv4 실패)으로 판정되게 가장 큰 값으로 둔다
        return digits.toLongOrNull(radix) ?: Long.MAX_VALUE
    }
}
