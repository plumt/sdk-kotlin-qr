package net.plumt.qr.scanner

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.action.QrAction
import net.plumt.qr.scanner.action.UrlWarning
import java.time.ZoneId

/** 기본 박스·칩·문구에 쓰는 값. 직접 만든 UI 에서도 같은 모양·문구를 쓰고 싶을 때 부른다. */
public object ScannerDefaults {

    /** 기본 박스 선 두께(고정·보통). */
    public val BoxStrokeWidth: Dp = 3.dp

    /** 기본 흐린 박스 선 두께. */
    public val DimmedBoxStrokeWidth: Dp = 2.dp

    /** 기본 박스 모서리 반지름. */
    public val BoxCornerRadius: Dp = 8.dp

    /** 기본 흐린 박스 색 — 반투명 흰색. */
    public val DimmedBoxColor: Color = Color.White.copy(alpha = 0.5f)

    /**
     * [QrCodeBox] 모양. 바꾸지 않은 값은 기본 — 보통·고정 색은 테마의 `primary`, 흐린 색은 [DimmedBoxColor].
     * 박스를 숨기려면 [CodeScanner] 에 `box = null` 을 넘긴다.
     *
     * @throws IllegalArgumentException 두께·반지름이 음수이거나 유한하지 않을 때
     */
    @Composable
    public fun boxStyle(
        color: Color = MaterialTheme.colorScheme.primary,
        lockedColor: Color = MaterialTheme.colorScheme.primary,
        dimmedColor: Color = DimmedBoxColor,
        strokeWidth: Dp = BoxStrokeWidth,
        lockedStrokeWidth: Dp = BoxStrokeWidth,
        dimmedStrokeWidth: Dp = DimmedBoxStrokeWidth,
        cornerRadius: Dp = BoxCornerRadius,
    ): CodeBoxStyle = CodeBoxStyle(color, lockedColor, dimmedColor, strokeWidth, lockedStrokeWidth, dimmedStrokeWidth, cornerRadius)

    /** 칩에 보일 요약의 최대 글자 수(유니코드 코드 포인트). 넘으면 잘라 `…` 를 붙인다. */
    public const val SUMMARY_MAX_LENGTH: Int = 40

    /** 내용 종류 이름(「링크」·「Wi-Fi」) 문자열 리소스. */
    @StringRes
    @JvmStatic
    public fun typeLabelRes(payload: QrPayload): Int = when (payload) {
        is QrPayload.Url -> R.string.plumt_qr_type_url
        is QrPayload.Text -> R.string.plumt_qr_type_text
        is QrPayload.Wifi -> R.string.plumt_qr_type_wifi
        is QrPayload.Contact -> R.string.plumt_qr_type_contact
        is QrPayload.Email -> R.string.plumt_qr_type_email
        is QrPayload.Phone -> R.string.plumt_qr_type_phone
        is QrPayload.Sms -> R.string.plumt_qr_type_sms
        is QrPayload.Event -> R.string.plumt_qr_type_event
        is QrPayload.Geo -> R.string.plumt_qr_type_geo
        is QrPayload.Otp -> R.string.plumt_qr_type_otp
        is QrPayload.Product -> R.string.plumt_qr_type_product
    }

    /** 동작 이름(「링크 열기」) 문자열 리소스. */
    @StringRes
    @JvmStatic
    public fun actionLabelRes(action: QrAction): Int = when (action) {
        is QrAction.OpenUrl -> R.string.plumt_qr_action_open_url
        is QrAction.ConnectWifi -> R.string.plumt_qr_action_connect_wifi
        is QrAction.CopyWifiPassword -> R.string.plumt_qr_action_copy_wifi_password
        is QrAction.AddContact -> R.string.plumt_qr_action_add_contact
        is QrAction.AddEvent -> R.string.plumt_qr_action_add_event
        is QrAction.Dial -> R.string.plumt_qr_action_dial
        is QrAction.SendSms -> R.string.plumt_qr_action_send_sms
        is QrAction.SendEmail -> R.string.plumt_qr_action_send_email
        is QrAction.ShowLocation -> R.string.plumt_qr_action_show_location
        is QrAction.AddOtp -> R.string.plumt_qr_action_add_otp
        is QrAction.CopyOtpSecret -> R.string.plumt_qr_action_copy_otp_secret
        is QrAction.SearchProduct -> R.string.plumt_qr_action_search_product
        is QrAction.CopyText -> R.string.plumt_qr_action_copy_text
    }

    /** 링크 경고 문구 리소스. */
    @StringRes
    @JvmStatic
    public fun warningRes(warning: UrlWarning): Int = when (warning) {
        UrlWarning.NOT_HTTPS -> R.string.plumt_qr_warning_not_https
        UrlWarning.USER_INFO -> R.string.plumt_qr_warning_user_info
        UrlWarning.INTERNATIONALIZED -> R.string.plumt_qr_warning_internationalized
        UrlWarning.IP_ADDRESS -> R.string.plumt_qr_warning_ip_address
        UrlWarning.UNREADABLE_HOST -> R.string.plumt_qr_warning_unreadable_host
        UrlWarning.NON_WEB_SCHEME -> R.string.plumt_qr_warning_non_web_scheme
    }

    /**
     * 확인창 본문에 보일, 동작이 실제로 다룰 값(번호·받는 사람·네트워크 이름 등). 사용자가 무엇을 승인하는지 알게 한다.
     * 비밀(Wi-Fi 비밀번호·OTP 비밀·민감 표시한 글)은 넣지 않는다 — 대신 네트워크 이름·계정을 보인다. 링크는 호스트를 따로 보이므로
     * 주소(사용자 정보는 가림)다. 보일 것이 없으면 빈 문자열.
     */
    @JvmStatic
    @JvmOverloads
    public fun actionDetail(action: QrAction, zone: ZoneId = ZoneId.systemDefault()): String = when (action) {
        is QrAction.OpenUrl -> QrPayload.Url(action.url).summary(zone)
        is QrAction.ConnectWifi -> action.wifi.ssid
        is QrAction.CopyWifiPassword -> action.wifi.ssid
        is QrAction.AddContact -> action.contact.summary(zone)
        is QrAction.AddEvent -> action.event.summary(zone)
        is QrAction.Dial -> action.number
        is QrAction.SendSms -> lines(action.number, action.message)
        is QrAction.SendEmail -> lines(action.address, action.subject, action.body)
        is QrAction.ShowLocation -> action.geo.summary(zone)
        is QrAction.AddOtp -> action.otp.summary(zone)
        is QrAction.CopyOtpSecret -> action.otp.summary(zone)
        is QrAction.SearchProduct -> action.code
        is QrAction.CopyText -> if (action.sensitive) "" else action.text
    }.trim().let(::clip)

    private fun lines(vararg parts: String) = parts.map(String::trim).filter(String::isNotEmpty).joinToString("\n")

    /** 확인창 본문 상한(코드 포인트). 긴 본문이 창을 덮지 않게 자른다. */
    private const val DETAIL_MAX_LENGTH = 200

    private fun clip(s: String): String {
        if (s.codePointCount(0, s.length) <= DETAIL_MAX_LENGTH) return s
        return s.substring(0, s.offsetByCodePoints(0, DETAIL_MAX_LENGTH - 1)).trimEnd() + ELLIPSIS
    }

    /**
     * 칩에 보일 한 줄 요약. `qr` 의 요약이라 Wi-Fi 비밀번호·OTP 비밀이 들지 않는다. 줄바꿈은 공백으로 바꾸고 [maxLength] 에서 자른다.
     *
     * @throws IllegalArgumentException [maxLength] 가 1 미만일 때
     */
    @JvmStatic
    @JvmOverloads
    public fun summary(result: ScanResult, zone: ZoneId = ZoneId.systemDefault(), maxLength: Int = SUMMARY_MAX_LENGTH): String {
        require(maxLength >= 1) { "maxLength($maxLength) 는 1 이상이어야 한다" }
        val raw = result.payload.summary(zone).ifBlank { QrPayload.summarize(result.text, zone) }
        val oneLine = raw.replace(WHITESPACE, " ").trim()
        val count = oneLine.codePointCount(0, oneLine.length)
        if (count <= maxLength) return oneLine
        val end = oneLine.offsetByCodePoints(0, maxLength - 1)
        return oneLine.substring(0, end).trimEnd() + ELLIPSIS
    }

    // \s 를 쓰지 않는다 — Android 는 유니코드 공백(U+3000·NBSP)까지 맞춰 JVM 과 결과가 달라진다.
    // 한 줄로 만드는 것이 목적이라 ASCII 공백·제어 공백과 유니코드 줄 구분자(U+0085·U+2028·U+2029)만 접고, 내용인 전각·고정 공백은 둔다
    private val WHITESPACE = Regex("[ \\t\\n\\u000B\\f\\r\\u0085\\u2028\\u2029]+")
    private const val ELLIPSIS = "…"
}

/**
 * 칩 자리 계산. 박스 아래 [gap] 에 가운데 맞춰 두고, 아래가 모자라면 박스 위로, 그래도 넘치면 뷰 안으로 민다.
 * 칩이 뷰보다 크면 왼쪽·위에 붙인다.
 */
internal object ChipPlacement {
    fun place(
        bounds: CodeBounds,
        chipWidth: Float,
        chipHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        gap: Float,
    ): Pair<Float, Float> {
        val x = (bounds.centerX - chipWidth / 2f).coerceIn(0f, maxOf(0f, viewWidth - chipWidth))
        val below = bounds.bottom + gap
        val above = bounds.top - gap - chipHeight
        val y = when {
            below + chipHeight <= viewHeight -> below
            above >= 0f -> above
            else -> (viewHeight - chipHeight).coerceAtLeast(0f)
        }
        return x to y
    }
}
