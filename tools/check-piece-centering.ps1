param(
    [string]$Adb = 'E:\android-m0\sdk\platform-tools\adb.exe',
    [string]$Device = 'emulator-5580',
    [double]$TolerancePixels = 2
)
$ErrorActionPreference = 'Stop'
if (-not $Device.StartsWith('emulator-')) { throw '仅用于临时测试模拟器。' }
. "$PSScriptRoot\check-phone-ui.ps1" -Adb $Adb -Device $Device -HelpersOnly
Add-Type -AssemblyName System.Drawing
$ui = Read-Ui
$node = $ui.SelectSingleNode('//node[starts-with(@content-desc,"中国象棋棋盘")]')
if (-not $node) { throw '请先进入未选子、未显示提示箭头的棋盘。' }
$bounds = Rectangle $node.bounds
$state = Read-State
$densityText = (Call-Adb @('shell', 'wm', 'density')) -join "`n"
$densityMatch = [regex]::Match($densityText, '(?m)(?:Override|Physical) density: (\d+)(?![\s\S]*Override density:)')
if (-not $densityMatch.Success) { throw '无法读取屏幕密度' }
$dpi = [int]$densityMatch.Groups[1].Value
$density = $dpi / 160.0
$width = $bounds[2] - $bounds[0]; $height = $bounds[3] - $bounds[1]
$cell = [Math]::Min(($width - [Math]::Round(12 * $density)) / 8.84,
                    ($height - [Math]::Round(12 * $density)) / 9.84)
$ox = $bounds[0] + ($width - 8 * $cell) / 2
$oy = $bounds[1] + ($height - 9 * $cell) / 2
$radius = .44 * $cell
$projectRoot = Split-Path $PSScriptRoot -Parent
$screenshot = Join-Path $projectRoot "output\glyph-centering-$dpi.png"
Call-Adb @('shell','screencap','-p','/sdcard/yijin-glyph-centering.png') | Out-Null
Call-Adb @('pull','/sdcard/yijin-glyph-centering.png',$screenshot) | Out-Null
$bitmap = [Drawing.Bitmap]::new($screenshot)
$wood = [Drawing.Bitmap]::new((Join-Path $projectRoot 'app\src\main\assets\wood-piece.png'))
$measurements = [Collections.Generic.List[object]]::new()
try {
    for ($at = 0; $at -lt 90; $at++) {
        if ($state.board[$at] -eq 0) { continue }
        $red = $state.board[$at] -gt 0
        $cx = $ox + ($at % 9) * $cell
        $cy = $oy + [Math]::Floor($at / 9) * $cell
        $minX = $bitmap.Width; $minY = $bitmap.Height; $maxX = -1; $maxY = -1; $pixels = 0
        for ($y = [int][Math]::Floor($cy - .69 * $radius); $y -le [Math]::Ceiling($cy + .69 * $radius); $y++) {
            for ($x = [int][Math]::Floor($cx - .69 * $radius); $x -le [Math]::Ceiling($cx + .69 * $radius); $x++) {
                $color = $bitmap.GetPixel($x, $y)
                if (($x + .5 - $cx) * ($x + .5 - $cx) + ($y + .5 - $cy) * ($y + .5 - $cy) -gt (.9 * $radius) * (.9 * $radius)) { continue }
                # Compare with the unlettered sprite so faint brush tips are not mistaken
                # for wood, and the caramel rim cannot be mistaken for red/black ink.
                $sx = [int][Math]::Floor((.492 + ($x + .5 - $cx) / $radius * .439) * $wood.Width)
                $sy = [int][Math]::Floor((.452 + ($y + .5 - $cy) / $radius * .439) * $wood.Height)
                $base = $wood.GetPixel($sx, $sy)
                $isInk = if ($red) { $base.G - $color.G -gt 18 -and $color.R - $color.G -gt 45 }
                         else { $base.R - $color.R -gt 18 -and $color.R - $color.G -lt 45 }
                if (-not $isInk) { continue }
                $minX = [Math]::Min($minX, $x); $maxX = [Math]::Max($maxX, $x)
                $minY = [Math]::Min($minY, $y); $maxY = [Math]::Max($maxY, $y); $pixels++
            }
        }
        if ($pixels -lt 8) { throw "棋子 $at 未检测到完整棋字" }
        # Compare actual colored pixels, independently of font metrics and path bounds.
        $dx = ($minX + $maxX + 1) / 2 - $cx
        $dy = ($minY + $maxY + 1) / 2 - $cy
        if ([Math]::Abs($dx) -gt $TolerancePixels -or [Math]::Abs($dy) -gt $TolerancePixels) {
            throw "棋子 $at 未居中：x=$dx, y=$dy 像素"
        }
        $measurements.Add([pscustomobject]@{Position=$at; Piece=$state.board[$at]; ErrorX=[Math]::Round($dx,3); ErrorY=[Math]::Round($dy,3); InkPixels=$pixels})
    }
} finally { $bitmap.Dispose(); $wood.Dispose() }
if ($measurements.Count -ne 32) { throw '居中检查需要完整的 32 枚棋子。' }
$report = Join-Path $projectRoot "output\glyph-centering-$dpi.json"
$measurements | ConvertTo-Json | Set-Content -LiteralPath $report -Encoding UTF8
Write-Output "PIECE_CENTERING_OK: $($measurements.Count) pieces, $dpi dpi, tolerance $TolerancePixels pixels"
