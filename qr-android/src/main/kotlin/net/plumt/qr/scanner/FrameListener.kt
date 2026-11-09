package net.plumt.qr.scanner

/**
 * 스캐너가 본 카메라 프레임을 앱에도 보여 준다. 같은 카메라로 글자 인식 같은 다른 분석을 함께 돌릴 때 쓴다.
 *
 * - 분석 전용 스레드에서, 엔진이 그 프레임의 코드를 찾은 직후 한 번 불린다. 일시정지 중이거나 엔진이 실패한 프레임은 오지 않는다
 * - [onFrame] 이 돌아올 때까지 다음 프레임 분석이 기다린다. 무거운 일은 [ScanFrame.luminance] 를 받아 다른 스레드로 넘긴다
 * - [ScanFrame.luminance] 는 [onFrame] 안에서만 읽을 수 있다(그 뒤에는 카메라가 버퍼를 되가져간다). 한 번 받은 배열은 프레임마다 새로
 *   만든 복사본이라 [onFrame] 이 끝난 뒤에도 써도 된다. 고치지는 않는다
 * - [onFrame] 이 던진 예외는 분석을 멈추지 않는다. [ScannerException] 으로 감싸 스캐너 오류로 한 번만 알린다
 */
public fun interface FrameListener {

    /**
     * @param frame 분석한 프레임(회전 전 버퍼)
     * @param detections 이 프레임에서 엔진이 찾은 코드. 좌표는 바로 세운 이미지 기준이다
     */
    public fun onFrame(frame: ScanFrame, detections: List<Detection>)
}
