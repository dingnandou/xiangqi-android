# 弈进象棋 · 原生引擎层（M0）
#
# 构建产物：libyjengine.so（JNI 桥接层）
# 依赖：pikafish_core（静态库，vendored Pikafish 源码）
#
# 编译宏与 flags 严格对应 Pikafish 自带 Makefile 的 `make ... COMP=ndk ARCH=armv8`
# 推导结果，见 PROJECT_STATUS.md「引擎构建参数」一节。修改前请同步核对上游 Makefile：
#   -DNDEBUG      <- debug=no
#   -DIS_64BIT    <- bits=64
#   -DUSE_POPCNT  <- popcnt=yes, arch=armv8
#   -DUSE_NEON=8  <- neon=yes, arm_version=8
#   -DARCH=armv8  <- ARCH=armv8
cmake_minimum_required(VERSION 3.22.1)
project(yjengine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(PIKAFISH_DIR "${CMAKE_CURRENT_SOURCE_DIR}/pikafish")

# ---------------------------------------------------------------------------
# Pikafish 源码清单
#
# 显式列出而不是 glob：Pikafish Makefile 用 `find . -name '*.cpp' -o -name '*.S'`
# （排除 universal/ 与 temp_builds/）。此处在此基础上再排除两项：
#   main.cpp                     —— 自带 main()，与 JNI 宿主冲突；其 init 序列已迁到 engine_bridge.cpp
#   external/decompress/huf_decompress_amd64.S —— 仅 x86-64 使用，arm64 下是死代码且无法汇编
# ---------------------------------------------------------------------------
set(PIKAFISH_SOURCES
    attacks.cpp
    benchmark.cpp
    bitboard.cpp
    engine.cpp
    evaluate.cpp
    memory.cpp
    misc.cpp
    movegen.cpp
    movepick.cpp
    position.cpp
    score.cpp
    search.cpp
    thread.cpp
    timeman.cpp
    tt.cpp
    tune.cpp
    uci.cpp
    ucioption.cpp

    external/common/debug.cpp
    external/common/entropy_common.cpp
    external/common/error_private.cpp
    external/common/fse_decompress.cpp
    external/common/pool.cpp
    external/common/threading.cpp
    external/common/xxhash.cpp
    external/common/zstd_common.cpp

    external/decompress/huf_decompress.cpp
    external/decompress/zstd_ddict.cpp
    external/decompress/zstd_decompress.cpp
    external/decompress/zstd_decompress_block.cpp

    nnue/features/full_threats.cpp
    nnue/features/half_ka_v2_hm.cpp
    nnue/network.cpp
    nnue/nnue_accumulator.cpp
    nnue/nnue_misc.cpp
)

list(TRANSFORM PIKAFISH_SOURCES PREPEND "${PIKAFISH_DIR}/" OUTPUT_VARIABLE PIKAFISH_SOURCES_ABS)

add_library(pikafish_core STATIC ${PIKAFISH_SOURCES_ABS})

target_include_directories(pikafish_core PUBLIC "${PIKAFISH_DIR}")

target_compile_options(pikafish_core PRIVATE
    -O3
    -funroll-loops
    -fno-exceptions
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wcast-qual
)

# 架构宏必须 PUBLIC：engine_bridge.cpp 会直接包含 Pikafish 头文件，
# 若这些宏只对 pikafish_core 生效，桥接层将拿不到 u128 / Bitboard 定义。
target_compile_definitions(pikafish_core PUBLIC
    IS_64BIT
    USE_POPCNT
    USE_NEON=8
    ARCH=armv8
)

target_compile_definitions(pikafish_core PRIVATE
    NDEBUG
)

# ---------------------------------------------------------------------------
# JNI 桥接层
# ---------------------------------------------------------------------------
add_library(yjengine SHARED engine_bridge.cpp)

target_include_directories(yjengine PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(yjengine PRIVATE pikafish_core)

target_compile_options(yjengine PRIVATE
    -O2
    -fno-exceptions
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Wall
)

# Android NDK r28 默认生成 16 KB 对齐代码；这里显式声明，避免工具链回退时无声失效。
# 链接完成后用 llvm-readelf -lW 复核，见 PROJECT_STATUS.md「16 KB 页面兼容性」。
target_link_options(yjengine PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--no-as-needed"
)

# NDK 默认带 -g，会把 libyjengine.so 撑大一倍多。发布包不需要符号表，
# 剥离后再随 APK 分发；调试定位问题时用 CMake 选项 YJ_KEEP_SYMBOLS=ON 重编。
if(NOT YJ_KEEP_SYMBOLS)
    find_program(YJ_STRIP_TOOL NDK_LLVM_STRIP)
    if(YJ_STRIP_TOOL)
        add_custom_command(TARGET yjengine POST_BUILD
            COMMAND "${YJ_STRIP_TOOL}" --strip-unneeded "$<TARGET_FILE:yjengine>"
            COMMENT "Stripping debug symbols from libyjengine.so")
    endif()
endif()

# Bionic 自带 pthread 实现，不需要 -lpthread / -lrt（见 Pikafish Makefile 对
# `ifeq ($(OS),Android)` 的处理）。