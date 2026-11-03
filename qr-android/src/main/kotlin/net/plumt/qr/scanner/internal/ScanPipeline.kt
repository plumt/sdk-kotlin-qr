package net.plumt.qr.scanner.internal

import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.Detection
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
 * 프레임 하나의 처리 순서: 추적(이미지 좌표) → 뷰 좌표로 변환 → 화면 밖 거르기 → (Single) 가운데 고정 → (autoDeliver) 넘기기 판정.
 * 분석 스레드 하나에서만 부른다. 뷰 크기를 모르면(0) 추적만 하고 화면에는 아무것도 내지 않는다.
 */
internal class ScanPipeline(private val mode: ScanMode, private val options: ScannerOptions) {

    private val tracker = CodeTracker(options.tracking)
    private val selector = LockSelector(options.lock)
    private val gate = AutoDeliverGate(options.autoDeliver)

    fun reset() {
        tracker.reset()
        selector.reset()
        gate.reset()
    }

    fun process(
        detections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        timestampNanos: Long,
    ): ScanSnapshot {
        val tracks = tracker.update(detections, imageWidth, imageHeight)
        gate.retain(tracks.mapTo(HashSet()) { it.id })
        val nowMillis = timestampNanos / NANOS_PER_MILLI
        if (viewWidth <= 0f || viewHeight <= 0f || !viewWidth.isFinite() || !viewHeight.isFinite()) {
            selector.reset()
            gate.offer(null, null, nowMillis)
            return EMPTY
        }
        val transform = PreviewTransform(imageWidth, imageHeight, viewWidth, viewHeight, options.scaleMode, options.lens.mirrored)
        val visible = tracks.mapNotNull { t ->
            val b = transform.mapBounds(t.bounds)
            if (transform.isVisible(b)) t to b else null
        }

        return when (mode) {
            ScanMode.Multi -> ScanSnapshot(
                codes = visible.map { (t, b) -> TrackedCode(t.id, t.result, b, t.missedFrames, isLocked = false) },
                locked = null,
                deliver = null,
            )
            is ScanMode.Single -> {
                val lockedId = selector.select(
                    visible.map { (t, b) -> LockCandidate(t.id, b, fresh = t.missedFrames == 0) },
                    viewWidth,
                    viewHeight,
                )
                val codes = visible.map { (t, b) -> TrackedCode(t.id, t.result, b, t.missedFrames, isLocked = t.id == lockedId) }
                val locked = codes.firstOrNull { it.isLocked }
                val deliver = if (mode.autoDeliver && gate.offer(locked?.id, locked?.result?.text, nowMillis)) locked?.result else null
                ScanSnapshot(codes, locked, deliver)
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val EMPTY = ScanSnapshot(emptyList(), null, null)
    }
}

internal val net.plumt.qr.scanner.CameraLens.mirrored: Boolean get() = this == net.plumt.qr.scanner.CameraLens.FRONT
