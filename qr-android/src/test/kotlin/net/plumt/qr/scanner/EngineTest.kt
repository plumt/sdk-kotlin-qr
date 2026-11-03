package net.plumt.qr.scanner

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.common.internal.BarcodeSource
import net.plumt.qr.Barcode as QrBarcode
import net.plumt.qr.BarcodeOptions
import net.plumt.qr.CodeFormat
import net.plumt.qr.QrCode
import net.plumt.qr.QrCodeException
import net.plumt.qr.QrPayload
import net.plumt.qr.ReadOptions
import net.plumt.qr.ScanPoint
import net.plumt.qr.scanner.internal.BoxMath
import net.plumt.qr.scanner.internal.MlKitEngine
import net.plumt.qr.scanner.internal.MlKitMapper
import net.plumt.qr.scanner.internal.Nv21
import net.plumt.qr.scanner.internal.PointBounds
import net.plumt.qr.scanner.internal.ZxingEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

class ZxingEngineTest {

    private val text = "https://example.com/scan?id=42"
    private val matrix = QrCode.encode(text)
    private val scale = 6
    private val margin = 4 // QrCodeOptions 기본 여백

    /** 세운 480x640 이미지의 (ox, oy) 에 QR 을 그리고, 회전 r 로 찍힌 버퍼를 엔진에 넣는다. */
    private fun detectAt(r: Int, ox: Int = 120, oy: Int = 200): Pair<Detection, CodeBounds> {
        val upright = luminanceOf(matrix, 480, 640, ox, oy, scale)
        val (buf, bw, bh) = unrotate(upright, 480, 640, r)
        val frame = ScanFrame(buf, bw, bh, rotationDegrees = r)
        assertEquals(480, frame.uprightWidth)
        assertEquals(640, frame.uprightHeight)
        val out = ZxingEngine(ReadOptions(formats = CodeFormat.QR_ONLY)).detect(frame)
        val truth = CodeBounds(
            (ox + margin * scale).toFloat(), (oy + margin * scale).toFloat(),
            (ox + (matrix.width - margin) * scale).toFloat(), (oy + (matrix.height - margin) * scale).toFloat(),
        )
        return out.single() to truth
    }

    @Test
    fun allRotations_yieldUprightBoundsOverTheCode() {
        for (r in listOf(0, 90, 180, 270)) {
            val (d, truth) = detectAt(r)
            assertEquals(text, d.result.text)
            assertEquals(CodeFormat.QR_CODE, d.result.format)
            assertEquals(QrPayload.Url(text), d.result.payload)
            assertTrue("회전 $r 에서 가운데가 어긋났다: ${d.bounds} vs $truth", BoxMath.centerDistance(d.bounds, truth) < 4f)
            assertTrue("회전 $r 의 겹침이 작다: ${BoxMath.iou(d.bounds, truth)}", BoxMath.iou(d.bounds, truth) > 0.85f)
            // 점도 세운 좌표다 — 모두 코드 안
            d.result.points.forEach { assertTrue("$r: $it", truth.contains(it.x, it.y)) }
        }
    }

    @Test
    fun differentPositions_rotation90() {
        for ((ox, oy) in listOf(0 to 0, 250 to 0, 0 to 430, 250 to 430)) {
            val (d, truth) = detectAt(90, ox, oy)
            assertTrue("($ox,$oy) ${d.bounds} vs $truth", BoxMath.centerDistance(d.bounds, truth) < 4f)
        }
    }

    @Test
    fun rowStridePadding_isHonored() {
        val w = 320
        val h = 320
        val stride = 352
        val buf = luminanceOf(matrix, w, h, 20, 20, 6, rowStride = stride)
        val out = ZxingEngine(ReadOptions(formats = CodeFormat.QR_ONLY)).detect(ScanFrame(buf, w, h, rowStride = stride))
        assertEquals(text, out.single().result.text)
    }

    @Test
    fun emptyFrame_returnsEmpty() {
        val frame = ScanFrame(ByteArray(64 * 48) { 0x7F }, 64, 48)
        assertTrue(ZxingEngine(ReadOptions()).detect(frame).isEmpty())
    }

    @Test
    fun linearBarcode_getsBandBounds() {
        val m = QrBarcode.encode("4006381333931", CodeFormat.EAN_13, BarcodeOptions(width = 300, height = 100))
        val buf = luminanceOf(m, 480, 640, 90, 270, 1)
        val out = ZxingEngine(ReadOptions(formats = CodeFormat.PRODUCT)).detect(ScanFrame(buf, 480, 640))
        val d = out.single()
        assertEquals(CodeFormat.EAN_13, d.result.format)
        assertEquals(QrPayload.Product("4006381333931"), d.result.payload)
        assertTrue(d.bounds.width > d.bounds.height)
        assertTrue(d.bounds.centerY in 270f..370f)
    }

    @Test
    fun engineFailure_isWrappedWithCause() {
        val cause = QrCodeException("내부")
        val e = assertThrows<ScannerException> {
            ZxingEngine(ReadOptions()) { _, _, _, _, _ -> throw cause }.detect(ScanFrame(ByteArray(4), 2, 2))
        }
        assertSame(cause, e.cause)
        val iae = IllegalArgumentException("크기")
        val e2 = assertThrows<ScannerException> {
            ZxingEngine(ReadOptions()) { _, _, _, _, _ -> throw iae }.detect(ScanFrame(ByteArray(4), 2, 2))
        }
        assertSame(iae, e2.cause)
    }

    @Test
    fun resultsWithoutPoints_areDropped() {
        val engine = ZxingEngine(ReadOptions()) { _, _, _, _, _ -> listOf(result("x")) }
        assertTrue(engine.detect(ScanFrame(ByteArray(4), 2, 2)).isEmpty())
        engine.close()
        engine.close()
    }

    @Test
    fun readerReceivesFrameAndOptions() {
        val opts = ReadOptions(formats = setOf(CodeFormat.AZTEC), tryHarder = true)
        var seen: List<Any>? = null
        val engine = ZxingEngine(opts) { l, w, h, s, o -> seen = listOf(l.size, w, h, s, o); emptyList() }
        engine.detect(ScanFrame(ByteArray(10 * 3), 8, 3, rowStride = 10))
        assertEquals(listOf(30, 8, 3, 10, opts), seen)
    }

    @Test
    fun fuzz_randomFramesNeverCrash() {
        val rnd = Random(5)
        val engine = ZxingEngine(ReadOptions(formats = CodeFormat.ALL))
        repeat(60) {
            val w = rnd.nextInt(1, 120)
            val h = rnd.nextInt(1, 120)
            val stride = w + rnd.nextInt(0, 8)
            val r = listOf(0, 90, 180, 270)[rnd.nextInt(4)]
            val frame = ScanFrame(rnd.nextBytes(stride * h), w, h, stride, r)
            try {
                engine.detect(frame).forEach {
                    assertTrue(it.bounds.width >= 0f && it.bounds.height >= 0f)
                }
            } catch (_: ScannerException) {
                // 허용: SDK 예외로만 실패한다
            }
        }
    }

}

class PointBoundsTest {

    @Test
    fun qr_threePoints_completesParallelogramAndPads() {
        // 왼쪽 아래 (10, 110), 왼쪽 위 (10, 10), 오른쪽 위 (110, 10) → 네 번째 (110, 110), 변 100, 여백 20
        val b = PointBounds.estimate(listOf(ScanPoint(10f, 110f), ScanPoint(10f, 10f), ScanPoint(110f, 10f)), CodeFormat.QR_CODE)!!
        assertEquals(box(-10f, -10f, 130f, 130f), b)
    }

    @Test
    fun qr_fourPoints_usesFirstThree() {
        val b = PointBounds.estimate(
            listOf(ScanPoint(10f, 110f), ScanPoint(10f, 10f), ScanPoint(110f, 10f), ScanPoint(95f, 95f)),
            CodeFormat.QR_CODE,
        )!!
        assertEquals(box(-10f, -10f, 130f, 130f), b)
    }

    @Test
    fun qr_rotatedPoints() {
        // 45도 돌린 QR — 네 모서리를 감싼 사각형
        val b = PointBounds.estimate(listOf(ScanPoint(50f, 100f), ScanPoint(0f, 50f), ScanPoint(50f, 0f)), CodeFormat.QR_CODE)!!
        assertEquals(50f, b.centerX, EPS)
        assertEquals(50f, b.centerY, EPS)
    }

    @Test
    fun linear_twoPoints_horizontalAndVertical() {
        assertEquals(box(0f, 85f, 100f, 115f), PointBounds.estimate(listOf(ScanPoint(0f, 100f), ScanPoint(100f, 100f)), CodeFormat.EAN_13))
        assertEquals(box(85f, 0f, 115f, 100f), PointBounds.estimate(listOf(ScanPoint(100f, 0f), ScanPoint(100f, 100f)), CodeFormat.CODE_128))
    }

    @Test
    fun other_padsEnclosure() {
        assertEquals(box(-10f, -10f, 110f, 60f), PointBounds.estimate(listOf(ScanPoint(0f, 0f), ScanPoint(100f, 50f)), CodeFormat.DATA_MATRIX))
        assertEquals(box(-5f, -5f, 55f, 55f), PointBounds.estimate(listOf(ScanPoint(0f, 0f), ScanPoint(50f, 50f), ScanPoint(50f, 0f), ScanPoint(0f, 50f)), CodeFormat.AZTEC))
        // QR 인데 점이 둘뿐이면 일반 규칙
        assertEquals(box(-10f, -10f, 110f, 110f), PointBounds.estimate(listOf(ScanPoint(0f, 0f), ScanPoint(100f, 100f)), CodeFormat.QR_CODE))
    }

    @Test
    fun emptyAndSinglePoint() {
        assertNull(PointBounds.estimate(emptyList(), CodeFormat.QR_CODE))
        assertEquals(box(5f, 5f, 5f, 5f), PointBounds.estimate(listOf(ScanPoint(5f, 5f)), CodeFormat.EAN_8))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlKitMapperTest {

    private class FakeSource(
        private val format: Int,
        private val raw: String?,
        private val box: Rect?,
        private val corners: Array<Point>? = null,
    ) : BarcodeSource {
        override fun getFormat(): Int = format
        override fun getValueType(): Int = Barcode.TYPE_TEXT
        override fun getBoundingBox(): Rect? = box
        override fun getCalendarEvent(): Barcode.CalendarEvent? = null
        override fun getContactInfo(): Barcode.ContactInfo? = null
        override fun getDriverLicense(): Barcode.DriverLicense? = null
        override fun getEmail(): Barcode.Email? = null
        override fun getGeoPoint(): Barcode.GeoPoint? = null
        override fun getPhone(): Barcode.Phone? = null
        override fun getSms(): Barcode.Sms? = null
        override fun getUrl(): Barcode.UrlBookmark? = null
        override fun getWifi(): Barcode.WiFi? = null
        override fun getDisplayValue(): String? = raw
        override fun getRawValue(): String? = raw
        override fun getRawBytes(): ByteArray? = raw?.toByteArray()
        override fun getCornerPoints(): Array<Point>? = corners
    }

    private fun bc(format: Int, raw: String?, box: Rect? = Rect(10, 20, 110, 120), corners: Array<Point>? = null) =
        Barcode(FakeSource(format, raw, box, corners))

    @Test
    fun formatMapping_isBijectiveOverAllFormats() {
        val seen = HashSet<Int>()
        for (f in CodeFormat.entries) {
            val ml = MlKitMapper.toMlKit(f)
            assertTrue("중복 $ml", seen.add(ml))
            assertEquals(f, MlKitMapper.fromMlKit(ml))
        }
        assertEquals(Barcode.FORMAT_QR_CODE, MlKitMapper.toMlKit(CodeFormat.QR_CODE))
        assertEquals(Barcode.FORMAT_PDF417, MlKitMapper.toMlKit(CodeFormat.PDF_417))
        assertNull(MlKitMapper.fromMlKit(Barcode.FORMAT_UNKNOWN))
        assertNull(MlKitMapper.fromMlKit(Barcode.FORMAT_ALL_FORMATS))
        assertNull(MlKitMapper.fromMlKit(12345))
    }

    @Test
    fun formatArgs_splitsFirstAndRest() {
        val (first, rest) = MlKitMapper.formatArgs(setOf(CodeFormat.EAN_13, CodeFormat.QR_CODE))
        assertEquals(Barcode.FORMAT_QR_CODE, first)
        assertArrayEquals(intArrayOf(Barcode.FORMAT_EAN_13), rest)
        val (one, none) = MlKitMapper.formatArgs(CodeFormat.QR_ONLY)
        assertEquals(Barcode.FORMAT_QR_CODE, one)
        assertEquals(0, none.size)
        assertEquals(12, MlKitMapper.formatArgs(CodeFormat.ALL).second.size)
        val e = assertThrows<IllegalArgumentException> { MlKitMapper.formatArgs(emptySet()) }
        assertTrue(e.message!!.contains("formats"))
    }

    @Test
    fun map_basicQr() {
        val corners = arrayOf(Point(10, 20), Point(110, 20), Point(110, 120), Point(10, 120))
        val d = MlKitMapper.map(listOf(bc(Barcode.FORMAT_QR_CODE, "WIFI:S:Cafe;T:WPA;P:secretpw;;", corners = corners)), CodeFormat.QR_ONLY).single()
        assertEquals(box(10f, 20f, 110f, 120f), d.bounds)
        assertEquals(CodeFormat.QR_CODE, d.result.format)
        assertEquals(QrPayload.Wifi("Cafe", "secretpw"), d.result.payload)
        assertEquals(listOf(ScanPoint(10f, 20f), ScanPoint(110f, 20f), ScanPoint(110f, 120f), ScanPoint(10f, 120f)), d.result.points)
    }

    @Test
    fun map_productFormatParsesAsProduct() {
        val d = MlKitMapper.map(listOf(bc(Barcode.FORMAT_EAN_13, "4006381333931")), CodeFormat.PRODUCT).single()
        assertEquals(QrPayload.Product("4006381333931"), d.result.payload)
        assertEquals(CodeFormat.EAN_13, d.result.format)
    }

    @Test
    fun map_dropsUnknownUnrequestedNullRawAndBoxless() {
        val list = listOf(
            bc(Barcode.FORMAT_UNKNOWN, "a"),
            bc(Barcode.FORMAT_AZTEC, "b"), // 요청하지 않은 형식
            bc(Barcode.FORMAT_QR_CODE, null), // 바이너리
            bc(Barcode.FORMAT_QR_CODE, "c", box = null),
            bc(Barcode.FORMAT_QR_CODE, "d", box = Rect(5, 5, 5, 9)), // 너비 0, 모서리 없음
            bc(Barcode.FORMAT_QR_CODE, "ok"),
        )
        assertEquals(listOf("ok"), MlKitMapper.map(list, CodeFormat.QR_ONLY).map { it.result.text })
    }

    @Test
    fun map_fallsBackToCornersWhenBoxIsEmpty() {
        val corners = arrayOf(Point(3, 4), Point(30, 4), Point(30, 40), Point(3, 40))
        val d = MlKitMapper.map(listOf(bc(Barcode.FORMAT_QR_CODE, "x", box = Rect(0, 0, 0, 0), corners = corners)), CodeFormat.QR_ONLY).single()
        assertEquals(box(3f, 4f, 30f, 40f), d.bounds)
    }

    @Test
    fun map_emptyAndUnicode() {
        assertTrue(MlKitMapper.map(emptyList(), CodeFormat.ALL).isEmpty())
        val d = MlKitMapper.map(listOf(bc(Barcode.FORMAT_QR_CODE, "안녕 😀")), CodeFormat.QR_ONLY).single()
        assertEquals(QrPayload.Text("안녕 😀"), d.result.payload)
        assertEquals("", MlKitMapper.map(listOf(bc(Barcode.FORMAT_QR_CODE, "")), CodeFormat.QR_ONLY).single().result.text)
    }

    @Test
    fun zxingFactory_isValueComparable_andCreates() {
        assertEquals(ScanEngines.zxing(), ScanEngines.zxing())
        assertEquals(ScanEngines.zxing(tryHarder = true, alsoInverted = true), ZxingEngine.Factory(true, true))
        assertNotEquals(ScanEngines.zxing(), ScanEngines.zxing(tryHarder = true))
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(ScanEngines.zxing().create(ctx, CodeFormat.QR_ONLY) is ZxingEngine)
        val e = assertThrows<IllegalArgumentException> { ScanEngines.zxing().create(ctx, emptySet()) }
        assertTrue(e.message!!.contains("formats"))
    }

    @Test
    fun mlKitFactory_isSingletonAndRejectsEmptyFormats() {
        assertSame(ScanEngines.mlKit(), ScanEngines.mlKit())
        assertEquals("ScanEngines.mlKit()", ScanEngines.mlKit().toString())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertThrows<IllegalArgumentException> { MlKitEngine.Factory.create(ctx, emptySet()) }
    }

    @Test
    fun mlKitFactory_withoutMlKitRuntime_throwsSdkException() {
        // Robolectric 에는 ML Kit 런타임(MlKitContext)이 초기화돼 있지 않다 — 조용한 대체 없이 SDK 예외로 알린다
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val e = assertThrows<ScannerException> { ScanEngines.mlKit().create(ctx, CodeFormat.QR_ONLY) }
        assertTrue(e.cause != null)
    }
}

class Nv21Test {
    @Test
    fun compactsStrideAndFillsGrayChroma() {
        val luma = byteArrayOf(1, 2, 3, 9, 4, 5, 6, 9) // 3x2, 행 간격 4
        val out = Nv21.fromLuminance(luma, 3, 2, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), out.copyOfRange(0, 6))
        assertEquals(6 + 2 * 2 * 1, out.size)
        assertTrue(out.copyOfRange(6, out.size).all { it == 128.toByte() })
    }

    @Test
    fun noStride_copiesDirectly_oddSizes() {
        val luma = ByteArray(5 * 3) { it.toByte() }
        val out = Nv21.fromLuminance(luma, 5, 3, 5)
        assertArrayEquals(luma, out.copyOfRange(0, 15))
        assertEquals(15 + 2 * 3 * 2, out.size)
    }
}
