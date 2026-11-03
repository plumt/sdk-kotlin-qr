package net.plumt.qr.scanner.action

import android.app.Activity
import android.app.Application
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import net.plumt.qr.ContactPhone
import net.plumt.qr.EventTime
import net.plumt.qr.PhoneType
import net.plumt.qr.QrPayload
import net.plumt.qr.WifiSecurity
import net.plumt.qr.scanner.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 30, 34, 35])
class QrActionExecutorTest {

    private lateinit var app: Application
    private val executor = QrActionExecutor.default(QrActionOptions(zone = SEOUL))

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun started(): Intent = shadowOf(app).nextStartedActivity ?: throw AssertionError("시작된 Activity 가 없다")

    private fun clip() = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!

    private fun assertSensitive() {
        assertTrue(clip().description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test
    fun openUrl() {
        assertEquals(QrActionResult.Started, executor.execute(app, QrActions.openUrl("https://example.com/a?b=1")))
        val i = started()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("https://example.com/a?b=1", i.dataString)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue("Activity 가 아닌 Context 는 새 작업으로 연다", i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun fromActivity_noNewTaskFlag() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertEquals(QrActionResult.Started, executor.execute(activity, QrAction.Dial("010")))
        val i = shadowOf(activity).nextStartedActivity
        assertEquals(0, i.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun dial_sms_email() {
        executor.execute(app, QrAction.Dial("+82 10-1234-5678"))
        started().let {
            assertEquals(Intent.ACTION_DIAL, it.action)
            assertEquals("tel", it.data!!.scheme)
            assertEquals("+82 10-1234-5678", it.data!!.schemeSpecificPart)
        }
        executor.execute(app, QrAction.SendSms("010#1", "안녕 & bye"))
        started().let {
            assertEquals(Intent.ACTION_SENDTO, it.action)
            assertEquals("smsto", it.data!!.scheme)
            assertEquals("010#1", it.data!!.schemeSpecificPart)
            assertEquals("안녕 & bye", it.getStringExtra("sms_body"))
        }
        executor.execute(app, QrAction.SendSms("010", ""))
        assertFalse(started().hasExtra("sms_body"))
        executor.execute(app, QrAction.SendEmail("a+b@example.com", "제목", "본문"))
        started().let {
            assertEquals(Intent.ACTION_SENDTO, it.action)
            assertEquals("mailto", it.data!!.scheme)
            assertEquals("a+b@example.com", it.data!!.schemeSpecificPart)
            assertArrayEquals(arrayOf("a+b@example.com"), it.getStringArrayExtra(Intent.EXTRA_EMAIL))
            assertEquals("제목", it.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals("본문", it.getStringExtra(Intent.EXTRA_TEXT))
        }
        executor.execute(app, QrAction.SendEmail("a@example.com", "", ""))
        started().let {
            assertFalse(it.hasExtra(Intent.EXTRA_SUBJECT))
            assertFalse(it.hasExtra(Intent.EXTRA_TEXT))
        }
    }

    @Test
    fun geo() {
        executor.execute(app, QrAction.ShowLocation(QrPayload.Geo(37.5665, 126.978, query = "서울 시청")))
        val i = started()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("geo", i.data!!.scheme)
        assertTrue(i.dataString!!.startsWith("geo:37.5665,126.978?q="))
        assertEquals("37.5665,126.978(서울 시청)", android.net.Uri.decode(i.dataString!!.substringAfter("?q=")))
        assertFalse("검색어의 공백·한글은 인코딩된다", i.dataString!!.contains(" "))
        executor.execute(app, QrAction.ShowLocation(QrPayload.Geo(-33.0, 151.0)))
        assertEquals("geo:-33.0,151.0?q=-33.0,151.0", started().dataString)
    }

    @Test
    fun otp_handsUriToAuthenticator() {
        val otp = QrPayload.Otp("me@example.com", "JBSWY3DPEHPK3PXP", "Ex")
        executor.execute(app, QrAction.AddOtp(otp))
        val i = started()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(otp.encode(), i.dataString)
        assertEquals("otpauth", i.data!!.scheme)
    }

    @Test
    fun otp_invalidPayload_failsWithCause() {
        val bad = QrPayload.Otp("", "not base32!")
        val r = executor.execute(app, QrAction.AddOtp(bad))
        assertTrue(r is QrActionResult.Failed)
        val e = (r as QrActionResult.Failed).error
        assertTrue(e.cause is IllegalArgumentException)
        assertTrue(e.message!!.contains("AddOtp"))
        assertFalse("메시지에 비밀을 넣지 않는다", e.message!!.contains("base32!"))
    }

    @Test
    fun product_webSearch() {
        executor.execute(app, QrAction.SearchProduct("4006381333931"))
        val i = started()
        assertEquals(Intent.ACTION_WEB_SEARCH, i.action)
        assertEquals("4006381333931", i.getStringExtra(SearchManager.QUERY))
    }

    @Test
    fun contact_allFields() {
        val c = QrPayload.Contact(
            name = "김철수", organization = "회사", title = "팀장",
            phones = listOf(
                ContactPhone("010-1", PhoneType.MOBILE), ContactPhone("02-2", PhoneType.WORK), ContactPhone("031-3", PhoneType.HOME),
                ContactPhone("04-4", PhoneType.FAX), ContactPhone("05-5", PhoneType.OTHER),
            ),
            emails = listOf("a@example.com", "b@example.com", "c@example.com", "d@example.com"),
            address = "서울", url = "https://x.example", note = "메모",
        )
        executor.execute(app, QrAction.AddContact(c))
        val i = started()
        assertEquals(Insert.ACTION, i.action)
        assertEquals(ContactsContract.RawContacts.CONTENT_TYPE, i.type)
        assertEquals("김철수", i.getStringExtra(Insert.NAME))
        assertEquals("회사", i.getStringExtra(Insert.COMPANY))
        assertEquals("팀장", i.getStringExtra(Insert.JOB_TITLE))
        assertEquals("서울", i.getStringExtra(Insert.POSTAL))
        assertEquals("메모", i.getStringExtra(Insert.NOTES))
        assertEquals("010-1", i.getStringExtra(Insert.PHONE))
        assertEquals(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, i.getIntExtra(Insert.PHONE_TYPE, -1))
        assertEquals("02-2", i.getStringExtra(Insert.SECONDARY_PHONE))
        assertEquals(ContactsContract.CommonDataKinds.Phone.TYPE_WORK, i.getIntExtra(Insert.SECONDARY_PHONE_TYPE, -1))
        assertEquals("031-3", i.getStringExtra(Insert.TERTIARY_PHONE))
        assertEquals(ContactsContract.CommonDataKinds.Phone.TYPE_HOME, i.getIntExtra(Insert.TERTIARY_PHONE_TYPE, -1))
        assertEquals("a@example.com", i.getStringExtra(Insert.EMAIL))
        assertEquals("b@example.com", i.getStringExtra(Insert.SECONDARY_EMAIL))
        assertEquals("c@example.com", i.getStringExtra(Insert.TERTIARY_EMAIL))
        @Suppress("DEPRECATION")
        val rows = i.getParcelableArrayListExtra<ContentValues>(Insert.DATA)!!
        assertEquals(4, rows.size)
        assertEquals("04-4", rows[0].getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER))
        assertEquals(ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK, rows[0].getAsInteger(ContactsContract.CommonDataKinds.Phone.TYPE))
        assertEquals(ContactsContract.CommonDataKinds.Phone.TYPE_OTHER, rows[1].getAsInteger(ContactsContract.CommonDataKinds.Phone.TYPE))
        assertEquals("d@example.com", rows[2].getAsString(ContactsContract.CommonDataKinds.Email.ADDRESS))
        assertEquals(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, rows[3].getAsString(ContactsContract.Data.MIMETYPE))
        assertEquals("https://x.example", rows[3].getAsString(ContactsContract.CommonDataKinds.Website.URL))
    }

    @Test
    fun contact_minimal_hasNoEmptyExtras() {
        executor.execute(app, QrAction.AddContact(QrPayload.Contact(phones = listOf(ContactPhone("010")))))
        val i = started()
        assertFalse(i.hasExtra(Insert.NAME))
        assertFalse(i.hasExtra(Insert.COMPANY))
        assertFalse(i.hasExtra(Insert.EMAIL))
        assertFalse(i.hasExtra(Insert.DATA))
        assertEquals("010", i.getStringExtra(Insert.PHONE))
    }

    @Test
    fun event_timed_withZoneOrUtc() {
        val e = QrPayload.Event(
            title = "회의",
            start = EventTime.Local(LocalDateTime.of(2026, 3, 1, 9, 30)),
            end = EventTime.Utc(Instant.parse("2026-03-01T01:30:00Z")),
            location = "본사", description = "설명",
        )
        executor.execute(app, QrAction.AddEvent(e))
        val i = started()
        assertEquals(Intent.ACTION_INSERT, i.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, i.data)
        assertEquals("회의", i.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("본사", i.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals("설명", i.getStringExtra(CalendarContract.Events.DESCRIPTION))
        // 서울 09:30 = UTC 00:30
        assertEquals(Instant.parse("2026-03-01T00:30:00Z").toEpochMilli(), i.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals(Instant.parse("2026-03-01T01:30:00Z").toEpochMilli(), i.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        assertFalse(i.hasExtra(CalendarContract.EXTRA_EVENT_ALL_DAY))
    }

    @Test
    fun event_explicitZoneWins() {
        val e = QrPayload.Event("t", start = EventTime.Local(LocalDateTime.of(2026, 3, 1, 9, 0), ZoneId.of("UTC")))
        executor.execute(app, QrAction.AddEvent(e))
        val i = started()
        assertEquals(Instant.parse("2026-03-01T09:00:00Z").toEpochMilli(), i.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertFalse(i.hasExtra(CalendarContract.EXTRA_EVENT_END_TIME))
        assertFalse(i.hasExtra(CalendarContract.Events.EVENT_LOCATION))
        assertFalse(i.hasExtra(CalendarContract.Events.DESCRIPTION))
    }

    @Test
    fun event_allDay_usesUtcMidnight() {
        executor.execute(app, QrAction.AddEvent(QrPayload.Event("휴가", start = EventTime.AllDay(LocalDate.of(2026, 5, 5)))))
        started().let {
            assertTrue(it.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
            assertEquals(Instant.parse("2026-05-05T00:00:00Z").toEpochMilli(), it.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
            assertEquals(Instant.parse("2026-05-06T00:00:00Z").toEpochMilli(), it.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        }
        val multi = QrPayload.Event("여행", EventTime.AllDay(LocalDate.of(2026, 5, 5)), EventTime.AllDay(LocalDate.of(2026, 5, 8)))
        executor.execute(app, QrAction.AddEvent(multi))
        assertEquals(Instant.parse("2026-05-08T00:00:00Z").toEpochMilli(), started().getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        // 끝이 시작보다 앞서거나 같으면 하루짜리로
        val same = QrPayload.Event("x", EventTime.AllDay(LocalDate.of(2026, 5, 5)), EventTime.AllDay(LocalDate.of(2026, 5, 5)))
        executor.execute(app, QrAction.AddEvent(same))
        assertEquals(Instant.parse("2026-05-06T00:00:00Z").toEpochMilli(), started().getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
    }

    @Test
    fun event_noTimes() {
        executor.execute(app, QrAction.AddEvent(QrPayload.Event("x")))
        val i = started()
        assertFalse(i.hasExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME))
        assertFalse(i.hasExtra(CalendarContract.EXTRA_EVENT_END_TIME))
    }

    @Test
    fun copyText_plainAndSensitive() {
        assertEquals(QrActionResult.Copied, executor.execute(app, QrAction.CopyText("hello")))
        assertEquals("hello", clip().getItemAt(0).text.toString())
        assertEquals("QR", clip().description.label.toString())
        assertNull(clip().description.extras)
        executor.execute(app, QrAction.CopyText("pw", sensitive = true))
        assertEquals("pw", clip().getItemAt(0).text.toString())
        assertSensitive()
    }

    @Test
    fun copySecrets_areMarkedSensitive() {
        executor.execute(app, QrAction.CopyWifiPassword(QrPayload.Wifi("Cafe", "pw12345678")))
        assertEquals("pw12345678", clip().getItemAt(0).text.toString())
        assertSensitive()
        executor.execute(app, QrAction.CopyOtpSecret(QrPayload.Otp("me", "JBSWY3DPEHPK3PXP")))
        assertEquals("JBSWY3DPEHPK3PXP", clip().getItemAt(0).text.toString())
        assertSensitive()
    }

    @Test
    fun customClipLabel() {
        QrActionExecutor.default(QrActionOptions(clipLabel = "스캔")).execute(app, QrAction.CopyText("a"))
        assertEquals("스캔", clip().description.label.toString())
    }

    @Test
    fun noHandler_isNotAFailure() {
        shadowOf(app).checkActivities(true)
        assertEquals(QrActionResult.NoHandler, executor.execute(app, QrAction.SearchProduct("1")))
        assertEquals(QrActionResult.NoHandler, executor.execute(app, QrActions.openUrl("https://example.com")))
    }

    @Test
    fun otherFailure_isFailedWithCause() {
        val boom = SecurityException("거부")
        val ctx = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent?) = throw boom
        }
        val r = executor.execute(ctx, QrAction.Dial("1")) as QrActionResult.Failed
        assertSame(boom, r.error.cause)
    }

    // ---- Wi-Fi ----

    private fun wifiAddExpected() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @Test
    fun wifi_wpa_addNetworksSheetOnR_elseSettings() {
        val wifi = QrPayload.Wifi("Cafe", "pw12345678", WifiSecurity.WPA, hidden = true)
        val r = executor.execute(app, QrAction.ConnectWifi(wifi))
        val i = started()
        if (wifiAddExpected()) {
            assertEquals(QrActionResult.Started, r)
            assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, i.action)
            @Suppress("DEPRECATION")
            val list = i.getParcelableArrayListExtra<WifiNetworkSuggestion>(Settings.EXTRA_WIFI_NETWORK_LIST)!!
            assertEquals(1, list.size)
            assertEquals("Cafe", list[0].ssid)
        } else {
            assertEquals(QrActionResult.WifiSettingsOpened(passwordCopied = true), r)
            assertEquals(Settings.ACTION_WIFI_SETTINGS, i.action)
            assertEquals("pw12345678", clip().getItemAt(0).text.toString())
            assertSensitive()
        }
    }

    @Test
    fun wifi_openAndWpa3() {
        for (sec in listOf(WifiSecurity.NONE, WifiSecurity.WPA3)) {
            val pw = if (sec == WifiSecurity.NONE) "" else "pw12345678"
            val r = executor.execute(app, QrAction.ConnectWifi(QrPayload.Wifi("Net", pw, sec)))
            val i = started()
            if (wifiAddExpected()) {
                assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, i.action)
                assertEquals(QrActionResult.Started, r)
            } else {
                assertEquals(Settings.ACTION_WIFI_SETTINGS, i.action)
                assertEquals(QrActionResult.WifiSettingsOpened(passwordCopied = pw.isNotEmpty()), r)
            }
        }
    }

    @Test
    fun wifi_unsupportedByAddSheet_fallsBackToSettings() {
        val cases = listOf(
            QrPayload.Wifi("Old", "abcde", WifiSecurity.WEP),
            QrPayload.Wifi("Corp", "pw", WifiSecurity.WPA2_EAP, eapMethod = "PEAP", identity = "me"),
            QrPayload.Wifi("Short", "short", WifiSecurity.WPA), // WPA2 비밀번호는 8자 이상
            QrPayload.Wifi("Uni", "비밀번호비밀번호", WifiSecurity.WPA), // ASCII 아님
            QrPayload.Wifi("x".repeat(40), "pw12345678", WifiSecurity.WPA), // SSID 32바이트 초과
            QrPayload.Wifi("", "pw12345678", WifiSecurity.WPA),
            QrPayload.Wifi("Long", "x".repeat(64), WifiSecurity.WPA), // 64자는 16진수여야 한다
            QrPayload.Wifi("Ctl", "pw\u0001pw12345", WifiSecurity.WPA3),
        )
        for (w in cases) {
            val r = executor.execute(app, QrAction.ConnectWifi(w))
            val i = started()
            assertEquals(w.ssid, Settings.ACTION_WIFI_SETTINGS, i.action)
            assertEquals(QrActionResult.WifiSettingsOpened(passwordCopied = true), r)
        }
    }

    @Test
    fun passphraseAndSsidRules() {
        assertTrue(QrIntents.isValidPassphrase("12345678"))
        assertTrue(QrIntents.isValidPassphrase("~".repeat(63)))
        assertTrue(QrIntents.isValidPassphrase("0123456789abcdefABCDEF0123456789abcdef0123456789abcdef0123456789".take(64)))
        assertFalse(QrIntents.isValidPassphrase("1234567"))
        assertFalse(QrIntents.isValidPassphrase("g".repeat(64)))
        assertTrue(QrIntents.isValidPassphrase("a".repeat(64)))
        assertFalse(QrIntents.isValidPassphrase("a".repeat(65)))
        assertFalse(QrIntents.isValidPassphrase("비밀번호비밀번호"))
        assertFalse(QrIntents.isValidPassphrase(""))
        assertTrue(QrIntents.isValidSsid("x".repeat(32)))
        assertTrue(QrIntents.isValidSsid("가".repeat(10))) // 30바이트
        assertFalse(QrIntents.isValidSsid("가".repeat(11))) // 33바이트
        assertFalse(QrIntents.isValidSsid(""))
    }

    @Test
    fun wifi_fallbackWithoutCopy_whenDisabled() {
        val ex = QrActionExecutor.default(QrActionOptions(copyWifiPasswordOnFallback = false))
        val before = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val r = ex.execute(app, QrAction.ConnectWifi(QrPayload.Wifi("Old", "abcde", WifiSecurity.WEP)))
        assertEquals(QrActionResult.WifiSettingsOpened(passwordCopied = false), r)
        assertEquals(before?.getItemAt(0)?.text, (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text)
    }

    @Test
    fun intentBuilder_rejectsNonIntentActions() {
        for (a in listOf<QrAction>(
            QrAction.CopyText("a"), QrAction.ConnectWifi(QrPayload.Wifi("s")),
            QrAction.CopyWifiPassword(QrPayload.Wifi("s")), QrAction.CopyOtpSecret(QrPayload.Otp("a", "JBSWY3DP")),
        )) {
            val e = assertThrows<IllegalArgumentException> { QrIntents.build(a, SEOUL) }
            assertTrue(e.message!!.contains(a::class.simpleName!!))
        }
    }

    @Test
    fun defaultOptions() {
        val o = QrActionOptions()
        assertTrue(o.copyWifiPasswordOnFallback)
        assertEquals("QR", o.clipLabel)
        assertEquals(ZoneId.systemDefault(), o.zone)
        assertEquals("Started", QrActionResult.Started.toString())
    }
}

/** API 분기를 SDK_INT 주입으로도 검증한다(Robolectric 버전과 무관하게 30 미만 경로). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrActionExecutorSdkBranchTest {
    @Test
    fun belowR_alwaysSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ex = DefaultQrActionExecutor(QrActionOptions(), sdkInt = { Build.VERSION_CODES.Q })
        val r = ex.execute(app, QrAction.ConnectWifi(QrPayload.Wifi("Cafe", "pw12345678")))
        assertEquals(QrActionResult.WifiSettingsOpened(passwordCopied = true), r)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun belowR_openNetwork_noCopy() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ex = DefaultQrActionExecutor(QrActionOptions(), sdkInt = { 26 })
        val r = ex.execute(app, QrAction.ConnectWifi(QrPayload.Wifi("Open", "", WifiSecurity.NONE)))
        assertEquals(QrActionResult.WifiSettingsOpened(passwordCopied = false), r)
    }
}
