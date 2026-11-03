package net.plumt.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 아무 픽셀이나 넣어도 읽기는 결과·null·빈 목록만 내고, 여러 스레드에서 함께 불러도 같은 결과를 낸다. */
class ReaderFuzzTest {

    private val allOptions = listOf(
        ReadOptions(),
        ReadOptions(tryHarder = true, alsoInverted = true),
        ReadOptions(formats = CodeFormat.QR_ONLY),
        ReadOptions(formats = CodeFormat.PRODUCT, tryHarder = true),
    )

    @Test
    fun `무작위 밝기·ARGB 배열은 던지지 않는다`() {
        val random = Random(20261102)
        repeat(150) { i ->
            val width = random.nextInt(1, 120)
            val height = random.nextInt(1, 120)
            val stride = width + random.nextInt(0, 5)
            val options = allOptions[i % allOptions.size]
            val bytes = random.nextBytes(stride * (height - 1) + width)
            val argb = IntArray(width * height) { random.nextInt() }
            try {
                CodeReader.read(bytes, width, height, stride, options)
                CodeReader.readAll(bytes, width, height, stride, options)
                CodeReader.read(argb, width, height, options)
                CodeReader.readAll(argb, width, height, options)
            } catch (e: Throwable) {
                fail("${width}x$height stride=$stride options=$options 에서 던졌다: $e")
            }
        }
    }

    @Test
    fun `잡음이 섞인 코드 이미지는 던지지 않고, 읽으면 원문 그대로다`() {
        val random = Random(42)
        val text = "noise-test"
        val m = QrCode.encode(text).scaled(4)
        repeat(60) {
            val cells = m.toBooleanArray()
            val data = ByteArray(cells.size) { if (cells[it]) 0 else -1 }
            repeat(random.nextInt(0, data.size / 4)) { data[random.nextInt(data.size)] = random.nextInt(256).toByte() }
            val result = try {
                CodeReader.read(data, m.width, m.height, options = ReadOptions(tryHarder = true))
            } catch (e: Throwable) {
                fail("던졌다: $e")
                null
            }
            if (result != null) assertEquals(text, result.text)
        }
    }

    @Test
    fun `여러 스레드에서 함께 읽어도 같은 결과`() {
        val codes = (0 until 8).map { "thread-$it" }
        val images = codes.map { QrCode.encode(it).scaled(3) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 64).map { i ->
                pool.submit(
                    Callable {
                        val m = images[i % images.size]
                        CodeReader.read(m.toArgb(), m.width, m.height)?.text
                    },
                )
            }
            futures.forEachIndexed { i, f -> assertEquals(codes[i % codes.size], f.get(60, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `여러 스레드에서 함께 만들어도 같은 격자`() {
        val expected = QrCode.encode("동시 생성")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 32).map { pool.submit(Callable { QrCode.encode("동시 생성") }) }
            futures.forEach { assertEquals(expected, it.get(60, TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
    }
}
