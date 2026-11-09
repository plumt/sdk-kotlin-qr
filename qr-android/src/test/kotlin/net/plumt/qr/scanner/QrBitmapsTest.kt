package net.plumt.qr.scanner

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.plumt.qr.ArgbImage
import net.plumt.qr.CodeColors
import net.plumt.qr.CodeFormat
import net.plumt.qr.CodeMatrix
import net.plumt.qr.QrCode
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 비트맵 그리기·사진 읽기. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 30, 35])
class QrBitmapsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun read(bitmap: Bitmap, formats: Set<CodeFormat> = CodeFormat.QR_ONLY, engine: ScanEngine.Factory = ScanEngines.zxing(tryHarder = true)) =
        runBlocking { BitmapCodeReader.read(app, bitmap, formats, engine) }

    // ---- 그리기 ----

    @Test
    fun `격자를 요청 크기의 ARGB_8888 비트맵으로 그린다`() {
        val m = QrCode.encode("https://example.com")
        val colors = CodeColors(0xFF0D47A1.toInt(), 0xFFFFF9C4.toInt())
        val b = QrBitmaps.render(m, 300, colors = colors)
        assertEquals(300, b.width)
        assertEquals(300, b.height)
        assertEquals(Bitmap.Config.ARGB_8888, b.config)
        val expected = net.plumt.qr.CodeImages.render(m, 300, colors = colors).toIntArray()
        assertTrue(expected.contentEquals(pixels(b)))
        val wide = QrBitmaps.render(m, 400, 200)
        assertEquals(400, wide.width)
        assertEquals(200, wide.height)
        assertThrows<IllegalArgumentException> { QrBitmaps.render(m, 10) }
    }

    @Test
    fun `로고를 얹은 QR 비트맵`() {
        val code = QrCode.encodeWithLogo("https://example.com/logo")
        val logo = Bitmap.createBitmap(IntArray(16) { 0xFFFF0000.toInt() }, 4, 4, Bitmap.Config.ARGB_8888)
        val b = QrBitmaps.render(code, code.matrix.width * 4, logo)
        val a = code.logoArea
        assertEquals(0xFFFF0000.toInt(), b.getPixel(a.left * 4 + 1, a.top * 4 + 1))
        assertEquals(CodeMatrix.WHITE, b.getPixel(0, 0))
        // 그린 비트맵을 다시 읽는다
        assertEquals(listOf("https://example.com/logo"), read(b).map { it.text })
    }

    @Test
    fun `비트맵과 ArgbImage 를 오간다`() {
        val image = ArgbImage(3, 2, intArrayOf(1 or (0xFF shl 24), 2 or (0xFF shl 24), 3 or (0xFF shl 24), 4 or (0xFF shl 24), 5 or (0xFF shl 24), 6 or (0xFF shl 24)))
        val b = QrBitmaps.toBitmap(image)
        assertEquals(image, QrBitmaps.toArgbImage(b))
        val recycled = QrBitmaps.toBitmap(image).also { it.recycle() }
        val e = assertThrows<IllegalArgumentException> { QrBitmaps.toArgbImage(recycled) }
        assertEquals("재활용된 비트맵이다", e.message)
    }

    @Test
    @Config(sdk = [30])
    fun `하드웨어 비트맵은 복사해 읽는다`() {
        val src = QrBitmaps.render(QrCode.encode("hw"), 120)
        val hw = src.copy(Bitmap.Config.HARDWARE, false) ?: return
        if (hw.config != Bitmap.Config.HARDWARE) return
        assertEquals(QrBitmaps.toArgbImage(src), QrBitmaps.toArgbImage(hw))
    }

    // ---- 사진 읽기 ----

    @Test
    fun `사진 속 코드를 모두 읽는다`() {
        val qr = QrBitmaps.render(QrCode.encode(QrPayload.Wifi("Home", "pw123456")), 400)
        val found = read(qr)
        assertEquals(1, found.size)
        assertEquals(QrPayload.Wifi("Home", "pw123456"), found[0].payload)
        assertEquals(CodeFormat.QR_CODE, found[0].format)
        // 점은 비트맵 좌표다
        found[0].points.forEach { assertTrue("$it", it.x in 0f..400f && it.y in 0f..400f) }
        // 빈 사진
        val blank = Bitmap.createBitmap(IntArray(100 * 100) { -1 }, 100, 100, Bitmap.Config.ARGB_8888)
        assertEquals(emptyList<ScanResult>(), read(blank))
    }

    @Test
    fun `투명 배경 QR 은 흰 바탕으로 읽는다`() {
        val m = QrCode.encode("transparent")
        val b = QrBitmaps.render(m, 300, colors = CodeColors(CodeMatrix.BLACK, 0x00000000))
        assertEquals(listOf("transparent"), read(b).map { it.text })
    }

    @Test
    fun `밝기 변환`() {
        assertEquals(0, Bitmaps.luma(0xFF000000.toInt()))
        assertEquals(255, Bitmaps.luma(-1))
        assertEquals(255, Bitmaps.luma(0x00000000))
        assertEquals(76, Bitmaps.luma(0xFFFF0000.toInt()))
        assertEquals(150, Bitmaps.luma(0xFF00FF00.toInt()))
        assertEquals(29, Bitmaps.luma(0xFF0000FF.toInt()))
        // 절반 투명 검정 → 흰 바탕 위 회색
        assertEquals(127, Bitmaps.luma(0x80000000.toInt()))
        val b = Bitmap.createBitmap(intArrayOf(0xFF000000.toInt(), -1, 0x00000000, 0xFFFF0000.toInt()), 2, 2, Bitmap.Config.ARGB_8888)
        assertTrue(byteArrayOf(0, -1, -1, 76).contentEquals(Bitmaps.luminance(b)))
    }

    @Test
    fun `읽기 인자 검사`() {
        val b = QrBitmaps.render(QrCode.encode("x"), 100)
        val e = assertThrows<IllegalArgumentException> { read(b, formats = emptySet()) }
        assertEquals("formats 가 비어 있다 — 아무것도 읽지 않는 설정은 호출부 실수다", e.message)
        b.recycle()
        val r = assertThrows<IllegalArgumentException> { read(b) }
        assertEquals("재활용된 비트맵이다", r.message)
    }

    private class FakeEngine(val detect: (ScanFrame) -> List<Detection>) : ScanEngine {
        val closed = AtomicBoolean(false)
        override fun detect(frame: ScanFrame): List<Detection> = detect.invoke(frame)
        override fun close() = closed.set(true)
    }

    @Test
    fun `엔진 실패는 ScannerException 이고 원인을 담으며 엔진은 닫힌다`() {
        val b = QrBitmaps.render(QrCode.encode("x"), 100)
        val boom = IllegalStateException("boom")
        val failing = FakeEngine { throw boom }
        val e = assertThrows<ScannerException> { read(b, engine = { _, _ -> failing }) }
        assertEquals("이미지에서 코드를 읽지 못했다", e.message)
        assertSame(boom, e.cause)
        assertTrue(failing.closed.get())

        val own = ScannerException("엔진 자체 오류")
        val ownEngine = FakeEngine { throw own }
        assertSame(own, assertThrows<ScannerException> { read(b, engine = { _, _ -> ownEngine }) })
        assertTrue(ownEngine.closed.get())

        val createBoom = IllegalStateException("no model")
        val c = assertThrows<ScannerException> { read(b, engine = { _, _ -> throw createBoom }) }
        assertEquals("인식 엔진을 만들지 못했다", c.message)
        assertSame(createBoom, c.cause)
        val createOwn = ScannerException("ML Kit 없음")
        assertSame(createOwn, assertThrows<ScannerException> { read(b, engine = { _, _ -> throw createOwn }) })
    }

    @Test
    fun `엔진에 넘기는 프레임과 형식`() {
        val b = Bitmap.createBitmap(IntArray(6) { 0xFF000000.toInt() }, 3, 2, Bitmap.Config.ARGB_8888)
        var seen: ScanFrame? = null
        var seenFormats: Set<CodeFormat>? = null
        var seenContext: Context? = null
        val other = Detection(result("ean", CodeFormat.EAN_13), CodeBounds(0f, 0f, 1f, 1f))
        val qr = Detection(result("qr"), CodeBounds(0f, 0f, 1f, 1f))
        val engine = FakeEngine { f -> seen = f; listOf(other, qr) }
        val found = read(b, setOf(CodeFormat.QR_CODE, CodeFormat.CODE_128)) { ctx, f -> seenContext = ctx; seenFormats = f; engine }
        // 요청하지 않은 형식은 버린다
        assertEquals(listOf("qr"), found.map { it.text })
        assertEquals(3, seen!!.width)
        assertEquals(2, seen!!.height)
        assertEquals(0, seen!!.rotationDegrees)
        assertEquals(3, seen!!.rowStride)
        assertTrue(seen!!.luminance.all { it == 0.toByte() })
        assertEquals(setOf(CodeFormat.QR_CODE, CodeFormat.CODE_128), seenFormats)
        assertSame(app, seenContext)
        assertTrue(engine.closed.get())
    }

    @Test
    fun `취소하면 엔진 대기를 끊고 엔진을 닫는다`() {
        val b = QrBitmaps.render(QrCode.encode("x"), 100)
        val entered = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val engine = FakeEngine {
            entered.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                interrupted.set(true)
                throw e
            }
            emptyList()
        }
        runBlocking {
            val job = async(Dispatchers.IO) { BitmapCodeReader.read(app, b, CodeFormat.QR_ONLY, { _, _ -> engine }, Dispatchers.IO) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            val e = runCatching { withTimeout(5_000) { job.await() } }.exceptionOrNull()
            assertTrue("$e", e is CancellationException)
        }
        assertTrue(interrupted.get())
        assertTrue(engine.closed.get())
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `권장 최대 변`() {
        assertEquals(2048, BitmapCodeReader.RECOMMENDED_MAX_DIMENSION)
    }
}
