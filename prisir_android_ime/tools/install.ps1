param([string]$Apk = "app\build\outputs\apk\debug\app-debug.apk")
# 安装 APK，并自动点掉华为系统的"风险提示 → 继续安装"确认弹窗。
#
# 为什么需要它：华为设备上 `adb install` 会弹一个系统级"风险提示"对话框
# （来源"PC 工具"的应用），**没人点就会超时拒绝** →
#   adb install: Failure [INSTALL_FAILED_ABORTED: User rejected permissions]
# 而失败信息容易被 `-Last 1` 之类的输出截断吃掉 —— 一次"构建成功"的假象，
# 结果真机上跑的还是旧包（2026-11-20 踩过：连装 3 次失败还以为装上了）。
#
# 用法：.\tools\install.ps1 [-Apk <path>]
$out = "$env:TEMP\lingxi_install_out.txt"
$err = "$env:TEMP\lingxi_install_err.txt"
$dump = "$env:TEMP\lingxi_install_ui.xml"

$proc = Start-Process -FilePath "adb" -ArgumentList "install", "-r", $Apk -NoNewWindow -PassThru `
    -RedirectStandardOutput $out -RedirectStandardError $err

# 弹窗按钮文案用 unicode 转义写（PowerShell 5.1 读 .ps1 按 ANSI，中文会乱码）
# 只认"继续安装"：**别匹配"确定"** —— 泛匹配会点到系统界面上的其它"确定"按钮，
# 结果误触手势/进入多任务界面，安装反而被拒（2026-11-20 踩过）。
$btn = [regex]::new('text="(\u7EE7\u7EED\u5B89\u88C5)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$deadline = (Get-Date).AddSeconds(90)
$clicked = 0
while (-not $proc.HasExited -and (Get-Date) -lt $deadline -and $clicked -lt 3) {
    Start-Sleep -Milliseconds 700
    adb shell uiautomator dump /sdcard/_lingxi_inst.xml 2>$null | Out-Null
    adb pull /sdcard/_lingxi_inst.xml $dump 2>$null | Out-Null
    if (Test-Path $dump) {
        try {
            $raw = [System.IO.File]::ReadAllText($dump, [System.Text.Encoding]::UTF8)
            $m = $btn.Match($raw)
            if ($m.Success) {
                $cx = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
                $cy = [int](([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2)
                Write-Output "click install-confirm at ($cx,$cy)"
                adb shell input tap $cx $cy | Out-Null
                $clicked++
                Start-Sleep -Milliseconds 1500
            }
        } catch {
        }
    }
}
$proc.WaitForExit()
if (Test-Path $out) { Get-Content $out | ForEach-Object { Write-Output $_ } }
if (Test-Path $err) { Get-Content $err | Select-Object -Last 3 | ForEach-Object { Write-Output $_ } }
if (-not $clicked) { Write-Output "(no install-confirm dialog)" }
adb shell dumpsys package com.lingxi.ime | Select-String "lastUpdateTime" | Select-Object -First 1
