package net.plumt.qr.internal

import net.plumt.qr.ArgbImage
import net.plumt.qr.CodeColors
import net.plumt.qr.CodeMatrix
import net.plumt.qr.LogoQrCode

/** 격자 → ARGB 픽셀. 정수 배 확대, 가운데 정렬. */
internal object Raster {

    class Rendered(val image: ArgbImage, val scale: Int, val left: Int, val top: Int)

    fun render(matrix: CodeMatrix, widthPx: Int, heightPx: Int, colors: CodeColors): Rendered {
        require(widthPx >= 1 && heightPx >= 1) { "widthPx($widthPx)·heightPx($heightPx) 는 1 이상이어야 한다" }
        require(widthPx.toLong() * heightPx <= CodeMatrix.MAX_CELLS) {
            "크기(${widthPx}x$heightPx) 가 너무 크다 — 최대 ${CodeMatrix.MAX_CELLS} 픽셀"
        }
        val scale = minOf(widthPx / matrix.width, heightPx / matrix.height)
        require(scale >= 1) {
            "크기(${widthPx}x$heightPx) 가 격자(${matrix.width}x${matrix.height}) 보다 작다 — 한 칸이 1픽셀 이상이어야 한다"
        }
        val left = (widthPx - matrix.width * scale) / 2
        val top = (heightPx - matrix.height * scale) / 2
        val out = IntArray(widthPx * heightPx) { colors.background }
        val cells = matrix.toBooleanArray()
        for (my in 0 until matrix.height) {
            for (mx in 0 until matrix.width) {
                if (!cells[my * matrix.width + mx]) continue
                val x0 = left + mx * scale
                for (py in top + my * scale until top + (my + 1) * scale) out.fill(colors.foreground, py * widthPx + x0, py * widthPx + x0 + scale)
            }
        }
        return Rendered(ArgbImage(widthPx, heightPx, out), scale, left, top)
    }

    fun renderWithLogo(code: LogoQrCode, sizePx: Int, logo: ArgbImage, colors: CodeColors): ArgbImage {
        val base = render(code.matrix, sizePx, sizePx, colors)
        val s = base.scale
        val area = code.logoArea
        val boxLeft = base.left + area.left * s
        val boxTop = base.top + area.top * s
        val boxW = area.width * s
        val boxH = area.height * s
        // 비율을 지켜 상자 안에 맞춘다
        val (dw, dh) = if (logo.width.toLong() * boxH >= logo.height.toLong() * boxW) {
            boxW to maxOf(1, (logo.height.toLong() * boxW / logo.width).toInt())
        } else {
            maxOf(1, (logo.width.toLong() * boxH / logo.height).toInt()) to boxH
        }
        val ox = boxLeft + (boxW - dw) / 2
        val oy = boxTop + (boxH - dh) / 2
        val out = base.image.raw().copyOf()
        val src = logo.raw()
        for (dy in 0 until dh) {
            val sy0 = (dy.toLong() * logo.height / dh).toInt()
            val sy1 = maxOf(sy0 + 1, ((dy + 1).toLong() * logo.height / dh).toInt())
            for (dx in 0 until dw) {
                val sx0 = (dx.toLong() * logo.width / dw).toInt()
                val sx1 = maxOf(sx0 + 1, ((dx + 1).toLong() * logo.width / dw).toInt())
                val i = (oy + dy) * sizePx + ox + dx
                out[i] = over(average(src, logo.width, sx0, sx1, sy0, sy1), out[i])
            }
        }
        return ArgbImage(sizePx, sizePx, out)
    }

    /** 원본 사각형 [x0, x1) x [y0, y1) 의 평균(미리 곱한 알파로 평균해 투명 픽셀의 색이 번지지 않게). */
    fun average(src: IntArray, stride: Int, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var a = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = src[y * stride + x]
                val pa = (p ushr 24).toLong()
                a += pa
                r += ((p shr 16) and 0xFF) * pa
                g += ((p shr 8) and 0xFF) * pa
                b += (p and 0xFF) * pa
            }
        }
        if (a == 0L) return 0
        val n = (x1 - x0).toLong() * (y1 - y0)
        val outA = ((a + n / 2) / n).toInt()
        fun ch(v: Long) = ((v + a / 2) / a).toInt().coerceIn(0, 0xFF)
        return (outA shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** source-over 합성. */
    fun over(src: Int, dst: Int): Int {
        val sa = src ushr 24
        if (sa == 0xFF) return src
        if (sa == 0) return dst
        val da = dst ushr 24
        // 결과 알파 = sa + da(1 - sa), 색 = (s·sa + d·da(1 - sa)) / 결과 알파 — 0..255 정수로 계산한다
        val dw = da * (0xFF - sa) // 0..255²
        val outA255 = sa * 0xFF + dw // 결과 알파 x 255
        fun ch(shift: Int): Int {
            val s = (src shr shift) and 0xFF
            val d = (dst shr shift) and 0xFF
            return ((s * sa * 0xFF + d * dw + outA255 / 2) / outA255).coerceIn(0, 0xFF)
        }
        val outA = (outA255 + 0xFF / 2) / 0xFF
        return (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
