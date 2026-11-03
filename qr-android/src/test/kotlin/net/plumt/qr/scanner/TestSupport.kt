package net.plumt.qr.scanner

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import net.plumt.qr.CodeFormat
import net.plumt.qr.CodeMatrix
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import java.nio.ByteBuffer

internal fun result(text: String, format: CodeFormat = CodeFormat.QR_CODE): ScanResult =
    ScanResult(text, format, QrPayload.parse(text, format), emptyList())

internal fun box(l: Float, t: Float, r: Float, b: Float) = CodeBounds(l, t, r, b)

internal fun det(text: String, l: Float, t: Float, r: Float, b: Float, format: CodeFormat = CodeFormat.QR_CODE) =
    Detection(result(text, format), CodeBounds(l, t, r, b))

/** 가운데 (cx, cy), 한 변 size 인 정사각형 검출. */
internal fun detAt(text: String, cx: Float, cy: Float, size: Float = 40f) =
    det(text, cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

internal const val EPS = 1e-3f

/** 흑백 격자를 [scale] 배로 키워 (ox, oy) 에 얹은 W x H 밝기 버퍼(흰 바탕). */
internal fun luminanceOf(m: CodeMatrix, width: Int, height: Int, ox: Int, oy: Int, scale: Int, rowStride: Int = width): ByteArray {
    val out = ByteArray(rowStride * height) { 0xFF.toByte() }
    for (y in 0 until m.height) for (x in 0 until m.width) {
        if (!m[x, y]) continue
        for (dy in 0 until scale) for (dx in 0 until scale) {
            val px = ox + x * scale + dx
            val py = oy + y * scale + dy
            if (px in 0 until width && py in 0 until height) out[py * rowStride + px] = 0
        }
    }
    return out
}

/** 버퍼를 시계 반대 방향으로 [degrees] 돌려, "센서가 이렇게 찍었다" 는 버퍼를 만든다(바로 세우려면 degrees 만큼 시계 방향). */
internal fun unrotate(upright: ByteArray, w: Int, h: Int, degrees: Int): Triple<ByteArray, Int, Int> {
    // upright(x, y) 가 버퍼의 어디에 있는지: 버퍼 → upright 가 rotatePoint 이므로 그 역
    val (bw, bh) = if (degrees % 180 == 0) w to h else h to w
    val out = ByteArray(bw * bh)
    for (by in 0 until bh) for (bx in 0 until bw) {
        val (ux, uy) = when (degrees) {
            0 -> bx to by
            90 -> (bh - 1 - by) to bx
            180 -> (bw - 1 - bx) to (bh - 1 - by)
            270 -> by to (bw - 1 - bx)
            else -> error("bad")
        }
        out[by * bw + bx] = upright[uy * w + ux]
    }
    return Triple(out, bw, bh)
}

internal class FakePlane(private val bytes: ByteArray, private val stride: Int) : ImageProxy.PlaneProxy {
    override fun getRowStride(): Int = stride
    override fun getPixelStride(): Int = 1
    override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
}

internal class FakeImageInfo(private val rotation: Int, private val ts: Long) : ImageInfo {
    override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()
    override fun getTimestamp(): Long = ts
    override fun getRotationDegrees(): Int = rotation
    override fun populateExifData(exifBuilder: ExifData.Builder) = Unit
}

internal class FakeImageProxy(
    private val w: Int,
    private val h: Int,
    private val luma: ByteArray = ByteArray(w * h),
    private val stride: Int = w,
    private val rotation: Int = 0,
    private val ts: Long = 0,
) : ImageProxy {
    var closeCount = 0
        private set
    var planesRead = 0
        private set

    override fun close() {
        closeCount++
    }

    override fun getCropRect(): Rect = Rect(0, 0, w, h)
    override fun setCropRect(rect: Rect?) = Unit
    override fun getFormat(): Int = 35
    override fun getHeight(): Int = h
    override fun getWidth(): Int = w
    override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
        planesRead++
        return arrayOf(FakePlane(luma, stride))
    }

    override fun getImageInfo(): ImageInfo = FakeImageInfo(rotation, ts)
    override fun getImage(): Image? = null
}

internal class FakeEngine(var next: (ScanFrame) -> List<Detection> = { emptyList() }) : ScanEngine {
    var calls = 0
    var closed = 0
    override fun detect(frame: ScanFrame): List<Detection> {
        calls++
        return next(frame)
    }

    override fun close() {
        closed++
    }
}

internal class FakeCamera(override val hasFlash: Boolean = true) : CameraHandle {
    val torch = ArrayList<Boolean>()
    val zoom = ArrayList<Float>()
    val focus = ArrayList<Pair<Float, Float>>()
    override fun enableTorch(enabled: Boolean) {
        torch += enabled
    }

    override fun setZoomRatio(ratio: Float) {
        zoom += ratio
    }

    override fun focus(viewX: Float, viewY: Float) {
        focus += viewX to viewY
    }
}

internal inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("기대한 예외 ${T::class.simpleName} 대신 ${t::class.simpleName}: ${t.message}", t)
    }
    throw AssertionError("예외 ${T::class.simpleName} 가 나지 않았다")
}
