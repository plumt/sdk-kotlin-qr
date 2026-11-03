package net.plumt.qr.scanner.internal

import net.plumt.qr.scanner.CodeBounds
import net.plumt.qr.scanner.LockOptions

/** 고를 후보 하나(뷰 좌표). [fresh] 는 이번 프레임에서 보였는가. */
internal data class LockCandidate(val id: Long, val bounds: CodeBounds, val fresh: Boolean)

/**
 * 가운데 고정 선택기. 한 스레드에서만 부른다.
 *
 * - 고정이 없으면 이번 프레임에 보인 후보 중 뷰 가운데에 가장 가까운 것에 바로 고정한다(거리가 같으면 번호가 작은 것)
 * - 고정된 코드가 후보에 남아 있는 동안(잠깐 놓친 프레임 포함) 고정을 지킨다
 * - 더 가까운 후보가 `hysteresis * 대각선` 이상 앞서는 프레임이 [LockOptions.switchFrames] 번 연속되면 옮긴다.
 *   연속이 끊기거나 앞서는 후보가 바뀌면 다시 센다
 * - 고정된 코드가 사라지면(추적에서 지워지거나 화면 밖) 고정을 풀고 같은 프레임에서 다시 고른다
 */
internal class LockSelector(private val options: LockOptions) {

    var lockedId: Long? = null
        private set

    private var challengerId: Long? = null
    private var challengeCount = 0

    fun reset() {
        lockedId = null
        challengerId = null
        challengeCount = 0
    }

    fun select(candidates: List<LockCandidate>, viewWidth: Float, viewHeight: Float): Long? {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val diagonal = BoxMath.distance(0f, 0f, viewWidth, viewHeight)
        fun dist(c: LockCandidate) = BoxMath.distance(c.bounds.centerX, c.bounds.centerY, cx, cy)
        val order = compareBy<LockCandidate>({ dist(it) }, { it.id })

        val current = candidates.firstOrNull { it.id == lockedId }
        if (current == null) {
            challengerId = null
            challengeCount = 0
            lockedId = candidates.filter { it.fresh }.minWithOrNull(order)?.id
            return lockedId
        }

        val best = candidates.filter { it.fresh && it.id != current.id }.minWithOrNull(order)
        val margin = options.hysteresis * diagonal
        if (best != null && dist(best) < dist(current) - margin) {
            if (best.id == challengerId) challengeCount++ else {
                challengerId = best.id
                challengeCount = 1
            }
            if (challengeCount >= options.switchFrames) {
                lockedId = best.id
                challengerId = null
                challengeCount = 0
            }
        } else {
            challengerId = null
            challengeCount = 0
        }
        return lockedId
    }
}
