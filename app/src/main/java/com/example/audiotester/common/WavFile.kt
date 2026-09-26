package com.example.audiotester.common

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Unified WAV read/write utility: read side (open + readData) and write side (create +
 * writeAudioData + close/patch) are fundamentally different and stay separated. Shared fields:
 * sampleRate/channelCount/bitsPerSample; byteRate/blockAlign are computed; dataLength unifies
 * duration between header-parsed (read) and accumulated (write) values.
 */
class WavFile(private val filePath: String, private val maxDataBytes: Long = Int.MAX_VALUE - 36L) {

    companion object {
        private const val TAG = "WavFile"
        private const val WAV_HEADER_SIZE = 44
        private const val RIFF_OFFSET = 0
        private const val WAVE_OFFSET = 8
        private const val FMT_CHUNK_SIZE = 16
        private const val AUDIO_FORMAT_PCM = 1
        private const val WAVE_FORMAT_IEEE_FLOAT = 3
        private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    }

    private var fileInputStream: InputStream? = null
    private var fileOutputStream: FileOutputStream? = null

    /** A close already failed to patch the header; replayed so later close() calls report the broken file.
     *  Reset by [create]: the failure belongs to the previous write session, not the new one */
    private var finalizeFailed = false

    /** Bytes left in the declared data chunk (read side); reset by [close] */
    var remainingData = 0L
        private set

    /** True while a real-length data chunk still has unread bytes; 0xFFFFFFFF is the streaming "unknown size" placeholder, not a truncation */
    val hasUnreadDeclaredData: Boolean
        get() = remainingData > 0 && dataLength != 0xFFFFFFFFL

    var sampleRate: Int = 0
        private set
    var channelCount: Int = 0
        private set
    var bitsPerSample: Int = 0
        private set

    /** True when the parsed format tag is IEEE float (3, incl. the EXTENSIBLE subformat); false = integer PCM.
     *  Distinguishes a 32-bit float file from a 32-bit integer PCM one — same bits-per-sample, different encoding */
    var isFloatFormat: Boolean = false
        private set

    /** Audio data size in bytes: parsed from the header on the read side, accumulated writes on the write side */
    var dataLength: Long = 0
        private set

    val byteRate: Int
        get() = sampleRate * channelCount * bitsPerSample / 8

    val blockAlign: Int
        get() = channelCount * bitsPerSample / 8

    val duration: Float
        get() = if (byteRate > 0) dataLength.toFloat() / byteRate else 0f

    // ===== Read side =====

    fun open(): Boolean = try {
        open(FileInputStream(File(filePath)))
    } catch (e: IOException) {
        Log.e(TAG, "Failed to open file: $filePath", e)
        false
    }

    /** Opens from an InputStream (supports assets, e.g. built-in audio sources) */
    fun open(stream: InputStream): Boolean {
        Log.d(TAG, "Opening WAV stream: $filePath")
        close()
        // Failure paths leave the stream closed; a successful open keeps it (read side owns it)
        var opened = false
        try {
            // RIFF/WAVE header (12 bytes), then iterate over chunks
            val riff = ByteArray(12)
            if (!readFully(stream, riff, riff.size)) {
                Log.e(TAG, "Cannot read WAV RIFF header")
                return false
            }
            if (String(riff, RIFF_OFFSET, 4, Charsets.US_ASCII) != "RIFF" ||
                String(riff, WAVE_OFFSET, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                Log.e(TAG, "Not a valid WAV file")
                return false
            }

            // Scan chunk by chunk: take parameters from fmt (EXTENSIBLE-compatible), skip fact/LIST etc., data marks the audio start
            var audioFormat = -1
            while (true) {
                val header = ByteArray(8)
                if (!readFully(stream, header, header.size)) {
                    Log.e(TAG, "Missing data chunk")
                    return false
                }
                // chunk size: header[4..7] (4 bytes little-endian, unsigned; mask restores the unsigned value)
                val size = readLittleEndianInt(header, 4).toLong() and 0xFFFFFFFFL
                when (String(header, 0, 4, Charsets.US_ASCII)) {
                    "fmt " -> {
                        // 40 bytes cover EXTENSIBLE's cbSize/validBits/channelMask/subformat (first 2 bytes of the GUID)
                        val fmtLen = minOf(size, 40L).toInt()
                        val fmt = ByteArray(fmtLen)
                        if (fmtLen < 16 || !readFully(stream, fmt, fmtLen)) {
                            Log.e(TAG, "Invalid fmt chunk")
                            return false
                        }
                        skip(stream, size - fmtLen)
                        audioFormat = readLittleEndianShort(fmt, 0)
                        channelCount = readLittleEndianShort(fmt, 2)
                        // fmt[4..7] = sampleRate (4 bytes little-endian)
                        sampleRate = readLittleEndianInt(fmt, 4)
                        bitsPerSample = readLittleEndianShort(fmt, 14)
                        // WAVE_FORMAT_EXTENSIBLE: the real format is in the first 2 bytes of the subformat GUID (needs at least 26 bytes)
                        if (audioFormat == WAVE_FORMAT_EXTENSIBLE) {
                            if (fmtLen < 26) {
                                Log.e(TAG, "Invalid EXTENSIBLE fmt chunk (size $fmtLen)")
                                return false
                            }
                            audioFormat = readLittleEndianShort(fmt, 24)
                            // wValidBitsPerSample (fmt[18]) is intentionally not read: the spec
                            // left-justifies valid bits in the container, so playing by the
                            // container size is level-correct; relabeling would misalign frames
                        }
                    }
                    "data" -> {
                        dataLength = size
                        break
                    }
                    else -> skip(stream, size)
                }
                // RIFF word alignment: an odd-sized chunk body is followed by a 1-byte pad
                if (size % 2 != 0L) skip(stream, 1)
            }

            // Assigned from the loop-local final tag: covers both the plain fmt chunk and the
            // EXTENSIBLE subformat (whose GUID's first 2 bytes replaced audioFormat in-scan)
            isFloatFormat = audioFormat == WAVE_FORMAT_IEEE_FLOAT
            if (audioFormat != AUDIO_FORMAT_PCM && audioFormat != WAVE_FORMAT_IEEE_FLOAT) {
                Log.e(TAG, "Unsupported WAV format (only PCM=1 or IEEE float=3 is supported)")
                return false
            }
            // Float WAVs are 32-bit only: Android has no double-precision playback encoding
            // (ENCODING_PCM_FLOAT is single precision), so 64-bit files are rejected explicitly
            // instead of being misread as 32-bit integer PCM. Read-side only — validateParameters
            // is shared with the write side, which always produces integer PCM
            if (audioFormat == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample != 32) {
                Log.e(TAG, "Unsupported float WAV: $bitsPerSample-bit (only 32-bit float is supported)")
                return false
            }
            if (!validateParameters(sampleRate, channelCount, bitsPerSample)) {
                return false
            }

            remainingData = dataLength
            fileInputStream = stream
            opened = true
            Log.i(
                TAG, "WAV opened: ${sampleRate}Hz, ${channelCount}ch, " +
                    "${bitsPerSample}bit${if (isFloatFormat) "(float)" else ""}, " +
                    "duration ${String.format(java.util.Locale.US, "%.2f", duration)}s"
            )
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open WAV stream", e)
            return false
        } finally {
            if (!opened) {
                try { stream.close() } catch (_: IOException) {}
            }
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

    /**
     * Returns -1 for a clean end of data; IO errors propagate to the caller (a mid-file read
     * failure must not masquerade as EOF). The stream stays open on error — cleanup belongs to
     * the caller (the player's releaseAudioResources closes it on the error path).
     */
    @Synchronized
    fun readData(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = fileInputStream ?: return -1
        if (offset < 0 || length < 0 || offset + length > buffer.size) return -1
        if (remainingData <= 0) return -1
        // Stay within the data chunk to avoid reading trailing metadata chunks
        val toRead = minOf(length.toLong(), remainingData).toInt()
        // Loop until the block is complete: InputStream.read may return fewer bytes than
        // requested, and the player relies on frame-aligned blocks
        var total = 0
        while (total < toRead) {
            val n = stream.read(buffer, offset + total, toRead - total)
            if (n < 0) break
            total += n
        }
        remainingData -= total
        return if (total > 0) total else -1
    }

    val channelDescription: String
        get() = getChannelInfo(channelCount).description

    val channelLayout: String
        get() = getChannelInfo(channelCount).layout

    // ===== Write side =====

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
            dataLength = 0L
            finalizeFailed = false
            writeInitialWavHeader()
            Log.i(TAG, "WAV created: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create WAV file", e)
            close()
            false
        }
    }

    @Synchronized
    fun writeAudioData(audioData: ByteArray, offset: Int, length: Int): Boolean {
        val out = fileOutputStream ?: return false
        if (offset < 0 || length < 0 || offset + length > audioData.size) return false
        if (dataLength + length > maxDataBytes) {
            // The 32-bit WAV size fields cannot represent more data; fail loudly instead of
            // patching a wrapped (corrupt) header at close
            Log.e(TAG, "WAV data exceeds the format limit of $maxDataBytes bytes; aborting save")
            close()
            return false
        }
        return try {
            out.write(audioData, offset, length)
            dataLength += length
            true
        } catch (_: IOException) {
            Log.e(TAG, "Failed to write data")
            close()
            false
        }
    }

    /**
     * Closes the streams, then finalizes the write side: header sizes patched back — except a
     * session that produced no audio data at all: its file is deleted instead of shipping a
     * header-only empty WAV. Returns whether the file is valid (header patched, or the empty
     * file deleted), not whether the stream close succeeded: the body is already on disk
     * (FileOutputStream never buffers) and a successful patch itself proves readability, so
     * counting a failed close would flag a valid recording as unreadable.
     *
     * Later close() calls replay the remembered result instead of reporting blind success:
     * after the internal close-on-failure in writeAudioData, out is already null here, and a
     * plain true would swallow the broken header from the caller's final release-time check.
     *
     * Synchronized with readData/writeAudioData: engine stop() closes the WAV from the engine
     * thread while the run loop may still be mid-write/read on its own thread — the monitor
     * serializes them, so the header always counts every accepted write (the writer's internal
     * close-on-failure is the same monitor, reentrant).
     */
    @Synchronized
    fun close(): Boolean {
        val out = fileOutputStream
        try {
            if (out == null) fileInputStream?.close() else out.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing WAV file", e)
        } finally {
            fileOutputStream = null
            fileInputStream = null
            remainingData = 0
        }
        // Finalization runs regardless of how the close ended: the delete must follow close
        // (the handle must be gone on Windows), and the patch must not be skipped by it
        val finalized = when {
            out == null -> !finalizeFailed // read side, or replay of an earlier failed patch
            dataLength == 0L -> { File(filePath).delete(); true }
            else -> updateWavHeader().also { if (!it) finalizeFailed = true }
        }
        return finalized
    }

    // ===== Header and validation =====

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
        else -> ChannelInfo("$count channels", "$count channels (layout not labeled)")
    }

    private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    // offset keeps the LE-helper family's signature uniform; both call sites read offset 4
    // (RIFF size, fmt sampleRate)
    @Suppress("SameParameterValue")
    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

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
