#include <jni.h>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>

#include "AndroidClient.h"
#include "DemoAir.h"

namespace
{
std::mutex mutex;
std::map<jlong, std::unique_ptr<ragtime::android::AndroidClient>> clients;
jlong nextId = 1;

std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray array)
{
    if (!array) throw std::invalid_argument("Missing byte array");
    auto size = env->GetArrayLength(array);
    std::vector<uint8_t> result((size_t)size);
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(result.data()));
    return result;
}
std::string string(JNIEnv* env, jbyteArray array)
{
    auto value = bytes(env, array);
    return {value.begin(), value.end()};
}
jbyteArray array(JNIEnv* env, const std::vector<uint8_t>& value)
{
    auto result = env->NewByteArray((jsize)value.size());
    if (result && !value.empty()) env->SetByteArrayRegion(result, 0, (jsize)value.size(), reinterpret_cast<const jbyte*>(value.data()));
    return result;
}
void fail(JNIEnv* env, const std::exception& error)
{
    if (env->ExceptionCheck()) return;
    // Exception messages are ASCII here; protocol text uses byte arrays.
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, error.what());
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_ragtime_radio_NativeCore_create(JNIEnv* env, jobject, jbyteArray call, jbyteArray locator, jbyteArray path, jbyteArray version, jlong now)
{
    try
    {
        std::lock_guard<std::mutex> lock(mutex);
        auto client = std::make_unique<ragtime::android::AndroidClient>(string(env, call), string(env, locator), string(env, path), (uint64_t)now, string(env,version));
        auto id = nextId++;
        clients.emplace(id, std::move(client));
        return id;
    }
    catch (const std::exception& e) { fail(env, e); }
    catch (...) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Native creation failed"); }
    return 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_ragtime_radio_NativeCore_invoke(JNIEnv* env, jobject, jlong id, jint operation, jbyteArray input, jlong now)
{
    try
    {
        std::lock_guard<std::mutex> lock(mutex);
        auto found = clients.find(id);
        if (found == clients.end()) throw std::runtime_error("Native client is closed");
        auto& client = *found->second;
        std::vector<uint8_t> result;
        if (operation == 0) client.connected((uint64_t)now);
        else if (operation == 1) client.disconnected((uint64_t)now);
        else if (operation == 2) client.dataPort(true, (uint64_t)now);
        else if (operation == 3) client.dataPort(false, (uint64_t)now);
        else if (operation == 4) client.tick((uint64_t)now);
        else if (operation >= 10 && operation <= 12) client.receive(operation - 10, bytes(env, input), (uint64_t)now);
        else if (operation >= 20 && operation <= 22) result = client.output(operation - 20, (uint64_t)now);
        else if (operation == 30) { auto text = client.snapshot((uint64_t)now); result.assign(text.begin(), text.end()); }
        else if (operation == 40)
        {
            auto text = string(env, input);
            auto split = text.find('\0');
            if (split == std::string::npos) throw std::invalid_argument("Missing action separator");
            result.push_back(client.action(text.substr(0, split), text.substr(split + 1), (uint64_t)now) ? 1 : 0);
        }
        else throw std::invalid_argument("Unknown native operation");
        return array(env, result);
    }
    catch (const std::exception& e) { fail(env, e); }
    catch (...) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Native operation failed"); }
    return nullptr;
}
// Demo mode's net: one at a time, on a thread of its own. Returns its
// operator host's KISS and command ports on loopback.
namespace
{
std::mutex demoMutex;
std::unique_ptr<ragtime::android::DemoRunner> demo;
} // namespace
extern "C" JNIEXPORT jintArray JNICALL
Java_org_ragtime_radio_NativeCore_demoStart(JNIEnv* env, jobject, jbyteArray folder, jbyteArray call)
{
    try
    {
        std::lock_guard<std::mutex> lock(demoMutex);
        demo.reset();
        demo = std::make_unique<ragtime::android::DemoRunner>(string(env, folder), string(env, call));
        const jint ports[2] = {demo->kissPort(), demo->commandPort()};
        auto result = env->NewIntArray(2);
        if (result) env->SetIntArrayRegion(result, 0, 2, ports);
        return result;
    }
    catch (const std::exception& e) { fail(env, e); }
    catch (...) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "The demo could not start"); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_org_ragtime_radio_NativeCore_demoStop(JNIEnv*, jobject)
{
    std::lock_guard<std::mutex> lock(demoMutex);
    demo.reset();
}
extern "C" JNIEXPORT void JNICALL
Java_org_ragtime_radio_NativeCore_destroy(JNIEnv*, jobject, jlong id)
{
    std::lock_guard<std::mutex> lock(mutex);
    clients.erase(id);
}
