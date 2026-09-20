package com.microphun.audio

import androidx.annotation.WorkerThread

/**
 * Kotlin face of the native capture + analysis engine.
 *
 * One instance owns one microphone stream. [close] must be called or the
 * native object and its Oboe stream leak.
 */
class AudioEngine : AutoCloseable {

    private var handle: Long = nativeCreate()

    private val bandBuffer = FloatArray(BAND_COUNT)
    private val scalarBuffer = FloatArray(SCALAR_COUNT)

    /** Reusable frame; [read] overwrites it rather than allocating per frame. */
    val frame = AudioFrame(bandBuffer)

    init {
        check(handle != 0L) { "native AudioEngine allocation failed" }
        val nativeBands = nativeBandCount(handle)
        check(nativeBands == BAND_COUNT) {
            "band count mismatch: Kotlin=$BAND_COUNT native=$nativeBands"
        }
        // The recogniser is told this rate in the intent while native resamples
        // to it. If the two ever drift, speech arrives pitch-shifted.
        val nativeRate = nativeSpeechRate()
        check(nativeRate == SPEECH_SAMPLE_RATE) {
            "speech rate mismatch: Kotlin=$SPEECH_SAMPLE_RATE native=$nativeRate"
        }
    }

    /** Opens and starts the input stream. Requires RECORD_AUDIO to be granted. */
    fun start(): Boolean = handle != 0L && nativeStart(handle)

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    /**
     * Starts teeing capture into [fd], the write end of a pipe, as mono 16-bit
     * PCM at [SPEECH_SAMPLE_RATE] — so the recogniser can listen without taking
     * the microphone off us.
     *
     * The descriptor is handed over to native code, which closes it, including
     * when this returns false. Pass a detached fd, never one still owned by a
     * ParcelFileDescriptor.
     */
    fun startSpeechTap(fd: Int): Boolean = handle != 0L && nativeStartSpeechTap(handle, fd)

    fun stopSpeechTap() {
        if (handle != 0L) nativeStopSpeechTap(handle)
    }

    /**
     * Refreshes [frame] from the most recent audio. Runs an FFT, so call it
     * from the render thread, once per frame.
     */
    @WorkerThread
    fun read(): AudioFrame {
        if (handle != 0L) {
            nativeFrame(handle, bandBuffer, scalarBuffer)
            frame.level = scalarBuffer[0]
            frame.bass = scalarBuffer[1]
            frame.mid = scalarBuffer[2]
            frame.treble = scalarBuffer[3]
            frame.onset = scalarBuffer[4]
        }
        return frame
    }

    override fun close() {
        if (handle != 0L) {
            nativeStop(handle)
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeFrame(handle: Long, bands: FloatArray, scalars: FloatArray)
    private external fun nativeBandCount(handle: Long): Int
    private external fun nativeStartSpeechTap(handle: Long, fd: Int): Boolean
    private external fun nativeStopSpeechTap(handle: Long)
    private external fun nativeSpeechRate(): Int

    companion object {
        /** Must stay in sync with `Frame::kBands` in analysis.h. */
        const val BAND_COUNT = 24

        /** Must stay in sync with `SpeechTap::kOutputRate` in speech_tap.h. */
        const val SPEECH_SAMPLE_RATE = 16000

        private const val SCALAR_COUNT = 5

        init {
            System.loadLibrary("microphun")
        }
    }
}

/** One analysed slice of audio. Mutable and reused; do not retain it. */
class AudioFrame(
    /** Log-spaced band magnitudes, each 0..1, low frequency first. */
    val bands: FloatArray,
) {
    var level: Float = 0f
    var bass: Float = 0f
    var mid: Float = 0f
    var treble: Float = 0f

    /** Spikes to 1 on a detected onset, then decays. */
    var onset: Float = 0f
}
