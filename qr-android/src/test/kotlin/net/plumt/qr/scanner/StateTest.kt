package net.plumt.qr.scanner

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.internal.ScanSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerStateTest {

    private var granted = true
    private fun state(mode: ScanMode = ScanMode.Single(), options: ScannerOptions = ScannerOptions(engine = ScanEngines.zxing())) =
        ScannerState(mode, options) { granted }

    private fun tracked(id: Long, b: CodeBounds, locked: Boolean = false, text: String = "T$id") =
        TrackedCode(id, result(text), b, 0, locked)

    @Test
    fun initialStatus_followsPermission() {
        granted = false
        assertEquals(CameraStatus.PermissionRequired, state().cameraStatus.value)
        granted = true
        val s = state()
        assertEquals(CameraStatus.Starting, s.cameraStatus.value)
        assertEquals(TorchState(false, false), s.torch.value)
        assertEquals(ZoomState(1f, 1f, 1f), s.zoom.value)
        assertFalse(s.isPaused.value)
        assertNull(s.error.value)
        assertTrue(s.codes.value.isEmpty())
        assertNull(s.lockedCode.value)
    }

    @Test
    fun refreshPermission_transitions() {
        granted = false
        val s = state()
        assertFalse(s.refreshPermission())
        assertEquals(CameraStatus.PermissionRequired, s.cameraStatus.value)
        granted = true
        assertTrue(s.refreshPermission())
        assertEquals(CameraStatus.Starting, s.cameraStatus.value)
        s.onCameraOpened(FakeCamera())
        assertTrue(s.refreshPermission())
        assertEquals("열린 카메라 상태는 그대로", CameraStatus.Running, s.cameraStatus.value)
        granted = false
        assertFalse(s.refreshPermission())
        assertEquals(CameraStatus.PermissionRequired, s.cameraStatus.value)
    }

    @Test
    fun controlsBeforeCameraOpen_returnFalse() {
        val s = state()
        assertFalse(s.setTorchEnabled(true))
        assertFalse(s.setZoomRatio(2f))
        assertFalse(s.zoomBy(2f))
        assertFalse(s.focusAt(1f, 1f))
    }

    @Test
    fun controlsWithCamera() {
        val s = state()
        val cam = FakeCamera()
        s.onCameraOpened(cam)
        assertEquals(CameraStatus.Running, s.cameraStatus.value)
        assertEquals(TorchState(available = true, enabled = false), s.torch.value)
        assertTrue(s.setTorchEnabled(true))
        assertEquals(listOf(true), cam.torch)
        s.onTorchChanged(true)
        assertEquals(TorchState(true, true), s.torch.value)

        s.onZoomChanged(1f, 1f, 4f)
        assertTrue(s.setZoomRatio(10f))
        assertTrue(s.setZoomRatio(0.1f))
        assertTrue(s.setZoomRatio(2.5f))
        assertEquals(listOf(4f, 1f, 2.5f), cam.zoom)
        s.onZoomChanged(2f, 1f, 4f)
        assertTrue(s.zoomBy(1.5f))
        assertEquals(3f, cam.zoom.last(), EPS)

        assertTrue(s.focusAt(10f, 20f))
        assertEquals(listOf(10f to 20f), cam.focus)
    }

    @Test
    fun torchWithoutFlash_returnsFalse() {
        val s = state()
        val cam = FakeCamera(hasFlash = false)
        s.onCameraOpened(cam)
        assertEquals(TorchState(false, false), s.torch.value)
        assertFalse(s.setTorchEnabled(true))
        assertTrue(cam.torch.isEmpty())
    }

    @Test
    fun controls_rejectBadArguments() {
        val s = state()
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(assertThrows<IllegalArgumentException> { s.setZoomRatio(bad) }.message!!.contains("ratio($bad)"))
            assertTrue(assertThrows<IllegalArgumentException> { s.zoomBy(bad) }.message!!.contains("factor($bad)"))
        }
        assertThrows<IllegalArgumentException> { s.focusAt(Float.NaN, 0f) }
        assertThrows<IllegalArgumentException> { s.focusAt(0f, Float.NEGATIVE_INFINITY) }
    }

    @Test
    fun snapshotsPublish_andPauseClears() {
        val s = state()
        val a = tracked(1, box(0f, 0f, 10f, 10f), locked = true)
        s.onSnapshot(ScanSnapshot(listOf(a), a, null))
        assertEquals(listOf(a), s.codes.value)
        assertSame(a, s.lockedCode.value)
        s.setPaused(true)
        assertTrue(s.isPaused.value)
        assertTrue(s.codes.value.isEmpty())
        assertNull(s.lockedCode.value)
        // 멈춘 동안 늦게 도착한 결과는 버린다
        s.onSnapshot(ScanSnapshot(listOf(a), a, null))
        assertTrue(s.codes.value.isEmpty())
        s.setPaused(true)
        s.setPaused(false)
        assertFalse(s.isPaused.value)
        assertTrue("재시작 뒤 첫 프레임에 기록을 지운다", s.consumeResetFlag())
        assertFalse(s.consumeResetFlag())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deliveries_emitted() = runTest(UnconfinedTestDispatcher()) {
        val s = state(ScanMode.Single(autoDeliver = true))
        val got = ArrayList<ScanResult>()
        val job = launch { s.deliveries.collect { got += it } }
        val r = result("A")
        s.onSnapshot(ScanSnapshot(emptyList(), null, r))
        s.onSnapshot(ScanSnapshot(emptyList(), null, null))
        assertEquals(listOf(r), got)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deliveries_bufferedWithoutCollector_dropOldest() = runTest(UnconfinedTestDispatcher()) {
        val s = state()
        // 구독자가 없으면 SharedFlow 는 버퍼에 쌓지 않고 버린다(replay 0) — 놓친 것을 다시 내지 않는다
        repeat(20) { s.onSnapshot(ScanSnapshot(emptyList(), null, result("X$it"))) }
        val first = async { s.deliveries.first() }
        s.onSnapshot(ScanSnapshot(emptyList(), null, result("LATE")))
        assertEquals("LATE", first.await().text)
    }

    @Test
    fun codeAt_picksContainingNearestCenter() {
        val s = state(ScanMode.Multi)
        val big = tracked(1, box(0f, 0f, 100f, 100f))
        val small = tracked(2, box(50f, 40f, 70f, 60f))
        s.onSnapshot(ScanSnapshot(listOf(big, small), null, null))
        assertEquals(2L, s.codeAt(62f, 50f)!!.id)
        // 가운데 거리가 같으면 목록 앞쪽
        val twin = tracked(3, box(0f, 0f, 100f, 100f))
        s.onSnapshot(ScanSnapshot(listOf(big, twin), null, null))
        assertEquals(1L, s.codeAt(50f, 50f)!!.id)
        assertEquals(1L, s.codeAt(10f, 10f)!!.id)
        assertNull(s.codeAt(150f, 10f))
        assertEquals(1L, s.codeAt(100f, 100f)!!.id) // 경계 포함
    }

    @Test
    fun errorsAndCameraLifecycle() {
        val s = state()
        val e = ScannerException("엔진")
        s.onError(e)
        assertSame(e, s.error.value)
        s.clearError()
        assertNull(s.error.value)

        val cam = ScannerException("카메라", IllegalStateException())
        s.onCameraError(cam)
        assertEquals(CameraStatus.Error(cam), s.cameraStatus.value)
        assertSame(cam, s.error.value)
        assertFalse(s.setTorchEnabled(true))

        s.onNoCamera()
        assertEquals(CameraStatus.NoCamera, s.cameraStatus.value)

        s.onCameraOpened(FakeCamera())
        s.onSnapshot(ScanSnapshot(listOf(tracked(1, box(0f, 0f, 1f, 1f))), null, null))
        s.onCameraClosed()
        assertEquals(CameraStatus.Starting, s.cameraStatus.value)
        assertTrue(s.codes.value.isEmpty())
        assertEquals(TorchState(false, false), s.torch.value)
        assertFalse(s.focusAt(0f, 0f))

        // 권한 없이 닫히면 권한 필요로
        s.onCameraOpened(FakeCamera())
        granted = false
        s.onCameraClosed()
        assertEquals(CameraStatus.PermissionRequired, s.cameraStatus.value)
        // 오류 상태에서 닫히면 오류를 지우지 않는다
        granted = true
        s.onCameraError(cam)
        s.onCameraClosed()
        assertEquals(CameraStatus.Error(cam), s.cameraStatus.value)
    }

    @Test
    fun newAnalyzer_wiresStateAndClosesPrevious() {
        val s = state(ScanMode.Multi)
        s.onViewSize(480f, 640f)
        val e1 = FakeEngine { listOf(detAt("A", 100f, 100f)) }
        val a1 = s.newAnalyzer(e1)
        a1.analyze(ScanFrame(ByteArray(480 * 640), 480, 640))
        assertEquals("A", s.codes.value.single().result.text)
        val a2 = s.newAnalyzer(FakeEngine())
        // 이전 분석기는 닫혔다
        a1.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertEquals(1, e1.calls)
        a2.analyze(ScanFrame(ByteArray(480 * 640), 480, 640))
        assertTrue(s.codes.value.isEmpty())
        // 엔진 오류가 상태로 온다
        val bad = s.newAnalyzer(FakeEngine { throw ScannerException("x") })
        bad.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertEquals("x", s.error.value!!.message)
        // 멈추면 분석기가 엔진을 부르지 않는다
        val e3 = FakeEngine()
        val a3 = s.newAnalyzer(e3)
        s.setPaused(true)
        a3.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertEquals(0, e3.calls)
        s.onCameraClosed()
        s.setPaused(false)
        a3.analyze(ScanFrame(ByteArray(4), 2, 2))
        assertEquals("닫힌 세션의 분석기", 0, e3.calls)
    }

    @Test
    fun exposesModeAndOptions() {
        val o = ScannerOptions(engine = ScanEngines.zxing(), lens = CameraLens.FRONT)
        val s = state(ScanMode.Multi, o)
        assertSame(ScanMode.Multi, s.mode)
        assertSame(o, s.options)
    }
}
