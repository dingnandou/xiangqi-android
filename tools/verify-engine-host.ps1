<#
    弈进象棋 · 宿主机引擎自检（M0 辅助验证）

    在 PC 上编译并运行 vendored Pikafish，验证：
      1. 源码可用第二个工具链独立编译（不是只有 NDK 能编）
      2. pikafish.nnue 与该源码版本配套（不配套时 verify_network() 会 exit）
      3. 能对初始局面给出合法最佳着
      4. 搜索途中 stop 不会挂死

    本脚本不验证 arm64 安卓真机行为，也不进入正式验收结论。

    用法：
      powershell -ExecutionPolicy Bypass -File tools\verify-engine-host.ps1 `
          -Gpp D:\MinGW\MINGW\mingw64\bin\g++.exe `
          -Net  E:\android-m0\downloads\pikafish.nnue
#>
param(
    [string]$Gpp   = 'g++',
    [string]$Net   = 'pikafish.nnue',
    [string]$Out   = 'E:\android-m0\hostbuild',
    [int]   $MoveTimeMs = 1000
)

$ErrorActionPreference = 'Stop'

$repoRoot  = Split-Path $PSScriptRoot -Parent
$pikafish  = Join-Path $repoRoot 'engine\src\main\cpp\pikafish'
$smokeSrc  = Join-Path $PSScriptRoot 'host_smoke.cpp'

if (-not (Get-Command $Gpp -ErrorAction SilentlyContinue)) {
    throw "找不到 C++ 编译器：$Gpp（请用 -Gpp 指定 g++ 路径）"
}
if (-not (Test-Path $Net)) {
    throw "找不到网络文件：$Net（请用 -Net 指定 pikafish.nnue 路径）"
}

New-Item -ItemType Directory -Force -Path $Out | Out-Null

# 与 Pikafish Makefile 的 x86-64 默认档一致：sse + sse2，无 prefetch，无 popcnt。
$flags = @(
    '-std=c++17', '-O3', '-funroll-loops', '-fno-exceptions', '-DNDEBUG',
    '-m64', '-msse', '-msse2', '-DUSE_SSE2', '-DNO_PREFETCH', '-DIS_64BIT'
)

$sources = @()
$sources += Get-ChildItem $pikafish -Filter '*.cpp' -File | ForEach-Object { $_.FullName }
# external 下同时收 .cpp 与 .S：x86-64 宿主机需要 huf_decompress_amd64.S 里的汇编实现。
# arm64 安卓构建相反，必须排除该文件，见 engine/src/main/cpp/CMakeLists.txt。
$sources += Get-ChildItem (Join-Path $pikafish 'external') -Recurse -File |
    Where-Object { $_.Extension -eq '.cpp' -or $_.Extension -eq '.S' } | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $pikafish 'nnue') -Recurse -Filter '*.cpp' -File | ForEach-Object { $_.FullName }
$sources += $smokeSrc

Write-Host "编译 $($sources.Count) 个源文件 ..."
$exe = Join-Path $Out 'host_smoke.exe'
& $Gpp @flags "-I$pikafish" @sources -o $exe -lstdc++fs -lpthread
if ($LASTEXITCODE -ne 0) { throw "编译失败，退出码 $LASTEXITCODE" }

# 网络文件必须放在可执行文件旁边：Windows 上 CommandLine::get_binary_directory()
# 用 GetModuleFileNameW 覆盖 argv0（misc.cpp:616），只认 exe 所在目录。
# 安卓侧对应的是「把 assets 里的网络复制到 filesDir 再交给引擎」，同一个约定。
$stagedNet = Join-Path $Out 'pikafish.nnue'
Copy-Item $Net $stagedNet -Force
Write-Host ("网络文件已就位：{0}（{1:N2} MB）" -f $stagedNet, ((Get-Item $stagedNet).Length / 1MB))

Write-Host "编译完成，开始自检（movetime=$MoveTimeMs ms）"
& $exe $stagedNet $MoveTimeMs
if ($LASTEXITCODE -ne 0) { throw "自检失败，退出码 $LASTEXITCODE" }

Write-Host ''
Write-Host "=== 再测一次「搜索途中停止」 ==="
& $exe $stagedNet 10000 stop
if ($LASTEXITCODE -ne 0) { throw "停止测试失败，退出码 $LASTEXITCODE" }

Write-Host 'HOST_VERIFY_OK'