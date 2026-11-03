package net.plumt.qr.internal

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import net.plumt.qr.CodeFormat
import net.plumt.qr.QrCodeException
import net.plumt.qr.QrPayload
import net.plumt.qr.ReadOptions
import net.plumt.qr.ScanPoint
import net.plumt.qr.ScanResult

/** 읽기 엔진(ZXing) 어댑터. 엔진 타입은 이 파일 밖으로 나가지 않는다. */
internal object Decoders {

    private val TO_ENGINE: Map<CodeFormat, BarcodeFormat> = mapOf(
        CodeFormat.QR_CODE to BarcodeFormat.QR_CODE,
        CodeFormat.DATA_MATRIX to BarcodeFormat.DATA_MATRIX,
        CodeFormat.AZTEC to BarcodeFormat.AZTEC,
        CodeFormat.PDF_417 to BarcodeFormat.PDF_417,
        CodeFormat.EAN_13 to BarcodeFormat.EAN_13,
        CodeFormat.EAN_8 to BarcodeFormat.EAN_8,
        CodeFormat.UPC_A to BarcodeFormat.UPC_A,
        CodeFormat.UPC_E to BarcodeFormat.UPC_E,
        CodeFormat.CODE_128 to BarcodeFormat.CODE_128,
        CodeFormat.CODE_39 to BarcodeFormat.CODE_39,
        CodeFormat.CODE_93 to BarcodeFormat.CODE_93,
        CodeFormat.CODABAR to BarcodeFormat.CODABAR,
        CodeFormat.ITF to BarcodeFormat.ITF,
    )
    private val FROM_ENGINE: Map<BarcodeFormat, CodeFormat> = TO_ENGINE.entries.associate { (k, v) -> v to k }

    fun luminanceSource(luminance: ByteArray, width: Int, height: Int, rowStride: Int): LuminanceSource {
        Preconditions.image(luminance.size, width, height, rowStride)
        return PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
    }

    /** ARGB → 밝기. 투명도는 흰 바탕에 얹어 계산한다 — 투명 배경 PNG 의 바탕이 검정으로 읽히지 않게. */
    fun argbSource(argb: IntArray, width: Int, height: Int): LuminanceSource {
        Preconditions.image(argb.size, width, height, width)
        val luminance = ByteArray(width * height) { i ->
            val p = argb[i]
            val a = p ushr 24
            val gray = (((p shr 16) and 0xFF) + 2 * ((p shr 8) and 0xFF) + (p and 0xFF)) / 4
            ((gray * a + 255 * (255 - a)) / 255).toByte()
        }
        return PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    }

    fun read(source: LuminanceSource, options: ReadOptions): ScanResult? = reading {
        val result = try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints(options))
        } catch (e: ReaderException) {
            // 코드가 없거나 깨졌으면 엔진이 예외로 알린다
            null
        }
        result?.let(::toScanResult)
    }

    fun readAll(source: LuminanceSource, options: ReadOptions): List<ScanResult> = reading {
        val hints = hints(options)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val found = mutableListOf<Result>()
        found += multiple { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap, hints) }
        // 범용 분할 탐색은 서로 붙은 QR 을 놓칠 수 있어, QR 을 찾을 때는 QR 전용 다중 탐색을 더한다
        if (CodeFormat.QR_CODE in options.formats) {
            found += multiple { QRCodeMultiReader().decodeMultiple(bitmap, hints) }
        }
        found.mapNotNull(::toScanResult)
            .distinctBy { it.format to it.text }
            .sortedWith(compareBy<ScanResult>({ r -> r.points.minOfOrNull { it.y } ?: 0f }, { r -> r.points.minOfOrNull { it.x } ?: 0f }))
    }

    private inline fun multiple(block: () -> Array<Result>): List<Result> =
        try {
            block().toList()
        } catch (e: NotFoundException) {
            // 하나도 없으면 엔진이 예외로 알린다
            emptyList()
        }

    private fun hints(options: ReadOptions): Map<DecodeHintType, Any> = buildMap {
        put(DecodeHintType.POSSIBLE_FORMATS, options.formats.mapNotNull(TO_ENGINE::get))
        if (options.tryHarder) put(DecodeHintType.TRY_HARDER, true)
        if (options.alsoInverted) put(DecodeHintType.ALSO_INVERTED, true)
        options.characterSet?.let { put(DecodeHintType.CHARACTER_SET, it.name()) }
    }

    private fun toScanResult(result: Result): ScanResult? {
        val format = FROM_ENGINE[result.barcodeFormat] ?: return null
        val text = result.text ?: return null
        val points = result.resultPoints.orEmpty().filterNotNull().map { ScanPoint(it.x, it.y) }
        return ScanResult(text, format, QrPayload.parse(text, format), points)
    }

    /** 인자 검사를 통과한 뒤 엔진이 던진 예외는 모두 [QrCodeException] 이다 — 이미지에 코드가 없는 것은 예외가 아니다. */
    internal inline fun <T> reading(block: () -> T): T =
        try {
            block()
        } catch (e: QrCodeException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            throw QrCodeException("코드 읽기에 실패했다: ${e.message}", e)
        }
}
