package com.example.audiotester.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine-level concurrency invariants (AudioEngineBase):
 * - release() during an in-flight start() must still fully release the engine (no leak)
 * - start() after release() must be a no-op (no resources created on a dead engine)
 */
class AudioEngineBaseTest {

    /** Minimal engine: start() blocks on a latch so tests can interleave release() deterministically */
    private class TestEngine : AudioEngineBase() {
        override val tag = "TestEngine"

        val enteredStart = CountDownLatch(1)
        val startLatch = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val releaseDone = CountDownLatch(1)
        var startResult = true
        val releaseCount = AtomicInteger()

        val testState: AudioState get() = state

        /** Signal so the test can pin release() to finish before start() commits (the leaking order) */
        override fun release() {
            releaseEntered.countDown()
            super.release()
            releaseDone.countDown()
        }

        override fun doStart(): Boolean {
            if (state == AudioState.ACTIVE) {
                engineListener?.onError("Already active")
                return false
            }
            enteredStart.countDown()
            startLatch.await()
            if (!startResult) return false
            state = AudioState.ACTIVE
            engineListener?.onStarted()
            return true
        }

        override fun cancelJob() {}
        override fun cancelScope() {}
        override fun releaseAudioResources() { releaseCount.incrementAndGet() }

        fun forceActive() { state = AudioState.ACTIVE }
        fun loopError(message: String) = handleLoopError(message)
    }

    private class RecordingListener : AudioEngine.Listener {
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val errors = mutableListOf<String>()
        override fun onStarted() { started.incrementAndGet() }
        override fun onStopped() { stopped.incrementAndGet() }
        override fun onError(error: String) { errors.add(error) }
    }

    @Test
    fun releaseDuringInFlightStart_stillReleases() {
        val engine = TestEngine()
        val listener = RecordingListener()
        engine.setListener(listener)

        val startExecutor = Executors.newSingleThreadExecutor()
        val releaseExecutor = Executors.newSingleThreadExecutor()
        startExecutor.submit { engine.start() }
        assertTrue(engine.enteredStart.await(5, TimeUnit.SECONDS))

        // release() lands while start() is in flight. On the unfixed engine it completes
        // while start is still blocked (the leaking order); on the fixed engine it waits for
        // the engine lock held by start(). Give it a moment, then let start() commit either way.
        val releaseFuture = releaseExecutor.submit { engine.release() }
        assertTrue(engine.releaseEntered.await(5, TimeUnit.SECONDS))
        engine.releaseDone.await(1, TimeUnit.SECONDS)
        engine.startLatch.countDown()

        startExecutor.shutdown()
        assertTrue(startExecutor.awaitTermination(5, TimeUnit.SECONDS))
        releaseFuture.get(5, TimeUnit.SECONDS)
        releaseExecutor.shutdown()

        assertEquals(AudioState.IDLE, engine.testState)
        assertEquals(1, engine.releaseCount.get())
    }

    @Test
    fun startAfterRelease_isRejected() {
        val engine = TestEngine()
        engine.startLatch.countDown()   // let start() run through once allowed
        engine.release()

        val result = engine.start()

        assertFalse(result)
        assertEquals(AudioState.IDLE, engine.testState)
        assertEquals(0, engine.releaseCount.get())
    }

    @Test
    fun loopErrorWhenIdle_isIgnored() {
        val engine = TestEngine()
        val listener = RecordingListener()
        engine.setListener(listener)

        engine.loopError("boom")

        assertEquals(AudioState.IDLE, engine.testState)
        assertTrue(listener.errors.isEmpty())
        assertEquals(0, engine.releaseCount.get())
    }

    @Test
    fun loopErrorWhenActive_marksErrorAndReleases() {
        val engine = TestEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.forceActive()

        engine.loopError("boom")

        assertEquals(AudioState.ERROR, engine.testState)
        assertEquals(listOf("boom"), listener.errors)
        assertEquals(1, engine.releaseCount.get())
    }
}
