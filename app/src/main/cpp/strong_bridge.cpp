// Pikafish is GPL-3.0; corresponding source is included in engine/src/main/cpp/pikafish.
#include <jni.h>
#include <atomic>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include "attacks.h"
#include "engine.h"
#include "misc.h"
#include "position.h"
#include "search.h"

using namespace Stockfish;
namespace {
std::string utf(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* bytes = env->GetStringUTFChars(text, nullptr);
    std::string result(bytes ? bytes : "");
    if (bytes) env->ReleaseStringUTFChars(text, bytes);
    return result;
}
class Session {
public:
    std::unique_ptr<Engine> engine;
    std::string best;
    std::mutex resultMutex;
    explicit Session(const std::string& directory) {
        static std::once_flag initialized;
        std::call_once(initialized, [] { Attacks::init(); Position::init(); });
        engine = std::make_unique<Engine>(std::filesystem::path(directory) / "pikafish");
        engine->set_on_start([] {});
        engine->set_on_update_no_moves([](const Engine::InfoShort&) {});
        engine->set_on_update_full([](const Engine::InfoFull&) {});
        engine->set_on_iter([](const Engine::InfoIter&) {});
        engine->set_on_verify_network([](std::string_view) {});
        engine->set_on_bestmove([this](std::string_view move, std::string_view) {
            std::lock_guard<std::mutex> guard(resultMutex);
            best = std::string(move);
        });
        // Workers copy the callback context at creation; rebuild after registering every callback.
        engine->resize_threads();
    }
    ~Session() { engine->stop(); engine->wait_for_search_finished(); }
    std::string search(const std::string& fen, int depth, int millis) {
        engine->stop(); engine->wait_for_search_finished();
        auto error = engine->set_position(fen, {});
        if (error.has_value()) return "error:position";
        { std::lock_guard<std::mutex> guard(resultMutex); best.clear(); }
        Search::LimitsType limits;
        limits.startTime = now();
        limits.movetime = millis;
        if (depth > 0) limits.depth = depth;
        engine->go(limits);
        engine->wait_for_search_finished();
        std::lock_guard<std::mutex> guard(resultMutex);
        return best;
    }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Session>> sessions;
std::atomic<jlong> nextHandle{1};
std::shared_ptr<Session> acquire(jlong handle) {
    std::lock_guard<std::mutex> guard(registryMutex);
    auto found = sessions.find(handle);
    return found == sessions.end() ? nullptr : found->second;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_yijin_xiangqi_light_StrongEngine_nativeCreate(JNIEnv* env, jclass, jstring folder) {
    auto session = std::make_shared<Session>(utf(env, folder));
    jlong handle = nextHandle.fetch_add(1);
    std::lock_guard<std::mutex> guard(registryMutex);
    sessions.emplace(handle, std::move(session));
    return handle;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_yijin_xiangqi_light_StrongEngine_nativeSearch(JNIEnv* env, jclass, jlong handle, jstring fen, jint depth, jint millis) {
    auto session = acquire(handle);
    std::string result = session ? session->search(utf(env, fen), depth, millis) : "error:closed";
    return env->NewStringUTF(result.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_yijin_xiangqi_light_StrongEngine_nativeStop(JNIEnv*, jclass, jlong handle) {
    if (auto session = acquire(handle)) session->engine->stop();
}
extern "C" JNIEXPORT void JNICALL
Java_com_yijin_xiangqi_light_StrongEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    std::shared_ptr<Session> removed;
    {
        std::lock_guard<std::mutex> guard(registryMutex);
        auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        removed = std::move(found->second); sessions.erase(found);
    }
    // Destruction never holds the registry lock; outstanding stop/search calls retain shared ownership.
    removed->engine->stop();
}
