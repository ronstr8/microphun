#pragma once

#include <array>
#include <cstddef>
#include <vector>

#include "fft.h"

namespace microphun {

// Everything the renderer needs to draw one frame.
struct Frame {
    static constexpr std::size_t kBands = 24;

    std::array<float, kBands> bands{};  // normalised 0..1, log-spaced
    float level = 0.0f;                 // broadband RMS, 0..1
    float bass = 0.0f;                  // 40-250 Hz
    float mid = 0.0f;                   // 250-2000 Hz
    float treble = 0.0f;                // 2-16 kHz
    float onset = 0.0f;                 // beat envelope, spikes to 1 then decays
};

// Turns a window of PCM into a Frame. Not thread safe: drive it from one
// thread only (in microphun, the GL thread).
class Analyzer {
public:
    Analyzer(std::size_t fftSize, int sampleRate);

    void analyze(const float* samples, Frame& out);

private:
    float bandEnergy(float lowHz, float highHz) const;

    Fft fft_;
    int sampleRate_;
    std::size_t bins_;
    float binHz_;

    std::vector<float> magnitudes_;
    std::vector<float> previous_;
    std::array<std::size_t, Frame::kBands + 1> edges_{};
    std::array<float, Frame::kBands> smoothed_{};

    // Spectral flux running stats, for an onset threshold that adapts to how
    // loud and how dense the material is.
    float fluxMean_ = 0.0f;
    float fluxVar_ = 1.0f;
    float onsetEnvelope_ = 0.0f;
    float levelSmoothed_ = 0.0f;
};

}  // namespace microphun
