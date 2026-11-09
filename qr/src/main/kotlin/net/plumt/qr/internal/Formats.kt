package net.plumt.qr.internal

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 메시지에 쓰는 수 표기. `String.format(Locale.ROOT, …)` 는 플랫폼 ICU 의 루트 로케일 규칙(구분 기호·숫자 모양)을 따라
 * Android 버전마다 결과가 달라질 수 있어 쓰지 않는다. 언제나 ASCII 숫자와 `.` 이다.
 */
internal object Formats {

    /** [value] 를 소수 [scale] 자리로 반올림(HALF_UP)한다 — `"%.Nf"` 와 같은 값. 유한하지 않으면 `toString()`. */
    fun fixed(value: Double, scale: Int): String {
        if (!value.isFinite()) return value.toString()
        val s = BigDecimal(value.toString()).setScale(scale, RoundingMode.HALF_UP).toPlainString()
        // "%.Nf" 는 0 으로 반올림된 음수(-0.0 포함)에도 부호를 남긴다.
        return if (value < 0 || 1 / value < 0) (if (s.startsWith("-")) s else "-$s") else s
    }

    /** ARGB 를 `#AARRGGBB`(대문자 16진 8자리)로 — `"#%08X"` 와 같은 값. */
    fun argbHex(argb: Int): String = "#" + Integer.toHexString(argb).uppercase().padStart(8, '0')
}
