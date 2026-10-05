param(
    [string]$Adb = 'E:\android-m0\sdk\platform-tools\adb.exe',
    [string]$Device = 'emulator-5580'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\check-phone-ui.ps1" -Adb $Adb -Device $Device -HelpersOnly

function Wait-Moves([int]$Minimum) {
    for ($i = 0; $i -lt 120; $i++) {
        $state = Read-State
        if (@($state.history).Count -ge $Minimum) { return $state }
        Start-Sleep -Milliseconds 150
    }
    throw "AI 未完成 $Minimum 着"
}
function Require-Stationary([object]$Before, [string]$Name) {
    Start-Sleep -Milliseconds 3000
    $after = Read-State
    Require (($after.board -join ',') -eq ($Before.board -join ',') -and $after.turn -eq $Before.turn -and @($after.history).Count -eq @($Before.history).Count) $Name
}

Call-Adb @('shell','am','force-stop',$package) | Out-Null
Call-Adb @('shell','pm','clear',$package) | Out-Null
Call-Adb @('logcat','-c') | Out-Null
Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
Tap-Text '模式'; Tap-Text '观战：AI 对战 AI'
$state = Wait-Moves 4
Require ($state.watch -eq $true -and $state.two -eq $false) '进入 AI 双方观战'
Require ($state.history[0].turn -eq 1 -and $state.history[1].turn -eq -1 -and $state.history[2].turn -eq 1 -and $state.history[3].turn -eq -1) '红黑 AI 连续交替走棋'
Tap-Text '暂停'
$paused = Read-State
Require-Stationary $paused '暂停后停止走棋且丢弃未完成回着'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[contains(@text,"观战已暂停")]') -and [bool]$ui.SelectSingleNode('//node[@text="下一步" and @enabled="true"]')) '暂停状态与逐步控件可见'
Tap-Board 54; Tap-Board 45
Require-Stationary $paused '观战不能手动移动棋子'
Tap-Text '下一步'
$state = Wait-Moves (@($paused.history).Count + 1)
Require (@($state.history).Count -eq @($paused.history).Count + 1 -and $state.turn -eq -$paused.turn) '下一步只推进一着并换边'
Require-Stationary $state '逐步之后继续暂停'
$beforeHint = Read-State
Tap-Text '提示'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[contains(@text,"箭头是一种建议")]')) '暂停观战可查看下一方提示'
Require-Stationary $beforeHint '观战提示不自动落子'
Tap-Text '悔棋'
$state = Read-State
Require (@($state.history).Count -eq @($paused.history).Count -and ($state.board -join ',') -eq ($paused.board -join ',')) '观战悔棋仅撤回一着'
Require-Stationary $state '观战悔棋后保持暂停'

Tap-Text '间隔 2 秒'; Tap-Text '慢速：每着间隔 4 秒'
$state = Read-State
Require ($state.watchDelay -eq 4000) '观战速度设置已保存'
Tap-Text '继续'
$state = Wait-Moves (@($state.history).Count + 2)
Require ($state.watch -eq $true) '继续后双方重新自动走棋'
Call-Adb @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
$background = Read-State
Require-Stationary $background '退到后台停止观战计算'
Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[contains(@text,"观战已暂停")]')) '返回前台仍暂停'
$saved = Read-State
Call-Adb @('shell','am','force-stop',$package) | Out-Null
Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
$ui = Read-Ui
$state = Read-State
Require (($state.board -join ',') -eq ($saved.board -join ',') -and $state.watch -eq $true -and $state.watchDelay -eq 4000) '重启恢复观战棋局和速度'
Require ([bool]$ui.SelectSingleNode('//node[@text="继续"]')) '恢复后由用户继续观战'
Require-Stationary $state '恢复观战不会偷偷落子'

foreach ($size in @('360x800','412x915')) {
    Call-Adb @('shell','wm','size',$size) | Out-Null
    $ui = Read-Ui
    Require ([bool]$ui.SelectSingleNode('//node[@text="下一步"]') -and [bool]$ui.SelectSingleNode('//node[@text="模式"]')) "观战控件小屏适配 $size"
}
Call-Adb @('shell','wm','size','reset') | Out-Null
Tap-Text '重开'; Tap-Text '重开'
$ui = Read-Ui
Tap-Text '暂停'
Tap-Text '模式'; Tap-Text '对手难度'; Tap-Text '轻松'
$ui = Read-Ui
Require ([bool]$ui.SelectSingleNode('//node[contains(@text,"轻松 AI")]')) '双方 AI 难度可调整'
Tap-Text '下一步'
$state = Wait-Moves 1
Require (@($state.history).Count -eq 1) '重开与难度切换后可逐步走棋'
Tap-Text '模式'; Tap-Text '单人：手机自动走黑棋'; Tap-Text '切换'
$ui = Read-Ui
$state = Read-State
Require ($state.watch -eq $false -and $state.two -eq $false -and @($state.history).Count -eq 0) '观战切回单人并重新摆棋'
Require (-not [bool]$ui.SelectSingleNode('//node[@text="下一步"]')) '单人模式隐藏观战控件'
Tap-Board 54; Tap-Board 45
$state = Wait-Reply
Require (@($state.history).Count -eq 2 -and $state.turn -eq 1) '退出观战后单人自动回着正常'
$runtimeLogs = Call-Adb @('logcat','-d','-s','AndroidRuntime')
Require (-not (($runtimeLogs -join "`n") -match 'FATAL EXCEPTION')) '观战操作无安卓运行时崩溃'
Write-Output "WATCH_UI_CHECKS_OK: $passed checks passed"
