package net.plumt.qr.internal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/** 메시지 수 표기가 로케일과 무관하고 JVM `String.format(Locale.ROOT, …)` 과 같은 값인지. */
class FormatsTest {
    @Test
    fun `소수 고정 표기는 ROOT 포맷과 같다`() {
        val rnd = Random(20261105)
        val values = listOf(0.0, -0.0, 0.05, 0.15, 0.25, 1.0, 2.9949, 2.9951, 2.995, 99.95, 1e-9, 12345.6789, -3.14159) +
            List(5000) { rnd.nextDouble(-1000.0, 1000.0) }
        for (v in values) for (scale in listOf(1, 3)) {
            assertEquals("$v/$scale", String.format(Locale.ROOT, "%.${scale}f", v), Formats.fixed(v, scale))
        }
    }

    @Test
    fun `유한하지 않은 값은 toString`() {
        assertEquals("NaN", Formats.fixed(Double.NaN, 1))
        assertEquals("Infinity", Formats.fixed(Double.POSITIVE_INFINITY, 3))
    }

    @Test
    fun `ARGB 16진 표기는 ROOT 포맷과 같다`() {
        val rnd = Random(7)
        for (argb in listOf(0, -1, 0xFF000000.toInt(), 0x00ABCDEF, Int.MIN_VALUE, Int.MAX_VALUE) + List(5000) { rnd.nextInt() }) {
            assertEquals(String.format(Locale.ROOT, "#%08X", argb), Formats.argbHex(argb))
        }
    }

    @Test
    fun `기본 로케일이 바뀌어도 같다`() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("de-DE", "ar-EG", "hi-IN-u-nu-deva", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "2.995", Formats.fixed(2.99512, 3))
                assertEquals(tag, "#FFABCDEF", Formats.argbHex(0xFFABCDEF.toInt()))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
