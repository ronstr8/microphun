package com.microphun.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.microphun.audio.AudioEngine
import com.microphun.speech.WordStream

/** Full-screen ES 3.0 surface. Tap anywhere to cycle to the next visual. */
class VisualizerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private var renderer: VisualizerRenderer? = null

    /** Invoked on the UI thread with the new visual's name after each tap. */
    var onShaderChanged: ((String) -> Unit)? = null

    init {
        // Without this the view returns false from ACTION_DOWN, Android stops
        // delivering the rest of the gesture, and performClick never fires.
        // Being clickable also gets tap detection and accessibility for free,
        // so there is no need to interpret MotionEvents by hand.
        isClickable = true
        isFocusable = true
    }

    fun attach(audio: AudioEngine, words: WordStream) {
        setEGLContextClientVersion(3)
        val created = VisualizerRenderer(context.assets, audio, words)
        renderer = created
        setRenderer(created)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun nextShader() = renderer?.nextShader()

    fun currentShaderName(): String? = renderer?.currentShaderName()

    override fun performClick(): Boolean {
        super.performClick()
        renderer?.let {
            it.nextShader()
            onShaderChanged?.invoke(it.currentShaderName())
        }
        return true
    }
}
