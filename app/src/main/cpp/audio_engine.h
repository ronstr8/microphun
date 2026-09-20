#pragma once

#include <atomic>
#include <cstddef>
#include <memory>
#include <mutex>
#include <vector>

#include <oboe/Oboe.h>

#include "analysis.h"
#include "speech_tap.h"

namespace microphun {

// Owns the microphone stream and hands the renderer an analysed Frame.
//
// Threading: Oboe's realtime callback only ever appends to the ring buffer and
// to the speech tap's ring. The FFT runs on whichever thread calls frame() (the
// GL thread), so no allocation, locking or logging happens on the audio thread.
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

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamLock_;

    std::vector<float> ring_;
    std::atomic<uint64_t> written_{0};

    std::vector<float> mono_;     // one callback's downmix, for the tap
    std::vector<float> scratch_;
    std::unique_ptr<Analyzer> analyzer_;
    SpeechTap tap_;
    int sampleRate_ = 48000;
};

}  // namespace microphun
