#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace microphun {

// Lock-free ring of audio samples with one producer and one consumer.
//
// The producer is a realtime audio callback: it must never wait, never
// allocate, and never be asked to slow down for the consumer. The consumer
// wants a *snapshot* — the newest `count` samples — and does not care about
// anything older, so the producer is free to overwrite as it goes.
//
// That freedom is what makes the two threads touch the same slot: the consumer
// copies the newest window while the producer keeps appending, and their
// indices collide once the producer wraps around. Hence:
//
//   - the slots are atomic, so the overlap is a defined access rather than a
//     data race (relaxed; `written_` already orders the data, and on arm64
//     these are the same instructions a plain float would compile to), and
//   - `snapshot` re-reads the write cursor afterwards and reports Lapped if
//     the producer wrapped onto the window mid-copy, because such a window is
//     two pieces of audio spliced together.
//
// Detecting rather than preventing is deliberate. Making the producer wait for
// the consumer would stall live audio to protect stale samples.
class SampleRing {
public:
    enum class Snapshot {
        Ok,       // the window is whole
        Filling,  // fewer than `count` samples have ever been written
        Lapped,   // the producer wrapped onto the window while it was copied
    };

    // `capacity` must be a power of two, and larger than any snapshot taken
    // from it — the difference is the consumer's headroom.
    explicit SampleRing(std::size_t capacity);

    // Producer only. Never blocks, never allocates.
    void append(const float* samples, std::int32_t count);

    // Consumer only. Copies the newest `count` samples into `out`.
    Snapshot snapshot(float* out, std::size_t count) const;

    // Total samples ever appended.
    std::uint64_t written() const { return written_.load(std::memory_order_acquire); }

    std::size_t capacity() const { return slots_.size(); }

private:
    std::vector<std::atomic<float>> slots_;
    std::size_t mask_;
    std::atomic<std::uint64_t> written_{0};
};

}  // namespace microphun
