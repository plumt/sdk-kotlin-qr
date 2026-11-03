package net.plumt.qr.scanner

/**
 * 스캐너가 일을 끝내지 못했을 때의 예외 하나. 원인은 언제나 [cause] 에 담는다.
 *
 * - 카메라를 열지 못함([CameraStatus.Error])
 * - 인식 엔진을 만들지 못함(ML Kit 의존이 빠짐·초기화 실패), 엔진이 프레임을 처리하다 실패함
 * - 실행([net.plumt.qr.scanner.action.QrActionResult.Failed])이 처리기 없음 외의 이유로 실패함
 *
 * 인자가 틀린 경우는 이 타입이 아니라 [IllegalArgumentException] 이다. 메시지에 코드 원문·비밀번호를 넣지 않는다.
 */
public class ScannerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
