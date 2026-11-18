#include <jni.h>
#include <MaaFramework/MaaAPI.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <condition_variable>
#include <mutex>

namespace {
void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
struct Session {
    std::unique_ptr<MaaResource, decltype(&MaaResourceDestroy)> resource{nullptr, MaaResourceDestroy};
    // Declared after resource: tasker is destroyed first, including its worker and cached references.
    std::unique_ptr<MaaTasker, decltype(&MaaTaskerDestroy)> tasker{nullptr, MaaTaskerDestroy};
    explicit Session(const char* model_dir) {
        MaaLoggingLevel off = MaaLoggingLevel_Off;
        bool no = false;
        size_t zero = 0;
        require(MaaGlobalSetOption(MaaGlobalOption_StdoutLevel, &off, sizeof(off)), "Maa log option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_DebugMode, &no, sizeof(no)), "Maa debug option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_SaveDraw, &no, sizeof(no)), "Maa draw option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_SaveOnError, &no, sizeof(no)), "Maa error-image option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_RecoImageCacheLimit, &zero, sizeof(zero)), "Maa cache option rejected");
        // Never initialize file logging or a controller in this experiment.
        resource.reset(MaaResourceCreate());
        require(resource != nullptr, "Maa resource allocation failed");
        MaaInferenceExecutionProvider provider = MaaInferenceExecutionProvider_CPU;
        require(MaaResourceSetOption(resource.get(), MaaResOption_InferenceExecutionProvider, &provider, sizeof(provider)), "Maa CPU selection failed");
        auto load = MaaResourcePostOcrModel(resource.get(), model_dir);
        require(load != MaaInvalidId && MaaResourceWait(resource.get(), load) == MaaStatus_Succeeded, "Maa model resource registration failed");
        tasker.reset(MaaTaskerCreate());
        require(tasker && MaaTaskerBindResource(tasker.get(), resource.get()) && MaaTaskerInited(tasker.get()), "Maa resource binding failed");
        require(MaaTaskerGetController(tasker.get()) == nullptr, "Unexpected Maa controller");
    }
    std::string recognize(std::vector<uint8_t>& png) {
        auto image = std::unique_ptr<MaaImageBuffer, decltype(&MaaImageBufferDestroy)>(MaaImageBufferCreate(), MaaImageBufferDestroy);
        require(image && MaaImageBufferSetEncoded(image.get(), png.data(), png.size()), "Maa image buffer rejected");
        require(MaaImageBufferWidth(image.get()) > 0 && MaaImageBufferHeight(image.get()) > 0, "Maa image decode failed");
        return recognizeImage(image.get());
    }
    std::string recognizeImage(const MaaImageBuffer* image) {
        auto task = MaaTaskerPostRecognition(tasker.get(), "OCR", "{}", image);
        require(task != MaaInvalidId, "Maa recognition submission rejected");
        auto status = MaaTaskerWait(tasker.get(), task);
        require(status == MaaStatus_Succeeded, "Maa recognition transport did not succeed");
        auto name = std::unique_ptr<MaaStringBuffer, decltype(&MaaStringBufferDestroy)>(MaaStringBufferCreate(), MaaStringBufferDestroy);
        auto algorithm = std::unique_ptr<MaaStringBuffer, decltype(&MaaStringBufferDestroy)>(MaaStringBufferCreate(), MaaStringBufferDestroy);
        auto detail = std::unique_ptr<MaaStringBuffer, decltype(&MaaStringBufferDestroy)>(MaaStringBufferCreate(), MaaStringBufferDestroy);
        require(name && algorithm && detail, "Maa result allocation failed");
        MaaSize count = 0;
        require(MaaTaskerGetTaskDetail(tasker.get(), task, name.get(), nullptr, &count, &status) && count == 1, "Maa recognition node count changed");
        MaaNodeId node = MaaInvalidId;
        require(MaaTaskerGetTaskDetail(tasker.get(), task, name.get(), &node, &count, &status), "Maa task detail unavailable");
        MaaRecoId reco = MaaInvalidId;
        MaaActId action = MaaInvalidId;
        MaaBool completed = false;
        require(MaaTaskerGetNodeDetail(tasker.get(), node, name.get(), &reco, &action, &completed) && reco != MaaInvalidId && action == MaaInvalidId, "Maa recognition detail identity changed");
        MaaBool hit = false;
        require(MaaTaskerGetRecognitionDetail(tasker.get(), reco, name.get(), algorithm.get(), &hit, nullptr, detail.get(), nullptr, nullptr), "Maa recognition detail unavailable");
        require(std::string(MaaStringBufferGet(algorithm.get())) == "OCR", "Unexpected Maa recognition algorithm");
        require(MaaTaskerGetController(tasker.get()) == nullptr, "Unexpected Maa controller");
        std::string result = std::string("{\"taskStatus\":") + std::to_string(status) + ",\"hit\":" + (hit ? "true" : "false") +
            ",\"controllerBound\":false,\"detail\":" + std::string(MaaStringBufferGet(detail.get()), MaaStringBufferSize(detail.get())) + "}";
        require(MaaTaskerClearCache(tasker.get()), "Maa result cache clear failed");
        // A succeeded Maa task is transport completion; it is not a business success or proof that a no-hit is healthy.
        return result;
    }
    void overrideTemplate(const std::string& name, std::vector<uint8_t>& png) {
        auto image = std::unique_ptr<MaaImageBuffer, decltype(&MaaImageBufferDestroy)>(MaaImageBufferCreate(), MaaImageBufferDestroy);
        require(image && MaaImageBufferSetEncoded(image.get(), png.data(), png.size()), "Maa template buffer rejected");
        require(MaaImageBufferWidth(image.get()) > 0 && MaaImageBufferHeight(image.get()) > 0, "Maa template decode failed");
        require(MaaResourceOverrideImage(resource.get(), name.c_str(), image.get()), "Maa template override rejected");
    }
    /** One region request on caller-supplied pixels. Only OCR and TemplateMatch are reachable. */
    std::string inspect(const std::string& type, const std::string& param, std::vector<uint8_t>& png) {
        auto image = std::unique_ptr<MaaImageBuffer, decltype(&MaaImageBufferDestroy)>(MaaImageBufferCreate(), MaaImageBufferDestroy);
        require(image && MaaImageBufferSetEncoded(image.get(), png.data(), png.size()), "Maa image buffer rejected");
        require(MaaImageBufferWidth(image.get()) > 0 && MaaImageBufferHeight(image.get()) > 0, "Maa image decode failed");
        return inspectImage(type, param, image.get());
    }
    std::string inspectImage(const std::string& type, const std::string& param, const MaaImageBuffer* image) {
        require(type == "OCR" || type == "TemplateMatch", "Unsupported Maa recognition type");
        auto task = MaaTaskerPostRecognition(tasker.get(), type.c_str(), param.c_str(), image);
        require(task != MaaInvalidId, "Maa recognition submission rejected");
        auto status = MaaTaskerWait(tasker.get(), task);
        require(status == MaaStatus_Succeeded, "Maa recognition transport did not succeed");
        auto name = std::unique_ptr<MaaStringBuffer, decltype(&MaaStringBufferDestroy)>(MaaStringBufferCreate(), MaaStringBufferDestroy);
        auto algorithm = std::unique_ptr<MaaStringBuffer, decltype(&MaaStringBufferDestroy)>(MaaStringBufferCreate(), MaaStringBufferDestroy);
        auto detail = std::unique_ptr<MaaStringBuffer, decltype(&MaaStringBufferDestroy)>(MaaStringBufferCreate(), MaaStringBufferDestroy);
        require(name && algorithm && detail, "Maa result allocation failed");
        MaaSize count = 0;
        require(MaaTaskerGetTaskDetail(tasker.get(), task, name.get(), nullptr, &count, &status) && count == 1, "Maa recognition node count changed");
        MaaNodeId node = MaaInvalidId;
        require(MaaTaskerGetTaskDetail(tasker.get(), task, name.get(), &node, &count, &status), "Maa task detail unavailable");
        MaaRecoId reco = MaaInvalidId;
        MaaActId action = MaaInvalidId;
        MaaBool completed = false;
        require(MaaTaskerGetNodeDetail(tasker.get(), node, name.get(), &reco, &action, &completed) && reco != MaaInvalidId && action == MaaInvalidId, "Maa recognition detail identity changed");
        MaaBool hit = false;
        require(MaaTaskerGetRecognitionDetail(tasker.get(), reco, name.get(), algorithm.get(), &hit, nullptr, detail.get(), nullptr, nullptr), "Maa recognition detail unavailable");
        require(std::string(MaaStringBufferGet(algorithm.get())) == type, "Unexpected Maa recognition algorithm");
        require(MaaTaskerGetController(tasker.get()) == nullptr, "Unexpected Maa controller");
        std::string body(MaaStringBufferGet(detail.get()), MaaStringBufferSize(detail.get()));
        std::string result = std::string("{\"taskStatus\":") + std::to_string(status) + ",\"hit\":" + (hit ? "true" : "false") +
            ",\"algorithm\":\"" + type + "\",\"detail\":" + (body.empty() ? "null" : body) + "}";
        require(MaaTaskerClearCache(tasker.get()), "Maa result cache clear failed");
        return result;
    }
};
using ImagePtr = std::unique_ptr<MaaImageBuffer, decltype(&MaaImageBufferDestroy)>;
/**
 * Raw RGBA (Android ARGB_8888 memory order) to the BGR 8UC3 buffer Maa expects. No encode/decode.
 * The BGR bytes live as long as the buffer in case the buffer refers to them instead of copying.
 */
struct RawImage {
    std::vector<uint8_t> bgr;
    ImagePtr image{nullptr, MaaImageBufferDestroy};
};
std::unique_ptr<RawImage> rawImage(const std::vector<uint8_t>& rgba, int width, int height) {
    require(width > 0 && height > 0 && rgba.size() == static_cast<size_t>(width) * height * 4, "Raw image size mismatch");
    auto raw = std::make_unique<RawImage>();
    raw->bgr.resize(static_cast<size_t>(width) * height * 3);
    for (size_t i = 0, j = 0; i < rgba.size(); i += 4, j += 3) { raw->bgr[j] = rgba[i + 2]; raw->bgr[j + 1] = rgba[i + 1]; raw->bgr[j + 2] = rgba[i]; }
    raw->image.reset(MaaImageBufferCreate());
    require(raw->image && MaaImageBufferSetRawData(raw->image.get(), raw->bgr.data(), width, height, 16 /* CV_8UC3 */), "Maa raw image rejected");
    require(MaaImageBufferWidth(raw->image.get()) == width && MaaImageBufferHeight(raw->image.get()) == height, "Maa raw image size changed");
    return raw;
}
std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray encoded) {
    auto count = env->GetArrayLength(encoded);
    require(count > 0, "Empty encoded image");
    std::vector<uint8_t> value(count);
    env->GetByteArrayRegion(encoded, 0, count, reinterpret_cast<jbyte*>(value.data()));
    require(!env->ExceptionCheck(), "Image copy failed");
    return value;
}
std::string utf(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    require(chars != nullptr, "String copy failed");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_version(JNIEnv* env, jobject) {
    const char* version = MaaVersion();
    if (!version) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Maa returned no version");
        return nullptr;
    }
    return env->NewStringUTF(version);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_create(JNIEnv* env, jobject, jstring directory) {
    const char* path = env->GetStringUTFChars(directory, nullptr);
    if (!path) return 0;
    try {
        auto session = std::make_unique<Session>(path);
        env->ReleaseStringUTFChars(directory, path);
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        env->ReleaseStringUTFChars(directory, path);
        fail(env, error);
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_recognizeRaw(JNIEnv* env, jobject, jlong handle, jbyteArray encoded) {
    try {
        require(handle != 0, "Maa session closed");
        auto count = env->GetArrayLength(encoded);
        require(count > 0, "Empty encoded image");
        std::vector<uint8_t> image(count);
        env->GetByteArrayRegion(encoded, 0, count, reinterpret_cast<jbyte*>(image.data()));
        if (env->ExceptionCheck()) return nullptr;
        auto text = reinterpret_cast<Session*>(handle)->recognize(image);
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return result;
    } catch (const std::exception& error) {
        fail(env, error);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_overrideTemplate(JNIEnv* env, jobject, jlong handle, jstring name, jbyteArray encoded) {
    try {
        require(handle != 0, "Maa session closed");
        auto png = bytes(env, encoded);
        reinterpret_cast<Session*>(handle)->overrideTemplate(utf(env, name), png);
    } catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_inspectRaw(JNIEnv* env, jobject, jlong handle, jstring type, jstring param, jbyteArray encoded) {
    try {
        require(handle != 0, "Maa session closed");
        auto png = bytes(env, encoded);
        auto text = reinterpret_cast<Session*>(handle)->inspect(utf(env, type), utf(env, param), png);
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_recognizePixels(JNIEnv* env, jobject, jlong handle, jbyteArray rgba, jint width, jint height) {
    try {
        require(handle != 0, "Maa session closed");
        auto image = rawImage(bytes(env, rgba), width, height);
        auto text = reinterpret_cast<Session*>(handle)->recognizeImage(image->image.get());
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_inspectPixels(JNIEnv* env, jobject, jlong handle, jstring type, jstring param, jbyteArray rgba, jint width, jint height) {
    try {
        require(handle != 0, "Maa session closed");
        auto image = rawImage(bytes(env, rgba), width, height);
        auto text = reinterpret_cast<Session*>(handle)->inspectImage(utf(env, type), utf(env, param), image->image.get());
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yysassist_vision_maa_MaaNative_blockForProbe(JNIEnv* env, jobject, jobject entered) {
    // An explicit test-only hang. No signal handler, fabricated recognition result or game action.
    auto type = env->GetObjectClass(entered);
    auto run = env->GetMethodID(type, "run", "()V");
    if (!run) return;
    env->CallVoidMethod(entered, run);
    if (env->ExceptionCheck()) return;
    std::mutex mutex;
    std::condition_variable condition;
    std::unique_lock<std::mutex> lock(mutex);
    condition.wait(lock, [] { return false; });
}
