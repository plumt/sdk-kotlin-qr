package net.plumt.qr.scanner.internal

import net.plumt.qr.ScanResult
import net.plumt.qr.scanner.CodeBounds
import net.plumt.qr.scanner.Detection
import net.plumt.qr.scanner.TrackingOptions
import kotlin.math.max

/** 추적 중인 코드 하나(이미지 좌표). */
internal data class Track(
    val id: Long,
    val result: ScanResult,
    val bounds: CodeBounds,
    val missedFrames: Int,
    val hits: Int,
)

/**
 * 프레임 사이로 코드를 잇는다. 한 스레드(분석 스레드)에서만 부른다.
 *
 * - 같은 코드 판정: 원문과 형식이 같고, 겹침(IoU)이 [TrackingOptions.iouThreshold] 이상이거나 가운데 거리가 두 박스 중 큰 변 길이 이하
 * - 짝짓기: 가능한 짝을 (IoU 큰 순, 거리 짧은 순)으로 늘어놓고 탐욕적으로 하나씩 묶는다 — 같은 원문의 코드 둘이 나란히 있어도 번호가 뒤바뀌지 않는다
 * - 위치: 지수 평활(`새 = 옛 + (1 - smoothing) * (관측 - 옛)`). 박스 크기 이상 튀면 따라가지 않고 바로 옮긴다
 * - 놓친 프레임이 [TrackingOptions.maxMissedFrames] 를 넘으면 지운다
 * - 이미지 크기가 바뀌면(회전·해상도 변경) 좌표를 비교할 수 없으므로 모두 지운다. 번호는 이어서 늘어난다(재사용하지 않는다)
 */
internal class CodeTracker(private val options: TrackingOptions) {

    private var tracks: List<Track> = emptyList()
    private var nextId = 1L
    private var imageWidth = 0
    private var imageHeight = 0

    val current: List<Track> get() = tracks

    fun reset() {
        tracks = emptyList()
    }

    fun update(detections: List<Detection>, imageWidth: Int, imageHeight: Int): List<Track> {
        if (imageWidth != this.imageWidth || imageHeight != this.imageHeight) {
            tracks = emptyList()
            this.imageWidth = imageWidth
            this.imageHeight = imageHeight
        }
        val unique = dedupe(detections)

        data class Pair(val t: Int, val d: Int, val iou: Float, val dist: Float)
        val pairs = ArrayList<Pair>()
        for ((ti, t) in tracks.withIndex()) {
            for ((di, d) in unique.withIndex()) {
                if (!sameValue(t.result, d.result)) continue
                val iou = BoxMath.iou(t.bounds, d.bounds)
                val dist = BoxMath.centerDistance(t.bounds, d.bounds)
                val reach = max(max(t.bounds.width, t.bounds.height), max(d.bounds.width, d.bounds.height))
                if (iou >= options.iouThreshold || dist <= reach) pairs += Pair(ti, di, iou, dist)
            }
        }
        pairs.sortWith(compareByDescending<Pair> { it.iou }.thenBy { it.dist })

        val trackTaken = BooleanArray(tracks.size)
        val detTaken = BooleanArray(unique.size)
        val updated = arrayOfNulls<Track>(tracks.size)
        for (p in pairs) {
            if (trackTaken[p.t] || detTaken[p.d]) continue
            trackTaken[p.t] = true
            detTaken[p.d] = true
            val t = tracks[p.t]
            val d = unique[p.d]
            updated[p.t] = t.copy(result = d.result, bounds = smooth(t.bounds, d.bounds), missedFrames = 0, hits = t.hits + 1)
        }

        val next = ArrayList<Track>(tracks.size + unique.size)
        for ((i, t) in tracks.withIndex()) {
            val u = updated[i]
            if (u != null) {
                next += u
            } else if (t.missedFrames < options.maxMissedFrames) {
                next += t.copy(missedFrames = t.missedFrames + 1)
            }
        }
        for ((i, d) in unique.withIndex()) {
            if (!detTaken[i]) next += Track(nextId++, d.result, d.bounds, missedFrames = 0, hits = 1)
        }
        tracks = next
        return tracks
    }

    private fun smooth(old: CodeBounds, new: CodeBounds): CodeBounds {
        if (options.smoothing == 0f) return new
        val jump = BoxMath.centerDistance(old, new)
        val size = max(max(old.width, old.height), max(new.width, new.height))
        if (jump > size) return new
        return BoxMath.lerp(old, new, 1f - options.smoothing)
    }

    private fun sameValue(a: ScanResult, b: ScanResult): Boolean = a.text == b.text && a.format == b.format

    /** 한 프레임에 같은 원문이 거의 같은 자리에 두 번 잡히면(엔진 중복) 하나만 남긴다. */
    private fun dedupe(detections: List<Detection>): List<Detection> {
        val out = ArrayList<Detection>(detections.size)
        for (d in detections) {
            if (out.none { sameValue(it.result, d.result) && BoxMath.iou(it.bounds, d.bounds) >= DUPLICATE_IOU }) out += d
        }
        return out
    }

    private companion object {
        const val DUPLICATE_IOU = 0.8f
    }
}
