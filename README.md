# 灵犀拼音 · Android

本地中文拼音输入法（Android）。与 [Windows](https://github.com/63894696/lingxi-windows)、[Linux](https://github.com/63894696/lingxi-linux) 共用同一套 Rust 引擎，候选、整句与词库学习行为一致。

**官网下载**：https://www.babelspan.com/prisir-ime.html  
**许可证**：Apache License 2.0

## 功能概览

- **26 键 + 九宫格**：QWERTY 与 T9 一键切换；长按符号、退格连删、候选翻页
- **简繁切换**：本地转换，不依赖网络
- **翻译**：主动点「译」时可用；离线短语可本地命中
- **个人词库**：选词自学习；导入 / 导出；词库与学习数据百分百由你在本机管理
- **开放 API（可选）**：默认关闭；开启后仅监听本机 `127.0.0.1`，供本机 AI Agent / 脚本按习惯协助配置（词库、状态、设置等）

## 隐私

严格隐私保护，完全离线无账号：

- 键入内容、候选与词库只保存在本机，不上传输入正文
- 无账号登录、无行为画像、不出售输入数据
- 输入法本身不主动发起网络请求；唯一可能联网的是你主动使用的「翻译」
- 开放 API 默认关闭；开启后也不对外网暴露

## 仓库结构（简述）

| 目录 | 说明 |
|------|------|
| `prisir_android_ime/` | Android 输入法应用（Kotlin） |
| `prisir_ime/` | 同源 Rust 引擎（JNI） |

## 构建（开发者）

需 Android SDK、NDK、JDK 17+、Rust（`aarch64-linux-android`）与 `cargo-ndk`。

```bash
cd prisir_android_ime
./build.sh              # 编译引擎 .so 并 assembleDebug
./build.sh --install    # 可选：adb install
```

产物 APK 也可从官网介绍页下载最新安装包。

## 相关链接

- 官网介绍与安装包：https://www.babelspan.com/prisir-ime.html
- Windows 仓：https://github.com/63894696/lingxi-windows
- Linux 仓：https://github.com/63894696/lingxi-linux
- 问题反馈：https://bbs.babelspan.com/forum.html#board=browser/ime&hint=prisirai

版权所有 © 2026 BabelSpan。
