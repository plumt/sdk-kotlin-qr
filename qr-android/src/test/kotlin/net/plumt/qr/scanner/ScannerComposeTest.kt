package net.plumt.qr.scanner

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.camera.view.PreviewView
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

/** 컴포저블 흐름 — 권한 허용 뒤 전환(H1), 엔진 람다 재구성(M2), 재시도(L7). 카메라는 대역 세션으로 바꾼다. */
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
                CodeScanner(ScanMode.Multi, onCodeTap = {}, options = ScannerOptions(engine = ScanEngines.zxing()), permissionContent = { Text("권한 필요") })
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
                val state = rememberScannerState(ScanMode.Multi, ScannerOptions(formats = f, engine = ScanEngines.zxing()))
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
                state = rememberScannerState(ScanMode.Multi, ScannerOptions(engine = ScanEngines.zxing()))
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
}
