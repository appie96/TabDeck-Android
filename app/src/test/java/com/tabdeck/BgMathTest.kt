package com.tabdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 내 배경 위치·확대 계산 확인 (화면 1080×2400, 가로로 긴 그림 1600×900 기준)
class BgMathTest {
    private val w = 1080f; private val h = 2400f
    private val iw = 1600f; private val ih = 900f

    @Test fun fillCoversScreen() {
        val c = BgMath.clamp(BgCrop(), w, h, iw, ih)
        val s = BgMath.scale(c.zoom, w, h, iw, ih)
        assertTrue(iw * s >= w - 0.5f && ih * s >= h - 0.5f)        // 꽉 채우기면 빈 곳이 없음
    }

    @Test fun cannotDragPastEdge() {
        val c = BgMath.clamp(BgCrop(0f, 0f, 1f), w, h, iw, ih)
        val (u, v) = BgMath.toImage(0f, 0f, c, w, h, iw, ih)          // 화면 왼쪽 위 = 그림 안쪽
        assertEquals(0f, u, 1e-4f)
        assertEquals(0f, v, 1e-4f)
    }

    @Test fun zoomOutShowsWholeImageCentered() {
        val c = BgMath.clamp(BgCrop(0.1f, 0.9f, 0f), w, h, iw, ih)
        assertEquals(BgMath.minZoom(w, h, iw, ih), c.zoom, 1e-6f)
        assertEquals(0.5f, c.cx, 1e-6f); assertEquals(0.5f, c.cy, 1e-6f)
        val (u, _) = BgMath.toImage(0f, h / 2, c, w, h, iw, ih)
        assertEquals(0f, u, 1e-4f)                                   // 가로가 화면에 딱 맞음
    }

    @Test fun darkBandIsDark() {
        val black = BgMath.bandLuma(BgCrop(), w, h, iw, ih, 0f, 0.1f, 0.94f) { _, _ -> 0xFF000000.toInt() }
        val white = BgMath.bandLuma(BgCrop(), w, h, iw, ih, 0f, 0.1f, 0.94f) { _, _ -> 0xFFFFFFFF.toInt() }
        assertEquals(0f, black, 1e-4f); assertEquals(1f, white, 1e-4f)
    }
}
