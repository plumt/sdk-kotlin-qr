package net.plumt.qr.scanner

import net.plumt.qr.scanner.internal.AutoDeliverGate
import net.plumt.qr.scanner.internal.ScanPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDeliverGateTest {

    private fun gate(stable: Long = 300, repeat: Long = 2000) = AutoDeliverGate(AutoDeliverOptions(stable, repeat))

    @Test
    fun deliversAfterStableTime_once() {
        val g = gate()
        assertFalse(g.offer(1, "A", 1000))
        assertFalse(g.offer(1, "A", 1299))
        assertTrue(g.offer(1, "A", 1300))
        assertFalse(g.offer(1, "A", 5000))
    }

    @Test
    fun zeroStable_deliversImmediately() {
        assertTrue(gate(stable = 0).offer(7, "A", 0))
    }

    @Test
    fun lockChangeRestartsTimer() {
        val g = gate()
        g.offer(1, "A", 0)
        assertFalse(g.offer(2, "B", 250))
        assertFalse(g.offer(2, "B", 500))
        assertTrue(g.offer(2, "B", 550))
    }

    @Test
    fun lossOfLockRestartsTimer() {
        val g = gate()
        g.offer(1, "A", 0)
        assertFalse(g.offer(null, null, 200))
        assertFalse(g.offer(1, "A", 400))
        assertTrue(g.offer(1, "A", 700))
    }

    @Test
    fun sameTextWithNewId_suppressedWithinWindow() {
        val g = gate(stable = 0, repeat = 2000)
        assertTrue(g.offer(1, "A", 0))
        assertFalse(g.offer(2, "A", 1999))
        assertTrue(g.offer(3, "A", 2000))
    }

    @Test
    fun differentText_notSuppressed() {
        val g = gate(stable = 0)
        assertTrue(g.offer(1, "A", 0))
        assertTrue(g.offer(2, "B", 1))
    }

    @Test
    fun zeroRepeatWindow_allowsSameTextWithNewId() {
        val g = gate(stable = 0, repeat = 0)
        assertTrue(g.offer(1, "A", 0))
        assertTrue(g.offer(2, "A", 0))
        assertFalse(g.offer(2, "A", 10))
    }

    @Test
    fun timeGoingBackwards_doesNotDeadlock() {
        val g = gate(stable = 100, repeat = 10_000)
        g.offer(1, "A", 1_000_000)
        assertTrue(g.offer(1, "A", 1_000_100))
        // 카메라 재시작으로 시각 기준이 0 근처로 — 같은 원문이지만 기록을 지워 다시 넘길 수 있다
        assertFalse(g.offer(2, "A", 5))
        assertTrue(g.offer(2, "A", 105))
    }

    @Test
    fun retain_forgetsRemovedIds_reset_forgetsAll() {
        val g = gate(stable = 0, repeat = 0)
        assertTrue(g.offer(1, "A", 0))
        g.retain(setOf(1))
        assertFalse(g.offer(1, "A", 1))
        g.retain(emptySet())
        assertTrue(g.offer(1, "A", 2))
        g.reset()
        assertTrue(g.offer(1, "A", 3))
    }

    @Test
    fun reset_keepsRepeatWindow_soResumeDoesNotRedeliverSameText() {
        // 넘기기 → 멈춤 → 결과 창 닫기 → 재개: 화면에 남은 같은 코드(재개 뒤 새 추적 번호)를 반복 창 안에 다시 넘기지 않는다
        val g = gate(stable = 300, repeat = 2000)
        assertFalse(g.offer(1, "A", 0))
        assertTrue(g.offer(1, "A", 300))
        g.reset()
        assertFalse(g.offer(1, "A", 400))
        assertFalse("안정 시간이 지나도 반복 창 안", g.offer(1, "A", 1000))
        assertTrue("반복 창이 지나면 다시 넘긴다", g.offer(1, "A", 2300))
        // 다른 원문은 반복 창과 무관하다
        g.reset()
        assertFalse(g.offer(7, "B", 2400))
        assertTrue(g.offer(7, "B", 2700))
        // 새 세션의 시각 기준이 더 작으면(거꾸로) 원문 기록을 지운다
        g.reset()
        assertFalse(g.offer(9, "B", 10))
        assertTrue(g.offer(9, "B", 310))
    }
}

class ScanPipelineTest {

    // 세운 이미지 480x640, 뷰 960x1280 → 배율 2, 여백 0. 뷰 가운데 = 이미지 (240, 320)
    private val IW = 480
    private val IH = 640
    private val VW = 960f
    private val VH = 1280f

    private fun options(
        lens: CameraLens = CameraLens.BACK,
        scale: PreviewScaleMode = PreviewScaleMode.FILL,
        stable: Long = 0,
    ) = ScannerOptions(
        engine = ScanEngines.zxing(),
        lens = lens,
        scaleMode = scale,
        tracking = TrackingOptions(maxMissedFrames = 2, smoothing = 0f),
        lock = LockOptions(hysteresis = 0.1f, switchFrames = 2),
        autoDeliver = AutoDeliverOptions(stableMillis = stable, repeatWindowMillis = 1000),
    )

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun multi_returnsAllVisibleWithViewCoordinates() {
        val p = ScanPipeline(ScanMode.Multi, options())
        val s = p.process(listOf(det("A", 10f, 20f, 50f, 60f), det("B", 300f, 300f, 340f, 340f)), IW, IH, VW, VH, 0)
        assertEquals(listOf("A", "B"), s.codes.map { it.result.text })
        assertEquals(box(20f, 40f, 100f, 120f), s.codes[0].bounds)
        assertTrue(s.codes.none { it.isLocked })
        assertNull(s.locked)
        assertNull(s.deliver)
    }

    @Test
    fun frontLens_mirrorsBoxes() {
        val p = ScanPipeline(ScanMode.Multi, options(lens = CameraLens.FRONT))
        val s = p.process(listOf(det("A", 10f, 20f, 50f, 60f)), IW, IH, VW, VH, 0)
        assertEquals(box(860f, 40f, 940f, 120f), s.codes.single().bounds)
    }

    @Test
    fun fill_cropsOffscreenCodes_fitKeepsThem() {
        // 뷰 1080x1920 에서 FILL 은 좌우 180px 씩 잘린다 — 이미지 x=20 근처 코드(뷰 x≈-120)는 보이지 않는다
        val d = listOf(det("EDGE", 0f, 300f, 40f, 340f), det("MID", 220f, 300f, 260f, 340f))
        val fill = ScanPipeline(ScanMode.Multi, options(scale = PreviewScaleMode.FILL)).process(d, IW, IH, 1080f, 1920f, 0)
        assertEquals(listOf("MID"), fill.codes.map { it.result.text })
        val fit = ScanPipeline(ScanMode.Multi, options(scale = PreviewScaleMode.FIT)).process(d, IW, IH, 1080f, 1920f, 0)
        assertEquals(listOf("EDGE", "MID"), fit.codes.map { it.result.text })
    }

    @Test
    fun unknownViewSize_tracksButShowsNothing() {
        val p = ScanPipeline(ScanMode.Single(), options())
        for (size in listOf(0f to 0f, 0f to 100f, Float.NaN to 100f, 100f to Float.POSITIVE_INFINITY)) {
            val s = p.process(listOf(detAt("A", 240f, 320f)), IW, IH, size.first, size.second, 0)
            assertTrue(s.codes.isEmpty())
            assertNull(s.locked)
        }
        // 뷰 크기를 알게 되면 이어서 같은 번호로 보인다
        val s = p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, 0)
        assertEquals(1L, s.locked!!.id)
    }

    @Test
    fun single_locksCenterCode_andMarksIt() {
        val p = ScanPipeline(ScanMode.Single(), options())
        val s = p.process(listOf(detAt("FAR", 50f, 50f), detAt("CENTER", 245f, 325f)), IW, IH, VW, VH, 0)
        assertEquals("CENTER", s.locked!!.result.text)
        assertEquals(listOf(false, true), s.codes.map { it.isLocked })
        assertNull("autoDeliver 가 꺼져 있으면 넘기지 않는다", s.deliver)
    }

    @Test
    fun single_lockIsStableUnderHysteresis_thenSwitches() {
        val p = ScanPipeline(ScanMode.Single(), options())
        p.process(listOf(detAt("A", 300f, 320f)), IW, IH, VW, VH, 0)
        // B 가 가운데에 거의 붙어도 차이(뷰 기준 120px) < 0.1 * 1600 → 그대로 A
        repeat(5) {
            assertEquals("A", p.process(listOf(detAt("A", 300f, 320f), detAt("B", 240f, 320f)), IW, IH, VW, VH, 0).locked!!.result.text)
        }
        // A 가 조금씩 멀어진다(추적은 이어진다). 뷰 거리 160 은 여유(160)와 같아 그대로, 200 이 되면 두 프레임 연속 뒤 B
        assertEquals("A", p.process(listOf(detAt("A", 320f, 320f), detAt("B", 240f, 320f)), IW, IH, VW, VH, 0).locked!!.result.text)
        assertEquals("A", p.process(listOf(detAt("A", 340f, 320f), detAt("B", 240f, 320f)), IW, IH, VW, VH, 0).locked!!.result.text)
        val s = p.process(listOf(detAt("A", 340f, 320f), detAt("B", 240f, 320f)), IW, IH, VW, VH, 0)
        assertEquals("B", s.locked!!.result.text)
        assertEquals(1L, s.codes.single { it.result.text == "A" }.id)
    }

    @Test
    fun single_autoDeliver_debouncedAndDeduplicated() {
        val p = ScanPipeline(ScanMode.Single(autoDeliver = true), options(stable = 300))
        val d = listOf(detAt("A", 240f, 320f))
        assertNull(p.process(d, IW, IH, VW, VH, ms(1000)).deliver)
        assertNull(p.process(d, IW, IH, VW, VH, ms(1200)).deliver)
        assertEquals("A", p.process(d, IW, IH, VW, VH, ms(1300)).deliver!!.text)
        assertNull(p.process(d, IW, IH, VW, VH, ms(1400)).deliver)
        assertNull(p.process(d, IW, IH, VW, VH, ms(9000)).deliver)
    }

    @Test
    fun single_autoDeliver_newCodeAfterOldExpires() {
        val p = ScanPipeline(ScanMode.Single(autoDeliver = true), options(stable = 0))
        assertEquals("A", p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, ms(0)).deliver!!.text)
        repeat(3) { p.process(emptyList(), IW, IH, VW, VH, ms(10)) }
        // 같은 원문이 반복 창(1000ms) 안에 새 번호로 돌아오면 넘기지 않고, 창이 지나면 넘긴다
        assertNull(p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, ms(500)).deliver)
        repeat(3) { p.process(emptyList(), IW, IH, VW, VH, ms(600)) }
        assertEquals("A", p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, ms(1500)).deliver!!.text)
    }

    @Test
    fun multi_neverAutoDelivers() {
        val p = ScanPipeline(ScanMode.Multi, options())
        assertNull(p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, ms(5000)).deliver)
    }

    @Test
    fun reset_startsTrackingOver_butKeepsRepeatWindow() {
        val p = ScanPipeline(ScanMode.Single(autoDeliver = true), options())
        assertEquals("A", p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, ms(0)).deliver!!.text)
        p.reset()
        val s = p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, ms(1))
        assertEquals(2L, s.locked!!.id)
        assertNull("반복 창(1000ms) 안의 같은 원문", s.deliver)
        assertEquals("A", p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, ms(1001)).deliver!!.text)
        // 다른 원문은 바로 넘긴다
        p.reset()
        assertEquals("B", p.process(listOf(detAt("B", 240f, 320f)), IW, IH, VW, VH, ms(1002)).deliver!!.text)
    }

    @Test
    fun staleCodesAreReportedWithMissedFrames() {
        val p = ScanPipeline(ScanMode.Multi, options())
        p.process(listOf(detAt("A", 240f, 320f)), IW, IH, VW, VH, 0)
        val s = p.process(emptyList(), IW, IH, VW, VH, 0)
        assertEquals(1, s.codes.single().missedFrames)
        assertTrue(s.codes.single().isStale)
    }
}
