package com.example.audiotester.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class WavFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Delivers one byte per read: InputStream allows short reads; readData must heal them into full blocks */
    private class OneByteAtATimeStream(private val source: InputStream) : InputStream() {
        override fun read(): Int = source.read()
        override fun read(buffer: ByteArray, off: Int, len: Int): Int =
            if (len <= 0) source.read(buffer, off, len) else source.read(buffer, off, 1)
    }

    @Test
    fun readData_fillsWholeBufferDespiteShortReads() {
        val file = File(tempFolder.root, "shortreads.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(48000, 2, 16))
        val data = ByteArray(4000) { (it % 251).toByte() }
        assertTrue(writer.writeAudioData(data, 0, data.size))
        assertTrue(writer.close())

        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open(OneByteAtATimeStream(FileInputStream(file))))
        val buffer = ByteArray(400)
        assertEquals(400, reader.readData(buffer, 0, buffer.size))
        assertArrayEquals(data.copyOfRange(0, 400), buffer)
        repeat(9) { assertEquals(400, reader.readData(buffer, 0, buffer.size)) }
        assertEquals(-1, reader.readData(buffer, 0, buffer.size))
        reader.close()
    }

    @Test
    fun readData_partialFinalBlock_returnsShortThenEof() {
        // data chunk of 6 bytes = 1.5 frames at blockAlign 4: the partial tail is returned
        // once, then EOF — the read loop must stop at the data boundary, not hang or skip
        val file = File(tempFolder.root, "partial_tail.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(8000, 2, 16))
        assertTrue(writer.writeAudioData(ByteArray(6), 0, 6))
        assertTrue(writer.close())

        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open(OneByteAtATimeStream(FileInputStream(file))))
        assertEquals(6, reader.readData(ByteArray(100), 0, 100))
        assertEquals(-1, reader.readData(ByteArray(100), 0, 100))
        reader.close()
    }

    @Test
    fun readData_truncatedFile_returnsAvailableBytesThenEof() {
        // Header declares 100 data bytes but the file ends after 60: readData returns the
        // available bytes once, then -1 (no crash, no infinite loop)
        val data = ByteArray(60)
        val header = ByteArray(44).also { h ->
            "RIFF".toByteArray().copyInto(h, 0)
            h.putLeInt(4, 36 + 100)           // riff size as if the data were complete
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            h.putLeInt(16, 16)                // fmt chunk size
            h.putLeShort(20, 1)               // PCM
            h.putLeShort(22, 2)               // channels
            h.putLeInt(24, 8000)              // sample rate
            h.putLeInt(28, 32000)             // byte rate
            h.putLeShort(32, 4)               // block align
            h.putLeShort(34, 16)              // bits per sample
            "data".toByteArray().copyInto(h, 36)
            h.putLeInt(40, 100)               // declared data size > actual file content
        }
        val file = File(tempFolder.root, "truncated_data.wav")
        file.writeBytes(header + data)

        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open())
        assertEquals(60, reader.readData(ByteArray(100), 0, 100))
        assertEquals(-1, reader.readData(ByteArray(100), 0, 100))
        reader.close()
    }

    @Test
    fun writeThenRead_roundTrip() {
        val file = File(tempFolder.root, "roundtrip.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(48000, 2, 16))
        val data = ByteArray(4096) { (it % 256).toByte() }
        assertTrue(writer.writeAudioData(data, 0, data.size))
        assertTrue(writer.close())
        assertEquals(4096f / 192000f, writer.duration, 0.001f)

        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open())
        assertEquals(48000, reader.sampleRate)
        assertEquals(2, reader.channelCount)
        assertEquals(16, reader.bitsPerSample)
        assertEquals(4096L, reader.dataLength)
        assertEquals(4096f / 192000f, reader.duration, 0.001f)

        val buffer = ByteArray(4096)
        assertEquals(4096, reader.readData(buffer, 0, buffer.size))
        assertArrayEquals(data, buffer)
        reader.close()
    }

    @Test
    fun openFromInputStream() {
        val file = File(tempFolder.root, "stream.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(16000, 1, 16))
        assertTrue(writer.writeAudioData(ByteArray(1600), 0, 1600))
        assertTrue(writer.close())

        val reader = WavFile("stream label")
        assertTrue(reader.open(FileInputStream(file)))
        assertEquals(16000, reader.sampleRate)
        assertEquals(1, reader.channelCount)
        reader.close()
    }

    @Test
    fun invalidFile_openFails() {
        val file = File(tempFolder.root, "bad.wav")
        file.writeText("this is not a wav file at all, just text")
        val reader = WavFile(file.absolutePath)
        assertTrue(!reader.open())
    }

    @Test
    fun readAfterEof_returnsMinusOne() {
        val file = File(tempFolder.root, "eof.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(8000, 1, 16))
        assertTrue(writer.writeAudioData(ByteArray(100), 0, 100))
        assertTrue(writer.close())
        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open())
        assertEquals(100, reader.readData(ByteArray(100), 0, 100))
        assertEquals(-1, reader.readData(ByteArray(100), 0, 100))
        reader.close()
    }

    @Test
    fun multiWrite_accumulatesAndBackfillsTotal() {
        val file = File(tempFolder.root, "multi.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(48000, 2, 16))
        assertTrue(writer.writeAudioData(ByteArray(1000), 0, 1000))
        assertTrue(writer.writeAudioData(ByteArray(2000), 0, 2000))
        assertTrue(writer.writeAudioData(ByteArray(3000), 0, 3000))
        assertTrue(writer.close())
        assertEquals(6000L, writer.dataLength)
        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open())
        assertEquals(6000L, reader.dataLength)
        assertEquals(6000, reader.readData(ByteArray(6000), 0, 6000))
        reader.close()
    }

    @Test
    fun openNonexistentFile_returnsFalse() {
        val reader = WavFile(File(tempFolder.root, "missing.wav").absolutePath)
        assertTrue(!reader.open())
    }

    @Test
    fun closeWriteSideWithNoData_discardsFile() {
        // An aborted session (start failed before any audio, or stop before the first read)
        // leaves only a header-only shell; close must delete it instead of shipping an empty WAV
        val file = File(tempFolder.root, "empty.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(48000, 2, 16))
        assertTrue(file.exists())
        assertTrue(writer.close())
        assertFalse("empty WAV should be discarded, not written", file.exists())
    }

    @Test
    fun createIntoNonexistentDir_createsParents() {
        val file = File(tempFolder.root, "sub/dir/created.wav")
        val writer = WavFile(file.absolutePath)
        assertTrue(writer.create(16000, 1, 16))
        assertTrue(writer.writeAudioData(ByteArray(320), 0, 320))
        assertTrue(writer.close())
        assertTrue(file.exists())
    }

    // Little-endian write helpers for hand-built WAV headers (test only)
    private fun ByteArray.putLeShort(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putLeInt(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    @Test
    fun openExtensibleWav_parsesParameters() {
        // WAVE_FORMAT_EXTENSIBLE: 40-byte fmt + subformat GUID (first 2 bytes = PCM)
        val data = ByteArray(8) { (it + 1).toByte() }
        val header = ByteArray(68).also { h ->
            "RIFF".toByteArray().copyInto(h, 0)
            h.putLeInt(4, 60 + data.size)     // riff size = fileSize - 8
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            h.putLeInt(16, 40)                // fmt chunk size
            h.putLeShort(20, 0xFFFE)          // WAVE_FORMAT_EXTENSIBLE
            h.putLeShort(22, 2)               // channels
            h.putLeInt(24, 96000)             // sample rate
            h.putLeInt(28, 96000 * 2 * 4)     // byte rate
            h.putLeShort(32, 8)               // block align
            h.putLeShort(34, 32)              // bits per sample
            h.putLeShort(36, 22)              // cbSize
            h.putLeShort(38, 32)              // valid bits
            h.putLeInt(40, 0x3)               // channel mask (stereo)
            // KSDATAFORMAT_SUBTYPE_PCM: first 2 bytes 0x0001 = PCM
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
                0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71).copyInto(h, 44)
            "data".toByteArray().copyInto(h, 60)
            h.putLeInt(64, data.size)
        }
        val file = File(tempFolder.root, "extensible.wav")
        file.writeBytes(header + data)

        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open())
        assertEquals(96000, reader.sampleRate)
        assertEquals(2, reader.channelCount)
        assertEquals(32, reader.bitsPerSample)
        assertEquals(data.size.toLong(), reader.dataLength)
        val buf = ByteArray(8)
        assertEquals(8, reader.readData(buf, 0, 8))
        assertArrayEquals(data, buf)
        reader.close()
    }

    @Test
    fun openPcmWithExtraChunk_skipsFact() {
        // Classic PCM + fact chunk: data is not at offset 36; chunk scanning skips over fact
        val data = ByteArray(16) { it.toByte() }
        val header = ByteArray(56).also { h ->
            "RIFF".toByteArray().copyInto(h, 0)
            h.putLeInt(4, 48 + data.size)     // riff size = fileSize - 8
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            h.putLeInt(16, 16)                // fmt chunk size
            h.putLeShort(20, 1)               // PCM
            h.putLeShort(22, 1)               // channels
            h.putLeInt(24, 16000)             // sample rate
            h.putLeInt(28, 32000)             // byte rate
            h.putLeShort(32, 2)               // block align
            h.putLeShort(34, 16)              // bits per sample
            "fact".toByteArray().copyInto(h, 36)
            h.putLeInt(40, 4)
            byteArrayOf(1, 0, 0, 0).copyInto(h, 44)
            "data".toByteArray().copyInto(h, 48)
            h.putLeInt(52, data.size)
        }
        val file = File(tempFolder.root, "fact.wav")
        file.writeBytes(header + data)

        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open())
        assertEquals(16000, reader.sampleRate)
        assertEquals(1, reader.channelCount)
        assertEquals(16, reader.bitsPerSample)
        assertEquals(data.size.toLong(), reader.dataLength)
        assertEquals(16, reader.readData(ByteArray(16), 0, 16))
        reader.close()
    }

    @Test
    fun openWavWithOddSizedChunk_skipsPadByte() {
        // RIFF word alignment: an odd-sized chunk (LIST, 5 bytes) is followed by a 1-byte
        // pad. Without consuming it, chunk scanning shifts by one byte and data is never found
        val data = ByteArray(16) { (it + 1).toByte() }
        val header = ByteArray(58).also { h ->
            "RIFF".toByteArray().copyInto(h, 0)
            h.putLeInt(4, 66)                 // riff size = fileSize - 8
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            h.putLeInt(16, 16)                // fmt chunk size
            h.putLeShort(20, 1)               // PCM
            h.putLeShort(22, 1)               // channels
            h.putLeInt(24, 16000)             // sample rate
            h.putLeInt(28, 32000)             // byte rate
            h.putLeShort(32, 2)               // block align
            h.putLeShort(34, 16)              // bits per sample
            "LIST".toByteArray().copyInto(h, 36)
            h.putLeInt(40, 5)                 // odd-sized chunk body
            // h[44..48]: 5 body bytes, h[49]: pad byte (zero-initialized)
            "data".toByteArray().copyInto(h, 50)
            h.putLeInt(54, 16)
        }
        val file = File(tempFolder.root, "odd_chunk.wav")
        file.writeBytes(header + data)

        val reader = WavFile(file.absolutePath)
        assertTrue(reader.open())
        assertEquals(16L, reader.dataLength)
        val buf = ByteArray(16)
        assertEquals(16, reader.readData(buf, 0, 16))
        assertArrayEquals(data, buf)
        reader.close()
    }

    @Test
    fun writeAudioData_beyondFormatLimit_failsAndCloses() {
        // The 32-bit WAV size fields cap the data chunk; crossing the limit must fail the
        // write loudly (recorder's saveFailed path) instead of silently corrupting the header
        val writer = WavFile(File(tempFolder.root, "limit.wav").absolutePath, maxDataBytes = 1000L)
        assertTrue(writer.create(8000, 2, 16))
        assertTrue(writer.writeAudioData(ByteArray(600), 0, 600))
        assertFalse(writer.writeAudioData(ByteArray(600), 0, 600))   // crosses the limit
        assertFalse(writer.writeAudioData(ByteArray(10), 0, 10))     // stream closed by the guard
        assertEquals(600L, writer.dataLength)
    }

    @Test
    fun openTruncatedExtensible_failsCleanly() {
        // fmt declares 16 bytes but tag=0xFFFE (EXTENSIBLE without the GUID): must fail cleanly rather than throw AIOOBE
        val data = ByteArray(8)
        val header = ByteArray(44).also { h ->
            "RIFF".toByteArray().copyInto(h, 0)
            h.putLeInt(4, 36 + data.size)
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            h.putLeInt(16, 16)                // fmt chunk size
            h.putLeShort(20, 0xFFFE)          // EXTENSIBLE (but fmt is only 16 bytes)
            h.putLeShort(22, 2)
            h.putLeInt(24, 48000)
            h.putLeInt(28, 48000 * 2 * 2)
            h.putLeShort(32, 4)
            h.putLeShort(34, 16)
            "data".toByteArray().copyInto(h, 36)
            h.putLeInt(40, data.size)
        }
        val file = File(tempFolder.root, "truncated_extensible.wav")
        file.writeBytes(header + data)

        val reader = WavFile(file.absolutePath)
        assertTrue(!reader.open())  // Returns false cleanly; no ArrayIndexOutOfBoundsException thrown
    }
}
