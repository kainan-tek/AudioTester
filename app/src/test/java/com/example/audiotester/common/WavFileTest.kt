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
        assertEquals(4096, reader.dataLength)
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
}
