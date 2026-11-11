package net.plumt.qr.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import net.plumt.qr.CodeFormat
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 실시간 QR·바코드 스캐너. 카메라 미리보기 위에 코드 박스와 칩(종류 + 요약)을 그린다.
 *
 * - [ScanMode.Single]: 가운데에 가장 가까운 코드에 고정해 그 코드의 칩만 띄운다. `autoDeliver` 면 탭 없이 [onCodeTap] 을 부른다
 * - [ScanMode.Multi]: 보이는 코드마다 칩을 띄운다. 고정은 [MultiLock] 으로 고르고, `autoDeliver` 면 고정된 코드를 탭 없이 넘긴다
 * - 칩·박스 자리를 탭하면 그 코드로 [onCodeTap] 을 부른다(메인 스레드). 박스를 숨겨도 박스 자리 탭은 그대로 판정한다.
 *   [MultiLock.Tap] 에서는 박스 자리 탭이 넘기기 대신 고정을 옮기거나 푼다. 코드가 아닌 곳을 탭하면 초점을 맞추고, 두 손가락으로 확대한다
 * - SDK 는 결과로 아무것도 실행하지 않는다 — 링크 열기·Wi-Fi 연결은 앱이 [net.plumt.qr.scanner.action.QrActionExecutor] 로 한다
 * - 카메라 권한은 앱이 요청한다. 권한이 없으면 [permissionContent] 를 그린다
 *
 * 카메라 화면만 띄우려면 `box = null, chip = null` 로 오버레이를 숨긴다. [ScanMode.Single] 의 `autoDeliver` 와 함께 쓰면
 * 가운데 코드를 탭 없이 넘기는 「카메라만」 스캐너가 된다.
 *
 * @param onCodeTap 넘긴 결과. 탭과 자동 넘기기 모두 이것으로 온다
 * @param onError 카메라·엔진 오류. 엔진 오류는 카메라 세션마다 처음 한 건만 온다
 * @param state 낮은 단계 상태. 플래시·확대·멈춤·고정을 밖에서 조작하려면 [rememberScannerState] 로 만들어 넘긴다
 * @param onFrame 분석한 카메라 프레임을 함께 받는다(분석 스레드). 같은 카메라로 글자 인식 같은 다른 분석을 돌릴 때 쓴다 — [FrameListener]
 * @param box 코드마다 그릴 박스. 보이는 코드 전부에 불리고, 코드 자리(미리보기 좌표의 [TrackedCode.bounds])에 그 크기로 놓인다.
 *   `dimmed` 는 기본 박스가 흐리게 그릴 코드인가 — 고정되지 않았는데 다른 코드가 고정돼 있거나 [ScanMode.Single] 일 때 참.
 *   기본 [QrCodeBox], 모양만 바꾸려면 [ScannerDefaults.boxStyle] 을 넘긴 [QrCodeBox]. null 이면 그리지 않는다.
 *   탭을 받는 컴포넌트를 그리면 그 자리 탭은 스캐너가 받지 않는다
 * @param tapToDeliver false 면 박스 자리·칩 탭이 결과를 넘기지 않는다(자동 넘기기만 넘긴다). [MultiLock.Tap] 의 탭 고정은 그대로다
 * @param chip 칩. [ScanMode.Single] 은 고정된 코드에만, [ScanMode.Multi] 는 보이는 코드 전부에 불리고 박스 아래(모자라면 위)에 놓인다.
 *   `onClick` 은 그 코드를 넘긴다([tapToDeliver] 가 false 면 아무것도 하지 않는다). 기본 [QrCodeChip], null 이면 그리지 않는다
 */
@Composable
public fun CodeScanner(
    mode: ScanMode,
    onCodeTap: (ScanResult) -> Unit,
    modifier: Modifier = Modifier,
    options: ScannerOptions = ScannerOptions(),
    state: ScannerState = rememberScannerState(mode, options),
    onError: (ScannerException) -> Unit = {},
    permissionContent: @Composable () -> Unit = {},
    onFrame: FrameListener? = null,
    box: (@Composable (code: TrackedCode, dimmed: Boolean) -> Unit)? = { code, dimmed -> QrCodeBox(code = code, dimmed = dimmed) },
    tapToDeliver: Boolean = true,
    chip: (@Composable (code: TrackedCode, onClick: () -> Unit) -> Unit)? = { code, onClick ->
        QrCodeChip(code = code, onClick = onClick, zone = state.options.zone)
    },
) {
    val tap by rememberUpdatedState(onCodeTap)
    val error by rememberUpdatedState(onError)
    val delivering by rememberUpdatedState(tapToDeliver)
    val status by state.cameraStatus.collectAsState()
    val codes by state.codes.collectAsState()

    val frame by rememberUpdatedState(onFrame)
    val listening = onFrame != null
    DisposableEffect(state, listening) {
        if (listening) state.setFrameListener { f, d -> frame?.onFrame(f, d) }
        onDispose { if (listening) state.setFrameListener(null) }
    }

    LaunchedEffect(state) { state.deliveries.collect { tap(it) } }
    LaunchedEffect(state) {
        state.error.filterNotNull().collect {
            error(it)
            state.clearError()
        }
    }

    Box(modifier) {
        if (status == CameraStatus.PermissionRequired) {
            permissionContent()
            return@Box
        }
        CodeScannerPreview(state, Modifier.fillMaxSize())
        CodeTapLayer(state, onDeliver = { if (delivering) tap(it) })
        if (box != null) BoxLayer(codes, dimmedPredicate(state.mode, codes), box)
        if (chip != null) {
            val shown = if (state.mode is ScanMode.Single) codes.filter { it.isLocked } else codes
            ChipLayer(shown, chip) { if (delivering) tap(it.result) }
        }
    }
}

/** 기본 박스가 흐리게 그릴 코드를 고르는 함수. */
internal fun dimmedPredicate(mode: ScanMode, codes: List<TrackedCode>): (TrackedCode) -> Boolean {
    val anyLocked = codes.any { it.isLocked }
    return when (mode) {
        is ScanMode.Single -> { code -> !code.isLocked }
        is ScanMode.Multi -> { code -> anyLocked && !code.isLocked }
    }
}

/** 탭(코드 판정·초점)과 두 손가락 확대를 받는 바닥 층. 박스·칩을 숨겨도 이 층이 판정한다. */
@Composable
private fun CodeTapLayer(state: ScannerState, onDeliver: (ScanResult) -> Unit) {
    val deliver by rememberUpdatedState(onDeliver)
    Spacer(
        Modifier
            .fillMaxSize()
            .testTag(CODE_TAP_LAYER_TAG)
            .pointerInputs(state, onTap = { onCodeAreaTap(state, it, deliver) }),
    )
}

internal const val CODE_TAP_LAYER_TAG = "plumt_qr_code_tap_layer"

/** 코드 자리 탭. [MultiLock.Tap] 이면 고정을 옮기거나 풀고, 아니면 넘긴다. */
internal fun onCodeAreaTap(state: ScannerState, code: TrackedCode, deliver: (ScanResult) -> Unit) {
    val mode = state.mode
    if (mode is ScanMode.Multi && mode.lock == MultiLock.Tap) {
        if (state.lockedCode.value?.id == code.id) state.unlock() else state.lockOn(code.id)
    } else {
        deliver(code.result)
    }
}

private fun Modifier.pointerInputs(state: ScannerState, onTap: (TrackedCode) -> Unit): Modifier {
    val taps = pointerInput(state) {
        detectTapGestures { pos ->
            val hit = state.codeAt(pos.x, pos.y)
            when {
                hit != null -> onTap(hit)
                state.options.tapToFocus -> state.focusAt(pos.x, pos.y)
            }
        }
    }
    if (!state.options.pinchToZoom) return taps
    return taps.pointerInput(state, "zoom") {
        detectTransformGestures { _, _, zoom, _ -> if (zoom.isFinite() && zoom > 0f && zoom != 1f) state.zoomBy(zoom) }
    }
}

/** 박스를 코드 자리에 그 크기로 놓는다. */
@Composable
private fun BoxLayer(
    codes: List<TrackedCode>,
    dimmed: (TrackedCode) -> Boolean,
    box: @Composable (TrackedCode, Boolean) -> Unit,
) {
    Layout(
        content = { codes.forEach { code -> key(code.id) { Box { box(code, dimmed(code)) } } } },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, m ->
            val b = codes[i].bounds
            m.measure(Constraints.fixed(b.width.roundToInt().coerceAtLeast(0), b.height.roundToInt().coerceAtLeast(0)))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p -> p.place(codes[i].bounds.left.roundToInt(), codes[i].bounds.top.roundToInt()) }
        }
    }
}

/**
 * 코드 박스 모양. [ScannerDefaults.boxStyle] 로 만든다(색을 테마에서 가져온다).
 *
 * @property color 고정이 없을 때 [ScanMode.Multi] 코드의 선 색
 * @property lockedColor 고정된 코드의 선 색
 * @property dimmedColor 흐리게 그리는 코드(고정되지 않았는데 다른 코드가 고정됐거나 [ScanMode.Single])의 선 색
 * @property strokeWidth [color] 선 두께
 * @property lockedStrokeWidth [lockedColor] 선 두께
 * @property dimmedStrokeWidth [dimmedColor] 선 두께
 * @property cornerRadius 모서리 반지름
 * @throws IllegalArgumentException 두께·반지름이 음수이거나 유한하지 않을 때 (생성 시)
 */
@Immutable
public class CodeBoxStyle(
    public val color: Color,
    public val lockedColor: Color,
    public val dimmedColor: Color,
    public val strokeWidth: Dp = ScannerDefaults.BoxStrokeWidth,
    public val lockedStrokeWidth: Dp = ScannerDefaults.BoxStrokeWidth,
    public val dimmedStrokeWidth: Dp = ScannerDefaults.DimmedBoxStrokeWidth,
    public val cornerRadius: Dp = ScannerDefaults.BoxCornerRadius,
) {
    init {
        requireLength("strokeWidth", strokeWidth)
        requireLength("lockedStrokeWidth", lockedStrokeWidth)
        requireLength("dimmedStrokeWidth", dimmedStrokeWidth)
        requireLength("cornerRadius", cornerRadius)
    }

    override fun equals(other: Any?): Boolean =
        other is CodeBoxStyle && other.color == color && other.lockedColor == lockedColor && other.dimmedColor == dimmedColor &&
            other.strokeWidth == strokeWidth && other.lockedStrokeWidth == lockedStrokeWidth &&
            other.dimmedStrokeWidth == dimmedStrokeWidth && other.cornerRadius == cornerRadius

    override fun hashCode(): Int {
        var h = color.hashCode()
        h = 31 * h + lockedColor.hashCode()
        h = 31 * h + dimmedColor.hashCode()
        h = 31 * h + strokeWidth.hashCode()
        h = 31 * h + lockedStrokeWidth.hashCode()
        h = 31 * h + dimmedStrokeWidth.hashCode()
        h = 31 * h + cornerRadius.hashCode()
        return h
    }

    override fun toString(): String =
        "CodeBoxStyle(color=$color, lockedColor=$lockedColor, dimmedColor=$dimmedColor, strokeWidth=$strokeWidth, " +
            "lockedStrokeWidth=$lockedStrokeWidth, dimmedStrokeWidth=$dimmedStrokeWidth, cornerRadius=$cornerRadius)"

    private companion object {
        fun requireLength(name: String, value: Dp) {
            require(value.value.isFinite() && value.value >= 0f) { "$name($value) 은 유한한 0 이상이어야 한다" }
        }
    }
}

/**
 * 기본 박스 — 둥근 모서리 선. [CodeScanner] 가 코드 자리에 그 크기로 놓는다(직접 쓸 때도 코드 크기의 자리에 둔다).
 *
 * @param dimmed 고정되지 않은 코드를 흐리게 그릴지. 고정된 코드([TrackedCode.isLocked])는 언제나 [CodeBoxStyle.lockedColor] 다
 */
@Composable
public fun QrCodeBox(
    code: TrackedCode,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    style: CodeBoxStyle = ScannerDefaults.boxStyle(),
) {
    val (color, stroke) = when {
        code.isLocked -> style.lockedColor to style.lockedStrokeWidth
        dimmed -> style.dimmedColor to style.dimmedStrokeWidth
        else -> style.color to style.strokeWidth
    }
    Canvas(modifier.fillMaxSize()) {
        drawRoundRect(
            color = color,
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(style.cornerRadius.toPx()),
            style = Stroke(width = stroke.toPx()),
        )
    }
}

/** 칩을 박스 아래(모자라면 위)에 두고 뷰 안으로 민다. */
@Composable
private fun ChipLayer(
    codes: List<TrackedCode>,
    chip: @Composable (TrackedCode, () -> Unit) -> Unit,
    onClick: (TrackedCode) -> Unit,
) {
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    Layout(
        content = { codes.forEach { code -> key(code.id) { Box { chip(code) { onClick(code) } } } } },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val (x, y) = ChipPlacement.place(
                    codes[i].bounds, p.width.toFloat(), p.height.toFloat(),
                    constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), gapPx,
                )
                p.place(x.toInt(), y.toInt())
            }
        }
    }
}

/**
 * 기본 칩 — 종류 이름과 한 줄 요약. 요약은 `qr` 의 요약이라 Wi-Fi 비밀번호·OTP 비밀이 보이지 않는다.
 */
@Composable
public fun QrCodeChip(code: TrackedCode, onClick: () -> Unit, modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault()) {
    val type = stringResource(ScannerDefaults.typeLabelRes(code.result.payload))
    val summary = ScannerDefaults.summary(code.result, zone)
    val description = stringResource(R.string.plumt_qr_code_description, type, summary)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (code.isStale) 0.7f else 0.95f),
        tonalElevation = 2.dp,
        modifier = modifier
            .widthIn(max = 280.dp)
            .semantics { contentDescription = description },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Preview
@Composable
private fun QrCodeBoxPreview() {
    val result = ScanResult("https://example.com", CodeFormat.QR_CODE, QrPayload.Url("https://example.com"), emptyList())
    MaterialTheme {
        Box(Modifier.size(120.dp)) {
            QrCodeBox(TrackedCode(1, result, CodeBounds(0f, 0f, 10f, 10f), 0, true), dimmed = false)
        }
    }
}

@Preview
@Composable
private fun QrCodeChipPreview() {
    val result = ScanResult("https://example.com", CodeFormat.QR_CODE, QrPayload.Url("https://example.com"), emptyList())
    MaterialTheme {
        QrCodeChip(TrackedCode(1, result, CodeBounds(0f, 0f, 10f, 10f), 0, true), onClick = {})
    }
}
