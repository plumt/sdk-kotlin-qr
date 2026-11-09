package net.plumt.qr.scanner

import net.plumt.qr.ScanPoint
import net.plumt.qr.scanner.internal.BoxMath
import net.plumt.qr.scanner.internal.FrameRotation
import net.plumt.qr.scanner.internal.PreviewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeometryTest {

    private fun assertPoint(ex: Float, ey: Float, p: ScanPoint) {
        assertEquals("x", ex, p.x, EPS)
        assertEquals("y", ey, p.y, EPS)
    }

    private fun assertBounds(e: CodeBounds, a: CodeBounds) {
        assertEquals("left", e.left, a.left, EPS)
        assertEquals("top", e.top, a.top, EPS)
        assertEquals("right", e.right, a.right, EPS)
        assertEquals("bottom", e.bottom, a.bottom, EPS)
    }

    // ---- 회전: 버퍼 640x480 의 네 모서리와 한 점 ----

    @Test
    fun rotate0_isIdentity() {
        assertPoint(10f, 20f, FrameRotation.rotatePoint(10f, 20f, 640, 480, 0))
    }

    @Test
    fun rotate90_mapsCornersClockwise() {
        // 버퍼 왼쪽 위 → 세운 이미지 오른쪽 위, 버퍼 오른쪽 위 → 세운 이미지 오른쪽 아래
        assertPoint(480f, 0f, FrameRotation.rotatePoint(0f, 0f, 640, 480, 90))
        assertPoint(480f, 640f, FrameRotation.rotatePoint(640f, 0f, 640, 480, 90))
        assertPoint(0f, 640f, FrameRotation.rotatePoint(640f, 480f, 640, 480, 90))
        assertPoint(0f, 0f, FrameRotation.rotatePoint(0f, 480f, 640, 480, 90))
        assertPoint(460f, 10f, FrameRotation.rotatePoint(10f, 20f, 640, 480, 90))
    }

    @Test
    fun rotate180_flipsBothAxes() {
        assertPoint(640f, 480f, FrameRotation.rotatePoint(0f, 0f, 640, 480, 180))
        assertPoint(630f, 460f, FrameRotation.rotatePoint(10f, 20f, 640, 480, 180))
    }

    @Test
    fun rotate270_mapsCornersCounterClockwise() {
        assertPoint(0f, 640f, FrameRotation.rotatePoint(0f, 0f, 640, 480, 270))
        assertPoint(0f, 0f, FrameRotation.rotatePoint(640f, 0f, 640, 480, 270))
        assertPoint(480f, 0f, FrameRotation.rotatePoint(640f, 480f, 640, 480, 270))
        assertPoint(20f, 630f, FrameRotation.rotatePoint(10f, 20f, 640, 480, 270))
    }

    @Test
    fun rotate_rejectsOtherAngles() {
        for (bad in listOf(-90, 45, 360, 1)) {
            val e = assertThrows<IllegalArgumentException> { FrameRotation.rotatePoint(0f, 0f, 10, 10, bad) }
            assertTrue(e.message!!.contains("$bad"))
        }
    }

    @Test
    fun rotate90Then270_isIdentity_randomized() {
        val rnd = Random(42)
        repeat(500) {
            val w = rnd.nextInt(1, 4000)
            val h = rnd.nextInt(1, 4000)
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val a = FrameRotation.rotatePoint(x, y, w, h, 90)
            // 90 으로 세운 이미지는 h x w — 그것을 270 으로 돌리면 원래 버퍼로 돌아온다
            val back = FrameRotation.rotatePoint(a.x, a.y, h, w, 270)
            assertEquals(x, back.x, 0.01f)
            assertEquals(y, back.y, 0.01f)
            val b = FrameRotation.rotatePoint(x, y, w, h, 180)
            val b2 = FrameRotation.rotatePoint(b.x, b.y, w, h, 180)
            assertEquals(x, b2.x, 0.01f)
            assertEquals(y, b2.y, 0.01f)
        }
    }

    @Test
    fun rotate_staysInsideUprightImage_randomized() {
        val rnd = Random(7)
        repeat(1000) {
            val w = rnd.nextInt(1, 2000)
            val h = rnd.nextInt(1, 2000)
            val r = listOf(0, 90, 180, 270)[rnd.nextInt(4)]
            val p = FrameRotation.rotatePoint(rnd.nextFloat() * w, rnd.nextFloat() * h, w, h, r)
            val (uw, uh) = if (r % 180 == 0) w to h else h to w
            assertTrue(p.x in -0.01f..uw + 0.01f && p.y in -0.01f..uh + 0.01f)
        }
    }

    @Test
    fun rotateBounds_normalizesCorners() {
        val b = box(10f, 20f, 110f, 70f)
        assertBounds(box(10f, 20f, 110f, 70f), FrameRotation.rotateBounds(b, 640, 480, 0))
        assertBounds(box(410f, 10f, 460f, 110f), FrameRotation.rotateBounds(b, 640, 480, 90))
        assertBounds(box(530f, 410f, 630f, 460f), FrameRotation.rotateBounds(b, 640, 480, 180))
        assertBounds(box(20f, 530f, 70f, 630f), FrameRotation.rotateBounds(b, 640, 480, 270))
    }

    @Test
    fun enclose_emptyIsNull_singlePointIsDegenerate() {
        assertNull(FrameRotation.enclose(emptyList()))
        assertBounds(box(3f, 4f, 3f, 4f), FrameRotation.enclose(listOf(ScanPoint(3f, 4f)))!!)
        assertBounds(box(-1f, 0f, 5f, 9f), FrameRotation.enclose(listOf(ScanPoint(5f, 0f), ScanPoint(-1f, 9f), ScanPoint(2f, 2f)))!!)
    }

    // ---- 미리보기 변환 ----

    @Test
    fun fill_landscapeImageIntoPortraitView_cropsSides() {
        // 세운 이미지 480x640(3:4), 뷰 1080x1920(9:16) — FILL 은 큰 배율(3.0)로 세로를 채우고 좌우가 넘친다
        val t = PreviewTransform(480, 640, 1080f, 1920f, PreviewScaleMode.FILL, mirrored = false)
        assertEquals(3f, t.scale, EPS)
        assertEquals((1080f - 1440f) / 2f, t.offsetX, EPS)
        assertEquals(0f, t.offsetY, EPS)
        assertBounds(box(-180f, 0f, 1260f, 1920f), t.mapBounds(box(0f, 0f, 480f, 640f)))
        assertPoint(540f, 960f, t.mapPoint(ScanPoint(240f, 320f)))
    }

    @Test
    fun fit_sameCase_letterboxesTopAndBottom() {
        val t = PreviewTransform(480, 640, 1080f, 1920f, PreviewScaleMode.FIT, mirrored = false)
        assertEquals(2.25f, t.scale, EPS)
        assertEquals(0f, t.offsetX, EPS)
        assertEquals((1920f - 1440f) / 2f, t.offsetY, EPS)
        assertBounds(box(0f, 240f, 1080f, 1680f), t.mapBounds(box(0f, 0f, 480f, 640f)))
        assertBounds(box(0f, 240f, 1080f, 1680f), t.visibleImageArea())
    }

    @Test
    fun fill_wideView_cropsTopAndBottom() {
        // 세운 이미지 640x480(4:3), 뷰 1920x1080(16:9) — FILL 배율 3.0, 위아래가 넘친다
        val t = PreviewTransform(640, 480, 1920f, 1080f, PreviewScaleMode.FILL, mirrored = false)
        assertEquals(3f, t.scale, EPS)
        assertEquals(0f, t.offsetX, EPS)
        assertEquals((1080f - 1440f) / 2f, t.offsetY, EPS)
        assertBounds(box(0f, 0f, 1920f, 1080f), t.visibleImageArea())
    }

    @Test
    fun fit_wideView_pillarboxes() {
        val t = PreviewTransform(640, 480, 1920f, 1080f, PreviewScaleMode.FIT, mirrored = false)
        assertEquals(2.25f, t.scale, EPS)
        assertEquals((1920f - 1440f) / 2f, t.offsetX, EPS)
        assertEquals(0f, t.offsetY, EPS)
    }

    @Test
    fun sameAspect_fillAndFitAgree() {
        val a = PreviewTransform(480, 640, 960f, 1280f, PreviewScaleMode.FILL, false)
        val b = PreviewTransform(480, 640, 960f, 1280f, PreviewScaleMode.FIT, false)
        val r = box(10f, 20f, 30f, 40f)
        assertBounds(a.mapBounds(r), b.mapBounds(r))
        assertEquals(2f, a.scale, EPS)
    }

    @Test
    fun mirrored_flipsHorizontallyAroundImageCenter() {
        val t = PreviewTransform(480, 640, 960f, 1280f, PreviewScaleMode.FILL, mirrored = true)
        // 이미지 왼쪽 끝 → 뷰 오른쪽 끝
        assertEquals(960f, t.mapX(0f), EPS)
        assertEquals(0f, t.mapX(480f), EPS)
        assertEquals(t.mapY(100f), PreviewTransform(480, 640, 960f, 1280f, PreviewScaleMode.FILL, false).mapY(100f), EPS)
        // 반전해도 left <= right 를 지킨다
        val m = t.mapBounds(box(10f, 10f, 50f, 60f))
        assertBounds(box(860f, 20f, 940f, 120f), m)
    }

    @Test
    fun mirrored_withFillCrop() {
        val t = PreviewTransform(480, 640, 1080f, 1920f, PreviewScaleMode.FILL, mirrored = true)
        // 가운데는 가운데로
        assertPoint(540f, 960f, t.mapPoint(ScanPoint(240f, 320f)))
        assertEquals(1260f, t.mapX(0f), EPS)
    }

    @Test
    fun unmap_isInverseOfMap_randomized() {
        val rnd = Random(2026)
        repeat(1000) {
            val iw = rnd.nextInt(1, 4000)
            val ih = rnd.nextInt(1, 4000)
            val vw = rnd.nextInt(1, 4000).toFloat()
            val vh = rnd.nextInt(1, 4000).toFloat()
            val mode = if (rnd.nextBoolean()) PreviewScaleMode.FILL else PreviewScaleMode.FIT
            val t = PreviewTransform(iw, ih, vw, vh, mode, rnd.nextBoolean())
            val x = rnd.nextFloat() * iw
            val y = rnd.nextFloat() * ih
            val tol = maxOf(iw, ih) * 1e-4f + 0.01f
            assertEquals(x, t.unmapX(t.mapX(x)), tol)
            assertEquals(y, t.unmapY(t.mapY(y)), tol)
            // 이미지 가운데는 언제나 뷰 가운데다
            assertEquals(vw / 2f, t.mapX(iw / 2f), vw * 1e-4f + 0.01f)
            assertEquals(vh / 2f, t.mapY(ih / 2f), vh * 1e-4f + 0.01f)
            // FIT 은 이미지 전체가 뷰 안, FILL 은 뷰 전체가 이미지 안
            val full = t.mapBounds(box(0f, 0f, iw.toFloat(), ih.toFloat()))
            val slack = 0.05f
            if (mode == PreviewScaleMode.FIT) {
                assertTrue(full.left >= -slack && full.top >= -slack && full.right <= vw + slack && full.bottom <= vh + slack)
            } else {
                assertTrue(full.left <= slack && full.top <= slack && full.right >= vw - slack && full.bottom >= vh - slack)
            }
        }
    }

    @Test
    fun rotationThenTransform_portraitPhoneScenario() {
        // 센서 640x480, 회전 90 → 세운 480x640. 버퍼의 오른쪽 위 구석 코드는 세운 이미지의 오른쪽 아래 → 뷰 오른쪽 아래
        val buf = box(600f, 0f, 640f, 40f)
        val upright = FrameRotation.rotateBounds(buf, 640, 480, 90)
        assertBounds(box(440f, 600f, 480f, 640f), upright)
        val t = PreviewTransform(480, 640, 960f, 1280f, PreviewScaleMode.FIT, false)
        assertBounds(box(880f, 1200f, 960f, 1280f), t.mapBounds(upright))
    }

    @Test
    fun isVisible_usesCenter() {
        val t = PreviewTransform(480, 640, 1080f, 1920f, PreviewScaleMode.FILL, false)
        assertTrue(t.isVisible(box(0f, 0f, 10f, 10f)))
        assertTrue(t.isVisible(box(-10f, 0f, 10f, 10f)))
        assertFalse(t.isVisible(box(-30f, 0f, -10f, 10f)))
        assertFalse(t.isVisible(box(1070f, 0f, 1200f, 10f)))
        assertFalse(t.isVisible(box(0f, 1915f, 10f, 1935f)))
    }

    @Test
    fun transform_rejectsBadSizes() {
        val bad: List<Pair<Pair<Int, Int>, Pair<Float, Float>>> = listOf(
            (0 to 1) to (1f to 1f), (1 to 0) to (1f to 1f), (1 to 1) to (0f to 1f), (1 to 1) to (1f to -1f),
            (1 to 1) to (Float.NaN to 1f), (1 to 1) to (1f to Float.POSITIVE_INFINITY),
        )
        for ((img, view) in bad) {
            assertThrows<IllegalArgumentException> {
                PreviewTransform(img.first, img.second, view.first, view.second, PreviewScaleMode.FILL, false)
            }
        }
    }

    @Test
    fun transform_extremeSizes() {
        val t = PreviewTransform(1, 1, 1f, 1f, PreviewScaleMode.FIT, false)
        assertEquals(1f, t.scale, EPS)
        val big = PreviewTransform(10000, 1, 1f, 10000f, PreviewScaleMode.FILL, true)
        assertTrue(big.scale.isFinite() && big.offsetX.isFinite())
    }

    // ---- 박스 계산 ----

    @Test
    fun iou_cases() {
        assertEquals(1f, BoxMath.iou(box(0f, 0f, 10f, 10f), box(0f, 0f, 10f, 10f)), EPS)
        assertEquals(0f, BoxMath.iou(box(0f, 0f, 10f, 10f), box(10f, 0f, 20f, 10f)), EPS)
        assertEquals(0f, BoxMath.iou(box(0f, 0f, 10f, 10f), box(20f, 20f, 30f, 30f)), EPS)
        assertEquals(25f / 175f, BoxMath.iou(box(0f, 0f, 10f, 10f), box(5f, 5f, 15f, 15f)), EPS)
        assertEquals(0f, BoxMath.iou(box(0f, 0f, 0f, 0f), box(0f, 0f, 0f, 0f)), EPS)
        // 안에 든 박스
        assertEquals(0.25f, BoxMath.iou(box(0f, 0f, 10f, 10f), box(0f, 0f, 5f, 5f)), EPS)
    }

    @Test
    fun iou_symmetricAndBounded_randomized() {
        val rnd = Random(3)
        repeat(1000) {
            fun r(): CodeBounds {
                val l = rnd.nextFloat() * 100
                val t = rnd.nextFloat() * 100
                return box(l, t, l + rnd.nextFloat() * 50, t + rnd.nextFloat() * 50)
            }
            val a = r()
            val b = r()
            val i = BoxMath.iou(a, b)
            assertEquals(i, BoxMath.iou(b, a), 1e-5f)
            assertTrue(i in 0f..1.0001f)
        }
    }

    @Test
    fun lerp_andDistance() {
        assertBounds(box(5f, 5f, 15f, 15f), BoxMath.lerp(box(0f, 0f, 10f, 10f), box(10f, 10f, 20f, 20f), 0.5f))
        assertBounds(box(0f, 0f, 10f, 10f), BoxMath.lerp(box(0f, 0f, 10f, 10f), box(10f, 10f, 20f, 20f), 0f))
        assertEquals(5f, BoxMath.distance(0f, 0f, 3f, 4f), EPS)
        assertEquals(5f, BoxMath.centerDistance(box(0f, 0f, 2f, 2f), box(3f, 4f, 5f, 6f)), EPS)
    }
}

/**
 * 센서 버퍼 → 세운 이미지 → 미리보기 뷰 좌표 사슬을 독립 모형과 맞춘다(검토 M1).
 *
 * 모형: 장면의 한 점을 세운 이미지 좌표로 정하고, 센서 버퍼 좌표는 그 역회전으로 만든다. 뷰 좌표는 `PreviewView` 의
 * FILL_CENTER·FIT_CENTER 정의(비율 유지 배율 + 가운데 정렬)대로 계산하고, 전면 카메라는 **뷰 공간에서** 좌우를 뒤집는다
 * (`PreviewTransform` 은 이미지 공간에서 뒤집으므로 계산 경로가 다르다).
 *
 * 전제(실기기에서 확인할 것): 미리보기와 분석 스트림이 같은 비율·같은 화각이다. 기기가 둘 중 하나를 다른 비율로 잡거나 센서 크롭이
 * 다르면 이 사슬이 맞아도 박스가 어긋난다.
 */
class CoordinateChainTest {

    /** 세운 (ux, uy) 가 센서 버퍼의 어디에 찍히는가 — rotatePoint 의 역. */
    private fun toBuffer(ux: Float, uy: Float, bw: Int, bh: Int, r: Int): ScanPoint = when (r) {
        0 -> ScanPoint(ux, uy)
        90 -> ScanPoint(uy, bh - ux)
        180 -> ScanPoint(bw - ux, bh - uy)
        270 -> ScanPoint(bw - uy, ux)
        else -> error("r")
    }

    private fun expectedView(ux: Float, uy: Float, iw: Int, ih: Int, vw: Float, vh: Float, mode: PreviewScaleMode, front: Boolean): ScanPoint {
        val s = if (mode == PreviewScaleMode.FILL) maxOf(vw / iw, vh / ih) else minOf(vw / iw, vh / ih)
        val x = ux * s + (vw - iw * s) / 2f
        val y = uy * s + (vh - ih * s) / 2f
        return ScanPoint(if (front) vw - x else x, y)
    }

    @Test
    fun chain_matchesIndependentModel_allRotationsModesLenses() {
        val rnd = Random(20261103)
        val views = listOf(1080f to 2340f, 2340f to 1080f, 1000f to 1000f, 720f to 960f, 333f to 777f)
        for (r in listOf(0, 90, 180, 270)) for (mode in PreviewScaleMode.entries) for (front in listOf(false, true)) for ((vw, vh) in views) {
            val (bw, bh) = 640 to 480
            val iw = if (r % 180 == 0) bw else bh
            val ih = if (r % 180 == 0) bh else bw
            val t = PreviewTransform(iw, ih, vw, vh, mode, front)
            repeat(50) {
                val ux = rnd.nextFloat() * iw
                val uy = rnd.nextFloat() * ih
                val b = toBuffer(ux, uy, bw, bh, r)
                val upright = FrameRotation.rotatePoint(b.x, b.y, bw, bh, r)
                assertEquals("r=$r 회전 왕복 x", ux, upright.x, 1e-2f)
                assertEquals("r=$r 회전 왕복 y", uy, upright.y, 1e-2f)
                val v = t.mapPoint(upright)
                val e = expectedView(ux, uy, iw, ih, vw, vh, mode, front)
                val tol = 1e-2f * maxOf(vw, vh) / 100f
                assertEquals("r=$r $mode front=$front ${vw}x$vh x", e.x, v.x, tol)
                assertEquals("r=$r $mode front=$front ${vw}x$vh y", e.y, v.y, tol)
            }
        }
    }

    @Test
    fun bufferCorners_landOnTheRightUprightCorners() {
        // CameraX rotationDegrees 는 버퍼를 바로 세우려고 시계 방향으로 돌릴 각도 — 90 이면 버퍼 왼쪽 위가 세운 이미지의 오른쪽 위
        val w = 640
        val h = 480
        fun up(x: Float, y: Float, r: Int) = FrameRotation.rotatePoint(x, y, w, h, r)
        assertEquals(ScanPoint(480f, 0f), up(0f, 0f, 90))
        assertEquals(ScanPoint(480f, 640f), up(640f, 0f, 90))
        assertEquals(ScanPoint(0f, 0f), up(0f, 480f, 90))
        assertEquals(ScanPoint(640f, 480f), up(0f, 0f, 180))
        assertEquals(ScanPoint(0f, 640f), up(0f, 0f, 270))
        assertEquals(ScanPoint(0f, 0f), up(640f, 0f, 270))
    }

    @Test
    fun fillCenter_cropWindow_mapsToViewEdges() {
        // 세운 480x640(3:4) 을 1080x2340(9:19.5) 뷰에 FILL — 높이에 맞춰(배율 3.65625) 좌우가 잘린다
        val t = PreviewTransform(480, 640, 1080f, 2340f, PreviewScaleMode.FILL, false)
        val s = 2340f / 640f
        assertEquals(s, t.scale, 1e-5f)
        val visibleW = 1080f / s
        val cropLeft = (480f - visibleW) / 2f
        assertEquals(0f, t.mapX(cropLeft), 1e-2f)
        assertEquals(1080f, t.mapX(cropLeft + visibleW), 1e-2f)
        assertEquals(0f, t.mapY(0f), 1e-3f)
        assertEquals(2340f, t.mapY(640f), 1e-2f)
        // 잘린 띠 안의 코드는 보이지 않는다고 판정한다
        assertFalse(t.isVisible(t.mapBounds(CodeBounds(0f, 300f, cropLeft - 1f, 340f))))
        assertTrue(t.isVisible(t.mapBounds(CodeBounds(cropLeft + 1f, 300f, cropLeft + 20f, 340f))))
        // 전면: 같은 잘린 창이 좌우로 뒤집힌다 — 이미지 왼쪽 끝 창이 뷰 오른쪽 끝
        val f = PreviewTransform(480, 640, 1080f, 2340f, PreviewScaleMode.FILL, true)
        assertEquals(1080f, f.mapX(cropLeft), 1e-2f)
        assertEquals(0f, f.mapX(cropLeft + visibleW), 1e-2f)
        // 역변환도 같은 창
        assertEquals(cropLeft, t.unmapX(0f), 1e-3f)
        assertEquals(cropLeft, f.unmapX(1080f), 1e-3f)
    }

    @Test
    fun fitCenter_letterbox_mapsImageEdgesInsideView() {
        val t = PreviewTransform(480, 640, 1080f, 2340f, PreviewScaleMode.FIT, false)
        val s = 1080f / 480f
        val band = (2340f - 640f * s) / 2f
        assertEquals(0f, t.mapX(0f), 1e-3f)
        assertEquals(1080f, t.mapX(480f), 1e-2f)
        assertEquals(band, t.mapY(0f), 1e-2f)
        assertEquals(2340f - band, t.mapY(640f), 1e-2f)
        val area = t.visibleImageArea()
        assertEquals(band, area.top, 1e-2f)
        assertEquals(2340f - band, area.bottom, 1e-2f)
    }

    @Test
    fun frontLens_boxStaysOverMirroredCode_forEveryRotation() {
        // 전면 카메라: 세운 이미지 왼쪽 위 사분면의 코드는 (거울처럼 보이는) 미리보기에서 오른쪽 위에 보인다
        for (r in listOf(0, 90, 180, 270)) {
            val (bw, bh) = 640 to 480
            val iw = if (r % 180 == 0) bw else bh
            val ih = if (r % 180 == 0) bh else bw
            val code = CodeBounds(iw * 0.1f, ih * 0.1f, iw * 0.3f, ih * 0.3f)
            val buf = FrameRotation.enclose(listOf(toBuffer(code.left, code.top, bw, bh, r), toBuffer(code.right, code.bottom, bw, bh, r)))!!
            val upright = FrameRotation.rotateBounds(buf, bw, bh, r)
            assertEquals("r=$r", code.centerX, upright.centerX, 1e-2f)
            val v = PreviewTransform(iw, ih, 1000f, 1000f, PreviewScaleMode.FIT, true).mapBounds(upright)
            assertTrue("r=$r 오른쪽 ${v}", v.centerX > 500f)
            assertTrue("r=$r 위 ${v}", v.centerY < 500f)
            assertTrue("r=$r 정규화", v.left <= v.right && v.top <= v.bottom)
        }
    }
}
