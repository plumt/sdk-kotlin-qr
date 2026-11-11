package net.plumt.qr.scanner.internal

import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.Detection
import net.plumt.qr.scanner.MultiLock
import net.plumt.qr.scanner.ScannerOptions
import net.plumt.qr.scanner.ScanMode
import net.plumt.qr.scanner.TrackedCode

/** 한 프레임을 처리한 결과. */
internal data class ScanSnapshot(
    val codes: List<TrackedCode>,
    val locked: TrackedCode?,
    val deliver: ScanResult?,
)

/**
 * 프레임 하나의 처리 순서: 추적(이미지 좌표) → 뷰 좌표로 변환 → 화면 밖 거르기 → 고정 선택([LockController]) → (autoDeliver) 넘기기 판정.
 * 분석 스레드 하나에서만 부른다([pin]·[release] 는 분석기와 같은 잠금 — 이 객체 — 안에서 부른다).
 * 뷰 크기를 모르면(0) 추적만 하고 화면에는 아무것도 내지 않는다.
 */
internal class ScanPipeline(mode: ScanMode, private val options: ScannerOptions) {

    /** 모드. [MultiLock.Custom] 의 함수만 바뀐 모드로 바꿀 수 있다(다음 프레임부터 쓴다). */
    @Volatile
    var mode: ScanMode = mode

    private val tracker = CodeTracker(options.tracking)
    private val lock = LockController(options.lock)
    private val gate = AutoDeliverGate(options.autoDeliver)

    fun reset() {
        tracker.reset()
        lock.reset()
        gate.reset()
    }

    /** 앱이 [id] 에 고정한다. */
    fun pin(id: Long) = lock.pin(id)

    /** 앱이 고정을 푼다. */
    fun release() = lock.release()

    fun process(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        timestampNanos: Long,
    ): ScanSnapshot {
        val mode = mode
        val tracks = tracker.update(detections, imageWidth, imageHeight)
        gate.retain(tracks.mapTo(HashSet()) { it.id })
        val nowMillis = timestampNanos / NANOS_PER_MILLI
        if (viewWidth <= 0f || viewHeight <= 0f || !viewWidth.isFinite() || !viewHeight.isFinite()) {
            lock.reset()
            gate.offer(null, null, nowMillis)
            return EMPTY
        }
        val transform = PreviewTransform(imageWidth, imageHeight, viewWidth, viewHeight, options.scaleMode, options.lens.mirrored)
        val visible = tracks.mapNotNull { t ->
            val b = transform.mapBounds(t.bounds)
            if (transform.isVisible(b)) TrackedCode(t.id, t.result, b, t.missedFrames, isLocked = false) else null
        }

        val lockedId = lock.select(mode.lockPolicy, visible, viewWidth, viewHeight)
        val codes = if (lockedId == null) visible else visible.map {
            if (it.id == lockedId) TrackedCode(it.id, it.result, it.bounds, it.missedFrames, isLocked = true) else it
        }
        val locked = codes.firstOrNull { it.isLocked }
        val deliver = if (mode.autoDeliverEnabled && gate.offer(locked?.id, locked?.result?.text, nowMillis)) locked?.result else null
        return ScanSnapshot(codes, locked, deliver)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val EMPTY = ScanSnapshot(emptyList(), null, null)
    }
}

internal val net.plumt.qr.scanner.CameraLens.mirrored: Boolean get() = this == net.plumt.qr.scanner.CameraLens.FRONT
