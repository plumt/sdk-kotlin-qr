package net.plumt.qr.scanner

import androidx.annotation.StringRes
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.action.QrAction
import net.plumt.qr.scanner.action.UrlWarning
import java.time.ZoneId

/** 기본 칩·문구에 쓰는 값. 직접 만든 UI 에서도 같은 문구를 쓰고 싶을 때 부른다. */
public object ScannerDefaults {

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

    private val WHITESPACE = Regex("\\s+")
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
