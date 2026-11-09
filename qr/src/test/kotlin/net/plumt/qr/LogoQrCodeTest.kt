package net.plumt.qr

import com.google.zxing.WriterException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 오류 정정 단계 고르기·용량 초과 예외·로고 자리 비우기. */
class LogoQrCodeTest {

    private fun tooLong(block: () -> Unit): QrContentTooLongException =
        assertThrows(QrContentTooLongException::class.java) { block() }

    private fun iae(expected: String, block: () -> Unit) {
        val e = assertThrows(IllegalArgumentException::class.java) { block() }
        assertEquals(expected, e.message)
    }

    private fun iaeContains(fragment: String, block: () -> Unit): IllegalArgumentException {
        val e = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue("'${e.message}' 에 '$fragment' 가 없다", e.message!!.contains(fragment))
        return e
    }

    /** 칸 하나를 [scale] 픽셀로 그린 ARGB 를 다시 읽는다. */
    private fun readBack(image: ArgbImage): String? =
        CodeReader.read(image.toIntArray(), image.width, image.height, ReadOptions(formats = CodeFormat.QR_ONLY, tryHarder = true))?.text

    private fun solidLogo(color: Int, w: Int = 40, h: Int = 40) = ArgbImage(w, h, IntArray(w * h) { color })

    // ---- 단계별 로고 상한 ----

    @Test
    fun `단계별 로고 상한`() {
        assertEquals(0f, ErrorCorrection.L.maxLogoRatio)
        assertEquals(0.15f, ErrorCorrection.M.maxLogoRatio)
        assertEquals(0.2f, ErrorCorrection.Q.maxLogoRatio)
        assertEquals(0.3f, ErrorCorrection.H.maxLogoRatio)
        assertEquals(ErrorCorrection.H.maxLogoRatio, QrLogoOptions.MAX_SIZE_RATIO)
    }

    // ---- 용량 초과 예외 ----

    @Test
    fun `용량을 넘으면 QrContentTooLongException 이고 원인과 단계를 담는다`() {
        val e = tooLong { QrCode.encode("a".repeat(1300), QrCodeOptions(errorCorrection = ErrorCorrection.H)) }
        assertEquals(ErrorCorrection.H, e.errorCorrection)
        assertFalse(e.withLogo)
        assertTrue(e.cause is WriterException)
        assertTrue(e.message!!, e.message!!.contains("내용이 최대 용량을 넘는다(오류 정정 H)"))
        // IllegalArgumentException 의 하위 타입이다 — 기존 호출부의 catch 가 그대로 받는다
        assertTrue(e is IllegalArgumentException)

        val l = tooLong { QrCode.encode("a".repeat(2953), QrCodeOptions(errorCorrection = ErrorCorrection.L)) }
        assertEquals(ErrorCorrection.L, l.errorCorrection)
        // 바코드 경로의 QR 도 같은 타입이다
        val b = tooLong { Barcode.encode("a".repeat(3000), CodeFormat.QR_CODE) }
        assertEquals(ErrorCorrection.M, b.errorCorrection)
        // 페이로드 경로
        tooLong { QrCode.encode(QrPayload.Text("가".repeat(1000)), QrCodeOptions(errorCorrection = ErrorCorrection.H)) }
    }

    @Test
    fun `엔진의 그 밖의 오류는 용량 예외가 아니다`() {
        // 빈 내용·문자셋은 일반 IllegalArgumentException
        val empty = assertThrows(IllegalArgumentException::class.java) { QrCode.encode("") }
        assertFalse(empty is QrContentTooLongException)
        val cs = assertThrows(IllegalArgumentException::class.java) { QrCode.encode("한", QrCodeOptions(charset = Charsets.ISO_8859_1)) }
        assertFalse(cs is QrContentTooLongException)
    }

    // ---- 단계 고르기 ----

    @Test
    fun `높은 단계부터 들어가는 첫 단계를 고른다`() {
        assertEquals(ErrorCorrection.H, QrCode.pickErrorCorrection("https://example.com"))
        // 바이트 모드(UTF-8 ECI 1바이트 포함) 버전 40 용량: H 1273, Q 1663, M 2331, L 2953
        assertEquals(ErrorCorrection.H, QrCode.pickErrorCorrection("a".repeat(1272)))
        assertEquals(ErrorCorrection.Q, QrCode.pickErrorCorrection("a".repeat(1273)))
        assertEquals(ErrorCorrection.Q, QrCode.pickErrorCorrection("a".repeat(1662)))
        assertEquals(ErrorCorrection.M, QrCode.pickErrorCorrection("a".repeat(1663)))
        assertEquals(ErrorCorrection.M, QrCode.pickErrorCorrection("a".repeat(2330)))
        assertEquals(ErrorCorrection.L, QrCode.pickErrorCorrection("a".repeat(2331)))
        assertEquals(ErrorCorrection.L, QrCode.pickErrorCorrection("a".repeat(2952)))
        val e = tooLong { QrCode.pickErrorCorrection("a".repeat(2953)) }
        assertEquals(ErrorCorrection.L, e.errorCorrection)
        assertFalse(e.withLogo)
        // 고른 단계로 실제로 만들어진다
        for (n in listOf(1, 500, 1272, 1273, 2000, 2952)) {
            val text = "a".repeat(n)
            QrCode.encode(text, QrCodeOptions(errorCorrection = QrCode.pickErrorCorrection(text)))
        }
    }

    @Test
    fun `한글 한계 — H 약 420자, L 약 980자`() {
        assertEquals(ErrorCorrection.H, QrCode.pickErrorCorrection("가".repeat(424)))
        assertEquals(ErrorCorrection.Q, QrCode.pickErrorCorrection("가".repeat(425)))
        assertEquals(ErrorCorrection.L, QrCode.pickErrorCorrection("가".repeat(984)))
        tooLong { QrCode.pickErrorCorrection("가".repeat(985)) }
    }

    @Test
    fun `단계 고르기는 options 의 문자셋과 최소 버전을 쓰고 단계는 보지 않는다`() {
        iae("내용을 ISO-8859-1 로 나타낼 수 없다") { QrCode.pickErrorCorrection("한", options = QrCodeOptions(charset = Charsets.ISO_8859_1)) }
        iae("내용이 비어 있다") { QrCode.pickErrorCorrection("") }
        assertEquals(ErrorCorrection.H, QrCode.pickErrorCorrection("x", options = QrCodeOptions(errorCorrection = ErrorCorrection.L)))
        // 문자셋에 따라 바이트 수가 달라진다 — 'é' 는 라틴-1 1바이트, UTF-8 2바이트
        assertEquals(ErrorCorrection.H, QrCode.pickErrorCorrection("é".repeat(1000), options = QrCodeOptions(charset = Charsets.ISO_8859_1)))
        assertEquals(ErrorCorrection.M, QrCode.pickErrorCorrection("é".repeat(1000)))
        assertEquals(ErrorCorrection.H, QrCode.pickErrorCorrection("x", options = QrCodeOptions(minVersion = 40)))
    }

    @Test
    fun `로고가 있으면 로고 자리를 허용하는 단계만 고른다`() {
        assertEquals(ErrorCorrection.H, QrCode.pickErrorCorrection("https://example.com/path", QrLogoOptions()))
        // 기본 로고(0.2 + 패딩 1)는 비우는 자리가 0.2 를 넘어 Q 이하가 받지 못한다 — H 에 안 들어가면 길이 문제다
        val e = tooLong { QrCode.pickErrorCorrection("a".repeat(1300), QrLogoOptions()) }
        assertTrue(e.withLogo)
        assertEquals(ErrorCorrection.H, e.errorCorrection)
        // 패딩 없는 0.2 는 Q 가 받는다
        assertEquals(ErrorCorrection.Q, QrCode.pickErrorCorrection("a".repeat(1300), QrLogoOptions(sizeRatio = 0.2f, paddingModules = 0)))
        // 0.14 는 M 도 받는다. L 은 로고를 받지 않는다
        assertEquals(ErrorCorrection.M, QrCode.pickErrorCorrection("a".repeat(2000), QrLogoOptions(sizeRatio = 0.14f, paddingModules = 0)))
        val l = tooLong { QrCode.pickErrorCorrection("a".repeat(2500), QrLogoOptions(sizeRatio = 0.1f, paddingModules = 0)) }
        assertEquals(ErrorCorrection.M, l.errorCorrection)
        assertTrue(l.withLogo)
    }

    @Test
    fun `들어가는 단계가 로고를 받지 못하면 로고 오류다`() {
        // 0.3 + 패딩 1 은 H 의 상한 0.3 을 넘는다
        val e = iaeContains("오류 정정 H 의 상한 30.0% 를 넘는다") { QrCode.pickErrorCorrection("https://example.com", QrLogoOptions(0.3f, 1)) }
        assertFalse(e is QrContentTooLongException)
        // 작은 버전은 찾기 패턴에 닿는다
        iaeContains("찾기 패턴·형식 정보에 닿는다") { QrCode.pickErrorCorrection("a", QrLogoOptions(0.2f, 1)) }
    }

    // ---- 로고 자리 비우기 ----

    @Test
    fun `로고 자리는 정가운데 정사각형이고 비운 칸은 밝다`() {
        val code = QrCode.encodeWithLogo("https://example.com/로고")
        val m = code.matrix
        assertEquals(ErrorCorrection.H, code.errorCorrection)
        val a = code.logoArea
        val c = code.clearedArea
        assertEquals(m.width, a.left * 2 + a.width)
        assertEquals(m.height, a.top * 2 + a.height)
        assertEquals(m.width, c.left * 2 + c.width)
        assertEquals(a.left - 1, c.left)
        assertEquals(a.width + 2, c.width)
        for (y in c.top until c.bottom) for (x in c.left until c.right) assertFalse("($x, $y)", m[x, y])
        // 바깥은 원래 QR 과 같다
        val plain = QrCode.encode("https://example.com/로고", QrCodeOptions(errorCorrection = ErrorCorrection.H))
        assertEquals(plain.width, m.width)
        for (y in 0 until m.height) for (x in 0 until m.width) if (!c.contains(x, y)) assertEquals("($x, $y)", plain[x, y], m[x, y])
        // 로고 한 변은 sizeRatio 이하이고 기호 한 변(여백 제외)과 홀짝이 같다
        val symbol = m.width - 8
        assertTrue(a.width <= symbol * 0.2)
        assertEquals(symbol % 2, a.width % 2)
    }

    @Test
    fun `여백과 패딩 설정이 좌표에 반영된다`() {
        val code = QrCode.encodeWithLogo("https://example.com", QrLogoOptions(0.2f, 0), QrCodeOptions(errorCorrection = ErrorCorrection.H, margin = 0))
        assertEquals(code.logoArea, code.clearedArea)
        assertEquals(code.matrix.width, code.logoArea.left * 2 + code.logoArea.width)
        val wide = QrCode.encodeWithLogo("https://example.com", QrLogoOptions(0.15f, 3), QrCodeOptions(errorCorrection = ErrorCorrection.H, margin = 2, minVersion = 10))
        assertEquals(wide.logoArea.left - 3, wide.clearedArea.left)
        assertEquals(wide.logoArea.width + 6, wide.clearedArea.width)
    }

    @Test
    fun `로고 자리 검사`() {
        iae("오류 정정 L 은 로고를 허용하지 않는다 — M 이상을 쓴다") {
            QrCode.encodeWithLogo("https://example.com", options = QrCodeOptions(errorCorrection = ErrorCorrection.L))
        }
        iaeContains("오류 정정 M 의 상한 15.0% 를 넘는다") {
            QrCode.encodeWithLogo("https://example.com", options = QrCodeOptions(errorCorrection = ErrorCorrection.M))
        }
        // 버전 1(21칸): 로고 3 + 패딩 2 = 5칸, 시작 8 — 형식 정보 줄에 닿는다
        iae("로고 자리(5칸)가 기호(21칸)의 찾기 패턴·형식 정보에 닿는다 — sizeRatio·paddingModules 를 줄이거나 minVersion 을 올린다") {
            QrCode.encodeWithLogo("a")
        }
        // 최소 버전을 올리면 들어간다
        QrCode.encodeWithLogo("a", options = QrCodeOptions(errorCorrection = ErrorCorrection.H, minVersion = 2))
        iae("내용이 비어 있다") { QrCode.encodeWithLogo("") }
        val e = tooLong { QrCode.encodeWithLogo("a".repeat(1300)) }
        assertTrue(e.withLogo)
        assertTrue(e.message!!, e.message!!.contains("로고 자리 비움"))
    }

    @Test
    fun `로고 설정 검사`() {
        iae("sizeRatio(0.0) 는 0 초과 0.3 이하여야 한다") { QrLogoOptions(0f) }
        iae("sizeRatio(-0.1) 는 0 초과 0.3 이하여야 한다") { QrLogoOptions(-0.1f) }
        iae("sizeRatio(0.31) 는 0 초과 0.3 이하여야 한다") { QrLogoOptions(0.31f) }
        iae("sizeRatio(NaN) 는 0 초과 0.3 이하여야 한다") { QrLogoOptions(Float.NaN) }
        iae("sizeRatio(Infinity) 는 0 초과 0.3 이하여야 한다") { QrLogoOptions(Float.POSITIVE_INFINITY) }
        iae("paddingModules(-1) 는 0..4 범위여야 한다") { QrLogoOptions(paddingModules = -1) }
        iae("paddingModules(5) 는 0..4 범위여야 한다") { QrLogoOptions(paddingModules = 5) }
        QrLogoOptions(QrLogoOptions.MAX_SIZE_RATIO, QrLogoOptions.MAX_PADDING)
        QrLogoOptions(Float.MIN_VALUE, 0)
        // 아주 작은 비율도 최소 1칸이다
        val tiny = QrCode.encodeWithLogo("https://example.com", QrLogoOptions(0.01f, 0))
        assertEquals(1, tiny.logoArea.width)
    }

    @Test
    fun `칸 영역 검사`() {
        iae("left(-1)·top(0) 는 0 이상이어야 한다") { CellArea(-1, 0, 1, 1) }
        iae("left(0)·top(-1) 는 0 이상이어야 한다") { CellArea(0, -1, 1, 1) }
        iae("width(0)·height(1) 는 1 이상이어야 한다") { CellArea(0, 0, 0, 1) }
        iae("width(1)·height(0) 는 1 이상이어야 한다") { CellArea(0, 0, 1, 0) }
        val a = CellArea(2, 3, 4, 5)
        assertEquals(6, a.right)
        assertEquals(8, a.bottom)
        assertTrue(a.contains(2, 3))
        assertTrue(a.contains(5, 7))
        assertFalse(a.contains(6, 7))
        assertFalse(a.contains(5, 8))
        assertFalse(a.contains(1, 3))
        assertFalse(a.contains(2, 2))
    }

    @Test
    fun `LogoQrCode 의 값 비교`() {
        val a = QrCode.encodeWithLogo("https://example.com")
        val b = QrCode.encodeWithLogo("https://example.com")
        val c = QrCode.encodeWithLogo("https://example.org")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertNotEquals(a, QrCode.encodeWithLogo("https://example.com", QrLogoOptions(0.1f)))
        assertNotEquals(a, QrCode.encodeWithLogo("https://example.com", QrLogoOptions(0.2f, 0)))
        assertNotEquals(a, QrCode.encodeWithLogo("https://example.com", QrLogoOptions(0.14f, 0), QrCodeOptions(errorCorrection = ErrorCorrection.M)))
        assertNotEquals(a, Any())
        assertTrue(a.toString().startsWith("LogoQrCode(matrix=CodeMatrix("))
        assertTrue(a.toString().contains("errorCorrection=H"))
    }

    // ---- 그려서 다시 읽기 ----

    @Test
    fun `불투명한 로고를 얹어도 읽힌다`() {
        val contents = listOf(
            "https://example.com",
            "https://example.com/" + "p".repeat(80),
            "WIFI:T:WPA;S:Home;P:pw123456;;",
            "가나다라마바사".repeat(20),
        )
        for (text in contents) {
            for (logo in listOf(QrLogoOptions(), QrLogoOptions(0.25f, 1), QrLogoOptions(0.1f, 0))) {
                val code = QrCode.encodeWithLogo(text, logo, QrCodeOptions(errorCorrection = ErrorCorrection.H, minVersion = 5))
                // 검은 로고 — 가장 나쁜 경우(모듈처럼 보인다)
                val image = CodeImages.render(code, code.matrix.width * 4, solidLogo(CodeMatrix.BLACK))
                assertEquals("$text / $logo", text, readBack(image))
            }
        }
    }

    @Test
    fun `무작위 내용 — 고른 단계와 로고로 만든 QR 이 모두 읽힌다`() {
        val random = Random(20261103)
        // 글자 단위(코드 포인트)로 고른다 — 이모지는 UTF-16 두 글자다
        val alphabet = listOf("a", "b", "X", "Z", "0", "9", " ", ":", "/", ".", "?", "=", "&", "-", "_", "가", "나", "한", "글", "😀")
        repeat(40) { i ->
            val length = 1 + random.nextInt(300)
            val text = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.size)]) } }.ifBlank { "x" }
            val logo = QrLogoOptions(0.05f + random.nextFloat() * 0.15f, random.nextInt(2))
            val level = try {
                QrCode.pickErrorCorrection(text, logo, QrCodeOptions(minVersion = 4))
            } catch (e: IllegalArgumentException) {
                throw AssertionError("#$i '$text' $logo", e)
            }
            val code = QrCode.encodeWithLogo(text, logo, QrCodeOptions(errorCorrection = level, minVersion = 4))
            val plain = QrCode.encode(text, QrCodeOptions(errorCorrection = level, minVersion = 4))
            // ZXing 은 완벽히 규칙적인 큰 그림에서 배율에 따라 로고 없는 QR 도 못 찾는 일이 있다(데이터 영역이 찾기 패턴처럼 보인다).
            // 그래서 "로고 없는 QR 이 읽히는 배율에서는 로고 있는 QR 도 읽힌다" 를 본다. 적어도 한 배율은 읽혀야 한다
            var readable = 0
            for (scale in 1..4) {
                val size = code.matrix.width * scale
                if (readBack(CodeImages.render(plain, size)) != text) continue
                readable++
                assertEquals("#$i $logo $level x$scale", text, readBack(CodeImages.render(code, size, solidLogo(0xFF336699.toInt(), 7, 3))))
            }
            assertTrue("#$i 어느 배율에서도 읽히지 않았다", readable > 0)
        }
    }
}
