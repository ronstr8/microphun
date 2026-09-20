// Host test for SampleRing: the single-threaded contract, and the race the
// lap check exists to catch.
//
// Not part of the CMake target — SampleRing has no Android dependencies:
//
//   g++ -std=c++17 -O2 -I. sample_ring_test.cpp sample_ring.cpp -o /tmp/t -pthread && /tmp/t
//
// And again under ThreadSanitizer, which is what proves the accesses are
// defined rather than merely lucky (needs ASLR off on some systems):
//
//   g++ -std=c++17 -O1 -g -fsanitize=thread -I. sample_ring_test.cpp sample_ring.cpp -o /tmp/t -pthread
//   setarch $(uname -m) -R /tmp/t
#include "sample_ring.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>

#if defined(__SANITIZE_THREAD__)
#define UNDER_TSAN 1
#elif defined(__has_feature)
#if __has_feature(thread_sanitizer)
#define UNDER_TSAN 1
#endif
#endif

using microphun::SampleRing;
using Snapshot = SampleRing::Snapshot;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%-58s %s\n", what, ok ? "ok" : "FAIL");
    if (!ok) ++failures;
}

void report(const char* what, long long value) {
    std::printf("%-58s %lld\n", what, value);
}

// A window is whole if it is a run of consecutive counter values. The writer
// appends 1, 2, 3, ..., so a window spliced from two moments shows a jump.
bool contiguous(const std::vector<float>& window) {
    for (std::size_t i = 1; i < window.size(); ++i) {
        if (window[i] != window[i - 1] + 1.0f) return false;
    }
    return true;
}

void single_threaded() {
    std::printf("\n-- the contract, one thread --\n");
    SampleRing ring(16);
    std::vector<float> out(8, -1.0f);

    check(ring.snapshot(out.data(), 8) == Snapshot::Filling, "an empty ring reports Filling");

    std::vector<float> block(8);
    for (int i = 0; i < 8; ++i) block[i] = static_cast<float>(i + 1);   // 1..8
    ring.append(block.data(), 8);

    check(ring.snapshot(out.data(), 8) == Snapshot::Ok, "a just-filled ring reports Ok");
    check(out[0] == 1.0f && out[7] == 8.0f, "the window holds every sample in order");
    check(ring.written() == 8, "the write cursor counts every sample");

    // Past the end of the ring: the oldest samples are gone, the newest remain.
    for (int i = 0; i < 8; ++i) block[i] = static_cast<float>(i + 9);   // 9..16
    ring.append(block.data(), 8);
    for (int i = 0; i < 8; ++i) block[i] = static_cast<float>(i + 17);  // 17..24
    ring.append(block.data(), 8);

    check(ring.snapshot(out.data(), 8) == Snapshot::Ok, "wrapping still reports Ok");
    check(out[0] == 17.0f && out[7] == 24.0f, "and hands back the newest window");
}

// The real test: a writer that never waits against a reader taking snapshots.
//
// Headroom — the gap between the ring and the window — decides how often the
// writer laps the reader, so it is a parameter: one run shaped like the app,
// one squeezed until laps are routine.
struct Stats {
    long long accepted = 0;
    long long lapped = 0;
    long long torn_accepted = 0;
};

Stats contend(std::size_t capacity, std::size_t window, std::size_t chunk, int millis) {
    constexpr long long kMaxSamples = 4'000'000;  // floats hold integers exactly to 2^24

    SampleRing ring(capacity);
    std::atomic<bool> running{true};

    std::thread writer([&] {
        std::vector<float> block(chunk);
        long long n = 0;
        while (running.load(std::memory_order_relaxed) && n < kMaxSamples) {
            for (std::size_t i = 0; i < chunk; ++i) block[i] = static_cast<float>(++n);
            ring.append(block.data(), static_cast<std::int32_t>(chunk));
        }
    });

    Stats stats;
    std::vector<float> buffer(window);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(millis);
    while (std::chrono::steady_clock::now() < deadline) {
        switch (ring.snapshot(buffer.data(), window)) {
            case Snapshot::Ok:
                ++stats.accepted;
                if (!contiguous(buffer)) ++stats.torn_accepted;
                break;
            case Snapshot::Lapped:
                ++stats.lapped;
                break;
            case Snapshot::Filling:
                break;
        }
    }
    running.store(false, std::memory_order_relaxed);
    writer.join();
    return stats;
}

void under_contention() {
    // Shaped like the app: an 8192-slot ring read 2048 samples at a time.
    std::printf("\n-- a writer that never waits, with the app's headroom --\n");
    Stats roomy = contend(8192, 2048, 256, 400);
    report("windows accepted", roomy.accepted);
    report("windows rejected as lapped", roomy.lapped);
    check(roomy.accepted > 0, "the reader gets usable windows");
    check(roomy.torn_accepted == 0, "every accepted window was whole, not spliced");

    // Squeezed until the writer laps constantly, so the check is exercised
    // rather than merely present. This is the 128ms renderer stall, provoked.
    std::printf("\n-- the same, squeezed until laps are routine --\n");
    Stats tight = contend(1024, 896, 128, 400);
    report("windows accepted", tight.accepted);
    report("windows rejected as lapped", tight.lapped);
    check(tight.lapped > 0, "the lap check fires, so this test can see the bug");
    check(tight.torn_accepted == 0, "still, nothing spliced was ever accepted");
}

#if !defined(UNDER_TSAN)
// Control group: the ring as it was before the fix — plain floats, and every
// window accepted. Without it, "no torn windows" above could just mean the
// test never provokes one. Skipped under ThreadSanitizer, where its data race
// is the very thing being demonstrated and would drown out the real signal.
struct LegacyRing {
    explicit LegacyRing(std::size_t capacity) : slots(capacity, 0.0f), mask(capacity - 1) {}

    void append(const float* samples, std::int32_t count) {
        std::uint64_t cursor = written.load(std::memory_order_relaxed);
        for (std::int32_t i = 0; i < count; ++i) slots[cursor++ & mask] = samples[i];
        written.store(cursor, std::memory_order_release);
    }

    bool snapshot(float* out, std::size_t count) const {
        const std::uint64_t cursor = written.load(std::memory_order_acquire);
        if (cursor < count) return false;
        const std::uint64_t start = cursor - count;
        for (std::size_t i = 0; i < count; ++i) out[i] = slots[(start + i) & mask];
        return true;   // no lap check: this is the bug
    }

    std::vector<float> slots;
    std::size_t mask;
    std::atomic<std::uint64_t> written{0};
};

void control_group() {
    std::printf("\n-- control: the same race without the lap check --\n");

    constexpr std::size_t kCapacity = 1024;
    constexpr std::size_t kWindow = 896;
    constexpr std::size_t kChunk = 128;

    LegacyRing ring(kCapacity);
    std::atomic<bool> running{true};

    std::thread writer([&] {
        float block[kChunk];
        long long n = 0;
        while (running.load(std::memory_order_relaxed) && n < 4'000'000) {
            for (std::size_t i = 0; i < kChunk; ++i) block[i] = static_cast<float>(++n);
            ring.append(block, static_cast<std::int32_t>(kChunk));
        }
    });

    std::vector<float> window(kWindow);
    long long accepted = 0, torn = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(400);
    while (std::chrono::steady_clock::now() < deadline) {
        if (ring.snapshot(window.data(), kWindow)) {
            ++accepted;
            if (!contiguous(window)) ++torn;
        }
    }
    running.store(false, std::memory_order_relaxed);
    writer.join();

    report("windows accepted", accepted);
    report("of those, spliced from two moments", torn);
    check(torn > 0, "the old ring hands back torn windows, as expected");
}
#endif

}  // namespace

int main() {
    single_threaded();
    under_contention();
#if !defined(UNDER_TSAN)
    control_group();
#else
    std::printf("\n-- control group skipped under ThreadSanitizer --\n");
#endif
    std::printf("\n%s\n", failures == 0 ? "all checks passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
