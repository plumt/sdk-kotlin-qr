package net.plumt.qr.scanner

import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.internal.CustomLockFailure
import net.plumt.qr.scanner.internal.LockController
import net.plumt.qr.scanner.internal.ScanPipeline
import net.plumt.qr.scanner.internal.ScanSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 고정 상태기 — None·Tap·Nearest·Custom, 핀(lockOn)·해제·전환·코드 사라짐. */
class LockControllerTest {

    // 뷰 1000x1000 → 가운데 (500, 500)
    private val V = 1000f

    private fun code(id: Long, cx: Float, cy: Float, missed: Int = 0) =
        TrackedCode(id, result("T$id"), box(cx - 20, cy - 20, cx + 20, cy + 20), missed, isLocked = false)

    private fun ctl() = LockController(LockOptions(hysteresis = 0.1f, switchFrames = 2))

    private val two = listOf(code(1, 100f, 100f), code(2, 510f, 500f))

    @Test
    fun none_neverLocks() {
        val c = ctl()
        repeat(3) { assertNull(c.select(MultiLock.None, two, V, V)) }
        assertNull(c.lockedId)
    }

    @Test
    fun tap_locksOnlyWhenPinned() {
        val c = ctl()
        assertNull(c.select(MultiLock.Tap, two, V, V))
        c.pin(1)
        assertEquals(1L, c.select(MultiLock.Tap, two, V, V))
        assertEquals(1L, c.select(MultiLock.Tap, two, V, V))
        // 다른 코드로 옮긴다
        c.pin(2)
        assertEquals(2L, c.select(MultiLock.Tap, two, V, V))
        // 풀면 고정 없음
        c.release()
        assertNull(c.lockedId)
        assertNull(c.select(MultiLock.Tap, two, V, V))
    }

    @Test
    fun pin_releasedWhenCodeDisappears() {
        val c = ctl()
        c.pin(1)
        assertEquals(1L, c.select(MultiLock.Tap, two, V, V))
        assertEquals("잠깐 놓친 프레임(목록에 남음)은 지킨다", 1L, c.select(MultiLock.Tap, listOf(code(1, 100f, 100f, missed = 1)), V, V))
        assertNull(c.select(MultiLock.Tap, listOf(code(2, 510f, 500f)), V, V))
        assertNull(c.pinnedId)
        assertNull("다시 보여도 핀은 돌아오지 않는다", c.select(MultiLock.Tap, two, V, V))
    }

    @Test
    fun nearest_locksCenter_withHysteresis() {
        val c = ctl()
        assertEquals(2L, c.select(MultiLock.Nearest, two, V, V))
        // 3 이 더 가깝지만 차이가 작다 → 그대로
        val three = two + code(3, 500f, 500f)
        repeat(3) { assertEquals(2L, c.select(MultiLock.Nearest, three, V, V)) }
    }

    @Test
    fun nearest_pinOverrides_thenContinuesFromPin() {
        val c = ctl()
        assertEquals(2L, c.select(MultiLock.Nearest, two, V, V))
        c.pin(1)
        repeat(3) { assertEquals("핀이 가운데 규칙보다 우선", 1L, c.select(MultiLock.Nearest, two, V, V)) }
        // 핀 코드가 사라지면 정책대로 다시 고른다
        assertEquals(2L, c.select(MultiLock.Nearest, listOf(code(2, 510f, 500f)), V, V))
    }

    @Test
    fun nearest_releaseRelocksNextFrame() {
        val c = ctl()
        c.select(MultiLock.Nearest, two, V, V)
        c.release()
        assertNull(c.lockedId)
        assertEquals(2L, c.select(MultiLock.Nearest, two, V, V))
    }

    @Test
    fun custom_choosesAndSeesPreviousLock() {
        val seen = ArrayList<List<Pair<Long, Boolean>>>()
        val policy = MultiLock.Custom { codes ->
            seen += codes.map { it.id to it.isLocked }
            codes.maxByOrNull { it.id }
        }
        val c = ctl()
        assertEquals(2L, c.select(policy, two, V, V))
        assertEquals(2L, c.select(policy, two, V, V))
        assertEquals(listOf(listOf(1L to false, 2L to false), listOf(1L to false, 2L to true)), seen)
    }

    @Test
    fun custom_nullOrForeignCode_meansNoLock() {
        val c = ctl()
        assertNull(c.select(MultiLock.Custom { null }, two, V, V))
        val foreign = code(99, 0f, 0f)
        assertNull("목록에 없는 코드는 고정하지 않는다", c.select(MultiLock.Custom { foreign }, two, V, V))
    }

    @Test
    fun custom_notCalledWhilePinned() {
        var calls = 0
        val c = ctl()
        c.pin(1)
        assertEquals(1L, c.select(MultiLock.Custom { calls++; it.last() }, two, V, V))
        assertEquals(0, calls)
    }

    @Test
    fun custom_throwing_isWrapped_andKeepsPreviousLock() {
        val c = ctl()
        assertEquals(2L, c.select(MultiLock.Custom { it.last() }, two, V, V))
        val boom = IllegalStateException("boom")
        try {
            c.select(MultiLock.Custom { throw boom }, two, V, V)
            throw AssertionError("던져야 한다")
        } catch (e: CustomLockFailure) {
            assertSame(boom, e.cause)
        }
        assertEquals(2L, c.lockedId)
    }

    @Test
    fun reset_clearsPinAndLock() {
        val c = ctl()
        c.pin(1)
        c.select(MultiLock.Tap, two, V, V)
        c.reset()
        assertNull(c.pinnedId)
        assertNull(c.lockedId)
        assertNull(c.select(MultiLock.Tap, two, V, V))
    }
}

/** 파이프라인 — Multi 고정 정책이 화면 결과·자동 넘기기에 반영되는가. */
class MultiLockPipelineTest {

    private val IW = 480
    private val IH = 640
    private val VW = 960f
    private val VH = 1280f

    private fun options(stable: Long = 0) = ScannerOptions(
        engine = ScanEngines.zxing(),
        tracking = TrackingOptions(maxMissedFrames = 0, smoothing = 0f),
        lock = LockOptions(hysteresis = 0.1f, switchFrames = 2),
        autoDeliver = AutoDeliverOptions(stableMillis = stable, repeatWindowMillis = 1000),
    )

    private val dets = listOf(detAt("FAR", 50f, 50f), detAt("CENTER", 245f, 325f))
    private fun ms(v: Long) = v * 1_000_000L

    private fun ScanPipeline.frame(t: Long = 0, d: List<Detection> = dets): ScanSnapshot = process(d, IW, IH, VW, VH, ms(t))

    @Test
    fun multiNone_keepsOldBehaviour() {
        val s = ScanPipeline(ScanMode.Multi(), options()).frame()
        assertTrue(s.codes.none { it.isLocked })
        assertNull(s.locked)
        assertNull(s.deliver)
    }

    @Test
    fun multiNearest_locksCenter_andAutoDelivers() {
        val p = ScanPipeline(ScanMode.Multi(MultiLock.Nearest, autoDeliver = true), options())
        val s = p.frame()
        assertEquals("CENTER", s.locked!!.result.text)
        assertEquals(listOf(false, true), s.codes.map { it.isLocked })
        assertEquals("CENTER", s.deliver!!.text)
        assertNull("같은 번호는 한 번만", p.frame(10).deliver)
    }

    @Test
    fun multiNearest_withoutAutoDeliver_doesNotDeliver() {
        val s = ScanPipeline(ScanMode.Multi(MultiLock.Nearest), options()).frame()
        assertNotNull(s.locked)
        assertNull(s.deliver)
    }

    @Test
    fun multiTap_pinThenDeliverAfterStable_andSwitch() {
        val p = ScanPipeline(ScanMode.Multi(MultiLock.Tap, autoDeliver = true), options(stable = 100))
        val first = p.frame(0)
        assertNull(first.locked)
        val far = first.codes.first { it.result.text == "FAR" }
        p.pin(far.id)
        assertEquals(far.id, p.frame(10).locked!!.id)
        assertNull(p.frame(50).deliver)
        assertEquals("FAR", p.frame(110).deliver!!.text)
        // 다른 코드로 옮긴다
        val center = first.codes.first { it.result.text == "CENTER" }
        p.pin(center.id)
        assertEquals(center.id, p.frame(120).locked!!.id)
        assertEquals("CENTER", p.frame(220).deliver!!.text)
        // 해제
        p.release()
        assertNull(p.frame(230).locked)
    }

    @Test
    fun multiTap_pinnedCodeDisappears_unlocks() {
        val p = ScanPipeline(ScanMode.Multi(MultiLock.Tap), options())
        val far = p.frame().codes.first { it.result.text == "FAR" }
        p.pin(far.id)
        assertNotNull(p.frame(1).locked)
        val s = p.frame(2, listOf(detAt("CENTER", 245f, 325f)))
        assertNull(s.locked)
        assertNull("돌아와도 새 번호라 고정하지 않는다", p.frame(3).locked)
    }

    @Test
    fun multiCustom_usesAppChoice_andModeSwapTakesNewFunction() {
        val p = ScanPipeline(ScanMode.Multi(MultiLock.Custom { codes -> codes.firstOrNull { it.result.text == "FAR" } }), options())
        assertEquals("FAR", p.frame().locked!!.result.text)
        p.mode = ScanMode.Multi(MultiLock.Custom { codes -> codes.firstOrNull { it.result.text == "CENTER" } })
        assertEquals("CENTER", p.frame(1).locked!!.result.text)
    }

    @Test
    fun single_pinOverridesCenter() {
        val p = ScanPipeline(ScanMode.Single(), options())
        val first = p.frame()
        assertEquals("CENTER", first.locked!!.result.text)
        p.pin(first.codes.first { it.result.text == "FAR" }.id)
        assertEquals("FAR", p.frame(1).locked!!.result.text)
    }

    @Test
    fun reset_clearsPin() {
        val p = ScanPipeline(ScanMode.Multi(MultiLock.Tap), options())
        p.pin(p.frame().codes.first().id)
        p.reset()
        assertNull(p.frame(1).locked)
    }
}

/** 상태 — lockOn·unlock 이 흐름에 바로 반영되고, 탭 고정·탭 넘기기가 정책대로 나뉘는가. */
class ScannerStateLockTest {

    private fun state(mode: ScanMode) = ScannerState(mode, ScannerOptions(engine = ScanEngines.zxing())) { true }

    private fun tracked(id: Long, locked: Boolean = false) = TrackedCode(id, result("T$id"), box(0f, 0f, 10f, 10f), 0, locked)

    @Test
    fun lockOn_unknownId_orPaused_returnsFalse() {
        val s = state(ScanMode.Multi())
        assertFalse(s.lockOn(1))
        s.onSnapshot(ScanSnapshot(listOf(tracked(1)), null, null))
        s.setPaused(true)
        assertFalse(s.lockOn(1))
    }

    @Test
    fun lockOn_and_unlock_updateFlowsImmediately() {
        val s = state(ScanMode.Multi())
        s.onSnapshot(ScanSnapshot(listOf(tracked(1), tracked(2)), null, null))
        assertTrue(s.lockOn(2))
        assertEquals(2L, s.lockedCode.value!!.id)
        assertEquals(listOf(false, true), s.codes.value.map { it.isLocked })
        assertTrue(s.lockOn(1))
        assertEquals(listOf(true, false), s.codes.value.map { it.isLocked })
        assertTrue(s.unlock())
        assertNull(s.lockedCode.value)
        assertTrue(s.codes.value.none { it.isLocked })
        assertFalse("고정이 없으면 false", s.unlock())
    }

    @Test
    fun codeAreaTap_tapPolicy_togglesLock_withoutDelivering() {
        val s = state(ScanMode.Multi(MultiLock.Tap))
        s.onSnapshot(ScanSnapshot(listOf(tracked(1), tracked(2)), null, null))
        val delivered = ArrayList<ScanResult>()
        onCodeAreaTap(s, s.codes.value[0], delivered::add)
        assertEquals(1L, s.lockedCode.value!!.id)
        onCodeAreaTap(s, s.codes.value[1], delivered::add)
        assertEquals("다른 코드를 탭하면 옮긴다", 2L, s.lockedCode.value!!.id)
        onCodeAreaTap(s, s.codes.value[1], delivered::add)
        assertNull("고정된 코드를 다시 탭하면 푼다", s.lockedCode.value)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun codeAreaTap_otherPolicies_deliver() {
        for (mode in listOf(ScanMode.Single(), ScanMode.Multi(), ScanMode.Multi(MultiLock.Nearest))) {
            val s = state(mode)
            s.onSnapshot(ScanSnapshot(listOf(tracked(1)), null, null))
            val delivered = ArrayList<ScanResult>()
            onCodeAreaTap(s, s.codes.value[0], delivered::add)
            assertEquals(mode.toString(), listOf("T1"), delivered.map { it.text })
        }
    }

    @Test
    fun updateMode_onlyCustomFunctionMayChange() {
        val s = state(ScanMode.Multi(MultiLock.Custom { null }))
        val next = ScanMode.Multi(MultiLock.Custom { it.firstOrNull() })
        s.updateMode(next)
        assertSame(next, s.mode)
        try {
            s.updateMode(ScanMode.Multi(MultiLock.Custom { null }, autoDeliver = true))
            throw AssertionError("던져야 한다")
        } catch (e: IllegalArgumentException) {
            assertEquals("고정 선택 함수 밖의 모드가 바뀌면 상태를 새로 만든다", e.message)
        }
    }

    @Test
    fun rememberKey_ignoresCustomFunctionOnly() {
        assertEquals(ScanMode.Multi(MultiLock.Custom { null }).rememberKey(), ScanMode.Multi(MultiLock.Custom { it.firstOrNull() }).rememberKey())
        assertFalse(ScanMode.Multi(MultiLock.Custom { null }).rememberKey() == ScanMode.Multi(MultiLock.Custom { null }, true).rememberKey())
        assertEquals(ScanMode.Single(true), ScanMode.Single(true).rememberKey())
    }

    @Test
    fun dimmed_rules() {
        val locked = tracked(1, locked = true)
        val other = tracked(2)
        val single = dimmedPredicate(ScanMode.Single(), listOf(other))
        assertTrue("Single 의 고정 안 된 코드는 흐리다", single(other))
        val multiNone = dimmedPredicate(ScanMode.Multi(), listOf(other))
        assertFalse("고정이 없는 Multi 는 흐리지 않다", multiNone(other))
        val multiLocked = dimmedPredicate(ScanMode.Multi(MultiLock.Tap), listOf(locked, other))
        assertTrue(multiLocked(other))
        assertFalse(multiLocked(locked))
    }

    @Test
    fun boxStyle_rejectsBadLengths() {
        val c = androidx.compose.ui.graphics.Color.Red
        for (bad in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try {
                CodeBoxStyle(c, c, c, strokeWidth = androidx.compose.ui.unit.Dp(bad))
                throw AssertionError("던져야 한다: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.startsWith("strokeWidth("))
            }
        }
        assertEquals(CodeBoxStyle(c, c, c), CodeBoxStyle(c, c, c))
        assertEquals(CodeBoxStyle(c, c, c).hashCode(), CodeBoxStyle(c, c, c).hashCode())
        assertFalse(CodeBoxStyle(c, c, c) == CodeBoxStyle(c, c, c, cornerRadius = androidx.compose.ui.unit.Dp(1f)))
        assertTrue(CodeBoxStyle(c, c, c).toString().startsWith("CodeBoxStyle(color="))
        assertEquals("Custom", MultiLock.Custom { null }.toString())
        val f: (List<TrackedCode>) -> TrackedCode? = { null }
        assertEquals(MultiLock.Custom(f), MultiLock.Custom(f))
        assertEquals(MultiLock.Custom(f).hashCode(), MultiLock.Custom(f).hashCode())
        assertEquals(ScanMode.Multi(MultiLock.Tap).hashCode(), ScanMode.Multi(MultiLock.Tap).hashCode())
    }
}
