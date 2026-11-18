// Cloud Canvas (#1809): libcanvasaudio.so, the JNI side of the Play page's SoundFont engine and of its Oboe output.
//
//  - SoundFont (audio/SoundFont.kt::SfNative): FluidSynth through sf_core, the bank read IN PLACE from the APK (the asset is stored, so
//    AAsset seeks are cheap) through FluidSynth's sfloader callbacks over AAssetManager: no 32 MB copy to storage.
//  - Output (audio/AudioOut.kt::OboeNative): one Oboe stream, LowLatency + Exclusive + float + stereo at the rate asked (the device's
//    native rate). The Kotlin render thread writes blocks into a single-producer single-consumer ring; the Oboe data callback only
//    copies out of it (no lock, no allocation, no JNI on the audio thread) and plays silence on an under-run, which it counts.
//    The latency the stream reports (calculateLatencyMillis) plus the ring's fill is logged once under the tag CanvasAudio and handed
//    back to Kotlin, which shows it on the Play page and writes files/audio-latency.txt (the emulator job reads it).
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "sf_core.h"

#define TAG "CanvasAudio"

namespace {

// ── the bank, read from the APK ───────────────────────────────────────────────────────────────────────────────────────────────
AAssetManager *gAssets = nullptr;
std::string gLastError;
constexpr const char *kAssetPrefix = "asset:";

void *assetOpen(const char *filename) {
    if (!gAssets || std::strncmp(filename, kAssetPrefix, std::strlen(kAssetPrefix)) != 0) return nullptr;
    return AAssetManager_open(gAssets, filename + std::strlen(kAssetPrefix), AASSET_MODE_RANDOM);
}
int assetRead(void *buf, fluid_long_long_t count, void *h) {
    auto *a = static_cast<AAsset *>(h);
    char *p = static_cast<char *>(buf);
    while (count > 0) {
        int n = AAsset_read(a, p, (size_t) std::min<fluid_long_long_t>(count, 1 << 20));
        if (n <= 0) return FLUID_FAILED;
        p += n; count -= n;
    }
    return FLUID_OK;
}
int assetSeek(void *h, fluid_long_long_t offset, int origin) {
    return AAsset_seek64(static_cast<AAsset *>(h), (off64_t) offset, origin) < 0 ? FLUID_FAILED : FLUID_OK;
}
fluid_long_long_t assetTell(void *h) {
    auto *a = static_cast<AAsset *>(h);
    return (fluid_long_long_t) (AAsset_getLength64(a) - AAsset_getRemainingLength64(a));
}
int assetClose(void *h) { AAsset_close(static_cast<AAsset *>(h)); return FLUID_OK; }

fluid_sfloader_t *assetLoader(fluid_settings_t *st) {
    fluid_sfloader_t *l = new_fluid_defsfloader(st);
    if (l) fluid_sfloader_set_callbacks(l, assetOpen, assetRead, assetSeek, assetTell, assetClose);
    return l;
}

std::string str(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

// ── the output ────────────────────────────────────────────────────────────────────────────────────────────────────────────────
class Out : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    std::shared_ptr<oboe::AudioStream> stream;
    std::vector<float> ring;      // interleaved stereo
    int32_t capacity = 0;         // frames
    std::atomic<int64_t> written{0}, read{0};
    std::atomic<int64_t> underruns{0};
    std::atomic<bool> dead{false};
    bool logged = false;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData, int32_t numFrames) override {
        auto *out = static_cast<float *>(audioData);
        int64_t r = read.load(std::memory_order_relaxed);
        int64_t avail = written.load(std::memory_order_acquire) - r;
        int32_t n = (int32_t) std::min<int64_t>(avail, numFrames);
        for (int32_t i = 0; i < n; i++) {
            int32_t k = (int32_t) ((r + i) % capacity) * 2;
            out[i * 2] = ring[k];
            out[i * 2 + 1] = ring[k + 1];
        }
        if (n < numFrames) {
            std::memset(out + n * 2, 0, (size_t) (numFrames - n) * 2 * sizeof(float));
            if (written.load(std::memory_order_relaxed) > 0) underruns.fetch_add(1, std::memory_order_relaxed);
        }
        read.store(r + n, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result) override { dead.store(true); }
};

}  // namespace

extern "C" {

// ════ SoundFont ════════════════════════════════════════════════════════════════════════════════════════════════════════════

JNIEXPORT jlong JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_create(JNIEnv *env, jclass, jobject assets, jstring bank, jint rate,
                                                                                jint groups, jint channels, jobjectArray keys, jobjectArray values) {
    gAssets = AAssetManager_fromJava(env, assets);
    canvas::SfConfig cfg;
    cfg.sampleRate = rate;
    cfg.groups = groups;
    cfg.midiChannels = channels;
    jsize n = env->GetArrayLength(keys);
    for (jsize i = 0; i < n; i++) {
        auto k = (jstring) env->GetObjectArrayElement(keys, i);
        auto v = (jstring) env->GetObjectArrayElement(values, i);
        cfg.settings.emplace_back(str(env, k), str(env, v));
        env->DeleteLocalRef(k); env->DeleteLocalRef(v);
    }
    std::string path = std::string(kAssetPrefix) + str(env, bank);
    std::string err;
    canvas::SfSynth *s = canvas::sfCreate(cfg, assetLoader, path.c_str(), &err);
    if (!s) { gLastError = err; __android_log_print(ANDROID_LOG_WARN, TAG, "SoundFont engine unavailable: %s", err.c_str()); }
    else __android_log_print(ANDROID_LOG_INFO, TAG, "FluidSynth %s ready: %s at %d Hz, %d groups", fluid_version_str(), path.c_str(), rate, groups);
    return (jlong) (intptr_t) s;
}

JNIEXPORT jstring JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_lastError(JNIEnv *env, jclass) { return env->NewStringUTF(gLastError.c_str()); }

JNIEXPORT jstring JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_version(JNIEnv *env, jclass) { return env->NewStringUTF(fluid_version_str()); }

JNIEXPORT void JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_destroy(JNIEnv *, jclass, jlong h) {
    canvas::sfDestroy(reinterpret_cast<canvas::SfSynth *>(h));
}

JNIEXPORT jboolean JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_program(JNIEnv *, jclass, jlong h, jint chan, jint bank, jint prog, jboolean drum) {
    return canvas::sfProgram(reinterpret_cast<canvas::SfSynth *>(h), chan, bank, prog, drum) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_presetName(JNIEnv *env, jclass, jlong h, jint bank, jint prog) {
    auto *s = reinterpret_cast<canvas::SfSynth *>(h);
    if (!canvas::sfHasPreset(s, bank, prog)) return nullptr;
    return env->NewStringUTF(canvas::sfPresetName(s, bank, prog).c_str());
}

JNIEXPORT void JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_noteOn(JNIEnv *, jclass, jlong h, jint chan, jint key, jint vel) {
    fluid_synth_noteon(reinterpret_cast<canvas::SfSynth *>(h)->synth, chan, key, vel);
}

JNIEXPORT void JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_noteOff(JNIEnv *, jclass, jlong h, jint chan, jint key) {
    fluid_synth_noteoff(reinterpret_cast<canvas::SfSynth *>(h)->synth, chan, key);
}

JNIEXPORT void JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_cc(JNIEnv *, jclass, jlong h, jint chan, jint num, jint val) {
    fluid_synth_cc(reinterpret_cast<canvas::SfSynth *>(h)->synth, chan, num, val);
}

JNIEXPORT void JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_allNotesOff(JNIEnv *, jclass, jlong h) {
    fluid_synth_all_notes_off(reinterpret_cast<canvas::SfSynth *>(h)->synth, -1);
}

/** Renders [len] frames at [offset] of each of the (2 * groups + 4) planes of [planes] (plane i starts at i * stride), ADDING into them. */
JNIEXPORT jint JNICALL Java_com_diegonmarcos_cloudcanvas_audio_SfNative_process(JNIEnv *env, jclass, jlong h, jint len, jint offset, jint stride, jfloatArray planes) {
    auto *s = reinterpret_cast<canvas::SfSynth *>(h);
    auto *base = static_cast<float *>(env->GetPrimitiveArrayCritical(planes, nullptr));
    if (!base) return -1;
    float *out[64];
    float *fx[canvas::SfSynth::kFx];
    int nout = std::min(2 * s->groups, 64);
    for (int i = 0; i < nout; i++) out[i] = base + (size_t) i * stride + offset;
    for (int i = 0; i < canvas::SfSynth::kFx; i++) fx[i] = base + (size_t) (nout + i) * stride + offset;
    int r = canvas::sfProcess(s, len, out, fx);
    env->ReleasePrimitiveArrayCritical(planes, base, 0);
    return r;
}

// ════ Oboe output ══════════════════════════════════════════════════════════════════════════════════════════════════════════

JNIEXPORT jlong JNICALL Java_com_diegonmarcos_cloudcanvas_audio_OboeNative_open(JNIEnv *, jclass, jint rate, jboolean exclusive, jint ringBursts) {
    auto *o = new Out();
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Game)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(o)
        ->setErrorCallback(o);
    // the engine renders at [rate] (the device's native rate): Oboe converts only if the device grants another rate or format
    b.setFormatConversionAllowed(true)->setChannelConversionAllowed(true);
    if (rate > 0) b.setSampleRate(rate)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
    oboe::Result r = b.openStream(o->stream);
    if (r != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Oboe stream could not be opened: %s", oboe::convertToText(r));
        delete o;
        return 0;
    }
    int32_t burst = std::max(o->stream->getFramesPerBurst(), 32);
    o->stream->setBufferSizeInFrames(burst * 2);
    o->capacity = burst * std::max(ringBursts, 2);
    o->ring.assign((size_t) o->capacity * 2, 0.0f);
    if (o->stream->requestStart() != oboe::Result::OK) { o->stream->close(); delete o; return 0; }
    return (jlong) (intptr_t) o;
}

/** [rate, framesPerBurst, bufferSizeInFrames, ringCapacityFrames, sharing (1 exclusive), performance (1 low latency), format (1 float), api (1 AAudio)]. */
JNIEXPORT jintArray JNICALL Java_com_diegonmarcos_cloudcanvas_audio_OboeNative_info(JNIEnv *env, jclass, jlong h) {
    auto *o = reinterpret_cast<Out *>(h);
    auto &s = o->stream;
    jint v[8] = {s->getSampleRate(), s->getFramesPerBurst(), s->getBufferSizeInFrames(), o->capacity,
                 s->getSharingMode() == oboe::SharingMode::Exclusive, s->getPerformanceMode() == oboe::PerformanceMode::LowLatency,
                 s->getFormat() == oboe::AudioFormat::Float, s->getAudioApi() == oboe::AudioApi::AAudio};
    jintArray a = env->NewIntArray(8);
    env->SetIntArrayRegion(a, 0, 8, v);
    return a;
}

/** Blocks until [frames] interleaved stereo frames of [lr] are in the ring; -1 once the stream is gone (Kotlin then reopens). */
JNIEXPORT jint JNICALL Java_com_diegonmarcos_cloudcanvas_audio_OboeNative_write(JNIEnv *env, jclass, jlong h, jfloatArray lr, jint frames) {
    auto *o = reinterpret_cast<Out *>(h);
    float *src = env->GetFloatArrayElements(lr, nullptr);
    int done = 0;
    while (done < frames) {
        if (o->dead.load()) { env->ReleaseFloatArrayElements(lr, src, JNI_ABORT); return -1; }
        int64_t w = o->written.load(std::memory_order_relaxed);
        int64_t space = o->capacity - (w - o->read.load(std::memory_order_acquire));
        if (space <= 0) { std::this_thread::sleep_for(std::chrono::microseconds(500)); continue; }
        int n = (int) std::min<int64_t>(space, frames - done);
        for (int i = 0; i < n; i++) {
            int32_t k = (int32_t) ((w + i) % o->capacity) * 2;
            o->ring[k] = src[(done + i) * 2];
            o->ring[k + 1] = src[(done + i) * 2 + 1];
        }
        o->written.store(w + n, std::memory_order_release);
        done += n;
    }
    env->ReleaseFloatArrayElements(lr, src, JNI_ABORT);
    return done;
}

/** The output latency in ms: the stream's own estimate (timestamps) plus the ring's current fill; -1 while the stream cannot say yet. */
JNIEXPORT jdouble JNICALL Java_com_diegonmarcos_cloudcanvas_audio_OboeNative_latencyMs(JNIEnv *, jclass, jlong h) {
    auto *o = reinterpret_cast<Out *>(h);
    auto l = o->stream->calculateLatencyMillis();
    double fill = (double) (o->written.load() - o->read.load()) * 1000.0 / std::max(o->stream->getSampleRate(), 1);
    double ms = l ? l.value() + fill : -1.0;
    if (l && !o->logged) {
        o->logged = true;
        __android_log_print(ANDROID_LOG_INFO, TAG, "output latency %.1f ms (stream %.1f + ring %.1f), %d Hz, burst %d, buffer %d, ring %d, %s, %s, %s, api %s",
                            ms, l.value(), fill, o->stream->getSampleRate(), o->stream->getFramesPerBurst(), o->stream->getBufferSizeInFrames(),
                            o->capacity, oboe::convertToText(o->stream->getSharingMode()), oboe::convertToText(o->stream->getPerformanceMode()),
                            oboe::convertToText(o->stream->getFormat()), oboe::convertToText(o->stream->getAudioApi()));
    }
    return ms;
}

JNIEXPORT jlong JNICALL Java_com_diegonmarcos_cloudcanvas_audio_OboeNative_underruns(JNIEnv *, jclass, jlong h) {
    auto *o = reinterpret_cast<Out *>(h);
    auto x = o->stream->getXRunCount();
    return o->underruns.load() + (x ? x.value() : 0);
}

JNIEXPORT void JNICALL Java_com_diegonmarcos_cloudcanvas_audio_OboeNative_close(JNIEnv *, jclass, jlong h) {
    auto *o = reinterpret_cast<Out *>(h);
    if (!o) return;
    o->stream->requestStop();
    o->stream->close();
    delete o;
}

}  // extern "C"
