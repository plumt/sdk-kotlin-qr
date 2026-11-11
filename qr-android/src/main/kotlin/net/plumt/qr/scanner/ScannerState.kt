package net.plumt.qr.scanner

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.internal.BoxMath
import net.plumt.qr.scanner.internal.FrameAnalyzer
import net.plumt.qr.scanner.internal.ScanPipeline
import net.plumt.qr.scanner.internal.ScanSnapshot

private const val DELIVERY_BUFFER = 8

/**
 * 스캐너 상태와 조작. [CodeScanner] 가 쓰는 것과 같은 상태를 직접 그리고 싶을 때(직접 만든 오버레이·칩) [rememberScannerState] 로 만들어
 * [CodeScannerPreview] 와 함께 쓴다.
 *
 * - 모든 값은 [StateFlow] 다. 어느 스레드에서 읽어도 된다. 분석 스레드가 값을 쓴다
 * - 조작 함수([setTorchEnabled]·[setZoomRatio]·[focusAt] 등)는 메인 스레드에서 부른다
 * - 카메라가 열리기 전·닫힌 뒤의 조작은 아무것도 하지 않고 false 를 돌려준다
 *
 * 테스트 대역: 내부 생성자가 권한 확인 함수를 받는다 — 공개 생성은 [rememberScannerState] 뿐이다.
 */
public class ScannerState internal constructor(
    mode: ScanMode,
    options: ScannerOptions,
    private val permissionGranted: () -> Boolean,
) {
    /**
     * 고르는 방식. [rememberScannerState] 가 [MultiLock.Custom] 의 함수만 다른 모드를 받으면 상태를 새로 만들지 않고 이 값만 바꾼다.
     */
    @Volatile
    public var mode: ScanMode = mode
        private set

    /**
     * 설정. [rememberScannerState] 가 엔진 팩토리만 다른 설정을 받으면 상태를 새로 만들지 않고 이 값만 바꾼다 —
     * 새 엔진은 다음 카메라 세션부터 쓴다.
     */
    @Volatile
    public var options: ScannerOptions = options
        private set

    private val _codes = MutableStateFlow<List<TrackedCode>>(emptyList())
    private val _locked = MutableStateFlow<TrackedCode?>(null)
    private val _status = MutableStateFlow(if (permissionGranted()) CameraStatus.Starting else CameraStatus.PermissionRequired)
    private val _torch = MutableStateFlow(TorchState(available = false, enabled = false))
    private val _zoom = MutableStateFlow(ZoomState(1f, 1f, 1f))
    private val _paused = MutableStateFlow(false)
    private val _error = MutableStateFlow<ScannerException?>(null)
    private val _deliveries = MutableSharedFlow<ScanResult>(extraBufferCapacity = DELIVERY_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 보이는 코드 전부(추적 번호 순). [ScanMode.Single] 에서도 고정되지 않은 코드가 들어 있다(흐리게 그릴 때 쓴다). */
    public val codes: StateFlow<List<TrackedCode>> = _codes.asStateFlow()

    /** 고정(lock-on)된 코드. [codes] 중 `isLocked` 인 것과 같다. 없으면 null. [ScanMode.Multi] 는 [MultiLock] 정책·[lockOn] 으로 고정된다. */
    public val lockedCode: StateFlow<TrackedCode?> = _locked.asStateFlow()

    /** 카메라 상태. 권한이 없으면 [CameraStatus.PermissionRequired]. */
    public val cameraStatus: StateFlow<CameraStatus> = _status.asStateFlow()

    /** 플래시. */
    public val torch: StateFlow<TorchState> = _torch.asStateFlow()

    /** 확대. */
    public val zoom: StateFlow<ZoomState> = _zoom.asStateFlow()

    /** 인식을 멈췄는가. 멈춘 동안 미리보기는 돌고 프레임은 버린다. */
    public val isPaused: StateFlow<Boolean> = _paused.asStateFlow()

    /** 마지막 오류(카메라·엔진). 엔진 오류는 카메라 세션마다 처음 한 건만 온다. 지우려면 [clearError]. */
    public val error: StateFlow<ScannerException?> = _error.asStateFlow()

    /**
     * 자동 넘기기([ScanMode.Single.autoDeliver]·[ScanMode.Multi.autoDeliver])로 넘긴 결과. 구독자가 없을 때 넘긴 것은 버린다(나중에 다시 내지 않는다).
     * 구독자가 느리면 최근 8개까지 쌓고 오래된 것부터 버린다.
     * [CodeScanner] 는 이것을 `onCodeTap` 으로 넘긴다.
     */
    public val deliveries: SharedFlow<ScanResult> = _deliveries.asSharedFlow()

    private val pipeline = ScanPipeline(mode, options)

    @Volatile
    private var viewWidth = 0f

    @Volatile
    private var viewHeight = 0f

    @Volatile
    private var camera: CameraHandle? = null
    private var analyzer: FrameAnalyzer? = null

    // 카메라 세션 번호. 바뀌면 미리보기가 카메라를 다시 묶는다(retry)
    private val _session = MutableStateFlow(0)
    internal val session: StateFlow<Int> = _session.asStateFlow()

    /**
     * 권한을 다시 읽는다. [rememberScannerState] 로 만든 상태는 화면이 다시 보일 때(ON_RESUME — 시스템 권한 창이 닫힐 때 포함)
     * 스스로 부르므로, 앱은 권한 요청 결과 콜백 등에서 바로 반영하고 싶을 때만 부른다.
     * @return 권한이 있는가
     */
    public fun refreshPermission(): Boolean {
        val granted = permissionGranted()
        val now = _status.value
        if (!granted) {
            _status.value = CameraStatus.PermissionRequired
        } else if (now == CameraStatus.PermissionRequired) {
            _status.value = CameraStatus.Starting
        }
        return granted
    }

    /**
     * 플래시를 켜고 끈다. @return 카메라가 열려 있고 플래시가 있어 요청을 보냈으면 true
     */
    public fun setTorchEnabled(enabled: Boolean): Boolean {
        val c = camera ?: return false
        if (!c.hasFlash) return false
        c.enableTorch(enabled)
        return true
    }

    /**
     * 확대 배율을 정한다. [ZoomState.min]..[ZoomState.max] 로 잘라 보낸다.
     * @return 카메라가 열려 있어 요청을 보냈으면 true
     * @throws IllegalArgumentException [ratio] 가 유한한 양수가 아닐 때
     */
    public fun setZoomRatio(ratio: Float): Boolean {
        require(ratio.isFinite() && ratio > 0f) { "ratio($ratio) 는 유한한 양수여야 한다" }
        val c = camera ?: return false
        val z = _zoom.value
        c.setZoomRatio(ratio.coerceIn(z.min, z.max))
        return true
    }

    /**
     * 지금 배율에 [factor] 를 곱한다(두 손가락 확대에서 한 번의 몸짓 배율).
     * @throws IllegalArgumentException [factor] 가 유한한 양수가 아닐 때
     */
    public fun zoomBy(factor: Float): Boolean {
        require(factor.isFinite() && factor > 0f) { "factor($factor) 는 유한한 양수여야 한다" }
        return setZoomRatio(_zoom.value.ratio * factor)
    }

    /**
     * 뷰 좌표 한 점에 초점·노출을 맞춘다.
     * @return 카메라가 열려 있어 요청을 보냈으면 true
     * @throws IllegalArgumentException 좌표가 유한하지 않을 때
     */
    public fun focusAt(x: Float, y: Float): Boolean {
        require(x.isFinite() && y.isFinite()) { "초점 좌표($x, $y)는 유한해야 한다" }
        val c = camera ?: return false
        c.focus(x, y)
        return true
    }

    /**
     * 카메라 열기를 다시 시도한다. [CameraStatus.Error]·[CameraStatus.NoCamera] 일 때만 동작한다 — 다른 앱이 카메라를 잡고 있다
     * 놓은 경우처럼 일시적인 실패에서 화면을 다시 들어오지 않고 복구할 때 쓴다. 메인 스레드에서 부른다.
     * @return 다시 열기를 시작했으면 true. 권한이 없으면 [CameraStatus.PermissionRequired] 로 바꾸고 false
     */
    public fun retry(): Boolean {
        val now = _status.value
        if (now !is CameraStatus.Error && now != CameraStatus.NoCamera) return false
        if (!permissionGranted()) {
            _status.value = CameraStatus.PermissionRequired
            return false
        }
        _status.value = CameraStatus.Starting
        _session.value = _session.value + 1
        return true
    }

    /**
     * 인식을 멈추거나 다시 시작한다. 다시 시작하면 추적·고정은 처음부터 쌓는다. 자동 넘기기의 같은 원문 반복 창
     * ([AutoDeliverOptions.repeatWindowMillis])은 이어진다 — 넘기기 → 멈춤 → 결과 창 닫기 → 재개 흐름에서 화면에 남은 같은 코드를
     * 곧바로 다시 넘기지 않게.
     */
    public fun setPaused(paused: Boolean) {
        if (_paused.value == paused) return
        _paused.value = paused
        if (paused) {
            _codes.value = emptyList()
            _locked.value = null
        } else {
            resetOnAnalysisThread = true
        }
    }

    @Volatile
    private var frameListener: FrameListener? = null

    /**
     * 분석한 프레임을 받을 수신자를 건다. null 이면 뗀다. 돌고 있는 세션에도 다음 프레임부터 적용된다.
     * `CodeScanner` 의 `onFrame` 을 쓰면 화면 수명에 맞춰 자동으로 걸고 뗀다.
     */
    public fun setFrameListener(listener: FrameListener?) {
        frameListener = listener
    }

    /** [error] 를 비운다. */
    public fun clearError() {
        _error.value = null
    }

    /**
     * 뷰 좌표 한 점에 있는 코드. 박스 여럿이 겹치면 가운데가 가장 가까운 것이다. 없으면 null.
     */
    public fun codeAt(x: Float, y: Float): TrackedCode? =
        _codes.value.filter { it.bounds.contains(x, y) }
            .minByOrNull { BoxMath.distance(it.bounds.centerX, it.bounds.centerY, x, y) }

    /**
     * 추적 번호 [id] 의 코드에 고정한다. 모드·[MultiLock] 정책보다 우선하고, 그 코드가 화면에서 사라지면(추적에서 지워지거나 화면 밖)
     * 풀린 뒤 정책대로 다시 고른다. [codes]·[lockedCode] 는 바로 바뀐다. 메인 스레드에서 부른다.
     *
     * 고정된 코드는 자동 넘기기가 켜져 있으면 규칙대로 넘긴다 — 같은 추적 번호는 한 번만 넘기므로 풀었다 다시 고정해도 다시 넘기지 않는다.
     * @return [codes] 에 그 번호가 있어 고정했으면 true. 없거나 멈춘 상태면 false
     */
    public fun lockOn(id: Long): Boolean = synchronized(pipeline) {
        if (_paused.value) return false
        val now = _codes.value
        if (now.none { it.id == id }) return false
        pipeline.pin(id)
        publishLock(now, id)
        true
    }

    /**
     * 고정을 푼다([lockOn] 고정과 정책 고정 모두). [codes]·[lockedCode] 는 바로 바뀐다. 메인 스레드에서 부른다.
     * 스스로 고르는 정책([ScanMode.Single]·[MultiLock.Nearest]·[MultiLock.Custom])은 다음 프레임에 다시 고른다 —
     * 고정 없이 두려면 [MultiLock.None]·[MultiLock.Tap] 을 쓴다.
     * @return 고정된 코드가 있었으면 true
     */
    public fun unlock(): Boolean = synchronized(pipeline) {
        val had = _locked.value != null
        pipeline.release()
        publishLock(_codes.value, null)
        had
    }

    private fun publishLock(now: List<TrackedCode>, id: Long?) {
        val codes = now.map {
            val locked = it.id == id
            if (it.isLocked == locked) it else TrackedCode(it.id, it.result, it.bounds, it.missedFrames, locked)
        }
        _codes.value = codes
        _locked.value = codes.firstOrNull { it.isLocked }
    }

    // ---- 내부: 카메라 바인더·미리보기가 부른다 ----

    @Volatile
    private var resetOnAnalysisThread = false

    /** 엔진 팩토리만 바뀐 설정으로 바꾼다(다음 세션부터 쓴다). 그 밖의 값이 다르면 [IllegalArgumentException]. */
    internal fun updateEngine(newOptions: ScannerOptions) {
        require(newOptions.copy(engine = options.engine) == options) { "엔진 밖의 설정이 바뀌면 상태를 새로 만든다" }
        options = newOptions
    }

    /** [MultiLock.Custom] 의 함수만 바뀐 모드로 바꾼다(다음 프레임부터 쓴다). 그 밖의 값이 다르면 [IllegalArgumentException]. */
    internal fun updateMode(newMode: ScanMode) {
        require(newMode.rememberKey() == mode.rememberKey()) { "고정 선택 함수 밖의 모드가 바뀌면 상태를 새로 만든다" }
        mode = newMode
        pipeline.mode = newMode
    }

    internal fun onViewSize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
    }

    internal fun newAnalyzer(engine: ScanEngine): FrameAnalyzer {
        analyzer?.close()
        resetOnAnalysisThread = true
        return FrameAnalyzer(
            engine = engine,
            pipeline = pipeline,
            viewSize = { viewWidth to viewHeight },
            paused = { _paused.value },
            consumeReset = ::consumeResetFlag,
            sink = ::onSnapshot,
            onError = ::onError,
            frameListener = { frameListener },
        ).also { analyzer = it }
    }

    internal fun onSnapshot(snapshot: ScanSnapshot) {
        if (_paused.value) return
        _codes.value = snapshot.codes
        _locked.value = snapshot.locked
        snapshot.deliver?.let { _deliveries.tryEmit(it) }
    }

    /** 분석 스레드가 프레임마다 묻는다 — true 면 그 스레드에서 파이프라인 기록을 지운다(재시작·새 세션 뒤 첫 프레임). */
    internal fun consumeResetFlag(): Boolean {
        if (!resetOnAnalysisThread) return false
        resetOnAnalysisThread = false
        return true
    }

    internal fun onError(error: ScannerException) {
        _error.value = error
    }

    internal fun onCameraOpened(handle: CameraHandle) {
        camera = handle
        _status.value = CameraStatus.Running
        _torch.value = TorchState(available = handle.hasFlash, enabled = false)
    }

    internal fun onCameraError(error: ScannerException) {
        camera = null
        _status.value = CameraStatus.Error(error)
        _error.value = error
    }

    internal fun onNoCamera() {
        camera = null
        _status.value = CameraStatus.NoCamera
    }

    internal fun onTorchChanged(enabled: Boolean) {
        _torch.value = _torch.value.copy(enabled = enabled)
    }

    internal fun onZoomChanged(ratio: Float, min: Float, max: Float) {
        _zoom.value = ZoomState(ratio, min, max)
    }

    internal fun onCameraClosed() {
        camera = null
        analyzer?.close()
        analyzer = null
        _codes.value = emptyList()
        _locked.value = null
        _torch.value = TorchState(available = false, enabled = false)
        if (_status.value == CameraStatus.Running) {
            _status.value = if (permissionGranted()) CameraStatus.Starting else CameraStatus.PermissionRequired
        }
    }

}

/** 열린 카메라에 보내는 조작. CameraX 구현은 바인더에 있고, 테스트는 대역을 쓴다. */
internal interface CameraHandle {
    val hasFlash: Boolean
    fun enableTorch(enabled: Boolean)
    fun setZoomRatio(ratio: Float)
    fun focus(viewX: Float, viewY: Float)
}
