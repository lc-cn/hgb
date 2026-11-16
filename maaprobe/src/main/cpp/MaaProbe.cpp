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
        auto task = MaaTaskerPostRecognition(tasker.get(), "OCR", "{}", image.get());
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
};
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_yysassist_maaprobe_MaaNative_version(JNIEnv* env, jobject) {
    const char* version = MaaVersion();
    if (!version) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Maa returned no version");
        return nullptr;
    }
    return env->NewStringUTF(version);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_yysassist_maaprobe_MaaNative_create(JNIEnv* env, jobject, jstring directory) {
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
Java_io_github_yysassist_maaprobe_MaaNative_recognizeRaw(JNIEnv* env, jobject, jlong handle, jbyteArray encoded) {
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
Java_io_github_yysassist_maaprobe_MaaNative_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yysassist_maaprobe_MaaNative_blockForProbe(JNIEnv* env, jobject, jobject entered) {
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
