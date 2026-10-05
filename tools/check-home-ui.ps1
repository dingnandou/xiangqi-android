param([string]$Adb = 'E:\android-m0\sdk\platform-tools\adb.exe', [string]$Device = 'emulator-5580')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\check-phone-ui.ps1" -Adb $Adb -Device $Device -HelpersOnly
function Wait-Count([int]$Count) {
    for ($i = 0; $i -lt 100; $i++) {
        $state = Read-State
        if (@($state.history).Count -ge $Count) { return $state }
        Start-Sleep -Milliseconds 150
    }
    throw "没有完成 $Count 着"
}
function Start-New([string]$Mode, [string]$Level, [string]$Start = '开始对局') {
    Tap-Text $Mode
    if ($Mode -ne '同机双人') { Tap-Text $Level }
    Tap-Text $Start
    $ui = Read-Ui
    if ($ui.SelectSingleNode('//node[@text="开始新的一局？"]')) { Tap-Text '开始新局' }
}
function Is-Home {
    $ui = Read-Ui
    return [bool]$ui.SelectSingleNode('//node[@text="选择模式"]') -and -not [bool]$ui.SelectSingleNode('//node[starts-with(@content-desc,"中国象棋棋盘")]')
}
Call-Adb @('shell','am','force-stop',$package) | Out-Null
Call-Adb @('shell','pm','clear',$package) | Out-Null
Call-Adb @('logcat','-c') | Out-Null
Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
Require (Is-Home) '首次启动先选择模式和难度'
$ui = Read-Ui
foreach ($text in @('人机对战','同机双人','AI 观战','简单','普通','困难','大师')) {
    Require ([bool]$ui.SelectSingleNode("//node[@text='$text']")) "首页选项：$text"
}
$levels = @('简单','普通','困难','大师')
for ($level = 0; $level -lt 4; $level++) {
    if ($level -gt 0) { Tap-Text '‹ 首页' }
    Start-New '人机对战' $levels[$level]
    $state = Read-State
    Require ($state.level -eq $level -and $state.two -eq $false -and $state.watch -eq $false -and @($state.history).Count -eq 0) "开始 $($levels[$level]) 人机新局"
    Tap-Board 54; Tap-Board 45
    $state = Wait-Reply
    Require ($state.turn -eq 1 -and @($state.history).Count -eq 2 -and $state.board[45] -eq 7) "$($levels[$level]) 对手实际完成黑方回着"
}
$pidValue = (Call-Adb @('shell','pidof',$package)) -join ''
$maps = Call-Adb @('shell','cat',"/proc/$($pidValue.Trim())/maps")
Require (($maps -join "`n") -match 'libstrongengine.so') '困难/大师使用真实 Pikafish 本地库'
$beforeHint = Read-State
Tap-Text '提示'
$hintVisible = $false
for ($hintPoll = 0; $hintPoll -lt 8; $hintPoll++) {
    $ui = Read-Ui
    if ($ui.SelectSingleNode('//node[contains(@text,"箭头是一种建议")]')) { $hintVisible = $true; break }
}
Require $hintVisible '大师级提示计算完成'
$state = Read-State
Require (($state.board -join ',') -eq ($beforeHint.board -join ',')) '专业提示不擅自落子'
Tap-Text '‹ 首页'
$saved = Read-State
Tap-Text '简单'; Tap-Text '继续上局'
$state = Read-State
Require ($state.level -eq 3 -and ($state.board -join ',') -eq ($saved.board -join ',')) '继续上局保留原难度与棋局'
Tap-Text '‹ 首页'
Start-New '同机双人' '普通'
Tap-Board 54; Tap-Board 45
$state = Read-State
Require ($state.two -eq $true -and $state.turn -eq -1 -and @($state.history).Count -eq 1) '首页开启双人且不代替黑方走棋'
Tap-Board 27; Tap-Board 36
$state = Read-State
Require ($state.turn -eq 1 -and @($state.history).Count -eq 2) '双人轮换正常'
Tap-Text '‹ 首页'
Start-New 'AI 观战' '大师' '开始观战'
Tap-Text '暂停'
$paused = Read-State
Tap-Text '下一步'
$step = Wait-Count (@($paused.history).Count + 1)
Require ($step.watch -eq $true -and $step.level -eq 3 -and $step.turn -eq -$paused.turn) '大师 AI 观战可逐步走一着'
Tap-Text '下一步'
$next = Wait-Count (@($step.history).Count + 1)
Require ($next.turn -eq $paused.turn) '大师 AI 可分别执红与执黑'
Start-Sleep -Milliseconds 2800
$state = Read-State
Require (@($state.history).Count -eq @($next.history).Count) '大师逐步观战保持暂停'
Tap-Text '‹ 首页'
Start-New '人机对战' '大师'
$ui = Read-Ui
$homeNode = $ui.SelectSingleNode('//node[@text="‹ 首页"]')
$homeBounds = Rectangle $homeNode.bounds
Tap-Board 54; Tap-Board 45
Call-Adb @('shell','input','tap',[string][int](($homeBounds[0]+$homeBounds[2])/2),[string][int](($homeBounds[1]+$homeBounds[3])/2)) | Out-Null
$homeSaved = Read-State
Require (Is-Home) '专业对手思考时可立即返回首页'
Start-Sleep -Milliseconds 3500
$state = Read-State
Require (($state.board -join ',') -eq ($homeSaved.board -join ',') -and @($state.history).Count -eq @($homeSaved.history).Count) '首页不会收到过期专业引擎回着'
Tap-Text '继续上局'
$state = Wait-Reply
Require ($state.turn -eq 1 -and @($state.history).Count -eq 2) '继续棋局后专业对手恢复计算'
Call-Adb @('shell','input','keyevent','KEYCODE_BACK') | Out-Null
Require (Is-Home) '系统返回键回到主界面'
Tap-Text '继续上局'; Tap-Text '模式'; Tap-Text '结束本局 / 认输'; Tap-Text '认输'
$state = Read-State
Require ($state.winner -eq -1 -and $state.reason -eq '认输') '认输结束棋局并保存结果'
Tap-Text '‹ 首页'; Tap-Text '设置'; Tap-Text '落子震动'; Tap-Text '完成'
Call-Adb @('shell','am','force-stop',$package) | Out-Null
Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
Require (Is-Home) '关闭重开始终停留主界面'
Tap-Text '设置'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[@text="落子震动" and @checked="false"]')) '设置跨进程保存'
Tap-Text '完成'
try {
    Call-Adb @('shell','wm','size','360x800') | Out-Null
    Call-Adb @('shell','settings','put','system','font_scale','1.3') | Out-Null
    Call-Adb @('shell','am','force-stop',$package) | Out-Null
    Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
    Require (Is-Home) '小屏大字体首页可用'
    Tap-Text '开始对局'
    $ui = Read-Ui
    Require ([bool]$ui.SelectSingleNode('//node[starts-with(@content-desc,"中国象棋棋盘")]')) '小屏大字体可滚动到开始按钮并进入棋局'
} finally {
    Call-Adb @('shell','settings','put','system','font_scale','1.0') | Out-Null
    Call-Adb @('shell','wm','size','reset') | Out-Null
}
$logs = Call-Adb @('logcat','-d','-s','AndroidRuntime','libc')
Require (-not (($logs -join "`n") -match 'FATAL EXCEPTION|Fatal signal')) '完整主界面与四档对战无 Java 或原生崩溃'
Write-Output "HOME_UI_CHECKS_OK: $passed checks passed"
