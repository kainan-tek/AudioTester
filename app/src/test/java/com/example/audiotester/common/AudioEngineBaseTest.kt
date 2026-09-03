package com.example.audiotester.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch

/**
 * Engine-level concurrency invariants (AudioEngineBase):
 * - release() during an in-flight start() must still fully release the engine (no leak)
 * - start() after release() must be a no-op (no resources created on a dead engine)
 * - a stale (cancelled) loop unwinding after a restart must not act on the new session
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
        val testConfig: AudioConfig get() = currentConfig

        /** Signal so the test can pin release() to finish before start() commits (the leaking order) */
        override fun release() {
            releaseEntered.countDown()
            super.release()
            releaseDone.countDown()
        }

        override val alreadyActiveMessage = "Already active"
        override val permissionDeniedMessage = "Permission denied"
        override val startupFailedMessage = "Start failed"
        override val startedMessage = "Started"

        override fun openResources(): Boolean {
            enteredStart.countDown()
            startLatch.await()
            return startResult
        }

        override fun initializeAudio() = true
        override fun releaseAudioResources() { releaseCount.incrementAndGet() }

        fun forceActive() { state = AudioState.ACTIVE }
        fun loopError(message: String) = handleLoopError(message)

        /** Per-session loop latches, recreated by each startLoop(): lets the test pin and release
         *  one specific session's loop (unwind the stale one while the newer session's loop parks) */
        class LoopSession {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val done = CountDownLatch(1)
            var fail = false
        }

        var currentLoop: LoopSession? = null
            private set

        override fun startLoop() {
            val session = LoopSession()
            currentLoop = session
            loopJob = loopScope.launch {
                try {
                    session.entered.countDown()
                    session.release.await()   // parked "blocking IO": cancellation does not interrupt it
                    if (session.fail) throw IOException("loop failure")
                    stopOnNaturalEnd()   // natural end
                } catch (e: Exception) {
                    reportLoopError("${AudioConstants.ErrorTypes.STREAM} $startupFailedMessage: ${e.message}")
                }
                session.done.countDown()
            }
        }
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
    fun setAudioConfigDuringInFlightStart_isRejected() {
        val engine = TestEngine()
        val startExecutor = Executors.newSingleThreadExecutor()
        startExecutor.submit { engine.start() }
        assertTrue(engine.enteredStart.await(5, TimeUnit.SECONDS))

        // Submitted while start() holds the engine lock: without serialization it lands
        // mid-start (openResources and initializeAudio would see different values); with the
        // fix it parks on the lock until start commits, then is rejected by the ACTIVE guard
        val configExecutor = Executors.newSingleThreadExecutor()
        val configFuture =
            configExecutor.submit { engine.setAudioConfig(AudioConfig(description = "switched")) }
        // Give the config task a window to run: without the fix it applies immediately
        // (mid-start); with the fix it stays parked on the engine lock the whole time
        val appliedEarlyDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        while (engine.testConfig.description != "switched" && System.nanoTime() < appliedEarlyDeadline) {
            Thread.yield()
        }

        engine.startLatch.countDown()
        startExecutor.shutdown()
        assertTrue(startExecutor.awaitTermination(5, TimeUnit.SECONDS))
        configFuture.get(5, TimeUnit.SECONDS)
        configExecutor.shutdown()
        assertTrue(configExecutor.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals("Default Configuration", engine.testConfig.description)
    }

    @Test
    fun setAudioConfigWhenIdle_isApplied() {
        val engine = TestEngine()

        engine.setAudioConfig(AudioConfig(description = "switched"))

        assertEquals("switched", engine.testConfig.description)
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

    /**
     * Stale-loop invariant: a loop cancelled by stop() keeps unwinding (blocking IO is not
     * interruptible) and may do so after a newer session already committed. The stale loop
     * must neither stop the engine nor report its error into the new session.
     */
    @Test
    fun staleLoopError_afterRestart_spareNewSession() {
        val engine = TestEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.startLatch.countDown()   // let start() run through

        assertTrue(engine.start())      // session 1 commits
        val stale = engine.currentLoop!!
        assertTrue(stale.entered.await(5, TimeUnit.SECONDS))
        engine.stop()                   // cancels the loop parked mid-"IO"
        assertEquals(1, engine.releaseCount.get())

        assertTrue(engine.start())      // session 2 commits while the stale loop is still parked
        assertEquals(AudioState.ACTIVE, engine.testState)

        stale.fail = true
        stale.release.countDown()       // stale loop unwinds with an error
        assertTrue(stale.done.await(5, TimeUnit.SECONDS))

        assertEquals(AudioState.ACTIVE, engine.testState)
        assertEquals(1, engine.releaseCount.get())
        assertEquals(1, listener.stopped.get())   // only session 1's stop
        assertTrue(listener.errors.isEmpty())

        engine.release()
    }

    @Test
    fun staleLoopNaturalEnd_afterRestart_spareNewSession() {
        val engine = TestEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.startLatch.countDown()

        assertTrue(engine.start())      // session 1 commits
        val stale = engine.currentLoop!!
        assertTrue(stale.entered.await(5, TimeUnit.SECONDS))
        engine.stop()

        assertTrue(engine.start())      // session 2 commits while the stale loop is still parked
        assertEquals(AudioState.ACTIVE, engine.testState)

        stale.release.countDown()       // stale loop reaches its "natural end"
        assertTrue(stale.done.await(5, TimeUnit.SECONDS))

        assertEquals(AudioState.ACTIVE, engine.testState)
        assertEquals(1, listener.stopped.get())   // only session 1's stop

        engine.release()
    }
}
