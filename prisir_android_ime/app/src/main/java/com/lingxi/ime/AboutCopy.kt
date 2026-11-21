package com.lingxi.ime

/**
 * 设置页说明文案（2026-11-20 与 Windows 端口径对齐）。
 *
 * 分栏：使用说明 · 关于 · 隐私政策 · 使用条款 · 开放 API · 问题反馈
 * 反馈通过论坛提交。
 */
object AboutCopy {

    const val WEBSITE_URL = "https://www.babelspan.com/"
    const val FORUM_URL =
        "https://bbs.babelspan.com/forum.html#board=browser/ime&hint=prisirai"

    const val HELP_TITLE = "使用说明"
    const val HELP_BODY = "" +
        "启用输入法：\n" +
        "系统设置 → 语言和输入法 → 虚拟键盘 → 管理键盘 → 启用「灵犀拼音」，并切换为当前输入法。\n" +
        "\n" +
        "基本操作：\n" +
        "· 输入拼音后，点选候选或按空格上屏。\n" +
        "· 候选栏首行显示已输入字母，次行显示候选字词。\n" +
        "· 底行提供「123」数字页、「符」符号页、「中/英」、布局切换、空格与回车。\n" +
        "· 布局键在「拼音 26 键 / 九宫格」之间切换；「中/英」进入英文 26 键。\n" +
        "· 候选栏「简/繁」切换简繁输出；「查」查词典释义；「译」发起翻译（长按可切换目标语言）。\n" +
        "· 「译」默认翻译输入框全文；存在选区时仅翻译选区。\n" +
        "· 长按候选词查询释义（英文走公开词典 API；中文离线包后续提供）。\n" +
        "· 候选较多时可左右滑动或使用 ‹ › 翻页。\n" +
        "· 英文页「⇧」单击：下一字母大写；快速双击：锁定大写（再点解除）。"

    const val ABOUT_TITLE = "关于"
    const val ABOUT_BODY = "" +
        "灵犀拼音输入法（Android）由 BabelSpan · Prisir（湃睿思）提供。\n" +
        "\n" +
        "产品定位：面向个人与专业场景的本地中文输入工具。核心能力包括拼音/混拼候选、整句解码、简繁输出、本地词库与用户词典管理，以及可选的多语言翻译。拼音输入与词库管理在本机完成，不依赖账号体系，也不以云端联想为默认能力。\n" +
        "\n" +
        "若需借助 AI Agent 按个人习惯定制输入法（词库、模式、查询等），请参阅设置页「开放 API」或下方说明：默认关闭，按需开启后仅对本机回环地址开放。\n" +
        "\n" +
        "官网与下载：https://www.babelspan.com/\n" +
        "\n" +
        "版权所有 © 2026 BabelSpan。本软件依据 Apache License, Version 2.0 授权，按「原样」提供，不附带任何担保或条件。完整授权见应用随附 LICENSE（Apache License 2.0）。"

    const val PRIVACY_TITLE = "隐私政策"
    const val PRIVACY_BODY = "" +
        "灵犀拼音采用严格的隐私保护设计，完全离线无账号运行：\n" +
        "\n" +
        "· 键入内容、候选结果与词库数据保存在本机，不向第三方上传输入正文。\n" +
        "· 不提供账号登录，不做行为画像，不出售或交换个人输入数据。\n" +
        "· 用户可通过词库管理与个人词库导入/导出自主维护本地词典。\n" +
        "· 开放 API 默认关闭；开启后也仅监听本机 127.0.0.1，不对外网暴露。\n" +
        "\n" +
        "可能联网的功能仅限您主动触发的「译」与「查」：\n" +
        "· 「译」：离线短语未命中时，可能向第三方翻译接口发送待译文本。\n" +
        "· 「查」：向公开词典接口查询单词释义（数据源自 Wiktionary，CC BY-SA；经 FreeDictionaryAPI 等）。\n" +
        "不使用「译/查」时，输入法不发起任何网络请求，可完全离线使用。"

    const val TERMS_TITLE = "使用条款"
    const val TERMS_BODY = "" +
        "使用本软件即表示您知悉并同意以下约定：\n" +
        "\n" +
        "· 本软件为本地工具，安装、配置与使用风险由用户自行承担。\n" +
        "· 请仅将本软件用于合法用途；请勿用于干扰系统安全或侵犯他人权益的场景。\n" +
        "· 词库与学习数据归用户本地管理；卸载或更换设备前请自行备份需要保留的用户词典。\n" +
        "· 软件可能通过官网发布更新；是否安装更新由用户决定。\n" +
        "\n" +
        "完整法律文本以应用随附 LICENSE（Apache License, Version 2.0）为准。官网：https://www.babelspan.com/"

    const val OPENAPI_TITLE = "开放 API"
    const val OPENAPI_BODY = "" +
        "开放 API 面向「用 AI 帮助用户定制个性化输入法」的场景：本机 Agent / 脚本可通过本地接口查询状态、调整配置、访问词库相关能力，从而按个人习惯协助设置输入法。\n" +
        "\n" +
        "默认关闭。需要 Agent 接入时，请在设置页打开「开放 API」开关。\n" +
        "\n" +
        "开启后仅监听本机 127.0.0.1（默认端口与 Windows 对齐），不对外网开放。\n" +
        "\n" +
        "常用端点：GET /health · GET /status · POST /rpc（JSON-RPC）。\n" +
        "\n" +
        "请仅在信任本机环境时开启；不需要个性化 Agent 协助时可保持关闭。"

    /** 设置页开放 API 卡片短提示（关） */
    const val OPENAPI_HINT_OFF = "" +
        "面向本机 AI Agent：协助定制个性化输入法（词库、状态、配置等）。\n" +
        "默认关闭；需要 Agent 接入时点按开启。仅监听 127.0.0.1，不外发数据。\n" +
        "端点：GET /health /status · POST /rpc"

    /** 设置页开放 API 卡片短提示（开） */
    fun openApiHintOn(port: Int, listening: Boolean): String {
        val live = if (listening) "正在监听" else "启动失败（请检查端口是否被占用）"
        return "Agent 可接入 · http://127.0.0.1:$port/ · $live\n" +
            "仅本机回环；不需要时请关闭。"
    }

    const val FEEDBACK_TITLE = "问题反馈"
    const val FEEDBACK_BODY = "" +
        "如需报告缺陷或提出改进建议，请通过 BabelSpan 论坛提交：\n" +
        "\n" +
        "https://bbs.babelspan.com/forum.html#board=browser/ime&hint=prisirai\n" +
        "\n" +
        "建议在帖中说明：Android 版本与灵犀版本号、复现步骤、期望与实际结果。可附截图或简要日志摘录（请勿粘贴含隐私的输入正文）。"
}
