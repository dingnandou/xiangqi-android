/*
 * 弈进象棋 · 引擎宿主机自检程序（M0 辅助验证，非随应用分发）
 *
 * 用途：在没有安卓真机的情况下，验证「vendored Pikafish 源码」与
 * 「pikafish.nnue 网络文件」确实配套——能加载网络、能对初始局面给出合法最佳着、
 * 能被 stop 中途打断。这三项正是《02-技术方案与验收标准》M0 门槛里
 * 「清楚 ABI 与 NNUE 配套」的直接证据。
 *
 * 构建与运行方式见 tools/verify-engine-host.ps1。
 *
 * 注意：本程序运行在 PC 上，只能证明源码与网络的配套关系，
 * 不能替代 arm64 真机验证。真机结论见 PROJECT_STATUS.md。
 */

#include <atomic>
#include <chrono>
#include <cstdio>
#include <iostream>
#include <string>
#include <thread>

#include "attacks.h"
#include "engine.h"
#include "misc.h"
#include "position.h"
#include "search.h"
#include "timeman.h"
#include "types.h"
#include "uci.h"

using namespace Stockfish;

namespace {

std::atomic<bool> g_done{false};

void onBestMove(std::string_view bestmove, std::string_view ponder) {
    std::cout << "bestmove=" << bestmove << " ponder=" << ponder << std::endl;
    g_done.store(true);
}

}  // namespace

int main(int argc, char** argv) {
    const std::string netPath  = (argc > 1) ? argv[1] : "";
    const int         movetime = (argc > 2) ? std::atoi(argv[2]) : 1000;
    const bool        wantStop = (argc > 3) && std::string(argv[3]) == "stop";

    std::cout << "engine_info: " << engine_info(true) << std::endl;
    std::cout << "compiler: " << compiler_info() << std::endl;
    std::cout << "Is64Bit: " << (Is64Bit ? "yes" : "no") << std::endl;

    Attacks::init();
    Position::init();

    // 传入 netPath 的父目录：Engine 的 binaryDirectory = argv0.parent_path()，
    // 于是 get_default_network() 会找到 <netDir>/pikafish.nnue，
    // evalFile.current 等于默认名，verify_network() 才能通过。
    // 详见 engine_bridge.cpp 里 EngineSession 构造函数的说明。
    const std::filesystem::path netDir =
      std::filesystem::path(netPath).parent_path().empty() ? std::filesystem::path(".")
                                                          : std::filesystem::path(netPath).parent_path();
    const std::filesystem::path fakeArgv0 = netDir / "pikafish";

    Engine engine(fakeArgv0);

    // Engine 的每个回调都必须显式注册：未注册的 std::function 在搜索时被调用
    // 会抛 std::bad_function_call 并终止进程（JNI 层同样必须全部注册）。
    engine.set_on_start([]() { std::cout << "search started" << std::endl; });
    engine.set_on_verify_network([](std::string_view text) {
        std::cout << "network: " << text << std::endl;
    });
    engine.set_on_update_no_moves([](const Engine::InfoShort& i) {
        std::cout << "info (no moves): depth=" << i.depth << std::endl;
    });
    engine.set_on_iter([](const Engine::InfoIter& i) {
        std::cout << "iter: depth=" << i.depth << " currmove=" << i.currmove
                  << " (" << i.currmovenumber << ")" << std::endl;
    });
    engine.set_on_update_full([](const Engine::InfoFull& i) {
        std::cout << "info: depth=" << i.depth << " seldepth=" << i.selDepth
                  << " score=" << UCIEngine::format_score(i.score) << " bound=" << i.bound
                  << " nodes=" << i.nodes << " nps=" << i.nps << " time=" << i.timeMs << "ms pv="
                  << i.pv << std::endl;
    });
    engine.set_on_bestmove(onBestMove);

    // 网络与引擎版本不配套时，这里会 exit(EXIT_FAILURE)，退出码非 0 即表示不配套。
    engine.verify_network();

    auto err = engine.set_position(StartFEN, {});
    if (err.has_value()) {
        std::cerr << "position error: " << err->what() << std::endl;
        return 2;
    }
    std::cout << "fen: " << engine.fen() << std::endl;

    Search::LimitsType limits;
    // 与 uci.cpp:195 一致：startTime 必须由调用方设置，否则 movetime 立即判定超时。
    limits.startTime = now();
    limits.movetime = movetime;

    const auto t0 = std::chrono::steady_clock::now();
    engine.go(limits);

    if (wantStop) {
        // 模拟安卓端「思考中点击停止」：立刻 stop，验证不会挂死且仍会给出 bestmove。
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        std::cout << "issuing stop after 50ms" << std::endl;
        engine.stop();
    }

    engine.wait_for_search_finished();
    const auto t1 = std::chrono::steady_clock::now();
    const auto elapsedMs =
      std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

    std::cout << "elapsed_ms=" << elapsedMs << std::endl;
    std::cout << "hashfull=" << engine.get_hashfull() << std::endl;

    if (!g_done.load()) {
        std::cerr << "no bestmove produced" << std::endl;
        return 3;
    }
    std::cout << "HOST_SMOKE_OK" << std::endl;
    return 0;
}