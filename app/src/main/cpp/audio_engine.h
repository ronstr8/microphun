#pragma once

#include <atomic>
#include <cstddef>
#include <memory>
#include <mutex>
#include <vector>

#include <oboe/Oboe.h>

#include "analysis.h"
#include "sample_ring.h"
#include "speech_tap.h"

namespace microphun {

// Owns the microphone stream and hands the renderer an analysed Frame.
//
// Threading: Oboe's realtime callback only ever appends to the ring buffer and
// to the speech tap's ring. The FFT runs on whichever thread calls frame() (the
// GL thread), so no allocation, locking or logging happens on the audio thread.
//
// The two threads genuinely do touch the same ring slots — the renderer copies
// the newest kFftSize samples while the callback keeps appending — so the ring
// itself owns that problem. See SampleRing.
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    static constexpr std::size_t kFftSize = 2048;
    static constexpr std::size_t kRingSize = 8192;  // power of two

    AudioEngine();
    ~AudioEngine() override;

    bool start();
    void stop();

    // Feeds the recogniser from our own capture instead of letting it open the
    // microphone. Takes ownership of fd (a pipe write end) either way. The tap
    // is deliberately independent of start()/stop(): a device disconnect
    // reopens the stream without interrupting recognition.
    bool startSpeechTap(int fd);
    void stopSpeechTap();

    // Analyses the most recent kFftSize samples. Safe to call at frame rate.
    void frame(Frame& out);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    // Longest block we downmix in one pass; bigger callbacks loop.
    static constexpr std::size_t kMaxCallbackFrames = 4096;

    // How many times frame() re-copies a window the callback wrapped onto.
    // Being lapped means this thread stalled for kRingSize - kFftSize samples
    // (128 ms at 48 kHz), so once it is running again a retry succeeds.
    static constexpr int kSnapshotTries = 3;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamLock_;

    SampleRing ring_;

    std::vector<float> mono_;     // one callback's downmix, for the tap
    std::vector<float> scratch_;
    std::unique_ptr<Analyzer> analyzer_;
    Frame lastFrame_{};  // shown again if a window has to be thrown away
    SpeechTap tap_;
    int sampleRate_ = 48000;
};

}  // namespace microphun
