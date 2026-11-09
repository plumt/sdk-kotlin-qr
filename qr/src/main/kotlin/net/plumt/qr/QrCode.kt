package net.plumt.qr

import net.plumt.qr.internal.Encoders
import java.nio.charset.Charset

/**
 * QR 오류 정정 단계. 높을수록 가려지거나 더러워져도 읽히지만 담는 양이 줄어든다.
 *
 * @property maxLogoRatio 가운데에 로고를 얹을 때 비울 수 있는 자리(로고 + 패딩)의 한 변이 기호 한 변에서 차지하는 최대 비율.
 *   복원력의 일부만 로고에 쓰고 나머지는 얼룩·초점 흐림 몫으로 남기는 보수적인 값이다. 0 이면 로고를 허용하지 않는다
 */
public enum class ErrorCorrection(public val maxLogoRatio: Float) {
    /** 약 7% 복원. 로고를 허용하지 않는다. */
    L(0f),

    /** 약 15% 복원. 일반 용도의 기본값이다. 로고 자리는 한 변의 15% 까지. */
    M(0.15f),

    /** 약 25% 복원. 로고 자리는 한 변의 20% 까지. */
    Q(0.2f),

    /** 약 30% 복원. 가운데에 로고를 얹을 때 쓴다. 로고 자리는 한 변의 30% 까지. */
    H(0.3f),
}

/**
 * QR 생성 설정.
 *
 * @property errorCorrection 오류 정정 단계
 * @property margin 사방 여백(조용한 영역)의 모듈 수. 명세 권장은 4 이고, 0 이상이어야 한다
 * @property charset 바이트 모드 문자셋. UTF-8 이면 ECI 표지를 함께 넣어 읽는 쪽이 문자셋을 알게 한다.
 *   내용을 이 문자셋으로 나타낼 수 없으면 [IllegalArgumentException]
 * @property minVersion 최소 버전(1..40) 또는 null. 내용이 작아도 이 버전 이상으로 만든다 — 같은 화면의 QR 들의 크기를 맞출 때 쓴다
 * @throws IllegalArgumentException [margin] 이 음수이거나 [minVersion] 이 1..40 밖일 때 (생성 시)
 */
public data class QrCodeOptions(
    val errorCorrection: ErrorCorrection = ErrorCorrection.M,
    val margin: Int = 4,
    val charset: Charset = Charsets.UTF_8,
    val minVersion: Int? = null,
) {
    init {
        require(margin >= 0) { "margin($margin) 은 0 이상이어야 한다" }
        require(minVersion == null || minVersion in 1..40) { "minVersion($minVersion) 은 1..40 범위여야 한다" }
    }
}

/**
 * QR 코드를 만든다. 결과는 한 칸 = 한 모듈이고 [QrCodeOptions.margin] 만큼의 여백을 포함한다.
 *
 * ## 오류
 * - [IllegalArgumentException] — 빈 내용, 문자셋으로 나타낼 수 없는 내용
 * - [QrContentTooLongException]([IllegalArgumentException] 의 하위 타입) — 오류 정정 단계의 최대 용량(버전 40)을 넘는 내용
 * - [QrCodeException] — 엔진이 그 밖의 이유로 만들지 못했을 때(원인은 `cause`)
 *
 * ```
 * val matrix = QrCode.encode(QrPayload.Wifi("Home", "pw123456"), QrCodeOptions(errorCorrection = ErrorCorrection.H))
 * val pixels = matrix.scaled(8).toArgb()
 * ```
 */
public object QrCode {

    /** [text] 를 담은 QR. 내용이 들어가지 않으면 [QrContentTooLongException]. */
    @JvmStatic
    @JvmOverloads
    public fun encode(text: String, options: QrCodeOptions = QrCodeOptions()): CodeMatrix = Encoders.qr(text, options)

    /** [payload] 의 [QrPayload.encode] 를 담은 QR. 내용이 쓸 수 없는 값이면 [IllegalArgumentException]. */
    @JvmStatic
    @JvmOverloads
    public fun encode(payload: QrPayload, options: QrCodeOptions = QrCodeOptions()): CodeMatrix =
        encode(payload.encode(), options)

    /**
     * 가운데 로고 자리를 비운 QR. 그 자리([LogoQrCode.logoArea])에 앱이 로고를 그린다 — 픽셀로 합성하려면 [CodeImages.render].
     *
     * 로고 자리와 그 둘레 [QrLogoOptions.paddingModules] 칸은 밝은 칸으로 비운다(로고가 모듈과 섞여 보이지 않게).
     * 비운 칸은 오류 정정이 복원하므로 단계가 높아야 한다 — 기본은 [ErrorCorrection.H] 다.
     *
     * ## 오류
     * - [QrContentTooLongException]([QrContentTooLongException.withLogo] 참) — 내용이 그 단계에 들어가지 않을 때
     * - [IllegalArgumentException] — 비울 자리가 단계의 [ErrorCorrection.maxLogoRatio] 를 넘거나 찾기 패턴·형식 정보에 닿을 때
     *   (작은 버전에서 로고가 크면 생긴다 — `sizeRatio`·`paddingModules` 를 줄이거나 [QrCodeOptions.minVersion] 을 올린다), 그 밖의 [encode] 오류
     *
     * ```
     * val code = QrCode.encodeWithLogo("https://example.com")
     * val image = CodeImages.render(code, sizePx = 768, logo = logoArgbImage)
     * ```
     */
    @JvmStatic
    @JvmOverloads
    public fun encodeWithLogo(
        text: String,
        logo: QrLogoOptions = QrLogoOptions(),
        options: QrCodeOptions = QrCodeOptions(errorCorrection = ErrorCorrection.H),
    ): LogoQrCode = Encoders.qrWithLogo(text, logo, options)

    /**
     * [text] 가 들어가는 가장 높은 오류 정정 단계. H 부터 Q·M·L 순서로 시도한다 — 짧은 내용은 가장 튼튼한 H 로, 긴 내용만 낮은 단계로 내려가
     * 더 많이 담는다. [logo] 를 주면 그 로고 자리를 허용하는 단계만 본다([ErrorCorrection.maxLogoRatio], L 은 제외).
     * [options] 의 문자셋·최소 버전을 쓰고 `errorCorrection` 은 보지 않는다.
     *
     * 한글은 UTF-8 로 한 글자 3바이트라 H 에 약 420자, L 에 약 980자가 한계다.
     *
     * @throws QrContentTooLongException 어느 단계에도 들어가지 않을 때([QrContentTooLongException.errorCorrection] 은 시도한 가장 낮은 단계)
     * @throws IllegalArgumentException 내용이 틀렸거나(빈 내용 등), 로고 자리가 들어가는 어느 단계에서도 허용되지 않을 때
     */
    @JvmStatic
    @JvmOverloads
    public fun pickErrorCorrection(text: String, logo: QrLogoOptions? = null, options: QrCodeOptions = QrCodeOptions()): ErrorCorrection =
        Encoders.pickErrorCorrection(text, logo, options)
}
