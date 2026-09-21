package com.mytvb.feature.player.danmaku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕渲染质量档位的回归：
 * - 默认档必须等价于改造前行为（系数 1.0），否则升级会悄悄改变所有设备的画质与负载；
 * - 选项数组必须与枚举 prefValue 完全一致（设置页直接由 `OPTIONS` 派生，失配会导致读取静默回退默认档）；
 * - 系数单调递减且最低档乘上 TV 设备基准后仍不低于降载下限 0.5
 *   （低于下限会让 `coerceIn(min, max)` 的区间反转，同时弹幕清晰度也会崩）。
 */
class DanmakuRenderQualityTest {

    private companion object {
        /** 与 GlDanmakuView.deviceRenderScale 的 TV 分支保持一致。 */
        const val TV_DEVICE_SCALE = 0.75f

        /** 与 GlDanmakuView.MIN_RENDER_SCALE 保持一致。 */
        const val MIN_RENDER_SCALE = 0.5f
    }

    @Test
    fun defaultIsHighAndKeepsLegacyBehavior() {
        assertSame(DanmakuRenderQuality.HIGH, DanmakuRenderQuality.DEFAULT)
        assertEquals(1.0f, DanmakuRenderQuality.DEFAULT.scaleFactor, 0.0001f)
    }

    @Test
    fun optionsMatchEnumPrefValuesInOrder() {
        assertArrayEquals(
            arrayOf("高清", "均衡", "流畅优先"),
            DanmakuRenderQuality.OPTIONS,
        )
    }

    @Test
    fun fromPrefResolvesEveryOption() {
        for (quality in DanmakuRenderQuality.values()) {
            assertSame(quality, DanmakuRenderQuality.fromPref(quality.prefValue))
        }
    }

    @Test
    fun fromPrefFallsBackToDefault() {
        assertSame(DanmakuRenderQuality.DEFAULT, DanmakuRenderQuality.fromPref(null))
        assertSame(DanmakuRenderQuality.DEFAULT, DanmakuRenderQuality.fromPref(""))
        assertSame(DanmakuRenderQuality.DEFAULT, DanmakuRenderQuality.fromPref("不存在的档位"))
        // 大小写/空白不匹配也应回退默认（存的是展示值，不做模糊匹配）
        assertSame(DanmakuRenderQuality.DEFAULT, DanmakuRenderQuality.fromPref("高清 "))
    }

    @Test
    fun scaleFactorsAreMonotonicAndBounded() {
        val factors = DanmakuRenderQuality.values().map { it.scaleFactor }
        for (i in 0 until factors.size - 1) {
            assertTrue("系数必须逐档递减: $factors", factors[i] > factors[i + 1])
        }
        assertTrue("系数必须在 (0, 1] 内: $factors", factors.all { it > 0f && it <= 1f })
    }

    @Test
    fun lowestTierStillRespectsRenderScaleFloorOnTv() {
        val lowestBase = TV_DEVICE_SCALE * DanmakuRenderQuality.values().last().scaleFactor
        assertTrue(
            "最低档 × TV 基准 = $lowestBase，不得低于降载下限 $MIN_RENDER_SCALE",
            lowestBase >= MIN_RENDER_SCALE,
        )
    }
}
