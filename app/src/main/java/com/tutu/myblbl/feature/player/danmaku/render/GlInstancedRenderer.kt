package com.tutu.myblbl.feature.player.danmaku.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GLES3 Instanced 渲染器：一个公共 Quad + 每弹幕一份实例数据，一次 Draw 绘制整屏弹幕。
 *
 * 实例数据布局（stride = 12 floats = 48 字节）：
 * ```
 * vec4 rect  : x, y, width, height（像素，y 向下，View 坐标）
 * vec4 uv    : u0, v0, u1, v1（atlas UV）
 * vec4 color : r, g, b, a（当前仅用 a 作为全局透明度，颜色已烘焙进纹理）
 * ```
 *
 * 每帧：
 * - CPU 计算弹幕位置（x 由媒体时钟派生，与视频严格同步），写入预分配 DirectBuffer（零分配热路径）。
 * - glBufferSubData 上传实例段；按 atlas 页分组，每页一次 Instanced Draw Call。
 * - 顶点着色器完成 quad 缩放/坐标变换，片段着色器采样 atlas + 施加透明度。
 */
internal class GlInstancedRenderer {
    companion object {
        const val STRIDE_FLOATS = 12
        const val STRIDE_BYTES = STRIDE_FLOATS * 4
        const val MAX_INSTANCES = 800
        const val MAX_PAGES = GlTextureAtlas.ATLAS_MAX_PAGES

        const val LOC_QUAD = 0
        const val LOC_RECT = 1
        const val LOC_UV = 2
        const val LOC_COLOR = 3

        // 注意：GLSL 要求 #version 指令必须位于源码首行，Kotlin 三引号字符串会带入行首换行，
        // 必须 trimIndent()（移除首行空行与公共缩进），否则 shader 编译报
        // "#version directive must occur on the first line"。
        private val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec2 aQuadPos;
            layout(location = 1) in vec4 aInstanceRect;
            layout(location = 2) in vec4 aInstanceUv;
            layout(location = 3) in vec4 aInstanceColor;
            uniform vec2 uViewport;
            out vec2 vUv;
            out vec4 vColor;
            void main() {
                vec2 quadPos = aQuadPos * aInstanceRect.zw;
                vec2 pos = aInstanceRect.xy + quadPos;
                vec2 clip = (pos / uViewport) * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
                vUv = mix(aInstanceUv.xy, aInstanceUv.zw, aQuadPos + 0.5);
                vColor = aInstanceColor;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            uniform sampler2D uMask;
            uniform int uMaskEnabled;
            // 精度必须与顶点 shader 的 uViewport（highp）一致，否则 glLinkProgram 报
            // "Precisions for uniform uViewport do not match"。
            uniform highp vec2 uViewport;
            in vec2 vUv;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                if (uMaskEnabled != 0) {
                    vec2 maskUv = vec2(gl_FragCoord.x / uViewport.x, 1.0 - gl_FragCoord.y / uViewport.y);
                    float maskValue = texture(uMask, maskUv).a;
                    if (maskValue < 0.5) discard;
                }
                vec4 texel = texture(uTexture, vUv);
                fragColor = vec4(texel.rgb * vColor.a, texel.a * vColor.a);
            }
        """.trimIndent()
    }

    private var program: GlProgram? = null
    private var vao = 0
    private var quadVbo = 0
    private var instanceVbo = 0
    private var viewportUniform = 0
    private var textureUniform = 0
    private var maskUniform = 0
    private var maskEnabledUniform = 0
    private var viewportW = 1
    private var viewportH = 1
    private var maskTextureId = 0
    private var maskEnabled = false

    private val instanceBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(MAX_INSTANCES * STRIDE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pageCounts = IntArray(MAX_PAGES)
    private val pageOffsets = IntArray(MAX_PAGES)

    /** 上一帧实际绘制的弹幕实例数（性能采样用）。 */
    @Volatile
    var lastDrawnInstanceCount: Int = 0
        private set

    fun init() {
        val p = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        program = p
        viewportUniform = p.uniformLocation("uViewport")
        textureUniform = p.uniformLocation("uTexture")
        maskUniform = p.uniformLocation("uMask")
        maskEnabledUniform = p.uniformLocation("uMaskEnabled")

        val ids = IntArray(1)

        // 公共 Quad（TRIANGLE_STRIP，4 顶点，中心在原点）
        val quad = floatArrayOf(
            -0.5f, -0.5f,
            0.5f, -0.5f,
            -0.5f, 0.5f,
            0.5f, 0.5f,
        )
        val quadBuffer = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadBuffer.put(quad).flip()
        GLES30.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, quadBuffer, GLES30.GL_STATIC_DRAW)

        // 实例 VBO（动态）
        GLES30.glGenBuffers(1, ids, 0)
        instanceVbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX_INSTANCES * STRIDE_BYTES, null, GLES30.GL_DYNAMIC_DRAW)

        // VAO：固定 attrib 0（quad），instanced attrib 1~3
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(LOC_QUAD)
        GLES30.glVertexAttribPointer(LOC_QUAD, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glEnableVertexAttribArray(LOC_RECT)
        GLES30.glVertexAttribPointer(LOC_RECT, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES30.glVertexAttribDivisor(LOC_RECT, 1)
        GLES30.glEnableVertexAttribArray(LOC_UV)
        GLES30.glVertexAttribPointer(LOC_UV, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, 16)
        GLES30.glVertexAttribDivisor(LOC_UV, 1)
        GLES30.glEnableVertexAttribArray(LOC_COLOR)
        GLES30.glVertexAttribPointer(LOC_COLOR, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, 32)
        GLES30.glVertexAttribDivisor(LOC_COLOR, 1)
        GLES30.glBindVertexArray(0)

        GLES30.glEnable(GLES30.GL_BLEND)
        // 纹理为 premultiplied alpha（Canvas 烘焙输出），blend 用 ONE/ONE_MINUS_SRC_ALPHA。
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    fun setViewport(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
    }

    /** 清屏（透明背景），供每帧开头调用。 */
    fun beginFrame() {
        GLES30.glViewport(0, 0, viewportW, viewportH)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /** 绑定防挡蒙版纹理（0 = 解除裁剪）。纹理由外部创建并管理生命周期。 */
    fun setMask(textureId: Int) {
        maskTextureId = textureId
        maskEnabled = textureId != 0
    }

    /** 写入一条弹幕实例。热路径：只做 FloatBuffer.put，零分配。 */
    fun writeInstance(
        pageId: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        alpha: Float,
    ) {
        instanceBuffer.put(x).put(y).put(w).put(h)
        instanceBuffer.put(u0).put(v0).put(u1).put(v1)
        instanceBuffer.put(1f).put(1f).put(1f).put(alpha)
        pageCounts[pageId]++
    }

    /**
     * 上传实例数据并按 atlas 页分批 Instanced 绘制。
     * [textureIds] 为各页纹理 id（长度 = MAX_PAGES，未分配页为 0）。
     */
    fun drawPages(textureIds: IntArray) {
        val p = program
        val total = instanceBuffer.position()
        if (p == null || total <= 0) {
            lastDrawnInstanceCount = 0
            // 无实例/未初始化：必须清空页计数，否则残留计数会把下一帧实例段错位。
            java.util.Arrays.fill(pageCounts, 0)
            if (p == null) return
            return
        }
        lastDrawnInstanceCount = total / STRIDE_FLOATS

        // 计算每页实例段起始偏移（前缀和）。
        var offset = 0
        for (i in textureIds.indices) {
            pageOffsets[i] = offset
            offset += pageCounts[i]
        }

        instanceBuffer.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, instanceBuffer.remaining() * 4, instanceBuffer)
        instanceBuffer.clear()

        p.use()
        GLES30.glUniform2f(viewportUniform, viewportW.toFloat(), viewportH.toFloat())
        GLES30.glUniform1i(textureUniform, 0)
        GLES30.glUniform1i(maskUniform, 1)
        GLES30.glUniform1i(maskEnabledUniform, if (maskEnabled && maskTextureId != 0) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        if (maskEnabled && maskTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        for (i in textureIds.indices) {
            val count = pageCounts[i]
            if (count <= 0) continue
            val texId = textureIds[i]
            if (texId == 0) continue
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            val byteOffset = pageOffsets[i] * STRIDE_BYTES
            GLES30.glVertexAttribPointer(LOC_RECT, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, byteOffset)
            GLES30.glVertexAttribPointer(LOC_UV, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, byteOffset + 16)
            GLES30.glVertexAttribPointer(LOC_COLOR, 4, GLES30.GL_FLOAT, false, STRIDE_BYTES, byteOffset + 32)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        java.util.Arrays.fill(pageCounts, 0)
    }

    fun release() {
        program?.release()
        program = null
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        if (instanceVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(instanceVbo), 0)
        vao = 0
        quadVbo = 0
        instanceVbo = 0
    }
}
