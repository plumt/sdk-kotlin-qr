package net.plumt.qr.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.plumt.qr.CodeFormat
import net.plumt.qr.scanner.internal.CameraSessionFactory

/** 테스트가 카메라 세션 대역을 넣는 자리. */
internal val LocalCameraSessionFactory = staticCompositionLocalOf { CameraSessionFactory.CameraX }

/** 상태 키에서 엔진 팩토리를 뺄 때 쓰는 자리표시. 만들지 않는다. */
private object EngineKeyPlaceholder : ScanEngine.Factory {
    override fun create(context: android.content.Context, formats: Set<CodeFormat>): ScanEngine =
        throw UnsupportedOperationException("자리표시 엔진이다")
}

/** 상태 키에서 [MultiLock.Custom] 의 함수를 뺄 때 쓰는 자리표시. */
private val CustomLockKeyPlaceholder: (List<TrackedCode>) -> TrackedCode? = { null }

/** 상태 키 — [MultiLock.Custom] 의 함수만 다른 모드는 같은 키다. */
internal fun ScanMode.rememberKey(): ScanMode =
    if (this is ScanMode.Multi && lock is MultiLock.Custom) ScanMode.Multi(MultiLock.Custom(CustomLockKeyPlaceholder), autoDeliver) else this

/**
 * 스캐너 상태를 만든다. [mode]·[options] 가 바뀌면 새 상태가 된다(카메라 세션도 새로 연다).
 * 권한은 앱의 `CAMERA` 권한을 그대로 읽는다 — SDK 는 요청하지 않는다.
 *
 * - 화면이 다시 보일 때(ON_RESUME) 권한을 다시 읽는다 — 시스템 권한 창에서 허용하고 돌아오면 앱이 따로 부르지 않아도
 *   [CameraStatus.PermissionRequired] 에서 벗어난다
 * - 엔진 팩토리([ScannerOptions.engine])만 다른 설정은 같은 상태로 본다 — 값을 캡처한 람다를 인라인으로 넘겨도 재구성마다
 *   카메라가 다시 열리지 않는다. 새 팩토리는 다음 카메라 세션부터 쓴다
 * - [MultiLock.Custom] 의 함수만 다른 모드도 같은 상태로 본다. 새 함수는 다음 프레임부터 쓴다
 */
@Composable
public fun rememberScannerState(mode: ScanMode, options: ScannerOptions = ScannerOptions()): ScannerState {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val state = remember(mode.rememberKey(), options.copy(engine = EngineKeyPlaceholder)) {
        ScannerState(mode, options) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
    }
    SideEffect {
        if (state.options.engine !== options.engine) state.updateEngine(options)
        if (state.mode != mode) state.updateMode(mode)
    }
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refreshPermission()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * 카메라 미리보기만 그리는 낮은 단계 컴포넌트. 박스·칩·제스처는 그리지 않는다 — [ScannerState] 의 값으로 직접 그린다.
 *
 * - 권한이 있을 때만 카메라를 연다. 화면이 다시 보일 때 권한을 다시 읽는 일은 [rememberScannerState] 가 한다
 * - [ScannerState.retry] 를 부르면 카메라를 다시 묶는다
 * - 이 컴포저블이 사라지거나 수명주기 소유자가 바뀌면 이 미리보기의 use case 둘만 풀고 엔진을 닫는다
 */
@Composable
public fun CodeScannerPreview(state: ScannerState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val status by state.cameraStatus.collectAsState()
    val session by state.session.collectAsState()
    val sessions = LocalCameraSessionFactory.current

    val previewView = remember(context, state) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = when (state.options.scaleMode) {
                PreviewScaleMode.FILL -> PreviewView.ScaleType.FILL_CENTER
                PreviewScaleMode.FIT -> PreviewView.ScaleType.FIT_CENTER
            }
        }
    }

    val granted = status != CameraStatus.PermissionRequired
    if (granted) {
        DisposableEffect(owner, state, previewView, session) {
            val binder = sessions.create(context, owner, previewView, state)
            binder.start()
            onDispose { binder.close() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.onSizeChanged { state.onViewSize(it.width.toFloat(), it.height.toFloat()) },
    )
}
