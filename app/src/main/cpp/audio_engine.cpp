#include "audio_engine.h"

#include <algorithm>

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "microphun", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "microphun", __VA_ARGS__)

namespace microphun {

AudioEngine::AudioEngine() : ring_(kRingSize) {
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

    // Downmix through a preallocated buffer first: both consumers want a
    // contiguous block, and it keeps the channel arithmetic in one place.
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
            mono_[static_cast<std::size_t>(i)] = sample / static_cast<float>(channels);
        }
        ring_.append(mono_.data(), chunk);
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
    for (int attempt = 0; attempt < kSnapshotTries; ++attempt) {
        switch (ring_.snapshot(scratch_.data(), kFftSize)) {
            case SampleRing::Snapshot::Ok:
                analyzer_->analyze(scratch_.data(), lastFrame_);
                out = lastFrame_;
                return;

            case SampleRing::Snapshot::Filling:
                // Not a full window of audio yet; the stream just started.
                out = Frame{};
                return;

            case SampleRing::Snapshot::Lapped:
                // The window was spliced from two different moments while we
                // copied it, and an FFT of that seam smears energy across every
                // band. Copying again is cheap; a torn frame is a visible flash.
                break;
        }
    }

    // Lapped every time: this thread is far enough behind that the ring turns
    // over faster than it can read it. Repeat the last good frame rather than
    // analyse a seam.
    LOGW("render thread lapped by the audio thread, reusing the last frame");
    out = lastFrame_;
}

}  // namespace microphun
