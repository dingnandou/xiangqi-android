param(
    [string]$Sdk = 'E:\android-m0\sdk',
    [string]$Jdk = 'E:\android-m0\jdk\jdk-17.0.20.1+1'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$sourceRoot = Join-Path $projectRoot 'app\src\main'
$buildRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('yijin-phone-build-' + [guid]::NewGuid().ToString('N'))
$outputRoot = Join-Path $projectRoot 'output'
$toolsRoot = Join-Path $Sdk 'build-tools\36.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-36\android.jar'
$javaExe = Join-Path $Jdk 'bin\java.exe'
$javacExe = Join-Path $Jdk 'bin\javac.exe'
$jarExe = Join-Path $Jdk 'bin\jar.exe'
$keytoolExe = Join-Path $Jdk 'bin\keytool.exe'
foreach ($requiredPath in @($androidJar, $javaExe, $javacExe, (Join-Path $toolsRoot 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "缺少构建工具：$requiredPath" }
}
foreach ($folder in @($buildRoot, $outputRoot, "$buildRoot\generated", "$buildRoot\classes", "$buildRoot\dex", "$buildRoot\source", "$buildRoot\source\java", "$projectRoot\.local-keys")) {
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
}
function Assert-Exit([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step 失败：退出码 $LASTEXITCODE" } }

& "$PSScriptRoot\build-strong-engine.ps1" -Sdk $Sdk
New-Item -ItemType Directory -Path "$buildRoot\source\assets", "$buildRoot\native\lib" -Force | Out-Null
Copy-Item -LiteralPath "$projectRoot\engine\src\main\assets\pikafish.nnue" -Destination "$buildRoot\source\assets\pikafish.nnue"
Copy-Item -Path "$sourceRoot\assets\*" -Destination "$buildRoot\source\assets" -Force
foreach ($abi in @('arm64-v8a','x86_64')) {
    New-Item -ItemType Directory -Path "$buildRoot\native\lib\$abi" -Force | Out-Null
    Copy-Item -LiteralPath "$projectRoot\.phone-build\native\$abi\libstrongengine.so" -Destination "$buildRoot\native\lib\$abi\libstrongengine.so"
}

# aapt2 的 Windows 版本不能可靠处理中文目录；用临时英文目录构建，最后复制安装包。
Copy-Item -LiteralPath "$sourceRoot\res" -Destination "$buildRoot\source" -Recurse
Copy-Item -LiteralPath "$sourceRoot\AndroidManifest.xml" -Destination "$buildRoot\source\AndroidManifest.xml"
$stagedManifest = [xml](Get-Content -LiteralPath "$buildRoot\source\AndroidManifest.xml" -Raw -Encoding UTF8)
$stagedManifest.manifest.SetAttribute('package', 'com.yijin.xiangqi.light')
$stagedManifest.Save("$buildRoot\source\AndroidManifest.xml")
Get-ChildItem -LiteralPath "$sourceRoot\java\com\yijin\xiangqi\light" -Filter '*.java' -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination "$buildRoot\source\java"
}

Write-Output '1/6 编译安卓资源'
& "$toolsRoot\aapt2.exe" compile --dir "$buildRoot\source\res" -o "$buildRoot\resources.zip"
Assert-Exit '资源编译'
& "$toolsRoot\aapt2.exe" link -I $androidJar --manifest "$buildRoot\source\AndroidManifest.xml" `
    --min-sdk-version 26 --target-sdk-version 36 --version-code 5 --version-name '2.2' `
    -A "$buildRoot\source\assets" -0 nnue `
    --java "$buildRoot\generated" -o "$buildRoot\unsigned.apk" "$buildRoot\resources.zip"
Assert-Exit '资源链接'

Write-Output '2/6 编译手机游戏'
$javaSources = @(Get-ChildItem -LiteralPath "$buildRoot\source\java" -Filter '*.java' -File | ForEach-Object { $_.FullName })
$javaSources += @(Get-ChildItem -LiteralPath "$buildRoot\generated" -Filter '*.java' -File -Recurse | ForEach-Object { $_.FullName })
& $javacExe --release 8 -encoding UTF-8 -classpath $androidJar -d "$buildRoot\classes" @javaSources
Assert-Exit 'Java 编译'
& $jarExe cf "$buildRoot\classes.jar" -C "$buildRoot\classes" .
Assert-Exit '类文件打包'

Write-Output '3/6 生成安卓 DEX'
& $javaExe -cp "$toolsRoot\lib\d8.jar" com.android.tools.r8.D8 --min-api 26 --lib $androidJar `
    --output "$buildRoot\dex" "$buildRoot\classes.jar"
Assert-Exit 'DEX 编译'
& $jarExe uf "$buildRoot\unsigned.apk" -C "$buildRoot\dex" classes.dex
Assert-Exit '加入 DEX'
& $jarExe uf "$buildRoot\unsigned.apk" -C "$buildRoot\native" lib
Assert-Exit '加入专业引擎'

Write-Output '4/6 对齐安装包'
& "$toolsRoot\zipalign.exe" -f 4 "$buildRoot\unsigned.apk" "$buildRoot\aligned.apk"
Assert-Exit 'APK 对齐'

Write-Output '5/6 签名自用安装包'
$keyFile = Join-Path $projectRoot '.local-keys\phone-debug.jks'
if (-not (Test-Path -LiteralPath $keyFile)) {
    & $keytoolExe -genkeypair -keystore $keyFile -storepass android -keypass android -alias androiddebugkey `
        -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000 -storetype JKS
    Assert-Exit '创建本地调试签名'
}
$apkFile = Join-Path $buildRoot 'xiangqi.apk'
& $javaExe -jar "$toolsRoot\lib\apksigner.jar" sign --ks $keyFile --ks-pass pass:android --key-pass pass:android `
    --out $apkFile "$buildRoot\aligned.apk"
Assert-Exit 'APK 签名'

Write-Output '6/6 检查安装包'
& $javaExe -jar "$toolsRoot\lib\apksigner.jar" verify --verbose $apkFile
Assert-Exit '签名验证'
& "$toolsRoot\zipalign.exe" -c 4 $apkFile
Assert-Exit '对齐验证'
& "$toolsRoot\aapt.exe" dump badging $apkFile
Assert-Exit '包结构检查'
$finalApk = Join-Path $outputRoot '掌上象棋.apk'
Copy-Item -LiteralPath $apkFile -Destination $finalApk -Force
Get-Item -LiteralPath $finalApk | Select-Object FullName, Length
