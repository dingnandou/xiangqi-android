package com.yijin.xiangqi.engine

/**
 * JNI 声明层。
 *
 * 这里只做「声明」，不做任何状态管理——状态与生命周期由 [EngineSession] 负责。
 *
 * 对应关系见 engine/src/main/cpp/engine_bridge.cpp，两边的函数签名必须一致：
 *   nativeCreate → Java_com_yijin_xiangqi_engine_NativeEngine_nativeCreate
 */
internal object NativeEngine {

    init {
        // 加载失败时 UnsatisfiedLinkError 会直接抛出，由调用方转换成可读错误，
        // 不做静默降级——引擎不可用时不能伪装成"在思考"。
        System.loadLibrary("yjengine")
    }

    /**
     * 创建一个引擎会话。
     *
     * @param netDir **包含 `pikafish.nnue` 的目录**，不是网络文件本身。
     *   引擎的 verify_network() 只认默认文件名，路径写法不对会直接 exit() 掉整个进程。
     * @return 会话句柄，销毁时必须配对调用 [nativeDestroy]。
     */
    external fun nativeCreate(netDir: String, threads: Int, hashMb: Int): Long

    external fun nativeDestroy(handle: Long)
    external fun nativeEngineInfo(handle: Long): String
    external fun nativeNetworkInfo(handle: Long): String
    external fun nativeFEN(handle: Long): String
    external fun nativeOptions(handle: Long): Array<String>
    external fun nativeSetOption(handle: Long, name: String, value: String): Boolean

    /** 成功返回空串；失败返回可直接展示的错误文本。 */
    external fun nativeSetPosition(handle: Long, fen: String, moves: Array<String>): String

    /**
     * 启动搜索（非阻塞）。
     *
     * @param requestId 调用方分配的请求编号，原样回填到每条事件里，用于丢弃过期响应。
     */
    external fun nativeGo(
        handle: Long,
        requestId: Long,
        movetimeMs: Int,
        depth: Int,
        nodes: Long,
        infinite: Boolean,
        ponder: Boolean,
    ): Boolean

    external fun nativeStop(handle: Long)

    /** 阻塞等待，仅供测试与析构使用，禁止在主线程调用。 */
    external fun nativeWaitForSearchFinished(handle: Long)

    external fun nativeClearSearch(handle: Long)
    external fun nativePerft(handle: Long, fen: String, depth: Int): Long
    external fun nativeHashfull(handle: Long): Int

    /** 取走并清空事件队列，返回形如 `TYPE|field|field...` 的原始字符串。 */
    external fun nativePollEvents(handle: Long): Array<String>

    /** 编译期信息，用于确认运行的确实是这份 arm64 二进制。 */
    external fun nativeBuildInfo(): String
}