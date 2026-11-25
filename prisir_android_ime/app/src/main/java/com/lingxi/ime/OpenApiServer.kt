package com.lingxi.ime

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机开放 API（供 AI Agent 定制个性化输入法）。
 *
 * 用途：让本机 Agent / 脚本接入，按用户习惯协助查询状态、调整配置与词库相关能力。
 * 默认关闭；需要 Agent 接入时在设置页打开「开放 API」开关。
 *
 * 对齐 Windows `prisir_tsfsvc` open_api：
 *   GET  / /health /status
 *   POST /rpc  — JSON-RPC（version / status / query / open_api.get / open_api.set）
 *
 * 只绑 `127.0.0.1`，不做 LAN。
 */
object OpenApiServer {
    private const val TAG = "LingxiOpenApi"
    const val DEFAULT_PORT = 23331

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var activePort: Int = 0
    @Volatile private var appCtx: Context? = null

    fun isRunning(): Boolean = running.get()
    fun activePort(): Int = activePort

    fun applyPref(ctx: Context) {
        appCtx = ctx.applicationContext
        if (LayoutPref.openApiEnabled(ctx)) {
            start(ctx, LayoutPref.openApiPort(ctx))
        } else {
            stop()
        }
    }

    fun start(ctx: Context, port: Int = DEFAULT_PORT): Boolean {
        appCtx = ctx.applicationContext
        if (running.get()) {
            Log.i(TAG, "already listening on $activePort")
            return true
        }
        val p = if (port in 1..65535) port else DEFAULT_PORT
        return try {
            val ss = ServerSocket(p, 8, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            activePort = ss.localPort
            running.set(true)
            thread = Thread({
                Log.i(TAG, "listening on http://127.0.0.1:$activePort/")
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        Thread({ serve(sock) }, "lingxi-openapi-conn").start()
                    } catch (t: Throwable) {
                        if (running.get()) Log.w(TAG, "accept: ${t.message}")
                    }
                }
            }, "lingxi-openapi").also { it.isDaemon = true; it.start() }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed: ${t.message}")
            running.set(false)
            activePort = 0
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        activePort = 0
        thread = null
        Log.i(TAG, "stopped")
    }

    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val first = reader.readLine() ?: return
            val parts = first.split(' ')
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val pathRaw = parts.getOrNull(1) ?: "/"
            val path = pathRaw.substringBefore('?')
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength.coerceAtMost(64 * 1024))
                var got = 0
                while (got < buf.size) {
                    val n = reader.read(buf, got, buf.size - got)
                    if (n < 0) break
                    got += n
                }
                String(buf, 0, got)
            } else ""

            val (code, payload) = route(method, path, body)
            val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            out.write("HTTP/1.1 $code\r\n")
            out.write("Content-Type: application/json; charset=utf-8\r\n")
            out.write("Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n")
            out.write("Connection: close\r\n")
            out.write("Access-Control-Allow-Origin: *\r\n")
            out.write("\r\n")
            out.write(payload)
            out.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "serve: ${t.message}")
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun route(method: String, path: String, body: String): Pair<Int, String> {
        return when {
            method == "GET" && (path == "/" || path == "/health") ->
                200 to JSONObject()
                    .put("ok", true)
                    .put("service", "prisir-ime-open-api")
                    .put("platform", "android")
                    .put(
                        "purpose",
                        "Local Open API for AI agents to help customize a personalized IME. Default off; enable in settings.",
                    )
                    .put(
                        "purpose_zh",
                        "供本机 AI Agent 定制个性化输入法；默认关闭；请在设置页开启「开放 API」。",
                    )
                    .put("listening", isRunning())
                    .put("port", activePort)
                    .put("bind", "127.0.0.1")
                    .put("version", "2.0.0-alpha.14")
                    .toString()
            method == "GET" && path == "/status" -> {
                val ctx = appCtx
                200 to JSONObject()
                    .put("open_api", JSONObject()
                        .put("enabled", ctx?.let { LayoutPref.openApiEnabled(it) } ?: false)
                        .put("port", ctx?.let { LayoutPref.openApiPort(it) } ?: DEFAULT_PORT)
                        .put("bind", "127.0.0.1")
                        .put("listening", isRunning())
                        .put("active_port", activePort))
                    .put("engine_loaded", Engine.loaded)
                    .put("using_mmap", Engine.usingMmap)
                    .toString()
            }
            method == "POST" && path == "/rpc" -> 200 to handleRpc(body.trim())
            method == "OPTIONS" -> 204 to ""
            else -> 404 to JSONObject().put("error", "not found").put("path", path).toString()
        }
    }

    private fun handleRpc(raw: String): String {
        if (raw.isEmpty()) {
            return JSONObject()
                .put("error", JSONObject().put("code", -32700).put("message", "empty body"))
                .toString()
        }
        return try {
            val req = JSONObject(raw)
            val id = if (req.has("id")) req.get("id") else JSONObject.NULL
            val method = req.optString("method", "")
            val params = req.optJSONObject("params") ?: JSONObject()
            val result: Any = when (method) {
                "version" -> JSONObject()
                    .put("platform", "android")
                    .put("version", "2.0.0-alpha.14")
                    .put("service", "prisir-ime-open-api")
                "status" -> JSONObject()
                    .put("listening", isRunning())
                    .put("port", activePort)
                    .put("engine_loaded", Engine.loaded)
                "query" -> {
                    val pinyin = params.optString("pinyin", "")
                    if (pinyin.isEmpty()) return err(id, -32602, "missing params.pinyin")
                    JSONObject().put("pinyin", pinyin).put("candidates", JSONArray(Engine.query(pinyin)))
                }
                "learn" -> {
                    val py = params.optString("pinyin", params.optString("input", ""))
                    val word = params.optString("word", params.optString("selected", ""))
                    if (py.isEmpty() || word.isEmpty()) return err(id, -32602, "params.pinyin and params.word required")
                    Engine.learn(py, word)
                    JSONObject().put("pinyin", py).put("word", word).put("learned", true)
                }
                "user_add", "user_add_pos", "dict.add" -> {
                    val word = params.optString("word", params.optString("text", "")).trim()
                    if (word.isEmpty()) return err(id, -32602, "params.word required")
                    val py = params.optString("pinyin", params.optString("code", "")).ifEmpty { null }
                    val pos = params.optLong("pos", 1L)
                    try {
                        val code = Engine.addWordAuto(word, py, pos)
                        JSONObject()
                            .put("word", word)
                            .put("pinyin", code)
                            .put("pos", pos.coerceIn(1, 50))
                            .put("added", true)
                    } catch (t: IllegalArgumentException) {
                        return err(id, -32602, t.message ?: "bad params")
                    } catch (t: Throwable) {
                        return err(id, -32000, t.message ?: "add failed")
                    }
                }
                "user_add_batch", "dict.add_batch" -> {
                    var items = params.optJSONArray("words")
                        ?: params.optJSONArray("items")
                        ?: params.optJSONArray("list")
                    if (items == null) {
                        val raw = params.optString("words", "")
                        if (raw.isNotEmpty()) {
                            items = JSONArray()
                            raw.replace("、", "\n").replace("，", "\n").replace(",", "\n")
                                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach { items!!.put(it) }
                        }
                    }
                    if (items == null || items.length() == 0) return err(id, -32602, "params.words (list) required")
                    if (items.length() > 500) return err(id, -32602, "batch too large (max 500)")
                    val defaultPos = params.optLong("pos", 1L)
                    val added = JSONArray()
                    val failed = JSONArray()
                    for (i in 0 until items.length()) {
                        val it = items.opt(i)
                        val word: String
                        val py: String?
                        val pos: Long
                        when (it) {
                            is String -> {
                                word = it.trim(); py = null; pos = defaultPos
                            }
                            is JSONObject -> {
                                word = it.optString("word", it.optString("text", "")).trim()
                                py = it.optString("pinyin", it.optString("code", "")).ifEmpty { null }
                                pos = if (it.has("pos")) it.optLong("pos") else defaultPos
                            }
                            else -> {
                                failed.put(JSONObject().put("index", i).put("error", "item must be object or string"))
                                continue
                            }
                        }
                        if (word.isEmpty()) {
                            failed.put(JSONObject().put("index", i).put("error", "empty word"))
                            continue
                        }
                        try {
                            val code = Engine.addWordAuto(word, py, pos)
                            added.put(
                                JSONObject().put("word", word).put("pinyin", code)
                                    .put("pos", pos.coerceIn(1, 50))
                            )
                        } catch (t: Throwable) {
                            failed.put(
                                JSONObject().put("index", i).put("word", word)
                                    .put("error", t.message ?: "failed")
                            )
                        }
                    }
                    JSONObject()
                        .put("added", added)
                        .put("failed", failed)
                        .put("ok", added.length())
                        .put("err", failed.length())
                }
                "open_api.get" -> {
                    val ctx = appCtx
                    JSONObject()
                        .put("enabled", ctx?.let { LayoutPref.openApiEnabled(it) } ?: false)
                        .put("port", ctx?.let { LayoutPref.openApiPort(it) } ?: DEFAULT_PORT)
                        .put("bind", "127.0.0.1")
                        .put("listening", isRunning())
                        .put("active_port", activePort)
                }
                "open_api.set" -> {
                    val ctx = appCtx ?: return err(id, -32000, "no context")
                    if (params.has("enabled")) LayoutPref.setOpenApiEnabled(ctx, params.getBoolean("enabled"))
                    if (params.has("port")) LayoutPref.setOpenApiPort(ctx, params.getInt("port"))
                    applyPref(ctx)
                    JSONObject()
                        .put("enabled", LayoutPref.openApiEnabled(ctx))
                        .put("port", LayoutPref.openApiPort(ctx))
                        .put("listening", isRunning())
                        .put("active_port", activePort)
                }
                else -> return err(id, -32601, "method not found: $method")
            }
            JSONObject().put("result", result).put("id", id).toString()
        } catch (t: Throwable) {
            JSONObject()
                .put("error", JSONObject().put("code", -32700).put("message", t.message ?: "parse error"))
                .toString()
        }
    }

    private fun err(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("error", JSONObject().put("code", code).put("message", message))
            .put("id", id ?: JSONObject.NULL)
            .toString()
}