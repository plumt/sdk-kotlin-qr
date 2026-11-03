package net.plumt.qr.scanner

import net.plumt.qr.CodeFormat
import net.plumt.qr.scanner.internal.CodeTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CodeTrackerTest {

    private val W = 480
    private val H = 640

    private fun tracker(maxMissed: Int = 2, smoothing: Float = 0f, iou: Float = 0.3f) =
        CodeTracker(TrackingOptions(maxMissedFrames = maxMissed, smoothing = smoothing, iouThreshold = iou))

    @Test
    fun newCodesGetIncreasingIds() {
        val t = tracker()
        val out = t.update(listOf(detAt("A", 100f, 100f), detAt("B", 300f, 300f)), W, H)
        assertEquals(listOf(1L, 2L), out.map { it.id })
        assertEquals(listOf(0, 0), out.map { it.missedFrames })
        assertEquals(listOf(1, 1), out.map { it.hits })
    }

    @Test
    fun idStaysWhileCodeMoves() {
        val t = tracker()
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        var last = 0L
        for (i in 1..20) {
            val out = t.update(listOf(detAt("A", 100f + i * 5, 100f + i * 3)), W, H)
            assertEquals(1, out.size)
            last = out[0].id
            assertEquals(1L, last)
            assertEquals(i + 1, out[0].hits)
        }
    }

    @Test
    fun differentTextAtSamePlace_isNewTrack() {
        val t = tracker()
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        val out = t.update(listOf(detAt("B", 100f, 100f)), W, H)
        assertEquals(setOf(1L, 2L), out.map { it.id }.toSet())
        assertEquals(1, out.first { it.id == 1L }.missedFrames)
    }

    @Test
    fun sameTextDifferentFormat_isNewTrack() {
        val t = tracker()
        t.update(listOf(det("123", 0f, 0f, 40f, 40f, CodeFormat.QR_CODE)), W, H)
        val out = t.update(listOf(det("123", 0f, 0f, 40f, 40f, CodeFormat.DATA_MATRIX)), W, H)
        assertEquals(2, out.size)
    }

    @Test
    fun farJump_isNewTrack_butNearMoveWithLowIouIsSame() {
        val t = tracker()
        t.update(listOf(detAt("A", 100f, 100f, size = 40f)), W, H)
        // 겹침은 없지만 가운데 거리(40)가 코드 크기(40) 이내 — 같은 코드
        val near = t.update(listOf(detAt("A", 140f, 100f, size = 40f)), W, H)
        assertEquals(listOf(1L), near.map { it.id })
        // 크기보다 멀리 — 새 코드, 옛 것은 놓친 것으로
        val far = t.update(listOf(detAt("A", 400f, 500f, size = 40f)), W, H)
        assertEquals(setOf(1L, 2L), far.map { it.id }.toSet())
    }

    @Test
    fun twoIdenticalCodesSideBySide_keepTheirIds() {
        val t = tracker()
        t.update(listOf(detAt("SAME", 100f, 100f), detAt("SAME", 200f, 100f)), W, H)
        // 순서를 뒤집어 넣고 조금씩 움직여도 번호가 자리를 따라간다
        val out = t.update(listOf(detAt("SAME", 205f, 102f), detAt("SAME", 103f, 101f)), W, H)
        val left = out.minByOrNull { it.bounds.centerX }!!
        val right = out.maxByOrNull { it.bounds.centerX }!!
        assertEquals(1L, left.id)
        assertEquals(2L, right.id)
    }

    @Test
    fun missedFramesThenExpiry() {
        val t = tracker(maxMissed = 2)
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        assertEquals(1, t.update(emptyList(), W, H).single().missedFrames)
        assertEquals(2, t.update(emptyList(), W, H).single().missedFrames)
        assertTrue(t.update(emptyList(), W, H).isEmpty())
        // 다시 보이면 새 번호
        assertEquals(2L, t.update(listOf(detAt("A", 100f, 100f)), W, H).single().id)
    }

    @Test
    fun maxMissedZero_removesImmediately() {
        val t = tracker(maxMissed = 0)
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        assertTrue(t.update(emptyList(), W, H).isEmpty())
    }

    @Test
    fun reappearingWithinWindow_keepsIdAndResetsMissed() {
        val t = tracker(maxMissed = 3)
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        t.update(emptyList(), W, H)
        t.update(emptyList(), W, H)
        val back = t.update(listOf(detAt("A", 102f, 100f)), W, H).single()
        assertEquals(1L, back.id)
        assertEquals(0, back.missedFrames)
    }

    @Test
    fun smoothing_movesPartway() {
        val t = tracker(smoothing = 0.5f)
        t.update(listOf(det("A", 0f, 0f, 40f, 40f)), W, H)
        val b = t.update(listOf(det("A", 10f, 0f, 50f, 40f)), W, H).single().bounds
        assertEquals(5f, b.left, EPS)
        assertEquals(45f, b.right, EPS)
        val b2 = t.update(listOf(det("A", 10f, 0f, 50f, 40f)), W, H).single().bounds
        assertEquals(7.5f, b2.left, EPS)
    }

    @Test
    fun smoothing_convergesToStationaryTarget() {
        val t = tracker(smoothing = 0.8f)
        t.update(listOf(det("A", 0f, 0f, 40f, 40f)), W, H)
        var b = t.current.single().bounds
        repeat(100) { b = t.update(listOf(det("A", 20f, 10f, 60f, 50f)), W, H).single().bounds }
        assertEquals(20f, b.left, 0.01f)
        assertEquals(10f, b.top, 0.01f)
    }

    @Test
    fun smoothing_followsWhenJumpEqualsSize() {
        val t = tracker(smoothing = 0.9f, iou = 0f)
        t.update(listOf(det("A", 0f, 0f, 40f, 40f)), W, H)
        // 같은 코드로 잇되(거리 = 크기), 박스 크기 이상 튀지는 않았다 → 평활
        val b = t.update(listOf(det("A", 40f, 0f, 80f, 40f)), W, H).single().bounds
        assertEquals(4f, b.left, EPS)
    }

    @Test
    fun smoothing_snapsOnJumpBiggerThanSize() {
        // 겹침 하한 0 이면 같은 원문은 언제나 잇는다 — 크기보다 멀리 튀면 따라가지 않고 바로 옮긴다
        val t = tracker(smoothing = 0.9f, iou = 0f)
        t.update(listOf(det("A", 0f, 0f, 40f, 40f)), W, H)
        val out = t.update(listOf(det("A", 200f, 0f, 240f, 40f)), W, H).single()
        assertEquals(1L, out.id)
        assertEquals(200f, out.bounds.left, EPS)
    }

    @Test
    fun smoothingZero_usesObservation() {
        val t = tracker(smoothing = 0f)
        t.update(listOf(det("A", 0f, 0f, 40f, 40f)), W, H)
        assertEquals(det("A", 10f, 5f, 50f, 45f).bounds, t.update(listOf(det("A", 10f, 5f, 50f, 45f)), W, H).single().bounds)
    }

    @Test
    fun resultIsUpdatedToLatest() {
        val t = tracker()
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        val out = t.update(listOf(detAt("A", 101f, 100f)), W, H).single()
        assertEquals("A", out.result.text)
    }

    @Test
    fun duplicateDetectionsInOneFrame_areMerged() {
        val t = tracker()
        val out = t.update(listOf(det("A", 0f, 0f, 40f, 40f), det("A", 1f, 1f, 41f, 41f)), W, H)
        assertEquals(1, out.size)
    }

    @Test
    fun imageSizeChange_resetsTracks_idsContinue() {
        val t = tracker()
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        val out = t.update(listOf(detAt("A", 100f, 100f)), H, W)
        assertEquals(listOf(2L), out.map { it.id })
    }

    @Test
    fun reset_clearsButKeepsCounter() {
        val t = tracker()
        t.update(listOf(detAt("A", 100f, 100f)), W, H)
        t.reset()
        assertTrue(t.current.isEmpty())
        assertEquals(2L, t.update(listOf(detAt("A", 100f, 100f)), W, H).single().id)
    }

    @Test
    fun emptyFrames_onEmptyTracker() {
        val t = tracker()
        assertTrue(t.update(emptyList(), W, H).isEmpty())
    }

    @Test
    fun fuzz_idsUniqueAndStableForStationaryCodes() {
        val rnd = Random(99)
        val t = tracker(maxMissed = 3, smoothing = 0.5f)
        val fixed = listOf(detAt("P", 100f, 100f), detAt("Q", 300f, 400f))
        val ids = HashMap<String, Long>()
        repeat(500) {
            val noise = List(rnd.nextInt(0, 4)) { detAt("N${rnd.nextInt(10)}", rnd.nextFloat() * W, rnd.nextFloat() * H) }
            val jitter = fixed.map { d ->
                det(d.result.text, d.bounds.left + rnd.nextFloat() * 4 - 2, d.bounds.top + rnd.nextFloat() * 4 - 2,
                    d.bounds.right + rnd.nextFloat() * 4 - 2, d.bounds.bottom + rnd.nextFloat() * 4 - 2)
            }
            val out = t.update((jitter + noise).shuffled(rnd), W, H)
            assertEquals(out.size, out.map { it.id }.toSet().size)
            for (code in listOf("P", "Q")) {
                val id = out.single { it.result.text == code && it.missedFrames == 0 }.id
                val prev = ids.getOrPut(code) { id }
                assertEquals("코드 $code 의 번호가 바뀌었다", prev, id)
            }
            out.forEach { assertTrue(it.missedFrames in 0..3) }
        }
        assertNotEquals(ids["P"], ids["Q"])
    }
}
