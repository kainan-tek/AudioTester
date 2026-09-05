package com.example.audiotester.common

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

/**
 * Stop-during-startup invariant: any stop() that lands before onStarted is processed
 * must still stop the engine and leave the UI in IDLE (no swallowed stop).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioViewModelTest {

    /** Mirrors the real engine contract: stop() is a no-op when idle; start() commits before returning */
    private class FakeEngine : AudioEngine {
        var stopCalled = false
        /** Mirrors the recorder's finalize failure: the error is reported during stop(), before the onStopped confirmation */
        var failRelease = false
        /** Mirrors the engines' start-failure contract: handleError (onError) before returning false */
        var failStart = false
        private var active = false
        private var listener: AudioEngine.Listener? = null

        var currentConfig: AudioConfig = AudioConfig()
            private set

        // Mirrors the real engine contract: reject while ACTIVE, return the effective config
        override fun setAudioConfig(config: AudioConfig): AudioConfig {
            if (!active) currentConfig = config
            return currentConfig
        }
        override fun start(): Boolean {
            if (active) {
                listener?.onError(AudioErrorType.ALREADY_ACTIVE, "Already playing")
                return false
            }
            if (failStart) {
                listener?.onError(AudioErrorType.STREAM, "start failed")
                return false
            }
            active = true
            return true
        }

        override fun stop() {
            if (!active) return
            active = false
            stopCalled = true
            if (failRelease) listener?.onError(AudioErrorType.FINALIZE, "test failure")
            listener?.onStopped()
        }

        override fun release() = stop()
        override fun setListener(listener: AudioEngine.Listener?) { this.listener = listener }

        /** Separate from start(): tests interleave UI dispatch after the engine already committed */
        fun fireOnStarted() = listener?.onStarted()
    }

    @get:Rule
    val instantExecutor = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private lateinit var viewModel: AudioViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        engine = FakeEngine()
        viewModel = AudioViewModel(
            Mockito.mock(Application::class.java),
            engine,
            "player",
            AudioMessages("ready", "preparing", "active", "stopped", "failed"),
            testDispatcher,
        )
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `stop during startup is not swallowed`() = runTest(testDispatcher.scheduler) {
        viewModel.start()
        advanceUntilIdle()      // engine committed; the IO-side stopRequested check already ran
        viewModel.stop()        // lands while _state is still STARTING: only sets the flag
        engine.fireOnStarted()  // onStarted reaches the UI after the stop request

        advanceUntilIdle()

        assertTrue("stop must be forwarded to the engine", engine.stopCalled)
        assertEquals(AudioState.IDLE, viewModel.state.value)
    }

    @Test
    fun `stop requested before start coroutine runs still ends stopped`() = runTest(testDispatcher.scheduler) {
        viewModel.start()
        viewModel.stop()        // flag set before the start coroutine executes
        advanceUntilIdle()
        engine.fireOnStarted()

        advanceUntilIdle()

        assertTrue(engine.stopCalled)
        assertEquals(AudioState.IDLE, viewModel.state.value)
    }

    @Test
    fun `start without stop becomes active`() = runTest(testDispatcher.scheduler) {
        viewModel.start()
        advanceUntilIdle()
        assertEquals(AudioState.STARTING, viewModel.state.value)   // startup window: not yet committed
        engine.fireOnStarted()

        advanceUntilIdle()

        assertEquals(AudioState.ACTIVE, viewModel.state.value)
        assertFalse(engine.stopCalled)
    }

    /** Rejected-while-ACTIVE config changes must never leak into the UI: the write point echoes the engine truth */
    @Test
    fun `config change while engine commits is rejected and converges`() = runTest(testDispatcher.scheduler) {
        val applied = AudioConfig(description = "applied")
        val rejected = AudioConfig(description = "rejected")

        viewModel.setAudioConfig(applied)
        advanceUntilIdle()
        viewModel.start()
        advanceUntilIdle()      // engine committed; onStarted not yet dispatched

        viewModel.setAudioConfig(rejected)
        advanceUntilIdle()

        assertEquals(applied, viewModel.currentConfig.value)
        assertEquals(applied, engine.currentConfig)
    }

    /** A release-time error (e.g. WAV finalization failed) must survive the stop confirmation until the UI observer consumes it */
    @Test
    fun `error during stop is not cleared by the stopped confirmation`() = runTest(testDispatcher.scheduler) {
        engine.failRelease = true
        viewModel.start()
        advanceUntilIdle()
        engine.fireOnStarted()
        advanceUntilIdle()
        viewModel.stop()

        advanceUntilIdle()

        assertEquals(AudioState.ERROR, viewModel.state.value)
        assertEquals(AudioErrorType.FINALIZE, viewModel.errorMessage.value)
    }

    /** Stop during the startup window must not leave the UI holding a config the engine rejected */
    @Test
    fun `stop during startup leaves config consistent with engine`() = runTest(testDispatcher.scheduler) {
        val applied = AudioConfig(description = "applied")
        val rejected = AudioConfig(description = "rejected")

        viewModel.setAudioConfig(applied)
        advanceUntilIdle()
        viewModel.start()
        advanceUntilIdle()
        viewModel.stop()        // startup window: only sets the flag
        viewModel.setAudioConfig(rejected)
        advanceUntilIdle()
        engine.fireOnStarted()  // stopRequested branch stops the engine

        advanceUntilIdle()

        assertEquals(AudioState.IDLE, viewModel.state.value)
        assertEquals(applied, viewModel.currentConfig.value)
        assertEquals(applied, engine.currentConfig)
    }

    /** Foreground failure: the fragment consumes the error (dialog + clearError → IDLE); the
     *  start-failure backstop must not re-enter ERROR and strand a messageless terminal error */
    @Test
    fun `failed start consumed by the UI does not re-enter ERROR`() = runTest(testDispatcher.scheduler) {
        engine.failStart = true
        // Mirror the fragment's consume-on-delivery contract (AudioTestFragment: dialog + clearError)
        viewModel.errorMessage.observeForever { type -> type?.let { viewModel.clearError() } }

        viewModel.start()
        advanceUntilIdle()

        assertEquals(AudioState.IDLE, viewModel.state.value)
        assertEquals(null, viewModel.errorMessage.value)
    }

    /** Backgrounded failure: no active observer consumed the error; it must survive pending until delivery */
    @Test
    fun `failed start not yet consumed keeps the error pending`() = runTest(testDispatcher.scheduler) {
        engine.failStart = true

        viewModel.start()
        advanceUntilIdle()

        assertEquals(AudioState.ERROR, viewModel.state.value)
        assertEquals(AudioErrorType.STREAM, viewModel.errorMessage.value)
    }
}
