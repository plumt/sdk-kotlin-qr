package net.plumt.qr

import net.plumt.qr.internal.PngWriter

/**
 * 만든 코드의 흑백 격자. 불변이다. `true` 가 어두운 칸(막대·모듈)이다.
 *
 * [QrCode.encode] 는 한 칸 = 한 모듈로, [Barcode.encode] 는 요청한 크기로 낸다. 그리는 쪽이 칸마다 사각형을 칠하거나
 * [toArgb] 로 픽셀 배열을 받아 비트맵에 넣는다 — 확대할 때는 보간 없이(최근접) 늘려야 경계가 흐려지지 않는다.
 *
 * ```
 * // Android
 * val m = QrCode.encode("https://example.com").scaled(8)
 * val bitmap = Bitmap.createBitmap(m.toArgb(), m.width, m.height, Bitmap.Config.ARGB_8888)
 * ```
 *
 * @param cells 행 우선(`cells[y * width + x]`) 배열. 복사해 보관한다
 * @throws IllegalArgumentException 너비·높이가 1 미만이거나 [cells] 크기가 `width * height` 와 다를 때
 */
public class CodeMatrix(public val width: Int, public val height: Int, cells: BooleanArray) {

    private val cells: BooleanArray

    init {
        require(width >= 1 && height >= 1) { "width($width)·height($height) 는 1 이상이어야 한다" }
        require(width.toLong() * height == cells.size.toLong()) { "cells 크기(${cells.size}) 가 width*height(${width.toLong() * height}) 와 다르다" }
        this.cells = cells.copyOf()
    }

    /** (x, y) 칸이 어두운지. 범위 밖이면 [IllegalArgumentException]. */
    public operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until width && y in 0 until height) { "($x, $y) 는 0..${width - 1}, 0..${height - 1} 범위여야 한다" }
        return cells[y * width + x]
    }

    /** [y] 행의 복사본. */
    public fun row(y: Int): BooleanArray {
        require(y in 0 until height) { "y($y) 는 0..${height - 1} 범위여야 한다" }
        return cells.copyOfRange(y * width, (y + 1) * width)
    }

    /** 행 우선 배열의 복사본(`[y * width + x]`). */
    public fun toBooleanArray(): BooleanArray = cells.copyOf()

    /** 칸마다 [factor]×[factor] 로 늘린 격자. 결과가 [MAX_CELLS] 칸을 넘으면 [IllegalArgumentException]. */
    public fun scaled(factor: Int): CodeMatrix {
        require(factor >= 1) { "factor($factor) 는 1 이상이어야 한다" }
        if (factor == 1) return this
        val w = width.toLong() * factor
        val h = height.toLong() * factor
        require(w * h <= MAX_CELLS) { "확대한 크기(${w}x$h) 가 너무 크다 — 최대 $MAX_CELLS 칸" }
        val out = BooleanArray((w * h).toInt())
        val ow = w.toInt()
        for (y in 0 until h.toInt()) {
            val src = (y / factor) * width
            for (x in 0 until ow) out[y * ow + x] = cells[src + x / factor]
        }
        return CodeMatrix(ow, h.toInt(), out)
    }

    /**
     * ARGB 픽셀 배열(행 우선). Android 의 `Bitmap.createBitmap(int[], w, h, ARGB_8888)`·`setPixels` 에 그대로 넣는다.
     * 기본 색은 불투명 검정·흰색이다.
     */
    @JvmOverloads
    public fun toArgb(foreground: Int = BLACK, background: Int = WHITE): IntArray =
        IntArray(cells.size) { if (cells[it]) foreground else background }

    /**
     * 1비트 흑백 PNG 파일 바이트. 플랫폼 그래픽 없이(`java.util.zip` 만으로) 만든다 — 서버에서 파일·응답으로 내보낼 때 쓴다.
     * 칸 하나가 픽셀 하나이므로 먼저 [scaled] 로 키운다.
     */
    public fun toPng(): ByteArray = PngWriter.write(this)

    override fun equals(other: Any?): Boolean =
        other is CodeMatrix && other.width == width && other.height == height && other.cells.contentEquals(cells)

    override fun hashCode(): Int = (width * 31 + height) * 31 + cells.contentHashCode()

    override fun toString(): String = "CodeMatrix(${width}x$height)"

    public companion object {
        /** 불투명 검정(0xFF000000). */
        public const val BLACK: Int = -0x1000000

        /** 불투명 흰색(0xFFFFFFFF). */
        public const val WHITE: Int = -0x1

        /**
         * 만들 수 있는 격자의 최대 칸 수(4096 x 4096). 생성·[scaled] 결과가 이를 넘으면 [IllegalArgumentException] 이다.
         * ARGB 로 바꾸면 칸마다 4 바이트라 이 상한에서 약 64MB 다 — Android 앱 힙에서 한 번의 실수로 OOM 이 나지 않게 막는다.
         */
        public const val MAX_CELLS: Int = 4096 * 4096
    }
}
