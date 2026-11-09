package net.plumt.qr.scanner

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.plumt.qr.Barcode
import net.plumt.qr.CodeFormat
import net.plumt.qr.CodeMatrix
import net.plumt.qr.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 실기기 검증: 카메라 앞에 실물 코드가 없으므로 합성 프레임(휘도)을 엔진에 직접 넣는다.
 * 세운(upright) 이미지의 알려진 위치에 코드를 그리고, 센서 회전만큼 거꾸로 돌린 원 프레임 + rotationDegrees 로 넣어
 * 돌려받은 박스(세운 좌표)가 그린 자리와 맞는지 본다. 결과는 logcat 태그 QrDev.
 */
@RunWith(AndroidJUnit4::class)
public class QrDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private class Placed(val luma: ByteArray, val w: Int, val h: Int, val l: Int, val t: Int, val r: Int, val b: Int)

    /** 세운 이미지 W x H 의 (ox,oy) 에 matrix 를 scale 배로 그린다(여백 포함). */
    private fun upright(m: CodeMatrix, scale: Int, W: Int, H: Int, ox: Int, oy: Int): Placed {
        val img = ByteArray(W * H) { 200.toByte() }
        val cells = m.toBooleanArray()
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val v: Byte = if (cells[y * m.width + x]) 20 else 245.toByte()
            for (dy in 0 until scale) for (dx in 0 until scale) img[(oy + y * scale + dy) * W + ox + x * scale + dx] = v
        }
        return Placed(img, W, H, ox, oy, ox + m.width * scale, oy + m.height * scale)
    }

    /** 세운 이미지를 rotation 만큼 거꾸로 돌린 원 프레임. 원 프레임을 시계 방향 rotation 만큼 돌리면 세운 이미지가 된다. */
    private fun raw(p: Placed, rotation: Int): Triple<ByteArray, Int, Int> {
        val rw = if (rotation % 180 == 0) p.w else p.h
        val rh = if (rotation % 180 == 0) p.h else p.w
        val out = ByteArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val (u, v) = when (rotation) {
                0 -> x to y
                90 -> (rh - 1 - y) to x
                180 -> (rw - 1 - x) to (rh - 1 - y)
                else -> y to (rw - 1 - x)
            }
            out[y * rw + x] = p.luma[v * p.w + u]
        }
        return Triple(out, rw, rh)
    }

    private fun near(label: String, d: Detection, p: Placed, tolFrac: Float) {
        val tol = (p.r - p.l) * tolFrac
        val bx = d.bounds
        Log.i(TAG, "$label bounds=(${bx.left.toInt()},${bx.top.toInt()},${bx.right.toInt()},${bx.bottom.toInt()}) expected=(${p.l},${p.t},${p.r},${p.b})")
        assertTrue("$label 가운데 x: ${bx.centerX} vs ${(p.l + p.r) / 2f}", abs(bx.centerX - (p.l + p.r) / 2f) <= tol)
        assertTrue("$label 가운데 y: ${(bx.top + bx.bottom) / 2} vs ${(p.t + p.b) / 2f}", abs((bx.top + bx.bottom) / 2f - (p.t + p.b) / 2f) <= tol)
    }

    private fun runEngine(name: String, factory: ScanEngine.Factory, formats: Set<CodeFormat>, m: CodeMatrix, text: String, scale: Int, tolFrac: Float) {
        // 1280x720 센서 프레임을 세로로 세운 720x1280. 코드는 왼쪽 위 사분면(비대칭)
        val p = upright(m, scale, 720, 1280, 60, 140)
        factory.create(ctx, formats).use { engine ->
            for (rot in listOf(0, 90, 180, 270)) {
                val (bytes, rw, rh) = raw(p, rot)
                val t0 = System.nanoTime()
                val found = engine.detect(ScanFrame(bytes, rw, rh, rotationDegrees = rot))
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, "$name rot=$rot found=${found.size} ${found.firstOrNull()?.result} ${ms}ms")
                assertEquals("$name rot=$rot 개수", 1, found.size)
                assertEquals(text, found[0].result.text)
                near("$name rot=$rot", found[0], p, tolFrac)
            }
        }
    }

    @Test
    public fun mlKit_realRecognition_coordinatesAcrossRotations() {
        val text = "https://example.com/qr-device"
        runEngine("mlkit-qr", ScanEngines.mlKit(), CodeFormat.QR_ONLY, QrCode.encode(text), text, 10, 0.05f)
    }

    @Test
    public fun zxing_qr_and_rotatedBarcode() {
        val text = "https://example.com/zx"
        runEngine("zxing-qr", ScanEngines.zxing(), CodeFormat.QR_ONLY, QrCode.encode(text), text, 10, 0.15f)
        val code = "ROT-128-0042"
        val bar = Barcode.encode(code, CodeFormat.CODE_128)
        // 1차원: 세로로 길게 늘린다(높이 방향 반복) — 행렬 높이가 1 이면 scale 로 키운다
        val tall = CodeMatrix(bar.width, 60, BooleanArray(bar.width * 60) { i -> bar.toBooleanArray()[(i % bar.width) + (if (bar.height > 1) (i / bar.width % bar.height) * bar.width else 0)] })
        runEngine("zxing-c128", ScanEngines.zxing(tryHarder = true), setOf(CodeFormat.CODE_128), tall, code, 3, 0.25f)
        runEngine("mlkit-c128", ScanEngines.mlKit(), setOf(CodeFormat.CODE_128), tall, code, 3, 0.25f)
    }

    @Test
    public fun hostileVCard_thenNormal() {
        // 공격형 vCard: 거의 최대 용량, 긴 줄·많은 필드·제어 문자·잘린 이스케이프
        val sb = StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        repeat(40) { sb.append("TEL;TYPE=CELL:+82-10-0000-${"%04d".format(it)}\r\n") }
        sb.append("N:").append("가".repeat(120)).append(";\\;\\\r\n")
        sb.append("NOTE:").append("\u0000‮￿".repeat(30)).append("\r\n")
        sb.append("URL:javascript:alert(1)\r\nEMAIL:").append("a".repeat(200)).append("@x\r\n")
        val hostile = sb.toString()
        val m = QrCode.encode(hostile, net.plumt.qr.QrCodeOptions(errorCorrection = net.plumt.qr.ErrorCorrection.L))
        Log.i(TAG, "hostile vcard length=${hostile.length} modules=${m.width}")
        val scale = maxOf(1, 700 / m.width)
        val p = upright(m, scale, 720, 1280, 5, 200)
        for ((name, f) in listOf("mlkit" to ScanEngines.mlKit(), "zxing" to ScanEngines.zxing(tryHarder = true))) {
            f.create(ctx, CodeFormat.QR_ONLY).use { e ->
                val r = runCatching { e.detect(ScanFrame(p.luma, p.w, p.h)) }
                Log.i(TAG, "$name hostile -> ${r.map { l -> l.map { it.result.payload::class.simpleName + "/" + it.result.text.length } }}")
                assertTrue("$name 공격 vCard 에서 예외: ${r.exceptionOrNull()}", r.isSuccess)
                val ok = "https://example.com/after"
                val n = upright(QrCode.encode(ok), 10, 720, 1280, 60, 140)
                val after = e.detect(ScanFrame(n.luma, n.w, n.h))
                Log.i(TAG, "$name 다음 정상 -> ${after.map { it.result.text == ok }}")
                assertEquals(ok, after.single().result.text)
            }
        }
    }

    private companion object { const val TAG = "QrDev" }
}
