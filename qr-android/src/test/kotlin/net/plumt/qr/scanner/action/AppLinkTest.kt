package net.plumt.qr.scanner.action

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import net.plumt.qr.CodeFormat
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URISyntaxException

/** 앱 링크 옵트인 — 정책, Intent 다듬기, 동작 선택, 실행. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 35])
class AppLinkTest {

    private lateinit var app: Application
    private val pay = AppLinkPolicy.allow(setOf("examplepay", "Market"), allowIntentUri = true)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun iae(expected: String, block: () -> Unit) {
        val e = assertThrows<IllegalArgumentException> { block() }
        assertEquals(expected, e.message)
    }

    private fun text(t: String) = ScanResult(t, CodeFormat.QR_CODE, QrPayload.parse(t), emptyList())

    // ---- 정책 ----

    @Test
    fun `기본 정책은 아무 앱 링크도 열지 않는다`() {
        val none = AppLinkPolicy.NONE
        assertEquals(emptySet<String>(), none.schemes)
        assertFalse(none.allowIntentUri)
        listOf("kakaotalk://open", "intent://x#Intent;end", "market://details?id=a", "https://example.com", "", "abc").forEach {
            assertFalse(it, none.allows(it))
        }
    }

    @Test
    fun `고른 스킴만 허용한다`() {
        assertEquals(setOf("examplepay", "market"), pay.schemes)
        assertTrue(pay.allows("examplepay://pay?x=1"))
        assertTrue(pay.allows("  EXAMPLEPAY://pay  "))
        assertTrue(pay.allows("market://details?id=com.example"))
        assertTrue(pay.allows("market:details?id=com.example"))
        assertTrue(pay.allows("intent://pay#Intent;scheme=examplepay;end"))
        assertFalse(pay.allows("kakaotalk://open"))
        assertFalse(pay.allows("https://example.com"))
        assertFalse(pay.allows("http://example.com"))
        assertFalse(pay.allows("example.com:8080/x"))
        assertFalse(AppLinkPolicy.allow(setOf("examplepay")).allows("intent://pay#Intent;scheme=examplepay;end"))
        // 빈 목록 + intent 만
        val intentOnly = AppLinkPolicy.allow(emptySet(), allowIntentUri = true)
        assertTrue(intentOnly.allows("intent://x#Intent;end"))
        assertFalse(intentOnly.allows("examplepay://x"))
    }

    @Test
    fun `정책 검사`() {
        iae("스킴() 의 문법이 틀렸다 — 영문으로 시작하고 영문·숫자·+·.·- 만 쓴다(:// 없이)") { AppLinkPolicy.allow(setOf("")) }
        iae("스킴(kakaotalk://) 의 문법이 틀렸다 — 영문으로 시작하고 영문·숫자·+·.·- 만 쓴다(:// 없이)") { AppLinkPolicy.allow(setOf("kakaotalk://")) }
        iae("스킴(1app) 의 문법이 틀렸다 — 영문으로 시작하고 영문·숫자·+·.·- 만 쓴다(:// 없이)") { AppLinkPolicy.allow(setOf("1app")) }
        iae("스킴(카톡) 의 문법이 틀렸다 — 영문으로 시작하고 영문·숫자·+·.·- 만 쓴다(:// 없이)") { AppLinkPolicy.allow(setOf("카톡")) }
        iae("스킴(a_b) 의 문법이 틀렸다 — 영문으로 시작하고 영문·숫자·+·.·- 만 쓴다(:// 없이)") { AppLinkPolicy.allow(setOf("a_b")) }
        iae("intent 는 schemes 가 아니라 allowIntentUri = true 로 켠다") { AppLinkPolicy.allow(setOf("Intent")) }
        for (s in AppLinkPolicy.FORBIDDEN_SCHEMES - "intent") iae("스킴($s) 은 앱 링크로 열 수 없다") { AppLinkPolicy.allow(setOf(s.uppercase())) }
        // 문법에 맞는 기호
        assertEquals(setOf("a+b.c-d", "x1"), AppLinkPolicy.allow(setOf(" A+B.C-D ", "x1")).schemes)
    }

    @Test
    fun `정책의 값 비교`() {
        assertEquals(AppLinkPolicy.allow(setOf("a", "b")), AppLinkPolicy.allow(setOf("B", "a")))
        assertEquals(AppLinkPolicy.allow(setOf("a")).hashCode(), AppLinkPolicy.allow(setOf("A")).hashCode())
        assertNotEquals(AppLinkPolicy.allow(setOf("a")), AppLinkPolicy.allow(setOf("a"), allowIntentUri = true))
        assertNotEquals(AppLinkPolicy.allow(setOf("a")), AppLinkPolicy.allow(setOf("b")))
        assertNotEquals(AppLinkPolicy.NONE, Any())
        assertEquals(AppLinkPolicy.NONE, AppLinkPolicy.allow(emptySet()))
        assertEquals("AppLinkPolicy(schemes=[examplepay, market], allowIntentUri=true)", pay.toString())
    }

    // ---- Intent 다듬기 ----

    @Test
    fun `앱 스킴은 VIEW 와 BROWSABLE 로 연다`() {
        val i = AppLinkIntents.build(" examplepay://pay?amount=1000 ", pay)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(Uri.parse("examplepay://pay?amount=1000"), i.data)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(i.component)
        assertNull(i.`package`)
    }

    @Test
    fun `intent 주소는 컴포넌트·셀렉터·플래그를 지우고 BROWSABLE 을 붙인다`() {
        val url = "intent://pay?id=1#Intent;scheme=examplepay;package=com.example.pay;" +
            "component=com.example.pay/.Hidden;launchFlags=0x10000043;S.order=A1;" +
            "SEL;component=com.evil/.Target;end"
        val i = AppLinkIntents.build(url, pay)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("examplepay", i.data?.scheme)
        assertEquals("com.example.pay", i.`package`)
        assertNull(i.component)
        assertNull(i.selector)
        assertEquals(0, i.flags)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("A1", i.getStringExtra("order"))
        // VIEW 를 직접 적어도 같다
        assertEquals(Intent.ACTION_VIEW, AppLinkIntents.build("intent://x#Intent;action=android.intent.action.VIEW;scheme=https;end", pay).action)
        // 데이터 없는 intent(패키지만)
        val bare = AppLinkIntents.build("intent://#Intent;package=com.example.pay;end", pay)
        assertEquals("com.example.pay", bare.`package`)
    }

    @Test
    fun `intent 주소 검사`() {
        iae("intent: 의 action 은 VIEW 만 연다(받은 값: android.intent.action.CALL)") {
            AppLinkIntents.build("intent://010#Intent;action=android.intent.action.CALL;scheme=tel;end", pay)
        }
        iae("intent: 안쪽 스킴(kakaotalk) 이 앱 링크 정책에 없다") {
            AppLinkIntents.build("intent://open#Intent;scheme=kakaotalk;end", pay)
        }
        iae("intent: 안쪽 스킴(file) 이 앱 링크 정책에 없다") {
            AppLinkIntents.build("intent:///sdcard/x#Intent;scheme=file;end", pay)
        }
        val e = assertThrows<IllegalArgumentException> { AppLinkIntents.build("intent://x#Intent;scheme=examplepay", pay) }
        assertEquals("intent: 주소를 읽지 못했다", e.message)
        assertTrue(e.cause is URISyntaxException)
        iae("앱 링크 정책이 허용하지 않는 스킴이다(스킴: kakaotalk)") { AppLinkIntents.build("kakaotalk://open", pay) }
        iae("앱 링크 정책이 허용하지 않는 스킴이다(스킴: 없음)") { AppLinkIntents.build("example.com", pay) }
        iae("앱 링크 정책이 허용하지 않는 스킴이다(스킴: intent)") {
            AppLinkIntents.build("intent://x#Intent;end", AppLinkPolicy.allow(setOf("examplepay")))
        }
    }

    @Test
    fun `확인창 대상`() {
        assertEquals("com.example.pay", AppLinkIntents.target("intent://pay#Intent;scheme=examplepay;package=com.example.pay;end"))
        assertEquals("examplepay", AppLinkIntents.target("intent://pay#Intent;scheme=ExamplePay;end"))
        assertNull(AppLinkIntents.target("intent://pay#Intent;end"))
        assertNull(AppLinkIntents.target("intent://x#Intent;scheme=examplepay"))
        assertEquals("market", AppLinkIntents.target(" market://details?id=a "))
        assertNull(AppLinkIntents.target("example.com"))
    }

    // ---- 동작 선택 ----

    @Test
    fun `허용한 앱 링크 글은 열기 동작이 된다`() {
        val link = QrPayload.parse("examplepay://pay?amount=1")
        assertTrue(link is QrPayload.Text)
        val open = QrActions.primary(link, pay) as QrAction.OpenUrl
        assertEquals("examplepay://pay?amount=1", open.url)
        assertTrue(UrlWarning.NON_WEB_SCHEME in open.warnings)
        assertTrue(QrActions.requiresConfirmation(open))
        // 정책 밖·기본 정책은 복사
        assertEquals(QrAction.CopyText("examplepay://pay?amount=1"), QrActions.primary(link, AppLinkPolicy.NONE))
        assertEquals(QrAction.CopyText("kakaotalk://open"), QrActions.primary(QrPayload.parse("kakaotalk://open"), pay))
        // 앱 링크가 아닌 내용은 primary 와 같다
        val wifi = QrPayload.Wifi("Home", "pw123456")
        assertEquals(QrActions.primary(wifi), QrActions.primary(wifi, pay))

        assertEquals(listOf(open, QrAction.CopyText("examplepay://pay?amount=1")), QrActions.all(text("examplepay://pay?amount=1"), pay))
        assertEquals(QrActions.all(text("kakaotalk://open")), QrActions.all(text("kakaotalk://open"), pay))
        assertEquals(QrActions.all(text("https://example.com")), QrActions.all(text("https://example.com"), pay))
    }

    @Test
    fun `확인이 필요한 동작`() {
        assertFalse(QrActions.requiresConfirmation(QrActions.openUrl("https://example.com")))
        assertFalse(QrActions.requiresConfirmation(QrActions.openUrl("http://example.com")))
        assertTrue(QrActions.requiresConfirmation(QrActions.openUrl("https://user@example.com")))
        assertTrue(QrActions.requiresConfirmation(QrActions.openUrl("https://127.0.0.1/")))
        assertTrue(QrActions.requiresConfirmation(QrActions.openUrl("https://xn--80ak6aa92e.com/")))
        assertTrue(QrActions.requiresConfirmation(QrActions.openUrl("intent://x#Intent;end")))
        assertFalse(QrActions.requiresConfirmation(QrAction.Dial("010")))
        assertFalse(QrActions.requiresConfirmation(QrAction.CopyText("x")))
    }

    // ---- 실행 ----

    @Test
    fun `기본 실행기는 앱 링크를 열지 않는다`() {
        val r = QrActionExecutor.default().execute(app, QrActions.openUrl("examplepay://pay")) as QrActionResult.Failed
        assertEquals("http·https 가 아닌 주소는 열지 않는다(스킴: examplepay) — 앱 링크는 AppLinkPolicy 로 고른 스킴만 연다", r.error.message)
        assertTrue(r.error.cause is IllegalArgumentException)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `정책을 준 실행기는 허용한 앱 링크만 연다`() {
        val executor = QrActionExecutor.default(QrActionOptions(), pay)
        assertSame(QrActionResult.Started, executor.execute(app, QrActions.openUrl("examplepay://pay?x=1")))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Uri.parse("examplepay://pay?x=1"), started.data)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        assertSame(QrActionResult.Started, executor.execute(app, QrActions.openUrl("intent://p#Intent;scheme=examplepay;package=com.example.pay;component=a/.B;end")))
        val viaIntent = shadowOf(app).nextStartedActivity
        assertEquals("com.example.pay", viaIntent.`package`)
        assertEquals(null as ComponentName?, viaIntent.component)

        // 웹 링크는 그대로 연다
        assertSame(QrActionResult.Started, executor.execute(app, QrActions.openUrl("https://example.com")))
        assertEquals(Uri.parse("https://example.com"), shadowOf(app).nextStartedActivity.data)

        // 정책 밖 스킴, 스크립트
        val other = executor.execute(app, QrActions.openUrl("kakaotalk://open")) as QrActionResult.Failed
        assertTrue(other.error.message!!.contains("스킴: kakaotalk"))
        assertTrue(executor.execute(app, QrActions.openUrl("javascript:alert(1)")) is QrActionResult.Failed)
        // 다듬을 수 없는 intent
        val bad = executor.execute(app, QrActions.openUrl("intent://010#Intent;action=android.intent.action.CALL;scheme=tel;end")) as QrActionResult.Failed
        assertEquals("앱 링크를 열 수 없다(스킴: intent)", bad.error.message)
        assertTrue(bad.error.cause is IllegalArgumentException)
        assertNull(shadowOf(app).nextStartedActivity)

        // 처리할 앱이 없으면 NoHandler
        shadowOf(app).checkActivities(true)
        assertEquals(QrActionResult.NoHandler, executor.execute(app, QrActions.openUrl("examplepay://pay")))
    }
}
