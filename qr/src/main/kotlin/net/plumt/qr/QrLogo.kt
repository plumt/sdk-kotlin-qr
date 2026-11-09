package net.plumt.qr

/**
 * 가운데 로고 설정.
 *
 * 로고 칸 수는 `기호 한 변 × sizeRatio` 이하이면서 기호 한 변과 홀짝이 같은 가장 큰 정수다(정확히 가운데에 놓이게). 최소 1칸.
 * 그 둘레 [paddingModules] 칸까지 비운다. 비우는 자리 전체(로고 + 패딩 × 2)가 단계의 [ErrorCorrection.maxLogoRatio] 이하여야 한다.
 *
 * @property sizeRatio 로고 한 변 / 기호 한 변(여백 제외). 0 초과 [MAX_SIZE_RATIO] 이하
 * @property paddingModules 로고 둘레에 더 비울 칸 수. 0..[MAX_PADDING]
 * @throws IllegalArgumentException 범위 밖일 때 (생성 시)
 */
public data class QrLogoOptions(
    val sizeRatio: Float = 0.2f,
    val paddingModules: Int = 1,
) {
    init {
        require(sizeRatio.isFinite() && sizeRatio > 0f && sizeRatio <= MAX_SIZE_RATIO) {
            "sizeRatio($sizeRatio) 는 0 초과 $MAX_SIZE_RATIO 이하여야 한다"
        }
        require(paddingModules in 0..MAX_PADDING) { "paddingModules($paddingModules) 는 0..$MAX_PADDING 범위여야 한다" }
    }

    public companion object {
        /** [sizeRatio] 의 상한. [ErrorCorrection.H] 의 [ErrorCorrection.maxLogoRatio] 와 같다. */
        public const val MAX_SIZE_RATIO: Float = 0.3f

        /** [paddingModules] 의 상한. */
        public const val MAX_PADDING: Int = 4
    }
}

/**
 * 격자 위의 직사각형(칸 단위, 여백 포함 좌표). 왼쪽 위가 원점이다.
 *
 * @throws IllegalArgumentException 좌표가 음수이거나 너비·높이가 1 미만일 때
 */
public data class CellArea(val left: Int, val top: Int, val width: Int, val height: Int) {
    init {
        require(left >= 0 && top >= 0) { "left($left)·top($top) 는 0 이상이어야 한다" }
        require(width >= 1 && height >= 1) { "width($width)·height($height) 는 1 이상이어야 한다" }
    }

    /** 오른쪽 끝(제외). */
    public val right: Int get() = left + width

    /** 아래쪽 끝(제외). */
    public val bottom: Int get() = top + height

    /** (x, y) 칸이 안에 있는지. */
    public fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

/**
 * 로고 자리를 비운 QR. [QrCode.encodeWithLogo] 가 만든다.
 *
 * @property matrix 로고 자리와 패딩을 밝은 칸으로 비운 격자(한 칸 = 한 모듈, 여백 포함)
 * @property logoArea 로고를 그릴 자리(패딩 제외). [matrix] 좌표다 — 픽셀로는 확대 배율을 곱한다
 * @property clearedArea 비운 자리 전체(로고 + 패딩)
 * @property errorCorrection 만든 오류 정정 단계
 */
public class LogoQrCode internal constructor(
    public val matrix: CodeMatrix,
    public val logoArea: CellArea,
    public val clearedArea: CellArea,
    public val errorCorrection: ErrorCorrection,
) {
    override fun equals(other: Any?): Boolean =
        other is LogoQrCode && other.matrix == matrix && other.logoArea == logoArea &&
            other.clearedArea == clearedArea && other.errorCorrection == errorCorrection

    override fun hashCode(): Int = ((matrix.hashCode() * 31 + logoArea.hashCode()) * 31 + clearedArea.hashCode()) * 31 + errorCorrection.hashCode()

    override fun toString(): String = "LogoQrCode(matrix=$matrix, logoArea=$logoArea, clearedArea=$clearedArea, errorCorrection=$errorCorrection)"
}
