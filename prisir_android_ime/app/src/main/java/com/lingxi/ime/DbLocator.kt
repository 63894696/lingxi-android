package com.lingxi.ime

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 词库位置：**内部 files 目录优先**（快），其次外部 files，再从 APK assets 首启释放。
 *
 * 2026-11-21：正式包把全量 `ciku.db` 打进 assets，装完即用，不再依赖二次下载 / adb push。
 * 首启从 assets 解到 filesDir（一次）；之后冷启直接读内部文件。
 */
object DbLocator {
    private const val DB = "ciku.db"
    private const val MIDX = "ciku.midx"
    /** 小于此大小视为损坏/占位，触发重新释放 */
    private const val MIN_DB_BYTES = 1_000_000L

    fun candidates(ctx: Context): List<File> = buildList {
        add(File(ctx.filesDir, DB))
        ctx.getExternalFilesDir(null)?.let { add(File(it, DB)) }
    }

    fun resolve(ctx: Context): File = candidates(ctx).firstOrNull { it.exists() && it.length() >= MIN_DB_BYTES }
        ?: candidates(ctx).first()

    /**
     * 装完即用：APK assets 内有词库时，释放到内部 filesDir。
     * 已有完整词库则跳过。返回是否发生了写入。
     */
    fun ensureFromAssets(ctx: Context): Boolean {
        val dst = File(ctx.filesDir, DB)
        if (dst.exists() && dst.length() >= MIN_DB_BYTES) return false
        return try {
            ctx.assets.open(DB).use { input ->
                val tmp = File(ctx.filesDir, "$DB.tmp")
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
            }
            // 可选：同包带了 mmap 索引一并释放
            try {
                ctx.assets.open(MIDX).use { input ->
                    val mDst = File(ctx.filesDir, MIDX)
                    val tmp = File(ctx.filesDir, "$MIDX.tmp")
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                    if (mDst.exists()) mDst.delete()
                    tmp.renameTo(mDst)
                    mDst.setLastModified(dst.lastModified())
                }
            } catch (_: Throwable) {
                // 无 midx 时引擎走 SQLite，功能完整只是略慢
            }
            android.util.Log.i("LingxiE", "词库已从 APK assets 释放到 filesDir (${dst.length()} bytes)")
            true
        } catch (t: Throwable) {
            android.util.Log.e("LingxiE", "assets 释放词库失败: ${t.message}")
            false
        }
    }

    /**
     * 外部 files 目录 → 内部 filesDir（adb / 旧分发兼容）。
     */
    fun ensureInstalled(ctx: Context): Boolean {
        val external = ctx.getExternalFilesDir(null) ?: return false
        val internal = ctx.filesDir
        var installed = false
        for (name in listOf(DB, MIDX)) {
            val src = File(external, name)
            val dst = File(internal, name)
            if (!src.exists() || src.length() <= 0L) continue
            if (dst.exists() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) continue
            try {
                copy(src, dst)
                dst.setLastModified(src.lastModified())
                installed = true
            } catch (_: Throwable) {
            }
        }
        return installed
    }

    private fun copy(src: File, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        FileInputStream(src).use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }
}
