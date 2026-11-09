package net.plumt.qr.internal

import com.google.zxing.WriterException
import net.plumt.qr.BarcodeNumber
import net.plumt.qr.BarcodeNumberType
import net.plumt.qr.CodeFormat
import net.plumt.qr.QrCodeException
import net.plumt.qr.QrContentTooLongException

/** 진입점 인자 검사. 실패하면 받은 값과 허용 범위를 담은 [IllegalArgumentException] 을 던진다. */
internal object Preconditions {

    private const val MAX_LINEAR_LENGTH = 80
    private const val CODE_39_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%"
    private const val CODABAR_BODY = "0123456789-$:/.+"
    private const val CODABAR_GUARDS = "ABCD"

    /** [format] 이 [text] 를 담을 수 있는지. 엔진의 모호한 오류 대신 무엇이 틀렸는지 알린다. */
    fun content(text: String, format: CodeFormat) {
        require(text.isNotEmpty()) { "내용이 비어 있다" }
        when (format) {
            CodeFormat.EAN_13 -> productNumber(text, format, BarcodeNumberType.EAN_13)
            CodeFormat.EAN_8 -> productNumber(text, format, BarcodeNumberType.EAN_8)
            CodeFormat.UPC_A -> productNumber(text, format, BarcodeNumberType.UPC_A)
            CodeFormat.UPC_E -> {
                require(text.isNotEmpty() && (text[0] == '0' || text[0] == '1')) { "UPC_E 는 번호 체계 0 또는 1 로 시작해야 한다 (받은 값: $text)" }
                productNumber(text, format, BarcodeNumberType.UPC_E)
            }
            CodeFormat.ITF -> {
                require(text.all { it in '0'..'9' }) { "ITF 는 숫자만 받는다 (받은 값: $text)" }
                require(text.length % 2 == 0 && text.length in 2..MAX_LINEAR_LENGTH) { "ITF 는 2..$MAX_LINEAR_LENGTH 의 짝수 자릿수여야 한다 (받은 길이: ${text.length})" }
            }
            CodeFormat.CODE_39 -> {
                linearLength(text, format)
                val bad = text.firstOrNull { it !in CODE_39_CHARS }
                require(bad == null) { "CODE_39 가 담을 수 없는 글자 '$bad' — 0-9 A-Z - . \$ / + % 와 공백만 받는다" }
            }
            CodeFormat.CODE_93, CodeFormat.CODE_128 -> {
                linearLength(text, format)
                val bad = text.firstOrNull { it.code > 127 }
                require(bad == null) { "${format.name} 는 ASCII 만 받는다 (받은 글자: '$bad')" }
            }
            CodeFormat.CODABAR -> codabar(text)
            CodeFormat.QR_CODE, CodeFormat.DATA_MATRIX, CodeFormat.AZTEC, CodeFormat.PDF_417 -> Unit
        }
    }

    /** 픽셀 배열의 크기·행 간격 검사. */
    fun image(size: Int, width: Int, height: Int, rowStride: Int) {
        require(width >= 1 && height >= 1) { "width($width)·height($height) 는 1 이상이어야 한다" }
        require(rowStride >= width) { "rowStride($rowStride) 는 width($width) 이상이어야 한다" }
        val needed = rowStride.toLong() * (height - 1) + width
        require(size.toLong() >= needed) { "배열 크기($size) 가 rowStride*(height-1)+width($needed) 보다 작다" }
    }

    private fun productNumber(text: String, format: CodeFormat, type: BarcodeNumberType) {
        require(text.all { it in '0'..'9' }) { "${format.name} 는 숫자만 받는다 (받은 값: $text)" }
        val full = type.length
        require(text.length == full || text.length == full - 1) { "${format.name} 는 ${full - 1}자리 또는 ${full}자리여야 한다 (받은 길이: ${text.length})" }
        if (text.length == full) {
            require(BarcodeNumber.isValid(text, type)) {
                "${format.name} 체크 숫자가 틀렸다: $text (맞는 값: ${BarcodeNumber.withCheckDigit(text.dropLast(1), type)})"
            }
        }
    }

    private fun linearLength(text: String, format: CodeFormat) {
        require(text.length <= MAX_LINEAR_LENGTH) { "${format.name} 는 ${MAX_LINEAR_LENGTH}자 이하여야 한다 (받은 길이: ${text.length})" }
    }

    private fun codabar(text: String) {
        linearLength(text, CodeFormat.CODABAR)
        val upper = text.uppercase()
        val guarded = upper.first() in CODABAR_GUARDS
        val body = if (guarded) {
            require(upper.length >= 2 && upper.last() in CODABAR_GUARDS) { "CODABAR 의 시작 문자(${text.first()})가 있으면 끝 문자(A~D)도 있어야 한다" }
            upper.substring(1, upper.length - 1)
        } else {
            upper
        }
        require(body.isNotEmpty()) { "CODABAR 의 본문이 비어 있다" }
        val bad = body.firstOrNull { it !in CODABAR_BODY }
        require(bad == null) { "CODABAR 가 담을 수 없는 글자 '$bad' — 0-9 - \$ : / . + 만 받는다" }
    }
}

/**
 * 엔진 호출을 감싼다. 호출부가 "인자 오류"([IllegalArgumentException])와 "엔진 실패"([QrCodeException]) 두 가지만
 * 구분하면 되게 한다. 엔진이 입력 탓으로 던진 오류(용량 초과 등)는 [IllegalArgumentException] 으로, 나머지는 원인을 담은
 * [QrCodeException] 으로 바꾼다.
 */
internal inline fun <T> engine(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: QrCodeException) {
        throw e
    } catch (e: QrContentTooLongException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$what 에 실패했다 — 내용을 확인한다: ${e.message}", e)
    } catch (e: WriterException) {
        val message = e.message.orEmpty()
        if (isCapacityError(e)) throw IllegalArgumentException("$what 에 실패했다 — 내용이 최대 용량을 넘는다: $message", e)
        throw QrCodeException("$what 에 실패했다: $message", e)
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        throw QrCodeException("$what 에 실패했다: ${e.message}", e)
    }

/** 엔진이 "내용이 너무 크다" 로 던진 오류인가. ZXing 은 형식마다 문구가 다르다. */
internal fun isCapacityError(e: WriterException): Boolean {
    val message = e.message.orEmpty()
    return listOf("too big", "too large", "too many", "unable to fit").any { message.contains(it, ignoreCase = true) }
}
