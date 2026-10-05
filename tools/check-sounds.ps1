param(
    [string]$Adb = 'E:\android-m0\sdk\platform-tools\adb.exe',
    [string]$Device = 'emulator-5580'
)
$ErrorActionPreference = 'Stop'
# Shared helpers restrict this destructive fixture setup to a temporary emulator.
. (Join-Path $PSScriptRoot 'check-phone-ui.ps1') -Adb $Adb -Device $Device -HelpersOnly
$projectRoot = Split-Path $PSScriptRoot -Parent
Call-Adb @('shell','wm','size','reset') | Out-Null
Call-Adb @('shell','wm','density','reset') | Out-Null
Call-Adb @('shell','settings','put','system','sound_effects_enabled','0') | Out-Null
Call-Adb @('shell','cmd','media_session','volume','--stream','3','--set','10') | Out-Null
Call-Adb @('shell','pm','clear',$package) | Out-Null
Call-Adb @('logcat','-c') | Out-Null

function Start-Game {
    Call-Adb @('shell','am','start','-W','-n',"$package/.MainActivity") | Out-Null
}
function Sound-Row {
    (Read-Ui).SelectSingleNode('//node[@text="音效（落子、吃子、将军）"]')
}
function Audio-State {
    $appProcess = (Call-Adb @('shell','pidof',$package)) -join ''
    $lines = @(Call-Adb @('shell','dumpsys','-t','1','media.audio_flinger'))
    $rows = @($lines | Where-Object { $_ -match "\bS\s+\d+\s+(yes|no)\s+$appProcess\s+" })
    return @{ Rows = $rows -join "`n"; Adds = (@($rows | Where-Object { $_ -match 'AT::add' }) -join "`n") }
}
function Require-Played([string]$Sound, [string]$Label) {
    $bytes = [System.IO.File]::ReadAllBytes((Join-Path $projectRoot "app\src\main\res\raw\$Sound.wav"))
    $frames = [BitConverter]::ToInt32($bytes,40) / 2
    $hex = ('{0:X8}' -f [int]$frames)
    $audio = Audio-State
    Require ($audio.Rows -match "\b$hex\s+$frames\s+0\s+f") $Label
}
function Load-Fixture([bool]$Capture, [bool]$SinglePlayer = $false) {
    Call-Adb @('shell','am','force-stop',$package) | Out-Null
    $board = New-Object int[] 90
    $board[4] = -1; $board[85] = 1; $board[49] = 7
    if ($Capture) { $board[18] = 5; $board[9] = -7 } else { $board[14] = 5 }
    $state = @{ version=2; board=$board; turn=1; two=(!$SinglePlayer); level=0; watch=$false;
        watchDelay=2000; winner=0; reason=''; ply=0; from=-1; to=-1; history=@() }
    $json = $state | ConvertTo-Json -Compress -Depth 5
    $xml = '<?xml version="1.0" encoding="utf-8"?><map><string name="saved">' + [System.Security.SecurityElement]::Escape($json) + '</string></map>'
    $local = Join-Path $projectRoot 'output\sound-fixture.xml'
    [System.IO.File]::WriteAllText($local, $xml, (New-Object System.Text.UTF8Encoding($false)))
    Call-Adb @('push',$local,'/data/local/tmp/xiangqi-sound-fixture.xml') | Out-Null
    Call-Adb @('shell','cp','/data/local/tmp/xiangqi-sound-fixture.xml',"/data/user/0/$package/shared_prefs/xiangqi.xml") | Out-Null
    Start-Game
    Tap-Text '继续上局'
}

Start-Game
Tap-Text '设置'
Require ((Sound-Row).checked -eq 'true') '首次安装默认开启音效'
Tap-Text '完成'; Tap-Text '同机双人'; Tap-Text '开始对局'
Tap-Board 54; Tap-Board 45
Require-Played 'move' '合法落子完整输出木质敲击 PCM'
$before = (Audio-State).Adds
Tap-Board 27
Require ((Audio-State).Adds -eq $before) '选中棋子不会播放音效'

Load-Fixture $true
Require ((Audio-State).Adds -eq '') '恢复棋局不自动播音'
Tap-Board 18; Tap-Board 9
Require ((Read-State).board[9] -eq 5) '吃子场景确实完成合法吃子'
Require-Played 'capture' '吃子完整输出独立的双敲 PCM'

Load-Fixture $false
Tap-Board 14; Tap-Board 13
Require ([bool](Read-Ui).SelectSingleNode('//node[contains(@text,"将军")]')) '将军场景确实使对方被将军'
Require-Played 'check' '将军完整输出中文语音 PCM'
$before = (Audio-State).Adds
Tap-Text '悔棋'
Require ((Audio-State).Adds -eq $before) '悔棋不重复播报将军'

Tap-Text '设置'; Tap-Text '音效（落子、吃子、将军）'
Require ((Sound-Row).checked -eq 'false') '设置能够关闭音效'
Tap-Text '完成'
Load-Fixture $false
Tap-Text '设置'
Require ((Sound-Row).checked -eq 'false') '关闭音效跨进程保存'
Tap-Text '完成'; Tap-Board 14; Tap-Board 13
Require ((Audio-State).Adds -eq '') '关闭后实际将军也不创建音频轨道'

Tap-Text '设置'; Tap-Text '音效（落子、吃子、将军）'; Tap-Text '完成'
Load-Fixture $false $true
Tap-Text '设置'
Require ((Sound-Row).checked -eq 'true') '开启音效跨进程保存'
Tap-Text '完成'; Tap-Board 14; Tap-Board 13
for ($i = 0; $i -lt 40 -and (Read-State).turn -ne 1; $i++) { Start-Sleep -Milliseconds 100 }
Require ((Read-State).turn -eq 1) '简单 AI 实际完成解将回着'
Require-Played 'check' '快速 AI 回着没有截断将军语音'

# A board refresh on returning from the background must not replay the last move.
Call-Adb @('shell','input','keyevent','3') | Out-Null
Start-Sleep -Milliseconds 800
$before = (Audio-State).Adds
Start-Game
Require ((Audio-State).Adds -eq $before) '后台返回不重复播放上一着音效'
Tap-Text '模式'; Tap-Text '返回首页'
$before = (Audio-State).Adds
Start-Sleep -Milliseconds 800
Require ((Audio-State).Adds -eq $before) '返回首页保持安静'

Tap-Text 'AI 观战'; Tap-Text '简单'; Tap-Text '开始观战'; Tap-Text '开始新局'; Tap-Text '暂停'
$beforeWatch = Read-State
Tap-Text '下一步'
$afterWatch = Read-State
Require (@($afterWatch.history).Count -eq @($beforeWatch.history).Count + 1) '观战实际推进一着'
$ui = Read-Ui
$watchSound = if ([bool]$ui.SelectSingleNode('//node[contains(@text,"将军")]')) { 'check' }
    elseif ($beforeWatch.board[$afterWatch.to] -ne 0) { 'capture' } else { 'move' }
Require-Played $watchSound '观战根据真实走法完整播放对应音效'
Start-Sleep -Milliseconds 1000
Require (@((Read-State).history).Count -eq @($afterWatch.history).Count) '音效不影响观战逐步暂停'

$logs = (Call-Adb @('logcat','-d','-s','GameSounds','AndroidRuntime')) -join "`n"
Require ($logs -notmatch 'Sound load failed|FATAL EXCEPTION') '音频资源加载和运行没有异常'
Write-Output "SOUND_CHECKS_OK: $passed checks passed (PCM consumption verified, not human listening)"
