package com.mytvb.feature.player.danmaku.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕区域占比与 band 高度换算的回归。
 *
 * 这两个纯函数的正确性直接决定 GPU 直播 band 模式的画面：
 * - [danmakuAreaFraction] 必须与下发给引擎的 area 同源（引擎 area 与 band 高度取自同一映射，
 *   失配会导致弹幕超出 band 或被挤在 band 的一小部分里）；
 * - [danmakuBandHeightPx] 在 fraction ≥ 1 时必须原样返回整屏高度（关闭 band 时行为不变），
 *   且任何输入下都不得返回 0/负数（0 高度会让 Surface 无法分配 buffer）。
 */
class DanmakuLayoutFractionsTest {

    private companion object {
        /** 典型 1080p 电视。 */
        const val SCREEN_HEIGHT = 1080
    }

    @Test
    fun areaFractionMatchesLegacyScreenAreaMapping() {
        // 与已移除功能向引擎的 toDanmakuScreenPart 一致：设置项 → 占屏比例
        assertEquals(1f / 8f, danmakuAreaFraction(-1), 0.0001f)
        assertEquals(0.16f, danmakuAreaFraction(0), 0.0001f)
        assertEquals(0.25f, danmakuAreaFraction(1), 0.0001f)
        assertEquals(0.5f, danmakuAreaFraction(3), 0.0001f)
        assertEquals(0.75f, danmakuAreaFraction(7), 0.0001f)
        // 未知值一律按全屏（与旧实现 else -> 1f 一致）
        assertEquals(1f, danmakuAreaFraction(2), 0.0001f)
        assertEquals(1f, danmakuAreaFraction(99), 0.0001f)
    }

    @Test
    fun bandHeightIsFullHeightWhenDisabled() {
        assertEquals(SCREEN_HEIGHT, danmakuBandHeightPx(SCREEN_HEIGHT, 1f))
        // 大于 1 的输入同样按全屏处理（不会算出超过屏幕的高度）
        assertEquals(SCREEN_HEIGHT, danmakuBandHeightPx(SCREEN_HEIGHT, 1.5f))
    }

    @Test
    fun bandHeightFollowsFraction() {
        assertEquals(SCREEN_HEIGHT / 2, danmakuBandHeightPx(SCREEN_HEIGHT, 0.5f))
        assertEquals(SCREEN_HEIGHT / 4, danmakuBandHeightPx(SCREEN_HEIGHT, 0.25f))
        assertEquals(135, danmakuBandHeightPx(SCREEN_HEIGHT, 1f / 8f))
    }

    @Test
    fun bandHeightNeverCollapses() {
        // 极小/非法比例被钳到下限，且任何输入都不会返回 0 或负数（0 高度无法分配 buffer）
        assertTrue(danmakuBandHeightPx(SCREEN_HEIGHT, 0f) >= 1)
        assertTrue(danmakuBandHeightPx(SCREEN_HEIGHT, -3f) >= 1)
        assertTrue(danmakuBandHeightPx(0, 0.5f) >= 1)
        assertTrue(danmakuBandHeightPx(1, 1f / 8f) >= 1)
    }

    @Test
    fun bandHeightForTinyScreenStillPositive() {
        // 1px 高度 + 最小比例：仍要保证 ≥1，不能变成 0
        assertEquals(1, danmakuBandHeightPx(1, MIN_DANMAKU_BAND_FRACTION))
        assertEquals(0, danmakuBandHeightPx(0, 1f))
    }

    @Test
    fun minBandFractionMatchesSmallestPreset() {
        // 下限必须 ≤ 最小的设置档（否则最小档会被抬高，与引擎 area 失配）
        assertTrue(MIN_DANMAKU_BAND_FRACTION <= danmakuAreaFraction(-1) + 0.0001f)
    }
}
