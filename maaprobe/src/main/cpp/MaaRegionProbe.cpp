#include <jni.h>
#include <MaaFramework/MaaAPI.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

// Probe-only generic recognition entry: OCR / TemplateMatch / ColorMatch with an explicit ROI.
// No controller, action, pipeline graph or file logging. Production `vision` is unchanged.
namespace {
void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
using Image = std::unique_ptr<MaaImageBuffer, decltype(&MaaImageBufferDestroy)>;
using Text = std::unique_ptr<MaaStringBuffer, decltype(&MaaStringBufferDestroy)>;
Image decode(JNIEnv* env, jbyteArray encoded) {
    auto count = env->GetArrayLength(encoded);
    require(count > 0, "Empty encoded image");
    std::vector<uint8_t> bytes(count);
    env->GetByteArrayRegion(encoded, 0, count, reinterpret_cast<jbyte*>(bytes.data()));
    require(!env->ExceptionCheck(), "Image copy failed");
    Image image(MaaImageBufferCreate(), MaaImageBufferDestroy);
    require(image && MaaImageBufferSetEncoded(image.get(), bytes.data(), bytes.size()), "Maa image buffer rejected");
    require(MaaImageBufferWidth(image.get()) > 0 && MaaImageBufferHeight(image.get()) > 0, "Maa image decode failed");
    return image;
}
std::string utf(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    require(chars != nullptr, "String copy failed");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
struct Session {
    std::unique_ptr<MaaResource, decltype(&MaaResourceDestroy)> resource{nullptr, MaaResourceDestroy};
    std::unique_ptr<MaaTasker, decltype(&MaaTaskerDestroy)> tasker{nullptr, MaaTaskerDestroy};
    explicit Session(const std::string& model_dir) {
        MaaLoggingLevel off = MaaLoggingLevel_Off;
        bool no = false;
        size_t zero = 0;
        require(MaaGlobalSetOption(MaaGlobalOption_StdoutLevel, &off, sizeof(off)), "Maa log option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_DebugMode, &no, sizeof(no)), "Maa debug option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_SaveDraw, &no, sizeof(no)), "Maa draw option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_SaveOnError, &no, sizeof(no)), "Maa error-image option rejected");
        require(MaaGlobalSetOption(MaaGlobalOption_RecoImageCacheLimit, &zero, sizeof(zero)), "Maa cache option rejected");
        resource.reset(MaaResourceCreate());
        require(resource != nullptr, "Maa resource allocation failed");
        MaaInferenceExecutionProvider provider = MaaInferenceExecutionProvider_CPU;
        require(MaaResourceSetOption(resource.get(), MaaResOption_InferenceExecutionProvider, &provider, sizeof(provider)), "Maa CPU selection failed");
        auto load = MaaResourcePostOcrModel(resource.get(), model_dir.c_str());
        require(load != MaaInvalidId && MaaResourceWait(resource.get(), load) == MaaStatus_Succeeded, "Maa model resource registration failed");
        tasker.reset(MaaTaskerCreate());
        require(tasker && MaaTaskerBindResource(tasker.get(), resource.get()) && MaaTaskerInited(tasker.get()), "Maa resource binding failed");
        require(MaaTaskerGetController(tasker.get()) == nullptr, "Unexpected Maa controller");
    }
    void overrideImage(const std::string& name, const MaaImageBuffer* image) {
        require(MaaResourceOverrideImage(resource.get(), name.c_str(), image), "Maa template override rejected");
    }
    std::string recognize(const std::string& type, const std::string& param, const MaaImageBuffer* image) {
        auto task = MaaTaskerPostRecognition(tasker.get(), type.c_str(), param.c_str(), image);
        require(task != MaaInvalidId, "Maa recognition submission rejected");
        auto status = MaaTaskerWait(tasker.get(), task);
        Text name(MaaStringBufferCreate(), MaaStringBufferDestroy), algorithm(MaaStringBufferCreate(), MaaStringBufferDestroy),
            detail(MaaStringBufferCreate(), MaaStringBufferDestroy);
        require(name && algorithm && detail, "Maa result allocation failed");
        MaaSize count = 0;
        MaaStatus detailStatus = status;
        require(MaaTaskerGetTaskDetail(tasker.get(), task, name.get(), nullptr, &count, &detailStatus) && count == 1, "Maa recognition node count changed");
        MaaNodeId node = MaaInvalidId;
        require(MaaTaskerGetTaskDetail(tasker.get(), task, name.get(), &node, &count, &detailStatus), "Maa task detail unavailable");
        MaaRecoId reco = MaaInvalidId;
        MaaActId action = MaaInvalidId;
        MaaBool completed = false;
        require(MaaTaskerGetNodeDetail(tasker.get(), node, name.get(), &reco, &action, &completed) && reco != MaaInvalidId && action == MaaInvalidId, "Maa recognition detail identity changed");
        MaaBool hit = false;
        require(MaaTaskerGetRecognitionDetail(tasker.get(), reco, name.get(), algorithm.get(), &hit, nullptr, detail.get(), nullptr, nullptr), "Maa recognition detail unavailable");
        require(MaaTaskerGetController(tasker.get()) == nullptr, "Unexpected Maa controller");
        std::string body(MaaStringBufferGet(detail.get()), MaaStringBufferSize(detail.get()));
        std::string result = std::string("{\"taskStatus\":") + std::to_string(status) + ",\"hit\":" + (hit ? "true" : "false") +
            ",\"algorithm\":\"" + MaaStringBufferGet(algorithm.get()) + "\",\"detail\":" + (body.empty() ? "null" : body) + "}";
        require(MaaTaskerClearCache(tasker.get()), "Maa result cache clear failed");
        return result;
    }
};
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_yysassist_maaprobe_MaaRegionNative_create(JNIEnv* env, jobject, jstring directory) {
    try {
        return reinterpret_cast<jlong>(std::make_unique<Session>(utf(env, directory)).release());
    } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yysassist_maaprobe_MaaRegionNative_overrideImage(JNIEnv* env, jobject, jlong handle, jstring name, jbyteArray encoded) {
    try {
        require(handle != 0, "Maa session closed");
        auto image = decode(env, encoded);
        reinterpret_cast<Session*>(handle)->overrideImage(utf(env, name), image.get());
    } catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_yysassist_maaprobe_MaaRegionNative_recognize(JNIEnv* env, jobject, jlong handle, jstring type, jstring param, jbyteArray encoded) {
    try {
        require(handle != 0, "Maa session closed");
        auto image = decode(env, encoded);
        auto text = reinterpret_cast<Session*>(handle)->recognize(utf(env, type), utf(env, param), image.get());
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_yysassist_maaprobe_MaaRegionNative_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
