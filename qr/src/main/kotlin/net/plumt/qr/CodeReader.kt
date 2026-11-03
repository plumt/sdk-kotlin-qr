package net.plumt.qr

import net.plumt.qr.internal.Decoders
import java.nio.charset.Charset

/**
 * 읽기 설정.
 *
 * @property formats 찾을 형식. 좁힐수록 빠르고 오인식이 준다 — 실시간 카메라는 [CodeFormat.QR_ONLY] 를 권한다
 * @property tryHarder 더 오래 걸려도 더 꼼꼼히 찾는다(회전·작은 코드). 사진 한 장을 읽을 때 켠다
 * @property alsoInverted 못 찾으면 명암을 뒤집어 한 번 더 찾는다(어두운 바탕에 밝은 코드)
 * @property characterSet 문자셋 표지(ECI)가 없는 코드의 바이트를 읽을 문자셋. null 이면 엔진이 추정한다
 * @throws IllegalArgumentException [formats] 가 비어 있을 때 (생성 시)
 */
public data class ReadOptions(
    val formats: Set<CodeFormat> = CodeFormat.ALL,
    val tryHarder: Boolean = false,
    val alsoInverted: Boolean = false,
    val characterSet: Charset? = null,
) {
    init {
        require(formats.isNotEmpty()) { "formats 가 비어 있다 — 아무것도 읽지 않는 설정은 호출부 실수다" }
    }
}

/** 이미지 위의 한 점(픽셀 좌표, 왼쪽 위가 원점). */
public data class ScanPoint(val x: Float, val y: Float)

/**
 * 읽은 코드 하나.
 *
 * @property text 코드에 든 원문
 * @property format 읽은 기호의 형식
 * @property payload [text] 를 [format] 과 함께 푼 내용(`QrPayload.parse(text, format)`)
 * @property points 코드의 위치를 알려 주는 점들. 형식마다 뜻이 다르다 — QR 은 세 찾기 패턴의 중심(왼쪽 아래·왼쪽 위·오른쪽 위,
 *   있으면 정렬 패턴 하나 더), 1차원 바코드는 읽은 줄의 양 끝 두 점, Data Matrix·Aztec 은 네 모서리 근처다.
 *   네 꼭짓점이 필요하면 이 점들을 감싸는 사각형으로 어림한다
 *
 * [toString] 은 원문을 넣지 않는다(내용은 비밀을 가린 [payload] 로 보인다).
 */
public data class ScanResult(
    val text: String,
    val format: CodeFormat,
    val payload: QrPayload,
    val points: List<ScanPoint>,
) {
    // 원문(text)을 넣지 않는다 — Wi-Fi 비밀번호·OTP 비밀이 로그에 새지 않게. payload 의 toString 은 비밀을 가린다
    override fun toString(): String = "ScanResult(format=$format, payload=$payload, length=${text.length}, points=$points)"
}

/**
 * 이미지에서 코드를 읽는다. 플랫폼 그래픽 타입 없이 픽셀 배열을 받는다.
 *
 * - 밝기 배열(`ByteArray`, 한 바이트가 한 픽셀의 0..255 밝기): 카메라 프레임의 Y(휘도) 평면을 그대로 넣는다.
 *   행 끝에 패딩이 있는 프레임은 [rowStride] 에 실제 행 간격을 준다
 * - ARGB 배열(`IntArray`): Android `Bitmap.getPixels` 의 결과를 그대로 넣는다. 투명한 픽셀은 흰 바탕에 얹은 것으로 본다
 *
 * 상태가 없어 여러 스레드에서 함께 불러도 된다(호출마다 엔진을 새로 만든다).
 *
 * ## 오류
 * - 코드가 없으면 예외가 아니라 null·빈 목록이다(카메라 프레임마다 흔한 경우다)
 * - [IllegalArgumentException] — 너비·높이가 1 미만, `rowStride < width`, 배열이 `rowStride * (height - 1) + width` 보다 작을 때
 * - [QrCodeException] — 엔진이 읽는 도중 예기치 못하게 실패했을 때(원인은 `cause`)
 */
public object CodeReader {

    /** 밝기 배열에서 코드 하나를 읽는다. 없으면 null. */
    @JvmStatic
    @JvmOverloads
    public fun read(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        options: ReadOptions = ReadOptions(),
    ): ScanResult? = Decoders.read(Decoders.luminanceSource(luminance, width, height, rowStride), options)

    /** ARGB 배열에서 코드 하나를 읽는다. 없으면 null. */
    @JvmStatic
    @JvmOverloads
    public fun read(argb: IntArray, width: Int, height: Int, options: ReadOptions = ReadOptions()): ScanResult? =
        Decoders.read(Decoders.argbSource(argb, width, height), options)

    /**
     * 밝기 배열에서 코드를 모두 읽는다. 없으면 빈 목록. 순서는 위에서 아래, 왼쪽에서 오른쪽이다.
     * 같은 원문의 코드가 여러 개면 하나만 남는다.
     */
    @JvmStatic
    @JvmOverloads
    public fun readAll(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        options: ReadOptions = ReadOptions(),
    ): List<ScanResult> = Decoders.readAll(Decoders.luminanceSource(luminance, width, height, rowStride), options)

    /** ARGB 배열에서 코드를 모두 읽는다. 없으면 빈 목록. */
    @JvmStatic
    @JvmOverloads
    public fun readAll(argb: IntArray, width: Int, height: Int, options: ReadOptions = ReadOptions()): List<ScanResult> =
        Decoders.readAll(Decoders.argbSource(argb, width, height), options)
}
