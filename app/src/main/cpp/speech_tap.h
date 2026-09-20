#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace microphun {

// Tees the captured microphone audio into a pipe for SpeechRecognizer.
//
// Android hands the microphone to one capture at a time, so a recogniser that
// opens its own input would cut ours off and the visuals would go still. This
// keeps the microphone and feeds the recogniser instead, through
// RecognizerIntent.EXTRA_AUDIO_SOURCE: mono 16-bit PCM at kOutputRate, written
// to the write end of a pipe whose read end Kotlin hands to the service.
//
// Threading: push() is called from Oboe's realtime callback and only appends to
// a lock-free ring. A writer thread does the resampling and the write(2), so no
// syscall, allocation or lock ever happens on the audio thread. start(), stop()
// and setInputRate() take controlLock_ between them, because a device
// disconnect calls setInputRate from Oboe's error thread while the main thread
// may be starting or stopping recognition.
class SpeechTap {
public:
    // What the recogniser is told to expect. Mirrored by
    // AudioEngine.SPEECH_SAMPLE_RATE in Kotlin, which is checked at startup.
    static constexpr int kOutputRate = 16000;

    SpeechTap();
    ~SpeechTap();

    SpeechTap(const SpeechTap&) = delete;
    SpeechTap& operator=(const SpeechTap&) = delete;

    // Takes ownership of fd, the write end of a pipe, and closes it on failure
    // and in stop(). Any session already running is stopped first.
    bool start(int fd, int inputRate);
    void stop();

    // Follows the capture stream if it reopens at a different rate, which would
    // otherwise pitch-shift everything the recogniser hears.
    void setInputRate(int inputRate);

    bool running() const { return running_.load(std::memory_order_acquire); }

    // Realtime thread. Never blocks, never allocates.
    void push(const float* samples, int32_t count);

private:
    static constexpr std::size_t kRingSize = 1 << 15;  // ~0.7 s at 48 kHz
    static constexpr std::size_t kTaps = 33;
    static constexpr float kCutoffHz = 7000.0f;
    static constexpr int kPollMillis = 10;

    void startWriter();
    void stopWriter();
    void stopLocked();
    void run();

    void designFilter(int inputRate);
    void resetState();
    float filter(float sample);
    bool writeAll(const int16_t* data, std::size_t count);

    // Single producer (audio thread), single consumer (writer thread). Both
    // cursors are published so the producer can tell when the ring is full and
    // drop, rather than overwriting a slot the consumer is still reading.
    std::vector<float> ring_;
    std::atomic<uint64_t> written_{0};
    std::atomic<uint64_t> read_{0};

    std::vector<float> taps_;
    std::vector<float> history_;
    std::size_t historyPos_ = 0;
    float previous_ = 0.0f;
    float phase_ = 0.0f;
    float step_ = 1.0f;
    int inputRate_ = 0;

    std::vector<int16_t> out_;

    std::mutex controlLock_;
    int fd_ = -1;
    std::thread writer_;
    std::atomic<bool> running_{false};
    std::atomic<uint64_t> dropped_{0};
};

}  // namespace microphun
