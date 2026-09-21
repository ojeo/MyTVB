package com.mytvb.feature.player.danmaku.render

/**
 * 弹幕负载档位（保播放降载）。
 *
 * 设计取舍：视频卡顿的代价高于弹幕观感，因此档位只朝"减负载"方向扩张，
 * 每一档同时收紧三件事——弹幕层提交帧率、引擎同屏上限、弹幕层光栅化分辨率；
 * 最后一档直接整层停渲染，把透明的全屏 Surface 从合成里摘出去。
 *
 * @property renderFps 弹幕层目标提交帧率（Hz）。
 * @property onScreenScale 引擎同屏上限缩放系数（1.0 = 不缩）。
 * @property renderScaleFactor Surface 光栅化分辨率系数（相对设备基准 renderScale）。
 * @property suspendLayer 是否整层停渲染（连 vsync 提交都不做）。
 * @property lowPriorityCache 是否让建图线程退让到 BACKGROUND（与解码争 CPU 时保播放）。
 */
internal enum class DanmakuLoadLevel(
    val renderFps: Int,
    val onScreenScale: Float,
    val renderScaleFactor: Float,
    val suspendLayer: Boolean,
    val lowPriorityCache: Boolean,
) {
    NORMAL(
        renderFps = 60, onScreenScale = 1.0f, renderScaleFactor = 1.0f,
        suspendLayer = false, lowPriorityCache = false,
    ),
    THROTTLED(
        renderFps = 30, onScreenScale = 0.65f, renderScaleFactor = 1.0f,
        suspendLayer = false, lowPriorityCache = false,
    ),
    REDUCED(
        renderFps = 30, onScreenScale = 0.45f, renderScaleFactor = 0.8f,
        suspendLayer = false, lowPriorityCache = true,
    ),
    SUSPENDED(
        renderFps = 30, onScreenScale = 0.35f, renderScaleFactor = 0.8f,
        suspendLayer = true, lowPriorityCache = true,
    ),
}

/**
 * 视频帧率驱动的弹幕降载判定器（单线程使用：主线程 100ms 采样点，无锁）。
 *
 * 与 [com.mytvb.feature.player.danmaku.AdaptiveOnScreenLimit] 的分工：
 * 后者看的是"弹幕渲染自己的帧率"，只能保证弹幕画得动；本类看的是
 * **视频帧率相对本机能力基线是否被拖累**，用来保证播放本身不掉帧。
 * 弱机上两者会背离（弹幕仍 60fps，视频解码已经饥饿），这正是"开弹幕就卡"的现场。
 *
 * ## 基线口径（防误判的关键）
 *
 * 视频帧率不是"越高越正常"：起播 flush 与 seek 会让播放器在短时间内高速刷帧，
 * 单个窗口的观测可以虚高到远超内容帧率。若把这种尖峰当成本机能力，随后正常的
 * 24/30fps 就会被判成"劣化到 40%"并一路降到最后一档——这比卡顿更糟。因此：
 * - **参考值取前两个有效窗口的较小值**：任一窗口是尖峰都不会抬高参考；
 * - **向上更新单窗口最多 +[BASELINE_UP_STEP]**：持续高帧率几个窗口内即可把参考抬上去，
 *   单次尖峰则抬不动；
 * - **向下更新只做极慢跟随（每窗口 [BASELINE_DECAY]）**：避免参考被持续劣化的帧率
 *   拖下水，否则"相对参考劣化"永远判定不出来。
 *
 * 迟滞：降档快（连续 [degradeConfirmWindows] 个劣化窗口，默认 1 → 3s 响应），
 * 升档慢（连续 [recoverConfirmWindows] 个健康窗口，默认 3 → 9s 才还一档），
 * 避免"降档 → 视频恢复 → 升档 → 又卡"的振荡。
 */
internal class DanmakuLoadGuard(
    private val degradeRatio: Float = DEGRADE_RATIO,
    private val recoverRatio: Float = RECOVER_RATIO,
    private val degradeConfirmWindows: Int = DEGRADE_CONFIRM_WINDOWS,
    private val recoverConfirmWindows: Int = RECOVER_CONFIRM_WINDOWS,
    private val baselineDecay: Float = BASELINE_DECAY,
    private val baselineUpStep: Float = BASELINE_UP_STEP,
) {

    /** 当前档位。 */
    var level: DanmakuLoadLevel = DanmakuLoadLevel.NORMAL
        private set

    /** 能力基线帧率；≤0 表示尚未建立（建立前不判定）。 */
    var baselineFps: Float = 0f
        private set

    private var firstSampleFps = 0f
    private var degradeStreak = 0
    private var recoverStreak = 0

    /**
     * 喂入一个统计窗口（调用方固定周期调用，见 GlDanmakuView.LOAD_GUARD_WINDOW_MS）。
     *
     * @return 非 null 表示档位发生变化，调用方据此落地新的渲染策略。
     */
    fun onWindow(videoFps: Float, isPlaying: Boolean): DanmakuLoadLevel? {
        // 暂停/缓冲（isPlaying=false）期间的帧率无意义：缓冲时视频本来就不出帧，
        // 若据此降档会把"网络卡"误判成"弹幕卡"。
        if (!isPlaying || !videoFps.isFinite() || videoFps < MIN_VALID_FPS) {
            degradeStreak = 0
            recoverStreak = 0
            return null
        }
        val baseline = baselineFps
        if (baseline <= 0f) {
            if (firstSampleFps <= 0f) {
                firstSampleFps = videoFps
                return null
            }
            // 取前两个有效窗口的较小值作参考；建立参考的这一窗口不判定。
            baselineFps = minOf(firstSampleFps, videoFps)
            return null
        }
        baselineFps =
            if (videoFps > baseline) {
                minOf(videoFps, baseline * baselineUpStep)
            } else {
                baseline * (1f - baselineDecay) + videoFps * baselineDecay
            }

        val ratio = videoFps / baselineFps
        return when {
            ratio < degradeRatio -> {
                recoverStreak = 0
                if (++degradeStreak >= degradeConfirmWindows) {
                    degradeStreak = 0
                    stepDown()
                } else {
                    null
                }
            }

            ratio >= recoverRatio -> {
                degradeStreak = 0
                if (++recoverStreak >= recoverConfirmWindows) {
                    recoverStreak = 0
                    stepUp()
                } else {
                    null
                }
            }

            else -> {
                degradeStreak = 0
                recoverStreak = 0
                null
            }
        }
    }

    /** 重置为满血并清空参考（弹幕被关闭、或播放源切换后重新建立能力基线）。 */
    fun reset() {
        level = DanmakuLoadLevel.NORMAL
        baselineFps = 0f
        firstSampleFps = 0f
        degradeStreak = 0
        recoverStreak = 0
    }

    private fun stepDown(): DanmakuLoadLevel? {
        val next = when (level) {
            DanmakuLoadLevel.NORMAL -> DanmakuLoadLevel.THROTTLED
            DanmakuLoadLevel.THROTTLED -> DanmakuLoadLevel.REDUCED
            DanmakuLoadLevel.REDUCED -> DanmakuLoadLevel.SUSPENDED
            DanmakuLoadLevel.SUSPENDED -> null
        } ?: return null
        level = next
        return next
    }

    private fun stepUp(): DanmakuLoadLevel? {
        val next = when (level) {
            DanmakuLoadLevel.SUSPENDED -> DanmakuLoadLevel.REDUCED
            DanmakuLoadLevel.REDUCED -> DanmakuLoadLevel.THROTTLED
            DanmakuLoadLevel.THROTTLED -> DanmakuLoadLevel.NORMAL
            DanmakuLoadLevel.NORMAL -> null
        } ?: return null
        level = next
        return next
    }

    private companion object {
        /** 劣化线：视频帧率低于基线的该比例即认定被弹幕拖累。 */
        const val DEGRADE_RATIO = 0.88f

        /** 恢复线：回到基线的该比例才允许还档（与劣化线之间留死区，防振荡）。 */
        const val RECOVER_RATIO = 0.97f

        /** 降档确认窗口数（1 个窗口 = 3s，追求快速响应）。 */
        const val DEGRADE_CONFIRM_WINDOWS = 1

        /** 升档确认窗口数（3 个窗口 = 9s，追求稳定不振荡）。 */
        const val RECOVER_CONFIRM_WINDOWS = 3

        /** 基线向下跟随系数（每窗口 2%，只用于极慢漂移跟踪）。 */
        const val BASELINE_DECAY = 0.02f

        /** 基线单窗口向上步进上限（挡住 seek/起播的瞬时高速刷帧）。 */
        const val BASELINE_UP_STEP = 1.10f

        /** 低于该帧率视为无有效样本（起播空窗、暂停残留值等）。 */
        const val MIN_VALID_FPS = 5f
    }
}
