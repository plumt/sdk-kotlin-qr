package net.plumt.qr

import kotlin.math.pow

/** 전경(어두운 칸)·배경 색 조합이 읽히는 정도. [QrColors.readability] 가 정한다. */
public enum class ColorReadability {
    /** 대비가 [QrColors.RECOMMENDED_CONTRAST] 이상 — 대부분의 스캐너가 읽는다. */
    READABLE,

    /** 대비가 [QrColors.MIN_CONTRAST] 이상 [QrColors.RECOMMENDED_CONTRAST] 미만 — 화면·조명에 따라 못 읽는 스캐너가 있다. */
    MARGINAL,

    /** 대비가 [QrColors.MIN_CONTRAST] 미만 — 인식이 자주 실패한다. */
    LOW_CONTRAST,

    /** 전경이 배경보다 밝다(밝기 반전). 명암 반전을 읽지 않는 스캐너가 많다. */
    INVERTED,
}

/**
 * 색 대비 검사. 색은 ARGB 정수(`android.graphics.Color`·Compose `Color.toArgb()` 와 같은 표현)다.
 *
 * 대비는 WCAG 2 의 상대 휘도 대비(1.0 = 같은 색, 21.0 = 검정·흰색)다. 알파가 있는 색은 흰 바탕에 얹은 색으로 본다 —
 * 투명 배경의 QR 은 대개 흰 화면·종이 위에 놓이고, 읽기([CodeReader])도 투명 픽셀을 흰 바탕으로 읽는다. 어두운 바탕에 놓을 QR 이면
 * 그 바탕색을 배경으로 넘긴다. 던지지 않는다.
 */
public object QrColors {

    /** 이 대비 미만이면 [ColorReadability.LOW_CONTRAST]. */
    public const val MIN_CONTRAST: Double = 3.0

    /** 이 대비 이상이면 [ColorReadability.READABLE]. */
    public const val RECOMMENDED_CONTRAST: Double = 4.0

    /** WCAG 상대 휘도(0.0 검정 .. 1.0 흰색). */
    @JvmStatic
    public fun relativeLuminance(argb: Int): Double {
        val a = (argb ushr 24) and 0xFF
        fun channel(shift: Int): Double {
            val raw = (argb shr shift) and 0xFF
            // 흰 바탕에 얹는다(sRGB 값에서 섞는다 — 화면 합성과 같다)
            val c = (raw * a + 0xFF * (0xFF - a)) / 255.0 / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** 두 색의 대비. 1.0 이상 21.0 이하, 순서와 무관하다. */
    @JvmStatic
    public fun contrastRatio(a: Int, b: Int): Double {
        val x = relativeLuminance(a)
        val y = relativeLuminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** 전경이 배경보다 밝은가(밝기 반전). 같은 밝기는 반전이 아니다. */
    @JvmStatic
    public fun isInverted(foreground: Int, background: Int): Boolean =
        relativeLuminance(foreground) > relativeLuminance(background)

    /** 판정. 반전이 먼저고, 그다음 대비다. */
    @JvmStatic
    public fun readability(foreground: Int, background: Int): ColorReadability {
        if (isInverted(foreground, background)) return ColorReadability.INVERTED
        val ratio = contrastRatio(foreground, background)
        return when {
            ratio < MIN_CONTRAST -> ColorReadability.LOW_CONTRAST
            ratio < RECOMMENDED_CONTRAST -> ColorReadability.MARGINAL
            else -> ColorReadability.READABLE
        }
    }
}
