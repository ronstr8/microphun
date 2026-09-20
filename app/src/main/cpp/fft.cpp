#include "fft.h"

#include <cmath>

namespace microphun {
namespace {

constexpr float kPi = 3.14159265358979323846f;

std::size_t reverseBits(std::size_t value, std::size_t bits) {
    std::size_t out = 0;
    for (std::size_t i = 0; i < bits; ++i) {
        out = (out << 1) | (value & 1);
        value >>= 1;
    }
    return out;
}

}  // namespace

Fft::Fft(std::size_t size) : size_(size), log2Size_(0) {
    while ((static_cast<std::size_t>(1) << log2Size_) < size_) {
        ++log2Size_;
    }

    window_.resize(size_);
    for (std::size_t i = 0; i < size_; ++i) {
        window_[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * static_cast<float>(i) /
                                             static_cast<float>(size_ - 1)));
    }

    reversed_.resize(size_);
    for (std::size_t i = 0; i < size_; ++i) {
        reversed_[i] = reverseBits(i, log2Size_);
    }

    // Twiddles for the largest butterfly; smaller stages stride into this table.
    twiddleRe_.resize(size_ / 2);
    twiddleIm_.resize(size_ / 2);
    for (std::size_t i = 0; i < size_ / 2; ++i) {
        const float angle = -2.0f * kPi * static_cast<float>(i) / static_cast<float>(size_);
        twiddleRe_[i] = std::cos(angle);
        twiddleIm_[i] = std::sin(angle);
    }

    re_.resize(size_);
    im_.resize(size_);
}

void Fft::magnitudeSpectrum(const float* input, float* magnitudes) {
    for (std::size_t i = 0; i < size_; ++i) {
        const std::size_t src = reversed_[i];
        re_[i] = input[src] * window_[src];
        im_[i] = 0.0f;
    }

    for (std::size_t stageSize = 2; stageSize <= size_; stageSize <<= 1) {
        const std::size_t half = stageSize / 2;
        const std::size_t stride = size_ / stageSize;

        for (std::size_t base = 0; base < size_; base += stageSize) {
            for (std::size_t k = 0; k < half; ++k) {
                const float wr = twiddleRe_[k * stride];
                const float wi = twiddleIm_[k * stride];

                const std::size_t lo = base + k;
                const std::size_t hi = lo + half;

                const float tr = re_[hi] * wr - im_[hi] * wi;
                const float ti = re_[hi] * wi + im_[hi] * wr;

                re_[hi] = re_[lo] - tr;
                im_[hi] = im_[lo] - ti;
                re_[lo] += tr;
                im_[lo] += ti;
            }
        }
    }

    const std::size_t bins = size_ / 2;
    const float scale = 2.0f / static_cast<float>(size_);
    for (std::size_t i = 0; i < bins; ++i) {
        magnitudes[i] = std::sqrt(re_[i] * re_[i] + im_[i] * im_[i]) * scale;
    }
}

}  // namespace microphun
