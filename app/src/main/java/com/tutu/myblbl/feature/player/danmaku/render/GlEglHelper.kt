package com.tutu.myblbl.feature.player.danmaku.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.view.Surface

/**
 * EGL 生命周期封装：负责 display / context / window surface 的创建与销毁。
 *
 * - 必须在 RenderThread（持有 GL context 的线程）上调用。
 * - 创建 GLES3 context（EGL_CONTEXT_CLIENT_VERSION = 3），renderable 类型兼容 ES2 bit。
 * - 销毁时严格按 EGL 规范顺序：MakeCurrent(NO_SURFACE) → DestroySurface → DestroyContext → Terminate。
 */
internal class GlEglHelper {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null

    fun isReady(): Boolean =
        display != EGL14.EGL_NO_DISPLAY &&
            context != EGL14.EGL_NO_CONTEXT &&
            surface != EGL14.EGL_NO_SURFACE

    /** 绑定 Surface 并完成全链路初始化，失败返回 false（可安全走 fallback）。 */
    fun init(surface: Surface): Boolean {
        if (!initDisplay()) return false
        if (!initContext()) return false
        if (!createWindowSurface(surface)) return false
        if (!makeCurrent()) return false
        // Choreographer already paces this producer to display vsync. Waiting for another EGL
        // swap interval can turn one slightly late frame into a 33ms cadence on double-buffered
        // TV/emulator surfaces. Interval 0 keeps swap non-vsync-blocking while Choreographer
        // remains the single frame-rate limiter.
        EGL14.eglSwapInterval(display, 0)
        return true
    }

    private fun initDisplay(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        return EGL14.eglInitialize(display, version, 0, version, 1)
    }

    private fun initContext(): Boolean {
        // GLES3 需要 renderable type 含 ES3 bit(0x40)；ES3 兼容 ES2，故 OR 上 ES2 bit 提升选配置成功率。
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x40,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
            return false
        }
        config = configs[0]
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        return context != EGL14.EGL_NO_CONTEXT
    }

    private fun createWindowSurface(surface: Surface): Boolean {
        val cfg = config ?: return false
        this.surface = EGL14.eglCreateWindowSurface(display, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        return this.surface != EGL14.EGL_NO_SURFACE
    }

    private fun makeCurrent(): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(frameTimeNanos: Long) {
        if (isReady()) {
            EGLExt.eglPresentationTimeANDROID(display, surface, frameTimeNanos)
        }
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        runCatching {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
                surface = EGL14.EGL_NO_SURFACE
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
    }
}
