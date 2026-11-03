package net.plumt.qr.scanner

import android.content.Context
import androidx.camera.core.ImageProxy
import net.plumt.qr.CodeFormat
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.internal.MlKitEngine
import net.plumt.qr.scanner.internal.ZxingEngine

/**
 * 카메라 분석 프레임 하나. 회전 전(센서 방향) 버퍼 그대로다.
 *
 * 밝기 평면([luminance])은 처음 읽을 때 한 번만 복사한다 — 밝기를 읽지 않는 엔진(ML Kit)은 복사 비용을 내지 않는다.
 *
 * @property width 버퍼 너비(회전 전)
 * @property height 버퍼 높이(회전 전)
 * @property rotationDegrees 버퍼를 바로 세우려면 시계 방향으로 돌릴 각도. 0·90·180·270 중 하나
 * @property timestampNanos 프레임 시각(나노초, 단조 증가). 자동 넘기기의 시간 판정이 이 값을 쓴다
 */
public class ScanFrame internal constructor(
    public val width: Int,
    public val height: Int,
    public val rotationDegrees: Int,
    public val timestampNanos: Long,
    private val lumaSource: () -> Luma,
    internal val image: ImageProxy?,
) {
    /**
     * 밝기 배열에서 프레임을 만든다(직접 프레임을 넣는 엔진·테스트용).
     *
     * @throws IllegalArgumentException 너비·높이가 1 미만, `rowStride < width`, 배열이 `rowStride * (height - 1) + width` 보다 작을 때,
     *   회전이 0·90·180·270 이 아닐 때
     */
    public constructor(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        rotationDegrees: Int = 0,
        timestampNanos: Long = 0,
    ) : this(width, height, rotationDegrees, timestampNanos, { Luma(luminance, rowStride) }, null) {
        require(rowStride >= width) { "rowStride($rowStride) 는 width($width) 이상이어야 한다" }
        val need = rowStride.toLong() * (height - 1) + width
        require(luminance.size >= need) { "luminance 크기(${luminance.size})가 rowStride*(height-1)+width($need) 보다 작다" }
    }

    init {
        require(width >= 1 && height >= 1) { "프레임 크기(${width}x$height)는 1x1 이상이어야 한다" }
        require(rotationDegrees in ROTATIONS) { "rotationDegrees($rotationDegrees) 는 0·90·180·270 중 하나여야 한다" }
    }

    private val luma: Luma by lazy(LazyThreadSafetyMode.NONE) { lumaSource() }

    /** 밝기(Y) 평면. 한 바이트가 한 픽셀의 0..255 밝기다. 행 간격은 [rowStride]. 돌려준 배열을 고치지 않는다. */
    public val luminance: ByteArray get() = luma.bytes

    /** [luminance] 의 행 간격(바이트). [width] 이상이다. */
    public val rowStride: Int get() = luma.rowStride

    /** 바로 세운 이미지의 너비. 90·270 이면 [height] 다. */
    public val uprightWidth: Int get() = if (rotationDegrees % 180 == 0) width else height

    /** 바로 세운 이미지의 높이. 90·270 이면 [width] 다. */
    public val uprightHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    internal class Luma(val bytes: ByteArray, val rowStride: Int)

    internal companion object {
        val ROTATIONS = setOf(0, 90, 180, 270)

        /** CameraX 프레임에서 만든다. 밝기 평면은 읽을 때 [ImageProxy] 의 Y 평면에서 복사한다. */
        fun of(image: ImageProxy): ScanFrame = ScanFrame(
            width = image.width,
            height = image.height,
            rotationDegrees = image.imageInfo.rotationDegrees,
            timestampNanos = image.imageInfo.timestamp,
            lumaSource = { copyLuma(image) },
            image = image,
        )

        private fun copyLuma(image: ImageProxy): Luma {
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate().apply { rewind() }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val stride = plane.rowStride
            // 마지막 행에 패딩이 없는 기기가 있다 — 배열이 짧아도 각 행의 width 바이트는 들어 있다
            return Luma(bytes, stride)
        }
    }
}

/**
 * 엔진이 찾은 코드 하나.
 *
 * @property result 읽은 결과. `points` 는 바로 세운 이미지 좌표다
 * @property bounds 바로 세운 이미지 좌표의 사각형. 엔진이 네 모서리를 주지 않으면(ZXing) 점들에서 어림한다
 */
public data class Detection(val result: ScanResult, val bounds: CodeBounds)

/**
 * 인식 엔진. 프레임 하나에서 코드를 모두 찾는다.
 *
 * - [detect] 는 분석 전용 스레드 하나에서 차례로 불린다(동시에 불리지 않는다). 메인 스레드에서 부르지 않는다 — 블로킹이다
 * - 돌려주는 좌표는 **바로 세운 이미지**([ScanFrame.uprightWidth] x [ScanFrame.uprightHeight]) 기준이다
 * - 코드가 없으면 빈 목록이다. 엔진이 실패하면 [ScannerException] 을 던진다(원인은 `cause`). 스캐너는 그 프레임을 건너뛰고 다음 프레임을 본다
 * - [close] 뒤에는 [detect] 가 불리지 않는다
 * - [ScanFrame] 은 [detect] 가 끝나면 카메라에 반납된다. 프레임이나 [ScanFrame.luminance] 를 붙들어 두지 않는다
 *
 * 직접 구현해 [ScannerOptions.engine] 에 넣으면 다른 인식기를 쓸 수 있다.
 */
public interface ScanEngine : AutoCloseable {

    /** 프레임에서 코드를 찾는다. */
    public fun detect(frame: ScanFrame): List<Detection>

    /** 자원을 푼다. 여러 번 불러도 된다. */
    override fun close()

    /** 엔진을 만든다. 카메라 세션마다 한 번 불리고, 세션이 끝나면 만든 엔진을 닫는다. */
    public fun interface Factory {
        /**
         * @param context 애플리케이션 Context
         * @param formats 찾을 형식(비어 있지 않다)
         * @throws ScannerException 엔진을 만들 수 없을 때(라이브러리가 빠졌거나 초기화 실패)
         */
        public fun create(context: Context, formats: Set<CodeFormat>): ScanEngine
    }
}

/** 기본 제공 엔진. */
public object ScanEngines {

    /**
     * Google ML Kit 바코드 인식(번들 모델). 기기에 Google Play 서비스가 없어도 돈다. 기울어진·작은·흐린 코드에 강하다.
     * 앱이 ML Kit 의존을 exclude 했다면 [ScanEngine.Factory.create] 가 [ScannerException] 을 던진다(조용히 다른 엔진으로 바꾸지 않는다).
     */
    @JvmStatic
    public fun mlKit(): ScanEngine.Factory = MlKitEngine.Factory

    /**
     * ZXing(`qr` 모듈의 [net.plumt.qr.CodeReader]). Google 라이브러리가 하나도 없어야 하는 배포판, APK 크기를 줄이려는 앱에 쓴다.
     * ML Kit 보다 기울어짐·흐림에 약하다. 같은 원문의 코드가 한 프레임에 여럿이면 하나만 잡는다.
     *
     * @param tryHarder 더 꼼꼼히(느리게) 찾는다
     * @param alsoInverted 어두운 바탕의 밝은 코드도 찾는다(프레임마다 한 번 더 읽어 느려진다)
     */
    @JvmStatic
    @JvmOverloads
    public fun zxing(tryHarder: Boolean = false, alsoInverted: Boolean = false): ScanEngine.Factory =
        ZxingEngine.Factory(tryHarder, alsoInverted)
}
