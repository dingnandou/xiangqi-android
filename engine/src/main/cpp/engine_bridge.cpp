/*
 * 弈进象棋 · Pikafish JNI 桥接层（M0）
 *
 * 设计要点（对应《02-技术方案与验收标准》§5.1/§5.2）：
 *
 *  1. 直接驱动 Pikafish 的 `Stockfish::Engine`，不使用 `UCIEngine::loop()`，
 *     因为后者通过 `getline(std::cin, ...)` 读取命令。改写 std::cin 会污染宿主进程
 *     的全局输入流；这里改为调用 Engine 的公开方法，用回调接收输出，
 *     输出落入本会话自己的事件队列。宿主进程的 stdin/stdout 不被重定向。
 *
 *  2. 每个会话最多一个搜索。事件全部带上 requestId 与 seq，
 *     由 Kotlin 层比对后丢弃过期响应（对应 §5.2 的过期响应核对）。
 *
 *  3. 所有搜索在 Pikafish 自己的线程池中后台运行；JNI 方法本身只做入队/取队列，
 *     绝不在调用线程上等待搜索结束（`nativeWaitForSearchFinished` 除外，
 *     该方法仅供测试与析构使用）。
 */

#include <jni.h>

#include <atomic>
#include <cctype>
#include <cstdint>
#include <deque>
#include <filesystem>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "attacks.h"
#include "engine.h"
#include "misc.h"
#include "position.h"
#include "score.h"
#include "search.h"
#include "timeman.h"
#include "types.h"
#include "ucioption.h"

using namespace Stockfish;

namespace {

constexpr std::int64_t kNoRequest = -1;

/* Pikafish 要求在首次使用前完成全局初始化。原 main() 里的两行调用搬到这里。 */
void ensureGlobalInit() {
    static std::once_flag once;
    std::call_once(once, [] {
        Attacks::init();
        Position::init();
    });
}

std::vector<std::string> splitLines(const std::string& text) {
    std::vector<std::string> out;
    std::istringstream      is(text);
    std::string              line;
    while (std::getline(is, line)) {
        if (!line.empty() && line.back() == '\r')
            line.pop_back();
        if (!line.empty())
            out.push_back(line);
    }
    return out;
}

std::string flatten(std::string_view text) {
    std::string out;
    out.reserve(text.size());
    for (char c : text)
        out.push_back((c == '\n' || c == '\r') ? ' ' : c);
    return out;
}

/*
 * 一个 EngineSession 对应一个引擎会话。JNI 侧只保存指针，
 * 生命周期由 Kotlin 的 create/destroy 配对保证。
 */
class EngineSession {
   public:
    /*
     * netDir 必须是「包含 pikafish.nnue 的目录」，而不是网络文件本身。
     *
     * 原因（nnue/network.cpp:132 verify()）：verify_network() 不接受路径参数，
     * 它拿 evalFile.current 和 EvalFileDefaultName（"pikafish.nnue"）比较。
     * 若用 Engine::load_network(绝对路径) 加载，evalFile.current 会变成那个绝对路径，
     * 与默认名永远不相等，verify 判定失败并直接 exit()——那会杀掉整个 App 进程。
     *
     * 因此这里走引擎原生的发现路径：把 netDir 当作"可执行文件所在目录"传进去
     * （CommandLine::get_binary_directory 取 argv0 的 parent_path，见 misc.cpp:606），
     * 让 Engine 构造函数里的 get_default_network() 自己把 <netDir>/pikafish.nnue 找到。
     * 这样 evalFile.current == "pikafish.nnue"，verify_network() 才能通过。
     */
    EngineSession(const std::string& netDir, int threads, int hashMb) {
        ensureGlobalInit();

        const std::filesystem::path binaryDir =
          netDir.empty() ? std::filesystem::path{} : std::filesystem::path(netDir) / "pikafish";

        engine_ = std::make_unique<Engine>(binaryDir);
        wireCallbacks();

        // Threads / Hash 必须先设置再 resize_threads()，否则线程池不会按新值建立。
        setOption("Threads", std::to_string(threads));
        if (hashMb > 0)
            setOption("Hash", std::to_string(hashMb));
        engine_->resize_threads();

        networkInfo_.clear();
        // 网络不可用时这里会 exit(EXIT_FAILURE)（nnue/network.cpp:160）。
        // Kotlin 侧在调用本构造前已把资产复制到 netDir 并校验过 SHA256。
        engine_->verify_network();
    }

    ~EngineSession() {
        if (engine_) {
            engine_->stop();
            engine_->wait_for_search_finished();
        }
    }

    EngineSession(const EngineSession&)            = delete;
    EngineSession& operator=(const EngineSession&) = delete;

    std::string engineInfo() const { return engine_info(); }

    const std::string& networkInfo() const { return networkInfo_; }

    const std::string& fen() const { return fen_; }

    std::vector<std::string> options() const {
        std::ostringstream ss;
        ss << engine_->get_options();
        return splitLines(ss.str());
    }

    bool setOption(const std::string& name, const std::string& value) {
        if (engine_->get_options().count(name) == 0)
            return false;

        // OptionsMap::operator[] 只有 const 重载，唯一可写的公开入口是 setoption()。
        std::istringstream is("name " + name + " value " + value);
        engine_->get_options().setoption(is);

        // Threads / Hash 改变后需要重建线程池与置换表。
        std::string lowered = name;
        std::transform(lowered.begin(), lowered.end(), lowered.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        if (lowered == "threads" || lowered == "hash")
            engine_->resize_threads();
        return true;
    }

    /* 返回空串表示成功，否则是可直接展示给用户的错误说明。 */
    std::string setPosition(const std::string& fen, const std::vector<std::string>& moves) {
        auto err = engine_->set_position(fen, moves);
        if (err.has_value()) {
            // 先停掉旧搜索，避免旧搜索结果落到新局面。
            engine_->stop();
            engine_->wait_for_search_finished();
            return err->what();
        }
        fen_ = engine_->fen();
        return {};
    }

    bool go(std::int64_t requestId, int movetimeMs, int depth, std::uint64_t nodes, bool infinite,
            bool ponder) {
        engine_->stop();
        engine_->wait_for_search_finished();

        {
            std::lock_guard<std::mutex> lock(eventMutex_);
            ++searchSeq_;
            currentRequest_ = requestId;
            currentSeq_     = searchSeq_;
        }

        Search::LimitsType limits;
        // 必须设置 startTime：引擎自身在 uci.cpp:195 里也是这样做的。
        // 不设的话 timeman 会拿 0 当起点，movetime 预算立即判定为超时，
        // 搜索会在 1~2 ms 内结束，并把 info.time 报成 steady_clock 的原始值。
        limits.startTime = now();
        if (movetimeMs > 0)
            limits.movetime = static_cast<TimePoint>(movetimeMs);
        if (depth > 0)
            limits.depth = depth;
        if (nodes > 0)
            limits.nodes = nodes;
        if (infinite)
            limits.infinite = 1;
        limits.ponderMode = ponder;

        engine_->go(limits);
        return true;
    }

    void stop() { engine_->stop(); }

    void waitForSearchFinished() {
        engine_->wait_for_search_finished();
        std::lock_guard<std::mutex> lock(eventMutex_);
        currentRequest_ = kNoRequest;
    }

    void clearSearch() {
        engine_->stop();
        engine_->wait_for_search_finished();
        engine_->search_clear();
    }

    std::uint64_t perft(const std::string& fen, int depth, bool& ok) {
        auto result = engine_->perft(fen, depth);
        if (std::holds_alternative<u64>(result)) {
            ok = true;
            return std::get<u64>(result);
        }
        ok = false;
        return 0;
    }

    int hashfull() const { return engine_->get_hashfull(); }

    /* 取走并清空事件队列。事件格式见 PROJECT_STATUS.md「引擎事件格式」。 */
    std::vector<std::string> pollEvents() {
        std::lock_guard<std::mutex> lock(eventMutex_);
        std::vector<std::string>    out;
        out.reserve(events_.size());
        while (!events_.empty()) {
            out.push_back(std::move(events_.front()));
            events_.pop_front();
        }
        return out;
    }

   private:
    static constexpr size_t kMaxQueuedEvents = 256;

    void push(std::string ev) {
        std::lock_guard<std::mutex> lock(eventMutex_);
        if (events_.size() >= kMaxQueuedEvents)
            events_.pop_front();  // 背压：丢掉最旧事件，保证最新状态不会被挤掉
        events_.push_back(std::move(ev));
    }

    void wireCallbacks() {
        // 这几个回调由引擎内部线程触发，因此一律只做入队，绝不在回调里做重活。
        engine_->set_on_start([this] {
            std::lock_guard<std::mutex> lock(eventMutex_);
            events_.push_back("START|" + std::to_string(currentRequest_) + "|"
                              + std::to_string(currentSeq_));
        });

        engine_->set_on_update_full([this](const Engine::InfoFull& info) {
            std::int64_t req;
            std::int64_t seq;
            {
                std::lock_guard<std::mutex> lock(eventMutex_);
                req = currentRequest_;
                seq = currentSeq_;
            }

            // 评分保留原始类型（cp / mate / 边界），不做任何折算。
            // 红黑视角换算属于 M3 复盘职责，见《02》§6.1 与 §6.3。
            std::string scoreType  = "none";
            std::string scoreValue = "0";
            if (info.score.is<Score::Mate>()) {
                scoreType  = "mate";
                scoreValue = std::to_string(info.score.get<Score::Mate>().plies);
            } else {
                scoreType  = "cp";
                scoreValue = std::to_string(info.score.get<Score::InternalUnits>().value);
            }

            std::ostringstream ss;
            ss << "INFO|" << req << "|" << seq << "|" << info.depth << "|" << info.selDepth << "|"
               << info.multiPV << "|" << scoreType << "|" << scoreValue << "|"
               << flatten(info.bound) << "|" << info.nodes << "|" << info.nps << "|" << info.timeMs << "|"
               << info.hashfull << "|" << flatten(info.wdl) << "|" << flatten(info.pv);
            push(ss.str());
        });

        engine_->set_on_bestmove([this](std::string_view bestmove, std::string_view ponder) {
            std::int64_t req;
            std::int64_t seq;
            {
                std::lock_guard<std::mutex> lock(eventMutex_);
                req = currentRequest_;
                seq = currentSeq_;
            }
            std::ostringstream ss;
            ss << "BESTMOVE|" << req << "|" << seq << "|" << flatten(bestmove) << "|"
               << flatten(ponder);
            push(ss.str());
        });

        engine_->set_on_verify_network([this](std::string_view text) {
            networkInfo_ = std::string(text);
            push("NETINFO|" + flatten(text));
        });
    }

    std::unique_ptr<Engine> engine_;

    mutable std::mutex      eventMutex_;
    std::deque<std::string> events_;
    std::string             networkInfo_;
    std::string             fen_;

    std::int64_t searchSeq_      = 0;
    std::int64_t currentSeq_     = 0;
    std::int64_t currentRequest_ = kNoRequest;
};

/* --------------------------------------------------------------------- */
/* handle 注册表                                                          */
/* --------------------------------------------------------------------- */

std::mutex                                                        gRegistryMutex;
std::unordered_map<jlong, std::unique_ptr<EngineSession>>          gRegistry;
std::atomic<jlong>                                                gNextHandle{1};

EngineSession* resolve(jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto                        it = gRegistry.find(handle);
    return it == gRegistry.end() ? nullptr : it->second.get();
}

jstring toJavaString(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

jobjectArray toJavaStringArray(JNIEnv* env, const std::vector<std::string>& items) {
    jclass       stringClass = env->FindClass("java/lang/String");
    jobjectArray array       = env->NewObjectArray(static_cast<jsize>(items.size()), stringClass, nullptr);
    for (size_t i = 0; i < items.size(); ++i)
        env->SetObjectArrayElement(array, static_cast<jsize>(i), toJavaString(env, items[i]));
    return array;
}

std::string fromJavaString(JNIEnv* env, jstring value) {
    if (value == nullptr)
        return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string  out(chars ? chars : "");
    if (chars)
        env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::vector<std::string> fromJavaStringArray(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr)
        return out;
    const jsize n = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(fromJavaString(env, item));
        if (item)
            env->DeleteLocalRef(item);
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeCreate(JNIEnv* env, jclass,
                                                                               jstring netDir, jint threads,
                                                                               jint hashMb) {
    auto         session   = std::make_unique<EngineSession>(fromJavaString(env, netDir), threads, hashMb);
    const jlong  handle    = gNextHandle.fetch_add(1);
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gRegistry[handle] = std::move(session);
    return handle;
}

JNIEXPORT void JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeDestroy(JNIEnv*, jclass,
                                                                               jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gRegistry.erase(handle);
}

JNIEXPORT jstring JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeEngineInfo(JNIEnv* env, jclass,
                                                                                     jlong handle) {
    EngineSession* s = resolve(handle);
    return toJavaString(env, s ? s->engineInfo() : "");
}

JNIEXPORT jstring JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeNetworkInfo(JNIEnv* env, jclass,
                                                                                     jlong handle) {
    EngineSession* s = resolve(handle);
    return toJavaString(env, s ? s->networkInfo() : "");
}

JNIEXPORT jstring JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeFEN(JNIEnv* env, jclass,
                                                                              jlong handle) {
    EngineSession* s = resolve(handle);
    return toJavaString(env, s ? s->fen() : "");
}

JNIEXPORT jobjectArray JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeOptions(JNIEnv* env, jclass,
                                                                                     jlong handle) {
    EngineSession* s = resolve(handle);
    return toJavaStringArray(env, s ? s->options() : std::vector<std::string>{});
}

JNIEXPORT jboolean JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeSetOption(JNIEnv* env, jclass,
                                                                                     jlong handle, jstring name,
                                                                                     jstring value) {
    EngineSession* s = resolve(handle);
    if (!s)
        return JNI_FALSE;
    return s->setOption(fromJavaString(env, name), fromJavaString(env, value)) ? JNI_TRUE : JNI_FALSE;
}

/* 成功返回空串，失败返回可直接展示的错误文本。 */
JNIEXPORT jstring JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeSetPosition(JNIEnv* env, jclass,
                                                                                    jlong handle, jstring fen,
                                                                                    jobjectArray moves) {
    EngineSession* s = resolve(handle);
    if (!s)
        return toJavaString(env, "引擎会话不存在");
    return toJavaString(env, s->setPosition(fromJavaString(env, fen), fromJavaStringArray(env, moves)));
}

JNIEXPORT jboolean JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeGo(JNIEnv*, jclass, jlong handle,
                                                                             jlong requestId,
                                                                             jint movetimeMs, jint depth,
                                                                             jlong nodes, jboolean infinite,
                                                                             jboolean ponder) {
    EngineSession* s = resolve(handle);
    if (!s)
        return JNI_FALSE;
    return s->go(requestId, movetimeMs, depth, static_cast<std::uint64_t>(nodes), infinite == JNI_TRUE,
                 ponder == JNI_TRUE)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeStop(JNIEnv*, jclass, jlong handle) {
    if (EngineSession* s = resolve(handle))
        s->stop();
}

JNIEXPORT void JNICALL
Java_com_yijin_xiangqi_engine_NativeEngine_nativeWaitForSearchFinished(JNIEnv*, jclass, jlong handle) {
    if (EngineSession* s = resolve(handle))
        s->waitForSearchFinished();
}

JNIEXPORT void JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeClearSearch(JNIEnv*, jclass,
                                                                                   jlong handle) {
    if (EngineSession* s = resolve(handle))
        s->clearSearch();
}

JNIEXPORT jlong JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativePerft(JNIEnv* env, jclass,
                                                                              jlong handle, jstring fen,
                                                                              jint depth) {
    EngineSession* s = resolve(handle);
    if (!s) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "引擎会话不存在");
        return -1;
    }
    bool            ok    = false;
    const uint64_t  nodes = s->perft(fromJavaString(env, fen), depth, ok);
    return ok ? static_cast<jlong>(nodes) : -1;
}

JNIEXPORT jint JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeHashfull(JNIEnv*, jclass,
                                                                               jlong handle) {
    EngineSession* s = resolve(handle);
    return s ? s->hashfull() : -1;
}

JNIEXPORT jobjectArray JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativePollEvents(JNIEnv* env, jclass,
                                                                                        jlong handle) {
    EngineSession* s = resolve(handle);
    return toJavaStringArray(env, s ? s->pollEvents() : std::vector<std::string>{});
}

/* 编译期信息，供「引擎验证」页面确认跑的确实是这份二进制。 */
JNIEXPORT jstring JNICALL Java_com_yijin_xiangqi_engine_NativeEngine_nativeBuildInfo(JNIEnv* env, jclass) {
    std::ostringstream ss;
    // ARCH 展开成裸标识符（armv8），必须经 stringify 才能当字符串输出。
    ss << "arch=" << stringify(ARCH) << ";is64=" << (Is64Bit ? 1 : 0) << ";neon=" << USE_NEON
       << ";popcnt=" << (USE_POPCNT ? 1 : 0) << ";compiler=" << compiler_info();
    return toJavaString(env, ss.str());
}

}  // extern "C"