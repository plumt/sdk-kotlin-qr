package net.plumt.qr

import net.plumt.qr.internal.Encoders
import java.nio.charset.Charset

/** QR 오류 정정 단계. 높을수록 가려지거나 더러워져도 읽히지만 담는 양이 줄어든다. */
public enum class ErrorCorrection {
    /** 약 7% 복원. */
    L,

    /** 약 15% 복원. 일반 용도의 기본값이다. */
    M,

    /** 약 25% 복원. */
    Q,

    /** 약 30% 복원. 가운데에 로고를 얹을 때 쓴다. */
    H,
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
 * - [IllegalArgumentException] — 빈 내용, 문자셋으로 나타낼 수 없는 내용, 오류 정정 단계의 최대 용량(버전 40)을 넘는 내용
 * - [QrCodeException] — 엔진이 그 밖의 이유로 만들지 못했을 때(원인은 `cause`)
 *
 * ```
 * val matrix = QrCode.encode(QrPayload.Wifi("Home", "pw123456"), QrCodeOptions(errorCorrection = ErrorCorrection.H))
 * val pixels = matrix.scaled(8).toArgb()
 * ```
 */
public object QrCode {

    /** [text] 를 담은 QR. */
    @JvmStatic
    @JvmOverloads
    public fun encode(text: String, options: QrCodeOptions = QrCodeOptions()): CodeMatrix = Encoders.qr(text, options)

    /** [payload] 의 [QrPayload.encode] 를 담은 QR. 내용이 쓸 수 없는 값이면 [IllegalArgumentException]. */
    @JvmStatic
    @JvmOverloads
    public fun encode(payload: QrPayload, options: QrCodeOptions = QrCodeOptions()): CodeMatrix =
        encode(payload.encode(), options)
}
