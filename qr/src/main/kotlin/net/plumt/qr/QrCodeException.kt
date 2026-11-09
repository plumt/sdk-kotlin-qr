package net.plumt.qr

/**
 * 인자는 올바른데 코드 생성·읽기 엔진이 일을 끝내지 못했을 때 던진다.
 *
 * 엔진 내부의 예외는 이 타입으로 감싸 [cause] 에 원인을 담는다.
 * 인자가 틀린 경우(빈 내용, 체크 숫자가 틀린 상품 번호, 용량을 넘는 내용, 배열 크기 불일치 등)는
 * 이 타입이 아니라 [IllegalArgumentException] 이다. 이미지에 코드가 없는 것은 예외가 아니라 값(null·빈 목록)으로 알린다.
 */
public class QrCodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 내용이 QR 한 장(버전 40, 또는 [QrCodeOptions.minVersion] 이 정한 범위)에 다 들어가지 않는다.
 *
 * 사용자가 내용을 줄여 고칠 입력 문제라 [IllegalArgumentException] 이다 — 크래시 보고 대상으로 다루지 않고 화면에 알리면 된다.
 * [QrCode.encode]·[QrCode.encodeWithLogo]·[QrCode.pickErrorCorrection]·`Barcode.encode(.., QR_CODE)` 가 던진다.
 *
 * @property errorCorrection 마지막으로 시도한 오류 정정 단계. [QrCode.pickErrorCorrection] 이면 허용된 가장 낮은 단계다
 * @property withLogo 로고 자리를 비우는 생성이었는지. 참이면 로고를 빼거나 더 작게 하면 들어갈 수 있다
 */
public class QrContentTooLongException internal constructor(
    message: String,
    public val errorCorrection: ErrorCorrection,
    public val withLogo: Boolean,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
