package net.plumt.qr.scanner

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.plumt.qr.ArgbImage
import net.plumt.qr.CodeColors
import net.plumt.qr.CodeFormat
import net.plumt.qr.CodeImages
import net.plumt.qr.CodeMatrix
import net.plumt.qr.LogoQrCode
import net.plumt.qr.ScanResult

/**
 * 만든 코드를 Android [Bitmap] 으로. `qr` 의 [CodeImages] 를 감싼다(같은 크기·색 검사·로고 합성 규칙).
 * 결과는 `ARGB_8888` 이다. 큰 크기는 메인 스레드를 오래 잡으므로 백그라운드에서 부른다.
 *
 * ```
 * val level = QrCode.pickErrorCorrection(text, QrLogoOptions())
 * val code = QrCode.encodeWithLogo(text, QrLogoOptions(), QrCodeOptions(errorCorrection = level))
 * val bitmap = QrBitmaps.render(code, sizePx = 768, logo = logoBitmap, colors = CodeColors(fg, bg))
 * ```
 */
public object QrBitmaps {

    /**
     * [matrix] 를 [widthPx] x [heightPx] 비트맵으로 그린다. 칸은 정수 배로 키우고 남는 픽셀은 가운데 정렬의 배경이다.
     *
     * @throws IllegalArgumentException 크기가 격자보다 작거나 [CodeMatrix.MAX_CELLS] 를 넘을 때, [colors] 검사 실패([CodeColors])
     */
    @JvmStatic
    @JvmOverloads
    public fun render(matrix: CodeMatrix, widthPx: Int, heightPx: Int = widthPx, colors: CodeColors = CodeColors()): Bitmap =
        toBitmap(CodeImages.render(matrix, widthPx, heightPx, colors))

    /**
     * 로고 자리를 비운 QR 을 [sizePx] 정사각형으로 그리고 [logo] 를 얹는다. 로고는 비율을 지켜 로고 자리 안에 맞춘다.
     *
     * @throws IllegalArgumentException 크기가 격자보다 작을 때, [logo] 가 재활용됐거나 [CodeMatrix.MAX_CELLS] 픽셀을 넘을 때
     */
    @JvmStatic
    @JvmOverloads
    public fun render(code: LogoQrCode, sizePx: Int, logo: Bitmap, colors: CodeColors = CodeColors()): Bitmap =
        toBitmap(CodeImages.render(code, sizePx, toArgbImage(logo), colors))

    /** [image] 를 `ARGB_8888` 비트맵으로. */
    @JvmStatic
    public fun toBitmap(image: ArgbImage): Bitmap =
        Bitmap.createBitmap(image.toIntArray(), image.width, image.height, Bitmap.Config.ARGB_8888)

    /**
     * [bitmap] 의 픽셀. 하드웨어 비트맵(`Config.HARDWARE`)은 복사해 읽는다.
     *
     * @throws IllegalArgumentException 재활용된 비트맵, [CodeMatrix.MAX_CELLS] 픽셀 초과
     */
    @JvmStatic
    public fun toArgbImage(bitmap: Bitmap): ArgbImage {
        val readable = Bitmaps.readable(bitmap)
        try {
            val pixels = IntArray(readable.width * readable.height)
            readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
            return ArgbImage(readable.width, readable.height, pixels)
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }
}

/**
 * 사진 한 장(비트맵)에서 코드를 읽는다 — 갤러리 사진·스크린숏처럼 카메라 없이 인식할 때 쓴다.
 *
 * 카메라 스캐너와 같은 [ScanEngine] 을 쓰므로(기본 ML Kit) 같은 코드는 두 경로에서 같은 원문으로 읽힌다. 엔진은 호출마다 만들고 닫는다.
 * 비트맵은 밝기 배열로 한 줄씩 바꿔 넘긴다(투명 픽셀은 흰 바탕으로 본다) — 원본 크기의 `IntArray` 를 만들지 않는다.
 *
 * ## 오류
 * - 코드가 없으면 빈 목록이다(예외가 아니다)
 * - [IllegalArgumentException] — [formats] 가 비었거나 비트맵이 재활용됐을 때
 * - [ScannerException] — 엔진을 만들지 못했거나(ML Kit 의존을 뺐는데 [ScanEngines.mlKit]) 읽다 실패했을 때(원인은 `cause`)
 */
public object BitmapCodeReader {

    /**
     * 사진을 줄여 읽을 때 긴 변의 권장 하한(px). 1024 로 줄이면 수천만 화소 사진 속 촘촘한 QR(연락처·일정)의 모듈이 몇 픽셀로 뭉개져
     * 읽지 못한다. 이보다 크게 두면 메모리(픽셀당 1바이트 + 엔진 버퍼)와 시간이 는다.
     */
    public const val RECOMMENDED_MAX_DIMENSION: Int = 2048

    /**
     * [bitmap] 에서 코드를 모두 읽는다. 기본 디스패처에서 돌고 취소하면 엔진 대기를 끊는다. 결과의 점은 비트맵 좌표다.
     *
     * @param formats 찾을 형식. 카메라 스캐너에 넘긴 것과 같은 값을 넘겨야 두 경로의 결과가 같다
     * @param engine 인식 엔진. 사진은 기울어짐·흐림이 흔해 기본(ML Kit)을 권한다. ZXing 이면 `ScanEngines.zxing(tryHarder = true)`
     */
    @JvmStatic
    public suspend fun read(
        context: Context,
        bitmap: Bitmap,
        formats: Set<CodeFormat> = CodeFormat.QR_ONLY,
        engine: ScanEngine.Factory = ScanEngines.mlKit(),
    ): List<ScanResult> = read(context, bitmap, formats, engine, Dispatchers.Default)

    internal suspend fun read(
        context: Context,
        bitmap: Bitmap,
        formats: Set<CodeFormat>,
        engine: ScanEngine.Factory,
        dispatcher: CoroutineDispatcher,
    ): List<ScanResult> {
        require(formats.isNotEmpty()) { "formats 가 비어 있다 — 아무것도 읽지 않는 설정은 호출부 실수다" }
        require(!bitmap.isRecycled) { "재활용된 비트맵이다" }
        val app = context.applicationContext ?: context
        return runInterruptible(dispatcher) {
            val frame = ScanFrame(Bitmaps.luminance(bitmap), bitmap.width, bitmap.height)
            val created = try {
                engine.create(app, formats)
            } catch (e: ScannerException) {
                throw e
            } catch (e: RuntimeException) {
                throw ScannerException("인식 엔진을 만들지 못했다", e)
            }
            created.use { scanner ->
                val found = try {
                    scanner.detect(frame)
                } catch (e: ScannerException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw ScannerException("이미지에서 코드를 읽지 못했다", e)
                }
                found.map { it.result }.filter { it.format in formats }
            }
        }
    }
}

/** 비트맵 픽셀 읽기. */
internal object Bitmaps {

    /** 픽셀을 읽을 수 있는 비트맵. 하드웨어 비트맵이면 소프트웨어 사본(호출부가 재활용한다). */
    fun readable(bitmap: Bitmap): Bitmap {
        require(!bitmap.isRecycled) { "재활용된 비트맵이다" }
        val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
        return if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }

    /** 밝기 배열(픽셀당 1바이트, 행 간격 = 너비). 투명 픽셀은 흰 바탕에 얹은 밝기다. 한 줄씩 읽어 큰 `IntArray` 를 만들지 않는다. */
    fun luminance(bitmap: Bitmap): ByteArray {
        val readable = readable(bitmap)
        try {
            val w = readable.width
            val h = readable.height
            val out = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                readable.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) out[y * w + x] = luma(row[x]).toByte()
            }
            return out
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /** ITU-R BT.601 밝기, 흰 바탕 합성. */
    fun luma(argb: Int): Int {
        val a = argb ushr 24
        val y = (299 * ((argb shr 16) and 0xFF) + 587 * ((argb shr 8) and 0xFF) + 114 * (argb and 0xFF) + 500) / 1000
        return (y * a + 0xFF * (0xFF - a) + 127) / 0xFF
    }
}
