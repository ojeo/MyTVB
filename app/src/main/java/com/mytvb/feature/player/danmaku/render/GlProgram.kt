package com.mytvb.feature.player.danmaku.render

import android.opengl.GLES30
import com.mytvb.core.common.log.AppLog

/**
 * GLSL 程序封装：编译/链接/资源定位。
 *
 * 弹幕引擎只需一个实例化 quad 着色器（顶点做位置变换，片段从 atlas 采样 + 全局透明度）。
 */
internal class GlProgram(
    vertexSource: String,
    fragmentSource: String,
) {
    val programId: Int = link(vertexSource, fragmentSource)

    fun use() {
        GLES30.glUseProgram(programId)
    }

    fun attribLocation(name: String): Int = GLES30.glGetAttribLocation(programId, name)

    fun uniformLocation(name: String): Int = GLES30.glGetUniformLocation(programId, name)

    fun release() {
        if (programId != 0) GLES30.glDeleteProgram(programId)
    }

    private fun link(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSource)
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("glCreateProgram failed")
        }
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("glLinkProgram failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("glCreateShader($type) failed")
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            if (AppLog.isEnabled) {
                AppLog.w("GlProgram", "shader compile failed: $log")
            }
            throw RuntimeException("glCompileShader($type) failed: $log")
        }
        return shader
    }
}
