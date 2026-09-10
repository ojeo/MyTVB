package com.mytvb.feature.player.danmaku.clock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PresentationTimestampResolver 决策回归（定稿 §16-18 / 约束 1）。
 *
 * 覆盖：期望时刻优先 / legacy fallback / 无效期望回退 / 负 legacy 钳制。
 */
class PresentationTimestampResolverTest {

    @Test
    fun expectedPresentationTimeWinsWhenProvided() {
        val expected = 1_000_000_000_000L
        val legacy = 1_000_000_000_050L
        assertEquals(expected, PresentationTimestampResolver.resolve(expected, legacy))
    }

    @Test
    fun fallsBackToLegacyWhenExpectedUnavailable() {
        val legacy = 1_000_000_000L
        assertEquals(legacy, PresentationTimestampResolver.resolve(0L, legacy))
    }

    @Test
    fun fallsBackToLegacyWhenExpectedNegative() {
        val legacy = 1_000_000_000L
        assertEquals(legacy, PresentationTimestampResolver.resolve(-1L, legacy))
    }

    @Test
    fun clampsNegativeLegacyToZero() {
        assertEquals(0L, PresentationTimestampResolver.resolve(0L, -5L))
    }
}
