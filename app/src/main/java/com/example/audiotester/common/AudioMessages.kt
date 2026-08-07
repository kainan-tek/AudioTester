package com.example.audiotester.common

/**
 * 各特性差异文案（ready / preparing / active / stopped / failed）。
 * 其余状态文案（如 "Stopping..."、"Configuration updated: X"、重载结果等）两特性相同，统一为通用文案。
 */
data class AudioMessages(
    val ready: String,
    val preparing: String,
    val active: String,
    val stopped: String,
    val failed: String,
)
