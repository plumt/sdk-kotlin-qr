package net.plumt.qr.scanner

import android.os.Looper
import net.plumt.qr.scanner.internal.AnalysisExecutor
import net.plumt.qr.scanner.internal.FrameAnalyzer
import net.plumt.qr.scanner.internal.ScanPipeline
import net.plumt.qr.scanner.internal.ScanSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScanFrameTest {

    @Test
    fun publicConstructor_validates() {
        assertTrue(assertThrows<IllegalArgumentException> { ScanFrame(ByteArray(10), 0, 1) }.message!!.contains("0x1"))
        assertThrows<IllegalArgumentException> { ScanFrame(ByteArray(10), 1, 0) }
        assertTrue(assertThrows<IllegalArgumentException> { ScanFrame(ByteArray(10), 5, 2, rowStride = 4) }.message!!.contains("rowStride(4)"))
        assertTrue(assertThrows<IllegalArgumentException> { ScanFrame(ByteArray(8), 5, 2) }.message!!.contains("(8)"))
        assertTrue(assertThrows<IllegalArgumentException> { ScanFrame(ByteArray(10), 5, 2, rotationDegrees = 45) }.message!!.contains("45"))
        // 마지막 행 패딩이 없어도 된다: stride 6, 높이 2 → 6 + 5 = 11
        assertEquals(11, ScanFrame(ByteArray(11), 5, 2, rowStride = 6).luminance.size)
    }

    @Test
    fun publicConstructor_hugeStrideDoesNotOverflow() {
        assertThrows<IllegalArgumentException> { ScanFrame(ByteArray(16), 1, 3, rowStride = Int.MAX_VALUE) }
    }

    @Test
    fun uprightSizes() {
        for ((r, ew, eh) in listOf(Triple(0, 4, 3), Triple(90, 3, 4), Triple(180, 4, 3), Triple(270, 3, 4))) {
            val f = ScanFrame(ByteArray(12), 4, 3, rotationDegrees = r, timestampNanos = 77)
            assertEquals(ew, f.uprightWidth)
            assertEquals(eh, f.uprightHeight)
            assertEquals(77, f.timestampNanos)
            assertEquals(r, f.rotationDegrees)
            assertNull(f.image)
        }
    }

    @Test
    fun fromImageProxy_copiesYPlaneLazily() {
        val luma = ByteArray(8 * 4) { it.toByte() }
        val img = FakeImageProxy(6, 4, luma, stride = 8, rotation = 270, ts = 123)
        val f = ScanFrame.of(img)
        assertEquals(6, f.width)
        assertEquals(4, f.height)
        assertEquals(270, f.rotationDegrees)
        assertEquals(123, f.timestampNanos)
        assertSame(img, f.image)
        assertEquals("밝기를 읽기 전에는 평면을 건드리지 않는다", 0, img.planesRead)
        assertArrayEquals(luma, f.luminance)
        assertEquals(8, f.rowStride)
        f.luminance
        assertEquals("한 번만 복사한다", 1, img.planesRead)
    }
}

class FrameAnalyzerTest {

    private val opts = ScannerOptions(engine = ScanEngines.zxing(), tracking = TrackingOptions(smoothing = 0f))

    private class Harness(
        engine: ScanEngine,
        mode: ScanMode = ScanMode.Multi,
        options: ScannerOptions,
        var paused: Boolean = false,
        var reset: Boolean = false,
        var view: Pair<Float, Float> = 480f to 640f,
    ) {
        val snapshots = ArrayList<ScanSnapshot>()
        val errors = ArrayList<ScannerException>()
        val analyzer = FrameAnalyzer(
            engine = engine,
            pipeline = ScanPipeline(mode, options),
            viewSize = { view },
            paused = { paused },
            consumeReset = { reset.also { reset = false } },
            sink = { snapshots += it },
            onError = { errors += it },
            frameListener = { listener },
        )
        var listener: FrameListener? = null
    }

    @Test
    fun frameListener_seesFrameAndDetections_beforeImageIsClosed() {
        val h = Harness(FakeEngine { listOf(detAt("A", 100f, 100f)) }, options = opts)
        val img = FakeImageProxy(640, 480, rotation = 90)
        var seen: List<Detection>? = null
        var closedWhenSeen = -1
        var lumaSize = -1
        h.listener = FrameListener { frame, detections ->
            seen = detections
            closedWhenSeen = img.closeCount
            lumaSize = frame.luminance.size
        }
        h.analyzer.analyze(img)
        assertEquals("A", seen!!.single().result.text)
        assertEquals("수신자는 반납 전에 불린다", 0, closedWhenSeen)
        assertTrue(lumaSize > 0)
        assertEquals(1, img.closeCount)
        assertEquals(1, h.snapshots.size)
    }

    @Test
    fun frameListener_failure_reportsOnce_andAnalysisContinues() {
        val h = Harness(FakeEngine { emptyList() }, options = opts)
        h.listener = FrameListener { _, _ -> throw IllegalStateException("app") }
        repeat(3) {
            val img = FakeImageProxy(4, 4)
            h.analyzer.analyze(img)
            assertEquals(1, img.closeCount)
        }
        assertEquals(1, h.errors.size)
        assertEquals(3, h.snapshots.size)
    }

    @Test
    fun frameListener_notCalled_whenPausedOrEngineFails() {
        var calls = 0
        val paused = Harness(FakeEngine { emptyList() }, options = opts, paused = true)
        paused.listener = FrameListener { _, _ -> calls++ }
        paused.analyzer.analyze(FakeImageProxy(4, 4))
        val failing = Harness(FakeEngine { throw IllegalStateException("x") }, options = opts)
        failing.listener = FrameListener { _, _ -> calls++ }
        failing.analyzer.analyze(FakeImageProxy(4, 4))
        assertEquals(0, calls)
    }

    @Test
    fun frameListener_directFrame_isCalled() {
        val h = Harness(FakeEngine { emptyList() }, options = opts)
        var calls = 0
        h.listener = FrameListener { _, _ -> calls++ }
        h.analyzer.analyze(ScanFrame(ByteArray(16), 4, 4))
        assertEquals(1, calls)
    }

    @Test
    fun imageIsAlwaysClosed_andSnapshotPublished() {
        val engine = FakeEngine { listOf(detAt("A", 100f, 100f)) }
        val h = Harness(engine, options = opts)
        val img = FakeImageProxy(640, 480, rotation = 90)
        h.analyzer.analyze(img)
        assertEquals(1, img.closeCount)
        assertEquals(1, engine.calls)
        assertEquals("A", h.snapshots.single().codes.single().result.text)
    }

    @Test
    fun engineFailure_closesImage_reportsOnce_skipsFrame() {
        val cause = IllegalStateException("x")
        val engine = FakeEngine { throw cause }
        val h = Harness(engine, options = opts)
        repeat(3) {
            val img = FakeImageProxy(4, 4)
            h.analyzer.analyze(img)
            assertEquals(1, img.closeCount)
        }
        assertEquals(1, h.errors.size)
        assertSame(cause, h.errors.single().cause)
        assertTrue(h.snapshots.isEmpty())
    }

    @Test
    fun sdkExceptionIsPassedAsIs() {
        val sdk = ScannerException("엔진", RuntimeException())
        val h = Harness(FakeEngine { throw sdk }, options = opts)
        h.analyzer.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertSame(sdk, h.errors.single())
    }

    @Test
    fun errorThrownByNonRuntimeIsNotSwallowed() {
        // Error(OOM 등)는 삼키지 않는다 — 그래도 프레임은 반납한다
        val h = Harness(FakeEngine { throw OutOfMemoryError("t") }, options = opts)
        val img = FakeImageProxy(4, 4)
        assertThrows<OutOfMemoryError> { h.analyzer.analyze(img) }
        assertEquals(1, img.closeCount)
    }

    @Test
    fun paused_skipsEngine() {
        val engine = FakeEngine()
        val h = Harness(engine, options = opts, paused = true)
        val img = FakeImageProxy(4, 4)
        h.analyzer.analyze(img)
        h.analyzer.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertEquals(0, engine.calls)
        assertEquals(1, img.closeCount)
        assertTrue(h.snapshots.isEmpty())
    }

    @Test
    fun closed_skipsEngine() {
        val engine = FakeEngine()
        val h = Harness(engine, options = opts)
        h.analyzer.close()
        val img = FakeImageProxy(4, 4)
        h.analyzer.analyze(img)
        h.analyzer.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertEquals(0, engine.calls)
        assertEquals(1, img.closeCount)
    }

    @Test
    fun closedWhileEngineRuns_dropsResult() {
        // 세션이 닫히는 사이 엔진 안에 있던 프레임 — 끝나도 상태에 옛 박스를 다시 싣지 않는다
        lateinit var h: Harness
        val engine = FakeEngine {
            h.analyzer.close()
            listOf(detAt("A", 100f, 100f))
        }
        h = Harness(engine, options = opts)
        val img = FakeImageProxy(480, 640)
        h.analyzer.analyze(img)
        assertEquals(1, engine.calls)
        assertEquals(1, img.closeCount)
        assertTrue(h.snapshots.isEmpty())
        h.analyzer.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertEquals(1, engine.calls)
    }

    @Test
    fun closeWaitsForInFlightPublish_thenNoMoreSnapshots() {
        // 분석 스레드가 결과를 내는 중에 메인 스레드가 닫으면, 닫기는 그 결과가 끝날 때까지 기다리고 그 뒤로는 결과가 없다
        val inSink = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pipeline = ScanPipeline(ScanMode.Multi, opts)
        val snapshots = java.util.Collections.synchronizedList(ArrayList<ScanSnapshot>())
        val analyzer = FrameAnalyzer(
            engine = FakeEngine { listOf(detAt("A", 100f, 100f)) },
            pipeline = pipeline,
            viewSize = { 480f to 640f },
            paused = { false },
            consumeReset = { false },
            sink = {
                snapshots += it
                inSink.countDown()
                release.await()
            },
            onError = {},
        )
        val worker = Thread { analyzer.analyze(ScanFrame(ByteArray(480 * 640), 480, 640)) }
        worker.start()
        assertTrue(inSink.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val closer = Thread { analyzer.close() }
        closer.start()
        closer.join(200)
        assertTrue("결과를 내는 동안 닫기는 기다린다", closer.isAlive)
        release.countDown()
        closer.join(5000)
        worker.join(5000)
        assertFalse(closer.isAlive)
        analyzer.analyze(ScanFrame(ByteArray(480 * 640), 480, 640))
        assertEquals(1, snapshots.size)
    }

    @Test
    fun resetFlag_resetsPipelineBeforeFrame() {
        val engine = FakeEngine { listOf(detAt("A", 100f, 100f)) }
        val h = Harness(engine, options = opts)
        h.analyzer.analyze(ScanFrame(ByteArray(480 * 640), 480, 640))
        assertEquals(1L, h.snapshots.last().codes.single().id)
        h.reset = true
        h.analyzer.analyze(ScanFrame(ByteArray(480 * 640), 480, 640))
        assertEquals(2L, h.snapshots.last().codes.single().id)
        assertFalse(h.reset)
    }

    @Test
    fun usesUprightSizeAndViewSize() {
        // 버퍼 640x480, 회전 90 → 세운 480x640. 뷰 960x1280 → 배율 2
        val engine = FakeEngine { listOf(det("A", 10f, 20f, 50f, 60f)) }
        val h = Harness(engine, options = opts, view = 960f to 1280f)
        h.analyzer.analyze(FakeImageProxy(640, 480, rotation = 90))
        assertEquals(box(20f, 40f, 100f, 120f), h.snapshots.single().codes.single().bounds)
    }

    @Test
    fun endToEnd_realQrThroughZxing_rotated() {
        val m = net.plumt.qr.QrCode.encode("hello")
        val upright = luminanceOf(m, 480, 640, 150, 230, 6)
        val (buf, bw, bh) = unrotate(upright, 480, 640, 90)
        val engine = net.plumt.qr.scanner.internal.ZxingEngine(net.plumt.qr.ReadOptions(formats = net.plumt.qr.CodeFormat.QR_ONLY))
        val h = Harness(engine, mode = ScanMode.Single(autoDeliver = true), options = opts.copy(autoDeliver = AutoDeliverOptions(0, 0)))
        h.analyzer.analyze(FakeImageProxy(bw, bh, buf, rotation = 90))
        val s = h.snapshots.single()
        assertEquals("hello", s.locked!!.result.text)
        assertEquals("hello", s.deliver!!.text)
        // 코드가 그려진 자리(가운데 근처)
        assertEquals(150f + m.width * 3f, s.locked!!.bounds.centerX, 10f)
    }

    @Test
    fun fuzz_randomFramesAlwaysClosed() {
        val rnd = Random(8)
        val h = Harness(net.plumt.qr.scanner.internal.ZxingEngine(net.plumt.qr.ReadOptions()), options = opts)
        repeat(40) {
            val w = rnd.nextInt(1, 80)
            val hh = rnd.nextInt(1, 80)
            val stride = w + rnd.nextInt(0, 4)
            val img = FakeImageProxy(w, hh, rnd.nextBytes(stride * hh), stride, listOf(0, 90, 180, 270)[rnd.nextInt(4)], rnd.nextLong())
            h.analyzer.analyze(img)
            assertEquals(1, img.closeCount)
        }
    }
}

/** 분석 스레드 — 인식은 블로킹이라 메인 스레드에서 돌면 안 된다. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class AnalysisExecutorTest {

    @Test
    fun analysisExecutor_runsOffMainThread() {
        val executor = AnalysisExecutor.create()
        try {
            val thread = executor.submit<Thread> { Thread.currentThread() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(AnalysisExecutor.THREAD_NAME, thread.name)
            assertTrue(thread.isDaemon)
            assertFalse("분석은 메인 스레드에서 돌지 않는다", thread === Looper.getMainLooper().thread)
            assertFalse(thread === Thread.currentThread())
            // 단일 스레드 — 프레임이 동시에 분석되지 않는다
            assertSame(thread, executor.submit<Thread> { Thread.currentThread() }.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
