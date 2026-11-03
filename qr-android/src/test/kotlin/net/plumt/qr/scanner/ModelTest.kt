package net.plumt.qr.scanner

import net.plumt.qr.CodeFormat
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class OptionsTest {

    @Test
    fun trackingOptions_validation() {
        TrackingOptions(0, 0f, 0f)
        TrackingOptions(1000, 0.999f, 1f)
        assertTrue(assertThrows<IllegalArgumentException> { TrackingOptions(maxMissedFrames = -1) }.message!!.contains("maxMissedFrames(-1)"))
        assertThrows<IllegalArgumentException> { TrackingOptions(maxMissedFrames = 1001) }
        assertTrue(assertThrows<IllegalArgumentException> { TrackingOptions(smoothing = 1f) }.message!!.contains("smoothing(1.0)"))
        assertThrows<IllegalArgumentException> { TrackingOptions(smoothing = -0.1f) }
        assertThrows<IllegalArgumentException> { TrackingOptions(smoothing = Float.NaN) }
        assertTrue(assertThrows<IllegalArgumentException> { TrackingOptions(iouThreshold = 1.5f) }.message!!.contains("iouThreshold(1.5)"))
        assertThrows<IllegalArgumentException> { TrackingOptions(iouThreshold = -0.01f) }
        assertThrows<IllegalArgumentException> { TrackingOptions(iouThreshold = Float.NaN) }
    }

    @Test
    fun lockOptions_validation() {
        LockOptions(0f, 1)
        LockOptions(1f, 1000)
        assertTrue(assertThrows<IllegalArgumentException> { LockOptions(hysteresis = 1.01f) }.message!!.contains("hysteresis(1.01)"))
        assertThrows<IllegalArgumentException> { LockOptions(hysteresis = -1f) }
        assertThrows<IllegalArgumentException> { LockOptions(hysteresis = Float.NaN) }
        assertTrue(assertThrows<IllegalArgumentException> { LockOptions(switchFrames = 0) }.message!!.contains("switchFrames(0)"))
        assertThrows<IllegalArgumentException> { LockOptions(switchFrames = 1001) }
    }

    @Test
    fun autoDeliverOptions_validation() {
        AutoDeliverOptions(0, 0)
        AutoDeliverOptions(Long.MAX_VALUE, Long.MAX_VALUE)
        assertTrue(assertThrows<IllegalArgumentException> { AutoDeliverOptions(stableMillis = -1) }.message!!.contains("stableMillis(-1)"))
        assertTrue(assertThrows<IllegalArgumentException> { AutoDeliverOptions(repeatWindowMillis = -5) }.message!!.contains("repeatWindowMillis(-5)"))
    }

    @Test
    fun scannerOptions_defaultsAndValidation() {
        val o = ScannerOptions()
        assertEquals(CodeFormat.QR_ONLY, o.formats)
        assertSame(ScanEngines.mlKit(), o.engine)
        assertEquals(CameraLens.BACK, o.lens)
        assertEquals(PreviewScaleMode.FILL, o.scaleMode)
        assertTrue(o.pinchToZoom && o.tapToFocus)
        assertEquals(o, ScannerOptions())
        assertEquals(o.hashCode(), ScannerOptions().hashCode())
        assertEquals(ScannerOptions(engine = ScanEngines.zxing()), ScannerOptions(engine = ScanEngines.zxing()))
        assertTrue(assertThrows<IllegalArgumentException> { ScannerOptions(formats = emptySet()) }.message!!.contains("formats"))
    }

    @Test
    fun effectiveResolution() {
        assertEquals(AnalysisResolution.STANDARD, ScannerOptions().effectiveResolution)
        assertEquals(AnalysisResolution.STANDARD, ScannerOptions(formats = setOf(CodeFormat.DATA_MATRIX, CodeFormat.PDF_417, CodeFormat.AZTEC)).effectiveResolution)
        assertEquals(AnalysisResolution.HIGH, ScannerOptions(formats = CodeFormat.PRODUCT).effectiveResolution)
        assertEquals(AnalysisResolution.HIGH, ScannerOptions(formats = setOf(CodeFormat.QR_CODE, CodeFormat.CODE_128)).effectiveResolution)
        assertEquals(AnalysisResolution.STANDARD, ScannerOptions(formats = CodeFormat.ALL, analysisResolution = AnalysisResolution.STANDARD).effectiveResolution)
        assertEquals(AnalysisResolution.HIGH, ScannerOptions(analysisResolution = AnalysisResolution.HIGH).effectiveResolution)
    }

    @Test
    fun modes() {
        assertFalse(ScanMode.Single().autoDeliver)
        assertTrue(ScanMode.Single(autoDeliver = true).autoDeliver)
        assertEquals(ScanMode.Single(true), ScanMode.Single(true))
        assertEquals("Multi", ScanMode.Multi.toString())
        assertTrue(CameraLens.FRONT.ordinal > CameraLens.BACK.ordinal)
    }
}

class ModelTest {

    @Test
    fun codeBounds_derivedValues() {
        val b = CodeBounds(10f, 20f, 30f, 60f)
        assertEquals(20f, b.width, EPS)
        assertEquals(40f, b.height, EPS)
        assertEquals(20f, b.centerX, EPS)
        assertEquals(40f, b.centerY, EPS)
        assertTrue(b.contains(10f, 20f))
        assertTrue(b.contains(30f, 60f))
        assertTrue((15f to 25f) in b)
        assertFalse(b.contains(9.9f, 30f))
        assertFalse(b.contains(15f, 60.1f))
        // 뒤집힌 사각형의 크기는 0
        val inv = CodeBounds(10f, 10f, 0f, 0f)
        assertEquals(0f, inv.width, EPS)
        assertEquals(0f, inv.height, EPS)
        assertFalse(inv.contains(5f, 5f))
    }

    @Test
    fun trackedCode_equalityAndHash() {
        val r = ScanResult("A", CodeFormat.QR_CODE, QrPayload.Text("A"), emptyList())
        val a = TrackedCode(1, r, box(0f, 0f, 1f, 1f), 0, true)
        assertEquals(a, TrackedCode(1, r, box(0f, 0f, 1f, 1f), 0, true))
        assertEquals(a.hashCode(), TrackedCode(1, r, box(0f, 0f, 1f, 1f), 0, true).hashCode())
        assertNotEquals(a, TrackedCode(2, r, box(0f, 0f, 1f, 1f), 0, true))
        assertNotEquals(a, TrackedCode(1, result("B"), box(0f, 0f, 1f, 1f), 0, true))
        assertNotEquals(a, TrackedCode(1, r, box(0f, 0f, 2f, 1f), 0, true))
        assertNotEquals(a, TrackedCode(1, r, box(0f, 0f, 1f, 1f), 1, true))
        assertNotEquals(a, TrackedCode(1, r, box(0f, 0f, 1f, 1f), 0, false))
        assertNotEquals(a, "A")
        assertFalse(a.isStale)
        assertTrue(TrackedCode(1, r, a.bounds, 3, false).isStale)
    }

    @Test
    fun trackedCode_toStringNeverLeaksSecrets() {
        val wifiText = "WIFI:T:WPA;S:Cafe;P:supersecret123;;"
        val otpText = "otpauth://totp/Ex:me?secret=JBSWY3DPEHPK3PXP&issuer=Ex"
        for (text in listOf(wifiText, otpText)) {
            val code = TrackedCode(1, result(text), box(0f, 0f, 1f, 1f), 0, false)
            val s = code.toString()
            assertFalse(s, s.contains("supersecret123"))
            assertFalse(s, s.contains("JBSWY3DPEHPK3PXP"))
        }
    }

    @Test
    fun stateValueTypes() {
        assertEquals(TorchState(true, false), TorchState(true, false).copy())
        assertEquals(ZoomState(2f, 1f, 8f).ratio, 2f, EPS)
        val e = ScannerException("m", IllegalStateException("c"))
        assertEquals("m", e.message)
        assertEquals("c", e.cause!!.message)
        assertEquals(null, ScannerException("x").cause)
        assertEquals(CameraStatus.Error(e), CameraStatus.Error(e))
        assertTrue(Detection(result("a"), box(0f, 0f, 1f, 1f)) == Detection(result("a"), box(0f, 0f, 1f, 1f)))
    }
}

class ScannerDefaultsTest {

    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun summary_neverContainsWifiPasswordOrOtpSecret() {
        val wifi = result("WIFI:T:WPA;S:Cafe;P:supersecret123;;")
        assertEquals("Cafe", ScannerDefaults.summary(wifi, zone))
        val noName = result("WIFI:T:WPA;S:;P:supersecret123;;")
        assertFalse(ScannerDefaults.summary(noName, zone).contains("supersecret"))
        val otp = result("otpauth://totp/Ex:me?secret=JBSWY3DPEHPK3PXP&issuer=Ex")
        val s = ScannerDefaults.summary(otp, zone)
        assertFalse(s.contains("JBSWY3DPEHPK3PXP"))
        assertEquals("Ex:me", s)
        val brokenOtp = result("otpauth://totp/?secret=JBSWY3DPEHPK3PXP")
        assertFalse(ScannerDefaults.summary(brokenOtp, zone).contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun summary_truncatesByCodePoints_andFlattensWhitespace() {
        assertEquals("a b c", ScannerDefaults.summary(result("a  b\t\tc"), zone))
        assertEquals("첫 줄만", "a", ScannerDefaults.summary(result("a\nb"), zone))
        val long = "x".repeat(100)
        val s = ScannerDefaults.summary(result(long), zone)
        assertEquals(ScannerDefaults.SUMMARY_MAX_LENGTH, s.codePointCount(0, s.length))
        assertTrue(s.endsWith("…"))
        // 이모지(서로게이트 쌍)를 반으로 자르지 않는다
        val emoji = "😀".repeat(50)
        val e = ScannerDefaults.summary(result(emoji), zone, maxLength = 5)
        assertEquals("😀".repeat(4) + "…", e)
        assertEquals("…", ScannerDefaults.summary(result("abc"), zone, maxLength = 1))
        assertEquals("abc", ScannerDefaults.summary(result("abc"), zone, maxLength = 3))
        assertEquals("", ScannerDefaults.summary(result(""), zone))
        assertTrue(assertThrows<IllegalArgumentException> { ScannerDefaults.summary(result("a"), zone, 0) }.message!!.contains("maxLength(0)"))
    }

    @Test
    fun summary_productAndDefaultZone() {
        assertEquals("4006381333931", ScannerDefaults.summary(result("4006381333931", CodeFormat.EAN_13)))
        // 이름 없는 연락처는 원문 첫 줄로
        val c = result("BEGIN:VCARD\nVERSION:3.0\nTEL:010\nEND:VCARD")
        assertTrue(ScannerDefaults.summary(c, zone).isNotBlank())
    }

    @Test
    fun labels_coverEveryPayloadAndAction() {
        val payloads: List<QrPayload> = listOf(
            QrPayload.Url("https://a.example"), QrPayload.Text("t"), QrPayload.Wifi("s"), QrPayload.Contact(name = "n"),
            QrPayload.Email("a@example.com"), QrPayload.Phone("1"), QrPayload.Sms("1"), QrPayload.Event("e"),
            QrPayload.Geo(1.0, 2.0), QrPayload.Otp("a", "JBSWY3DP"), QrPayload.Product("1"),
        )
        val ids = payloads.map { ScannerDefaults.typeLabelRes(it) }
        assertEquals(payloads.size, ids.toSet().size)
        assertTrue(ids.all { it != 0 })
        val actions = payloads.flatMap { net.plumt.qr.scanner.action.QrActions.all(ScanResult(it.toString(), CodeFormat.QR_CODE, it, emptyList())) }
        val actionIds = (actions + net.plumt.qr.scanner.action.QrAction.CopyWifiPassword(QrPayload.Wifi("s", "p")))
            .map { ScannerDefaults.actionLabelRes(it) }.toSet()
        assertEquals(13, actionIds.size)
        val warn = net.plumt.qr.scanner.action.UrlWarning.entries.map { ScannerDefaults.warningRes(it) }
        assertEquals(net.plumt.qr.scanner.action.UrlWarning.entries.size, warn.toSet().size)
    }

    @Test
    fun chipPlacement() {
        // 아래에 자리 있음 → 박스 아래 gap, 가운데 정렬
        assertEquals(50f to 118f, ChipPlacement.place(box(50f, 50f, 110f, 110f), 60f, 20f, 500f, 500f, 8f))
        // 아래가 모자람 → 위
        assertEquals(50f to 22f, ChipPlacement.place(box(50f, 50f, 110f, 480f), 60f, 20f, 500f, 500f, 8f))
        // 위아래 모두 모자람 → 바닥에 붙인다
        assertEquals(50f to 480f, ChipPlacement.place(box(50f, 10f, 110f, 490f), 60f, 20f, 500f, 500f, 8f))
        // 왼쪽·오른쪽 가장자리 → 안으로 민다
        assertEquals(0f to 118f, ChipPlacement.place(box(-50f, 50f, 10f, 110f), 60f, 20f, 500f, 500f, 8f))
        assertEquals(440f to 118f, ChipPlacement.place(box(480f, 50f, 540f, 110f), 60f, 20f, 500f, 500f, 8f))
        // 칩이 뷰보다 크다 → 왼쪽·위
        assertEquals(0f to 0f, ChipPlacement.place(box(0f, 0f, 10f, 10f), 600f, 600f, 500f, 500f, 8f))
    }
}
