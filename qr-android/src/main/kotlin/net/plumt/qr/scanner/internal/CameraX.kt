package net.plumt.qr.scanner.internal

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import net.plumt.qr.scanner.AnalysisResolution
import net.plumt.qr.scanner.CameraHandle
import net.plumt.qr.scanner.CameraLens
import net.plumt.qr.scanner.ScannerException
import net.plumt.qr.scanner.ScannerState
import net.plumt.qr.scanner.ScanEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 바인딩. 미리보기·분석 두 use case 를 [owner] 수명주기에 묶고, [close] 에서 이 두 개만 푼다
 * (`unbindAll` 을 쓰지 않는다 — 같은 앱의 다른 카메라 화면을 끊지 않게).
 *
 * 순서: 제공자 얻기(비동기) → 카메라 유무 → 엔진 생성 → 분석기 연결 → 바인딩.
 * 바인딩 전에 [close] 가 불리면 늦게 도착한 제공자로는 아무것도 묶지 않는다.
 * 해제: 분석기 떼기 → use case 풀기 → 분석 스레드에 엔진 닫기를 넣고 스레드를 내린다(돌던 프레임이 끝난 뒤 닫힌다).
 */
internal class CameraXBinder(
    context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val state: ScannerState,
) : CameraSession {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = AnalysisExecutor.create()
    private var closed = false
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var engine: ScanEngine? = null
    private var camera: Camera? = null
    private val torchObserver = Observer<Int> { state.onTorchChanged(it == androidx.camera.core.TorchState.ON) }
    private val zoomObserver = Observer<androidx.camera.core.ZoomState> { state.onZoomChanged(it.zoomRatio, it.minZoomRatio, it.maxZoomRatio) }

    override fun start() {
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (closed) return@addListener
            try {
                bind(future.get())
            } catch (e: ScannerException) {
                releaseAfterFailure()
                state.onCameraError(e)
            } catch (e: Exception) {
                releaseAfterFailure()
                state.onCameraError(ScannerException("카메라를 열지 못했다", e))
            }
        }, mainExecutor)
    }

    private fun bind(provider: ProcessCameraProvider) {
        this.provider = provider
        val options = state.options
        val selector = if (options.lens == CameraLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) {
            state.onNoCamera()
            return
        }
        val engine = options.engine.create(appContext, options.formats).also { this.engine = it }
        val target = if (options.effectiveResolution == AnalysisResolution.HIGH) Size(1280, 960) else Size(640, 480)
        val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(analysisExecutor, state.newAnalyzer(engine))
        this.preview = preview
        this.analysis = analysis
        val camera = provider.bindToLifecycle(owner, selector, preview, analysis)
        this.camera = camera
        camera.cameraInfo.torchState.observe(owner, torchObserver)
        camera.cameraInfo.zoomState.observe(owner, zoomObserver)
        state.onCameraOpened(CameraXHandle(camera, previewView))
    }

    /** 바인딩 도중 실패 — 만든 엔진·분석기를 거둔다. 상태는 호출부가 오류로 바꾼다. */
    private fun releaseAfterFailure() {
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, analysis).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        val engine = engine
        analysisExecutor.execute { engine?.close() }
        this.engine = null
        this.preview = null
        this.analysis = null
    }

    override fun close() {
        if (closed) return
        closed = true
        camera?.cameraInfo?.torchState?.removeObserver(torchObserver)
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, analysis).toTypedArray()
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
        state.onCameraClosed()
        val engine = engine
        analysisExecutor.execute { engine?.close() }
        analysisExecutor.shutdown()
        this.engine = null
        this.camera = null
        this.preview = null
        this.analysis = null
    }
}

/** 미리보기 하나의 카메라 세션. [start] 로 열고 [close] 로 푼다. 메인 스레드에서 부른다. */
internal interface CameraSession : AutoCloseable {
    fun start()
    override fun close()
}

/** 카메라 세션을 만든다. 기본은 CameraX, 테스트는 대역을 넣는다. */
internal fun interface CameraSessionFactory {
    fun create(context: Context, owner: LifecycleOwner, previewView: PreviewView, state: ScannerState): CameraSession

    companion object {
        val CameraX: CameraSessionFactory = CameraSessionFactory(::CameraXBinder)
    }
}

/**
 * 프레임 분석 전용 스레드. 인식(엔진)은 블로킹이라 메인 스레드에서 돌리면 안 된다 — CameraX 의 분석기는 이 스레드 하나에서만 불린다.
 * 데몬 스레드라 세션을 닫지 못한 채 프로세스가 끝나도 종료를 막지 않는다.
 */
internal object AnalysisExecutor {
    const val THREAD_NAME = "plumt-qr-analysis"

    fun create(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
}

/** 열린 CameraX 카메라 조작. 초점 좌표는 [PreviewView] 의 측광 점 공장이 뷰 좌표 → 센서 좌표로 바꾼다. */
internal class CameraXHandle(private val camera: Camera, private val previewView: PreviewView) : CameraHandle {
    override val hasFlash: Boolean get() = camera.cameraInfo.hasFlashUnit()

    override fun enableTorch(enabled: Boolean) {
        camera.cameraControl.enableTorch(enabled)
    }

    override fun setZoomRatio(ratio: Float) {
        camera.cameraControl.setZoomRatio(ratio)
    }

    override fun focus(viewX: Float, viewY: Float) {
        val point = previewView.meteringPointFactory.createPoint(viewX, viewY)
        camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }
}
