#include "speech_tap.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>

#include <fcntl.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "microphun", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "microphun", __VA_ARGS__)
#else
// The resampler is plain C++ so it can be exercised on the host; see the host
// test command in CLAUDE.md.
#include <cstdio>
#define LOGI(...) std::fprintf(stderr, __VA_ARGS__)
#define LOGW(...) std::fprintf(stderr, __VA_ARGS__)
#endif

namespace microphun {
namespace {

constexpr float kPi = 3.14159265358979323846f;

float sinc(float x) {
    if (std::fabs(x) < 1e-6f) {
        return 1.0f;
    }
    return std::sin(kPi * x) / (kPi * x);
}

int16_t toPcm16(float sample) {
    const float clamped = std::min(1.0f, std::max(-1.0f, sample));
    return static_cast<int16_t>(std::lrintf(clamped * 32767.0f));
}

}  // namespace

SpeechTap::SpeechTap() {
    ring_.assign(kRingSize, 0.0f);
    taps_.assign(kTaps, 0.0f);
    history_.assign(kTaps, 0.0f);
    // Worst case one drain produces the whole ring; upsampling produces more, so
    // writeAll flushes mid-drain when this fills.
    out_.assign(kRingSize, 0);
}

SpeechTap::~SpeechTap() {
    stop();
}

bool SpeechTap::start(int fd, int inputRate) {
    std::lock_guard<std::mutex> lock(controlLock_);
    stopLocked();

    if (fd < 0) {
        return false;
    }
    if (inputRate <= 0) {
        ::close(fd);
        return false;
    }

    // Non-blocking on purpose: when the recogniser stops reading, the pipe fills
    // and we drop audio instead of stalling the writer thread.
    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags < 0 || ::fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        LOGW("speech tap: could not set O_NONBLOCK (errno %d)\n", errno);
        ::close(fd);
        return false;
    }

    fd_ = fd;
    designFilter(inputRate);
    resetState();
    startWriter();

    LOGI("speech tap: %d Hz -> %d Hz\n", inputRate, kOutputRate);
    return true;
}

void SpeechTap::stop() {
    std::lock_guard<std::mutex> lock(controlLock_);
    stopLocked();
}

void SpeechTap::stopLocked() {
    stopWriter();

    if (fd_ >= 0) {
        const uint64_t dropped = dropped_.exchange(0, std::memory_order_relaxed);
        if (dropped > 0) {
            LOGI("speech tap: dropped %llu samples the recogniser did not keep up with\n",
                 static_cast<unsigned long long>(dropped));
        }
        ::close(fd_);
        fd_ = -1;
    }
}

void SpeechTap::setInputRate(int inputRate) {
    std::lock_guard<std::mutex> lock(controlLock_);
    if (inputRate <= 0 || inputRate == inputRate_ || !running()) {
        return;
    }
    LOGI("speech tap: input rate now %d Hz\n", inputRate);
    stopWriter();
    designFilter(inputRate);
    resetState();
    startWriter();
}

void SpeechTap::push(const float* samples, int32_t count) {
    if (!running()) {
        return;
    }

    // Conservative: the consumer may have advanced since this load, which only
    // costs us a sample we could have kept.
    const uint64_t consumed = read_.load(std::memory_order_acquire);
    uint64_t cursor = written_.load(std::memory_order_relaxed);

    int32_t taken = 0;
    while (taken < count && cursor - consumed < kRingSize) {
        ring_[cursor & (kRingSize - 1)] = samples[taken];
        ++cursor;
        ++taken;
    }
    written_.store(cursor, std::memory_order_release);

    if (taken < count) {
        // The ring is full, so the recogniser is not draining the pipe. Dropping
        // here rather than overwriting keeps the consumer's samples intact.
        dropped_.fetch_add(static_cast<uint64_t>(count - taken), std::memory_order_relaxed);
    }
}

void SpeechTap::startWriter() {
    running_.store(true, std::memory_order_release);
    writer_ = std::thread(&SpeechTap::run, this);
}

void SpeechTap::stopWriter() {
    running_.store(false, std::memory_order_release);
    if (writer_.joinable()) {
        writer_.join();
    }
}

void SpeechTap::designFilter(int inputRate) {
    inputRate_ = inputRate;
    step_ = static_cast<float>(inputRate) / static_cast<float>(kOutputRate);

    // Windowed sinc just under the output Nyquist, so decimating cannot fold
    // 8 kHz and up back down into the speech band as aliases.
    const float cutoff = std::min(0.45f, kCutoffHz / static_cast<float>(inputRate));
    const float middle = static_cast<float>(kTaps - 1) * 0.5f;

    float sum = 0.0f;
    for (std::size_t i = 0; i < kTaps; ++i) {
        const float n = static_cast<float>(i) - middle;
        const float hamming = 0.54f - 0.46f * std::cos(2.0f * kPi * static_cast<float>(i) /
                                                       static_cast<float>(kTaps - 1));
        taps_[i] = 2.0f * cutoff * sinc(2.0f * cutoff * n) * hamming;
        sum += taps_[i];
    }
    for (float& tap : taps_) {
        tap /= sum;  // unity gain at DC, so speech keeps its level
    }
}

void SpeechTap::resetState() {
    // Start from live audio: whatever is already in the ring is from before the
    // recogniser asked to listen.
    read_.store(written_.load(std::memory_order_acquire), std::memory_order_release);
    std::fill(history_.begin(), history_.end(), 0.0f);
    historyPos_ = 0;
    previous_ = 0.0f;
    phase_ = 0.0f;
    dropped_.store(0, std::memory_order_relaxed);
}

float SpeechTap::filter(float sample) {
    history_[historyPos_] = sample;

    float accumulator = 0.0f;
    std::size_t index = historyPos_;
    for (std::size_t i = 0; i < kTaps; ++i) {
        accumulator += taps_[i] * history_[index];
        index = (index == 0) ? kTaps - 1 : index - 1;
    }

    historyPos_ = (historyPos_ + 1) % kTaps;
    return accumulator;
}

void SpeechTap::run() {
    while (running()) {
        // push() never writes past read_, so available cannot exceed the ring.
        const uint64_t consumed = read_.load(std::memory_order_relaxed);
        const uint64_t available = written_.load(std::memory_order_acquire) - consumed;

        std::size_t produced = 0;
        for (uint64_t i = 0; i < available; ++i) {
            const float y = filter(ring_[(consumed + i) & (kRingSize - 1)]);

            // phase_ is where the next output sample falls between the previous
            // filtered sample and this one, in input samples.
            while (phase_ <= 1.0f) {
                out_[produced++] = toPcm16(previous_ + (y - previous_) * phase_);
                phase_ += step_;

                if (produced == out_.size()) {
                    if (!writeAll(out_.data(), produced)) {
                        running_.store(false, std::memory_order_release);
                        return;
                    }
                    produced = 0;
                }
            }
            phase_ -= 1.0f;
            previous_ = y;
        }
        read_.store(consumed + available, std::memory_order_release);

        if (produced > 0 && !writeAll(out_.data(), produced)) {
            running_.store(false, std::memory_order_release);
            return;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(kPollMillis));
    }
}

bool SpeechTap::writeAll(const int16_t* data, std::size_t count) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(data);
    std::size_t remaining = count * sizeof(int16_t);

    while (remaining > 0) {
        const ssize_t wrote = ::write(fd_, bytes, remaining);
        if (wrote > 0) {
            bytes += wrote;
            remaining -= static_cast<std::size_t>(wrote);
            continue;
        }
        if (wrote < 0 && errno == EINTR) {
            continue;
        }
        if (wrote < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            // The pipe is full: the recogniser is between sessions or has
            // stopped reading. Dropping is right — this audio is already stale.
            dropped_.fetch_add(remaining / sizeof(int16_t), std::memory_order_relaxed);
            return true;
        }
        LOGW("speech tap: write failed (errno %d), reader gone\n", errno);
        return false;
    }
    return true;
}

}  // namespace microphun
