# 当前工程状态

更新日期：2026-10-05。当前版本：掌上象棋 2.1 / versionCode 4。

## 本轮实现

- 根据棋子效果图新增统一的 WoodPiecePainter：圆润倒角、木纹、微凸表面、高光与阴影，清晰的红黑棋字替代彩色双圈；棋子直径从格距的 84% 调整为 88%。
- 对局棋子、双方头像和首页装饰使用同一绘制器；复用渐变与路径，按手机实际格距缩放，触控坐标与棋局规则保持原有行为。
- 新首页：三种模式、四档难度、开始新局、继续上局、设置、说明与关于。
- 米白/朱红/木色统一视觉，卡片式选择与双方信息、渐变木棋盘与棋子、回合显示。
- 人机、双人和 AI 观战；暂停、逐步、调速、提示、悔棋、重开、认输。
- 简单/普通使用 Java 对手，困难/大师实际调用 Pikafish 专业引擎。
- 返回首页/后台停止计算并保存，丢弃过期回着；恢复先进入首页，由用户继续。
- 新局待选项独立保存，继续上局保留原难度与棋局。
- version 2 存档兼容 version 1，保存总着数、胜方和结束原因。

## 平台与构建

- 包名：com.yijin.xiangqi.light；最低 Android 8.0 / API 26。
- 支持 arm64-v8a 手机、x86_64 测试设备；不含 32 位 ABI。
- compile/target SDK 36，Java 8 字节码，原生 Activity/Canvas。
- JDK 17、build-tools 36.0.0、NDK 28.2.13676358、CMake 3.22.1。
- libstrongengine.so 静态链接 libc++，LOAD 段 16 KB 对齐。
- APK 内置 NNUE，不申请联网权限。
- 签名与旧版一致；v2/v3 签名，密钥不入库。

## 关键文件

| 路径 | 用途 |
|---|---|
| app/src/main/java/com/yijin/xiangqi/light/MainActivity.java | 首页、棋盘、模式、设置、存档和生命周期 |
| app/src/main/java/com/yijin/xiangqi/light/WoodPiecePainter.java | 圆润木质棋子、头像和首页装饰绘制 |
| app/src/main/java/com/yijin/xiangqi/light/Chess.java | 规则、轻量对手、FEN/UCI、基础记谱 |
| app/src/main/java/com/yijin/xiangqi/light/StrongEngine.java | NNUE 校验、专业引擎调度、合法着检查 |
| app/src/main/cpp/strong_bridge.cpp | 引擎回调、限时搜索、共享生命周期 |
| app/src/main/cpp/CMakeLists.txt | 两个 ABI 的引擎构建 |
| tools/build-phone-apk.ps1 | 编译、打包、签名、安装包检查 |
| tools/build-strong-engine.ps1 | 手机和模拟器本地库构建 |
| tools/check-home-ui.ps1 | 首页、四档对手、返回/继续、取消、设置、大字体 |
| tools/check-phone-ui.ps1 | 单人/双人/提示/悔棋/恢复 |
| tools/check-watch-ui.ps1 | 观战连续走棋、暂停、逐步、恢复 |
| output/掌上象棋.apk | 最新安装包 |

## 专业引擎

使用保留的 Pikafish 源码，新桥接独立于旧 M0 桥接。必要回调在重建工作线程前注册，startTime 初始化，句柄采用 shared_ptr 延长调用生命周期。阻塞搜索与销毁在单工作线程执行，停止可由主线程安全请求。

NNUE SHA256：`7D13D73569A9B571BA0EB20CF1596247BC2A42738967E61AFEF6482B231E900E`。加载前完整校验，UCI 返回值经 Java 规则检查。

Pikafish 为 GPL-3.0，权重采用上游非商业许可。对应源代码和许可证随项目提供，许可证也随 APK 提供。

## 2.1 本次验证

- 最终安装包完成 arm64-v8a/x86_64 构建、资源/DEX、签名与 ZIP 对齐检查；Android 11 模拟器覆盖安装并启动成功。
- 22 项现有安卓交互检查全部通过，覆盖人机/双人、触控落子、提示、悔棋、存档恢复与重开，没有运行时崩溃。
- 360×800 与 412×915 布局检查通过；另在 320 dpi / 788×1702 检查实际落子与回着。
- 实际截图检查了小屏边缘棋子、选中圆环、合法落点、提示箭头、双方头像，以及 AI 观战落子与暂停。首页、棋盘和观战截图均更新为最终 APK 的实际画面。
- 棋子绘制为本轮改动，规则与专业引擎代码未修改；未在实体手机实测。

## 2.0 既有验证

- 46 项 Java 检查通过：规则、perft 44/1920/79666、合法对弈/取消、FEN、UCI、基础记谱。
- arm64-v8a/x86_64 编译、ELF 对齐、APK 资源/DEX/签名/ZIP 对齐通过。
- Android 11 / API 30 / x86_64 实际安装运行，覆盖更新保留 1.1 旧棋局，启动先显示首页。
- 35 项首页检查、22 项基础对战检查、26 项观战检查通过，共 83 项。
- 四档真实回着，困难/大师原生库实际加载；大师提示、红黑逐步观战、返回首页时取消通过。
- 检查期间没有 Java 或原生崩溃，小屏 360×800/412×915 与字体 1.3 倍已检查。
- 未在实体 arm64 手机实测；大师不代表正式赛事棋力评级。

## 范围说明

个人离线游戏，不实现账号、联机排行或完整课程系统。休闲规则不自动判罚复杂长将长捉。

旧 Kotlin 验证页面、旧 JNI 桥接和 01～04 文档保留为历史资料，不进入当前安装包；其旧缺陷报告不代表新桥接运行结果。

最终 APK SHA256：`DDDA5ED3D56BDF0A4E7DA9037E7557CCFE9C2CBB43C2F51EDDF23E31853AD4FA`；大小 51811463 字节。
