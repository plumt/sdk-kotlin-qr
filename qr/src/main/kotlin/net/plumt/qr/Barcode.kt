package net.plumt.qr

import net.plumt.qr.internal.Encoders

/**
 * 바코드 생성 설정.
 *
 * 결과 크기는 너비 `max(width, 자연 크기)`, 높이 `max(height, 자연 높이)` 다. 자연 크기는 한 모듈 = 한 칸에 여백을 더한 것이고,
 * 1차원 바코드의 자연 높이는 1 이다. 요청한 크기가 자연 크기보다 크면 정수 배로 키우고 남는 칸은 가운데 정렬의 여백이 된다.
 * 1차원 바코드는 가로만 정수 배로 키우고 세로는 요청한 높이만큼 같은 행을 반복한다.
 *
 * @property width 원하는 너비(칸). 0 이면 자연 크기
 * @property height 원하는 높이(칸). 0 이면 자연 크기
 * @property margin 여백의 모듈 수. null 이면 형식별 기본값 — QR 4, 1차원 10(좌우만), PDF417 2, Data Matrix·Aztec 1
 * @throws IllegalArgumentException 값이 음수일 때 (생성 시)
 */
public data class BarcodeOptions(
    val width: Int = 0,
    val height: Int = 0,
    val margin: Int? = null,
) {
    init {
        require(width >= 0 && height >= 0) { "width($width)·height($height) 는 0 이상이어야 한다" }
        require(margin == null || margin >= 0) { "margin($margin) 은 0 이상이어야 한다" }
        require(width.toLong() * height <= MAX_CELLS) { "width*height(${width.toLong() * height}) 가 너무 크다" }
    }

    private companion object {
        const val MAX_CELLS = 100_000_000L
    }
}

/**
 * 1차원·2차원 바코드를 만든다. QR 은 [QrCode] 의 기본 설정(오류 정정 M·UTF-8)으로 만들고, 단계·버전을 고르려면 [QrCode] 를 쓴다.
 *
 * 형식마다 받는 내용을 엔진에 넘기기 전에 검사한다.
 *
 * | 형식 | 받는 내용 |
 * |---|---|
 * | `EAN_13` | 숫자 12자리(체크 숫자 계산) 또는 13자리(체크 숫자 검사) |
 * | `EAN_8` | 숫자 7자리 또는 8자리 |
 * | `UPC_A` | 숫자 11자리 또는 12자리 |
 * | `UPC_E` | 번호 체계 0·1 로 시작하는 숫자 7자리 또는 8자리 |
 * | `ITF` | 짝수 자릿수의 숫자, 2..80자리 |
 * | `CODE_39` | `0-9 A-Z - . $ / + %` 와 공백, 1..80자 |
 * | `CODE_93`·`CODE_128` | ASCII(0..127), 1..80자 |
 * | `CODABAR` | `0-9 - $ : / . +`, 앞뒤에 시작·끝 문자(`A`~`D`)를 붙일 수 있다. 붙이지 않으면 `A` 로 감싼다 |
 * | `QR_CODE`·`DATA_MATRIX`·`AZTEC`·`PDF_417` | 비어 있지 않은 글. 라틴-1 밖의 글자는 UTF-8 로 담는다 |
 *
 * ## 오류
 * - [IllegalArgumentException] — 위 표를 어긴 내용, 체크 숫자 불일치, 형식의 최대 용량을 넘는 내용
 * - [QrCodeException] — 엔진이 그 밖의 이유로 만들지 못했을 때(원인은 `cause`)
 */
public object Barcode {

    /** [text] 를 [format] 으로 만든다. */
    @JvmStatic
    @JvmOverloads
    public fun encode(text: String, format: CodeFormat, options: BarcodeOptions = BarcodeOptions()): CodeMatrix =
        Encoders.barcode(text, format, options)
}
