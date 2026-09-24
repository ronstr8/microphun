package com.microphun.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import com.microphun.BuildConfig
import com.microphun.audio.AudioEngine
import java.io.IOException
import java.util.Locale

/**
 * Continuous word capture for the `words` visual: settled words one at a time
 * for the word cloud, and whole utterances for [WordCorpus].
 *
 * The recogniser is a separate app, and Android hands the microphone to one
 * capture at a time, so letting it open its own input would silence ours and
 * leave the visuals still. Instead microphun keeps the microphone and feeds the
 * recogniser through `EXTRA_AUDIO_SOURCE` (API 33+): [AudioEngine] resamples the
 * captured audio to 16 kHz mono PCM and writes it into a pipe whose read end
 * goes to the service in the intent.
 *
 * With `EXTRA_SEGMENTED_SESSION` the service is asked to keep one session open
 * against that descriptor and report each utterance as a segment. The docs are
 * explicit that a recogniser may ignore both extras, so this also handles the
 * ordinary one-utterance-per-session behaviour, and drops back to letting the
 * recogniser use the microphone if the descriptor turns out to produce nothing.
 *
 * SpeechRecognizer must be created and driven from the main thread, so every
 * method here is [MainThread] and restarts go through [handler].
 */
class WordListener(
    context: Context,
    private val audio: AudioEngine,
    private val sink: WordStream,
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var backoffMillis = MIN_RESTART_MILLIS

    /** How many tokens of the hypothesis in progress have reached [sink]. */
    private var emittedCount = 0

    /** The pipe's read end, held open until the session that uses it ends. */
    private var audioSource: ParcelFileDescriptor? = null

    /**
     * Whether to feed the recogniser our own capture rather than give up the
     * microphone. Switched off for the rest of the process if the recogniser
     * turns out not to honour the descriptor.
     */
    private var feedOwnAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private var silentSessions = 0

    /** Fired once, on the main thread, if the microphone had to be given up. */
    var onMicFallback: (() -> Unit)? = null

    /** False when the device has no recognition service at all. */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** True while the recogniser is being fed from our own capture. */
    val keepsMicrophone: Boolean
        get() = feedOwnAudio

    private val restart = Runnable {
        if (listening) recognizer?.let { listen(it) }
    }

    @MainThread
    fun start() {
        if (listening) return
        val target = recognizer ?: create() ?: return
        listening = true
        backoffMillis = MIN_RESTART_MILLIS
        listen(target)
    }

    /** Stops listening, releases the service connection and closes the tap. */
    @MainThread
    fun stop() {
        listening = false
        handler.removeCallbacks(restart)
        emittedCount = 0
        recognizer?.let { target ->
            try {
                target.cancel()
                target.destroy()
            } catch (e: RuntimeException) {
                Log.w(TAG, "recogniser teardown failed", e)
            }
        }
        recognizer = null
        closeAudioSource()
        sink.clear()
    }

    private fun create(): SpeechRecognizer? {
        val created = try {
            when {
                // The on-device recogniser survives being restarted every few
                // seconds, works offline, and is the one that supports being fed
                // a descriptor. The network one rate-limits long sessions.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)

                SpeechRecognizer.isRecognitionAvailable(appContext) ->
                    SpeechRecognizer.createSpeechRecognizer(appContext)

                else -> null
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "no usable recogniser", e)
            null
        } ?: return null

        created.setRecognitionListener(Listener())
        recognizer = created
        return created
    }

    private fun listen(target: SpeechRecognizer) {
        emittedCount = 0

        // Each session gets its own pipe. Reusing one would hand the next
        // session whatever audio was still sitting in the buffer.
        closeAudioSource()
        val source = if (feedOwnAudio) openTap() else null
        if (feedOwnAudio && source == null) {
            // No pipe means no way to feed it, so the choice is the microphone
            // or nothing. Go through the fallback so the UI hears about it and
            // later sessions stop trying.
            fallBackToMicrophone()
        }

        val request = request()
        if (source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            describeAudioSource(request, source)
        }

        try {
            // Makes a restart idempotent: cancel is harmless when idle, and stops
            // a segmented session that is somehow still running.
            target.cancel()
            target.startListening(request)
            audioSource = source
        } catch (e: RuntimeException) {
            Log.w(TAG, "startListening failed", e)
            closeAudioSource()
            source?.closeQuietly()
            scheduleRestart(backoffMillis)
        }
    }

    private fun request(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

    /**
     * Opens a pipe and points the native tap at its write end.
     *
     * Native owns that descriptor from here on and closes it itself, so the
     * ParcelFileDescriptor is detached rather than kept.
     */
    private fun openTap(): ParcelFileDescriptor? {
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            Log.w(TAG, "could not open the speech pipe", e)
            return null
        }

        val read = pipe[0]
        val write = pipe[1]
        if (!audio.startSpeechTap(write.detachFd())) {
            Log.w(TAG, "speech tap refused to start")
            read.closeQuietly()
            return null
        }
        return read
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun describeAudioSource(request: Intent, source: ParcelFileDescriptor) {
        request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        request.putExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        request.putExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
            AudioEngine.SPEECH_SAMPLE_RATE,
        )
        // Ask for one session that runs for as long as the descriptor does, with
        // each utterance reported as a segment. If the recogniser ignores this we
        // simply get ordinary results and restart, as on older releases.
        request.putExtra(
            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
            RecognizerIntent.EXTRA_AUDIO_SOURCE,
        )
    }

    private fun closeAudioSource() {
        audio.stopSpeechTap()
        audioSource?.closeQuietly()
        audioSource = null
    }

    private fun scheduleRestart(delayMillis: Long) {
        if (!listening) return
        // One pending restart at a time: results and an error can both land for
        // the same session, and a cancel can report one of its own.
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMillis)
    }

    /**
     * Gives up on the descriptor and lets the recogniser open the microphone.
     *
     * The visuals lose their input while it listens, which is worse but still
     * better than a word cloud that never fills.
     */
    private fun fallBackToMicrophone() {
        if (!feedOwnAudio) return
        Log.i(TAG, "recogniser produced nothing from the descriptor, giving it the mic")
        feedOwnAudio = false
        closeAudioSource()
        onMicFallback?.invoke()
    }

    /**
     * Pushes whatever has settled in [results] into the sink.
     *
     * A partial hypothesis is revised as the recogniser hears more, and the
     * word being spoken right now is revised most of all: "mi" becomes "mic"
     * becomes "microphone". A word already on screen cannot be taken back, so
     * emitting the whole hypothesis would strand each word at its earliest,
     * shortest guess — one syllable of every word, never corrected.
     *
     * A token is settled once the recogniser has moved past it, which means
     * every token but the last. On a final result the last one has settled too.
     * The cost is that a word appears once the next one begins, or at the end
     * of the utterance if it is the last.
     *
     * A final result is also the end of an utterance, which goes to the sink
     * whole for [WordCorpus]. That is the recogniser's last word on what was
     * said, so it has the revisions the word-by-word stream could not make.
     */
    private fun emit(results: Bundle?, final: Boolean): Boolean {
        val hypothesis = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?: return false

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "${if (final) "final  " else "partial"}: $hypothesis")
        }

        if (final) sink.offerUtterance(tokenise(hypothesis, MIN_CORPUS_WORD_LENGTH))

        val tokens = tokenise(hypothesis, MIN_WORD_LENGTH)
        val settled = if (final) tokens.size else tokens.size - 1
        for (i in emittedCount until settled) {
            sink.offer(tokens[i])
        }
        // Never goes backwards: a hypothesis that shrinks must not re-emit what
        // is already on screen.
        emittedCount = maxOf(emittedCount, settled)
        return tokens.isNotEmpty()
    }

    private fun tokenise(text: String, minLength: Int): List<String> =
        SEPARATOR.split(text.lowercase(Locale.getDefault()))
            .mapNotNull { token ->
                token.trim('\'').takeIf { it.length >= minLength }
            }

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            Log.w(TAG, "closing the speech pipe failed", e)
        }
    }

    private inner class Listener : RecognitionListener {

        override fun onPartialResults(partialResults: Bundle?) {
            // Partials are the earliest proof that audio is reaching the
            // recogniser, which is what the fallback is watching for.
            heard(emit(partialResults, final = false))
        }

        override fun onResults(results: Bundle?) {
            heard(emit(results, final = true))
            // A one-shot session is over; the next utterance needs a new one.
            scheduleRestart(MIN_RESTART_MILLIS)
        }

        /**
         * Segmented mode: one utterance of a session that is still running, so
         * there is nothing to restart. The next segment starts its hypothesis
         * from scratch, so the emitted tail resets with it.
         */
        override fun onSegmentResults(segmentResults: Bundle) {
            heard(emit(segmentResults, final = true))
            emittedCount = 0
        }

        override fun onEndOfSegmentedSession() {
            emittedCount = 0
            scheduleRestart(MIN_RESTART_MILLIS)
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stop()
                return
            }

            // No-match and timeout just mean silence, which is the normal state
            // of a room — but a descriptor the recogniser never reads looks
            // exactly like silence, so count them towards the fallback.
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (silence) {
                countSilentSession()
                scheduleRestart(MIN_RESTART_MILLIS)
                return
            }

            // A recogniser that cannot use the descriptor at all tends to say so
            // immediately, with a client or audio error.
            if (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_CLIENT) {
                countSilentSession()
            }

            val delay = backoffMillis
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_RESTART_MILLIS)
            Log.d(TAG, "recogniser error $error, retrying in ${delay}ms")
            scheduleRestart(delay)
        }

        private fun heard(anything: Boolean) {
            if (!anything) return
            // The service is healthy and the descriptor, if we used one, works.
            backoffMillis = MIN_RESTART_MILLIS
            silentSessions = 0
        }

        private fun countSilentSession() {
            if (!feedOwnAudio) return
            silentSessions++
            if (silentSessions >= FALLBACK_AFTER_SILENT_SESSIONS) {
                fallBackToMicrophone()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "microphun.speech"

        /** Punctuation and whitespace; apostrophes stay inside a word. */
        val SEPARATOR = Regex("[^\\p{L}\\p{Nd}']+")

        /** "a" and "I" are noise on screen; two letters is the floor. */
        const val MIN_WORD_LENGTH = 2

        /**
         * The corpus keeps them. A phrase is read as a sentence, not a scatter,
         * and "went to shop" reads as broken where "went to a shop" does not.
         */
        const val MIN_CORPUS_WORD_LENGTH = 1

        const val MIN_RESTART_MILLIS = 120L
        const val MAX_RESTART_MILLIS = 4_000L

        /**
         * Sessions that hear nothing at all before we stop trusting the
         * descriptor. Generous: a genuinely quiet room should not cost us the
         * microphone, and one session is over in a few seconds.
         */
        const val FALLBACK_AFTER_SILENT_SESSIONS = 6
    }
}
