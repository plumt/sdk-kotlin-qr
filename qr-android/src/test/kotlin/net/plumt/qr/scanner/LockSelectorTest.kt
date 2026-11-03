package net.plumt.qr.scanner

import net.plumt.qr.scanner.internal.LockCandidate
import net.plumt.qr.scanner.internal.LockSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class LockSelectorTest {

    // 뷰 1000x1000 → 가운데 (500, 500), 대각선 1414.2
    private val V = 1000f

    private fun c(id: Long, cx: Float, cy: Float, fresh: Boolean = true) =
        LockCandidate(id, box(cx - 20, cy - 20, cx + 20, cy + 20), fresh)

    private fun sel(h: Float = 0.1f, frames: Int = 3) = LockSelector(LockOptions(hysteresis = h, switchFrames = frames))

    @Test
    fun noCandidates_noLock() {
        val s = sel()
        assertNull(s.select(emptyList(), V, V))
        assertNull(s.lockedId)
    }

    @Test
    fun locksNearestToCenter() {
        val s = sel()
        assertEquals(2L, s.select(listOf(c(1, 100f, 100f), c(2, 520f, 480f), c(3, 900f, 900f)), V, V))
    }

    @Test
    fun tieBreaksBySmallerId() {
        val s = sel()
        assertEquals(3L, s.select(listOf(c(5, 600f, 500f), c(3, 400f, 500f)), V, V))
    }

    @Test
    fun staleCandidatesAreNotNewlyLocked() {
        val s = sel()
        assertEquals(2L, s.select(listOf(c(1, 500f, 500f, fresh = false), c(2, 900f, 900f)), V, V))
        assertNull(sel().select(listOf(c(1, 500f, 500f, fresh = false)), V, V))
    }

    @Test
    fun keepsLockWhenChallengerIsOnlySlightlyCloser() {
        val s = sel(h = 0.1f, frames = 1)
        s.select(listOf(c(1, 600f, 500f)), V, V)
        // 1 은 가운데에서 100, 2 는 30 — 차이 70 < 0.1 * 1414 = 141 → 그대로
        repeat(10) { assertEquals(1L, s.select(listOf(c(1, 600f, 500f), c(2, 530f, 500f)), V, V)) }
    }

    @Test
    fun switchesAfterConsecutiveFramesWhenClearlyCloser() {
        val s = sel(h = 0.1f, frames = 3)
        s.select(listOf(c(1, 800f, 500f)), V, V)
        val both = listOf(c(1, 800f, 500f), c(2, 500f, 500f)) // 차이 300 > 141
        assertEquals(1L, s.select(both, V, V))
        assertEquals(1L, s.select(both, V, V))
        assertEquals(2L, s.select(both, V, V))
        assertEquals(2L, s.select(both, V, V))
    }

    @Test
    fun interruptedChallengeRestartsCount() {
        val s = sel(h = 0.1f, frames = 3)
        s.select(listOf(c(1, 800f, 500f)), V, V)
        val both = listOf(c(1, 800f, 500f), c(2, 500f, 500f))
        s.select(both, V, V)
        s.select(both, V, V)
        // 한 프레임 동안 2 가 안 보인다
        assertEquals(1L, s.select(listOf(c(1, 800f, 500f)), V, V))
        assertEquals(1L, s.select(both, V, V))
        assertEquals(1L, s.select(both, V, V))
        assertEquals(2L, s.select(both, V, V))
    }

    @Test
    fun challengerChangeRestartsCount() {
        val s = sel(h = 0.1f, frames = 2)
        s.select(listOf(c(1, 900f, 500f)), V, V)
        assertEquals(1L, s.select(listOf(c(1, 900f, 500f), c(2, 500f, 500f)), V, V))
        // 다음 프레임엔 3 이 가장 앞선다 — 2 의 연속은 끊기고 3 이 1 번째
        assertEquals(1L, s.select(listOf(c(1, 900f, 500f), c(3, 500f, 500f)), V, V))
        assertEquals(3L, s.select(listOf(c(1, 900f, 500f), c(3, 500f, 500f)), V, V))
    }

    @Test
    fun staleChallengerCannotSteal() {
        val s = sel(h = 0f, frames = 1)
        s.select(listOf(c(1, 900f, 500f)), V, V)
        assertEquals(1L, s.select(listOf(c(1, 900f, 500f), c(2, 500f, 500f, fresh = false)), V, V))
    }

    @Test
    fun lockSurvivesMissedFrames() {
        val s = sel(h = 0f, frames = 1)
        s.select(listOf(c(1, 500f, 500f)), V, V)
        assertEquals(1L, s.select(listOf(c(1, 500f, 500f, fresh = false), c(2, 900f, 900f)), V, V))
    }

    @Test
    fun lockLostWhenCodeDisappears_relocksSameFrame() {
        val s = sel()
        s.select(listOf(c(1, 500f, 500f)), V, V)
        assertEquals(2L, s.select(listOf(c(2, 900f, 900f)), V, V))
        assertNull(s.select(emptyList(), V, V))
    }

    @Test
    fun zeroHysteresis_switchFramesOne_followsNearestStrictly() {
        val s = sel(h = 0f, frames = 1)
        s.select(listOf(c(1, 600f, 500f)), V, V)
        // 거리가 같으면 옮기지 않는다
        assertEquals(1L, s.select(listOf(c(1, 600f, 500f), c(2, 400f, 500f)), V, V))
        assertEquals(2L, s.select(listOf(c(1, 600f, 500f), c(2, 450f, 500f)), V, V))
    }

    @Test
    fun fullHysteresis_neverSwitches() {
        val s = sel(h = 1f, frames = 1)
        s.select(listOf(c(1, 0f, 0f)), V, V)
        repeat(5) { assertEquals(1L, s.select(listOf(c(1, 0f, 0f), c(2, 500f, 500f)), V, V)) }
    }

    @Test
    fun reset_forgetsLock() {
        val s = sel()
        s.select(listOf(c(1, 500f, 500f)), V, V)
        s.reset()
        assertNull(s.lockedId)
        assertEquals(2L, s.select(listOf(c(1, 900f, 900f), c(2, 510f, 500f)), V, V))
    }

    @Test
    fun fuzz_noFlickerBetweenTwoJitteringCodes() {
        // 두 코드가 가운데에서 거의 같은 거리(±5px 흔들림) — 고정이 한 번도 바뀌면 안 된다
        val rnd = Random(11)
        val s = sel(h = 0.05f, frames = 3)
        var switches = 0
        var last: Long? = null
        repeat(1000) {
            val a = c(1, 450f + rnd.nextFloat() * 10 - 5, 500f + rnd.nextFloat() * 10 - 5)
            val b = c(2, 550f + rnd.nextFloat() * 10 - 5, 500f + rnd.nextFloat() * 10 - 5)
            val id = s.select(listOf(a, b).shuffled(rnd), V, V)
            if (last != null && id != last) switches++
            last = id
        }
        assertEquals(0, switches)
    }

    @Test
    fun fuzz_selectionIsAlwaysACandidate() {
        val rnd = Random(12)
        val s = sel(h = rnd.nextFloat(), frames = 2)
        repeat(2000) {
            val cands = List(rnd.nextInt(0, 6)) { c(rnd.nextLong(0, 8), rnd.nextFloat() * V, rnd.nextFloat() * V, rnd.nextBoolean()) }
                .distinctBy { it.id }
            val id = s.select(cands, V, V)
            if (id != null) org.junit.Assert.assertTrue(cands.any { it.id == id })
        }
    }
}
