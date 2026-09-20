#include "analysis.h"

#include <algorithm>
#include <cmath>

namespace microphun {
namespace {

constexpr float kLowHz = 40.0f;
constexpr float kHighHz = 16000.0f;

// Bands rise fast and fall slow: a bar that tracked the decay exactly would
// flicker, and the eye reads the slow fall as the note ringing out.
constexpr float kAttack = 0.55f;
constexpr float kDecay = 0.12f;

constexpr float kOnsetDecay = 0.10f;

// Magnitudes are mapped through this dB floor so quiet detail stays visible.
constexpr float kFloorDb = -70.0f;

float toNormalisedDb(float magnitude) {
    const float db = 20.0f * std::log10(std::max(magnitude, 1e-9f));
    return std::clamp((db - kFloorDb) / -kFloorDb, 0.0f, 1.0f);
}

}  // namespace

Analyzer::Analyzer(std::size_t fftSize, int sampleRate)
    : fft_(fftSize),
      sampleRate_(sampleRate),
      bins_(fftSize / 2),
      binHz_(static_cast<float>(sampleRate) / static_cast<float>(fftSize)) {
    magnitudes_.assign(bins_, 0.0f);
    previous_.assign(bins_, 0.0f);

    // Log-spaced band edges, so each band covers a roughly equal musical
    // interval rather than an equal slice of Hz.
    const float ratio = std::log(kHighHz / kLowHz);
    for (std::size_t i = 0; i <= Frame::kBands; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(Frame::kBands);
        const float hz = kLowHz * std::exp(ratio * t);
        edges_[i] = std::min(bins_ - 1, static_cast<std::size_t>(hz / binHz_));
    }
}

float Analyzer::bandEnergy(float lowHz, float highHz) const {
    const auto lo = std::min(bins_ - 1, static_cast<std::size_t>(lowHz / binHz_));
    const auto hi = std::min(bins_ - 1, static_cast<std::size_t>(highHz / binHz_));
    if (hi <= lo) {
        return 0.0f;
    }

    float sum = 0.0f;
    for (std::size_t i = lo; i < hi; ++i) {
        sum += magnitudes_[i];
    }
    return toNormalisedDb(sum / static_cast<float>(hi - lo));
}

void Analyzer::analyze(const float* samples, Frame& out) {
    const std::size_t n = fft_.size();

    float sumSquares = 0.0f;
    for (std::size_t i = 0; i < n; ++i) {
        sumSquares += samples[i] * samples[i];
    }
    const float rms = std::sqrt(sumSquares / static_cast<float>(n));
    levelSmoothed_ += (toNormalisedDb(rms) - levelSmoothed_) * 0.25f;
    out.level = levelSmoothed_;

    fft_.magnitudeSpectrum(samples, magnitudes_.data());

    // Spectral flux: only rises count, so a note starting registers and a note
    // ending does not.
    float flux = 0.0f;
    for (std::size_t i = 0; i < bins_; ++i) {
        flux += std::max(0.0f, magnitudes_[i] - previous_[i]);
    }
    std::copy(magnitudes_.begin(), magnitudes_.end(), previous_.begin());

    const float delta = flux - fluxMean_;
    fluxMean_ += delta * 0.05f;
    fluxVar_ += (delta * delta - fluxVar_) * 0.05f;
    const float threshold = fluxMean_ + 1.5f * std::sqrt(std::max(fluxVar_, 1e-12f));

    if (flux > threshold) {
        onsetEnvelope_ = 1.0f;
    } else {
        onsetEnvelope_ = std::max(0.0f, onsetEnvelope_ - kOnsetDecay);
    }
    out.onset = onsetEnvelope_;

    for (std::size_t b = 0; b < Frame::kBands; ++b) {
        const std::size_t lo = edges_[b];
        const std::size_t hi = std::max(edges_[b + 1], lo + 1);

        float peak = 0.0f;
        for (std::size_t i = lo; i < hi && i < bins_; ++i) {
            peak = std::max(peak, magnitudes_[i]);
        }

        const float target = toNormalisedDb(peak);
        const float rate = target > smoothed_[b] ? kAttack : kDecay;
        smoothed_[b] += (target - smoothed_[b]) * rate;
        out.bands[b] = smoothed_[b];
    }

    out.bass = bandEnergy(40.0f, 250.0f);
    out.mid = bandEnergy(250.0f, 2000.0f);
    out.treble = bandEnergy(2000.0f, 16000.0f);
}

}  // namespace microphun
