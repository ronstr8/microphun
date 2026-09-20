package com.microphun

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.microphun.audio.AudioEngine
import com.microphun.gl.VisualizerRenderer
import com.microphun.gl.VisualizerSurfaceView
import com.microphun.speech.WordListener
import com.microphun.speech.WordStream

/**
 * The whole app: one immersive GL surface fed by the microphone.
 *
 * Audio capture is started in [onResume] and stopped in [onPause] so microphun
 * never holds the mic while backgrounded. Speech recognition follows the same
 * rule and is narrower still: it only runs while the `words` visual is on
 * screen, since it is the only visual that has anything to do with it, and it
 * listens to our capture rather than opening the microphone itself.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var surface: VisualizerSurfaceView
    private lateinit var audio: AudioEngine
    private lateinit var label: TextView
    private lateinit var words: WordStream
    private lateinit var listener: WordListener

    private var micGranted = false

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) {
            startCapture()
            syncListener(surface.currentShaderName())
        } else {
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        goImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audio = AudioEngine()
        words = WordStream()
        listener = WordListener(this, audio, words)
        listener.onMicFallback = {
            flashLabel(getString(R.string.speech_took_mic), holdMillis = 3500)
        }
        surface = VisualizerSurfaceView(this).apply { attach(audio, words) }

        // The surface fills the window; the label floats over it and fades,
        // so the visual is never permanently obscured.
        label = buildLabel()
        setContentView(
            FrameLayout(this).apply {
                addView(surface)
                addView(
                    label,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    ),
                )
            },
        )

        surface.onShaderChanged = { name ->
            runOnUiThread {
                flashLabel(name)
                syncListener(name)
            }
        }

        // Without this the tap-to-cycle gesture is invisible.
        flashLabel("Tap anywhere to change visual", holdMillis = 3500)

        micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        if (micGranted) {
            startCapture()
            syncListener(surface.currentShaderName())
        }
    }

    override fun onPause() {
        listener.stop()
        audio.stop()
        surface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        listener.stop()
        audio.close()
        super.onDestroy()
    }

    private fun buildLabel(): TextView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            setPadding(pad, pad, pad, pad)
            alpha = 0f
        }
    }

    /** Shows [text] over the visual, then fades it out. */
    private fun flashLabel(text: String, holdMillis: Long = 1400) {
        label.animate().cancel()
        label.text = text
        label.alpha = 1f
        label.animate()
            .alpha(0f)
            .setStartDelay(holdMillis)
            .setDuration(700)
            .start()
    }

    /**
     * Starts or stops speech recognition to match the visual on screen.
     *
     * The recogniser is a separate app, and Android hands the microphone to one
     * capture at a time, so it is fed from our own capture instead of opening
     * the microphone itself and silencing the visuals. See [WordListener]. If a
     * device will not accept that, it says so through `onMicFallback` and the
     * label explains why the backdrop went still.
     */
    private fun syncListener(name: String?) {
        if (name != VisualizerRenderer.WORDS_SHADER || !micGranted) {
            listener.stop()
            return
        }
        if (!listener.isAvailable) {
            flashLabel(getString(R.string.speech_unavailable), holdMillis = 3500)
            return
        }
        listener.start()
    }

    private fun startCapture() {
        if (!audio.start()) {
            Toast.makeText(this, "Could not open the microphone", Toast.LENGTH_LONG).show()
        }
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
