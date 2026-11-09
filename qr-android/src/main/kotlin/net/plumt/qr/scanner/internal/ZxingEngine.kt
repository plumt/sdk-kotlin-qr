package net.plumt.qr.scanner.internal

import net.plumt.qr.CodeFormat
import net.plumt.qr.CodeReader
import net.plumt.qr.QrCodeException
import net.plumt.qr.ReadOptions
import net.plumt.qr.ScanPoint
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.CodeBounds
import net.plumt.qr.scanner.Detection
import net.plumt.qr.scanner.ScannerException
import net.plumt.qr.scanner.ScanEngine
import net.plumt.qr.scanner.ScanFrame
import kotlin.math.max

/**
 * `qr` 모듈의 [CodeReader] 로 읽는 엔진. 버퍼 좌표로 나온 점을 바로 세운 좌표로 돌리고, 점에서 박스를 어림한다.
 *
 * ZXing 의 1차원 리더는 버퍼의 행만 훑는다. 센서 버퍼는 화면과 90도 돌아 있는 일이 흔해(세로 화면이면 `rotationDegrees=90`)
 * 바코드를 화면 가로로 들면 버퍼 안에서는 막대가 행과 나란히 누워 읽히지 않는다. 그래서 1차원 형식을 찾을 때는
 * 버퍼를 시계 방향으로 90도 돌린 사본을 1차원 형식만으로 한 번 더 읽는다(0°·90° 두 번 — 1차원 리더는 거꾸로 놓인 것도 읽으므로
 * 180°·270° 는 따로 보지 않는다). 같은 형식·원문은 하나만 남긴다.
 */
internal class ZxingEngine(
    private val options: ReadOptions,
    private val reader: (ByteArray, Int, Int, Int, ReadOptions) -> List<ScanResult> = { l, w, h, s, o -> CodeReader.readAll(l, w, h, s, o) },
) : ScanEngine {

    override fun detect(frame: ScanFrame): List<Detection> {
        val found = reading { reader(frame.luminance, frame.width, frame.height, frame.rowStride, options) }
        val rotated = linearOptions?.let { linear ->
            val turned = rotateClockwise(frame.luminance, frame.width, frame.height, frame.rowStride)
            // 돌린 버퍼는 (높이 x 너비) 이고 행 간격이 없다. 점은 원래 버퍼 좌표로 되돌린다
            reading { reader(turned, frame.height, frame.width, frame.height, linear) }
                .map { r -> r.copy(points = r.points.map { unrotatePoint(it, frame.height) }) }
        }.orEmpty()
        val merged = (found + rotated).distinctBy { it.format to it.text }
        return merged.mapNotNull { r ->
            val upright = r.points.map { FrameRotation.rotatePoint(it.x, it.y, frame.width, frame.height, frame.rotationDegrees) }
            val bounds = PointBounds.estimate(upright, r.format) ?: return@mapNotNull null
            Detection(r.copy(points = upright), bounds)
        }
    }

    /** 돌린 버퍼로 한 번 더 읽을 1차원 형식만의 설정. 1차원 형식이 없으면 null(한 번만 읽는다). */
    private val linearOptions: ReadOptions? =
        options.formats.filterTo(LinkedHashSet()) { it.kind != CodeFormat.Kind.MATRIX }
            .takeIf { it.isNotEmpty() }
            ?.let { options.copy(formats = it) }

    private inline fun reading(block: () -> List<ScanResult>): List<ScanResult> = try {
        block()
    } catch (e: QrCodeException) {
        throw ScannerException("ZXing 엔진이 프레임을 읽지 못했다", e)
    } catch (e: IllegalArgumentException) {
        throw ScannerException("ZXing 엔진에 넘긴 프레임 정보가 맞지 않는다", e)
    }

    override fun close() = Unit

    internal companion object {
        /** [width] x [height] 버퍼(행 간격 [rowStride])를 시계 방향으로 90도 돌린 (height x width) 사본. 원래 (x, y) → (height - 1 - y, x). */
        fun rotateClockwise(luminance: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val src = y * rowStride
                val col = height - 1 - y
                for (x in 0 until width) out[x * height + col] = luminance[src + x]
            }
            return out
        }

        /** [rotateClockwise] 한 버퍼의 점을 원래 버퍼 좌표로. 돌린 (rx, ry) → 원래 (ry, height - 1 - rx). */
        fun unrotatePoint(p: ScanPoint, originalHeight: Int): ScanPoint = ScanPoint(p.y, originalHeight - 1 - p.x)
    }

    /** 값으로 비교되는 팩토리 — 설정 객체가 같으면 같은 엔진 설정으로 본다(Compose 재구성마다 스캐너를 다시 만들지 않게). */
    data class Factory(val tryHarder: Boolean, val alsoInverted: Boolean) : ScanEngine.Factory {
        override fun create(context: android.content.Context, formats: Set<CodeFormat>): ScanEngine {
            require(formats.isNotEmpty()) { "formats 가 비어 있다" }
            return ZxingEngine(ReadOptions(formats = formats, tryHarder = tryHarder, alsoInverted = alsoInverted))
        }
    }
}

/**
 * ZXing 이 주는 위치 점에서 코드 박스를 어림한다. 엔진이 네 모서리를 주지 않기 때문이다.
 *
 * - QR(점 3개 이상): 세 찾기 패턴의 중심이다. 네 번째 모서리를 평행사변형으로 채우고, 찾기 패턴 중심이 모서리에서 안쪽으로
 *   들어간 만큼(변 길이의 [QR_PAD] 배)을 사방으로 넓힌다
 * - 1차원(점 2개): 읽은 줄의 양 끝이다. 길이의 [LINEAR_HEIGHT] 배를 높이로 잡는다(세로로 놓이면 너비로)
 * - 그 밖: 점을 감싸는 사각형을 변 길이의 [OTHER_PAD] 배만큼 넓힌다
 * 점이 없으면 null(위치를 모르는 코드는 그리지 않는다).
 */
internal object PointBounds {
    const val QR_PAD = 0.2f
    const val LINEAR_HEIGHT = 0.3f
    const val OTHER_PAD = 0.1f

    fun estimate(points: List<ScanPoint>, format: CodeFormat): CodeBounds? {
        if (points.isEmpty()) return null
        if (format == CodeFormat.QR_CODE && points.size >= 3) {
            val (bl, tl, tr) = points
            val fourth = ScanPoint(bl.x + tr.x - tl.x, bl.y + tr.y - tl.y)
            val box = FrameRotation.enclose(listOf(bl, tl, tr, fourth))!!
            return pad(box, max(box.width, box.height) * QR_PAD)
        }
        val box = FrameRotation.enclose(points)!!
        if (points.size == 2 && format.kind != CodeFormat.Kind.MATRIX) {
            val horizontal = box.width >= box.height
            val half = max(box.width, box.height) * LINEAR_HEIGHT / 2f
            return if (horizontal) {
                CodeBounds(box.left, box.centerY - half, box.right, box.centerY + half)
            } else {
                CodeBounds(box.centerX - half, box.top, box.centerX + half, box.bottom)
            }
        }
        return pad(box, max(box.width, box.height) * OTHER_PAD)
    }

    private fun pad(b: CodeBounds, by: Float) = CodeBounds(b.left - by, b.top - by, b.right + by, b.bottom + by)
}
