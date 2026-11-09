package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 만든 코드를 픽셀 배열로 그려 다시 읽는다. */
class EncodeReadRoundTripTest {

    private fun luminance(m: CodeMatrix): ByteArray = ByteArray(m.width * m.height) { if (m.toBooleanArray()[it]) 0 else -1 }

    private fun luminanceFast(m: CodeMatrix): ByteArray {
        val cells = m.toBooleanArray()
        return ByteArray(cells.size) { if (cells[it]) 0 else -1 }
    }

    private fun read(m: CodeMatrix, options: ReadOptions = ReadOptions()): ScanResult? =
        CodeReader.read(luminanceFast(m), m.width, m.height, options = options)

    /** 격자를 시계 방향으로 90도 돌린 밝기 배열(막대가 행과 나란히 눕는다). 크기는 (height x width). */
    private fun rotatedLuminance(m: CodeMatrix): ByteArray {
        val out = ByteArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) out[x * m.height + (m.height - 1 - y)] = if (m[x, y]) 0 else -1
        return out
    }

    @Test
    fun `tryHarder 는 90도 돌아간 1차원 바코드도 읽고 점은 받은 이미지 좌표다`() {
        val m = Barcode.encode("4006381333931", CodeFormat.EAN_13, BarcodeOptions(300, 100))
        val turned = rotatedLuminance(m)
        val w = m.height
        val h = m.width
        // 행만 훑으면 막대를 가로지르지 못한다
        assertNull(CodeReader.read(turned, w, h, options = ReadOptions(formats = CodeFormat.PRODUCT)))
        val r = CodeReader.read(turned, w, h, options = ReadOptions(formats = CodeFormat.PRODUCT, tryHarder = true))
        assertEquals("4006381333931", r?.text)
        // 돌려 읽은 점도 받은 이미지(w x h) 좌표다 — 세로로 선 바코드의 위아래 끝
        r!!.points.forEach { assertTrue("$it", it.x in 0f..w.toFloat() && it.y in 0f..h.toFloat()) }
        assertTrue(r.points.maxOf { it.y } - r.points.minOf { it.y } > h / 2f)
        val all = CodeReader.readAll(turned, w, h, options = ReadOptions(formats = CodeFormat.PRODUCT, tryHarder = true))
        assertEquals(listOf("4006381333931"), all.map { it.text })
        // ARGB 경로도 같다
        val argb = IntArray(turned.size) { if (turned[it] == 0.toByte()) CodeMatrix.BLACK else CodeMatrix.WHITE }
        assertEquals("4006381333931", CodeReader.read(argb, w, h, ReadOptions(formats = CodeFormat.PRODUCT, tryHarder = true))?.text)
        // 행 간격이 있는 버퍼도 돌린다
        val stride = w + 7
        val padded = ByteArray(stride * h) { -1 }
        for (y in 0 until h) System.arraycopy(turned, y * w, padded, y * stride, w)
        assertEquals("4006381333931", CodeReader.read(padded, w, h, stride, ReadOptions(formats = CodeFormat.PRODUCT, tryHarder = true))?.text)
    }

    @Test
    fun `QR 을 만들고 읽는다 — 한글·이모지 포함`() {
        listOf("https://example.com", "안녕하세요 QR 😀", "A".repeat(500), "1234567890").forEach { text ->
            val result = read(QrCode.encode(text).scaled(4))
            assertNotNull(text, result)
            assertEquals(text, result!!.text)
            assertEquals(CodeFormat.QR_CODE, result.format)
            assertTrue(result.points.size >= 3)
        }
    }

    @Test
    fun `내용 형식을 담은 QR 은 읽으면 같은 내용으로 풀린다`() {
        val payloads = listOf(
            QrPayload.Wifi("집 와이파이;5G", "p:w,\"d\\"),
            QrPayload.Contact("홍길동", "예시", phones = listOf(ContactPhone("010-1", PhoneType.MOBILE)), note = "줄\n바꿈"),
            QrPayload.Otp("alice", "JBSWY3DPEHPK3PXP", issuer = "Example"),
            QrPayload.Geo(37.5665, 126.978, query = "서울시청"),
        )
        payloads.forEach { payload ->
            val result = read(QrCode.encode(payload, QrCodeOptions(errorCorrection = ErrorCorrection.H)).scaled(3))
            assertEquals(payload, result?.payload)
        }
    }

    @Test
    fun `오류 정정 단계와 최소 버전`() {
        val low = QrCode.encode("hello", QrCodeOptions(errorCorrection = ErrorCorrection.L, margin = 0))
        assertEquals(21, low.width)
        val v5 = QrCode.encode("hello", QrCodeOptions(margin = 0, minVersion = 5))
        assertEquals(17 + 4 * 5, v5.width)
        assertEquals("hello", read(v5.scaled(4).let { padded(it) })?.text)
        val withMargin = QrCode.encode("hello", QrCodeOptions(errorCorrection = ErrorCorrection.L))
        assertEquals(21 + 8, withMargin.width)
        assertEquals("hello", read(QrCode.encode("hello", QrCodeOptions(errorCorrection = ErrorCorrection.H)).scaled(4))?.text)
    }

    @Test
    fun `라틴-1 문자셋 QR`() {
        val m = QrCode.encode("café", QrCodeOptions(charset = Charsets.ISO_8859_1))
        assertEquals("café", read(m.scaled(4))?.text)
    }

    @Test
    fun `1차원 바코드 형식마다 만들고 읽는다`() {
        val samples = mapOf(
            CodeFormat.EAN_13 to "4006381333931",
            CodeFormat.EAN_8 to "96385074",
            CodeFormat.UPC_A to "036000291452",
            CodeFormat.UPC_E to "04252614",
            CodeFormat.CODE_128 to "Hello, World! 123 abc",
            CodeFormat.CODE_39 to "CODE-39 $/+%",
            CodeFormat.CODE_93 to "Code 93 lower",
            CodeFormat.CODABAR to "A40156B",
            CodeFormat.ITF to "14006381333938",
        )
        samples.forEach { (format, text) ->
            val m = Barcode.encode(text, format, BarcodeOptions(width = 600, height = 120))
            assertEquals(600, m.width)
            assertEquals(120, m.height)
            val result = read(m, ReadOptions(formats = setOf(format)))
            assertNotNull("$format", result)
            assertEquals("$format", format, result!!.format)
            val expected = if (format == CodeFormat.CODABAR) "40156" else text
            assertEquals("$format", expected, result.text)
            assertEquals(2, result.points.size)
        }
    }

    @Test
    fun `체크 숫자 없이 준 상품 번호는 계산해 붙인다`() {
        assertEquals("4006381333931", read(Barcode.encode("400638133393", CodeFormat.EAN_13, BarcodeOptions(400, 80)))?.text)
        assertEquals("036000291452", read(Barcode.encode("03600029145", CodeFormat.UPC_A, BarcodeOptions(400, 80)))?.text)
    }

    @Test
    fun `상품 바코드를 읽으면 상품 번호 내용이다`() {
        val result = read(Barcode.encode("4006381333931", CodeFormat.EAN_13, BarcodeOptions(400, 80)))
        assertEquals(QrPayload.Product("4006381333931"), result?.payload)
    }

    @Test
    fun `2차원 형식마다 만들고 읽는다 — 한글 포함`() {
        listOf(CodeFormat.QR_CODE, CodeFormat.DATA_MATRIX, CodeFormat.AZTEC, CodeFormat.PDF_417).forEach { format ->
            listOf("Hello 2D 123", "한글 내용 테스트").forEach { text ->
                val natural = Barcode.encode(text, format)
                val m = Barcode.encode(text, format, BarcodeOptions(width = natural.width * 4, height = natural.height * 4))
                val result = read(m, ReadOptions(formats = setOf(format), tryHarder = true))
                assertNotNull("$format $text", result)
                assertEquals("$format", text, result!!.text)
                assertEquals(format, result.format)
            }
        }
    }

    @Test
    fun `요청 크기는 정수 배로 키우고 가운데 정렬한다`() {
        val natural = Barcode.encode("x", CodeFormat.QR_CODE)
        assertEquals(29, natural.width)
        val big = Barcode.encode("x", CodeFormat.QR_CODE, BarcodeOptions(100, 100))
        assertEquals(100, big.width)
        assertEquals(100, big.height)
        // 3배(87칸) + 좌우 여백 6·7칸. 왼쪽 위 찾기 패턴은 (6 + 4*3) 에서 시작한다
        assertTrue(big[18, 18])
        assertTrue(!big[17, 17])
        val linear = Barcode.encode("4006381333931", CodeFormat.EAN_13)
        assertEquals(1, linear.height)
        assertEquals(95 + 20, linear.width)
    }

    @Test
    fun `ARGB 배열 — 투명 바탕도 흰색으로 본다`() {
        val m = QrCode.encode("투명 배경").scaled(4)
        val transparent = m.toArgb(foreground = CodeMatrix.BLACK, background = 0x00000000)
        assertEquals("투명 배경", CodeReader.read(transparent, m.width, m.height)?.text)
        val colored = m.toArgb(foreground = 0xFF1A237E.toInt(), background = 0xFFFFF59D.toInt())
        assertEquals("투명 배경", CodeReader.read(colored, m.width, m.height)?.text)
    }

    @Test
    fun `행 끝 패딩이 있는 밝기 배열`() {
        val m = QrCode.encode("stride").scaled(3)
        val stride = m.width + 13
        val cells = m.toBooleanArray()
        // 마지막 행은 패딩 없이 끝난다(카메라 버퍼의 흔한 모양)
        val data = ByteArray(stride * (m.height - 1) + m.width) { 0x55 }
        for (y in 0 until m.height) for (x in 0 until m.width) data[y * stride + x] = if (cells[y * m.width + x]) 0 else -1
        assertEquals("stride", CodeReader.read(data, m.width, m.height, rowStride = stride)?.text)
    }

    @Test
    fun `명암이 뒤집힌 코드는 alsoInverted 로 읽는다`() {
        val m = QrCode.encode("inverted").scaled(4)
        val inverted = luminanceFast(m).map { (it.toInt() xor -1).toByte() }.toByteArray()
        assertNull(CodeReader.read(inverted, m.width, m.height))
        assertEquals("inverted", CodeReader.read(inverted, m.width, m.height, options = ReadOptions(alsoInverted = true))?.text)
    }

    @Test
    fun `코드가 없으면 null 과 빈 목록`() {
        val blank = ByteArray(200 * 200) { -1 }
        assertNull(CodeReader.read(blank, 200, 200))
        assertEquals(emptyList<ScanResult>(), CodeReader.readAll(blank, 200, 200))
        val noise = kotlin.random.Random(3).nextBytes(200 * 200)
        assertNull(CodeReader.read(noise, 200, 200, options = ReadOptions(tryHarder = true)))
    }

    @Test
    fun `찾을 형식에 없는 코드는 읽지 않는다`() {
        val m = Barcode.encode("4006381333931", CodeFormat.EAN_13, BarcodeOptions(400, 80))
        assertNull(read(m, ReadOptions(formats = CodeFormat.QR_ONLY)))
    }

    @Test
    fun `나란히 놓인 QR 두 개를 모두 읽는다`() {
        val left = QrCode.encode("왼쪽 코드").scaled(4)
        val right = QrCode.encode("https://example.com/right").scaled(4)
        val gap = 40
        val width = left.width + gap + right.width
        val height = maxOf(left.height, right.height)
        val data = ByteArray(width * height) { -1 }
        fun blit(m: CodeMatrix, offsetX: Int) {
            val cells = m.toBooleanArray()
            for (y in 0 until m.height) for (x in 0 until m.width) if (cells[y * m.width + x]) data[y * width + offsetX + x] = 0
        }
        blit(left, 0)
        blit(right, left.width + gap)
        val results = CodeReader.readAll(data, width, height, options = ReadOptions(formats = CodeFormat.QR_ONLY))
        assertEquals(listOf("왼쪽 코드", "https://example.com/right"), results.map { it.text }.sortedBy { it != "왼쪽 코드" })
        results.forEach { r -> assertTrue(r.points.size >= 3) }
        val leftResult = results.first { it.text == "왼쪽 코드" }
        val rightResult = results.first { it.text != "왼쪽 코드" }
        assertTrue(leftResult.points.all { it.x < left.width })
        assertTrue(rightResult.points.all { it.x > left.width + gap })
        assertEquals(QrPayload.Url("https://example.com/right"), rightResult.payload)
    }

    @Test
    fun `QR 과 바코드가 섞인 이미지`() {
        val qr = QrCode.encode("qr-part").scaled(4)
        val bar = Barcode.encode("4006381333931", CodeFormat.EAN_13, BarcodeOptions(qr.width * 2, 100))
        val width = bar.width
        val height = qr.height + 40 + bar.height
        val data = ByteArray(width * height) { -1 }
        val qc = qr.toBooleanArray()
        for (y in 0 until qr.height) for (x in 0 until qr.width) if (qc[y * qr.width + x]) data[y * width + x] = 0
        val bc = bar.toBooleanArray()
        for (y in 0 until bar.height) for (x in 0 until bar.width) if (bc[y * bar.width + x]) data[(qr.height + 40 + y) * width + x] = 0
        val texts = CodeReader.readAll(data, width, height).map { it.text }.toSet()
        assertEquals(setOf("qr-part", "4006381333931"), texts)
    }

    @Test
    fun `luminance 헬퍼 일관성`() {
        val m = QrCode.encode("x")
        assertTrue(luminance(m).contentEquals(luminanceFast(m)))
    }

    /** 여백 없는 격자에 흰 테두리를 두른다. */
    private fun padded(m: CodeMatrix, pad: Int = 16): CodeMatrix {
        val w = m.width + 2 * pad
        val h = m.height + 2 * pad
        val cells = BooleanArray(w * h)
        for (y in 0 until m.height) for (x in 0 until m.width) cells[(y + pad) * w + x + pad] = m[x, y]
        return CodeMatrix(w, h, cells)
    }
}
