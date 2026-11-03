package net.plumt.qr.internal

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.aztec.AztecWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.oned.CodaBarWriter
import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.Code39Writer
import com.google.zxing.oned.Code93Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.EAN8Writer
import com.google.zxing.oned.ITFWriter
import com.google.zxing.oned.OneDimensionalCodeWriter
import com.google.zxing.oned.UPCEWriter
import com.google.zxing.pdf417.PDF417Writer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import net.plumt.qr.BarcodeOptions
import net.plumt.qr.CodeFormat
import net.plumt.qr.CodeMatrix
import net.plumt.qr.ErrorCorrection
import net.plumt.qr.QrCodeOptions

/**
 * 생성 엔진(ZXing) 어댑터. 엔진에서는 여백 없는 모듈 격자만 받고, 여백·확대는 여기서 한다 —
 * 형식마다 다른 엔진의 여백 단위(모듈·픽셀)를 하나로 맞추려는 것이다.
 */
internal object Encoders {

    private const val MAX_CELLS = 100_000_000L

    /** 여백 없는 모듈 격자. */
    private class Modules(val width: Int, val height: Int, val cells: BooleanArray)

    fun qr(text: String, options: QrCodeOptions): CodeMatrix {
        Preconditions.content(text, CodeFormat.QR_CODE)
        require(options.charset.newEncoder().canEncode(text)) { "내용을 ${options.charset.name()} 로 나타낼 수 없다" }
        val modules = engine("QR 생성") { qrModules(text, options) }
        return render(modules, options.margin, linear = false, width = 0, height = 0)
    }

    fun barcode(text: String, format: CodeFormat, options: BarcodeOptions): CodeMatrix {
        Preconditions.content(text, format)
        val linear = format.kind != CodeFormat.Kind.MATRIX
        val margin = options.margin ?: defaultMargin(format)
        val modules = engine("${format.name} 생성") {
            if (linear) linearModules(text, format) else matrixModules(text, format)
        }
        return render(modules, margin, linear, options.width, options.height)
    }

    private fun qrModules(text: String, options: QrCodeOptions): Modules {
        val level = when (options.errorCorrection) {
            ErrorCorrection.L -> ErrorCorrectionLevel.L
            ErrorCorrection.M -> ErrorCorrectionLevel.M
            ErrorCorrection.Q -> ErrorCorrectionLevel.Q
            ErrorCorrection.H -> ErrorCorrectionLevel.H
        }
        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to options.charset.name())
        var code = Encoder.encode(text, level, hints)
        val min = options.minVersion
        if (min != null && code.version.versionNumber < min) {
            hints[EncodeHintType.QR_VERSION] = min
            code = Encoder.encode(text, level, hints)
        }
        val m = code.matrix
        return Modules(m.width, m.height, BooleanArray(m.width * m.height) { m.get(it % m.width, it / m.width).toInt() == 1 })
    }

    private fun linearModules(text: String, format: CodeFormat): Modules {
        val (writer, contents) = when (format) {
            CodeFormat.EAN_13 -> EAN13Writer() to text
            CodeFormat.EAN_8 -> EAN8Writer() to text
            // UPC-A 는 0 으로 시작하는 EAN-13 과 같은 막대다
            CodeFormat.UPC_A -> EAN13Writer() to "0$text"
            CodeFormat.UPC_E -> UPCEWriter() to text
            CodeFormat.CODE_128 -> Code128Writer() to text
            CodeFormat.CODE_39 -> Code39Writer() to text
            CodeFormat.CODE_93 -> Code93Writer() to text
            CodeFormat.CODABAR -> CodaBarWriter() to text.uppercase()
            CodeFormat.ITF -> ITFWriter() to text
            else -> error("1차원 형식이 아니다: $format")
        }
        val bars = (writer as OneDimensionalCodeWriter).encode(contents)
        return Modules(bars.size, 1, bars)
    }

    private fun matrixModules(text: String, format: CodeFormat): Modules {
        if (format == CodeFormat.QR_CODE) return qrModules(text, QrCodeOptions())
        val latin1 = Charsets.ISO_8859_1.newEncoder().canEncode(text)
        val hints = mutableMapOf<EncodeHintType, Any>()
        val bits: BitMatrix = when (format) {
            CodeFormat.DATA_MATRIX -> {
                if (!latin1) {
                    // 기본 인코더는 라틴-1 밖의 글자를 담지 못한다. 최소 인코더가 ECI 로 UTF-8 을 표시한다
                    hints[EncodeHintType.DATA_MATRIX_COMPACT] = true
                    hints[EncodeHintType.CHARACTER_SET] = Charsets.UTF_8.name()
                }
                DataMatrixWriter().encode(text, BarcodeFormat.DATA_MATRIX, 0, 0, hints)
            }
            CodeFormat.AZTEC -> {
                if (!latin1) hints[EncodeHintType.CHARACTER_SET] = Charsets.UTF_8.name()
                AztecWriter().encode(text, BarcodeFormat.AZTEC, 0, 0, hints)
            }
            CodeFormat.PDF_417 -> {
                hints[EncodeHintType.MARGIN] = 0
                if (!latin1) hints[EncodeHintType.CHARACTER_SET] = Charsets.UTF_8.name()
                PDF417Writer().encode(text, BarcodeFormat.PDF_417, 0, 0, hints)
            }
            else -> error("2차원 형식이 아니다: $format")
        }
        return Modules(bits.width, bits.height, BooleanArray(bits.width * bits.height) { bits.get(it % bits.width, it / bits.width) })
    }

    private fun defaultMargin(format: CodeFormat): Int = when (format) {
        CodeFormat.QR_CODE -> 4
        CodeFormat.PDF_417 -> 2
        CodeFormat.DATA_MATRIX, CodeFormat.AZTEC -> 1
        else -> 10
    }

    /**
     * 여백을 두르고 요청 크기로 키운다. 정수 배로만 키우고 남는 칸은 가운데 정렬의 여백이 된다.
     * 1차원은 여백을 좌우에만 두고 같은 행을 높이만큼 반복한다.
     */
    private fun render(m: Modules, margin: Int, linear: Boolean, width: Int, height: Int): CodeMatrix {
        val fullW = m.width.toLong() + 2L * margin
        val fullH = if (linear) 1L else m.height.toLong() + 2L * margin
        val outW = maxOf(width.toLong(), fullW)
        val outH = maxOf(height.toLong(), fullH)
        require(outW * outH <= MAX_CELLS) { "결과 크기(${outW}x$outH) 가 너무 크다 — margin($margin) 을 확인한다" }
        val w = outW.toInt()
        val h = outH.toInt()
        val scale = if (linear) (outW / fullW).toInt() else minOf(outW / fullW, outH / fullH).toInt()
        val left = (w - m.width * scale) / 2
        val top = if (linear) 0 else (h - m.height * scale) / 2
        val cells = BooleanArray(w * h)
        for (y in 0 until h) {
            val my = if (linear) 0 else (y - top).let { if (it < 0) -1 else it / scale }
            if (my !in 0 until m.height) continue
            for (x in 0 until w) {
                val mx = (x - left).let { if (it < 0) -1 else it / scale }
                if (mx in 0 until m.width && m.cells[my * m.width + mx]) cells[y * w + x] = true
            }
        }
        return CodeMatrix(w, h, cells)
    }
}
