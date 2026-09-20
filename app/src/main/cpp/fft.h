#pragma once

#include <cstddef>
#include <vector>

namespace microphun {

// In-place iterative radix-2 Cooley-Tukey FFT over a real-valued input.
// Size is fixed at construction and must be a power of two.
class Fft {
public:
    explicit Fft(std::size_t size);

    std::size_t size() const { return size_; }

    // Applies the analysis window to `input` (size() samples), transforms, and
    // writes size()/2 linear magnitudes to `magnitudes`.
    void magnitudeSpectrum(const float* input, float* magnitudes);

private:
    std::size_t size_;
    std::size_t log2Size_;
    std::vector<float> window_;      // Hann
    std::vector<std::size_t> reversed_;
    std::vector<float> twiddleRe_;
    std::vector<float> twiddleIm_;
    std::vector<float> re_;
    std::vector<float> im_;
};

}  // namespace microphun
