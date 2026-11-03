package net.plumt.qr.scanner

import net.plumt.qr.CodeFormat
import java.time.ZoneId

private const val MAX_MISSED = 1000
private const val MAX_SWITCH = 1000

/**
 * 프레임 사이 추적 설정.
 *
 * @property maxMissedFrames 이 수만큼 연속으로 놓치면 지운다. 0 이면 놓친 즉시 지운다. 손떨림으로 한두 프레임을 놓쳐도 칩이 깜빡이지 않게 기본 5
 * @property smoothing 박스 위치를 얼마나 부드럽게 따라갈지 0..1 미만. 0 이면 매 프레임 새 위치로 바로 옮기고, 클수록 천천히 따라간다
 * @property iouThreshold 같은 원문의 코드를 같은 코드로 이어 볼 겹침(IoU) 하한 0..1. 겹침이 이보다 작아도 가운데 거리가 코드 크기 안이면 잇는다
 * @throws IllegalArgumentException 범위 밖 값 (생성 시)
 */
public data class TrackingOptions(
    val maxMissedFrames: Int = 5,
    val smoothing: Float = 0.5f,
    val iouThreshold: Float = 0.3f,
) {
    init {
        require(maxMissedFrames in 0..MAX_MISSED) { "maxMissedFrames($maxMissedFrames) 는 0..$MAX_MISSED 범위여야 한다" }
        require(smoothing >= 0f && smoothing < 1f) { "smoothing($smoothing) 은 0 이상 1 미만이어야 한다" }
        require(iouThreshold in 0f..1f) { "iouThreshold($iouThreshold) 는 0..1 범위여야 한다" }
    }

}

/**
 * 가운데 고정([ScanMode.Single]) 설정.
 *
 * @property hysteresis 다른 코드로 옮겨 가려면 지금 고정된 코드보다 가운데에 얼마나 더 가까워야 하는지 — 뷰 대각선에 대한 비율 0..1
 * @property switchFrames 더 가까운 코드가 이 수만큼 연속 프레임에서 앞서야 옮긴다. 1 이면 한 프레임으로 옮긴다
 * @throws IllegalArgumentException 범위 밖 값 (생성 시)
 */
public data class LockOptions(
    val hysteresis: Float = 0.1f,
    val switchFrames: Int = 3,
) {
    init {
        require(hysteresis in 0f..1f) { "hysteresis($hysteresis) 는 0..1 범위여야 한다" }
        require(switchFrames in 1..MAX_SWITCH) { "switchFrames($switchFrames) 는 1..$MAX_SWITCH 범위여야 한다" }
    }

}

/**
 * 탭 없이 바로 넘기기([ScanMode.Single.autoDeliver]) 설정. 시각은 카메라 프레임의 시각이다(벽시계가 아니다).
 *
 * @property stableMillis 같은 코드에 이만큼 고정돼 있어야 넘긴다(흔들리며 스친 코드를 넘기지 않게). 0 이면 고정 즉시
 * @property repeatWindowMillis 같은 원문은 마지막으로 넘긴 뒤 이 시간 안에 다시 넘기지 않는다. 같은 추적 번호는 시간과 무관하게 한 번만 넘긴다
 * @throws IllegalArgumentException 음수 (생성 시)
 */
public data class AutoDeliverOptions(
    val stableMillis: Long = 300,
    val repeatWindowMillis: Long = 2000,
) {
    init {
        require(stableMillis >= 0) { "stableMillis($stableMillis) 는 0 이상이어야 한다" }
        require(repeatWindowMillis >= 0) { "repeatWindowMillis($repeatWindowMillis) 는 0 이상이어야 한다" }
    }
}

/** 분석 해상도. 미리보기와 같은 4:3 비율로 잡는다(박스 좌표가 맞으려면 두 스트림의 비율이 같아야 한다). */
public enum class AnalysisResolution {
    /** 1차원 바코드 형식이 하나라도 있으면 [HIGH], 아니면 [STANDARD]. 기본. */
    AUTO,

    /** 약 640x480. QR 에 충분하고 가볍다. */
    STANDARD,

    /** 약 1280x960. 막대가 가는 1차원 바코드를 조금 떨어져서도 읽는다. */
    HIGH,
}

/**
 * 스캐너 설정.
 *
 * @property formats 찾을 형식. 좁힐수록 빠르고 오인식이 준다. 기본 QR 만
 * @property engine 인식 엔진. 기본은 ML Kit([ScanEngines.mlKit]). Google 라이브러리 없이 쓰려면 [ScanEngines.zxing]
 * @property lens 후면·전면
 * @property scaleMode 미리보기 맞춤 방식
 * @property tracking 프레임 사이 추적
 * @property lock 가운데 고정
 * @property autoDeliver 탭 없이 넘기기
 * @property analysisResolution 분석 해상도
 * @property pinchToZoom 두 손가락으로 확대
 * @property tapToFocus 코드가 아닌 곳을 탭하면 그 자리에 초점·노출을 맞춘다
 * @property zone 일정 요약을 보일 시간대
 * @throws IllegalArgumentException [formats] 가 비었을 때 (생성 시)
 */
public data class ScannerOptions(
    val formats: Set<CodeFormat> = CodeFormat.QR_ONLY,
    val engine: ScanEngine.Factory = ScanEngines.mlKit(),
    val lens: CameraLens = CameraLens.BACK,
    val scaleMode: PreviewScaleMode = PreviewScaleMode.FILL,
    val tracking: TrackingOptions = TrackingOptions(),
    val lock: LockOptions = LockOptions(),
    val autoDeliver: AutoDeliverOptions = AutoDeliverOptions(),
    val analysisResolution: AnalysisResolution = AnalysisResolution.AUTO,
    val pinchToZoom: Boolean = true,
    val tapToFocus: Boolean = true,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    init {
        require(formats.isNotEmpty()) { "formats 가 비어 있다 — 아무것도 읽지 않는 설정은 호출부 실수다" }
    }

    /** 실제로 쓸 분석 해상도. [AnalysisResolution.AUTO] 를 풀어 낸다. */
    public val effectiveResolution: AnalysisResolution
        get() = when (analysisResolution) {
            AnalysisResolution.AUTO ->
                if (formats.any { it.kind != CodeFormat.Kind.MATRIX }) AnalysisResolution.HIGH else AnalysisResolution.STANDARD
            else -> analysisResolution
        }
}
