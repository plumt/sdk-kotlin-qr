package net.plumt.qr.scanner.action

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert as I
import android.provider.Settings
import androidx.annotation.RequiresApi
import net.plumt.qr.EventTime
import net.plumt.qr.PhoneType
import net.plumt.qr.QrPayload
import net.plumt.qr.WifiSecurity
import net.plumt.qr.scanner.ScannerException
import java.time.ZoneId
import java.time.ZoneOffset

/** 실행 결과. 실패만 [Failed] 이고, 처리할 앱이 없는 것은 기기 사정이라 [NoHandler] 로 따로 알린다. */
public sealed interface QrActionResult {
    /** 다른 앱(브라우저·연락처·시스템 창 등)을 띄웠다. */
    public data object Started : QrActionResult

    /** 클립보드에 넣었다. */
    public data object Copied : QrActionResult

    /** Wi-Fi 를 바로 붙일 수 없어 설정 화면을 열었다. [passwordCopied] 면 비밀번호를 클립보드에 넣었다(민감 표시). */
    public data class WifiSettingsOpened(val passwordCopied: Boolean) : QrActionResult

    /** 처리할 앱이 기기에 없다. 다른 동작(복사 등)을 권한다. */
    public data object NoHandler : QrActionResult

    /** 그 밖의 이유로 실패했다. 원인은 `error.cause`. */
    public data class Failed(val error: ScannerException) : QrActionResult
}

/**
 * 실행 설정.
 *
 * @property copyWifiPasswordOnFallback Wi-Fi 를 설정 화면으로 보낼 때 비밀번호를 클립보드에 넣는다(사용자가 붙여 넣게). 민감 표시를 붙인다
 * @property clipLabel 클립보드 항목의 이름(사용자에게 보일 수 있다)
 * @property zone 시간대 없는 일정 시각을 해석할 시간대
 */
public data class QrActionOptions(
    val copyWifiPasswordOnFallback: Boolean = true,
    val clipLabel: String = "QR",
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * [QrAction] 을 실행한다. 스캐너는 이것을 스스로 부르지 않는다 — 앱이 사용자 확인 뒤 부른다.
 * 앱이 직접 처리하고 싶은 동작은 이 인터페이스를 구현하거나 [default] 를 감싸 바꾼다.
 */
public interface QrActionExecutor {

    /** 메인 스레드에서 부른다. 던지지 않는다 — 실패는 [QrActionResult.Failed]. */
    public fun execute(context: Context, action: QrAction): QrActionResult

    public companion object {
        /** 기본 구현. 시스템 Intent 만 쓰고 권한을 요구하지 않는다. 앱 링크는 열지 않는다. */
        @JvmStatic
        @JvmOverloads
        public fun default(options: QrActionOptions = QrActionOptions()): QrActionExecutor = DefaultQrActionExecutor(options)

        /**
         * 기본 구현 + [appLinks] 가 허용한 앱 링크도 연다. 앱 링크는 `ACTION_VIEW` + `CATEGORY_BROWSABLE` 이고, `intent:` 는
         * [AppLinkPolicy] 의 설명대로 다듬어 연다. 허용하지 않은 스킴과 다듬을 수 없는 `intent:` 는 [QrActionResult.Failed] 다.
         * 실행 전 확인은 앱이 받는다([QrActions.requiresConfirmation]).
         */
        @JvmStatic
        public fun default(options: QrActionOptions, appLinks: AppLinkPolicy): QrActionExecutor = DefaultQrActionExecutor(options, appLinks)
    }
}

internal class DefaultQrActionExecutor(
    private val options: QrActionOptions,
    private val appLinks: AppLinkPolicy = AppLinkPolicy.NONE,
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
) : QrActionExecutor {

    override fun execute(context: Context, action: QrAction): QrActionResult = try {
        when (action) {
            is QrAction.CopyText -> copy(context, action.text, action.sensitive)
            is QrAction.CopyWifiPassword -> copy(context, action.wifi.password, sensitive = true)
            is QrAction.CopyOtpSecret -> copy(context, action.otp.secret, sensitive = true)
            is QrAction.ConnectWifi -> connectWifi(context, action.wifi)
            is QrAction.OpenUrl -> openUrl(context, action)
            else -> start(context, QrIntents.build(action, options.zone))
        }
    } catch (_: ActivityNotFoundException) {
        // 처리할 앱이 없는 기기다 — 실패가 아니라 값으로 알린다
        QrActionResult.NoHandler
    } catch (e: RuntimeException) {
        QrActionResult.Failed(ScannerException("동작을 실행하지 못했다: ${action::class.simpleName}", e))
    }

    private fun start(context: Context, intent: Intent): QrActionResult {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return QrActionResult.Started
    }

    /**
     * http·https 와 [appLinks] 가 허용한 앱 링크만 연다. 그 밖(`intent:`·`javascript:` 등)은 브라우저가 다른 앱을 실행할 수 있어 거절한다
     * (경고 표지와 무관하게 주소를 다시 본다).
     */
    private fun openUrl(context: Context, action: QrAction.OpenUrl): QrActionResult {
        val scheme = action.url.toUri().scheme?.lowercase(java.util.Locale.ROOT)
        if (scheme == "http" || scheme == "https") return start(context, QrIntents.build(action, options.zone))
        if (!appLinks.allows(action.url)) {
            return QrActionResult.Failed(
                ScannerException(
                    "http·https 가 아닌 주소는 열지 않는다(스킴: ${scheme ?: "없음"}) — 앱 링크는 AppLinkPolicy 로 고른 스킴만 연다",
                    IllegalArgumentException("scheme=$scheme"),
                ),
            )
        }
        val intent = try {
            AppLinkIntents.build(action.url, appLinks)
        } catch (e: IllegalArgumentException) {
            return QrActionResult.Failed(ScannerException("앱 링크를 열 수 없다(스킴: $scheme)", e))
        }
        return start(context, intent)
    }

    private fun connectWifi(context: Context, wifi: QrPayload.Wifi): QrActionResult {
        // sdkInt 는 테스트가 낮은 버전 경로를 고르려고 주입한다. 실제 기기 버전도 함께 본다
        if (sdkInt() >= Build.VERSION_CODES.R && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = QrIntents.addWifiNetwork(wifi)
            if (intent != null) return start(context, intent)
        }
        val copied = options.copyWifiPasswordOnFallback && wifi.password.isNotEmpty()
        if (copied) copy(context, wifi.password, sensitive = true)
        start(context, Intent(Settings.ACTION_WIFI_SETTINGS))
        return QrActionResult.WifiSettingsOpened(passwordCopied = copied)
    }

    private fun copy(context: Context, text: String, sensitive: Boolean): QrActionResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(options.clipLabel, text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        return QrActionResult.Copied
    }

    internal companion object {
        // ClipDescription.EXTRA_IS_SENSITIVE(API 33)의 값. 키 문자열은 그 아래 버전에서도 무해하게 실린다
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

/** 동작별 Intent. 순수 조립이라 실행하지 않는다. */
internal object QrIntents {

    const val SMS_BODY = "sms_body"

    fun build(action: QrAction, zone: ZoneId): Intent = when (action) {
        is QrAction.OpenUrl -> Intent(Intent.ACTION_VIEW, action.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        is QrAction.AddContact -> contact(action.contact)
        is QrAction.AddEvent -> event(action.event, zone)
        is QrAction.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.number, null))
        is QrAction.SendSms -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", action.number, null)).apply {
            if (action.message.isNotEmpty()) putExtra(SMS_BODY, action.message)
        }
        is QrAction.SendEmail -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", action.address, null)).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(action.address))
            if (action.subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, action.subject)
            if (action.body.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, action.body)
        }
        is QrAction.ShowLocation -> Intent(Intent.ACTION_VIEW, geoUri(action.geo))
        is QrAction.AddOtp -> Intent(Intent.ACTION_VIEW, action.otp.encode().toUri())
        is QrAction.SearchProduct -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, action.code)
        is QrAction.ConnectWifi, is QrAction.CopyWifiPassword, is QrAction.CopyOtpSecret, is QrAction.CopyText ->
            throw IllegalArgumentException("Intent 로 실행하지 않는 동작이다: ${action::class.simpleName}")
    }

    /**
     * `geo:위도,경도?q=위도,경도(라벨)`. 좌표는 지수 표기 없이(`1.0E-5` 가 아니라 `0.00001`), 라벨은 괄호까지 퍼센트 인코딩해
     * 라벨 안의 `(`·`)` 가 라벨 경계를 깨지 않게 한다.
     */
    fun geoUri(geo: QrPayload.Geo): Uri {
        val point = "${plain(geo.latitude)},${plain(geo.longitude)}"
        val label = geo.query.trim()
        // Uri.encode 는 ( ) 를 그대로 둔다 — 라벨 안의 괄호는 직접 인코딩한다
        val encodedLabel = Uri.encode(label).replace("(", "%28").replace(")", "%29")
        val q = Uri.encode(point, ",") + if (label.isEmpty()) "" else "($encodedLabel)"
        return "geo:$point?q=$q".toUri()
    }

    private fun plain(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /**
     * 연락처 삽입 화면. 전화·이메일은 표준 칸이 셋씩이라 넷째부터는 `DATA` 행으로 넣는다. 웹 주소도 `DATA` 행이다.
     */
    fun contact(c: QrPayload.Contact): Intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        if (c.name.isNotBlank()) putExtra(I.NAME, c.name)
        if (c.organization.isNotBlank()) putExtra(I.COMPANY, c.organization)
        if (c.title.isNotBlank()) putExtra(I.JOB_TITLE, c.title)
        if (c.address.isNotBlank()) putExtra(I.POSTAL, c.address)
        if (c.note.isNotBlank()) putExtra(I.NOTES, c.note)
        val phoneKeys = listOf(I.PHONE to I.PHONE_TYPE, I.SECONDARY_PHONE to I.SECONDARY_PHONE_TYPE, I.TERTIARY_PHONE to I.TERTIARY_PHONE_TYPE)
        c.phones.take(3).forEachIndexed { i, p ->
            putExtra(phoneKeys[i].first, p.number)
            putExtra(phoneKeys[i].second, phoneType(p.type))
        }
        val emailKeys = listOf(I.EMAIL, I.SECONDARY_EMAIL, I.TERTIARY_EMAIL)
        c.emails.take(3).forEachIndexed { i, e -> putExtra(emailKeys[i], e) }
        val rows = ArrayList<ContentValues>()
        c.phones.drop(3).forEach { p ->
            rows += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, p.number)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, phoneType(p.type))
            }
        }
        c.emails.drop(3).forEach { e ->
            rows += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Email.ADDRESS, e)
            }
        }
        if (c.url.isNotBlank()) {
            rows += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Website.URL, c.url)
            }
        }
        if (rows.isNotEmpty()) putParcelableArrayListExtra(I.DATA, rows)
    }

    fun phoneType(type: PhoneType): Int = when (type) {
        PhoneType.MOBILE -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        PhoneType.HOME -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
        PhoneType.WORK -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
        PhoneType.FAX -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
        PhoneType.OTHER -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
    }

    /**
     * 일정 추가 화면. 종일 일정은 캘린더 규약대로 UTC 자정을 시작으로, 끝(iCalendar 의 DTEND, 그날 미포함)이 없으면 다음 날 자정을 끝으로 준다.
     * 시간대 없는 현지 시각은 [zone] 으로 해석한다.
     */
    fun event(e: QrPayload.Event, zone: ZoneId): Intent =
        Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, e.title)
            if (e.location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
            if (e.description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, e.description)
            val start = e.start
            if (start is EventTime.AllDay) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                val begin = start.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val endDate = (e.end as? EventTime.AllDay)?.date?.takeIf { it.isAfter(start.date) } ?: start.date.plusDays(1)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            } else {
                start?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.toInstant(zone).toEpochMilli()) }
                e.end?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.toInstant(zone).toEpochMilli()) }
            }
        }

    /**
     * 시스템 「네트워크 추가」 창(API 30+). 그 창이 받지 않는 경우 null — WEP·WPA2-EAP(서버 인증서가 필요하다),
     * 빈·32바이트(UTF-8) 넘는 SSID, WPA 규격 밖 비밀번호(8..63자 출력 가능 ASCII 또는 64자리 16진수가 아님),
     * 그 밖에 제안 API 가 거절한 값.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun addWifiNetwork(wifi: QrPayload.Wifi): Intent? {
        if (!isValidSsid(wifi.ssid)) return null
        if ((wifi.security == WifiSecurity.WPA || wifi.security == WifiSecurity.WPA3) && !isValidPassphrase(wifi.password)) return null
        val builder = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).setIsHiddenSsid(wifi.hidden)
        val suggestion = try {
            when (wifi.security) {
                WifiSecurity.NONE -> builder
                WifiSecurity.WPA -> builder.setWpa2Passphrase(wifi.password)
                WifiSecurity.WPA3 -> builder.setWpa3Passphrase(wifi.password)
                WifiSecurity.WEP, WifiSecurity.WPA2_EAP -> return null
            }.build()
        } catch (_: IllegalArgumentException) {
            // 제안 API 가 받지 않는 값이다 — 설정 화면으로 보낸다
            return null
        } catch (_: IllegalStateException) {
            // 조합이 맞지 않는 설정이다 — 설정 화면으로 보낸다
            return null
        }
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
    }

    fun isValidSsid(ssid: String): Boolean = ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= MAX_SSID_BYTES

    /** IEEE 802.11i: 8..63자 출력 가능 ASCII 암호문, 또는 64자리 16진수 PSK. */
    fun isValidPassphrase(p: String): Boolean =
        (p.length in 8..63 && p.all { it in ' '..'~' }) || (p.length == 64 && p.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })

    private const val MAX_SSID_BYTES = 32
}
