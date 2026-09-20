#include "audio_engine.h"

#include <algorithm>

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "microphun", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "microphun", __VA_ARGS__)

namespace microphun {

AudioEngine::AudioEngine() {
    ring_.assign(kRingSize, 0.0f);
    mono_.assign(kMaxCallbackFrames, 0.0f);
    scratch_.assign(kFftSize, 0.0f);
    analyzer_ = std::make_unique<Analyzer>(kFftSize, sampleRate_);
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(streamLock_);
    if (stream_) {
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(sampleRate_)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        // Unprocessed bypasses AGC, noise suppression and echo cancellation.
        // Those are tuned for speech and would flatten exactly the dynamics
        // the visuals are meant to show. Oboe falls back if unsupported.
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGW("failed to open input stream: %s", oboe::convertToText(result));
        stream_.reset();
        return false;
    }

    // The device may have refused our requested rate; rebuild the analyser
    // against what we actually got, or every frequency would be mislabelled.
    const int actualRate = stream_->getSampleRate();
    if (actualRate != sampleRate_) {
        LOGI("sample rate negotiated to %d Hz", actualRate);
        sampleRate_ = actualRate;
        analyzer_ = std::make_unique<Analyzer>(kFftSize, sampleRate_);
    }
    // Same reasoning for the recogniser: resampling from the wrong input rate
    // would hand it pitch-shifted speech.
    tap_.setInputRate(sampleRate_);

    const oboe::Result started = stream_->requestStart();
    if (started != oboe::Result::OK) {
        LOGW("failed to start input stream: %s", oboe::convertToText(started));
        stream_->close();
        stream_.reset();
        return false;
    }

    LOGI("capture running: %d Hz, %d ch", sampleRate_, stream_->getChannelCount());
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(streamLock_);
    if (!stream_) {
        return;
    }
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream,
                                                   void* audioData,
                                                   int32_t numFrames) {
    const auto* in = static_cast<const float*>(audioData);
    const int channels = stream->getChannelCount();

    uint64_t cursor = written_.load(std::memory_order_relaxed);

    // Downmix through a preallocated buffer as well as into the ring: the ring
    // wraps, and the speech tap wants a contiguous block.
    int32_t done = 0;
    while (done < numFrames) {
        const auto chunk = std::min<int32_t>(numFrames - done,
                                            static_cast<int32_t>(kMaxCallbackFrames));
        for (int32_t i = 0; i < chunk; ++i) {
            // Downmix defensively: we asked for mono but may have been given more.
            float sample = 0.0f;
            for (int c = 0; c < channels; ++c) {
                sample += in[(done + i) * channels + c];
            }
            sample /= static_cast<float>(channels);

            mono_[static_cast<std::size_t>(i)] = sample;
            ring_[cursor & (kRingSize - 1)] = sample;
            ++cursor;
        }
        written_.store(cursor, std::memory_order_release);
        tap_.push(mono_.data(), chunk);
        done += chunk;
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    // Disconnects happen when headphones or a USB interface are plugged in.
    LOGW("stream closed with %s, reopening", oboe::convertToText(error));
    {
        std::lock_guard<std::mutex> lock(streamLock_);
        stream_.reset();
    }
    start();
}

bool AudioEngine::startSpeechTap(int fd) {
    // Under the stream lock so the rate cannot be renegotiated out from under
    // us mid-call. Same lock order as start(), which reaches the tap through
    // setInputRate, so the two cannot deadlock.
    std::lock_guard<std::mutex> lock(streamLock_);
    return tap_.start(fd, sampleRate_);
}

void AudioEngine::stopSpeechTap() {
    tap_.stop();
}

void AudioEngine::frame(Frame& out) {
    const uint64_t cursor = written_.load(std::memory_order_acquire);
    if (cursor < kFftSize) {
        out = Frame{};
        return;
    }

    const uint64_t start = cursor - kFftSize;
    for (std::size_t i = 0; i < kFftSize; ++i) {
        scratch_[i] = ring_[(start + i) & (kRingSize - 1)];
    }

    analyzer_->analyze(scratch_.data(), out);
}

}  // namespace microphun
