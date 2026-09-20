cmake_minimum_required(VERSION 3.22.1)
project(microphun LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(microphun SHARED
    fft.cpp
    analysis.cpp
    sample_ring.cpp
    audio_engine.cpp
    speech_tap.cpp
    jni_bridge.cpp
)

target_compile_options(microphun PRIVATE -Wall -Wextra -Wno-unused-parameter)

# Android 15+ runs 16 KB memory pages on newer arm64 hardware, and a library
# whose LOAD segments are 4 KB aligned will not load there at all. Verify with:
#   llvm-readelf -l libmicrophun.so | grep LOAD   # must show 0x4000
target_link_options(microphun PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

target_link_libraries(microphun
    PRIVATE
        oboe::oboe
        android
        log
)
