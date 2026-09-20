#include <jni.h>
#include <unistd.h>

#include <new>

#include "analysis.h"
#include "audio_engine.h"
#include "speech_tap.h"

using microphun::AudioEngine;
using microphun::Frame;
using microphun::SpeechTap;

namespace {

AudioEngine* asEngine(jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_microphun_audio_AudioEngine_nativeCreate(JNIEnv* env, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) AudioEngine());
}

JNIEXPORT void JNICALL
Java_com_microphun_audio_AudioEngine_nativeDestroy(JNIEnv* env, jobject, jlong handle) {
    delete asEngine(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_microphun_audio_AudioEngine_nativeStart(JNIEnv* env, jobject, jlong handle) {
    auto* engine = asEngine(handle);
    return engine != nullptr && engine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_microphun_audio_AudioEngine_nativeStop(JNIEnv* env, jobject, jlong handle) {
    if (auto* engine = asEngine(handle)) {
        engine->stop();
    }
}

// Fills `bands` with Frame::kBands values and `scalars` with
// [level, bass, mid, treble, onset]. Called once per rendered frame.
JNIEXPORT void JNICALL
Java_com_microphun_audio_AudioEngine_nativeFrame(JNIEnv* env,
                                              jobject,
                                              jlong handle,
                                              jfloatArray bands,
                                              jfloatArray scalars) {
    auto* engine = asEngine(handle);
    if (engine == nullptr) {
        return;
    }

    Frame frame;
    engine->frame(frame);

    const auto bandCount = static_cast<jsize>(Frame::kBands);
    if (env->GetArrayLength(bands) >= bandCount) {
        env->SetFloatArrayRegion(bands, 0, bandCount, frame.bands.data());
    }

    const float packed[5] = {frame.level, frame.bass, frame.mid, frame.treble, frame.onset};
    if (env->GetArrayLength(scalars) >= 5) {
        env->SetFloatArrayRegion(scalars, 0, 5, packed);
    }
}

JNIEXPORT jint JNICALL
Java_com_microphun_audio_AudioEngine_nativeBandCount(JNIEnv* env, jobject) {
    return static_cast<jint>(Frame::kBands);
}

// Starts teeing capture into `fd`, the write end of a pipe whose read end goes
// to the recogniser. Ownership of the descriptor passes to native code here,
// including when this fails.
JNIEXPORT jboolean JNICALL
Java_com_microphun_audio_AudioEngine_nativeStartSpeechTap(JNIEnv* env,
                                                         jobject,
                                                         jlong handle,
                                                         jint fd) {
    auto* engine = asEngine(handle);
    if (engine == nullptr) {
        ::close(fd);
        return JNI_FALSE;
    }
    return engine->startSpeechTap(fd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_microphun_audio_AudioEngine_nativeStopSpeechTap(JNIEnv* env, jobject, jlong handle) {
    if (auto* engine = asEngine(handle)) {
        engine->stopSpeechTap();
    }
}

JNIEXPORT jint JNICALL
Java_com_microphun_audio_AudioEngine_nativeSpeechRate(JNIEnv* env, jobject) {
    return static_cast<jint>(SpeechTap::kOutputRate);
}

}  // extern "C"
