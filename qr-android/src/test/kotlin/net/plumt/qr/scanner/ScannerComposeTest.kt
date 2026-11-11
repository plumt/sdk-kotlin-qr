package net.plumt.qr.scanner

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.internal.ScanSnapshot
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import net.plumt.qr.CodeFormat
import net.plumt.qr.scanner.internal.CameraSession
import net.plumt.qr.scanner.internal.CameraSessionFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** 컴포저블 흐름 — 권한 허용 뒤 전환(H1), 엔진 람다 재구성(M2), 재시도(L7), 오버레이 슬롯·탭 판정. 카메라는 대역 세션으로 바꾼다. */
@RunWith(RobolectricTestRunner::class)
class ScannerComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class FakeSessions : CameraSessionFactory {
        var created = 0
        var started = 0
        var closed = 0
        var lastState: ScannerState? = null
        override fun create(context: Context, owner: LifecycleOwner, previewView: PreviewView, state: ScannerState): CameraSession {
            created++
            lastState = state
            return object : CameraSession {
                override fun start() {
                    started++
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
    private fun deny() = shadowOf(app).denyPermissions(Manifest.permission.CAMERA)

    @Test
    fun permissionGrantedWhileShown_switchesToPreviewOnResume() {
        deny()
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val sessions = FakeSessions()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalCameraSessionFactory provides sessions) {
                // 기본 사용 — state 를 넘기지 않아 앱은 refreshPermission 을 부를 수 없다
                CodeScanner(
                    mode = ScanMode.Single(),
                    onCodeTap = {},
                    options = ScannerOptions(engine = ScanEngines.zxing()),
                    permissionContent = { Text("권한 필요") },
                )
            }
        }
        rule.onNodeWithText("권한 필요").assertExists()
        assertEquals(0, sessions.created)

        // 시스템 권한 창: 화면이 멈췄다가(ON_PAUSE) 허용 뒤 다시 보인다(ON_RESUME)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        grant()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()

        rule.onNodeWithText("권한 필요").assertDoesNotExist()
        assertEquals("미리보기가 카메라 세션을 연다", 1, sessions.created)
        assertEquals(1, sessions.started)
        assertEquals(CameraStatus.Starting, sessions.lastState!!.cameraStatus.value)
    }

    @Test
    fun permissionRevokedWhileBackgrounded_returnsToPermissionContent() {
        grant()
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val sessions = FakeSessions()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalCameraSessionFactory provides sessions) {
                CodeScanner(ScanMode.Multi(), onCodeTap = {}, options = ScannerOptions(engine = ScanEngines.zxing()), permissionContent = { Text("권한 필요") })
            }
        }
        rule.onNodeWithText("권한 필요").assertDoesNotExist()
        assertEquals(1, sessions.created)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        deny()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        rule.onNodeWithText("권한 필요").assertExists()
        assertEquals("권한이 사라지면 세션을 닫는다", 1, sessions.closed)
    }

    @Test
    fun inlineEngineLambda_recomposition_keepsStateAndCameraSession() {
        grant()
        val sessions = FakeSessions()
        var tick by mutableIntStateOf(0)
        val states = ArrayList<ScannerState>()
        var latest: ScanEngine.Factory? = null
        rule.setContent {
            CompositionLocalProvider(LocalCameraSessionFactory provides sessions) {
                val threshold = tick
                // 값을 캡처한 람다 — 재구성마다 새 인스턴스다
                val options = ScannerOptions(engine = { ctx, f -> ScanEngines.zxing(tryHarder = threshold > 100).create(ctx, f) })
                latest = options.engine
                val state = rememberScannerState(ScanMode.Single(), options)
                states += state
                CodeScannerPreview(state)
            }
        }
        repeat(3) {
            rule.runOnIdle { tick++ }
            rule.waitForIdle()
        }
        assertTrue(states.size >= 4)
        assertTrue("엔진 람다만 바뀌면 같은 상태", states.all { it === states.first() })
        assertEquals("카메라를 다시 열지 않는다", 1, sessions.created)
        assertEquals(0, sessions.closed)
        assertSame("상태의 엔진은 최신 팩토리다(다음 세션부터 쓴다)", latest, states.last().options.engine)
    }

    @Test
    fun otherOptionChange_createsNewState() {
        grant()
        val sessions = FakeSessions()
        var formats by mutableIntStateOf(0)
        val states = ArrayList<ScannerState>()
        rule.setContent {
            CompositionLocalProvider(LocalCameraSessionFactory provides sessions) {
                val f = if (formats == 0) CodeFormat.QR_ONLY else CodeFormat.PRODUCT
                val state = rememberScannerState(ScanMode.Multi(), ScannerOptions(formats = f, engine = ScanEngines.zxing()))
                states += state
                CodeScannerPreview(state)
            }
        }
        rule.runOnIdle { formats = 1 }
        rule.waitForIdle()
        assertNotSame(states.first(), states.last())
        assertEquals(CodeFormat.PRODUCT, states.last().options.formats)
        assertEquals(2, sessions.created)
        assertEquals(1, sessions.closed)
    }

    @Test
    fun retryAfterCameraError_rebindsSession() {
        grant()
        val sessions = FakeSessions()
        lateinit var state: ScannerState
        rule.setContent {
            CompositionLocalProvider(LocalCameraSessionFactory provides sessions) {
                state = rememberScannerState(ScanMode.Multi(), ScannerOptions(engine = ScanEngines.zxing()))
                CodeScannerPreview(state)
            }
        }
        assertEquals(1, sessions.created)
        rule.runOnIdle {
            assertFalse("오류가 아니면 재시도하지 않는다", state.retry())
            state.onCameraError(ScannerException("사용 중"))
            assertTrue(state.retry())
        }
        rule.waitForIdle()
        assertEquals(2, sessions.created)
        assertEquals(1, sessions.closed)
        assertEquals(CameraStatus.Starting, state.cameraStatus.value)
    }

    // ---- 오버레이 슬롯 ----

    private fun tracked(id: Long, l: Float, t: Float, r: Float, b: Float, locked: Boolean = false) =
        TrackedCode(id, result("T$id"), box(l, t, r, b), 0, locked)

    /** 스캐너를 300dp 정사각형에 띄우고 상태를 돌려준다. */
    private fun show(
        mode: ScanMode,
        onTap: (ScanResult) -> Unit = {},
        tapToDeliver: Boolean = true,
        box: (@androidx.compose.runtime.Composable (TrackedCode, Boolean) -> Unit)? = { code, dimmed -> QrCodeBox(code, dimmed) },
        chip: (@androidx.compose.runtime.Composable (TrackedCode, () -> Unit) -> Unit)? = { code, onClick -> QrCodeChip(code, onClick) },
    ): ScannerState {
        grant()
        lateinit var state: ScannerState
        rule.setContent {
            CompositionLocalProvider(LocalCameraSessionFactory provides FakeSessions()) {
                state = rememberScannerState(mode, ScannerOptions(engine = ScanEngines.zxing(), tapToFocus = false, pinchToZoom = false))
                Box(Modifier.size(300.dp)) {
                    CodeScanner(
                        mode = mode,
                        onCodeTap = onTap,
                        modifier = Modifier.fillMaxSize(),
                        state = state,
                        box = box,
                        tapToDeliver = tapToDeliver,
                        chip = chip,
                    )
                }
            }
        }
        rule.waitForIdle()
        return state
    }

    private fun tapAt(x: Float, y: Float) {
        rule.onNodeWithTag(CODE_TAP_LAYER_TAG).performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()
    }

    @Test
    fun hiddenOverlays_cameraOnly_tapStillDelivers() {
        val taps = ArrayList<String>()
        val state = show(ScanMode.Single(autoDeliver = true), onTap = { taps += it.text }, box = null, chip = null)
        rule.runOnIdle {
            val a = tracked(1, 10f, 10f, 60f, 60f, locked = true)
            state.onSnapshot(ScanSnapshot(listOf(a), a, null))
        }
        rule.waitForIdle()
        rule.onNodeWithText("T1").assertDoesNotExist()
        tapAt(30f, 30f)
        assertEquals("박스·칩을 숨겨도 코드 자리 탭을 판정한다", listOf("T1"), taps)
        tapAt(200f, 200f)
        assertEquals(listOf("T1"), taps)
    }

    @Test
    fun autoDeliver_reachesOnCodeTap_withHiddenOverlays() {
        val taps = ArrayList<String>()
        val state = show(ScanMode.Single(autoDeliver = true), onTap = { taps += it.text }, box = null, chip = null)
        rule.runOnIdle {
            val a = tracked(1, 10f, 10f, 60f, 60f, locked = true)
            state.onSnapshot(ScanSnapshot(listOf(a), a, a.result))
        }
        rule.waitForIdle()
        assertEquals(listOf("T1"), taps)
    }

    @Test
    fun tapToDeliverOff_tapDoesNothing() {
        val taps = ArrayList<String>()
        val state = show(ScanMode.Multi(), onTap = { taps += it.text }, tapToDeliver = false)
        rule.runOnIdle { state.onSnapshot(ScanSnapshot(listOf(tracked(1, 10f, 10f, 60f, 60f)), null, null)) }
        rule.waitForIdle()
        tapAt(30f, 30f)
        rule.onNodeWithText("T1").performClick()
        rule.waitForIdle()
        assertTrue(taps.isEmpty())
    }

    @Test
    fun customBoxAndChip_renderPerCode() {
        val dimmedSeen = HashMap<Long, Boolean>()
        val state = show(
            ScanMode.Multi(MultiLock.Tap),
            box = { code, dimmed ->
                dimmedSeen[code.id] = dimmed
                Box(Modifier.fillMaxSize().testTag("box-${code.id}"))
            },
            chip = { code, _ -> Text("chip-${code.id}-${code.isLocked}") },
        )
        rule.runOnIdle {
            val a = tracked(1, 10f, 10f, 60f, 60f, locked = true)
            state.onSnapshot(ScanSnapshot(listOf(a, tracked(2, 100f, 100f, 150f, 150f)), a, null))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("box-1").assertExists()
        rule.onNodeWithTag("box-2").assertExists()
        rule.onNodeWithText("chip-1-true").assertExists()
        rule.onNodeWithText("chip-2-false").assertExists()
        assertEquals(mapOf(1L to false, 2L to true), dimmedSeen)
        rule.onNodeWithText("T1").assertDoesNotExist()
    }

    @Test
    fun tapPolicy_tapMovesLock_chipStillDelivers() {
        val taps = ArrayList<String>()
        val state = show(ScanMode.Multi(MultiLock.Tap), onTap = { taps += it.text }, box = null)
        rule.runOnIdle {
            state.onSnapshot(ScanSnapshot(listOf(tracked(1, 10f, 10f, 60f, 60f), tracked(2, 150f, 10f, 200f, 60f)), null, null))
        }
        rule.waitForIdle()
        tapAt(30f, 30f)
        assertEquals(1L, state.lockedCode.value?.id)
        tapAt(170f, 30f)
        assertEquals(2L, state.lockedCode.value?.id)
        tapAt(170f, 30f)
        assertEquals(null, state.lockedCode.value)
        assertTrue("박스 자리 탭은 넘기지 않는다", taps.isEmpty())
        rule.onAllNodesWithTag(CODE_TAP_LAYER_TAG).assertCountEquals(1)
    }
}
