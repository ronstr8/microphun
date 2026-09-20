package com.microphun.gl

import android.opengl.GLES30
import android.util.Log

/** A linked GL program with lazily cached uniform locations. */
class ShaderProgram private constructor(private val id: Int) {

    private val uniforms = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(id)

    private fun location(name: String): Int =
        uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set(name: String, value: Float) {
        val loc = location(name)
        if (loc >= 0) GLES30.glUniform1f(loc, value)
    }

    fun set(name: String, x: Float, y: Float) {
        val loc = location(name)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }

    fun setInt(name: String, value: Int) {
        val loc = location(name)
        if (loc >= 0) GLES30.glUniform1i(loc, value)
    }

    fun setArray(name: String, values: FloatArray) {
        val loc = location(name)
        if (loc >= 0) GLES30.glUniform1fv(loc, values.size, values, 0)
    }

    fun attribute(name: String): Int = GLES30.glGetAttribLocation(id, name)

    fun release() {
        GLES30.glDeleteProgram(id)
        uniforms.clear()
    }

    companion object {
        private const val TAG = "microphun.gl"

        /** Compiles and links, or throws with the driver's log attached. */
        fun link(vertexSource: String, fragmentSource: String): ShaderProgram {
            val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vs)
            GLES30.glAttachShader(program, fs)
            GLES30.glLinkProgram(program)

            // Shaders are reference-counted by the program once attached.
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)

            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                throw RuntimeException("shader link failed: $log")
            }

            return ShaderProgram(program)
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)

            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                Log.e(TAG, "shader compile failed:\n$log")
                throw RuntimeException("shader compile failed: $log")
            }
            return shader
        }
    }
}
