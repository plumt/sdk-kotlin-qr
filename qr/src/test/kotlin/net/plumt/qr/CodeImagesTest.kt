package net.plumt.qr

import net.plumt.qr.internal.Raster
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 대비 판정·색 검사·ARGB 그리기·로고 합성. */
class CodeImagesTest {

    private fun iae(expected: String, block: () -> Unit) {
        val e = assertThrows(IllegalArgumentException::class.java) { block() }
        assertEquals(expected, e.message)
    }

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private val black = CodeMatrix.BLACK
    private val white = CodeMatrix.WHITE

    // ---- 대비 ----

    @Test
    fun `상대 휘도와 대비는 WCAG 값이다`() {
        assertEquals(0.0, QrColors.relativeLuminance(black), 0.0)
        assertEquals(1.0, QrColors.relativeLuminance(white), 1e-12)
        assertEquals(21.0, QrColors.contrastRatio(black, white), 1e-9)
        assertEquals(21.0, QrColors.contrastRatio(white, black), 1e-9)
        assertEquals(1.0, QrColors.contrastRatio(0xFF336699.toInt(), 0xFF336699.toInt()), 0.0)
        // 독립 계산값(sRGB → 선형, 0.2126/0.7152/0.0722)
        assertEquals(4.478089453577214, QrColors.contrastRatio(gray(0x77), white), 1e-9)
        assertEquals(3.0334698257384747, QrColors.contrastRatio(gray(0x94), white), 1e-9)
        assertEquals(8.631141281945503, QrColors.contrastRatio(0xFF0D47A1.toInt(), white), 1e-9)
        assertEquals(8.055475873862257, QrColors.contrastRatio(0xFF0D47A1.toInt(), 0xFFFFF9C4.toInt()), 1e-9)
    }

    @Test
    fun `알파가 있는 색은 흰 바탕에 얹은 색으로 본다`() {
        // 완전 투명은 색과 무관하게 흰색
        assertEquals(1.0, QrColors.relativeLuminance(0x00000000), 1e-12)
        assertEquals(1.0, QrColors.relativeLuminance(0x00FF0000), 1e-12)
        // 절반 투명 검정 = (0x7F) 회색
        assertEquals(QrColors.relativeLuminance(gray(0x7F)), QrColors.relativeLuminance(0x80000000.toInt()), 1e-12)
        assertEquals(21.0, QrColors.contrastRatio(black, 0x00000000), 1e-9)
    }

    @Test
    fun `반전 판정`() {
        assertTrue(QrColors.isInverted(white, black))
        assertFalse(QrColors.isInverted(black, white))
        assertFalse(QrColors.isInverted(gray(0x80), gray(0x80)))
        assertTrue(QrColors.isInverted(0x00000000, black))
    }

    @Test
    fun `읽힘 판정 경계`() {
        assertEquals(ColorReadability.READABLE, QrColors.readability(black, white))
        assertEquals(ColorReadability.READABLE, QrColors.readability(gray(0x7F), white)) // 4.004
        assertEquals(ColorReadability.MARGINAL, QrColors.readability(gray(0x80), white)) // 3.949
        assertEquals(ColorReadability.MARGINAL, QrColors.readability(gray(0x94), white)) // 3.033
        assertEquals(ColorReadability.LOW_CONTRAST, QrColors.readability(gray(0x95), white)) // 2.995
        assertEquals(ColorReadability.LOW_CONTRAST, QrColors.readability(gray(0x80), gray(0x80)))
        assertEquals(ColorReadability.INVERTED, QrColors.readability(white, black))
        // 반전이 대비보다 먼저다(대비가 낮아도 반전이면 반전)
        assertEquals(ColorReadability.INVERTED, QrColors.readability(gray(0x81), gray(0x80)))
        assertEquals(3.0, QrColors.MIN_CONTRAST, 0.0)
        assertEquals(4.0, QrColors.RECOMMENDED_CONTRAST, 0.0)
    }

    @Test
    fun `무작위 색 — 대비는 1 이상 21 이하이고 대칭이다`() {
        val random = Random(7)
        repeat(2000) {
            val a = random.nextInt()
            val b = random.nextInt()
            val r = QrColors.contrastRatio(a, b)
            assertTrue("$r", r in 1.0..21.0 + 1e-9)
            assertEquals(r, QrColors.contrastRatio(b, a), 0.0)
            val v = QrColors.readability(a, b)
            if (v == ColorReadability.INVERTED) assertTrue(QrColors.isInverted(a, b))
        }
    }

    // ---- 색 설정 ----

    @Test
    fun `읽히지 않을 색 조합은 만들 때 막는다`() {
        CodeColors()
        CodeColors(gray(0x94), white) // 경계(MARGINAL)는 막지 않는다
        CodeColors(black, 0x00000000) // 투명 배경은 흰 바탕으로 본다
        iae("전경(#FFFFFFFF)이 배경(#FF000000)보다 밝다 — 명암 반전을 읽지 않는 스캐너가 많다. 의도했다면 checkContrast = false") {
            CodeColors(white, black)
        }
        iae("대비 2.995 가 3.0 미만이다(전경 #FF959595, 배경 #FFFFFFFF) — 인식이 자주 실패한다") { CodeColors(gray(0x95), white) }
        iae("대비 1.000 가 3.0 미만이다(전경 #FF808080, 배경 #FF808080) — 인식이 자주 실패한다") { CodeColors(gray(0x80), gray(0x80)) }
        // 끄면 무엇이든 받는다
        CodeColors(white, black, checkContrast = false)
        CodeColors(gray(0x80), gray(0x80), checkContrast = false)
    }

    // ---- ArgbImage ----

    @Test
    fun `ArgbImage 검사와 불변성`() {
        iae("width(0)·height(1) 는 1 이상이어야 한다") { ArgbImage(0, 1, IntArray(0)) }
        iae("width(1)·height(0) 는 1 이상이어야 한다") { ArgbImage(1, 0, IntArray(0)) }
        iae("pixels 크기(3) 가 width*height(4) 와 다르다") { ArgbImage(2, 2, IntArray(3)) }
        iae("이미지(4097x4096) 가 너무 크다 — 최대 16777216 픽셀") { ArgbImage(4097, 4096, IntArray(0)) }
        val source = intArrayOf(1, 2, 3, 4, 5, 6)
        val image = ArgbImage(3, 2, source)
        source[0] = 99
        assertEquals(1, image[0, 0])
        assertEquals(6, image[2, 1])
        val copy = image.toIntArray()
        copy[1] = 99
        assertEquals(2, image[1, 0])
        assertNotSame(image.toIntArray(), image.toIntArray())
        iae("(3, 0) 는 0..2, 0..1 범위여야 한다") { image[3, 0] }
        iae("(0, -1) 는 0..2, 0..1 범위여야 한다") { image[0, -1] }
        assertEquals(ArgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6)), image)
        assertEquals(ArgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 6)).hashCode(), image.hashCode())
        assertNotEquals(ArgbImage(2, 3, intArrayOf(1, 2, 3, 4, 5, 6)), image)
        assertNotEquals(ArgbImage(3, 2, intArrayOf(1, 2, 3, 4, 5, 7)), image)
        assertNotEquals(image, Any())
        assertEquals("ArgbImage(3x2)", image.toString())
    }

    // ---- 그리기 ----

    @Test
    fun `요청 크기를 정확히 채우고 칸은 정수 배로 가운데에 둔다`() {
        val m = CodeMatrix(3, 2, booleanArrayOf(true, false, true, false, true, false))
        val image = CodeImages.render(m, 10, 7, CodeColors(0xFF112233.toInt(), 0xFFEEDDCC.toInt()))
        assertEquals(10, image.width)
        assertEquals(7, image.height)
        // 배율 min(10/3, 7/2) = 3, 그린 영역 9x6, 왼쪽 (10-9)/2=0, 위 (7-6)/2=0
        for (y in 0 until 7) for (x in 0 until 10) {
            val mx = x / 3
            val my = y / 3
            val dark = mx < 3 && my < 2 && m[mx, my]
            assertEquals("($x, $y)", if (dark) 0xFF112233.toInt() else 0xFFEEDDCC.toInt(), image[x, y])
        }
        val centered = CodeImages.render(CodeMatrix(1, 1, booleanArrayOf(true)), 5)
        // 배율 5 — 전부 전경
        assertTrue(centered.toIntArray().all { it == black })
        val padded = CodeImages.render(CodeMatrix(2, 2, booleanArrayOf(true, true, true, true)), 5)
        // 배율 2, 그린 영역 4x4, 왼쪽·위 0 — 마지막 행·열은 배경
        assertEquals(black, padded[0, 0])
        assertEquals(black, padded[3, 3])
        assertEquals(white, padded[4, 0])
        assertEquals(white, padded[0, 4])
    }

    @Test
    fun `QR 을 그려 다시 읽는다`() {
        val m = QrCode.encode("https://example.com/그리기")
        for (size in listOf(m.width, m.width + 1, 512, 768)) {
            val image = CodeImages.render(m, size, colors = CodeColors(0xFF0D47A1.toInt(), 0xFFFFF9C4.toInt()))
            assertEquals(size, image.width)
            assertEquals(size, image.height)
            // 한 칸 1픽셀은 이진화 블록보다 작아 읽기 검증에서 뺀다(크기만 본다)
            if (size < m.width * 2) continue
            val read = CodeReader.read(image.toIntArray(), size, size, ReadOptions(formats = CodeFormat.QR_ONLY))
            assertEquals("$size", "https://example.com/그리기", read?.text)
        }
    }

    @Test
    fun `그리기 크기 검사`() {
        val m = QrCode.encode("x")
        iae("크기(20x20) 가 격자(29x29) 보다 작다 — 한 칸이 1픽셀 이상이어야 한다") { CodeImages.render(m, 20) }
        iae("크기(100x20) 가 격자(29x29) 보다 작다 — 한 칸이 1픽셀 이상이어야 한다") { CodeImages.render(m, 100, 20) }
        iae("widthPx(0)·heightPx(10) 는 1 이상이어야 한다") { CodeImages.render(m, 0, 10) }
        iae("widthPx(10)·heightPx(-1) 는 1 이상이어야 한다") { CodeImages.render(m, 10, -1) }
        iae("크기(4097x4096) 가 너무 크다 — 최대 16777216 픽셀") { CodeImages.render(m, 4097, 4096) }
        val logo = ArgbImage(1, 1, intArrayOf(black))
        val code = QrCode.encodeWithLogo("https://example.com")
        val w = code.matrix.width
        iae("크기(10x10) 가 격자(${w}x$w) 보다 작다 — 한 칸이 1픽셀 이상이어야 한다") { CodeImages.render(code, 10, logo) }
    }

    // ---- 로고 합성 ----

    @Test
    fun `로고는 비율을 지켜 로고 자리 가운데에 놓인다`() {
        val code = QrCode.encodeWithLogo("https://example.com")
        val scale = 4
        val size = code.matrix.width * scale
        val red = 0xFFFF0000.toInt()
        // 가로로 긴 로고(4:1)
        val wide = CodeImages.render(code, size, ArgbImage(8, 2, IntArray(16) { red }))
        val a = code.logoArea
        val boxL = a.left * scale
        val boxT = a.top * scale
        val box = a.width * scale
        val dh = box / 4
        val top = boxT + (box - dh) / 2
        for (y in boxT until boxT + box) for (x in boxL until boxL + box) {
            val inLogo = y in top until top + dh
            assertEquals("($x, $y)", if (inLogo) red else white, wide[x, y])
        }
        // 세로로 긴 로고(1:4)
        val tall = CodeImages.render(code, size, ArgbImage(2, 8, IntArray(16) { red }))
        val dw = box / 4
        val left = boxL + (box - dw) / 2
        for (y in boxT until boxT + box) for (x in boxL until boxL + box) {
            assertEquals("($x, $y)", if (x in left until left + dw) red else white, tall[x, y])
        }
        // 로고 자리 밖은 로고 없는 그림과 같다
        val plain = CodeImages.render(code.matrix, size)
        for (y in 0 until size) for (x in 0 until size) {
            if (x in boxL until boxL + box && y in boxT until boxT + box) continue
            assertEquals(plain[x, y], wide[x, y])
        }
    }

    @Test
    fun `반투명 로고는 배경 위에 섞고 투명 로고는 배경을 남긴다`() {
        val code = QrCode.encodeWithLogo("https://example.com")
        val size = code.matrix.width * 2
        val cx = code.logoArea.left * 2 + 1
        val cy = code.logoArea.top * 2 + 1
        val clear = CodeImages.render(code, size, ArgbImage(1, 1, intArrayOf(0x00FF0000)))
        assertEquals(white, clear[cx, cy])
        val half = CodeImages.render(code, size, ArgbImage(1, 1, intArrayOf(0x80000000.toInt())))
        // 흰 바탕 위 절반 검정: 0x7F
        assertEquals(gray(0x7F), half[cx, cy])
        // 투명 배경 위에 반투명 로고 — 결과 알파는 로고 알파
        val onClear = CodeImages.render(code, size, ArgbImage(1, 1, intArrayOf(0x80FF0000.toInt())), CodeColors(black, 0x00FFFFFF))
        assertEquals(0x80FF0000.toInt(), onClear[cx, cy])
    }

    @Test
    fun `줄일 때는 픽셀 평균, 키울 때는 가장 가까운 픽셀`() {
        // 2x2 → 1x1 평균
        val src = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(gray(0x80), Raster.average(src, 2, 0, 2, 0, 2))
        // 투명 픽셀의 색은 번지지 않는다
        val withClear = intArrayOf(0x00FF0000, 0xFF0000FF.toInt())
        assertEquals(0x800000FF.toInt(), Raster.average(withClear, 2, 0, 2, 0, 1))
        assertEquals(0, Raster.average(intArrayOf(0x00123456, 0x00654321), 2, 0, 2, 0, 1))
        // 합성
        assertEquals(0xFF112233.toInt(), Raster.over(0xFF112233.toInt(), white))
        assertEquals(white, Raster.over(0x00112233, white))
        assertEquals(gray(0x7F), Raster.over(0x80000000.toInt(), white))
        assertEquals(0x80FF0000.toInt(), Raster.over(0x80FF0000.toInt(), 0x00000000))
        // 반투명 위 반투명: 알파 = a + b(1 - a)
        val both = Raster.over(0x80FF0000.toInt(), 0x800000FF.toInt())
        assertEquals(0xC0, both ushr 24)

        // 큰 로고를 줄여 얹어도 로고 자리 밖으로 나가지 않는다
        val code = QrCode.encodeWithLogo("https://example.com")
        val big = ArgbImage(300, 300, IntArray(300 * 300) { if ((it / 300 + it % 300) % 2 == 0) black else white })
        val size = code.matrix.width * 3
        val image = CodeImages.render(code, size, big)
        val a = code.logoArea
        for (y in 0 until size) for (x in 0 until size) {
            val inside = x / 3 in a.left until a.right && y / 3 in a.top until a.bottom
            if (!inside) assertEquals(CodeImages.render(code.matrix, size)[x, y], image[x, y])
        }
        // 1x1 로고를 키우면 로고 자리 전체가 그 색이다
        val one = CodeImages.render(code, size, ArgbImage(1, 1, intArrayOf(0xFF00FF00.toInt())))
        for (y in a.top * 3 until a.bottom * 3) for (x in a.left * 3 until a.right * 3) assertEquals(0xFF00FF00.toInt(), one[x, y])
    }

    @Test
    fun `로고 그림에도 색 설정이 쓰인다`() {
        val code = QrCode.encodeWithLogo("https://example.com")
        val colors = CodeColors(0xFF1B5E20.toInt(), 0xFFFFF3E0.toInt())
        val image = CodeImages.render(code, code.matrix.width * 2, ArgbImage(1, 1, intArrayOf(0)), colors)
        assertEquals(0xFFFFF3E0.toInt(), image[0, 0])
        val pixels = image.toIntArray().toSet()
        assertEquals(setOf(0xFF1B5E20.toInt(), 0xFFFFF3E0.toInt()), pixels)
        assertArrayEquals(image.toIntArray(), CodeImages.render(code.matrix, code.matrix.width * 2, colors = colors).toIntArray())
    }
}
