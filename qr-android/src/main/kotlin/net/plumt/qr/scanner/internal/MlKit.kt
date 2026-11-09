package net.plumt.qr.scanner.internal

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import net.plumt.qr.CodeFormat
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanPoint
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.CodeBounds
import net.plumt.qr.scanner.Detection
import net.plumt.qr.scanner.ScannerException
import net.plumt.qr.scanner.ScanEngine
import net.plumt.qr.scanner.ScanFrame
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ML Kit [Barcode] → [Detection]. ML Kit 형식 상수를 아는 곳은 여기 하나다.
 *
 * ML Kit 은 입력에 준 회전을 적용한 좌표(바로 세운 이미지 기준)로 박스·모서리를 돌려준다 — 다시 돌리지 않는다.
 */
internal object MlKitMapper {

    private val FORMATS: Map<CodeFormat, Int> = mapOf(
        CodeFormat.QR_CODE to Barcode.FORMAT_QR_CODE,
        CodeFormat.DATA_MATRIX to Barcode.FORMAT_DATA_MATRIX,
        CodeFormat.AZTEC to Barcode.FORMAT_AZTEC,
        CodeFormat.PDF_417 to Barcode.FORMAT_PDF417,
        CodeFormat.EAN_13 to Barcode.FORMAT_EAN_13,
        CodeFormat.EAN_8 to Barcode.FORMAT_EAN_8,
        CodeFormat.UPC_A to Barcode.FORMAT_UPC_A,
        CodeFormat.UPC_E to Barcode.FORMAT_UPC_E,
        CodeFormat.CODE_128 to Barcode.FORMAT_CODE_128,
        CodeFormat.CODE_39 to Barcode.FORMAT_CODE_39,
        CodeFormat.CODE_93 to Barcode.FORMAT_CODE_93,
        CodeFormat.CODABAR to Barcode.FORMAT_CODABAR,
        CodeFormat.ITF to Barcode.FORMAT_ITF,
    )
    private val REVERSE: Map<Int, CodeFormat> = FORMATS.entries.associate { (k, v) -> v to k }

    fun toMlKit(format: CodeFormat): Int = FORMATS.getValue(format)

    fun fromMlKit(format: Int): CodeFormat? = REVERSE[format]

    /** 형식 집합을 ML Kit 옵션 인자(첫 형식, 나머지 배열)로 나눈다. 비어 있으면 [IllegalArgumentException]. */
    fun formatArgs(formats: Set<CodeFormat>): Pair<Int, IntArray> {
        require(formats.isNotEmpty()) { "formats 가 비어 있다" }
        val codes = formats.sortedBy { it.ordinal }.map(::toMlKit)
        return codes.first() to codes.drop(1).toIntArray()
    }

    /**
     * 결과를 바꾼다. 버리는 것: 모르는 형식, 요청하지 않은 형식, 원문이 없는 것(UTF-8 이 아닌 바이너리 QR — `rawValue` 가 null),
     * 박스가 없는 것.
     */
    fun map(barcodes: List<Barcode>, formats: Set<CodeFormat>): List<Detection> = barcodes.mapNotNull { b ->
        val format = fromMlKit(b.format) ?: return@mapNotNull null
        if (format !in formats) return@mapNotNull null
        val text = b.rawValue ?: return@mapNotNull null
        val corners = b.cornerPoints?.map { ScanPoint(it.x.toFloat(), it.y.toFloat()) }.orEmpty()
        val box = b.boundingBox
        val bounds = when {
            box != null && box.width() > 0 && box.height() > 0 ->
                CodeBounds(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            else -> FrameRotation.enclose(corners)
        } ?: return@mapNotNull null
        Detection(ScanResult(text, format, QrPayload.parse(text, format), corners), bounds)
    }
}

/**
 * ML Kit 엔진. 분석 스레드에서 인식 작업을 끝날 때까지 기다린다(블로킹, 프레임당 상한 2초 — 넘으면 [ScannerException]). CameraX 프레임이면 복사 없이 미디어 이미지를 넘기고,
 * 직접 만든 프레임이면 밝기 평면으로 NV21 을 만들어 넘긴다(바코드 인식은 밝기만 쓴다).
 */
internal class MlKitEngine private constructor(
    private val scanner: BarcodeScanner,
    private val formats: Set<CodeFormat>,
) : ScanEngine {

    @Volatile
    private var closed = false

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun detect(frame: ScanFrame): List<Detection> {
        check(!closed) { "닫힌 엔진이다" }
        val media = frame.image?.image
        val input = if (media != null) {
            InputImage.fromMediaImage(media, frame.rotationDegrees)
        } else {
            InputImage.fromByteArray(Nv21.fromLuminance(frame.luminance, frame.width, frame.height, frame.rowStride),
                frame.width, frame.height, frame.rotationDegrees, InputImage.IMAGE_FORMAT_NV21)
        }
        val barcodes = try {
            // 상한을 둔다 — 인식기가 멈추면 분석 스레드가 영구히 묶이고 오류도 알리지 못한다
            Tasks.await(scanner.process(input), PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw ScannerException("ML Kit 이 ${PROCESS_TIMEOUT_SECONDS}초 안에 프레임을 처리하지 못했다", e)
        } catch (e: ExecutionException) {
            throw ScannerException("ML Kit 이 프레임을 처리하지 못했다", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ScannerException("ML Kit 처리를 기다리다 중단됐다", e)
        }
        return MlKitMapper.map(barcodes, formats)
    }

    override fun close() {
        if (closed) return
        closed = true
        scanner.close()
    }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 2L
    }

    object Factory : ScanEngine.Factory {
        override fun create(context: Context, formats: Set<CodeFormat>): ScanEngine {
            require(formats.isNotEmpty()) { "formats 가 비어 있다" }
            return try {
                val (first, rest) = MlKitMapper.formatArgs(formats)
                val options = BarcodeScannerOptions.Builder().setBarcodeFormats(first, *rest).build()
                MlKitEngine(BarcodeScanning.getClient(options), formats)
            } catch (e: LinkageError) {
                throw ScannerException("ML Kit 바코드 라이브러리를 찾지 못했다 — 의존에서 뺐다면 ScanEngines.zxing() 을 쓴다", e)
            } catch (e: RuntimeException) {
                throw ScannerException("ML Kit 인식기를 만들지 못했다", e)
            }
        }

        override fun toString(): String = "ScanEngines.mlKit()"
    }
}

/** 밝기 평면 → NV21(색차는 회색 128). */
internal object Nv21 {
    fun fromLuminance(luminance: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        val ySize = width * height
        val out = ByteArray(ySize + 2 * ((width + 1) / 2) * ((height + 1) / 2))
        if (rowStride == width) {
            System.arraycopy(luminance, 0, out, 0, ySize)
        } else {
            for (row in 0 until height) System.arraycopy(luminance, row * rowStride, out, row * width, width)
        }
        out.fill(GRAY, ySize, out.size)
        return out
    }

    private const val GRAY: Byte = 128.toByte()
}
