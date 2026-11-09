package net.plumt.qr.internal

import net.plumt.qr.ErrorCorrection
import net.plumt.qr.QrLogoOptions

/**
 * 로고 자리 계산. 좌표는 여백 없는 모듈 격자 기준이고 자리는 언제나 정사각형·정가운데다.
 *
 * 비울 수 없는 곳: 세 모서리의 찾기 패턴과 분리선(8x8), 그 바로 옆 형식 정보 줄(8번째 행·열). 정가운데 정사각형이라
 * 시작 칸이 [RESERVED] 이상이면 넷 모두를 피한다. 타이밍 패턴(6번째 행·열)과 버전 정보(v7+)도 그 안쪽이라 함께 피한다.
 */
internal object LogoLayout {

    /** 시작 칸의 하한 — 0..7 은 찾기 패턴·분리선, 8 은 형식 정보다. */
    private const val RESERVED = 9

    class Place(val logoStart: Int, val logoSide: Int, val clearStart: Int, val clearSide: Int) {
        val clearEnd: Int get() = clearStart + clearSide
    }

    fun place(symbolSize: Int, logo: QrLogoOptions, level: ErrorCorrection): Place {
        require(level.maxLogoRatio > 0f) { "오류 정정 $level 은 로고를 허용하지 않는다 — M 이상을 쓴다" }
        var side = (symbolSize * logo.sizeRatio.toDouble()).toInt()
        // 한 변과 홀짝을 맞춰야 정가운데에 놓인다. QR 의 한 변(17 + 4 x 버전)은 언제나 홀수라 최소 1칸이 맞는다
        if ((symbolSize - side) % 2 != 0) side -= 1
        side = maxOf(side, 1)
        val clear = side + 2 * logo.paddingModules
        val ratio = clear.toDouble() / symbolSize
        require(ratio <= level.maxLogoRatio + EPSILON) {
            "로고 자리(로고 $side + 패딩 ${logo.paddingModules}x2 = ${clear}칸)가 기호 한 변(${symbolSize}칸)의 ${percent(ratio)}% 다 — " +
                "오류 정정 $level 의 상한 ${percent(level.maxLogoRatio.toDouble())}% 를 넘는다. sizeRatio·paddingModules 를 줄이거나 단계를 올린다"
        }
        val clearStart = (symbolSize - clear) / 2
        require(clearStart >= RESERVED) {
            "로고 자리(${clear}칸)가 기호(${symbolSize}칸)의 찾기 패턴·형식 정보에 닿는다 — sizeRatio·paddingModules 를 줄이거나 minVersion 을 올린다"
        }
        return Place(clearStart + logo.paddingModules, side, clearStart, clear)
    }

    private fun percent(r: Double): String = Formats.fixed(r * 100, 1)

    private const val EPSILON = 1e-9
}
