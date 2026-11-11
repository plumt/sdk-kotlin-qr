package net.plumt.qr.scanner.internal

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import net.plumt.qr.scanner.Detection
import net.plumt.qr.scanner.FrameListener
import net.plumt.qr.scanner.ScannerException
import net.plumt.qr.scanner.ScanEngine
import net.plumt.qr.scanner.ScanFrame

/**
 * CameraX 분석기. 프레임마다 엔진 → 파이프라인을 돌리고 결과를 [sink] 로 낸다. 분석 전용 단일 스레드에서 불린다.
 *
 * - 프레임은 엔진이 끝나면 바로 반납한다(반납 지점은 `finally` 하나). 반납 전에 예외가 새도 분석이 멈추지 않는다
 * - 엔진 실패는 그 프레임만 건너뛴다(추적 나이를 늘리지 않는다). 원인은 이 분석기에서 처음 한 건만 [onError] 로 낸다 —
 *   같은 원인이 매 프레임 반복되기 때문이다
 * - [paused] 가 true 면 엔진을 부르지 않고 반납만 한다
 * - 엔진이 성공한 프레임은 반납 전에 [frameListener] 에게도 보여 준다. 수신자 예외는 [onError] 로 내고 분석은 계속한다
 * - [close] 뒤에 온 프레임은 반납만 한다. [close] 가 돌아온 뒤에는 엔진 안에 있던 프레임도 결과를 내지 않는다 —
 *   파이프라인 처리와 [sink] 를 [pipeline] 잠금 안에서 하고, [close] 도 같은 잠금을 잡는다. 그래서 세션이 끝나 상태가 비워진 뒤
 *   옛 박스가 다시 실리지 않고, 같은 파이프라인을 새 세션의 분석기와 동시에 만지지 않는다
 */
internal class FrameAnalyzer(
    private val engine: ScanEngine,
    private val pipeline: ScanPipeline,
    private val viewSize: () -> Pair<Float, Float>,
    private val paused: () -> Boolean,
    private val consumeReset: () -> Boolean,
    private val sink: (ScanSnapshot) -> Unit,
    private val onError: (ScannerException) -> Unit,
    private val frameListener: () -> FrameListener? = { null },
) : ImageAnalysis.Analyzer {

    @Volatile
    private var closed = false
    private var errorReported = false

    override fun analyze(image: ImageProxy) {
        val work = try {
            if (closed || paused()) {
                null
            } else {
                val frame = ScanFrame.of(image)
                // 수신자는 반납 전에 부른다 — 밝기 평면을 읽으려면 버퍼가 열려 있어야 한다
                detectSafely(frame)?.also { notifyListener(frame, it) }?.let { frame to it }
            }
        } finally {
            image.close()
        }
        // 반납 뒤에는 프레임의 크기·시각만 읽는다(밝기 평면은 읽지 않는다)
        if (work != null) publish(work.second, work.first)
    }

    /** CameraX 를 거치지 않은 프레임(직접 넣는 프레임)을 처리한다. 반납할 것이 없다. */
    fun analyze(frame: ScanFrame) {
        if (closed || paused()) return
        val detections = detectSafely(frame) ?: return
        notifyListener(frame, detections)
        publish(detections, frame)
    }

    private fun detectSafely(frame: ScanFrame): List<Detection>? = try {
        engine.detect(frame)
    } catch (e: ScannerException) {
        report(e)
        null
    } catch (e: RuntimeException) {
        report(ScannerException("인식 엔진이 예기치 못하게 실패했다", e))
        null
    }

    private fun notifyListener(frame: ScanFrame, detections: List<Detection>) {
        val listener = frameListener() ?: return
        try {
            listener.onFrame(frame, detections)
        } catch (e: RuntimeException) {
            report(ScannerException("프레임 수신자가 실패했다", e))
        }
    }

    private fun publish(detections: List<Detection>, frame: ScanFrame) {
        synchronized(pipeline) {
            // 엔진이 도는 사이 닫혔으면 결과를 버린다
            if (closed) return
            if (consumeReset()) pipeline.reset()
            val (w, h) = viewSize()
            val snapshot = try {
                pipeline.process(detections, frame.uprightWidth, frame.uprightHeight, w, h, frame.timestampNanos)
            } catch (e: CustomLockFailure) {
                report(ScannerException("고정 선택 함수(MultiLock.Custom)가 실패했다", e.cause))
                return
            }
            sink(snapshot)
        }
    }

    private fun report(e: ScannerException) {
        if (errorReported) return
        errorReported = true
        onError(e)
    }

    /** 닫는다. 다른 스레드에서 결과를 내는 중이면 그것이 끝날 때까지 기다린다(파이프라인 처리만 — 엔진은 기다리지 않는다). */
    fun close() {
        synchronized(pipeline) { closed = true }
    }
}
