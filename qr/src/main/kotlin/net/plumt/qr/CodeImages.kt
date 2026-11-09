package net.plumt.qr

import net.plumt.qr.internal.Raster

/**
 * ARGB 픽셀 이미지(행 우선 `pixels[y * width + x]`). 불변이다.
 *
 * Android 는 `Bitmap.createBitmap(image.toIntArray(), w, h, ARGB_8888)` 로 비트맵이 되고, 비트맵의 `getPixels` 결과로 만들 수 있다.
 *
 * @param pixels 복사해 보관한다
 * @throws IllegalArgumentException 너비·높이가 1 미만, [pixels] 크기가 `width * height` 와 다름, [CodeMatrix.MAX_CELLS] 초과일 때
 */
public class ArgbImage(public val width: Int, public val height: Int, pixels: IntArray) {

    private val pixels: IntArray

    init {
        require(width >= 1 && height >= 1) { "width($width)·height($height) 는 1 이상이어야 한다" }
        val count = width.toLong() * height
        require(count <= CodeMatrix.MAX_CELLS) { "이미지(${width}x$height) 가 너무 크다 — 최대 ${CodeMatrix.MAX_CELLS} 픽셀" }
        require(count == pixels.size.toLong()) { "pixels 크기(${pixels.size}) 가 width*height($count) 와 다르다" }
        this.pixels = pixels.copyOf()
    }

    /** (x, y) 픽셀. 범위 밖이면 [IllegalArgumentException]. */
    public operator fun get(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) 는 0..${width - 1}, 0..${height - 1} 범위여야 한다" }
        return pixels[y * width + x]
    }

    /** 픽셀 배열의 복사본. */
    public fun toIntArray(): IntArray = pixels.copyOf()

    internal fun raw(): IntArray = pixels

    override fun equals(other: Any?): Boolean =
        other is ArgbImage && other.width == width && other.height == height && other.pixels.contentEquals(pixels)

    override fun hashCode(): Int = (width * 31 + height) * 31 + pixels.contentHashCode()

    override fun toString(): String = "ArgbImage(${width}x$height)"
}

/**
 * 그릴 색. ARGB 정수다.
 *
 * [checkContrast] 가 켜져 있으면(기본) 인식이 자주 실패할 조합을 만들 때 막는다 — [QrColors.readability] 가
 * [ColorReadability.LOW_CONTRAST](대비 3 미만) 또는 [ColorReadability.INVERTED](전경이 더 밝음)이면 [IllegalArgumentException].
 * [ColorReadability.MARGINAL] 은 막지 않는다(화면에 경고를 띄우는 것은 앱 몫이다). 알파가 있는 색은 흰 바탕에 얹은 색으로 판정한다.
 * 밝은 코드를 어두운 바탕에 일부러 그리는 경우에만 끈다.
 *
 * @throws IllegalArgumentException 위 조건 (생성 시)
 */
public data class CodeColors(
    val foreground: Int = CodeMatrix.BLACK,
    val background: Int = CodeMatrix.WHITE,
    val checkContrast: Boolean = true,
) {
    init {
        if (checkContrast) {
            val verdict = QrColors.readability(foreground, background)
            require(verdict != ColorReadability.INVERTED) {
                "전경(${hex(foreground)})이 배경(${hex(background)})보다 밝다 — 명암 반전을 읽지 않는 스캐너가 많다. 의도했다면 checkContrast = false"
            }
            require(verdict != ColorReadability.LOW_CONTRAST) {
                "대비 ${net.plumt.qr.internal.Formats.fixed(QrColors.contrastRatio(foreground, background), 3)} 가 " +
                    "${QrColors.MIN_CONTRAST} 미만이다(전경 ${hex(foreground)}, 배경 ${hex(background)}) — 인식이 자주 실패한다"
            }
        }
    }

    private fun hex(argb: Int): String = net.plumt.qr.internal.Formats.argbHex(argb)
}

/**
 * 격자를 정해진 픽셀 크기의 ARGB 이미지로 그린다. 플랫폼 그래픽 없이 돈다(서버·Android 공통).
 *
 * 칸은 정수 배로만 키우고(보간 없음, 경계가 흐려지지 않는다) 남는 픽셀은 가운데 정렬의 배경이다 — 요청한 크기를 정확히 채운다.
 */
public object CodeImages {

    /**
     * [matrix] 를 [widthPx] x [heightPx] 로 그린다. 배율은 `min(widthPx / 격자 너비, heightPx / 격자 높이)` 다.
     *
     * @throws IllegalArgumentException 요청 크기가 격자보다 작거나(배율 0), [CodeMatrix.MAX_CELLS] 를 넘을 때
     */
    @JvmStatic
    @JvmOverloads
    public fun render(matrix: CodeMatrix, widthPx: Int, heightPx: Int = widthPx, colors: CodeColors = CodeColors()): ArgbImage =
        Raster.render(matrix, widthPx, heightPx, colors).image

    /**
     * 로고 자리를 비운 QR([QrCode.encodeWithLogo])을 [sizePx] 정사각형으로 그리고 [logo] 를 로고 자리에 얹는다.
     *
     * 로고는 가로세로 비율을 지켜 로고 자리 안에 맞추고 가운데에 둔다. 줄일 때는 픽셀 평균으로, 키울 때는 가장 가까운 픽셀로 늘린다.
     * 반투명 로고는 배경 위에 섞는다(source-over).
     *
     * @throws IllegalArgumentException 요청 크기가 격자보다 작거나 [CodeMatrix.MAX_CELLS] 를 넘을 때
     */
    @JvmStatic
    @JvmOverloads
    public fun render(code: LogoQrCode, sizePx: Int, logo: ArgbImage, colors: CodeColors = CodeColors()): ArgbImage =
        Raster.renderWithLogo(code, sizePx, logo, colors)
}
