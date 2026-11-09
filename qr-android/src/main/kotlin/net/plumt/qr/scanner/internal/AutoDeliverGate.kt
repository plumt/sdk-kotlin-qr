package net.plumt.qr.scanner.internal

import net.plumt.qr.scanner.AutoDeliverOptions

/**
 * 자동 넘기기 판정. 시각은 프레임 시각(밀리초)이다. 한 스레드에서만 부른다.
 *
 * - 같은 추적 번호에 [AutoDeliverOptions.stableMillis] 이상 고정돼 있어야 넘긴다
 * - 같은 추적 번호는 한 번만 넘긴다
 * - 같은 원문은 마지막으로 넘긴 시각부터 [AutoDeliverOptions.repeatWindowMillis] 안에 다시 넘기지 않는다
 *   (코드가 화면에서 잠깐 빠졌다 들어와 새 번호가 붙어도 막는다)
 * - 시각이 거꾸로 가면(카메라 재시작으로 시각 기준이 바뀜) 경과 시간을 0 으로 보고, 원문 기록을 지운다
 * - [reset](멈춤 뒤 재개·새 세션)은 고정·넘긴 추적 번호만 지운다. 마지막으로 넘긴 원문과 시각은 남겨 반복 창을 잇는다 —
 *   넘기기 → 멈춤 → 결과 창 닫기 → 재개에서 화면에 남은 같은 코드를 곧바로 다시 넘기지 않게. 마지막 시각도 남겨, 새 세션의
 *   시각 기준이 더 작으면 위 규칙대로 원문 기록을 지운다
 */
internal class AutoDeliverGate(private val options: AutoDeliverOptions) {

    private var lockedId: Long? = null
    private var lockedSince = 0L
    private val deliveredIds = HashSet<Long>()
    private var lastText: String? = null
    private var lastAt = 0L
    private var lastNow = Long.MIN_VALUE

    fun reset() {
        lockedId = null
        deliveredIds.clear()
    }

    /** 지금 살아 있는 추적 번호만 기억에 남긴다(지워진 번호는 다시 오지 않는다). */
    fun retain(liveIds: Set<Long>) {
        deliveredIds.retainAll(liveIds)
    }

    /** 이번 프레임에 고정된 코드([id]·[text], 없으면 null)를 보고 넘길지 정한다. */
    fun offer(id: Long?, text: String?, nowMillis: Long): Boolean {
        if (nowMillis < lastNow) {
            lastText = null
            lockedSince = nowMillis
        }
        lastNow = nowMillis
        if (id == null || text == null) {
            lockedId = null
            return false
        }
        if (id != lockedId) {
            lockedId = id
            lockedSince = nowMillis
        }
        if (id in deliveredIds) return false
        if (nowMillis - lockedSince < options.stableMillis) return false
        if (text == lastText && nowMillis - lastAt < options.repeatWindowMillis) return false
        deliveredIds += id
        lastText = text
        lastAt = nowMillis
        return true
    }
}
