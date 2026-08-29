package com.example.audiotester.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream

class WavFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
