package net.plumt.qr.scanner.internal

import net.plumt.qr.scanner.LockOptions
import net.plumt.qr.scanner.MultiLock
import net.plumt.qr.scanner.ScanMode
import net.plumt.qr.scanner.TrackedCode

/** 앱이 준 [MultiLock.Custom.select] 가 던졌다. 분석기가 이 프레임을 버리고 오류로 알린다. */
internal class CustomLockFailure(cause: RuntimeException) : RuntimeException(cause)

/** 모드가 쓰는 고정 정책. [ScanMode.Single] 은 [MultiLock.Nearest] 와 같다. */
internal val ScanMode.lockPolicy: MultiLock
    get() = when (this) {
        is ScanMode.Single -> MultiLock.Nearest
        is ScanMode.Multi -> lock
    }

internal val ScanMode.autoDeliverEnabled: Boolean
    get() = when (this) {
        is ScanMode.Single -> autoDeliver
        is ScanMode.Multi -> autoDeliver
    }

/**
 * 고정 상태기. 한 스레드(또는 같은 잠금)에서만 부른다.
 *
 * - 핀([pin], 앱의 lockOn·탭 고정)이 있으면 정책보다 우선한다. 핀 코드가 보이는 목록에서 빠지면 핀을 푼다
 * - 핀이 없으면 정책대로: None·Tap 은 고정 없음, Nearest 는 [LockSelector], Custom 은 앱 함수
 * - Nearest 는 핀이 있는 동안 선택기를 핀 코드에 맞춰 둔다 — 핀이 풀려도 그 코드에서 히스테리시스로 이어 간다
 * - [release] 는 핀과 고정을 모두 지운다. 스스로 고르는 정책(Nearest·Custom)은 다음 프레임에 다시 고른다
 */
internal class LockController(options: LockOptions) {

    private val nearest = LockSelector(options)

    var pinnedId: Long? = null
        private set

    var lockedId: Long? = null
        private set

    fun reset() {
        nearest.reset()
        pinnedId = null
        lockedId = null
    }

    fun pin(id: Long) {
        pinnedId = id
        lockedId = id
    }

    fun release() {
        nearest.reset()
        pinnedId = null
        lockedId = null
    }

    /**
     * 이번 프레임의 고정을 고른다. [codes] 는 화면에 보이는 코드(뷰 좌표, `isLocked` 는 무시한다).
     * @return 고정된 추적 번호. 없으면 null
     */
    fun select(policy: MultiLock, codes: List<TrackedCode>, viewWidth: Float, viewHeight: Float): Long? {
        val ids = codes.mapTo(HashSet()) { it.id }
        val pin = pinnedId
        if (pin != null && pin !in ids) pinnedId = null
        val chosen = pinnedId?.also { nearest.force(it) } ?: when (policy) {
            MultiLock.None, MultiLock.Tap -> null
            MultiLock.Nearest -> nearest.select(
                codes.map { LockCandidate(it.id, it.bounds, fresh = it.missedFrames == 0) },
                viewWidth,
                viewHeight,
            )
            is MultiLock.Custom -> {
                val previous = lockedId
                val view = codes.map { TrackedCode(it.id, it.result, it.bounds, it.missedFrames, isLocked = it.id == previous) }
                val picked = try {
                    policy.select(view)
                } catch (e: RuntimeException) {
                    throw CustomLockFailure(e)
                }
                picked?.id?.takeIf { it in ids }
            }
        }
        lockedId = chosen
        return chosen
    }
}
