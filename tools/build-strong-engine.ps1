param([string]$Sdk = 'E:\android-m0\sdk')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$ndkRoot = Join-Path $Sdk 'ndk\28.2.13676358'
$cmake = Join-Path $Sdk 'cmake\3.22.1\bin\cmake.exe'
$ninja = Join-Path $Sdk 'cmake\3.22.1\bin\ninja.exe'
$nativeRoot = Join-Path ([IO.Path]::GetTempPath()) 'yijin-strong-engine-v12'
$nativeSource = Join-Path $nativeRoot 'source'
New-Item -ItemType Directory -Path $nativeSource -Force | Out-Null
Copy-Item -LiteralPath "$projectRoot\app\src\main\cpp\CMakeLists.txt", "$projectRoot\app\src\main\cpp\strong_bridge.cpp" -Destination $nativeSource -Force
Copy-Item -LiteralPath "$projectRoot\engine\src\main\cpp\pikafish" -Destination $nativeSource -Recurse -Force
foreach ($abi in @('arm64-v8a','x86_64')) {
    Write-Output "编译专业对手：$abi"
    $abiBuild = Join-Path $nativeRoot $abi
    & $cmake -S $nativeSource -B $abiBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndkRoot\build\cmake\android.toolchain.cmake" "-DANDROID_ABI=$abi" `
        '-DANDROID_PLATFORM=android-26' '-DANDROID_STL=c++_static' '-DCMAKE_BUILD_TYPE=Release' "-DPIKAFISH_DIR=$nativeSource\pikafish"
    if ($LASTEXITCODE -ne 0) { throw "配置 $abi 失败" }
    & $cmake --build $abiBuild --parallel 3
    if ($LASTEXITCODE -ne 0) { throw "编译 $abi 失败" }
    $destination = Join-Path $projectRoot ".phone-build\native\$abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath "$abiBuild\libstrongengine.so" -Destination "$destination\libstrongengine.so" -Force
    & "$ndkRoot\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe" --strip-unneeded "$destination\libstrongengine.so"
    if ($LASTEXITCODE -ne 0) { throw "精简 $abi 库失败" }
}
Write-Output 'STRONG_ENGINE_BUILD_OK'
