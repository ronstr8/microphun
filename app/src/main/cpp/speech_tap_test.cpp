// Host test for SpeechTap: resampling, PCM conversion and the pipe write path.
//
// Not part of the CMake target — SpeechTap is plain C++ apart from its logging,
// so it can be checked without a device:
//
//   g++ -std=c++17 -O2 -I. speech_tap_test.cpp speech_tap.cpp -o /tmp/t -pthread && /tmp/t
#include "speech_tap.h"

#include <unistd.h>
#include <fcntl.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

using microphun::SpeechTap;

namespace {

int inputRate = 48000;
int failures = 0;

void check(bool ok, const char* what, double value) {
    std::printf("%-46s %-4s  (%.4f)\n", what, ok ? "ok" : "FAIL", value);
    if (!ok) ++failures;
}

// Magnitude of `hz` in a block of samples, normalised by length.
double energyAt(const std::vector<float>& samples, double hz, double rate) {
    double re = 0.0, im = 0.0;
    for (std::size_t i = 0; i < samples.size(); ++i) {
        const double phase = 2.0 * M_PI * hz * static_cast<double>(i) / rate;
        re += samples[i] * std::cos(phase);
        im += samples[i] * std::sin(phase);
    }
    return 2.0 * std::sqrt(re * re + im * im) / static_cast<double>(samples.size());
}

double rms(const std::vector<float>& samples) {
    double sum = 0.0;
    for (float s : samples) sum += static_cast<double>(s) * s;
    return samples.empty() ? 0.0 : std::sqrt(sum / static_cast<double>(samples.size()));
}

// Pushes one second of a sine through the tap and returns what came out of the
// pipe, scaled back to -1..1.
std::vector<float> run(double hz, double amplitude) {
    int fds[2];
    if (::pipe(fds) != 0) {
        std::printf("pipe() failed\n");
        ++failures;
        return {};
    }
    // The reader is this thread; keep it non-blocking so a short read is fine.
    ::fcntl(fds[0], F_SETFL, O_NONBLOCK);

    SpeechTap tap;
    if (!tap.start(fds[1], inputRate)) {
        std::printf("tap.start() failed\n");
        ++failures;
        ::close(fds[0]);
        return {};
    }

    std::vector<float> block(480);
    std::vector<float> out;
    std::vector<int16_t> buffer(8192);

    auto drain = [&] {
        while (true) {
            const ssize_t got = ::read(fds[0], buffer.data(), buffer.size() * sizeof(int16_t));
            if (got <= 0) break;
            const std::size_t count = static_cast<std::size_t>(got) / sizeof(int16_t);
            for (std::size_t i = 0; i < count; ++i) {
                out.push_back(static_cast<float>(buffer[i]) / 32767.0f);
            }
        }
    };

    std::size_t n = 0;
    for (int chunk = 0; chunk < inputRate / 480; ++chunk) {
        for (float& sample : block) {
            sample = static_cast<float>(
                amplitude * std::sin(2.0 * M_PI * hz * static_cast<double>(n++) / inputRate));
        }
        tap.push(block.data(), static_cast<int32_t>(block.size()));
        std::this_thread::sleep_for(std::chrono::milliseconds(4));
        drain();
    }

    // Let the writer thread catch up on the tail, then take what is left.
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    drain();
    tap.stop();
    drain();
    ::close(fds[0]);
    return out;
}

}  // namespace

int main() {
    std::printf("-- 1 kHz at 0.5 amplitude, 48 kHz in --\n");
    const std::vector<float> speech = run(1000.0, 0.5);
    check(speech.size() > 15600 && speech.size() < 16400,
          "one second arrives as ~16000 samples", static_cast<double>(speech.size()));
    check(std::abs(rms(speech) - 0.3536) < 0.02, "level is preserved", rms(speech));
    const double at1k = energyAt(speech, 1000.0, SpeechTap::kOutputRate);
    check(std::abs(at1k - 0.5) < 0.05, "1 kHz survives at full amplitude", at1k);
    check(energyAt(speech, 3000.0, SpeechTap::kOutputRate) < 0.01,
          "nothing spurious at 3 kHz", energyAt(speech, 3000.0, SpeechTap::kOutputRate));

    std::printf("\n-- 12 kHz at 0.5 amplitude: would alias to 4 kHz unfiltered --\n");
    const std::vector<float> alias = run(12000.0, 0.5);
    const double at4k = energyAt(alias, 4000.0, SpeechTap::kOutputRate);
    check(at4k < 0.02, "12 kHz does not fold back to 4 kHz", at4k);
    check(rms(alias) < 0.02, "and is rejected overall", rms(alias));

    std::printf("\n-- 1 kHz at 44.1 kHz in: a fractional 2.75625 ratio --\n");
    inputRate = 44100;
    const std::vector<float> odd = run(1000.0, 0.5);
    check(odd.size() > 15600 && odd.size() < 16400,
          "one second still arrives as ~16000 samples", static_cast<double>(odd.size()));
    const double oddAt1k = energyAt(odd, 1000.0, SpeechTap::kOutputRate);
    check(std::abs(oddAt1k - 0.5) < 0.05, "1 kHz survives at full amplitude", oddAt1k);
    check(energyAt(odd, 3000.0, SpeechTap::kOutputRate) < 0.02,
          "no resampling sidebands at 3 kHz", energyAt(odd, 3000.0, SpeechTap::kOutputRate));
    inputRate = 48000;

    std::printf("\n-- nobody reading the pipe --\n");
    int fds[2];
    if (::pipe(fds) == 0) {
        SpeechTap tap;
        tap.start(fds[1], inputRate);
        std::vector<float> noise(4096, 0.25f);
        const auto began = std::chrono::steady_clock::now();
        for (int i = 0; i < 40; ++i) {
            tap.push(noise.data(), static_cast<int32_t>(noise.size()));
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        tap.stop();
        const double seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - began).count();
        // Generous: the point is that stop() returns at all, and an instrumented
        // build (-fsanitize=thread) is several times slower than a plain one.
        check(seconds < 2.0, "writer never blocks on a full pipe", seconds);
        ::close(fds[0]);
    }

    std::printf("\n%s\n", failures == 0 ? "all checks passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
