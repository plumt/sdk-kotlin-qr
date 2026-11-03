package net.plumt.qr.scanner.internal

import net.plumt.qr.ScanPoint
import net.plumt.qr.scanner.CodeBounds
import net.plumt.qr.scanner.PreviewScaleMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 버퍼(센서 방향) 좌표 → 바로 세운 이미지 좌표. CameraX 의 `rotationDegrees` 는 버퍼를 바로 세우려고 시계 방향으로 돌릴 각도다.
 *
 * | 회전 | (x, y) → | 세운 크기 |
 * |---|---|---|
 * | 0 | (x, y) | W x H |
 * | 90 | (H - y, x) | H x W |
 * | 180 | (W - x, H - y) | W x H |
 * | 270 | (y, W - x) | H x W |
 */
internal object FrameRotation {

    fun rotatePoint(x: Float, y: Float, bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): ScanPoint {
        val w = bufferWidth.toFloat()
        val h = bufferHeight.toFloat()
        return when (rotationDegrees) {
            0 -> ScanPoint(x, y)
            90 -> ScanPoint(h - y, x)
            180 -> ScanPoint(w - x, h - y)
            270 -> ScanPoint(y, w - x)
            else -> throw IllegalArgumentException("rotationDegrees($rotationDegrees) 는 0·90·180·270 중 하나여야 한다")
        }
    }

    /** 사각형의 네 모서리를 돌려 감싸는 사각형. 회전이 90 의 배수라 결과도 정확한 사각형이다. */
    fun rotateBounds(b: CodeBounds, bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int): CodeBounds =
        enclose(
            listOf(
                rotatePoint(b.left, b.top, bufferWidth, bufferHeight, rotationDegrees),
                rotatePoint(b.right, b.bottom, bufferWidth, bufferHeight, rotationDegrees),
            ),
        )!!

    fun enclose(points: List<ScanPoint>): CodeBounds? {
        if (points.isEmpty()) return null
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var bt = Float.NEGATIVE_INFINITY
        for (p in points) {
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); bt = max(bt, p.y)
        }
        return CodeBounds(l, t, r, bt)
    }
}

/**
 * 바로 세운 분석 이미지 좌표 → 미리보기 뷰 좌표. `PreviewView` 의 FILL_CENTER·FIT_CENTER 와 같은 계산이다.
 *
 * 1. 전면 카메라면 미리보기가 좌우 반전돼 보이므로 x 를 이미지 너비에서 뺀다
 * 2. 비율을 지키는 배율 — FILL 은 두 축 비율 중 큰 쪽(넘치는 쪽이 잘림), FIT 은 작은 쪽(남는 쪽이 빈 띠)
 * 3. 가운데 정렬 — 남거나 넘치는 폭·높이의 절반을 더한다(FILL 이면 음수)
 *
 * 미리보기 스트림과 분석 스트림의 비율이 같다는 전제다(두 스트림 모두 4:3 으로 잡는다).
 */
internal class PreviewTransform(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Float,
    val viewHeight: Float,
    val scaleMode: PreviewScaleMode,
    val mirrored: Boolean,
) {
    init {
        require(imageWidth >= 1 && imageHeight >= 1) { "이미지 크기(${imageWidth}x$imageHeight)는 1x1 이상이어야 한다" }
        require(viewWidth > 0f && viewHeight > 0f && viewWidth.isFinite() && viewHeight.isFinite()) {
            "뷰 크기(${viewWidth}x$viewHeight)는 0 보다 큰 유한한 값이어야 한다"
        }
    }

    val scale: Float = run {
        val sx = viewWidth / imageWidth
        val sy = viewHeight / imageHeight
        if (scaleMode == PreviewScaleMode.FILL) max(sx, sy) else min(sx, sy)
    }
    val offsetX: Float = (viewWidth - imageWidth * scale) / 2f
    val offsetY: Float = (viewHeight - imageHeight * scale) / 2f

    fun mapX(x: Float): Float = (if (mirrored) imageWidth - x else x) * scale + offsetX
    fun mapY(y: Float): Float = y * scale + offsetY

    fun mapPoint(p: ScanPoint): ScanPoint = ScanPoint(mapX(p.x), mapY(p.y))

    fun mapBounds(b: CodeBounds): CodeBounds {
        val x1 = mapX(b.left)
        val x2 = mapX(b.right)
        return CodeBounds(min(x1, x2), mapY(b.top), max(x1, x2), mapY(b.bottom))
    }

    /** 뷰 좌표 → 이미지 좌표(역변환). */
    fun unmapX(x: Float): Float = ((x - offsetX) / scale).let { if (mirrored) imageWidth - it else it }
    fun unmapY(y: Float): Float = (y - offsetY) / scale

    /** 박스 가운데가 뷰 안(경계 포함)에 보이는가. FILL 에서 잘려 나간 코드를 거른다. */
    fun isVisible(viewBounds: CodeBounds): Boolean =
        viewBounds.centerX in 0f..viewWidth && viewBounds.centerY in 0f..viewHeight

    /** 이미지가 실제로 그려지는 뷰 영역(FIT 의 빈 띠를 뺀 곳, FILL 이면 뷰 전체). */
    fun visibleImageArea(): CodeBounds = CodeBounds(
        max(0f, offsetX),
        max(0f, offsetY),
        min(viewWidth, offsetX + imageWidth * scale),
        min(viewHeight, offsetY + imageHeight * scale),
    )
}

internal object BoxMath {

    fun iou(a: CodeBounds, b: CodeBounds): Float {
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun centerDistance(a: CodeBounds, b: CodeBounds): Float = distance(a.centerX, a.centerY, b.centerX, b.centerY)

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun lerp(from: CodeBounds, to: CodeBounds, t: Float): CodeBounds = CodeBounds(
        from.left + (to.left - from.left) * t,
        from.top + (to.top - from.top) * t,
        from.right + (to.right - from.right) * t,
        from.bottom + (to.bottom - from.bottom) * t,
    )
}
