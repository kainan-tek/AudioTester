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

        /** Session token captured by the most recent startLoop() — lets tests grab a stale token before a restart */
        var currentToken: Int = -1
            private set

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

        /** Token visible during initializeAudio: real engines capture focus callbacks here, so
         *  it must already be the token this start commits — else focus-loss stops carry a
         *  stale token and are permanent no-ops */
        var tokenSeenByInitializeAudio: Int = -1
            private set

        override fun initializeAudio(session: Int): Boolean {
            tokenSeenByInitializeAudio = session
            return true
        }
        override fun releaseAudioResources() { releaseCount.incrementAndGet() }

        fun loopError(token: Int, type: AudioErrorType, detail: String) = handleLoopError(token, type, detail)
        fun naturalEnd(token: Int) = stopIfSession(token)

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

        override fun startLoop(session: Int) {
            currentToken = session
            val loop = LoopSession()
            currentLoop = loop
            loopJob = loopScope.launch {
                try {
                    loop.entered.countDown()
                    loop.release.await()   // parked "blocking IO": cancellation does not interrupt it
                    if (loop.fail) throw IOException("loop failure")
                    stopIfSession(session)   // natural end
                } catch (e: Exception) {
                    handleLoopError(session, AudioErrorType.STREAM, "$startupFailedMessage: ${e.message}")
                }
                loop.done.countDown()
            }
        }
    }

    private class RecordingListener : AudioEngine.Listener {
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val errors = mutableListOf<Pair<AudioErrorType, String>>()
        override fun onStarted() { started.incrementAndGet() }
        override fun onStopped() { stopped.incrementAndGet() }
        override fun onError(type: AudioErrorType, detail: String) { errors.add(type to detail) }
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

        engine.loopError(engine.currentToken, AudioErrorType.STREAM, "boom")

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
        engine.startLatch.countDown()
        assertTrue(engine.start())

        engine.loopError(engine.currentToken, AudioErrorType.STREAM, "boom")

        assertEquals(AudioState.ERROR, engine.testState)
        assertEquals(listOf(AudioErrorType.STREAM to "boom"), listener.errors)
        assertEquals(1, engine.releaseCount.get())

        engine.release()   // also parks-and-cancels the loop started above (keeps the test hermetic)
    }

    /**
     * Stale-loop invariant: a loop cancelled by stop() keeps unwinding (blocking IO is not
     * interruptible) and may do so after a newer session committed — it must neither stop the
     * engine nor report its error into the new session.
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

    /**
     * Session-token invariant: a loop-side stop/error carrying a stale session token must be
     * a no-op after a newer session committed — closing the check-then-act window where the
     * stale loop passes the (unlocked) isActive/state guard but stop() lands on the new session.
     */
    @Test
    fun staleTokenNaturalEnd_afterRestart_sparesNewSession() {
        val engine = TestEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.startLatch.countDown()

        assertTrue(engine.start())      // session 1
        val staleToken = engine.currentToken
        engine.stop()
        assertTrue(engine.start())      // session 2 commits
        assertEquals(AudioState.ACTIVE, engine.testState)

        engine.naturalEnd(staleToken)   // stale loop unwinding after the restart

        assertEquals(AudioState.ACTIVE, engine.testState)
        assertEquals(1, listener.stopped.get())   // only session 1's stop
        assertEquals(1, engine.releaseCount.get())

        engine.release()
    }

    @Test
    fun staleTokenLoopError_afterRestart_sparesNewSession() {
        val engine = TestEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        engine.startLatch.countDown()

        assertTrue(engine.start())      // session 1
        val staleToken = engine.currentToken
        engine.stop()
        assertTrue(engine.start())      // session 2 commits
        assertEquals(AudioState.ACTIVE, engine.testState)

        engine.loopError(staleToken, AudioErrorType.STREAM, "boom")

        assertEquals(AudioState.ACTIVE, engine.testState)
        assertTrue(listener.errors.isEmpty())
        assertEquals(1, engine.releaseCount.get())

        engine.release()
    }

    /**
     * Focus-setup invariant: the token visible during initializeAudio() (where real engines
     * capture the focus-loss callback) must equal the token passed to startLoop() — across
     * restarts too, not just the first start.
     */
    @Test
    fun initializeAudio_seesTheSessionTokenGivenToStartLoop() {
        val engine = TestEngine()
        engine.startLatch.countDown()

        assertTrue(engine.start())
        assertEquals(engine.currentToken, engine.tokenSeenByInitializeAudio)

        engine.stop()
        assertTrue(engine.start())
        assertEquals(engine.currentToken, engine.tokenSeenByInitializeAudio)

        engine.release()
    }
}
