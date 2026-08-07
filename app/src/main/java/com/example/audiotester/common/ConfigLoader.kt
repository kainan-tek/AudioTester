package com.example.audiotester.common

import android.content.Context
import android.util.Log
import java.io.File

/**
 * JSONC 配置加载器：外部 /data 文件优先，否则读 assets；加载前剥离 // 与 /* */ 注释。
 */
object ConfigLoader {
    private const val TAG = "ConfigLoader"

    fun loadRawText(context: Context, externalPath: String, assetName: String): String {
        val externalFile = File(externalPath)
        val jsonString = if (externalFile.exists()) {
            Log.i(TAG, "Loading configuration from external file")
            externalFile.readText()
        } else {
            Log.i(TAG, "Loading configuration from assets")
            context.assets.open(assetName).bufferedReader().use { it.readText() }
        }
        return stripComments(jsonString)
    }

    /**
     * 剥离 JSON 注释（// 与 /* */），字符串内部不剥离（如 "asset://..." 中的 //）。
     */
    fun stripComments(json: String): String {
        val sb = StringBuilder(json.length)
        var i = 0
        var inString = false
        while (i < json.length) {
            val c = json[i]
            when {
                inString -> {
                    sb.append(c)
                    when (c) {
                        '\\' -> { i++; if (i < json.length) sb.append(json[i]) }
                        '"' -> inString = false
                    }
                }
                c == '"' -> { inString = true; sb.append(c) }
                c == '/' && i + 1 < json.length && json[i + 1] == '/' -> {
                    while (i < json.length && json[i] != '\n') i++
                }
                c == '/' && i + 1 < json.length && json[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < json.length && !(json[i] == '*' && json[i + 1] == '/')) i++
                    i++
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
