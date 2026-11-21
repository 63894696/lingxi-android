# 灵犀拼音输入法 · Android 端 v2

2026-11-18 重做。旧版（v1，1.0.0-beta.1）是自绘自研界面，用户实测判定「使用不便」；
本版按**常规通用键盘**重做：26 键 QWERTY + 顶部候选栏 + 长按出符号 + 退格连删。

引擎复用 Rust 那份（`../prisir_ime`），与 Windows TSF / Linux fcitx5 **同源**，
候选/整句/学习行为一致（三端共用 `prisir_ime/abi_cases.json` 的 15 条用例）。

## 结构

```
app/src/main/java/com/lingxi/ime/
├── Engine.kt            JNI 桥（类名/方法名必须与 prisir_ime/src/jni.rs 一致）
├── DbLocator.kt         词库位置：外部 files 目录优先，其次内部 files
├── KeyboardLayout.kt    布局数据（比例参考搜狗 py_26.ini，见下）
├── KeyboardView.kt      候选栏 + 按键自绘 + 触摸/长按/连删
├── PrisirImeService.kt  InputMethodService：组词、候选、学习、中英、标点、简繁、翻译
└── AboutActivity.kt     状态页（词库/引擎状态 + 一个测试输入框）
```

## 构建

```bash
./build.sh              # 编引擎 .so（cargo ndk）→ assembleDebug
./build.sh --install    # 顺便 adb install -r
./build.sh --push-db    # 顺便推精简词库（56MB / 30 万词）到设备
```

依赖：Android SDK（platform 34）、NDK r27d、JDK 17/21（用 Android Studio 自带 JBR）、
Gradle 8.7（`~/.gradle` 里已有）、Rust + `cargo-ndk` + target `aarch64-linux-android`。

## 手写输入（2026-11-18 新增）

- 引擎：`onnxruntime-android` + `ochw.ort`（与 Windows `prisir_hw.exe` **同模型同预处理**）
- 模型不进 APK（29.6MB，push 到 files 目录；release 策略待定）
- 面板：候选栏 42dp + **书写区 200dp** + 功能键（尺寸参考百度 `hw_full.ini`；用户实测两次加高后定 200dp）
- 交互：抬笔 600ms 自动识别、撤销/清空/⌫/**更多**/切回键盘、点候选上屏并自动清板
- **多字连写**：按笔画 x 间距切字（书写区高度 35% 为阈值），各字 top3 拼候选
- **候选放大格**：点「更多」→ 5×6 网格（最多 30 个候选），点选即上屏
- 笔迹：Catmull-Rom 样条平滑（对齐搜狗 `HWR_Beautify` 的观感目标）
- **上屏并入词库学习**：`pinyinFor(word)` → `learn()`，与拼音/九宫格共享学习与黑名单

```bash
adb push C:/Users/Administrator/_hw_test/models/ochw.ort        /sdcard/Android/data/com.lingxi.ime/files/
adb push C:/Users/Administrator/_hw_test/models/ochw_labels.txt /sdcard/Android/data/com.lingxi.ime/files/
```

> `ochw.ort` 是 **ORT flatbuffer**（magic `ORTM`），没有 ONNX 源码 → 不能 INT8 量化。
> 体积换来的代价：APK 5.9MB → 26MB（ORT AAR 单 ABI ~20MB）。

## 词库

302MB 全量词库**不进 APK**。开发期：

```bash
python ../lexicon_pipeline/trim_db.py --dst /d/Temp/ciku_android.db --top 300000   # 56MB
adb push /d/Temp/ciku_android.db /sdcard/Android/data/com.lingxi.ime/files/ciku.db
```

正式分发再决定：首启下载 / 自带精简词库 / 用户自备。学习记录会写在同一个目录的
`ciku.user.db`（引擎侧已做「只读安装 → 用户目录」处理，见 Windows 端同名经验）。

## 布局与页面（2026-11-18 第二轮）

| 页面 | 内容 |
|---|---|
| 26 键 | q..p（角标 1–0）/ a..l / ⇧z..m⌫ / `123` `符` `，` `中/英` 空格 `。` `九` 回车 |
| **九宫格 T9** | `1 2ABC 3DEF ⌫ / 4GHI 5JKL 6MNO 符 / 7PQRS 8TUV 9WXYZ 回车 / 123 中英 26 0 空格` |
| 数字页 | 拨号盘 3×4（`1–9 . - ⌫` + `0`），底行 `ABC 符 0 空格 回车` |
| 符号页 1 | 中文标点（，。、；：？！…—～ “”‘’（）《》【】 ·￥%@#&*），可翻「更多」 |
| 符号页 2 | 箭头/数学/货币（←↑→↓ √π∞°℃℉ ≈≠≤≥ ∑∏∫∂ €£¢©®™） |

切换 26 键 / 九宫格：状态页按钮（`LayoutPref`）。
**为什么不用输入法子类型**：实测 EMUI 缓存子类型列表，新增 `layout=t9` 后 `ime list -a` 看不到、切不过去；
搜狗也是"可见的键盘切换入口"，所以做成显式按钮。

## 候选栏与工具键（2026-11-20）

- 候选栏第一行显示**用户敲的字母**（如 `nihao`），不按首候选反查读音覆盖
  —— 学习/权重让首候选漂移时，首行不再被别的词读音顶掉。
- 右上角工具键：
  - 「简/繁」二态切换：点一下切繁体、再点回简体（旧的「原样 / 繁→简」第三态已移除，
    统一为 `0=简体 / 1=繁体`，旧值 `2` 读取时归一化为简体）。
  - 「译」云端翻译，长按换目标语言；离线短语表只保留中英互译
    （`assets/offline_dict.txt`，精简为 `zh>en` 255 条 + `en>zh` 117 条，删除日/韩/法等其它语向）。

T9 需要引擎的 `t9` 表（`python ../lexicon_pipeline/build_t9.py --db <ciku.db>`，+25MB，46 万行）。

## 布局比例（逆向自搜狗 20.20）

| 项 | 值 | 来源 |
|---|---|---|
| 底行宽比 | 123=0.1157 / 中英=0.1157 / 空格=0.487 / 回车=0.2389 | `assets/keyboard/ios_26/.../py_26.ini` |
| 行间距 | 0.0093（宽度比） | 同上 `H_OFFSET` |
| 行高节距 | 0.2458（键盘高度比） | `fusion/.../65538_origin.ini` 的 `V_OFFSET` 0.0124/0.258/0.504/0.750 |
| 键盘总高 | 屏高 0.34831 | 同上 `[Keyboard] H` |
| 键间隔 | 宽 0.00456 | `H_GAP_QWERTY` |
| 键 9-patch 内边距 | 43,45,43,45 @1080 | `layout/images_config.ini` |
| 候选栏配色 | 底 #F2F3F7 / 高亮 #0073F0 | `phoneSkin.ini [VPA_Bar]` |

字母行搜狗是**代码里写死**的（`.ini` 里只有功能行与符号页），本版按通用 10/9/7 实现。

## 已知边界

- 长按备选是「点选弹层」，不跟手滑动选择（Sogou 是跟手）；手感待真机体验后再定
- 没有滑行输入、没有云候选、没有皮肤/表情面板（本版目标：把"常规可用"先做对）
- 九宫格未做（26 键优先）
- 词库状态页可直接看到「已就位 N MB / 引擎已加载」
