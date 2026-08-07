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
        private const val SAMPLE_RATE_OFFSET = 24
        private const val CHANNEL_COUNT_OFFSET = 22
        private const val BITS_PER_SAMPLE_OFFSET = 34
        private const val DATA_SIZE_OFFSET = 40
        private const val FMT_CHUNK_SIZE = 16
        private const val AUDIO_FORMAT_PCM = 1
    }

    private var fileInputStream: InputStream? = null
    private var fileOutputStream: FileOutputStream? = null
    private var isReadMode = false
    private var isWriteMode = false

    var sampleRate: Int = 0
        private set
    var channelCount: Int = 0
        private set
    var bitsPerSample: Int = 0
        private set

    /** 音频数据字节数：读侧解析自头部，写侧为累计写入量 */
    var dataLength: Int = 0
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
            val header = ByteArray(WAV_HEADER_SIZE)
            var read = 0
            while (read < WAV_HEADER_SIZE) {
                val n = stream.read(header, read, WAV_HEADER_SIZE - read)
                if (n < 0) break
                read += n
            }
            if (read != WAV_HEADER_SIZE) {
                Log.e(TAG, "Cannot read complete WAV header")
                return false
            }
            if (String(header, RIFF_OFFSET, 4) != "RIFF") {
                Log.e(TAG, "Not a valid WAV file format")
                return false
            }
            sampleRate = readLittleEndianInt(header, SAMPLE_RATE_OFFSET)
            channelCount = readLittleEndianShort(header, CHANNEL_COUNT_OFFSET)
            bitsPerSample = readLittleEndianShort(header, BITS_PER_SAMPLE_OFFSET)
            dataLength = readLittleEndianInt(header, DATA_SIZE_OFFSET)
            if (!validateReadParameters()) return false

            fileInputStream = stream
            isReadMode = true
            Log.i(
                TAG, "WAV opened: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit, " +
                    "duration ${String.format(java.util.Locale.US, "%.2f", duration)}s"
            )
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open WAV stream", e)
            close()
            return false
        }
    }

    fun readData(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isReadMode || fileInputStream == null) return -1
        if (offset < 0 || length < 0 || offset + length > buffer.size) return -1
        return try {
            fileInputStream!!.read(buffer, offset, length)
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
        if (!validateWriteParameters(sampleRate, channelCount, bitsPerSample)) return false
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
            dataLength = 0
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
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write data")
            close()
            false
        }
    }

    /** 写侧关闭时回填头部大小；读侧关闭流。返回是否成功关闭。 */
    fun close(): Boolean {
        return try {
            if (isWriteMode && fileOutputStream != null) {
                fileOutputStream!!.close()
                updateWavHeader()
            } else {
                fileInputStream?.close()
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Error closing WAV file", e)
            false
        } finally {
            fileOutputStream = null
            fileInputStream = null
            isReadMode = false
            isWriteMode = false
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

    private fun updateWavHeader() {
        try {
            RandomAccessFile(File(filePath), "rw").use { raf ->
                raf.seek(4)
                raf.write(createLittleEndianInt(dataLength + WAV_HEADER_SIZE - 8))
                raf.seek(40)
                raf.write(createLittleEndianInt(dataLength))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to update WAV header")
        }
    }

    private fun validateReadParameters(): Boolean {
        if (sampleRate <= 0 || channelCount <= 0 || bitsPerSample <= 0 || channelCount > 16) {
            Log.e(TAG, "Invalid audio parameters: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit")
            return false
        }
        return true
    }

    private fun validateWriteParameters(sampleRate: Int, channelCount: Int, bitsPerSample: Int): Boolean {
        if (sampleRate <= 0 || channelCount <= 0 || bitsPerSample <= 0 || channelCount > 16) {
            Log.e(TAG, "Invalid audio parameters: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit")
            return false
        }
        if (bitsPerSample !in listOf(8, 16, 24, 32)) {
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

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

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
