param([string]$Keys, [int]$DelayMs = 320)
# 在灵犀自绘键盘上连打一串字母（26 键）或点某个 id。
#
# 用法：
#   .\typer.ps1 "nhjsg"          # 依次点 n h j s g
#   .\typer.ps1 "key:tool:trans" # 点工具键（译）
#   .\typer.ps1 "bsp:5"          # 连按 5 次删除键
#
# 为什么不能只用 hit.ps1 一次取全部坐标：
# 键盘视图的**屏幕位置会变**（输入法窗口高度、状态页滚动、软键盘弹出收起），
# HITS 日志里的 origin=(x,y) 跟着变 —— 坐标算错就会点到隔壁键
# （实测把 n 打成 j，"nhjsg" 变成 "hjsg"）。
# 所以这里**每个键都重新抓一次 HITS**，用当次最新的 origin。
$ErrorActionPreference = "Stop"

$script:ImePid = ""
function Get-ImePid {
    if ($script:ImePid -ne "") { return $script:ImePid }
    $p = (adb shell pidof com.lingxi.ime 2>&1 | Out-String).Trim()
    if ($p -match "^\d+") { $script:ImePid = $p }
    return $script:ImePid
}

function Get-Hits {
    # 按 **进程** 过滤读取（`--pid`）：只取 IME 自己的日志 → 读得快、不会被别的 tag 挤掉。
    # ⚠️ `logcat -d -t N -s TAG` 的 -t 是"先取最近 N 行再过滤"，N 太小会一条都读不到
    #   （实测 -t 400 -s LingxiK 只剩 2 行且没有 HITS）→ 千万别用 -t。
    $pid1 = Get-ImePid
    $line = if ($pid1 -ne "") {
        (adb logcat -d -s LingxiK --pid $pid1 2>&1 | Select-String "HITS origin" | Select-Object -Last 1)
    } else {
        (adb logcat -d -s LingxiK 2>&1 | Select-String "HITS origin" | Select-Object -Last 1)
    }
    if (-not $line) { return $null }
    $txt = $line.ToString()
    $o = [regex]::Match($txt, 'origin=\((\d+),(\d+)\)')
    if (-not $o.Success) { return $null }
    @{
        ox = [int]$o.Groups[1].Value
        oy = [int]$o.Groups[2].Value
        txt = $txt
    }
}

function Get-StableHits {
    # 键盘弹起/收起时视图位置会变，HITS 的 origin 跟着变 —— 用**过期**的 origin 算坐标
    # 会打到隔壁键（实测第一键 6 被打成 9，"64426" 变 "94426"）。
    # 判定标准：**连续 3 次**抓到的内容完全一致（同布局同位置），才算稳定。
    # 只要求 2 次不够：键盘刚弹起时的过渡态在几百毫秒内 origin 可能一直是同一个旧值。
    $prev = $null
    $same = 0
    for ($i = 0; $i -lt 12; $i++) {
        $h = Get-Hits
        if ($null -eq $h) { Start-Sleep -Milliseconds 250; $prev = $null; $same = 0; continue }
        if ($null -ne $prev -and $h.oy -eq $prev.oy -and $h.txt -eq $prev.txt) {
            $same++
            if ($same -ge 3) { return $h }
        } else {
            $same = 0
        }
        $prev = $h
        Start-Sleep -Milliseconds 300
    }
    return (Get-Hits)
}

function Get-LastTouch {
    $line = (adb logcat -d -s LingxiK --pid (Get-ImePid) 2>&1 | Select-String "TOUCH" | Select-Object -Last 1)
    if (-not $line) { return $null }
    $m = [regex]::Match($line.ToString(), 'raw=\((-?\d+),(-?\d+)\)')
    if (-not $m.Success) { return $null }
    @([int]$m.Groups[1].Value, [int]$m.Groups[2].Value)
}

function Calibrate {
    # **闭环校准**：系统对"注入"的触摸事件会做坐标补偿（华为实测：前几次点击整体 +84px，
    # 之后恢复正常）→ 真人点击不受影响（raw 就是手指位置），但自动化会点歪
    # （6 键被点成 9 键，"64426" 变 "94426"）。
    #
    # 这里在**键盘顶部空白**（不命中任何键 → 无副作用）连续点几次，
    # 用 KeyboardView 的 TOUCH 调试日志核对"事件实际落点 == 请求坐标"，
    # 相等即认为系统补偿消失，可以正式开始敲了。
    for ($i = 0; $i -lt 8; $i++) {
        $h = Get-StableHits
        if ($null -eq $h) { return $false }
        $tx = 60
        $ty = [int]$h.oy + 12
        adb shell input tap $tx $ty | Out-Null
        Start-Sleep -Milliseconds 260
        $raw = Get-LastTouch
        if ($null -ne $raw -and $raw[0] -eq $tx -and $raw[1] -eq $ty) { return $true }
    }
    return $false
}

function Get-KeyCenter($h, [string]$pattern) {
    foreach ($m in [regex]::Matches($h.txt, '([^\s@]+)@(\d+),(\d+),(\d+),(\d+)')) {
        $id = $m.Groups[1].Value
        if ($id -like "*$pattern*") {
            $l = [int]$m.Groups[2].Value
            $t = [int]$m.Groups[3].Value
            $r = [int]$m.Groups[4].Value
            $b = [int]$m.Groups[5].Value
            $cxp = [int](($l + $r) / 2) + [int]$h.ox
            $cyp = [int](($t + $b) / 2) + [int]$h.oy
            return @($cxp, $cyp)
        }
    }
    return $null
}

if ($Keys -match '^bsp:(\d+)$') {
    Calibrate | Out-Null
    $n = [int]$matches[1]
    for ($i = 0; $i -lt $n; $i++) {
        $h = Get-StableHits
        $c = Get-KeyCenter $h "DELETE:"
        if ($null -eq $c) { Write-Output "MISS-DELETE"; exit 1 }
        adb shell input tap $c[0] $c[1] | Out-Null
        Start-Sleep -Milliseconds $DelayMs
    }
    Write-Output "OK bsp x$n"
    exit 0
}
if ($Keys -match '^cand:(.+)$') {
    # 点候选词：id 形如 "cand:你好"
    Calibrate | Out-Null
    $h = Get-StableHits
    if ($null -eq $h) { Write-Output "NOHITS"; exit 1 }
    $c = Get-KeyCenter $h "cand:$($matches[1])"
    if ($null -eq $c) {
        Write-Output "MISS-CAND:$($matches[1])"
        exit 1
    }
    adb shell input tap $c[0] $c[1] | Out-Null
    Write-Output "OK cand:$($matches[1])"
    exit 0
}

if ($Keys -match '^tool:(.+)$') {
    Calibrate | Out-Null
    $h = Get-StableHits
    if ($null -eq $h) { Write-Output "NOHITS"; exit 1 }
    $c = Get-KeyCenter $h "tool:$($matches[1])"
    if ($null -eq $c) { Write-Output "MISS-TOOL:$($matches[1])"; exit 1 }
    adb shell input tap $c[0] $c[1] | Out-Null
    Write-Output "OK tool:$($matches[1])"
    exit 0
}

$first = $true
foreach ($ch in $Keys.ToCharArray()) {
    if ($first) { Calibrate | Out-Null; $first = $false }
    $h = Get-StableHits
    if ($null -eq $h) { Write-Output "NOHITS"; exit 1 }
    $c = Get-KeyCenter $h "key:${ch}:CHAR:${ch}"
    if ($null -eq $c) { Write-Output "MISS:$ch"; exit 1 }
    adb shell input tap $c[0] $c[1] | Out-Null
    Start-Sleep -Milliseconds $DelayMs
}
Write-Output "OK $Keys"
