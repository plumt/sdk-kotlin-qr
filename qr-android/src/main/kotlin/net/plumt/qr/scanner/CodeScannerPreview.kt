package net.plumt.qr.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.plumt.qr.scanner.internal.CameraXBinder

/**
 * 스캐너 상태를 만든다. [mode]·[options] 가 바뀌면 새 상태가 된다(카메라 세션도 새로 연다).
 * 권한은 앱의 `CAMERA` 권한을 그대로 읽는다 — SDK 는 요청하지 않는다.
 */
@Composable
public fun rememberScannerState(mode: ScanMode, options: ScannerOptions = ScannerOptions()): ScannerState {
    val context = LocalContext.current.applicationContext
    return remember(mode, options) {
        ScannerState(mode, options) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * 카메라 미리보기만 그리는 낮은 단계 컴포넌트. 박스·칩·제스처는 그리지 않는다 — [ScannerState] 의 값으로 직접 그린다.
 *
 * - 권한이 있을 때만 카메라를 연다. 화면이 다시 보일 때(ON_RESUME) 권한을 다시 읽는다
 * - 이 컴포저블이 사라지거나 수명주기 소유자가 바뀌면 이 미리보기의 use case 둘만 풀고 엔진을 닫는다
 */
@Composable
public fun CodeScannerPreview(state: ScannerState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val status by state.cameraStatus.collectAsState()

    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refreshPermission()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

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
        DisposableEffect(owner, state, previewView) {
            val binder = CameraXBinder(context, owner, previewView, state)
            binder.start()
            onDispose { binder.close() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.onSizeChanged { state.onViewSize(it.width.toFloat(), it.height.toFloat()) },
    )
}
