package net.plumt.qr.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import net.plumt.qr.CodeFormat
import net.plumt.qr.QrPayload
import net.plumt.qr.ScanResult
import java.time.ZoneId

/**
 * 실시간 QR·바코드 스캐너. 카메라 미리보기 위에 코드 박스와 칩(종류 + 요약)을 그린다.
 *
 * - [ScanMode.Single]: 가운데에 가장 가까운 코드에 고정해 그 코드의 칩만 띄운다. `autoDeliver` 면 탭 없이 [onCodeTap] 을 부른다
 * - [ScanMode.Multi]: 보이는 코드마다 칩을 띄운다
 * - 칩·박스를 탭하면 그 코드로 [onCodeTap] 을 부른다(메인 스레드). 코드가 아닌 곳을 탭하면 초점을 맞추고, 두 손가락으로 확대한다
 * - SDK 는 결과로 아무것도 실행하지 않는다 — 링크 열기·Wi-Fi 연결은 앱이 [net.plumt.qr.scanner.action.QrActionExecutor] 로 한다
 * - 카메라 권한은 앱이 요청한다. 권한이 없으면 [permissionContent] 를 그린다
 *
 * @param onError 카메라·엔진 오류. 엔진 오류는 카메라 세션마다 처음 한 건만 온다
 * @param state 낮은 단계 상태. 플래시·확대·멈춤을 밖에서 조작하려면 [rememberScannerState] 로 만들어 넘긴다
 * @param chip 칩 모양을 바꾼다. 기본 [QrCodeChip]
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
    chip: @Composable (code: TrackedCode, onClick: () -> Unit) -> Unit = { code, onClick ->
        QrCodeChip(code = code, onClick = onClick, zone = state.options.zone)
    },
) {
    val tap by rememberUpdatedState(onCodeTap)
    val error by rememberUpdatedState(onError)
    val status by state.cameraStatus.collectAsState()
    val codes by state.codes.collectAsState()

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

        val shown = if (state.mode is ScanMode.Single) codes.filter { it.isLocked } else codes
        val dimmed = if (state.mode is ScanMode.Single) codes.filter { !it.isLocked } else emptyList()
        val accent = MaterialTheme.colorScheme.primary
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInputs(state, onTap = { tap(it) }),
        ) {
            dimmed.forEach { drawBox(it.bounds, Color.White.copy(alpha = 0.5f), 2.dp.toPx()) }
            shown.forEach { drawBox(it.bounds, accent, 3.dp.toPx()) }
        }
        ChipLayer(shown, chip) { tap(it.result) }
    }
}

private fun Modifier.pointerInputs(state: ScannerState, onTap: (ScanResult) -> Unit): Modifier {
    val taps = pointerInput(state) {
        detectTapGestures { pos ->
            val hit = state.codeAt(pos.x, pos.y)
            when {
                hit != null -> onTap(hit.result)
                state.options.tapToFocus -> state.focusAt(pos.x, pos.y)
            }
        }
    }
    if (!state.options.pinchToZoom) return taps
    return taps.pointerInput(state, "zoom") {
        detectTransformGestures { _, _, zoom, _ -> if (zoom.isFinite() && zoom > 0f && zoom != 1f) state.zoomBy(zoom) }
    }
}

private fun DrawScope.drawBox(b: CodeBounds, color: Color, stroke: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(b.left, b.top),
        size = Size(b.width, b.height),
        cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(width = stroke),
    )
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
private fun QrCodeChipPreview() {
    val result = ScanResult("https://example.com", CodeFormat.QR_CODE, QrPayload.Url("https://example.com"), emptyList())
    MaterialTheme {
        QrCodeChip(TrackedCode(1, result, CodeBounds(0f, 0f, 10f, 10f), 0, true), onClick = {})
    }
}
