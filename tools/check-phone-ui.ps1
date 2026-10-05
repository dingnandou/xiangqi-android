param(
    [string]$Adb = 'E:\android-m0\sdk\platform-tools\adb.exe',
    [string]$Device = 'emulator-5580',
    [switch]$HelpersOnly
)
$ErrorActionPreference = 'Stop'
if (-not $Device.StartsWith('emulator-')) { throw '该脚本只用于临时模拟器，会清除测试应用的存档，不能用于实体手机。' }
$package = 'com.yijin.xiangqi.light'
$passed = 0
function Call-Adb([string[]]$Arguments) {
    $result = & $Adb -s $Device @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb 失败：$($Arguments -join ' ')" }
    return $result
}
function Require([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
    Write-Output "PASS: $Name"
}
function Read-Ui {
    Call-Adb @('shell', 'uiautomator', 'dump', '/sdcard/yijin-ui.xml') | Out-Null
    return [xml]((Call-Adb @('shell', 'cat', '/sdcard/yijin-ui.xml')) -join "`n")
}
function Read-State {
    $doc = [xml]((Call-Adb @('shell', 'cat', "/data/user/0/$package/shared_prefs/xiangqi.xml")) -join "`n")
    return ($doc.SelectSingleNode('/map/string[@name="saved"]').InnerText | ConvertFrom-Json)
}
function Rectangle([string]$Bounds) {
    $n = [regex]::Matches($Bounds, '-?\d+') | ForEach-Object { [int]$_.Value }
    return @($n)
}
function Tap-Text([string]$Text) {
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $ui = Read-Ui
        $node = @($ui.SelectNodes('//node') | Where-Object { $_.text -eq $Text -and $_.enabled -eq 'true' -and $_.bounds -ne '[0,0][0,0]' }) | Select-Object -Last 1
        if ($node) {
            $r = Rectangle $node.bounds
            Call-Adb @('shell', 'input', 'tap', [string][int](($r[0]+$r[2])/2), [string][int](($r[1]+$r[3])/2)) | Out-Null
            return
        }
        $scroll = $ui.SelectSingleNode('//node[@scrollable="true"]')
        if (-not $scroll) { break }
        $r = Rectangle $scroll.bounds; $x = [string][int](($r[0]+$r[2])/2)
        $from = [string]($r[3]-35); $to = [string]($r[1]+35)
        if ($attempt -eq 0) { $from = [string]($r[1]+35); $to = [string]($r[3]-35) }
        Call-Adb @('shell','input','swipe',$x,$from,$x,$to,'250') | Out-Null
    }
    throw "找不到控件：$Text"
}
function Tap-Board([int]$Position) {
    $ui = Read-Ui
    $node = $ui.SelectSingleNode('//node[starts-with(@content-desc,"中国象棋棋盘")]')
    $r = Rectangle $node.bounds
    # 测试设备密度固定为 160 dpi，dp 与 px 相等。布局与棋盘几何直接来自当前 UI。
    $width = $r[2]-$r[0]; $height = $r[3]-$r[1]
    $cell = [Math]::Min(($width-12)/8.84, ($height-12)/9.84)
    $x = $r[0] + ($width-8*$cell)/2 + ($Position%9)*$cell
    $y = $r[1] + ($height-9*$cell)/2 + [Math]::Floor($Position/9)*$cell
    Call-Adb @('shell', 'input', 'tap', [string][int]$x, [string][int]$y) | Out-Null
}
function Wait-Reply {
    for ($i=0; $i -lt 80; $i++) {
        $state = Read-State
        if ($state.turn -eq 1 -and @($state.history).Count -ge 2) { return $state }
        Start-Sleep -Milliseconds 150
    }
    throw '手机没有按时完成回着'
}

if ($HelpersOnly) { return }

Call-Adb @('shell','pm','clear',$package) | Out-Null
Call-Adb @('logcat','-c') | Out-Null
$launch = Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity")
Require (($launch -join "`n") -match 'Status: ok') '安卓应用启动'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[@text="掌上象棋"]')) '标题与手机界面可见'
Require (-not [bool]$ui.SelectSingleNode('//node[starts-with(@content-desc,"中国象棋棋盘")]')) '首次启动进入主界面'
Tap-Text '人机对战'; Tap-Text '普通'; Tap-Text '开始对局'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[@text="轮到红方"]')) '红方先走'
Require ([bool]$ui.SelectSingleNode('//node[@text="悔棋" and @enabled="false"]')) '开局不能凭空悔棋'

Tap-Board 54; Tap-Board 45
$state = Wait-Reply
Require ($state.board[45] -eq 7 -and $state.board[54] -eq 0) '触控走红兵成功'
Require (@($state.history).Count -eq 2 -and $state.turn -eq 1) '手机回黑棋后轮到红方'
$initialBoard = $state.history[0].board -join ','
Tap-Text '悔棋'
$state = Read-State
Require (@($state.history).Count -eq 0 -and ($state.board -join ',') -eq $initialBoard) '单人悔棋恢复双方上一轮'

Tap-Text '提示'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[contains(@text,"箭头是一种建议")]')) '提示计算完成'
$state = Read-State
Require (($state.board -join ',') -eq $initialBoard) '提示不会替玩家落子'

Tap-Board 82; Tap-Board 63
$state = Wait-Reply
$savedBoard = $state.board -join ','
Call-Adb @('shell','am','force-stop',$package) | Out-Null
Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[@text="继续上局"]')) '重启先显示主界面和继续入口'
Tap-Text '继续上局'
$ui = Read-Ui
$state = Read-State
Require (($state.board -join ',') -eq $savedBoard -and @($state.history).Count -eq 2) '进程关闭后恢复棋局和悔棋记录'
Require ([bool]$ui.SelectSingleNode('//node[contains(@text,"已恢复上次的棋局")]')) '恢复状态明确显示'

Tap-Text '模式'; Tap-Text '双人：在本机轮流走棋'; Tap-Text '切换'
$state = Read-State
Require ($state.two -eq $true -and @($state.history).Count -eq 0) '切换同机双人并重新摆棋'
Tap-Board 54; Tap-Board 45
Start-Sleep -Milliseconds 800
$state = Read-State
Require ($state.turn -eq -1 -and @($state.history).Count -eq 1) '双人模式不自动替黑方落子'
Tap-Board 27; Tap-Board 36
$state = Read-State
Require ($state.turn -eq 1 -and $state.board[36] -eq -7) '双人黑方触控落子'
Tap-Text '悔棋'
$state = Read-State
Require ($state.turn -eq -1 -and @($state.history).Count -eq 1 -and $state.board[27] -eq -7) '双人悔棋只撤回一着'
Tap-Text '重开'; Tap-Text '重开'
$state = Read-State
Require ($state.turn -eq 1 -and @($state.history).Count -eq 0 -and ($state.board -join ',') -eq $initialBoard) '重新开始完整恢复开局'

foreach ($size in @('360x800','412x915')) {
    Call-Adb @('shell','wm','size',$size) | Out-Null
    $ui = Read-Ui
    Require ([bool]$ui.SelectSingleNode('//node[@text="模式"]') -and [bool]$ui.SelectSingleNode('//node[@text="重开"]')) "小屏适配 $size"
}
Call-Adb @('shell','wm','size','reset') | Out-Null
Tap-Text '模式'; Tap-Text '单人：手机自动走黑棋'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[contains(@text,"单人模式")]')) '回到单人开局'
$runtimeLogs = Call-Adb @('logcat','-d','-s','AndroidRuntime')
Require (-not (($runtimeLogs -join "`n") -match 'FATAL EXCEPTION')) '操作过程中没有安卓运行时崩溃'
Write-Output "PHONE_UI_CHECKS_OK: $passed checks passed"
