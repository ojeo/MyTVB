package com.tutu.myblbl.feature.player.danmaku

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuFrameRateTest {
    @Test
    fun calculatesFrameRateFromIndependentCounterWindow() {
        assertEquals(60f, calculateDanmakuFrameRate(frameDelta = 30L, elapsedMs = 500L), 0.001f)
        assertEquals(37.5f, calculateDanmakuFrameRate(frameDelta = 30L, elapsedMs = 800L), 0.001f)
    }

    @Test
    fun emptyOrInvalidWindowReportsZero() {
        assertEquals(0f, calculateDanmakuFrameRate(frameDelta = 0L, elapsedMs = 500L), 0.001f)
        assertEquals(0f, calculateDanmakuFrameRate(frameDelta = 30L, elapsedMs = 0L), 0.001f)
        assertEquals(0f, calculateDanmakuFrameRate(frameDelta = -1L, elapsedMs = 500L), 0.001f)
    }
}
