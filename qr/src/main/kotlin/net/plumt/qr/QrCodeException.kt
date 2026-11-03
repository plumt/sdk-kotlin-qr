package net.plumt.qr

/**
 * 인자는 올바른데 코드 생성·읽기 엔진이 일을 끝내지 못했을 때 던진다.
 *
 * 엔진 내부의 예외는 이 타입으로 감싸 [cause] 에 원인을 담는다.
 * 인자가 틀린 경우(빈 내용, 체크 숫자가 틀린 상품 번호, 용량을 넘는 내용, 배열 크기 불일치 등)는
 * 이 타입이 아니라 [IllegalArgumentException] 이다. 이미지에 코드가 없는 것은 예외가 아니라 값(null·빈 목록)으로 알린다.
 */
public class QrCodeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
