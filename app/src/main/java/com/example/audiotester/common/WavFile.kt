package com.example.audiotester.common

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * WAV 文件读写统一工具。
 * 读侧：open(文件/InputStream) + readData；写侧：create + writeAudioData + close 回填头部。
 * 读/写实现实质不同，按职责分区；共享字段：sampleRate/channelCount/bitsPerSample，
 * byteRate/blockAlign 为计算属性，dataLength 统一读侧解析与写侧累计的时长计算。
 */
class WavFile(private val filePath: String) {

    companion object {
        private const val TAG = "WavFile"
        private const val WAV_HEADER_SIZE = 44
        private const val RIFF_OFFSET = 0
        private const val WAVE_OFFSET = 8
        private const val FMT_CHUNK_SIZE = 16
        private const val AUDIO_FORMAT_PCM = 1
        private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    }

    private var fileInputStream: InputStream? = null
    private var fileOutputStream: FileOutputStream? = null
    private var isReadMode = false
    private var isWriteMode = false
    private var remainingData = 0L

    var sampleRate: Int = 0
        private set
    var channelCount: Int = 0
        private set
    var bitsPerSample: Int = 0
        private set

    /** 音频数据字节数：读侧解析自头部，写侧为累计写入量 */
    var dataLength: Long = 0
        private set

    val byteRate: Int
        get() = sampleRate * channelCount * bitsPerSample / 8

    val blockAlign: Int
        get() = channelCount * bitsPerSample / 8

    val duration: Float
        get() = if (sampleRate > 0 && channelCount > 0 && bitsPerSample > 0) {
            dataLength.toFloat() / (sampleRate * channelCount * (bitsPerSample / 8))
        } else 0f

    // ===== 读侧 =====

    fun open(): Boolean = try {
        open(FileInputStream(File(filePath)))
    } catch (e: IOException) {
        Log.e(TAG, "Failed to open file: $filePath", e)
        false
    }

    /** 从 InputStream 打开（支持 assets，如内置音源） */
    fun open(stream: InputStream): Boolean {
        Log.d(TAG, "Opening WAV stream: $filePath")
        close()
        try {
            // RIFF/WAVE 头（12 字节），随后按 chunk 迭代
            val riff = ByteArray(12)
            if (!readFully(stream, riff, riff.size)) {
                Log.e(TAG, "Cannot read WAV RIFF header")
                stream.close()
                return false
            }
            if (String(riff, RIFF_OFFSET, 4) != "RIFF" ||
                String(riff, WAVE_OFFSET, 4) != "WAVE"
            ) {
                Log.e(TAG, "Not a valid WAV file")
                stream.close()
                return false
            }

            // 逐 chunk 扫描：fmt 取参数（兼容 EXTENSIBLE），fact/LIST 等跳过，data 即数据起点
            var audioFormat = -1
            while (true) {
                val header = ByteArray(8)
                if (!readFully(stream, header, header.size)) {
                    Log.e(TAG, "Missing data chunk")
                    stream.close()
                    return false
                }
                // chunk size：header[4..7]（4 字节小端，无符号）
                val size = (header[4].toLong() and 0xFFL) or
                    ((header[5].toLong() and 0xFFL) shl 8) or
                    ((header[6].toLong() and 0xFFL) shl 16) or
                    ((header[7].toLong() and 0xFFL) shl 24)
                when (String(header, 0, 4)) {
                    "fmt " -> {
                        // 40 字节覆盖 EXTENSIBLE 的 cbSize/validBits/channelMask/subformat(GUID 前 2 字节)
                        val fmtLen = minOf(size, 40L).toInt()
                        val fmt = ByteArray(fmtLen)
                        if (fmtLen < 16 || !readFully(stream, fmt, fmtLen)) {
                            Log.e(TAG, "Invalid fmt chunk")
                            stream.close()
                            return false
                        }
                        skip(stream, size - fmtLen)
                        audioFormat = readLittleEndianShort(fmt, 0)
                        channelCount = readLittleEndianShort(fmt, 2)
                        // fmt[4..7] = sampleRate（4 字节小端）
                        sampleRate = (fmt[4].toInt() and 0xFF) or ((fmt[5].toInt() and 0xFF) shl 8) or
                            ((fmt[6].toInt() and 0xFF) shl 16) or ((fmt[7].toInt() and 0xFF) shl 24)
                        bitsPerSample = readLittleEndianShort(fmt, 14)
                        // WAVE_FORMAT_EXTENSIBLE：真实格式在 subformat GUID 前 2 字节（需至少 26 字节）
                        if (audioFormat == WAVE_FORMAT_EXTENSIBLE) {
                            if (fmtLen < 26) {
                                Log.e(TAG, "Invalid EXTENSIBLE fmt chunk (size $fmtLen)")
                                stream.close()
                                return false
                            }
                            audioFormat = readLittleEndianShort(fmt, 24)
                        }
                    }
                    "data" -> {
                        dataLength = size
                        break
                    }
                    else -> skip(stream, size)
                }
            }

            if (audioFormat != AUDIO_FORMAT_PCM) {
                Log.e(TAG, "Unsupported WAV format (only PCM=1 is supported)")
                stream.close()
                return false
            }
            if (!validateParameters(sampleRate, channelCount, bitsPerSample)) {
                stream.close()
                return false
            }

            remainingData = dataLength
            fileInputStream = stream
            isReadMode = true
            Log.i(
                TAG, "WAV opened: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit, " +
                    "duration ${String.format(java.util.Locale.US, "%.2f", duration)}s"
            )
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open WAV stream", e)
            try { stream.close() } catch (_: IOException) {}
            return false
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = stream.read(buf, off, len - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skip(stream: InputStream, count: Long) {
        var remaining = count
        val tmp = ByteArray(8192)
        while (remaining > 0) {
            val n = stream.read(tmp, 0, minOf(remaining, tmp.size.toLong()).toInt())
            if (n < 0) return
            remaining -= n
        }
    }

    fun readData(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isReadMode || fileInputStream == null) return -1
        if (offset < 0 || length < 0 || offset + length > buffer.size) return -1
        if (remainingData <= 0) return -1
        // 限制在 data chunk 内，避免读到尾部元数据 chunk
        val toRead = minOf(length.toLong(), remainingData).toInt()
        return try {
            val n = fileInputStream!!.read(buffer, offset, toRead)
            if (n > 0) remainingData -= n
            n
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read data", e)
            close()
            -1
        }
    }

    fun isValid(): Boolean = isReadMode && sampleRate > 0 && channelCount > 0 && bitsPerSample > 0

    val channelDescription: String
        get() = getChannelInfo(channelCount).description

    val channelLayout: String
        get() = getChannelInfo(channelCount).layout

    // ===== 写侧 =====

    fun create(sampleRate: Int, channelCount: Int, bitsPerSample: Int): Boolean {
        if (!validateParameters(sampleRate, channelCount, bitsPerSample)) return false
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.bitsPerSample = bitsPerSample
        return try {
            close()
            val file = File(filePath)
            file.parentFile?.mkdirs()
            fileOutputStream = FileOutputStream(file)
            writeInitialWavHeader()
            isWriteMode = true
            dataLength = 0L
            Log.i(TAG, "WAV created: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create WAV file", e)
            close()
            false
        }
    }

    fun writeAudioData(audioData: ByteArray, offset: Int, length: Int): Boolean {
        if (!isWriteMode || fileOutputStream == null) return false
        if (offset < 0 || length < 0 || offset + length > audioData.size) return false
        return try {
            fileOutputStream!!.write(audioData, offset, length)
            dataLength += length
            true
        } catch (_: IOException) {
            Log.e(TAG, "Failed to write data")
            close()
            false
        }
    }

    /** 写侧关闭时回填头部大小；读侧关闭流。返回是否成功关闭。 */
    fun close(): Boolean {
        return try {
            if (fileOutputStream != null) {
                fileOutputStream!!.close()
                if (isWriteMode) updateWavHeader() else true
            } else {
                fileInputStream?.close()
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error closing WAV file", e)
            false
        } finally {
            fileOutputStream = null
            fileInputStream = null
            isReadMode = false
            isWriteMode = false
            remainingData = 0
        }
    }

    // ===== 头部与校验 =====

    private fun writeInitialWavHeader() {
        val header = ByteArray(WAV_HEADER_SIZE).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            writeLittleEndianInt(WAV_HEADER_SIZE - 8, 4)
            "WAVE".toByteArray().copyInto(this, 8)
            "fmt ".toByteArray().copyInto(this, 12)
            writeLittleEndianInt(FMT_CHUNK_SIZE, 16)
            writeLittleEndianShort(AUDIO_FORMAT_PCM, 20)
            writeLittleEndianShort(channelCount, 22)
            writeLittleEndianInt(sampleRate, 24)
            writeLittleEndianInt(byteRate, 28)
            writeLittleEndianShort(blockAlign, 32)
            writeLittleEndianShort(bitsPerSample, 34)
            "data".toByteArray().copyInto(this, 36)
            writeLittleEndianInt(0, 40)
        }
        fileOutputStream?.write(header)
    }

    private fun updateWavHeader(): Boolean {
        return try {
            RandomAccessFile(File(filePath), "rw").use { raf ->
                raf.seek(4)
                raf.write(createLittleEndianInt((dataLength + WAV_HEADER_SIZE - 8).toInt()))
                raf.seek(40)
                raf.write(createLittleEndianInt(dataLength.toInt()))
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to update WAV header", e)
            false
        }
    }

    private fun validateParameters(sampleRate: Int, channelCount: Int, bitsPerSample: Int): Boolean {
        if (sampleRate <= 0 || channelCount <= 0 || bitsPerSample <= 0 || channelCount > 16) {
            Log.e(TAG, "Invalid audio parameters: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit")
            return false
        }
        if (!AudioConstants.isValidBitDepth(bitsPerSample)) {
            Log.e(TAG, "Unsupported bit depth: ${bitsPerSample}bit")
            return false
        }
        return true
    }

    private data class ChannelInfo(val description: String, val layout: String)

    private fun getChannelInfo(count: Int): ChannelInfo = when (count) {
        1 -> ChannelInfo("Mono", "M")
        2 -> ChannelInfo("Stereo", "L R")
        4 -> ChannelInfo("Quad", "L R Ls Rs")
        6 -> ChannelInfo("5.1 Surround", "L R C LFE Ls Rs")
        8 -> ChannelInfo("7.1 Surround", "L R C LFE Ls Rs Lrs Rrs")
        10 -> ChannelInfo("5.1.4 Surround", "L R C LFE Ls Rs Ltf Rtf Ltb Rtb")
        12 -> ChannelInfo("7.1.4 Surround", "L R C LFE Ls Rs Lrs Rrs Ltf Rtf Ltb Rtb")
        else -> ChannelInfo("$count channels (playback as stereo)", "$count channels → Stereo (L R)")
    }

    private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.writeLittleEndianInt(value: Int, offset: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.writeLittleEndianShort(value: Int, offset: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun createLittleEndianInt(value: Int): ByteArray =
        ByteArray(4).apply { writeLittleEndianInt(value, 0) }
}
