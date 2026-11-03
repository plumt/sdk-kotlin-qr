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
