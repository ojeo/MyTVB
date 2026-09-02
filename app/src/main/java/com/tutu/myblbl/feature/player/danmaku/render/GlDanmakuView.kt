package com.tutu.myblbl.feature.player.danmaku.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
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
import android.view.View.MeasureSpec
import com.tutu.myblbl.BuildConfig
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
        private const val PROVIDER_SAMPLE_INTERVAL_MS = 100L
        private const val TARGET_FRAME_NS = 16_666_667L

        // ---- Debug Overlay ----
        private const val DIAG_RING_CAPACITY = 600
        private const val DIAG_UPDATE_INTERVAL_MS = 500L
        private const val DIAG_BG_COLOR = 0xCC000000.toInt()
        private const val DIAG_LINE_COUNT = 8
        private const val MASK_SAMPLE_COLUMNS = 18
        private const val MASK_SAMPLE_ROWS = 10
        private const val MASK_WARNING_INTERVAL_MS = 3_000L
    }

    // ---- providers（由 BlblDanmakuController 注入）----
    private var positionProvider: (() -> Long)? = null
    private var isPlayingProvider: (() -> Boolean)? = null
    private var playWhenReadyProvider: (() -> Boolean)? = null
    private var playbackSpeedProvider: (() -> Float)? = null
    private var configProvider: (() -> DanmakuConfig)? = null

    /**
     * Media3 providers are main-thread confined. The GL thread consumes this immutable sample
     * and extrapolates position from elapsedRealtime, so a busy UI thread cannot quantize GL
     * motion to the main window's lower frame rate.
     */
    private data class ProviderSnapshot(
        val positionMs: Long,
        val sampledAtRealtimeNs: Long,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val speed: Float,
        val config: DanmakuConfig,
    )

    @Volatile
    private var providerSnapshot =
        ProviderSnapshot(0L, SystemClock.elapsedRealtimeNanos(), false, false, 1f, defaultConfig())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var providerSamplerRunning = false
    private val providerSampler =
        object : Runnable {
            override fun run() {
                if (!providerSamplerRunning || released || !isAttachedToWindow) return
                sampleProvidersOnMain()
                mainHandler.postDelayed(this, PROVIDER_SAMPLE_INTERVAL_MS)
            }
        }

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
    @Volatile private var logicalViewportWidth = 0
    @Volatile private var logicalViewportHeight = 0
    @Volatile private var renderViewportWidth = 0
    @Volatile private var renderViewportHeight = 0
    private var fixedBufferWidth = 0
    private var fixedBufferHeight = 0
    @Volatile private var surfaceAvailable = false
    // 两个 SurfaceView 必须保持相同的全屏 View 几何，避免 SurfaceFlinger 在添加/更新
    // 顶部 band 时短暂裁切视频 Surface。内部 buffer 仍可只保留内容 band 的像素量；
    // GL 投影使用完整视口，SurfaceFlinger 拉伸后字体与运动坐标仍保持正确比例。
    private val renderScale = 0.5f
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
    private var perfAccumSwapNs = 0L
    private var perfMaxSwapNs = 0L
    @Volatile private var lastSwapMs = 0f

    // ---- Debug Overlay（方案 §70；RenderThread 采集 RingBuffer，onDraw 主线程绘制）----
    // Debug APKs are validation builds: keep the requested on-screen diagnostics visible even
    // when persistent file logging is off. Release builds still default to zero-overhead off.
    @Volatile private var debugOverlayEnabled = BuildConfig.DEBUG
    @Volatile private var manualDebugOverlay = BuildConfig.DEBUG
    private val frameTimeRingNs = LongArray(DIAG_RING_CAPACITY)
    private var frameTimeRingHead = 0
    private var frameTimeRingCount = 0
    private var diagnosticFrameTotal = 0L
    private var lastDiagFrameTotal = 0L
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
    private var uiFrameMonitorRunning = false
    private val uiFrameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!uiFrameMonitorRunning || released || !isAttachedToWindow) return
                Choreographer.getInstance().postFrameCallback(this)
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
        }

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
    private val maskBounds = RectF()
    private var maskApplied = false
    private var maskFailOpen = false
    private var maskCoveragePercent = 100
    private var lastMaskWarningUptimeMs = 0L

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

    override fun setRenderVisible(visible: Boolean) {
        super.setVisibility(if (visible) View.VISIBLE else View.GONE)
        if (visible) requestRenderFrame()
    }

    // ========== SurfaceHolder.Callback ==========

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        startProviderSampler()
        updateUiFrameMonitor(manualDebugOverlay || AppLog.isEnabled)
        renderHandler.obtainMessage(MSG_INIT_EGL, holder.surface).sendToTarget()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        renderHandler.obtainMessage(MSG_VIEWPORT, width.coerceAtLeast(0), height.coerceAtLeast(0)).sendToTarget()
        // 视口变化后 mask 纹理需按新尺寸重建（强制下帧重新栅格化）。
        lastMaskVersion = -1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        val fullHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
        logicalViewportWidth = fullWidth
        logicalViewportHeight = fullHeight
        if (fullWidth <= 0 || fullHeight <= 0) {
            setMeasuredDimension(fullWidth, fullHeight)
            return
        }

        val cfg = runCatching { configProvider?.invoke() }.getOrNull() ?: providerSnapshot.config
        val safeTop = viewportTopInsetPx.coerceIn(0, fullHeight)
        val safeBottom = viewportBottomInsetPx.coerceIn(0, fullHeight - safeTop)
        val availableHeight = (fullHeight - safeTop - safeBottom).coerceAtLeast(0)
        val contentBottom = safeTop + (availableHeight * cfg.area.coerceIn(0f, 1f)).roundToInt()
        val diagnosticBottom = if (debugOverlayEnabled) dp(150f) else 0
        val bufferBandHeight = maxOf(contentBottom + dp(4f), diagnosticBottom, 1).coerceAtMost(fullHeight)
        // View/SurfaceControl 的目标区域始终全屏；只有 producer buffer 使用内容 band 的
        // 像素预算。renderViewportHeight 必须保持 fullHeight，才能抵消最终的纵向缩放。
        val surfaceViewHeight = fullHeight
        renderViewportWidth = fullWidth
        renderViewportHeight = fullHeight
        setMeasuredDimension(fullWidth, surfaceViewHeight)
        val targetBufferWidth = (fullWidth * renderScale).roundToInt().coerceAtLeast(1)
        val targetBufferHeight = (bufferBandHeight * renderScale).roundToInt().coerceAtLeast(1)
        if (targetBufferWidth != fixedBufferWidth || targetBufferHeight != fixedBufferHeight) {
            fixedBufferWidth = targetBufferWidth
            fixedBufferHeight = targetBufferHeight
            holder.setFixedSize(targetBufferWidth, targetBufferHeight)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Published before the RenderThread receives MSG_RELEASE_EGL. An in-flight frame may
        // observe eglSwapBuffers failure after SurfaceFlinger disconnects the producer; that is
        // normal lifecycle teardown, not a renderer failure that should trigger Canvas fallback.
        surfaceAvailable = false
        surfaceWidth = 0
        surfaceHeight = 0
        renderHandler.sendEmptyMessage(MSG_RELEASE_EGL)
    }

    // ========== DanmakuRenderHost ==========

    override fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
        sampleProvidersSoon()
    }

    override fun setIsPlayingProvider(provider: () -> Boolean) {
        isPlayingProvider = provider
        sampleProvidersSoon()
    }

    override fun setPlayWhenReadyProvider(provider: () -> Boolean) {
        playWhenReadyProvider = provider
        sampleProvidersSoon()
    }

    override fun setPlaybackSpeedProvider(provider: () -> Float) {
        playbackSpeedProvider = provider
        sampleProvidersSoon()
    }

    override fun setConfigProvider(provider: () -> DanmakuConfig) {
        configProvider = provider
        sampleProvidersSoon()
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
        val old = providerSnapshot
        providerSnapshot = old.copy(
            positionMs = positionMs.coerceAtLeast(0L),
            sampledAtRealtimeNs = SystemClock.elapsedRealtimeNanos(),
        )
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
        updateUiFrameMonitor(enabled || AppLog.isEnabled)
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
        val snap = diagSnapshot ?: return
        drawDiagnosticOverlay(canvas, snap)
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
        line {
            append("DANMAKU [SURFACE-").append(if (snap.eglReady) "GL3" else "off").append(' ')
                .append(surfaceWidth).append('x').append(surfaceHeight).append(']')
        }
        line { append("UI fps=").append(fmt1(snap.uiFps)).append("  video=").append(fmt1(snap.videoFps)) }
        line { append("danmaku fps=").append(fmt1(snap.fps)).append("  avg=").append(fmt2(snap.frameAvgMs)).append("ms") }
        line { append("P50=").append(fmt2(snap.frameP50Ms)).append(" P95=").append(fmt2(snap.frameP95Ms)).append("ms") }
        line { append("P99=").append(fmt2(snap.frameP99Ms)).append("ms  active=").append(snap.active) }
        line { append("snapshot=").append(snap.snapshotCount).append("  atlas=").append(snap.atlasPages).append("p") }
        line {
            append("mask=")
                .append(if (snap.maskFailOpen) "bypass" else if (snap.maskApplied) "on" else "off")
                .append("  visible=").append(snap.maskCoveragePercent).append('%')
        }
        line { append("drawCalls=").append(snap.drawCalls).append("  swap=").append(fmt2(snap.swapMs)).append("ms") }
    }

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Float): String = String.format(Locale.US, "%.2f", v)

    override fun onDetachedFromWindow() {
        updateUiFrameMonitor(false)
        stopProviderSampler()
        super.onDetachedFromWindow()
        release()
    }

    private fun release() {
        if (released) return
        released = true
        stopProviderSampler()
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

    private fun startProviderSampler() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startProviderSampler() }
            return
        }
        if (providerSamplerRunning || released || !isAttachedToWindow) return
        providerSamplerRunning = true
        mainHandler.removeCallbacks(providerSampler)
        sampleProvidersOnMain()
        mainHandler.postDelayed(providerSampler, PROVIDER_SAMPLE_INTERVAL_MS)
    }

    private fun stopProviderSampler() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopProviderSampler() }
            return
        }
        providerSamplerRunning = false
        mainHandler.removeCallbacks(providerSampler)
    }

    private fun sampleProvidersSoon() {
        mainHandler.post {
            if (!released) sampleProvidersOnMain()
        }
    }

    private fun sampleProvidersOnMain() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val previous = providerSnapshot
        val speed =
            runCatching { playbackSpeedProvider?.invoke() }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?: previous.speed
        val next =
            ProviderSnapshot(
                positionMs = runCatching { positionProvider?.invoke() }.getOrNull()?.coerceAtLeast(0L)
                    ?: previous.positionMs,
                sampledAtRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                isPlaying = runCatching { isPlayingProvider?.invoke() }.getOrNull() ?: previous.isPlaying,
                playWhenReady = runCatching { playWhenReadyProvider?.invoke() }.getOrNull()
                    ?: previous.playWhenReady,
                speed = speed,
                config = runCatching { configProvider?.invoke() }.getOrNull() ?: previous.config,
            )
        providerSnapshot = next
        if (next.config.area != previous.config.area) requestLayout()
        if (providerSnapshot.isPlaying || providerSnapshot.playWhenReady) requestRenderFrame()
    }

    private fun updateUiFrameMonitor(enabled: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateUiFrameMonitor(enabled) }
            return
        }
        if (enabled == uiFrameMonitorRunning) return
        uiFrameMonitorRunning = enabled
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(uiFrameCallback)
        uiFrameCount = 0L
        uiFrameWindowStartMs = 0L
        if (enabled && isAttachedToWindow && !released) {
            choreographer.postFrameCallback(uiFrameCallback)
        }
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
    private fun updateDiagnosticRing(
        frameNs: Long,
        activeCount: Int,
        snapshotCount: Int,
        atlasPages: Int,
        drawCalls: Int,
    ) {
        // Debug Overlay 跟随日志开关：开日志自动显示诊断面板（现场排查），关日志不采样零开销。
        val overlay = AppLog.isEnabled || manualDebugOverlay
        if (debugOverlayEnabled != overlay) {
            debugOverlayEnabled = overlay
            updateUiFrameMonitor(overlay)
            postInvalidateOnAnimation()
        }
        if (!overlay) return
        frameTimeRingNs[frameTimeRingHead] = frameNs
        frameTimeRingHead = (frameTimeRingHead + 1) % DIAG_RING_CAPACITY
        if (frameTimeRingCount < DIAG_RING_CAPACITY) frameTimeRingCount++
        diagnosticFrameTotal++
        val nowUptime = SystemClock.uptimeMillis()
        if (nowUptime - lastDiagUpdateUptimeMs < DIAG_UPDATE_INTERVAL_MS) return
        val deltaMs = nowUptime - lastDiagUpdateUptimeMs
        val deltaCount = diagnosticFrameTotal - lastDiagFrameTotal
        lastDiagUpdateUptimeMs = nowUptime
        lastDiagFrameTotal = diagnosticFrameTotal
        val count = frameTimeRingCount
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
                snapshotCount = snapshotCount,
                atlasPages = atlasPages,
                drawCalls = drawCalls,
                swapMs = lastSwapMs,
                eglReady = eglReady,
                maskApplied = maskApplied,
                maskFailOpen = maskFailOpen,
                maskCoveragePercent = maskCoveragePercent,
            )
        // The diagnostics View layer is intentionally refreshed only at 2Hz. Invalidating it at
        // GL frame rate would put the full player window back on the HWUI critical path.
        postInvalidateOnAnimation()
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
        val host = maskHostProvider?.invoke()
        if (host == null) {
            renderer?.setMask(0)
            maskApplied = false
            maskFailOpen = false
            maskCoveragePercent = 100
            lastMaskVersion = -1
            return
        }
        val version = host.surfaceMaskVersion
        if (version == lastMaskVersion) return
        lastMaskVersion = version
        if (host.readSurfaceMask(maskPath)) {
            val coverage = runCatching { rasterizeAndUploadMask() }
                .onFailure { logMaskFailOpen("rasterize_failed:${it.javaClass.simpleName}") }
                .getOrNull()
            if (coverage != null) {
                renderer?.setMask(maskTextureId)
                maskApplied = true
                maskFailOpen = false
                maskCoveragePercent = (coverage * 100f).roundToInt().coerceIn(1, 100)
            } else {
                // 蒙版数据异常时优先保证弹幕可见，下一次有效版本到来后自动恢复裁剪。
                renderer?.setMask(0)
                maskApplied = false
                maskFailOpen = true
                maskCoveragePercent = 0
                logMaskFailOpen("empty_or_outside_viewport")
            }
        } else {
            renderer?.setMask(0)
            maskApplied = false
            maskFailOpen = false
            maskCoveragePercent = 100
        }
    }

    /** 返回蒙版可见区采样比例；无有效可见区时返回 null，调用方执行 fail-open。 */
    private fun rasterizeAndUploadMask(): Float? {
        val w = surfaceWidth
        val h = surfaceHeight
        val viewportW = renderViewportWidth
        val viewportH = renderViewportHeight
        if (w <= 0 || h <= 0 || viewportW <= 0 || viewportH <= 0 || maskPath.isEmpty) return null
        maskPath.computeBounds(maskBounds, true)
        if (!maskBounds.left.isFinite() || !maskBounds.top.isFinite() ||
            !maskBounds.right.isFinite() || !maskBounds.bottom.isFinite()
        ) return null
        val intersectionWidth =
            minOf(maskBounds.right, viewportW.toFloat()) - maxOf(maskBounds.left, 0f)
        val intersectionHeight =
            minOf(maskBounds.bottom, viewportH.toFloat()) - maxOf(maskBounds.top, 0f)
        if (intersectionWidth <= 0f || intersectionHeight <= 0f) return null
        if (maskBitmap.width != w || maskBitmap.height != h) {
            maskBitmap.recycle()
            maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        }
        maskBitmap.eraseColor(0x00000000)
        maskCanvas.setBitmap(maskBitmap)
        val saveCount = maskCanvas.save()
        maskCanvas.scale(
            w.toFloat() / renderViewportWidth.coerceAtLeast(1),
            h.toFloat() / renderViewportHeight.coerceAtLeast(1),
        )
        maskCanvas.drawPath(maskPath, maskPaint)
        maskCanvas.restoreToCount(saveCount)
        var visibleSamples = 0
        val totalSamples = MASK_SAMPLE_COLUMNS * MASK_SAMPLE_ROWS
        for (row in 0 until MASK_SAMPLE_ROWS) {
            val y = (((row + 0.5f) * h) / MASK_SAMPLE_ROWS).toInt().coerceIn(0, h - 1)
            for (column in 0 until MASK_SAMPLE_COLUMNS) {
                val x = (((column + 0.5f) * w) / MASK_SAMPLE_COLUMNS).toInt().coerceIn(0, w - 1)
                if (Color.alpha(maskBitmap.getPixel(x, y)) > 0) visibleSamples++
            }
        }
        if (visibleSamples == 0) return null
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
        return visibleSamples.toFloat() / totalSamples
    }

    private fun logMaskFailOpen(reason: String) {
        if (!AppLog.isEnabled) return
        val now = SystemClock.uptimeMillis()
        if (now - lastMaskWarningUptimeMs < MASK_WARNING_INTERVAL_MS) return
        lastMaskWarningUptimeMs = now
        AppLog.w(
            TAG,
            "mask fail-open reason=$reason surface=${surfaceWidth}x$surfaceHeight " +
                "viewport=${renderViewportWidth}x$renderViewportHeight bounds=$maskBounds",
        )
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
                    renderer?.setViewport(
                        surfaceWidth = w,
                        surfaceHeight = h,
                        logicalWidth = renderViewportWidth.coerceAtLeast(w),
                        logicalHeight = renderViewportHeight.coerceAtLeast(h),
                    )
                    actionHandler.obtainMessage(
                        MSG_VIEWPORT,
                        logicalViewportWidth.coerceAtLeast(w),
                        logicalViewportHeight.coerceAtLeast(h),
                    ).sendToTarget()
                }
                MSG_FRAME -> renderAndSchedule(SystemClock.elapsedRealtimeNanos())
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
            r.setViewport(
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                logicalWidth = renderViewportWidth.coerceAtLeast(surfaceWidth),
                logicalHeight = renderViewportHeight.coerceAtLeast(surfaceHeight),
            )
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
            renderAndSchedule(frameTimeNanos)
        }
    }

    private fun renderAndSchedule(frameTimeNanos: Long) {
        val renderStartedNs = System.nanoTime()
        if (released || !eglReady) {
            renderLoopRunning = false
            return
        }
        try {
            doRenderFrame(frameTimeNanos)
        } catch (t: Throwable) {
            if (AppLog.isEnabled) {
                AppLog.w(TAG, "render frame failed", t)
            }
        }
        if (!renderLoopRunning || released || !eglReady) return

        val renderElapsedNs = (System.nanoTime() - renderStartedNs).coerceAtLeast(0L)
        if (renderElapsedNs >= TARGET_FRAME_NS) {
            // A late frame already crossed at least one vsync boundary. Waiting for another
            // Choreographer callback adds a second idle gap (25ms render became a ~40ms frame).
            // Queue exactly one immediate frame using the latest media clock; historical frames
            // are never replayed, and Handler serialization prevents catch-up bursts.
            renderHandler.sendEmptyMessage(MSG_FRAME)
        } else {
            // Fast devices remain aligned to display vsync and never busy-spin above 60fps.
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    /**
     * 渲染单帧：时钟推进 → 快照消费 → Atlas 上传 → 实例构建 → Instanced 绘制 → 交换缓冲。
     * RenderThread 独占，禁止对象分配于热路径（预分配缓冲 + 原语遍历）。
     */
    private fun doRenderFrame(frameTimeNanos: Long) {
        val perfStartNs = System.nanoTime()
        val sampled = providerSnapshot
        val cfg = sampled.config
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

        val isPlaying = sampled.isPlaying
        val playWhenReady = sampled.playWhenReady
        val speed = sampled.speed
        val elapsedSinceSampleNs = (frameTimeNanos - sampled.sampledAtRealtimeNs).coerceAtLeast(0L)
        val rawPos =
            if (isPlaying) {
                sampled.positionMs + (elapsedSinceSampleNs.toDouble() / 1_000_000.0 * speed).toLong()
            } else {
                sampled.positionMs
            }

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
        val vw = logicalViewportWidth.coerceAtLeast(surfaceWidth).coerceAtLeast(1)

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

        e.setPresentationTime(frameTimeNanos)
        val swapStartedNs = System.nanoTime()
        val swapSucceeded = e.swapBuffers()
        val swapNs = (System.nanoTime() - swapStartedNs).coerceAtLeast(0L)
        lastSwapMs = (swapNs / 1_000_000.0).toFloat()
        perfAccumSwapNs += swapNs
        if (swapNs > perfMaxSwapNs) perfMaxSwapNs = swapNs
        if (!swapSucceeded) {
            if (!surfaceAvailable || released) {
                renderLoopRunning = false
                return
            }
            if (AppLog.isEnabled) AppLog.w(TAG, "eglSwapBuffers failed; switching to Canvas fallback")
            renderLoopRunning = false
            onEglFailed?.invoke()
            return
        }

        // 性能采样：汇总帧耗时/负载，周期输出（对齐方案 §69 性能监控）。
        perfFrameCount++
        val frameNs = (System.nanoTime() - perfStartNs).coerceAtLeast(0L)
        perfAccumFrameNs += frameNs
        if (frameNs > perfMaxFrameNs) perfMaxFrameNs = frameNs
        // Debug Overlay：帧耗时 RingBuffer + 分位数（RenderThread 采集，onDraw 主线程消费）。
        updateDiagnosticRing(
            frameNs,
            r.lastDrawnInstanceCount,
            snapshot.count,
            a.pageCount(),
            r.lastDrawCallCount,
        )
        val nowUptimeMs = android.os.SystemClock.uptimeMillis()
        if (nowUptimeMs - perfLastLogUptimeMs >= PERF_LOG_INTERVAL_MS) {
            perfLastLogUptimeMs = nowUptimeMs
            if (AppLog.isEnabled) {
                val count = perfFrameCount.coerceAtLeast(1)
                val avgMs = perfAccumFrameNs.toDouble() / count / 1_000_000.0
                val avgSwapMs = perfAccumSwapNs.toDouble() / count / 1_000_000.0
                AppLog.i(
                    TAG,
                    "perf fps=${String.format(Locale.US, "%.1f", count.toFloat() * 1000f / PERF_LOG_INTERVAL_MS)} " +
                        "avg=${String.format(Locale.US, "%.2f", avgMs)}ms " +
                        "swap=${String.format(Locale.US, "%.2f", avgSwapMs)}/${String.format(Locale.US, "%.2f", perfMaxSwapNs / 1_000_000.0)}ms " +
                        "max=${String.format(Locale.US, "%.2f", perfMaxFrameNs / 1_000_000.0)}ms " +
                        "active=${r.lastDrawnInstanceCount} snapshot=${snapshot.count} atlasPages=${a.pageCount()}"
                )
            }
            perfAccumFrameNs = 0L
            perfMaxFrameNs = 0L
            perfAccumSwapNs = 0L
            perfMaxSwapNs = 0L
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
    val swapMs: Float,
    val eglReady: Boolean,
    val maskApplied: Boolean,
    val maskFailOpen: Boolean,
    val maskCoveragePercent: Int,
)
