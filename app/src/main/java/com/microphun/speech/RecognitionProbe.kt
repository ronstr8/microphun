package com.microphun.speech

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.microphun.BuildConfig

/**
 * Finds out what the device's recogniser will actually report, beyond text.
 *
 * Android lets a client ask for punctuation, per-word timing and confidence,
 * alternatives and language detection, but every one of them is a request the
 * recogniser is free to ignore. This asks for all of them and logs everything
 * that comes back, under `microphun.probe`, so a single conversation on the
 * device settles what a speech-driven visual can rely on.
 *
 * Debug builds only: [ENABLED] is false in release, and then this neither
 * changes the request nor logs anything.
 */
object RecognitionProbe {

    val ENABLED = BuildConfig.DEBUG

    private const val TAG = "microphun.probe"

    /** Alternatives asked for, so their agreement can be seen. */
    const val MAX_RESULTS = 3

    private var sessionStart = 0L
    private var rmsCount = 0
    private var rmsMin = Float.MAX_VALUE
    private var rmsMax = -Float.MAX_VALUE
    private var bufferBytes = 0L

    /** Adds every optional extra this API level knows about to [request]. */
    fun ask(request: Intent) {
        if (!ENABLED) return
        request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            request.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            request.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
            request.putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_CONFIDENCE, true)
            request.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
        }
        sessionStart = SystemClock.elapsedRealtime()
        log("asked for: formatting, word timing, word confidence, language detection, $MAX_RESULTS alternatives (API ${Build.VERSION.SDK_INT})")
    }

    fun event(name: String) {
        if (ENABLED) log(name)
    }

    /** Level is too frequent to log per call; kept as a range, reported per result. */
    fun rms(rmsdB: Float) {
        if (!ENABLED) return
        rmsCount++
        rmsMin = minOf(rmsMin, rmsdB)
        rmsMax = maxOf(rmsMax, rmsdB)
    }

    fun buffer(bytes: Int) {
        if (ENABLED) bufferBytes += bytes
    }

    /** Logs every key in [bundle], with the known structured ones spelled out. */
    fun bundle(name: String, bundle: Bundle?) {
        if (!ENABLED) return
        if (bundle == null) {
            log("$name: null bundle")
            return
        }
        log("$name: keys ${bundle.keySet().sorted()}")
        for (key in bundle.keySet().sorted()) {
            @Suppress("DEPRECATION")
            val value = bundle.get(key)
            when {
                key == SpeechRecognizer.RECOGNITION_PARTS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    parts(name, bundle)
                value is FloatArray -> log("  $key = ${value.joinToString()}")
                value is IntArray -> log("  $key = ${value.joinToString()}")
                value is ArrayList<*> -> log("  $key = ${value.joinToString(" | ")}")
                value is Array<*> -> log("  $key = ${value.joinToString(" | ")}")
                else -> log("  $key = $value")
            }
        }
        if (name.startsWith("results") || name.startsWith("segment")) {
            log(
                "  level over ${rmsCount} rms callbacks: " +
                    if (rmsCount > 0) "%.1f..%.1f dB".format(rmsMin, rmsMax) else "none",
            )
            log("  onBufferReceived bytes so far: $bufferBytes")
            rmsCount = 0
            rmsMin = Float.MAX_VALUE
            rmsMax = -Float.MAX_VALUE
        }
    }

    private fun parts(name: String, bundle: Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val parts = bundle.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java)
        log("  ${SpeechRecognizer.RECOGNITION_PARTS}: ${parts?.size ?: 0} parts")
        parts?.forEach { part ->
            log(
                "    '${part.rawText}' formatted='${part.formattedText}' " +
                    "t=${part.timestampMillis}ms confidence=${confidenceName(part.confidenceLevel)}",
            )
        }
    }

    private fun confidenceName(level: Int): String = when (level) {
        RecognitionPart.CONFIDENCE_LEVEL_UNKNOWN -> "unknown"
        RecognitionPart.CONFIDENCE_LEVEL_LOW -> "low"
        RecognitionPart.CONFIDENCE_LEVEL_MEDIUM_LOW -> "medium-low"
        RecognitionPart.CONFIDENCE_LEVEL_MEDIUM -> "medium"
        RecognitionPart.CONFIDENCE_LEVEL_MEDIUM_HIGH -> "medium-high"
        RecognitionPart.CONFIDENCE_LEVEL_HIGH -> "high"
        else -> "level $level"
    }

    private fun log(message: String) {
        val elapsed = SystemClock.elapsedRealtime() - sessionStart
        Log.d(TAG, "+${elapsed}ms $message")
    }
}
