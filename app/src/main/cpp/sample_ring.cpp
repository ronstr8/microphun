#include "sample_ring.h"

#include <cassert>

namespace microphun {

// The producer is a realtime callback, so a std::atomic that fell back to a
// mutex would be a lock on the audio thread.
static_assert(std::atomic<float>::is_always_lock_free,
              "atomic<float> must be lock free on this target");

SampleRing::SampleRing(std::size_t capacity) : slots_(capacity), mask_(capacity - 1) {
    assert(capacity >= 2 && (capacity & (capacity - 1)) == 0 && "capacity must be a power of two");
    // atomic<float> is not zero-initialised by its default constructor in
    // C++17, and a snapshot can be taken before anything has been appended.
    for (auto& slot : slots_) {
        slot.store(0.0f, std::memory_order_relaxed);
    }
}

void SampleRing::append(const float* samples, std::int32_t count) {
    std::uint64_t cursor = written_.load(std::memory_order_relaxed);
    for (std::int32_t i = 0; i < count; ++i) {
        slots_[cursor & mask_].store(samples[i], std::memory_order_relaxed);
        ++cursor;
    }
    // Release: everything stored above is visible to a consumer that acquires
    // this value, so a sample below the cursor is always fully published.
    written_.store(cursor, std::memory_order_release);
}

SampleRing::Snapshot SampleRing::snapshot(float* out, std::size_t count) const {
    assert(count <= slots_.size() && "a snapshot cannot be larger than the ring");

    const std::uint64_t cursor = written_.load(std::memory_order_acquire);
    if (cursor < count) {
        return Snapshot::Filling;
    }

    const std::uint64_t start = cursor - count;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = slots_[(start + i) & mask_].load(std::memory_order_relaxed);
    }

    // The oldest slot we read is `start`. The producer has clobbered it only if
    // it got a whole ring past it; equality is still safe, since that leaves it
    // poised to write that slot next rather than having written it.
    if (written_.load(std::memory_order_acquire) - start > slots_.size()) {
        return Snapshot::Lapped;
    }
    return Snapshot::Ok;
}

}  // namespace microphun
