package net.plumt.qr.scanner

import net.plumt.qr.ScanResult

/**
 * 스캐너가 코드를 고르는 방식.
 *
 * - [Single] — 미리보기 가운데에 가장 가까운 코드 하나에 고정(lock-on)한다. 여러 코드가 함께 보여도 고정이 깜빡이지 않게
 *   히스테리시스로 붙든다
 * - [Multi] — 보이는 코드를 모두 추적하고 코드마다 칩을 띄운다. 고정 여부와 방식은 [MultiLock] 으로 고른다
 *
 * 두 모드 모두 [ScannerState.lockOn]·[ScannerState.unlock] 으로 앱이 고정을 직접 바꿀 수 있다.
 */
public sealed interface ScanMode {

    /**
     * 가운데 고정 모드.
     *
     * @property autoDeliver true 면 고정된 코드를 탭 없이 바로 넘긴다. 고정이 [AutoDeliverOptions.stableMillis] 동안
     *   유지돼야 넘기고, 같은 원문은 [AutoDeliverOptions.repeatWindowMillis] 안에 다시 넘기지 않는다.
     *   넘긴다는 것은 콜백을 부른다는 뜻일 뿐이다 — 링크를 열거나 Wi-Fi 에 붙는 일은 SDK 가 스스로 하지 않는다
     */
    public data class Single(val autoDeliver: Boolean = false) : ScanMode

    /**
     * 다중 추적 모드. 보이는 코드를 모두 추적한다.
     *
     * @property lock 어떤 코드에 고정할지. 기본 [MultiLock.None] 은 고정하지 않는다(0.0.x 와 같다)
     * @property autoDeliver true 면 고정된 코드를 탭 없이 넘긴다. 규칙은 [Single.autoDeliver] 와 같다.
     *   고정이 없으면 넘기지 않는다 — [MultiLock.None] 에서는 [ScannerState.lockOn] 으로 고정한 코드만 넘긴다
     */
    public class Multi(
        public val lock: MultiLock = MultiLock.None,
        public val autoDeliver: Boolean = false,
    ) : ScanMode {
        override fun equals(other: Any?): Boolean = other is Multi && other.lock == lock && other.autoDeliver == autoDeliver

        override fun hashCode(): Int = 31 * lock.hashCode() + autoDeliver.hashCode()

        override fun toString(): String = "Multi(lock=$lock, autoDeliver=$autoDeliver)"
    }
}

/**
 * [ScanMode.Multi] 에서 어떤 코드에 고정(lock-on)할지.
 *
 * 어느 정책이든 [ScannerState.lockOn] 으로 고정한 코드가 우선이다. 고정된 코드가 화면에서 사라지면(추적에서 지워지거나
 * 화면 밖) 고정이 풀리고 정책대로 다시 고른다.
 */
public sealed interface MultiLock {

    /** 고정하지 않는다. 기본. [ScannerState.lockOn] 으로만 고정된다. */
    public data object None : MultiLock

    /**
     * 사용자가 탭한 코드에 고정한다. [CodeScanner] 에서 코드 박스 자리를 탭하면 그 코드로 고정을 옮기고, 고정된 코드를 다시
     * 탭하면 푼다 — 이때 박스 자리 탭은 결과를 넘기지 않는다(칩 탭·[ScanMode.Multi.autoDeliver] 로 넘긴다).
     */
    public data object Tap : MultiLock

    /** 가운데에 가장 가까운 코드에 스스로 고정한다. [ScanMode.Single] 과 같은 규칙([LockOptions] 의 히스테리시스·연속 프레임). */
    public data object Nearest : MultiLock

    /**
     * 앱이 고른다. [select] 는 고정이 없거나 [ScannerState.lockOn] 고정이 없는 프레임마다 분석 스레드에서 불린다.
     *
     * - 인자는 이번 프레임에 화면에 보이는 코드다. `isLocked` 는 직전 프레임의 고정을 뜻한다
     * - 인자 중 하나를 돌려주면 그 코드(추적 번호로 맞춘다)에 고정하고, null 이나 목록에 없는 코드면 고정하지 않는다
     * - 빨리 끝나야 한다(프레임마다 불린다). 예외를 던지면 그 프레임을 버리고 [ScannerException] 으로 한 번 알린다
     *
     * 비교는 [select] 의 동일성으로 한다. [rememberScannerState] 는 [select] 만 다른 모드를 같은 상태로 보고 새 함수로 바꾼다 —
     * 인라인 람다를 넘겨도 재구성마다 카메라가 다시 열리지 않는다.
     */
    public class Custom(public val select: (codes: List<TrackedCode>) -> TrackedCode?) : MultiLock {
        override fun equals(other: Any?): Boolean = other is Custom && other.select === select

        override fun hashCode(): Int = System.identityHashCode(select)

        override fun toString(): String = "Custom"
    }
}

/** 미리보기 테두리 안의 사각형(픽셀, 왼쪽 위가 원점). 비교·해시는 값으로 한다. */
public data class CodeBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** 너비. 음수가 되지 않는다. */
    public val width: Float get() = (right - left).coerceAtLeast(0f)

    /** 높이. 음수가 되지 않는다. */
    public val height: Float get() = (bottom - top).coerceAtLeast(0f)

    /** 가운데 x. */
    public val centerX: Float get() = (left + right) / 2f

    /** 가운데 y. */
    public val centerY: Float get() = (top + bottom) / 2f

    /** 점이 사각형 안(경계 포함)에 있는가. */
    public operator fun contains(point: Pair<Float, Float>): Boolean = contains(point.first, point.second)

    /** 점이 사각형 안(경계 포함)에 있는가. */
    public fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * 화면에 보이는, 프레임 사이로 추적 중인 코드 하나.
 *
 * @property id 추적 번호. 같은 코드가 계속 보이는 동안 프레임이 바뀌어도 그대로다. 사라졌다 다시 잡히면 새 번호다
 * @property result 읽은 결과. `points` 는 바로 세운(회전을 적용한) 분석 이미지 좌표다
 * @property bounds 미리보기 뷰 좌표의 사각형(픽셀). 프레임 사이 흔들림을 줄이려고 부드럽게 따라간다
 * @property missedFrames 마지막으로 보인 뒤 놓친 프레임 수. 0 이면 이번 프레임에서 보였다
 * @property isLocked 고정(lock-on)된 코드인가. 한 번에 하나뿐이다. [ScanMode.Single] 은 가운데 고정, [ScanMode.Multi] 는 [MultiLock] 정책이나
 *   [ScannerState.lockOn] 으로 고정된 코드다. 고정이 없으면 모두 false
 */
public class TrackedCode(
    public val id: Long,
    public val result: ScanResult,
    public val bounds: CodeBounds,
    public val missedFrames: Int,
    public val isLocked: Boolean,
) {
    /** 이번 프레임에서는 보이지 않았지만 아직 지우지 않은 코드인가. */
    public val isStale: Boolean get() = missedFrames > 0

    override fun equals(other: Any?): Boolean =
        other is TrackedCode && other.id == id && other.result == result && other.bounds == bounds &&
            other.missedFrames == missedFrames && other.isLocked == isLocked

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + result.hashCode()
        h = 31 * h + bounds.hashCode()
        h = 31 * h + missedFrames
        h = 31 * h + isLocked.hashCode()
        return h
    }

    // 원문(result.text)을 넣지 않는다 — Wi-Fi 비밀번호·OTP 비밀이 로그에 새지 않게. payload 의 toString 은 비밀을 가린다
    override fun toString(): String =
        "TrackedCode(id=$id, format=${result.format}, payload=${result.payload}, bounds=$bounds, " +
            "missedFrames=$missedFrames, isLocked=$isLocked)"
}

/** 카메라 쪽 상태. */
public sealed interface CameraStatus {
    /** 카메라 권한이 없다. SDK 는 권한을 요청하지 않는다 — 앱이 요청하고 [ScannerState.refreshPermission] 을 부른다. */
    public data object PermissionRequired : CameraStatus

    /** 권한은 있고 카메라를 여는 중이다. */
    public data object Starting : CameraStatus

    /** 미리보기와 분석이 돈다. */
    public data object Running : CameraStatus

    /** 요청한 방향의 카메라가 기기에 없다. */
    public data object NoCamera : CameraStatus

    /** 카메라를 열지 못했다. 원인은 [error] 의 `cause`. */
    public data class Error(val error: ScannerException) : CameraStatus
}

/** 쓸 카메라. */
public enum class CameraLens {
    /** 후면. 기본. */
    BACK,

    /** 전면. 미리보기가 좌우 반전돼 보이므로 박스도 반전해 그린다. */
    FRONT,
}

/** 미리보기를 뷰에 맞추는 방식. 박스 좌표 변환도 같은 방식을 쓴다. */
public enum class PreviewScaleMode {
    /** 비율을 지키며 뷰를 꽉 채운다. 넘치는 쪽은 잘린다. 잘린 곳의 코드는 보이지 않으므로 고르지 않는다. 기본. */
    FILL,

    /** 비율을 지키며 뷰 안에 다 들어오게 한다. 남는 쪽은 빈 띠가 된다. */
    FIT,
}

/** 플래시(토치) 상태. */
public data class TorchState(val available: Boolean, val enabled: Boolean)

/** 확대 상태. [ratio] 는 [min]..[max] 안이다. 카메라가 열리기 전에는 1..1 이다. */
public data class ZoomState(val ratio: Float, val min: Float, val max: Float)
