package com.tutu.myblbl.feature.player.danmaku.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.tutu.myblbl.core.common.log.AppLog
import com.tutu.myblbl.feature.player.danmaku.CacheBuildResult
import com.tutu.myblbl.feature.player.danmaku.CacheManager
import com.tutu.myblbl.feature.player.danmaku.Danmaku
import com.tutu.myblbl.feature.player.danmaku.DanmakuConfig
import com.tutu.myblbl.feature.player.danmaku.DanmakuEngine
import com.tutu.myblbl.feature.player.danmaku.DanmakuFontWeight
import com.tutu.myblbl.feature.player.danmaku.DanmakuLaneDensity
import com.tutu.myblbl.feature.player.danmaku.DanmakuRenderHost
import com.tutu.myblbl.feature.player.danmaku.DanmakuTimer
import com.tutu.myblbl.feature.player.danmaku.common.BiliDanmakuStyle
import com.tutu.myblbl.feature.player.danmaku.model.DanmakuKind
import com.tutu.myblbl.feature.player.view.DanmakuMaskHostLayout
import kotlin.math.roundToInt
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPU 弹幕视图：SurfaceView + 独立 RenderThread + GLES3 Instanced 渲染。
 *
 * 线程模型（对齐高性能弹幕方案）：
 * - RenderThread：独占 EGL/GL 状态，Choreographer 驱动每帧；媒体时钟推进、纹理上传、实例构建、绘制全在此线程。
 * - ActionThread：弹幕调度（Timeline 入场/轨道分配/缓存调度），复用 [DanmakuEngine] 成熟逻辑。
 * - 主线程：仅做 View 操作与参数注入（providers），渲染状态由 RenderThread 独占。
 *
 * 与 Canvas 引擎（[BlblDanmakuController] 原 [com.tutu.myblbl.feature.player.danmaku.DanmakuView]）共用同一套
 * [DanmakuRenderHost] 接口，可无缝切换；EGL 初始化失败通过 [onEglFailed] 通知上层回退。
 *
 * 说明：
 * - 防挡蒙版（mask）通过 View 层 clipPath 实现，对独立 Surface 无效；GPU 引擎 V1 不做蒙版裁剪
 *   （对齐方案中"人脸防挡"归入第三阶段）。
 * - Surface 使用 setZOrderMediaOverlay(true)，位于视频 Surface 之上、普通 View 层之下，
 *   控制栏等 UI 自然覆盖弹幕，兼具避让效果。
 */
class GlDanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), DanmakuRenderHost, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "GlDmView"

        private const val MSG_ACT = 2101
        private const val MSG_SET = 3101
        private const val MSG_APPEND = 3102
        private const val MSG_TRIM_RANGE = 3103
        private const val MSG_REPLACE_FROM = 3104
        private const val MSG_SEEK = 3105
        private const val MSG_CLEAR = 3106
        private const val MSG_VIEWPORT = 3201
        private const val MSG_CONFIG = 3202
        private const val MSG_CACHE_RESULT = 3203
        private const val MSG_RELEASE = 3999

        private const val MSG_INIT_EGL = 3301
        private const val MSG_RELEASE_EGL = 3302
        private const val MSG_FRAME = 3303

        private const val DANMAKU_FONT_BORDER_DEFAULT = 0

        /** 渲染性能采样日志间隔（ms）。 */
        private const val PERF_LOG_INTERVAL_MS = 3_000L

        // ---- Debug Overlay ----
        private const val DIAG_RING_CAPACITY = 600
        private const val DIAG_UPDATE_INTERVAL_MS = 500L
        private const val DIAG_BG_COLOR = 0xCC000000.toInt()
        private const val DIAG_LINE_COUNT = 7
    }

    // ---- providers（由 BlblDanmakuController 注入）----
    private var positionProvider: (() -> Long)? = null
    private var isPlayingProvider: (() -> Boolean)? = null
    private var playWhenReadyProvider: (() -> Boolean)? = null
    private var playbackSpeedProvider: (() -> Float)? = null
    private var configProvider: (() -> DanmakuConfig)? = null

    // ---- 引擎组件（复用现有成熟模块）----
    private val cacheManager: CacheManager
    private val engine: DanmakuEngine
    private val timer = DanmakuTimer()
    private val seekSerial = AtomicInteger(0)
    private val uiFrameId = AtomicInteger(0)

    // ---- 线程 ----
    private val actionThread = HandlerThread("Danmaku-GL-Action").apply { start() }
    private val actionHandler = ActionHandler(actionThread.looper)
    private val renderThread = HandlerThread("Danmaku-GL-Render").apply { start() }
    private val renderHandler = RenderHandler(renderThread.looper)

    // ---- GL 状态（RenderThread 独占）----
    private var egl: GlEglHelper? = null
    private var renderer: GlInstancedRenderer? = null
    private var atlas: GlTextureAtlas? = null
    private val pageTextures = IntArray(GlInstancedRenderer.MAX_PAGES)
    @Volatile private var eglReady = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var released = false
    private var renderLoopRunning = false
    private val frameCallback = FrameCallback()

    private var lastConfig: DanmakuConfig? = null
    private var lastStyleKey: String? = null

    private val viewportTopInsetPx = dp(2f)
    private val viewportBottomInsetPx = dp(52f)

    /** EGL 初始化失败回调：上层用于自动降级到 Canvas 引擎。 */
    var onEglFailed: (() -> Unit)? = null

    // ---- 性能采样（RenderThread 私有，周期日志）----
    private var perfAccumFrameNs = 0L
    private var perfMaxFrameNs = 0L
    private var perfFrameCount = 0
    private var perfLastLogUptimeMs = 0L

    // ---- Debug Overlay（方案 §70；RenderThread 采集 RingBuffer，onDraw 主线程绘制）----
    @Volatile private var debugOverlayEnabled = false
    @Volatile private var manualDebugOverlay = false
    private val frameTimeRingNs = LongArray(DIAG_RING_CAPACITY)
    private var frameTimeRingHead = 0
    private var frameTimeRingCount = 0
    private var lastDiagFrameCount = 0
    private var lastDiagUpdateUptimeMs = 0L
    @Volatile private var diagSnapshot: GlDiagnosticSnapshot? = null
    private val diagBgPaint = Paint().apply { color = DIAG_BG_COLOR }
    private val diagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f).toFloat()
        typeface = Typeface.MONOSPACE
    }
    /** Debug Overlay 的视频 FPS 来源（MyPlayerView 注入，由 VideoFrameMetadataListener 统计）。 */
    var videoFpsProvider: (() -> Float)? = null
    /** UI FPS：主线程 onDraw 被 vsync 驱动的实际绘制频率（Debug 时每帧刷新 onDraw 统计）。 */
    @Volatile private var uiFps = 0f
    private var uiFrameCount = 0L
    private var uiFrameWindowStartMs = 0L

    // ---- 防挡蒙版（B 站 webmask；RenderThread 消费 maskHost 发布的 path 快照）----
    /** 由 MyPlayerView 注入：返回当前弹幕防挡蒙版宿主。 */
    var maskHostProvider: (() -> DanmakuMaskHostLayout?)? = null
    private var maskTextureId = 0
    private var lastMaskVersion = -1
    private val maskPath = Path()
    private var maskBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
    private val maskCanvas = Canvas()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        holder.addCallback(this)
        setZOrderMediaOverlay(true)
        // 关键：SurfaceView 默认 opaque（PixelFormat.OPAQUE）。若保持不透明，弹幕 Surface
        // 会以不透明黑底覆盖下层视频 Surface → 视频全黑。必须声明透明格式，SurfaceFlinger
        // 才能对弹幕 Surface 做 alpha 混合、透出视频画面。
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // SurfaceView 默认 willNotDraw=true（View 层不绘制），但 Debug Overlay 面板要画在
        // View 合成层（弹幕 Surface 之上），必须显式允许 onDraw。
        setWillNotDraw(false)
        cacheManager = CacheManager(context.applicationContext, resources.displayMetrics.density) { result ->
            actionHandler.obtainMessage(MSG_CACHE_RESULT, result).sendToTarget()
        }
        engine = DanmakuEngine(context.applicationContext, resources.displayMetrics, cacheManager)
    }

    override val renderView: View get() = this

    // ========== SurfaceHolder.Callback ==========

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderHandler.obtainMessage(MSG_INIT_EGL, holder.surface).sendToTarget()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        renderHandler.obtainMessage(MSG_VIEWPORT, width.coerceAtLeast(0), height.coerceAtLeast(0)).sendToTarget()
        // 视口变化后 mask 纹理需按新尺寸重建（强制下帧重新栅格化）。
        lastMaskVersion = -1
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceWidth = 0
        surfaceHeight = 0
        renderHandler.sendEmptyMessage(MSG_RELEASE_EGL)
    }

    // ========== DanmakuRenderHost ==========

    override fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    override fun setIsPlayingProvider(provider: () -> Boolean) {
        isPlayingProvider = provider
    }

    override fun setPlayWhenReadyProvider(provider: () -> Boolean) {
        playWhenReadyProvider = provider
    }

    override fun setPlaybackSpeedProvider(provider: () -> Float) {
        playbackSpeedProvider = provider
    }

    override fun setConfigProvider(provider: () -> DanmakuConfig) {
        configProvider = provider
    }

    override fun setDanmakus(list: List<Danmaku>) {
        actionHandler.obtainMessage(MSG_SET, list).sendToTarget()
        requestRenderFrame()
    }

    override fun appendDanmakus(list: List<Danmaku>, maxItems: Int, alreadySorted: Boolean) {
        if (list.isEmpty()) return
        actionHandler.obtainMessage(MSG_APPEND, AppendPayload(list, maxItems, alreadySorted)).sendToTarget()
        requestRenderFrame()
    }

    override fun replaceDanmakusFrom(minTimeMs: Long, list: List<Danmaku>) {
        actionHandler.obtainMessage(MSG_REPLACE_FROM, ReplaceFromPayload(minTimeMs, list)).sendToTarget()
        requestRenderFrame()
    }

    override fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        actionHandler.obtainMessage(MSG_TRIM_RANGE, TrimRangePayload(minTimeMs, maxTimeMs)).sendToTarget()
        requestRenderFrame()
    }

    override fun notifySeek(positionMs: Long) {
        seekSerial.incrementAndGet()
        actionHandler.obtainMessage(MSG_SEEK, positionMs).sendToTarget()
        // allowClockBackwardTo 必须与 stepTime 同线程（RenderThread），避免跨线程重置/写入交错。
        renderHandler.post {
            engine.allowClockBackwardTo(positionMs.coerceAtLeast(0L))
            startRenderLoopIfNeeded()
        }
    }

    override fun updateDanmakuTimeFactor(factor: Float) {
        timer.softSyncFactor = factor.toDouble()
    }

    override fun currentDanmakuPositionMs(): Long = engine.currentPositionMs()

    override fun syncDanmakuTimerTo(positionMs: Long) {
        // timer.step 在 RenderThread 推进；syncTo 与其同线程，避免并发覆盖。
        renderHandler.post { timer.syncTo(positionMs) }
    }

    override fun setDebugEnabled(enabled: Boolean) {
        if (manualDebugOverlay == enabled) return
        manualDebugOverlay = enabled
        if (enabled) {
            // 立即采集一帧并刷新 View 层诊断面板。
            requestRenderFrame()
            postInvalidateOnAnimation()
        }
    }

    override fun invalidate() {
        super.invalidate()
        requestRenderFrame()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!debugOverlayEnabled) return
        recordUiFrame()
        val snap = diagSnapshot ?: return
        drawDiagnosticOverlay(canvas, snap)
    }

    /** UI FPS：主线程 onDraw 的调用间隔（Debug 时每帧刷新 onDraw，反映主线程 UI 绘制频率）。 */
    private fun recordUiFrame() {
        val now = SystemClock.uptimeMillis()
        if (uiFrameWindowStartMs == 0L) uiFrameWindowStartMs = now
        uiFrameCount++
        val elapsed = now - uiFrameWindowStartMs
        if (elapsed >= DIAG_UPDATE_INTERVAL_MS) {
            uiFps = uiFrameCount * 1000f / elapsed
            uiFrameCount = 0L
            uiFrameWindowStartMs = now
        }
    }

    /** Debug Overlay：左上角半透明面板，绘制渲染统计（方案 §70）。 */
    private fun drawDiagnosticOverlay(canvas: Canvas, snap: GlDiagnosticSnapshot) {
        val lineH = dp(18f).toFloat()
        val padding = dp(8f).toFloat()
        val panelW = dp(310f).toFloat()
        val panelH = lineH * DIAG_LINE_COUNT + padding * 2f
        canvas.drawRect(0f, 0f, panelW, panelH, diagBgPaint)
        val sb = StringBuilder(96)
        var y = padding + lineH
        fun line(block: StringBuilder.() -> Unit) {
            sb.clear()
            sb.block()
            canvas.drawText(sb, 0, sb.length, padding, y, diagTextPaint)
            y += lineH
        }
        line { append("DANMAKU GPU  ").append(if (snap.eglReady) "GL3" else "off") }
        line { append("UI fps=").append(fmt1(snap.uiFps)).append("  video=").append(fmt1(snap.videoFps)) }
        line { append("danmaku fps=").append(fmt1(snap.fps)).append("  avg=").append(fmt2(snap.frameAvgMs)).append("ms") }
        line { append("P50=").append(fmt2(snap.frameP50Ms)).append(" P95=").append(fmt2(snap.frameP95Ms)).append("ms") }
        line { append("P99=").append(fmt2(snap.frameP99Ms)).append("ms  active=").append(snap.active) }
        line { append("snapshot=").append(snap.snapshotCount).append("  atlas=").append(snap.atlasPages).append("p") }
        line { append("drawCalls=").append(snap.drawCalls).append("  v=").append(fmt2(snap.frameP95Ms)).append("p95") }
    }

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    private fun release() {
        if (released) return
        released = true
        renderHandler.post {
            renderLoopRunning = false
            runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        }
        runCatching {
            actionHandler.obtainMessage(MSG_RELEASE).sendToTarget()
            renderHandler.obtainMessage(MSG_RELEASE_EGL).sendToTarget()
        }
    }

    private fun requestRenderFrame() {
        if (released) return
        renderHandler.post { startRenderLoopIfNeeded() }
    }

    private fun startRenderLoopIfNeeded() {
        if (released || !eglReady || renderLoopRunning) return
        renderLoopRunning = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun defaultConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = true,
            opacity = BiliDanmakuStyle.DEFAULT_ALPHA_FACTOR,
            textSizeSp = 18f,
            fontWeight = DanmakuFontWeight.Bold,
            strokeWidthPx = BiliDanmakuStyle.strokeWidthForCache(
                textSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    18f,
                    resources.displayMetrics,
                ),
                fontBorder = DANMAKU_FONT_BORDER_DEFAULT,
            ),
            speedLevel = 4,
            area = 1f,
            laneDensity = DanmakuLaneDensity.Standard,
        )

    private fun scrollX(width: Int, nowMs: Int, startTimeMs: Int, pxPerMs: Float): Float {
        val elapsed = (nowMs - startTimeMs).coerceAtLeast(0)
        return width.toFloat() - elapsed * pxPerMs
    }

    private fun buildStyleKey(cfg: DanmakuConfig): String =
        buildString(32) {
            append(cfg.textSizeSp)
            append('|')
            append(cfg.strokeWidthPx)
            append('|')
            append(cfg.fontWeight)
            append('|')
            append(cfg.trackSpacing)
        }

    /**
     * Debug Overlay 采集：帧耗时写入 RingBuffer，每 [DIAG_UPDATE_INTERVAL_MS] 计算一次
     * fps / avg / P50 / P95 / P99 快照并刷新 View 层。排序仅每 500ms 一次（600 元素），可接受。
     */
    private fun updateDiagnosticRing(frameNs: Long, activeCount: Int, atlasPages: Int) {
        // Debug Overlay 跟随日志开关：开日志自动显示诊断面板（现场排查），关日志不采样零开销。
        val overlay = AppLog.isEnabled || manualDebugOverlay
        if (debugOverlayEnabled != overlay) {
            debugOverlayEnabled = overlay
            if (overlay) postInvalidateOnAnimation()
        }
        if (!overlay) return
        // Debug 时每帧刷新 View 层：onDraw 被 vsync 驱动，既统计 UI FPS 也实时更新面板。
        postInvalidateOnAnimation()
        frameTimeRingNs[frameTimeRingHead] = frameNs
        frameTimeRingHead = (frameTimeRingHead + 1) % DIAG_RING_CAPACITY
        if (frameTimeRingCount < DIAG_RING_CAPACITY) frameTimeRingCount++
        val nowUptime = SystemClock.uptimeMillis()
        if (nowUptime - lastDiagUpdateUptimeMs < DIAG_UPDATE_INTERVAL_MS) return
        val totalCount = frameTimeRingCount
        val deltaMs = nowUptime - lastDiagUpdateUptimeMs
        val deltaCount = totalCount - lastDiagFrameCount
        lastDiagUpdateUptimeMs = nowUptime
        lastDiagFrameCount = totalCount
        val count = totalCount
        val copy = frameTimeRingNs.copyOf(count)
        copy.sort()
        diagSnapshot =
            GlDiagnosticSnapshot(
                // fps 用窗口增量（非 RingBuffer 累计帧数），否则会随运行时间持续虚高。
                fps = if (deltaMs > 0L) deltaCount.toFloat() * 1000f / deltaMs else 0f,
                uiFps = uiFps,
                videoFps = videoFpsProvider?.invoke() ?: 0f,
                frameAvgMs = (copy.sum().toDouble() / count / 1_000_000.0).toFloat(),
                frameP50Ms = percentileMs(copy, 0.50),
                frameP95Ms = percentileMs(copy, 0.95),
                frameP99Ms = percentileMs(copy, 0.99),
                active = activeCount,
                snapshotCount = activeCount,
                atlasPages = atlasPages,
                drawCalls = atlasPages,
                eglReady = eglReady,
            )
    }

    private fun percentileMs(sorted: LongArray, q: Double): Float {
        if (sorted.isEmpty()) return 0f
        val idx = (sorted.size * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx] / 1_000_000.0f
    }

    /**
     * 防挡蒙版：检测 maskHost 发布的 path 快照版本变化，变化时栅格化为全屏 ALPHA_8 纹理
     * 并上传（RenderThread 独占；离屏 Canvas 栅格化不依赖 GL context）。
     * mask path 语义：path 内部 = 弹幕可显示区（白），外部 = 遮挡区（隐藏弹幕）。
     */
    private fun updateMaskIfNeeded() {
        val host = maskHostProvider?.invoke() ?: return
        val version = host.surfaceMaskVersion
        if (version == lastMaskVersion) return
        lastMaskVersion = version
        if (host.readSurfaceMask(maskPath)) {
            rasterizeAndUploadMask()
            renderer?.setMask(maskTextureId)
        } else {
            renderer?.setMask(0)
        }
    }

    private fun rasterizeAndUploadMask() {
        val w = surfaceWidth.coerceAtLeast(1)
        val h = surfaceHeight.coerceAtLeast(1)
        if (maskBitmap.width != w || maskBitmap.height != h) {
            maskBitmap.recycle()
            maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        }
        maskBitmap.eraseColor(0x00000000)
        maskCanvas.setBitmap(maskBitmap)
        maskCanvas.drawPath(maskPath, maskPaint)
        if (maskTextureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            maskTextureId = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, maskBitmap, 0)
    }

    // ========== RenderThread ==========

    private inner class RenderHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_INIT_EGL -> {
                    val surface = msg.obj as? Surface ?: return
                    initEgl(surface)
                }
                MSG_RELEASE_EGL -> releaseEgl()
                MSG_VIEWPORT -> {
                    val w = msg.arg1
                    val h = msg.arg2
                    renderer?.setViewport(w, h)
                    actionHandler.obtainMessage(MSG_VIEWPORT, w, h).sendToTarget()
                }
                MSG_FRAME -> doRenderFrame()
            }
        }
    }

    private fun initEgl(surface: Surface) {
        if (eglReady) return
        try {
            val e = GlEglHelper()
            if (!e.init(surface)) {
                if (AppLog.isEnabled) {
                    AppLog.w(TAG, "EGL init failed → GPU 引擎不可用，等待上层回退 Canvas")
                }
                e.release()
                onEglFailed?.invoke()
                return
            }
            val r = GlInstancedRenderer()
            r.init()
            val a = GlTextureAtlas()
            r.setViewport(surfaceWidth, surfaceHeight)
            egl = e
            renderer = r
            atlas = a
            eglReady = true
            if (AppLog.isEnabled) {
                AppLog.i(TAG, "EGL ready ${surfaceWidth}x${surfaceHeight} GLES=${GLES30.glGetString(GLES30.GL_VERSION)}")
            }
            startRenderLoopIfNeeded()
        } catch (t: Throwable) {
            if (AppLog.isEnabled) {
                AppLog.w(TAG, "GL init failed", t)
            }
            releaseEgl()
            onEglFailed?.invoke()
        }
    }

    private fun releaseEgl() {
        eglReady = false
        renderLoopRunning = false
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        if (maskTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0)
            maskTextureId = 0
        }
        lastMaskVersion = -1
        atlas?.release()
        atlas = null
        renderer?.release()
        renderer = null
        egl?.release()
        egl = null
    }

    private inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (released || !eglReady) {
                renderLoopRunning = false
                return
            }
            // Pre-arm 下一帧回调：在干活之前注册，避免本回调处理靠近帧边界时错过下一 vsync
            // （对齐 danmaku-smoothness 分支的 pre-arm 优化）。
            Choreographer.getInstance().postFrameCallback(this)
            try {
                doRenderFrame()
            } catch (t: Throwable) {
                if (AppLog.isEnabled) {
                    AppLog.w(TAG, "render frame failed", t)
                }
            }
            // doRenderFrame 可能停止循环（暂停/弹幕关闭/释放）：若已停止则取消 pre-arm 的回调。
            if (!renderLoopRunning) {
                Choreographer.getInstance().removeFrameCallback(this)
            }
        }
    }

    /**
     * 渲染单帧：时钟推进 → 快照消费 → Atlas 上传 → 实例构建 → Instanced 绘制 → 交换缓冲。
     * RenderThread 独占，禁止对象分配于热路径（预分配缓冲 + 原语遍历）。
     */
    private fun doRenderFrame() {
        val perfStartNs = System.nanoTime()
        val cfg = configProvider?.invoke() ?: defaultConfig()
        if (cfg != lastConfig) {
            lastConfig = cfg
            actionHandler.obtainMessage(MSG_CONFIG, cfg).sendToTarget()
            // 字号/描边/字重变化 → 纹理内容失效，清空 Atlas 强制重建。
            val styleKey = buildStyleKey(cfg)
            if (styleKey != lastStyleKey) {
                lastStyleKey = styleKey
                atlas?.clear()
            }
        }

        val rawPos = positionProvider?.invoke()?.coerceAtLeast(0L) ?: 0L
        val isPlaying = runCatching { isPlayingProvider?.invoke() }.getOrNull() ?: false
        val playWhenReady = runCatching { playWhenReadyProvider?.invoke() }.getOrNull() ?: false
        val speed = runCatching { playbackSpeedProvider?.invoke() }.getOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 1f

        val frameId = uiFrameId.incrementAndGet()
        val smoothPos = timer.step(System.nanoTime(), rawPos, isPlaying, speed, seekSerial.get())
        engine.stepTime(smoothPos, frameId)
        cacheManager.drainReleasedBitmaps(frameId)

        // Action 线程执行调度（下一帧生效；去重避免积压）。
        actionHandler.removeMessages(MSG_ACT)
        actionHandler.sendEmptyMessage(MSG_ACT)

        val r = renderer
        val a = atlas
        val e = egl
        if (r == null || a == null || e == null) return
        val vw = surfaceWidth.coerceAtLeast(1)

        if (!cfg.enabled) {
            // 弹幕关闭：清屏一帧后停止渲染循环。
            r.beginFrame()
            e.swapBuffers()
            renderLoopRunning = false
            return
        }

        r.beginFrame()
        a.onFrameBegin()
        updateMaskIfNeeded()

        val snapshot = engine.acquireRenderSnapshot()
        try {
            val nowMs = smoothPos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val alpha = (cfg.opacity * 255f).roundToInt().coerceIn(0, 255) / 255f
            for (i in 0 until snapshot.count) {
                val item = snapshot.items[i] ?: continue
                val entry = snapshot.cacheEntries[i] ?: continue
                if (entry.isRecycled) continue
                val slot = a.obtain(entry) ?: continue
                val w = slot.width.toFloat()
                val h = slot.height.toFloat()
                val x =
                    when (item.kind) {
                        DanmakuKind.SCROLL -> scrollX(vw, nowMs, item.startTimeMs, item.pxPerMs)
                        DanmakuKind.TOP, DanmakuKind.BOTTOM -> ((vw - w) * 0.5f).coerceAtLeast(0f)
                    }
                // 完全滚出屏幕的弹幕跳过（不写实例、不浪费 draw）。
                if (x + w < 0f || x > vw) continue
                val y = snapshot.yTop[i]
                // 实例 rect.xy 是弹幕中心（shader 中 pos = rect.xy + quadPos×size，quadPos∈[-0.5,0.5]）。
                r.writeInstance(slot.pageId, x + w * 0.5f, y + h * 0.5f, w, h, slot.u0, slot.v0, slot.u1, slot.v1, alpha)
            }
            a.fillPageTextures(pageTextures)
            r.drawPages(pageTextures)
        } finally {
            engine.releaseRenderSnapshot(snapshot)
        }

        e.swapBuffers()

        // 性能采样：汇总帧耗时/负载，周期输出（对齐方案 §69 性能监控）。
        perfFrameCount++
        val frameNs = (System.nanoTime() - perfStartNs).coerceAtLeast(0L)
        perfAccumFrameNs += frameNs
        if (frameNs > perfMaxFrameNs) perfMaxFrameNs = frameNs
        // Debug Overlay：帧耗时 RingBuffer + 分位数（RenderThread 采集，onDraw 主线程消费）。
        updateDiagnosticRing(frameNs, r.lastDrawnInstanceCount, a.pageCount())
        val nowUptimeMs = android.os.SystemClock.uptimeMillis()
        if (nowUptimeMs - perfLastLogUptimeMs >= PERF_LOG_INTERVAL_MS) {
            perfLastLogUptimeMs = nowUptimeMs
            if (AppLog.isEnabled) {
                val count = perfFrameCount.coerceAtLeast(1)
                val avgMs = perfAccumFrameNs.toDouble() / count / 1_000_000.0
                AppLog.i(
                    TAG,
                    "perf fps=${String.format(Locale.US, "%.1f", count.toFloat() * 1000f / PERF_LOG_INTERVAL_MS)} " +
                        "avg=${String.format(Locale.US, "%.2f", avgMs)}ms " +
                        "max=${String.format(Locale.US, "%.2f", perfMaxFrameNs / 1_000_000.0)}ms " +
                        "active=${r.lastDrawnInstanceCount} snapshot=${snapshot.count} atlasPages=${a.pageCount()}"
                )
            }
            perfAccumFrameNs = 0L
            perfMaxFrameNs = 0L
            perfFrameCount = 0
        }

        // 暂停/缓冲：冻结画面，停止连续渲染以省电。
        if (!isPlaying && !playWhenReady) {
            renderLoopRunning = false
        }
    }

    // ========== ActionThread ==========

    private inner class ActionHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ACT -> {
                    if (released) return
                    runCatching { engine.act() }
                }
                MSG_SET -> {
                    @Suppress("UNCHECKED_CAST")
                    engine.setDanmakus(msg.obj as? List<Danmaku> ?: emptyList())
                }
                MSG_APPEND -> {
                    val p = msg.obj as? AppendPayload ?: return
                    engine.appendDanmakus(p.list, alreadySorted = p.alreadySorted)
                    if (p.maxItems > 0) engine.trimToMax(p.maxItems)
                }
                MSG_REPLACE_FROM -> {
                    val p = msg.obj as? ReplaceFromPayload ?: return
                    engine.replaceDanmakusFrom(p.minTimeMs, p.list)
                }
                MSG_TRIM_RANGE -> {
                    val p = msg.obj as? TrimRangePayload ?: return
                    engine.trimToTimeRange(p.minTimeMs, p.maxTimeMs)
                }
                MSG_SEEK -> {
                    engine.seekTo((msg.obj as? Long) ?: 0L)
                    requestRenderFrame()
                }
                MSG_CLEAR -> engine.clear()
                MSG_VIEWPORT -> {
                    val w = msg.arg1.coerceAtLeast(0)
                    val h = msg.arg2.coerceAtLeast(0)
                    engine.updateViewport(w, h, viewportTopInsetPx, viewportBottomInsetPx)
                }
                MSG_CONFIG -> {
                    (msg.obj as? DanmakuConfig)?.let { engine.updateConfig(it) }
                }
                MSG_CACHE_RESULT -> {
                    (msg.obj as? CacheBuildResult)?.let { engine.applyCacheResult(it) }
                }
                MSG_RELEASE -> {
                    removeCallbacksAndMessages(null)
                    runCatching { actionThread.quitSafely() }
                    engine.release()
                    cacheManager.release()
                }
            }
        }
    }

    private data class AppendPayload(
        val list: List<Danmaku>,
        val maxItems: Int,
        val alreadySorted: Boolean,
    )

    private data class ReplaceFromPayload(
        val minTimeMs: Long,
        val list: List<Danmaku>,
    )

    private data class TrimRangePayload(
        val minTimeMs: Long,
        val maxTimeMs: Long,
    )
}

/** GPU 引擎 Debug Overlay 数据快照（RenderThread 采集，主线程 onDraw 消费）。 */
internal data class GlDiagnosticSnapshot(
    val fps: Float,
    val uiFps: Float,
    val videoFps: Float,
    val frameAvgMs: Float,
    val frameP50Ms: Float,
    val frameP95Ms: Float,
    val frameP99Ms: Float,
    val active: Int,
    val snapshotCount: Int,
    val atlasPages: Int,
    val drawCalls: Int,
    val eglReady: Boolean,
)
