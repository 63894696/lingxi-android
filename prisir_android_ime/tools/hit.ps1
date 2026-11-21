param([string]$Key)
# 从 KeyboardView 的 DEBUG 命中区日志里取某个键的**屏幕绝对中心坐标**。
# 用法: .\hit.ps1 "key:n"  ->  输出 "840 1707"
# 自绘键盘没有 accessibility 节点，uiautomator 看不到，只能靠这份输出标定。
# 按 **进程** 过滤读日志（--pid）：快、且不会被别的 tag 的日志挤掉。
# ⚠️ 不要用 `logcat -d -t N`：-t 是"先取最近 N 行再过滤"，N 太小会一条 HITS 都读不到。
$imePid = (adb shell pidof com.lingxi.ime 2>&1 | Out-String).Trim()
if ($imePid -match "^\d+") {
    $line = (adb logcat -d -s LingxiK --pid $imePid 2>&1 | Select-String "HITS origin" | Select-Object -Last 1)
} else {
    $line = (adb logcat -d -s LingxiK 2>&1 | Select-String "HITS origin" | Select-Object -Last 1)
}
if (-not $line) { Write-Output "NOHITS"; exit 1 }
$txt = $line.ToString()
$o = [regex]::Match($txt, 'origin=\((\d+),(\d+)\)')
if (-not $o.Success) { Write-Output "NOORIGIN"; exit 1 }
$ox = [int]$o.Groups[1].Value
$oy = [int]$o.Groups[2].Value
foreach ($m in [regex]::Matches($txt, '([^\s@]+)@(\d+),(\d+),(\d+),(\d+)')) {
    $id = $m.Groups[1].Value
    if ($id -like "*$Key*") {
        $l = [int]$m.Groups[2].Value
        $t = [int]$m.Groups[3].Value
        $r = [int]$m.Groups[4].Value
        $b = [int]$m.Groups[5].Value
        $x = [int](($l + $r) / 2) + $ox
        $y = [int](($t + $b) / 2) + $oy
        Write-Output "$x $y"
        exit 0
    }
}
Write-Output "NOTFOUND:$Key"
exit 1
